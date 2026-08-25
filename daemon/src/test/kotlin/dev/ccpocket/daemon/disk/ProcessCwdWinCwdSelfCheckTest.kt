package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification for the BLIND Windows cwd probe (issue #302). [ProcessCwd] was ported without a
 * Windows machine to test on, so the PEB offsets are unproven until a real windows-latest runner runs
 * this — the "have CI/user feedback surface it" half of "盲修先行，有反馈再精修".
 *
 * On non-Windows the probe returns null by design (lsof handles those platforms), so these are no-ops
 * — the assertions only bite on the Windows CI job (`*WinCwd*`).
 */
class ProcessCwdWinCwdSelfCheckTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun reading_our_own_cwd_matches_user_dir_on_windows() {
        val result = ProcessCwd.selfCheck()
        if (!isWindows) {
            assertEquals(null, result, "non-Windows must opt out of the PEB read (null), leaving lsof in charge")
            return
        }
        // On a real Windows runner this MUST hold, or the blind PEB offsets are wrong for this NT build
        // and every external-writer verdict on Windows must stay UNKNOWN (never ABSENT).
        assertTrue(result == true, "ProcessCwd read our own cwd but it did not match user.dir — offsets are off")
    }

    @Test
    fun non_windows_never_reads_a_cwd() {
        if (isWindows) return
        assertEquals(null, ProcessCwd.of(ProcessHandle.current().pid()))
    }
}
