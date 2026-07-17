package dev.ccpocket.app.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Headless contracts of the embedded terminal dock (issue #153): the controller's
 * open/collapse/close state machine, the one-shell-per-cwd engine lifecycle, height clamping +
 * persistence, the ⌘J toggle, and the default-open routing — none of it touches Swing or a PTY.
 */
class TerminalPanelTest {

    private class FakeEngine : EmbeddedTerminal {
        var disposed = false
        var focusRequests = 0
        override val running: Boolean get() = !disposed
        override val focused: Boolean = false
        override fun focus() { focusRequests++ }
        override fun dispose() { disposed = true }
    }

    @Test
    fun openCollapseRestoreCloseLifecycle() {
        val tp = TerminalPanelController()
        assertEquals(TermPanelMode.CLOSED, tp.mode)

        tp.openEmbedded("~/code/app", "main")
        assertEquals(TermPanelMode.OPEN, tp.mode)
        assertEquals("~/code/app", tp.cwd)
        assertEquals("main", tp.branch)

        tp.collapse()
        assertEquals(TermPanelMode.COLLAPSED, tp.mode)
        assertEquals("~/code/app", tp.cwd) // the collapsed strip still labels the shell's session

        tp.restore()
        assertEquals(TermPanelMode.OPEN, tp.mode)

        tp.close()
        assertEquals(TermPanelMode.CLOSED, tp.mode)
        assertNull(tp.cwd)
        assertNull(tp.branch)
    }

    @Test
    fun collapseAndRestoreOnlyActFromTheirOwnState() {
        val tp = TerminalPanelController()
        tp.collapse() // closed → collapse is a no-op, not an invalid transition
        assertEquals(TermPanelMode.CLOSED, tp.mode)
        tp.restore()
        assertEquals(TermPanelMode.CLOSED, tp.mode)
    }

    @Test
    fun closeDisposesTheEngine() {
        val engine = FakeEngine()
        val tp = TerminalPanelController().apply { engineFactory = { engine } }
        tp.openEmbedded("~/a", null)
        assertSame(engine, tp.engine)
        assertTrue(engine.focusRequests > 0, "opening hands the shell keyboard focus")

        tp.close()
        assertTrue(engine.disposed, "closing the dock must kill the shell")
        assertNull(tp.engine)
    }

    @Test
    fun reopenSameCwdKeepsShell_reopenElsewhereRespawns() {
        val spawned = mutableListOf<FakeEngine>()
        val tp = TerminalPanelController().apply { engineFactory = { FakeEngine().also(spawned::add) } }

        tp.openEmbedded("~/a", "main")
        tp.collapse()
        tp.openEmbedded("~/a", "main") // same session → the running shell is kept, panel just reopens
        assertEquals(1, spawned.size)
        assertFalse(spawned[0].disposed)
        assertEquals(TermPanelMode.OPEN, tp.mode)

        tp.openEmbedded("~/b", null) // another session's folder → ONE shell rule: old dies, new spawns
        assertEquals(2, spawned.size)
        assertTrue(spawned[0].disposed)
        assertFalse(spawned[1].disposed)
        assertEquals("~/b", tp.cwd)
    }

    @Test
    fun blankCwdNeverOpens() {
        val tp = TerminalPanelController().apply { engineFactory = { FakeEngine() } }
        tp.openEmbedded(null, null)
        tp.openEmbedded("", null)
        assertEquals(TermPanelMode.CLOSED, tp.mode)
        assertNull(tp.engine)
    }

    @Test
    fun enginelessPanelStaysFullyDrivable() {
        // seed/preview/tests and headless spawns: no factory → the chrome still opens/collapses/closes
        val tp = TerminalPanelController()
        tp.openEmbedded("~/a", null)
        assertEquals(TermPanelMode.OPEN, tp.mode)
        assertNull(tp.engine)
        tp.collapse(); tp.restore(); tp.close()
        assertEquals(TermPanelMode.CLOSED, tp.mode)
    }

    @Test
    fun dockRendersOnlyInTheOwningSession() {
        val tp = TerminalPanelController()
        assertFalse(tp.dockedAt("~/a"), "closed dock docks nowhere")
        tp.openEmbedded("~/a", null)
        assertTrue(tp.dockedAt("~/a"))
        assertFalse(tp.dockedAt("~/b"), "another session's chat must not show this shell")
        tp.collapse()
        assertTrue(tp.dockedAt("~/a"), "the collapsed strip still belongs to its session")
    }

    @Test
    fun heightClampsAndPersistsOncePerGesture() {
        val saved = mutableListOf<Float>()
        val tp = TerminalPanelController(loadHeight = { 0.9f }, saveHeight = saved::add)
        // a persisted out-of-range value is clamped on load
        assertEquals(TerminalPanelController.MAX_FRACTION, tp.heightFraction)

        tp.dragHeightTo(0.05f)
        assertEquals(TerminalPanelController.MIN_FRACTION, tp.heightFraction)
        tp.dragHeightTo(0.5f)
        assertEquals(0.5f, tp.heightFraction)
        assertTrue(saved.isEmpty(), "drag ticks must not rewrite the store")

        tp.persistHeight() // drag end
        assertEquals(listOf(0.5f), saved)
    }

    @Test
    fun cmdJTogglesOpenCollapseRestoreForTheCurrentSession() {
        val dir = Files.createTempDirectory("ccpkt-term").toString()
        val model = object : DesktopModel by SeedDesktopModel() {
            override val chatWorkdir = dir
            override val chatBranch = "main"
            override val terminalPanel = TerminalPanelController()
        }
        val tp = model.terminalPanel

        model.toggleEmbeddedTerminal() // closed → open embedded here
        assertEquals(TermPanelMode.OPEN, tp.mode)
        assertEquals(dir, tp.cwd)
        assertEquals("main", tp.branch)

        model.toggleEmbeddedTerminal() // open → out of the way
        assertEquals(TermPanelMode.COLLAPSED, tp.mode)

        model.toggleEmbeddedTerminal() // collapsed → back
        assertEquals(TermPanelMode.OPEN, tp.mode)
    }

    @Test
    fun cmdJRefusesANonLocalCwd() {
        val model = object : DesktopModel by SeedDesktopModel() {
            override val chatWorkdir = "/definitely/not/a/dir/on/this/machine"
            override val terminalPanel = TerminalPanelController()
        }
        model.toggleEmbeddedTerminal()
        assertEquals(TermPanelMode.CLOSED, model.terminalPanel.mode)
    }

    @Test
    fun openTerminalPreferredRoutesByTheDefault() {
        val external = mutableListOf<Pair<TerminalApp, String>>()

        // default = embedded → the dock opens, the external app is never launched
        val embedded = object : DesktopModel by SeedDesktopModel() {
            override var terminalDefaultEmbedded = true
            override val terminalPanel = TerminalPanelController()
        }
        embedded.openTerminalPreferred(external = { app, dir -> external += app to dir })
        assertEquals(TermPanelMode.OPEN, embedded.terminalPanel.mode)
        assertEquals(embedded.chatWorkdir, embedded.terminalPanel.cwd)
        assertTrue(external.isEmpty())

        // default = external → the dock stays closed, the launcher gets the pick + cwd
        val ext = object : DesktopModel by SeedDesktopModel() {
            override var terminalDefaultEmbedded = false
            override var terminalApp = TerminalApp.GHOSTTY
            override val terminalPanel = TerminalPanelController()
        }
        ext.openTerminalPreferred(external = { app, dir -> external += app to dir })
        assertEquals(TermPanelMode.CLOSED, ext.terminalPanel.mode)
        assertEquals(listOf(TerminalApp.GHOSTTY to ext.chatWorkdir), external)
    }

    @Test
    fun middleTruncationKeepsHeadAndLeaf() {
        assertEquals("~/code/cc-pocket", middleTruncatedPath("~/code/cc-pocket")) // short → untouched
        val long = "~/Desktop/Project/app/cc-pocket/mobile/composeApp/src/desktopMain/relay"
        val cut = middleTruncatedPath(long, maxChars = 40)
        assertTrue(cut.length < long.length)
        assertTrue(cut.startsWith("~/Desktop"), "the head anchors the truncation: $cut")
        assertTrue(cut.endsWith("/relay"), "the leaf always survives: $cut")
        assertTrue("…" in cut)
        // a single over-long segment still gets end-ellipsis rather than crashing
        assertTrue(middleTruncatedPath("x".repeat(80), maxChars = 20).endsWith("…"))
    }
}
