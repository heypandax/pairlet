package dev.ccpocket.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu-bar glyph's five-state language (issue #151, direction 1), as pure-function tests: the priority
 * fold, the done-flash transition rule, the elapsed clock/labels, the glyph raster (headless Java2D), and
 * the menuBarEnabled pref's persistence.
 */
class MenuBarStateTest {

    // ── the five-way priority fold ──

    @Test
    fun fiveStatePriorityFold() {
        // offline beats everything — counts read as "can't see", not "nothing happening"
        assertEquals(MenuBarKind.OFFLINE, menuBarIcon(MenuBarSnapshot(true, needs = 3, running = 2), flashing = true).kind)
        // a human being needed beats running work, and carries its count
        assertEquals(MenuBarIconSpec(MenuBarKind.NEEDS_YOU, 3), menuBarIcon(MenuBarSnapshot(false, 3, 2), flashing = false))
        // running carries the session count, still monochrome
        assertEquals(MenuBarIconSpec(MenuBarKind.RUNNING, 2), menuBarIcon(MenuBarSnapshot(false, 0, 2), flashing = false))
        // the done flash only shows on an otherwise idle bar
        assertEquals(MenuBarKind.DONE_FLASH, menuBarIcon(MenuBarSnapshot(false, 0, 0), flashing = true).kind)
        assertEquals(MenuBarKind.IDLE, menuBarIcon(MenuBarSnapshot(false, 0, 0), flashing = false).kind)
    }

    @Test
    fun flashNeverOutranksLiveStates() {
        // work started again mid-flash → the count wins over the decaying tick
        assertEquals(MenuBarKind.RUNNING, menuBarIcon(MenuBarSnapshot(false, 0, 1), flashing = true).kind)
        assertEquals(MenuBarKind.NEEDS_YOU, menuBarIcon(MenuBarSnapshot(false, 1, 0), flashing = true).kind)
    }

    // ── the done-flash transition rule ──

    @Test
    fun doneFlashFiresWhenTheLastRunFinishesClean() {
        val was = MenuBarSnapshot(false, needs = 0, running = 2)
        assertTrue(startsDoneFlash(was, MenuBarSnapshot(false, 0, 0)))
    }

    @Test
    fun doneFlashSuppressedWhenSomethingStillWaitsOrRuns() {
        val was = MenuBarSnapshot(false, 0, 2)
        // a decrement that leaves work running just updates the count
        assertTrue(!startsDoneFlash(was, MenuBarSnapshot(false, 0, 1)))
        // finishing INTO a pending approval is not "all done"
        assertTrue(!startsDoneFlash(was, MenuBarSnapshot(false, 1, 0)))
        // first observation has no transition to celebrate
        assertTrue(!startsDoneFlash(null, MenuBarSnapshot(false, 0, 0)))
        // nothing was running before → nothing finished
        assertTrue(!startsDoneFlash(MenuBarSnapshot(false, 0, 0), MenuBarSnapshot(false, 0, 0)))
    }

    @Test
    fun doneFlashSuppressedAcrossOfflineEdges() {
        // a dropped link zeroes the counts artificially — that's disconnection, not completion
        assertTrue(!startsDoneFlash(MenuBarSnapshot(false, 0, 2), MenuBarSnapshot(true, 0, 0)))
        // and coming back online to an idle fleet isn't a finish either
        assertTrue(!startsDoneFlash(MenuBarSnapshot(true, 0, 2), MenuBarSnapshot(false, 0, 0)))
    }

    // ── snapshot fold from live-shaped model state ──

    @Test
    fun snapshotFoldsTheSeedFleet() {
        val s = menuBarSnapshot(SeedDesktopModel())
        assertEquals(MenuBarSnapshot(offline = false, needs = 2, running = 3), s)
        assertEquals(MenuBarIconSpec(MenuBarKind.NEEDS_YOU, 2), menuBarIcon(s, flashing = false))
    }

    @Test
    fun snapshotReadsOfflineWhenNoComputerIsReachable() {
        val m = object : DesktopModel by SeedDesktopModel() {
            override val machines: List<DkMachine> = SeedDesktopModel().machines.map {
                it.copy(computer = it.computer.copy(online = false), projects = emptyList())
            }
        }
        assertTrue(menuBarSnapshot(m).offline)
        // and when the relay link itself is down, whatever the machine flags claim
        val down = object : DesktopModel by SeedDesktopModel() {
            override val connected = false
        }
        assertTrue(menuBarSnapshot(down).offline)
    }

    // ── the elapsed clock + labels ──

    @Test
    fun elapsedLabelsAreCompact() {
        assertEquals("now", elapsedLabel(0))
        assertEquals("now", elapsedLabel(59_999))
        assertEquals("1m", elapsedLabel(60_000))
        assertEquals("12m", elapsedLabel(12 * 60_000L))
        assertEquals("59m", elapsedLabel(59 * 60_000L + 59_000))
        assertEquals("1h", elapsedLabel(3_600_000))
        assertEquals("26h", elapsedLabel(26 * 3_600_000L + 5))
    }

    @Test
    fun runningSinceKeepsFirstObservationAndForgetsStoppedKeys() {
        val t = RunningSinceTracker()
        assertEquals(mapOf("a" to 100L, "b" to 100L), t.observe(listOf("a", "b"), now = 100))
        // later observations never move an existing clock
        assertEquals(100L, t.observe(listOf("a", "b"), now = 500)["a"])
        // a key that stops running is forgotten; re-appearing starts a fresh clock
        assertEquals(mapOf("a" to 100L), t.observe(listOf("a"), now = 900))
        assertEquals(1_000L, t.observe(listOf("a", "b"), now = 1_000)["b"])
    }

    // ── the glyph raster (headless Java2D — real rendering is on the machine-verification list) ──

    @Test
    fun rasterGrowsWithItsCompanions() {
        val idle = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.IDLE), darkMenuBar = true, scale = 1)
        val running = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.RUNNING, 2), darkMenuBar = true, scale = 1)
        val needs = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.NEEDS_YOU, 2), darkMenuBar = true, scale = 1)
        val done = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.DONE_FLASH), darkMenuBar = true, scale = 1)
        assertEquals(18, idle.height)
        assertEquals(18, idle.width) // the bare glyph is square
        assertTrue(running.width > idle.width, "a session count widens the slot")
        assertTrue(needs.width > running.width, "the terracotta dot widens needs-you past running")
        assertTrue(done.width > idle.width, "the tick widens done past idle")
    }

    @Test
    fun rasterScalesForRetinaAndFlipsWithTheBarTheme() {
        val one = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.NEEDS_YOU, 1), darkMenuBar = true, scale = 1)
        val two = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.NEEDS_YOU, 1), darkMenuBar = true, scale = 2)
        assertEquals(36, two.height) // 2× height exactly; width may round per-glyph metrics
        assertTrue(two.width >= one.width * 2 - 2 && two.width <= one.width * 2 + 2)
        // template behavior is hand-rolled (AWT has no NSImage templates): dark bar → light strokes,
        // light bar → dark strokes. Compare the brightest opaque-ish pixel of each variant.
        fun brightest(img: java.awt.image.BufferedImage): Int {
            var best = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.getRGB(x, y)
                if ((p ushr 24) and 0xFF > 128) best = maxOf(best, maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF))
            }
            return best
        }
        val dark = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.IDLE), darkMenuBar = true, scale = 2)
        val light = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.IDLE), darkMenuBar = false, scale = 2)
        assertTrue(brightest(dark) > 200, "dark menu bar gets near-white strokes")
        assertTrue(brightest(light) < 120, "light menu bar gets near-black strokes")
    }

    @Test
    fun rasterCapsTheCountAtNinePlus() {
        val nine = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.RUNNING, 9), darkMenuBar = true, scale = 1)
        val more = renderMenuBarImage(MenuBarIconSpec(MenuBarKind.RUNNING, 27), darkMenuBar = true, scale = 1)
        assertTrue(more.width > nine.width, "\"9+\" is wider than \"9\" — the cap actually rendered")
    }

    // ── the pref: default ON, opt-out persists (same store discipline as the terminal pick) ──

    @Test
    fun menuBarEnabledDefaultsOnAndPersistsTheOptOut() {
        val store = FakeDesktopStore()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val m1 = RepoDesktopModel(dev.ccpocket.app.data.PocketRepository(scope), scope, store = store)
        assertTrue(m1.menuBarEnabled, "absent key = on — upgrades gain the glyph")
        m1.menuBarEnabled = false
        assertEquals("0", store.map["desktop_menubar_enabled"]) // pins the on-disk format
        // a fresh model (relaunch) reads the opt-out back
        val m2 = RepoDesktopModel(dev.ccpocket.app.data.PocketRepository(scope), scope, store = store)
        assertTrue(!m2.menuBarEnabled)
        m2.menuBarEnabled = true
        assertEquals("1", store.map["desktop_menubar_enabled"])
    }
}
