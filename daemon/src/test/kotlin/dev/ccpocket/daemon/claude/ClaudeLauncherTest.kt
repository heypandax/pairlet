package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PresetEnv
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeLauncherTest {

    @Test
    fun args_always_include_p_and_stream_flags_and_explicit_default_mode() {
        val args = ClaudeLauncher.buildArgs(AgentSpec(Path.of("/x")))
        assertTrue("-p" in args)
        assertTrue(
            args.containsAll(
                listOf(
                    "--output-format", "stream-json",
                    "--input-format", "stream-json",
                    "--permission-prompt-tool", "stdio",
                    "--replay-user-messages", "--verbose",
                ),
            ),
        )
        // default must be passed EXPLICITLY — omitted, claude falls back to the user's global
        // permissions.defaultMode (e.g. "auto") and the phone's "Ask each step" silently lies
        assertEquals("default", args[args.indexOf("--permission-mode") + 1])
        assertFalse("--resume" in args)
    }

    @Test
    fun mode_and_resume_added_when_set() {
        val args = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/x"), resumeId = "sid-9", mode = PermissionMode.PLAN),
        )
        assertEquals("plan", args[args.indexOf("--permission-mode") + 1])
        assertEquals("sid-9", args[args.indexOf("--resume") + 1])
    }

    @Test
    fun native_auto_mode_and_max_effort_are_forwarded_verbatim() {
        val args = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/x"), permissionMode = "auto", effort = "max"),
        )
        assertEquals("auto", args[args.indexOf("--permission-mode") + 1])
        assertEquals("max", args[args.indexOf("--effort") + 1])
    }

    @Test
    fun fork_session_added_only_when_forking_a_resume() {
        val forked = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/x"), resumeId = "sid-9", forkSession = true),
        )
        assertTrue("--fork-session" in forked)
        assertEquals("sid-9", forked[forked.indexOf("--resume") + 1])

        // plain resume (the relaunch-in-place case) must NOT fork — that would mint a new id every switch
        val plain = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/x"), resumeId = "sid-9", forkSession = false),
        )
        assertFalse("--fork-session" in plain)

        // fork is a no-op without a resume id — never emit --fork-session with nothing to fork
        val noResume = ClaudeLauncher.buildArgs(AgentSpec(Path.of("/x"), forkSession = true))
        assertFalse("--fork-session" in noResume)
    }

    @Test
    fun rewind_flags_ride_the_resume_block_and_never_appear_alone() {
        // issue #282. The two flags are `.hideHelp()`-hidden on the CLI but are exactly what the Agent
        // SDK's resumeSessionAt compiles to; scripts/probe-claude-wire.py `scenario_rewind` asserts they
        // still exist on every upgrade, and this asserts we spell them the way it proved works.
        val args = ClaudeLauncher.buildArgs(
            AgentSpec(
                Path.of("/x"), resumeId = "sid-9", forkSession = true,
                resumeSessionAt = "anchor-uuid", resumeDropsTurn = "dropped-uuid",
            ),
        )
        assertEquals("anchor-uuid", args[args.indexOf("--resume-session-at") + 1])
        assertEquals("dropped-uuid", args[args.indexOf("--resume-drops-turn") + 1])
        // ordering is load-bearing: --resume-session-at must follow --resume (there is nothing to
        // truncate without one), and the free-text values sit at the tail so a mangled command line can
        // only ever damage them and never the flags ahead of them
        assertTrue(args.indexOf("--resume") < args.indexOf("--resume-session-at"))
        assertTrue(args.indexOf("--fork-session") < args.indexOf("--resume-session-at"))

        // truncation without a resume is meaningless — and emitting it bare would make the CLI reject
        // the launch outright
        val noResume = ClaudeLauncher.buildArgs(AgentSpec(Path.of("/x"), resumeSessionAt = "anchor-uuid"))
        assertFalse("--resume-session-at" in noResume)

        // the guard rail is nested one level deeper: alone it has no meaning, and a multi-turn cut
        // deliberately omits it (declaring one turn while dropping several is always rejected)
        val multiTurn = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/x"), resumeId = "sid-9", forkSession = true, resumeSessionAt = "anchor-uuid"),
        )
        assertTrue("--resume-session-at" in multiTurn)
        assertFalse("--resume-drops-turn" in multiTurn)

        val dropsOnly = ClaudeLauncher.buildArgs(
            AgentSpec(Path.of("/x"), resumeId = "sid-9", resumeDropsTurn = "dropped-uuid"),
        )
        assertFalse("--resume-drops-turn" in dropsOnly)

        // an ordinary launch is byte-for-byte what it was
        val plain = ClaudeLauncher.buildArgs(AgentSpec(Path.of("/x"), resumeId = "sid-9"))
        assertFalse("--resume-session-at" in plain)
        assertFalse("--resume-drops-turn" in plain)
    }

    @Test
    fun bypass_mode_serializes_to_cli_name() {
        assertEquals("bypassPermissions", PermissionMode.BYPASS_PERMISSIONS.wireName())
    }

    // ── API preset env injection (issue #113) ────────────────────────────

    @Test
    fun preset_env_scrubs_every_owned_var_then_injects() {
        // stale daemon env: an exported API key + model + a CUSTOM_HEADERS carrying a bearer credential
        // bound to the computer's OWN endpoint — none may survive into a preset launch
        val env = mutableMapOf(
            PresetEnv.API_KEY to "sk-stale-from-daemon-env",
            PresetEnv.MODEL to "claude-sonnet-4-5",
            PresetEnv.CUSTOM_HEADERS to "Authorization: Bearer sk-real-cred-for-another-endpoint",
            "PATH" to "/usr/bin",
        )
        ClaudeLauncher.applyPresetEnv(
            env,
            mapOf(
                PresetEnv.BASE_URL to "https://api.example-proxy.com/v1",
                PresetEnv.AUTH_TOKEN to "sk-proxy-9f2a4c8e3f9a",
            ),
        )
        assertEquals("https://api.example-proxy.com/v1", env[PresetEnv.BASE_URL])
        assertEquals("sk-proxy-9f2a4c8e3f9a", env[PresetEnv.AUTH_TOKEN])
        // the stale key/model are GONE — they'd otherwise fight the preset's token for CLI precedence
        assertFalse(PresetEnv.API_KEY in env)
        assertFalse(PresetEnv.MODEL in env)
        // and the bearer credential is GONE — leaving it would ship it to the preset's third-party base
        // URL (cross-endpoint secret leak). Belt: the credential string is nowhere in the resulting env.
        assertFalse(PresetEnv.CUSTOM_HEADERS in env)
        assertFalse(env.values.any { it.contains("sk-real-cred-for-another-endpoint") })
        assertEquals("/usr/bin", env["PATH"]) // everything else passes through
    }

    @Test
    fun process_builder_injects_preset_env_only_when_one_is_active() {
        val preset = mapOf(
            PresetEnv.BASE_URL to "https://api.example-proxy.com/v1",
            PresetEnv.API_KEY to "sk-proxy-9f2a4c8e3f9a",
            PresetEnv.MODEL to "gpt-4o",
        )
        val with = ClaudeLauncher.processBuilder(Path.of("/bin/echo"), AgentSpec(Path.of("/tmp")), presetEnv = preset)
        assertEquals("https://api.example-proxy.com/v1", with.environment()[PresetEnv.BASE_URL])
        assertEquals("sk-proxy-9f2a4c8e3f9a", with.environment()[PresetEnv.API_KEY])
        assertEquals("gpt-4o", with.environment()[PresetEnv.MODEL])
        assertFalse(PresetEnv.AUTH_TOKEN in with.environment())

        // no active preset (null) = the inherited environment passes through untouched — existing
        // API-key users' launches stay byte-identical to today's
        val without = ClaudeLauncher.processBuilder(Path.of("/bin/echo"), AgentSpec(Path.of("/tmp")))
        assertEquals(System.getenv(PresetEnv.BASE_URL), without.environment()[PresetEnv.BASE_URL])
        assertEquals(System.getenv(PresetEnv.API_KEY), without.environment()[PresetEnv.API_KEY])
    }
}
