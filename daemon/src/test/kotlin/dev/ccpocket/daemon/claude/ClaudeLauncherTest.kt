package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.PermissionMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeLauncherTest {

    @Test
    fun args_always_include_p_and_stream_flags_and_explicit_default_mode() {
        val args = ClaudeLauncher.buildArgs(ClaudeSpec(Path.of("/x")))
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
            ClaudeSpec(Path.of("/x"), resumeId = "sid-9", mode = PermissionMode.PLAN),
        )
        assertEquals("plan", args[args.indexOf("--permission-mode") + 1])
        assertEquals("sid-9", args[args.indexOf("--resume") + 1])
    }

    @Test
    fun fork_session_added_only_when_forking_a_resume() {
        val forked = ClaudeLauncher.buildArgs(
            ClaudeSpec(Path.of("/x"), resumeId = "sid-9", forkSession = true),
        )
        assertTrue("--fork-session" in forked)
        assertEquals("sid-9", forked[forked.indexOf("--resume") + 1])

        // plain resume (the relaunch-in-place case) must NOT fork — that would mint a new id every switch
        val plain = ClaudeLauncher.buildArgs(
            ClaudeSpec(Path.of("/x"), resumeId = "sid-9", forkSession = false),
        )
        assertFalse("--fork-session" in plain)

        // fork is a no-op without a resume id — never emit --fork-session with nothing to fork
        val noResume = ClaudeLauncher.buildArgs(ClaudeSpec(Path.of("/x"), forkSession = true))
        assertFalse("--fork-session" in noResume)
    }

    @Test
    fun bypass_mode_serializes_to_cli_name() {
        assertEquals("bypassPermissions", PermissionMode.BYPASS_PERMISSIONS.wireName())
    }
}
