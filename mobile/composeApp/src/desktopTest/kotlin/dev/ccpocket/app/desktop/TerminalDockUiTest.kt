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
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.term_close
import dev.ccpocket.app.resources.term_collapse
import dev.ccpocket.app.resources.term_default_hint
import dev.ccpocket.app.resources.term_default_hint_link
import dev.ccpocket.app.resources.term_engine_unavailable
import dev.ccpocket.app.resources.term_menu
import dev.ccpocket.app.resources.term_open_embedded
import dev.ccpocket.app.resources.term_open_system
import dev.ccpocket.app.resources.term_open_title
import dev.ccpocket.app.str
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
        assertPresent(str(Res.string.term_engine_unavailable))
        assertTrue(present("main"), "the branch chip labels the shell") // chip Text is an exact "main" node

        onNode(hasContentDescription(str(Res.string.term_collapse))).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.COLLAPSED, model.terminalPanel.mode)
        assertTrue(!present(str(Res.string.term_engine_unavailable)), "the collapsed strip hides the body")
        assertPresent("~/code/cc-pocket") // the strip still labels the session

        // clicking the strip (its cwd label sits inside the clickable row) restores the panel
        onNode(hasText("~/code/cc-pocket")).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.OPEN, model.terminalPanel.mode)

        onNode(hasContentDescription(str(Res.string.term_close))).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.CLOSED, model.terminalPanel.mode)
        assertTrue(!present(str(Res.string.term_engine_unavailable)), "closing removes the dock entirely")
    }

    @Test
    fun glyphOpensTheOpenModeMenuAndEmbeddedRowRestores() = runComposeUiTest {
        val model = SeedDesktopModel()
        model.terminalPanel.openEmbedded(model.chatWorkdir, model.chatBranch)
        model.terminalPanel.collapse()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        onNode(hasContentDescription(str(Res.string.term_menu))).performClick() // the strip's glyph
        waitForIdle()
        assertPresent(str(Res.string.term_open_title).uppercase())
        assertPresent(str(Res.string.term_open_embedded))
        assertPresent("⌘J") // the default's keycap hint
        assertPresent(str(Res.string.term_open_system)) // seed pref = SYSTEM
        assertPresent(str(Res.string.term_default_hint, str(Res.string.term_default_hint_link)))
        // seed default = embedded → the check (primary styling) sits on the EMBEDDED row
        onNode(hasTestTag("term-menu-default-row")).assert(hasText(str(Res.string.term_open_embedded)))

        onNode(hasText(str(Res.string.term_open_embedded))).performClick()
        waitForIdle()
        assertEquals(TermPanelMode.OPEN, model.terminalPanel.mode, "picking embedded restores the panel")
        assertTrue(!present(str(Res.string.term_open_title).uppercase()), "the menu dismisses after a pick")
    }

    @Test
    fun defaultExternalMovesTheCheckToTheExternalRow() = runComposeUiTest {
        val model = SeedDesktopModel()
        model.terminalDefaultEmbedded = false
        model.terminalPanel.openEmbedded(model.chatWorkdir, model.chatBranch)
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        onNode(hasContentDescription(str(Res.string.term_menu))).performClick() // the panel header's glyph
        waitForIdle()
        // the ⌘J keycap rides the EMBEDDED row regardless; the check (primary styling) follows the
        // default — exactly ONE row carries it, and it must be the external one
        assertPresent(str(Res.string.term_open_embedded))
        assertPresent(str(Res.string.term_open_system))
        onAllNodes(hasTestTag("term-menu-default-row")).assertCountEquals(1)
        onNode(hasTestTag("term-menu-default-row")).assert(hasText(str(Res.string.term_open_system)))
        onNode(hasTestTag("term-menu-row")).assert(hasText(str(Res.string.term_open_embedded))) // the un-checked row
        assertEquals(TermPanelMode.OPEN, model.terminalPanel.mode)
    }
}
