package dev.ccpocket.daemon.update

import dev.ccpocket.daemon.DaemonPrefs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #244: a curl-installed daemon keeps itself current unless someone says otherwise. These pin the
 * precedence ladder (flag > env > persisted pref > default-on) — the piece that decides whether
 * UpdateChecker even *considers* a self-update. The safety guards it still has to clear
 * (managed install + service-anchored + not Windows) live in checkOnce and are untouched by this.
 */
class AutoUpdateResolveTest {

    @Test
    fun `default is on when nothing is set`() {
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = null, pref = null))
    }

    @Test
    fun `run flag forces on over every lower source`() {
        assertTrue(UpdateChecker.resolveAutoApply(flag = true, env = "0", pref = false))
        assertTrue(UpdateChecker.resolveAutoApply(flag = true, env = null, pref = false))
        assertTrue(UpdateChecker.resolveAutoApply(flag = true, env = "off", pref = null))
    }

    @Test
    fun `env beats the persisted pref in both directions`() {
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = "1", pref = false))
        assertFalse(UpdateChecker.resolveAutoApply(flag = false, env = "0", pref = true))
    }

    @Test
    fun `env accepts the usual truthy and falsy spellings`() {
        for (on in listOf("1", "true", "TRUE", "on", "yes", " on ")) {
            assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = on, pref = false), "env=$on should mean on")
        }
        for (off in listOf("0", "false", "FALSE", "off", "no", " off ")) {
            assertFalse(UpdateChecker.resolveAutoApply(flag = false, env = off, pref = true), "env=$off should mean off")
        }
    }

    @Test
    fun `unrecognized env value is ignored rather than read as off`() {
        // a typo must not silently disable updates — fall through to the pref/default instead
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = "maybe", pref = null))
        assertFalse(UpdateChecker.resolveAutoApply(flag = false, env = "maybe", pref = false))
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = "", pref = true))
    }

    @Test
    fun `persisted pref decides when flag and env are absent`() {
        assertFalse(UpdateChecker.resolveAutoApply(flag = false, env = null, pref = false))
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = null, pref = true))
    }

    @Test
    fun `prefs round-trip keeps unset distinct from explicitly off`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")

        // never touched: null, so resolveAutoApply falls back to the default (on)
        assertNull(DaemonPrefs.load(file).autoUpdate)
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = null, pref = DaemonPrefs.load(file).autoUpdate))

        DaemonPrefs.load(file).setAutoUpdate(false)
        assertEquals(false, DaemonPrefs.load(file).autoUpdate)
        assertFalse(UpdateChecker.resolveAutoApply(flag = false, env = null, pref = DaemonPrefs.load(file).autoUpdate))

        DaemonPrefs.load(file).setAutoUpdate(true)
        assertEquals(true, DaemonPrefs.load(file).autoUpdate)

        DaemonPrefs.load(file).setAutoUpdate(null)
        assertNull(DaemonPrefs.load(file).autoUpdate)
    }

    @Test
    fun `an old prefs file without the key reads as unset, not off`(@TempDir dir: File) {
        // daemons upgrading from before #244 have no autoUpdate key — they must land on the new default
        val file = File(dir, "prefs.json")
        file.writeText("""{"pushEnabled":true,"isolatedClaudeAuth":false,"askNoAutoDeny":false,"fullControlExpiryMs":0}""")
        assertNull(DaemonPrefs.load(file).autoUpdate)
        assertTrue(UpdateChecker.resolveAutoApply(flag = false, env = null, pref = DaemonPrefs.load(file).autoUpdate))
    }

    @Test
    fun `writing another pref does not clobber an explicit auto-update choice`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        DaemonPrefs.load(file).setAutoUpdate(false)
        DaemonPrefs.load(file).setPushEnabled(false)
        val reloaded = DaemonPrefs.load(file)
        assertEquals(false, reloaded.autoUpdate)
        assertFalse(reloaded.pushEnabled)
    }
}
