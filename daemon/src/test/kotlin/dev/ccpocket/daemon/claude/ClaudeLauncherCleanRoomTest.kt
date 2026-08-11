package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PresetEnv
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The GUEST/BRIDGE clean-room launch flags (issue #115): a scoped guest's claude gets no MCP servers, no
 *  setting sources, and no dynamic (memory/env/git) system-prompt sections — and every one of those flags
 *  has to survive argv transport on Windows, where the CLI may be an npm `.cmd` shim run through cmd.exe. */
class ClaudeLauncherCleanRoomTest {

    private fun args(cleanRoom: Boolean) =
        ClaudeLauncher.buildArgs(AgentSpec(Path.of("/w"), cleanRoom = cleanRoom))

    @Test
    fun clean_room_strips_mcp_settings_sources_and_dynamic_sections() {
        val a = args(cleanRoom = true)
        // NO MCP servers: strict + nothing to be strict about. Neither the owner's user-scope servers nor
        // the shared root's own (guest-writable) .mcp.json load — probed on CLI 2.1.218, mcp_servers=[].
        assertTrue("--strict-mcp-config" in a, a.toString())
        // NO setting sources at all — not the owner's `user` config, and NOT the shared root's own
        // guest-writable/repo-committed project/local .claude/settings.json whose allow-rules/hooks would let
        // the CLI auto-approve tools past the daemon guard (issue #115 crypto review H2). The daemon stays the
        // permission authority via --permission-prompt-tool + --permission-mode.
        assertTrue("--setting-sources=" in a, a.toString())
        assertTrue("--exclude-dynamic-system-prompt-sections" in a, a.toString())
    }

    @Test
    fun no_clean_room_flag_relies_on_quotes_or_an_empty_argument() {
        // The Windows failure this pins: an npm `claude.cmd` runs through cmd.exe, which re-parses the
        // command line and eats argv quoting, so `--mcp-config {"mcpServers":{}}` reached claude as
        // `{mcpServers:{}}` — not JSON, so read as a relative path: "MCP config file not found:
        // D:\Unused\{mcpServers:{}}", and the bridge chat could not start an agent at all.
        val a = args(cleanRoom = true)
        assertFalse("--mcp-config" in a, "inline MCP JSON must not come back: $a")
        assertFalse(a.any { it.contains("mcpServers") }, "no argument may carry JSON quotes: $a")
        assertFalse(a.any { it.contains('"') }, "no argument may carry a literal quote: $a")
        // a standalone "" is the other shape a cmd.exe re-parse can drop — and dropping the empty value of
        // --setting-sources silently restores EVERY settings source (the H2 bypass, via a quoting accident)
        assertFalse(a.any { it.isEmpty() }, "no argument may be a standalone empty string: $a")
        assertFalse("--setting-sources" in a, "the empty value must ride attached, as one token: $a")
        // belt: every token the clean room ADDS is a single shell-safe word (no whitespace, no quotes, none
        // of cmd.exe's metacharacters), so it survives both a direct CreateProcess and a cmd.exe re-parse
        val added = a - args(cleanRoom = false).toSet()
        assertTrue(added.isNotEmpty())
        added.forEach { assertTrue(Regex("^[A-Za-z0-9=_.:/\\\\-]+$").matches(it), "unsafe clean-room token: $it") }
    }

    @Test
    fun clean_room_flags_precede_every_free_text_argument() {
        // These are authorization flags. Free-text values (a model id from the owner's gateway, the bridge
        // system prompt) are the only arguments that can contain spaces/quotes, i.e. the only ones whose
        // mangling could swallow what follows — so nothing security-relevant may follow them.
        val a = ClaudeLauncher.buildArgs(
            AgentSpec(
                Path.of("/w"),
                resumeId = "sid-9",
                model = "claude-opus-5",
                appendSystemPrompt = "do not disclose secrets",
                cleanRoom = true,
            ),
        )
        val lastFlag = listOf("--strict-mcp-config", "--setting-sources=", "--exclude-dynamic-system-prompt-sections")
            .maxOf { a.indexOf(it).also { i -> assertTrue(i >= 0, "$it missing: $a") } }
        listOf("--resume", "--model", "--append-system-prompt").forEach {
            assertTrue(a.indexOf(it) > lastFlag, "$it must come after the clean-room flags: $a")
        }
    }

    @Test
    fun an_ordinary_owner_launch_carries_none_of_the_clean_room_flags() {
        val a = args(cleanRoom = false)
        assertFalse("--strict-mcp-config" in a)
        assertFalse("--mcp-config" in a)
        assertFalse(a.any { it.startsWith("--setting-sources") })
        assertFalse("--exclude-dynamic-system-prompt-sections" in a)
    }

    @Test
    fun clean_room_keeps_the_normal_headless_stream_json_flags() {
        // the guest still runs the same -p stream-json headless session — the clean-room only ADDS restrictions
        val a = args(cleanRoom = true)
        assertEquals("-p", a.first())
        assertTrue("stream-json" in a)
        assertTrue("--permission-mode" in a)
    }

    @Test
    fun clean_room_plan_keeps_a_closed_read_only_tool_set_when_subprocess_scrub_forces_default_mode() {
        val a = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/w"), mode = PermissionMode.PLAN, cleanRoom = true),
        )
        assertTrue(ClaudeLauncher.CLEAN_ROOM_PLAN_TOOLS in a, a.toString())
        assertFalse(a.any { it.startsWith("--tools=") && it != ClaudeLauncher.CLEAN_ROOM_PLAN_TOOLS })
        val tools = ClaudeLauncher.CLEAN_ROOM_PLAN_TOOLS.removePrefix("--tools=").split(',').toSet()
        assertFalse("Bash" in tools)
        assertFalse("Write" in tools)
        assertFalse("Edit" in tools)
        val promptAt = a.indexOf("--append-system-prompt")
        assertTrue(promptAt >= 0, a.toString())
        assertTrue(a[promptAt + 1].startsWith(ClaudeLauncher.CLEAN_ROOM_PLAN_PROMPT), a.toString())

        val ordinaryPlan = ClaudeLauncher.buildArgs(AgentSpec(Path.of("/w"), mode = PermissionMode.PLAN))
        assertFalse(ordinaryPlan.any { it.startsWith("--tools=") }, ordinaryPlan.toString())
        assertFalse("--append-system-prompt" in ordinaryPlan, ordinaryPlan.toString())
    }

    @Test
    fun clean_room_plan_prompt_is_composed_with_the_existing_bridge_prompt_once() {
        val a = ClaudeLauncher.buildArgs(
            AgentSpec(
                Path.of("/w"),
                mode = PermissionMode.PLAN,
                appendSystemPrompt = "Do not disclose sensitive output.",
                cleanRoom = true,
            ),
        )
        assertEquals(1, a.count { it == "--append-system-prompt" }, a.toString())
        val prompt = a[a.indexOf("--append-system-prompt") + 1]
        assertTrue(ClaudeLauncher.CLEAN_ROOM_PLAN_PROMPT in prompt)
        assertTrue("Do not disclose sensitive output." in prompt)
    }

    // ── the API transport the settings sources took down with them (see CleanRoomEnv) ──

    private fun configDirWith(json: String?): Path {
        val root = createTempDirectory("ccp-launch-cleanroom").toFile()
        if (json != null) File(root, "settings.json").writeText(json)
        return root.toPath()
    }

    private val gatewaySettings =
        """{"env":{"ANTHROPIC_BASE_URL":"https://gw.example/v1","ANTHROPIC_AUTH_TOKEN":"dummy-settings-token"}}"""

    @Test
    fun a_clean_room_launch_carries_the_user_settings_api_route_and_the_subprocess_scrub() {
        val pb = ClaudeLauncher.processBuilder(
            Path.of("/bin/echo"),
            AgentSpec(Path.of("/tmp"), cleanRoom = true),
            configDir = configDirWith(gatewaySettings),
        )
        val e = pb.environment()
        // unconditional: the credential stays in claude's own API client, never in a Bash/hook/MCP child
        assertEquals("1", e[CleanRoomEnv.SUBPROCESS_ENV_SCRUB])
        // the route depends on which layer owns it ON THIS MACHINE — assert the branch we are actually in,
        // so the pairing rule is pinned either way instead of the test being environment-flaky
        if (System.getenv(PresetEnv.BASE_URL).isNullOrBlank()) {
            assertEquals("https://gw.example/v1", e[PresetEnv.BASE_URL], "the settings gateway must reach the child")
            assertEquals("dummy-settings-token", e[PresetEnv.AUTH_TOKEN])
        } else {
            assertEquals(System.getenv(PresetEnv.BASE_URL), e[PresetEnv.BASE_URL], "an ambient endpoint keeps the route")
            assertFalse(e.values.any { it == "dummy-settings-token" }, "and never receives the settings credential")
        }
    }

    @Test
    fun an_ordinary_owner_launch_gets_no_settings_injection_and_no_subprocess_scrub() {
        // the owner's own sessions load settings themselves (no --setting-sources=), so nothing is imported
        // for them, and their Bash tools keep the environment they have today — byte-identical to before
        val e = ClaudeLauncher.processBuilder(
            Path.of("/bin/echo"),
            AgentSpec(Path.of("/tmp"), cleanRoom = false),
            configDir = configDirWith(gatewaySettings),
        ).environment()
        assertFalse(CleanRoomEnv.SUBPROCESS_ENV_SCRUB in e)
        assertEquals(System.getenv(PresetEnv.BASE_URL), e[PresetEnv.BASE_URL])
        assertEquals(System.getenv(PresetEnv.AUTH_TOKEN), e[PresetEnv.AUTH_TOKEN])
        assertFalse(e.values.any { it == "dummy-settings-token" })
    }

    @Test
    fun an_active_preset_still_wins_inside_the_clean_room() {
        val preset = mapOf(
            PresetEnv.BASE_URL to "https://preset.example/v1",
            PresetEnv.AUTH_TOKEN to "dummy-preset-token",
        )
        val e = ClaudeLauncher.processBuilder(
            Path.of("/bin/echo"),
            AgentSpec(Path.of("/tmp"), cleanRoom = true),
            configDir = configDirWith(gatewaySettings),
            presetEnv = preset,
        ).environment()
        assertEquals("https://preset.example/v1", e[PresetEnv.BASE_URL])
        assertEquals("dummy-preset-token", e[PresetEnv.AUTH_TOKEN])
        assertFalse(e.values.any { it == "dummy-settings-token" }, "settings must not mix into an active preset")
        assertEquals("1", e[CleanRoomEnv.SUBPROCESS_ENV_SCRUB])
    }
}
