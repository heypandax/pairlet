package dev.ccpocket.daemon.testsupport

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.approval.ApprovalHistoryStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.schedule.ScheduleStore
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests used to construct `DaemonCore(emptyMap())` and friends against the developer's REAL `~/.cc-pocket`
 * (schedules.json, prefs.json, approval history, the spawned-session journal, …). The daemon test task now
 * runs with `user.home` = `build/test-home`, and [RealHomeGuard] refuses to start a test otherwise.
 */
class RealHomeGuardTest {

    @Test
    fun `this test JVM resolves every daemon store under the throwaway home`() {
        assertEquals(emptyList(), RealHomeGuard.violations())
        val home = File(System.getProperty("user.home")).absoluteFile
        assertTrue(home.path.replace('\\', '/').endsWith("build/test-home"), "user.home = $home")
        for (f in listOf(
            Identity.defaultPath(),
            ScheduleStore.defaultPath(),
            DaemonPrefs.defaultPath(),
            ApprovalHistoryStore.defaultPath(),
        )) {
            assertTrue(f.absoluteFile.startsWith(home), "$f escapes the test home $home")
        }
    }

    @Test
    fun `the guard flags the real home and its daemon state directories`() {
        val real = setOf(Path.of("/Users/owner"))
        val bad = RealHomeGuard.violations(
            real,
            listOf(
                "user.home" to File("/Users/owner"),
                "identity" to File("/Users/owner/.cc-pocket"),
                "rebranded" to File("/Users/owner/.pairlet/sub"),
            ),
        )
        assertEquals(3, bad.size, bad.joinToString())
        // a checkout that happens to live under the real home is fine — only its state dirs are off limits
        assertEquals(
            emptyList(),
            RealHomeGuard.violations(
                real,
                listOf(
                    "user.home" to File("/Users/owner/Desktop/Pairlet/daemon/build/test-home"),
                    "identity" to File("/Users/owner/Desktop/Pairlet/daemon/build/test-home/.cc-pocket"),
                ),
            ),
        )
    }

    @Test
    fun `the real home is read from the OS environment, not from user dot home`() {
        val homes = RealHomeGuard.realHomes { mapOf("HOME" to "/Users/owner", "USERPROFILE" to "")[it] }
        assertEquals(setOf(Path.of("/Users/owner")), homes)
    }
}
