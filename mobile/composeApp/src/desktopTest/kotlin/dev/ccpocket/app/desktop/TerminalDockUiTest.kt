package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chrome-level tests for the embedded terminal dock (issue #153), rendered headless through the
 * real DesktopApp with [SeedDesktopModel]. The seed model has no engine factory, so no PTY or Swing
 * widget ever spawns — exactly the fallback body the panel shows when an engine can't exist.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalDockUiTest {

    @Test
    fun dockOpensCollapsesRestoresAndCloses() = runComposeUiTest {
        val model = SeedDesktopModel()
        model.terminalPanel.openEmbedded(model.chatWorkdir, model.chatBranch)
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        // open: header cwd + the engineless fallback body + the header controls
        assertPresent("~/code/cc-pocket")
        assertPresent("shell engine unavailable", substring = true)
        assertTrue(present("main"), "the branch chip labels the shell") // chip Text is an exact "main" node

        onNode(hasContentDescription("Collapse terminal")).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.COLLAPSED, model.terminalPanel.mode)
        assertTrue(!present("shell engine unavailable", substring = true), "the collapsed strip hides the body")
        assertPresent("~/code/cc-pocket") // the strip still labels the session

        // clicking the strip (its cwd label sits inside the clickable row) restores the panel
        onNode(hasText("~/code/cc-pocket")).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.OPEN, model.terminalPanel.mode)

        onNode(hasContentDescription("Close terminal")).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.CLOSED, model.terminalPanel.mode)
        assertTrue(!present("shell engine unavailable", substring = true), "closing removes the dock entirely")
    }

    @Test
    fun glyphOpensTheOpenModeMenuAndEmbeddedRowRestores() = runComposeUiTest {
        val model = SeedDesktopModel()
        model.terminalPanel.openEmbedded(model.chatWorkdir, model.chatBranch)
        model.terminalPanel.collapse()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        onNode(hasContentDescription("Terminal menu")).performClick() // the strip's glyph
        waitForIdle()
        assertPresent("OPEN TERMINAL")
        assertPresent("Open embedded")
        assertPresent("⌘J") // the default's keycap hint
        assertPresent("Open in system terminal") // seed pref = SYSTEM
        assertPresent("Default can be changed in Settings → Terminal.")
        // seed default = embedded → the check (primary styling) sits on the EMBEDDED row
        onNode(hasTestTag("term-menu-default-row")).assert(hasText("Open embedded"))

        onNode(hasText("Open embedded")).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.OPEN, model.terminalPanel.mode, "picking embedded restores the panel")
        assertTrue(!present("OPEN TERMINAL"), "the menu dismisses after a pick")
    }

    @Test
    fun defaultExternalMovesTheCheckToTheExternalRow() = runComposeUiTest {
        val model = SeedDesktopModel()
        model.terminalDefaultEmbedded = false
        model.terminalPanel.openEmbedded(model.chatWorkdir, model.chatBranch)
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        onNode(hasContentDescription("Terminal menu")).performClick() // the panel header's glyph
        waitForIdle()
        // the ⌘J keycap rides the EMBEDDED row regardless; the check (primary styling) follows the
        // default — exactly ONE row carries it, and it must be the external one
        assertPresent("Open embedded")
        assertPresent("Open in system terminal")
        onAllNodes(hasTestTag("term-menu-default-row")).assertCountEquals(1)
        onNode(hasTestTag("term-menu-default-row")).assert(hasText("Open in system terminal"))
        onNode(hasTestTag("term-menu-row")).assert(hasText("Open embedded")) // the un-checked row
        assertEquals(TermPanelMode.OPEN, model.terminalPanel.mode)
    }
}
