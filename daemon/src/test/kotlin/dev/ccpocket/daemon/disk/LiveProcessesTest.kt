package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProcessesTest {

    @Test
    fun codex_process_match_is_exact_and_platform_separator_agnostic() {
        assertTrue(LiveProcesses.isCodexExecutable("/opt/homebrew/bin/codex"))
        assertTrue(LiveProcesses.isCodexExecutable("Codex"))
        assertTrue(LiveProcesses.isCodexExecutable("C:\\tools\\codex.exe"))

        assertFalse(LiveProcesses.isCodexExecutable("/opt/homebrew/bin/codex-code-mode-host"))
        assertFalse(LiveProcesses.isCodexExecutable("/Applications/Codex.app/Contents/MacOS/Codex Helper"))
        assertFalse(LiveProcesses.isCodexExecutable("node"))
    }
}
