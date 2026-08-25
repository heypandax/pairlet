package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The external-writer probes identify agents by command path; a matcher blind to one platform's
 *  separators silently converts UNKNOWN ("can't tell — stay safe") into ABSENT ("no writer — go ahead"),
 *  which is a two-writer clobber, not a cosmetic miss (PR #296 re-review). */
class LiveProcessesMatcherTest {

    @Test
    fun claude_matcher_sees_both_separator_conventions() {
        assertTrue(LiveProcesses.isClaudeCommand("/Users/x/.local/bin/claude"))
        assertTrue(LiveProcesses.isClaudeCommand("/opt/share/claude/versions/2.1.0"))
        assertTrue(LiveProcesses.isClaudeCommand("""C:\Users\x\AppData\Local\Programs\claude.exe"""))
        assertFalse(LiveProcesses.isClaudeCommand("/usr/bin/node")) // unrelated hosts stay invisible
    }

    @Test
    fun codex_matcher_is_exact_basename_on_both_platforms() {
        assertTrue(LiveProcesses.isCodexExecutable("/Users/x/.bun/bin/codex"))
        assertTrue(LiveProcesses.isCodexExecutable("""C:\tools\codex.exe"""))
        assertFalse(LiveProcesses.isCodexExecutable("/opt/codex-code-mode-host")) // helpers must not count
    }
}
