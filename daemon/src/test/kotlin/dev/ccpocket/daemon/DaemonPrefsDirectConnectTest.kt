package dev.ccpocket.daemon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * `config --direct-connect`: a service-managed daemon is a bare `run`, so this persisted setting is the only
 * way it can open the E2E direct listener to the LAN. Every path that is not an explicit, valid choice must
 * land on the safe default (local = 127.0.0.1).
 */
class DaemonPrefsDirectConnectTest {

    private fun bindFor(file: File) = DirectConnect.resolveBind(flag = null, pref = DaemonPrefs.load(file).directConnect).bind

    @Test
    fun `unset by default, which resolves to this computer only`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        assertNull(DaemonPrefs.load(file).directConnect)
        assertEquals("127.0.0.1", bindFor(file))
    }

    @Test
    fun `each mode is read back after a reload and resolves to its bind`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.LAN)
        assertEquals(DirectConnectMode.LAN, DaemonPrefs.load(file).directConnect)
        assertEquals("0.0.0.0", bindFor(file))

        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.OFF)
        assertEquals(DirectConnectMode.OFF, DaemonPrefs.load(file).directConnect)
        assertEquals("none", bindFor(file))

        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.LOCAL)
        assertEquals(DirectConnectMode.LOCAL, DaemonPrefs.load(file).directConnect)
        assertEquals("127.0.0.1", bindFor(file))
    }

    @Test
    fun `clearing returns to the default`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.LAN)
        DaemonPrefs.load(file).setDirectConnect(null)
        assertNull(DaemonPrefs.load(file).directConnect)
        assertEquals("127.0.0.1", bindFor(file))
    }

    @Test
    fun `stored with the same lowercase words the command takes`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.LAN)
        assert("\"directConnect\":\"lan\"" in file.readText()) { file.readText() }
    }

    @Test
    fun `writing another pref keeps the setting, and setting it keeps the others`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setAutoUpdate(false)
        DaemonPrefs.load(file).setDshBin("/opt/dsh")
        DaemonPrefs.load(file).setDirectConnect(DirectConnectMode.LAN)
        DaemonPrefs.load(file).setPushEnabled(false)
        val reloaded = DaemonPrefs.load(file)
        assertEquals(DirectConnectMode.LAN, reloaded.directConnect)
        assertEquals(false, reloaded.autoUpdate)
        assertEquals("/opt/dsh", reloaded.dshBin)
        assertFalse(reloaded.pushEnabled)
    }

    @Test
    fun `no prefs file means local`(@TempDir dir: File) {
        val file = File(dir, "missing/prefs.json")
        assertNull(DaemonPrefs.load(file).directConnect)
        assertEquals("127.0.0.1", bindFor(file))
    }

    @Test
    fun `a corrupt prefs file means local`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        file.writeText("""{"directConnect":"lan", this is not json""")
        assertNull(DaemonPrefs.load(file).directConnect)
        assertEquals("127.0.0.1", bindFor(file))
    }

    @Test
    fun `an unknown stored value means local, not an error`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        file.writeText("""{"pushEnabled":false,"directConnect":"0.0.0.0"}""")
        val prefs = DaemonPrefs.load(file)
        assertNull(prefs.directConnect)
        assertFalse(prefs.pushEnabled, "the rest of the file still loads")
        assertEquals("127.0.0.1", bindFor(file))
    }

    @Test
    fun `a prefs file written before this field existed still loads`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        file.writeText("""{"pushEnabled":false,"autoUpdate":false}""")
        val prefs = DaemonPrefs.load(file)
        assertNull(prefs.directConnect)
        assertEquals(false, prefs.autoUpdate)
    }
}
