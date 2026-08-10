package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentSpec
import java.nio.file.Path
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
}
