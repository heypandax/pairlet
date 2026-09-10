package dev.ccpocket.daemon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Issue #365: the pinned `dsh` path has to SURVIVE — that is the whole reason it lives in prefs.json
 * rather than in a `run --dsh-bin` flag. An npx-only install leaves no `dsh` file for the resolver to
 * find, and a service-managed daemon is restarted by launchd/systemd with whatever argv `service-install`
 * baked in, so a pin that did not persist would silently evaporate on the next restart or daemon update.
 */
class DaemonPrefsDshBinTest {

    @Test
    fun `a pinned dsh path is read back after a reload`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        assertNull(DaemonPrefs.load(file).dshBin) // unset by default: auto-detection stays the norm
        DaemonPrefs.load(file).setDshBin("/Users/x/.local/bin/dsh")
        assertEquals("/Users/x/.local/bin/dsh", DaemonPrefs.load(file).dshBin)
    }

    @Test
    fun `clearing the pin returns to auto-detection`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setDshBin("/opt/dsh")
        DaemonPrefs.load(file).setDshBin(null)
        assertNull(DaemonPrefs.load(file).dshBin)
        // blank is the same as cleared — an empty --dsh-bin must not become a path that can never resolve
        DaemonPrefs.load(file).setDshBin("   ")
        assertNull(DaemonPrefs.load(file).dshBin)
    }

    @Test
    fun `writing another pref does not clobber the pin`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setDshBin("/opt/dsh")
        DaemonPrefs.load(file).setPushEnabled(false)
        val reloaded = DaemonPrefs.load(file)
        assertEquals("/opt/dsh", reloaded.dshBin)
        assertFalse(reloaded.pushEnabled)
    }

    @Test
    fun `a prefs file written before this field existed still loads`(@TempDir dir: File) {
        // forward/backward compat: a daemon downgrade or an older file must not fail to parse
        val file = File(dir, "prefs.json")
        file.writeText("""{"pushEnabled":false,"isolatedClaudeAuth":true}""")
        val prefs = DaemonPrefs.load(file)
        assertNull(prefs.dshBin)
        assertFalse(prefs.pushEnabled)
    }
}
