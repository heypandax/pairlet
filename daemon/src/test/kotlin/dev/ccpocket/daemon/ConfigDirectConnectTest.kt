package dev.ccpocket.daemon

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `pairlet config --direct-connect` against a temp prefs file — never the real ~/.cc-pocket. */
class ConfigDirectConnectTest {

    @Test
    fun `lan is saved, explained, and comes with the restart command`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        val r = ConfigCmd(file, osName = "Mac OS X").test(listOf("--direct-connect", "lan"))
        assertEquals(0, r.statusCode, r.output)
        assertEquals(DirectConnectMode.LAN, DaemonPrefs.load(file).directConnect)
        val out = r.output
        assertTrue("direct-connect: lan" in out, out)
        // the security note: which port, who can reach it, who gets through, how to close it, the macOS prompt
        assertTrue("TCP port 8765" in out && "every network" in out, out)
        assertTrue("paired to this computer" in out && "refused during the handshake" in out, out)
        assertTrue("pairlet config --direct-connect local" in out && "--direct-connect off" in out, out)
        assertTrue("macOS may ask" in out, out)
        // restart, the service way — never a foreground `pairlet run`
        assertTrue("restart the daemon for this to take effect" in out, out)
        assertTrue("launchctl kickstart -k gui/\$(id -u)/dev.ccpocket.daemon" in out, out)
        assertFalse("pairlet run" in out, out)
    }

    @Test
    fun `restart command follows the platform`(@TempDir dir: File) {
        val linux = ConfigCmd(File(dir, "a.json"), osName = "Linux").test(listOf("--direct-connect", "lan")).output
        assertTrue("systemctl --user restart cc-pocket-daemon" in linux, linux)
        val win = ConfigCmd(File(dir, "b.json"), osName = "Windows 11").test(listOf("--direct-connect", "lan")).output
        assertTrue("Start-ScheduledTask -TaskName cc-pocket-daemon" in win, win)
    }

    @Test
    fun `local and off are saved without the LAN note`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        val local = ConfigCmd(file, osName = "Linux").test(listOf("--direct-connect", "local"))
        assertEquals(DirectConnectMode.LOCAL, DaemonPrefs.load(file).directConnect)
        assertFalse("what this opens" in local.output, local.output)
        assertTrue("restart the daemon" in local.output, local.output)

        val off = ConfigCmd(file, osName = "Linux").test(listOf("--direct-connect", "OFF"))
        assertEquals(DirectConnectMode.OFF, DaemonPrefs.load(file).directConnect)
        assertTrue("direct-connect: off" in off.output, off.output)
        assertFalse("what this opens" in off.output, off.output)
    }

    @Test
    fun `default clears the setting`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.LAN)
        val r = ConfigCmd(file, osName = "Linux").test(listOf("--direct-connect", "default"))
        assertNull(DaemonPrefs.load(file).directConnect)
        assertTrue("direct-connect: local (default)" in r.output, r.output)
    }

    @Test
    fun `an unknown value is refused and nothing is saved`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        val r = ConfigCmd(file, osName = "Linux").test(listOf("--direct-connect", "0.0.0.0"))
        assertTrue(r.statusCode != 0, r.output)
        assertTrue("local|lan|off" in r.output, r.output)
        assertNull(DaemonPrefs.load(file).directConnect)
    }

    @Test
    fun `plain config shows the setting without a restart prompt`(@TempDir dir: File) {
        val r = ConfigCmd(File(dir, "prefs.json"), osName = "Linux").test(emptyList())
        assertTrue("direct-connect: local (default)" in r.output, r.output)
        assertFalse("restart the daemon" in r.output, r.output)
    }
}
