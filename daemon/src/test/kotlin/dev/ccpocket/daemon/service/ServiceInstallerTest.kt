package dev.ccpocket.daemon.service

import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** removeSiblingAgents enforces a single cc-pocket daemon LaunchAgent: it deletes every other
 *  dev.ccpocket.daemon*.plist while keeping the canonical one and leaving unrelated files alone.
 *  (The best-effort `launchctl unload` it runs is a harmless no-op on these empty temp plists.) */
class ServiceInstallerTest {
    @Test
    fun removesSiblingDaemonAgentsKeepsCanonicalAndUnrelated() {
        val dir = Files.createTempDirectory("launchagents")
        val canonical = dir.resolve("dev.ccpocket.daemon.plist").createFile()
        val devSibling = dir.resolve("dev.ccpocket.daemon.dev.plist").createFile()
        val staleSibling = dir.resolve("dev.ccpocket.daemon.old.plist").createFile()
        val unrelated = dir.resolve("com.apple.something.plist").createFile()

        val removed = ServiceInstaller.removeSiblingAgents(canonical)

        assertEquals(setOf("dev.ccpocket.daemon.dev.plist", "dev.ccpocket.daemon.old.plist"), removed.toSet())
        assertTrue(canonical.exists(), "canonical agent must be kept")
        assertTrue(unrelated.exists(), "unrelated plist must be left alone")
        assertFalse(devSibling.exists(), "dev sibling must be removed")
        assertFalse(staleSibling.exists(), "stale sibling must be removed")
    }

    @Test
    fun noSiblingsReturnsEmptyAndKeepsCanonical() {
        val dir = Files.createTempDirectory("launchagents")
        val canonical = dir.resolve("dev.ccpocket.daemon.plist").createFile()
        assertTrue(ServiceInstaller.removeSiblingAgents(canonical).isEmpty())
        assertTrue(canonical.exists())
    }

    @Test
    fun windowsTaskCanBeRegisteredWithoutStartingDuringUpdateHandoff() {
        val rendered = ServiceInstaller.windows(
            exec = "C:\\Program Files\\cc-pocket\\cc-pocket-daemon.exe",
            args = listOf("run", "--pair-port", "8799"),
            apply = false,
            startNow = false,
        )

        assertTrue("Register-ScheduledTask" in rendered)
        assertFalse("Start-ScheduledTask" in rendered)
    }

    @Test
    fun normalWindowsServiceInstallStillStartsImmediately() {
        val rendered = ServiceInstaller.windows(
            exec = "C:\\Program Files\\cc-pocket\\cc-pocket-daemon.exe",
            args = listOf("run"),
            apply = false,
            startNow = true,
        )

        assertTrue("Start-ScheduledTask" in rendered)
    }
}
