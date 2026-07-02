package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless automated tests for the desktop shell. They render the real composables (driven by [SeedDesktopModel],
 * the canned [DesktopModel] — the live app uses [RepoDesktopModel] instead) and exercise the same code paths:
 * core navigation is present, selecting the Codex session surfaces its diff approval + flips the header model,
 * and "New session" opens its popover. No display required.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopUiTest {

    private fun ComposeUiTest.present(text: String, substring: Boolean = false): Boolean =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.assertPresent(text: String, substring: Boolean = false) =
        assertTrue(present(text, substring), "expected a node with text: \"$text\"")

    @Test
    fun shellShowsCoreNavigation() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertPresent("PROJECTS")
        assertPresent("SESSIONS")
        assertPresent("New session")
        assertPresent("Lidapeng-MBP")          // computer switcher
        assertPresent("Fix relay reconnect")   // selected session (sidebar + chat header)
        assertPresent("Port parser to Rust")   // a Codex session in the list
        assertPresent("sonnet", substring = true) // Claude session header model line
    }

    @Test
    fun selectingCodexSessionRevealsDiffApprovalAndModel() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertTrue(!present("Codex wants to edit files"), "no Codex diff before selecting it")
        onAllNodes(hasText("Port parser to Rust")).onFirst().performClick()
        waitForIdle()
        assertPresent("Codex wants to edit files")       // inline diff approval card
        assertPresent("gpt-5.1-codex", substring = true) // header model flipped to Codex
    }

    @Test
    fun newSessionOpensPopover() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertTrue(!present("Start session"), "popover closed initially")
        onAllNodes(hasText("New session")).onFirst().performClick()
        waitForIdle()
        assertPresent("Start session")
        assertPresent("Ask each step")
    }

    @Test
    fun commandPaletteListsAndFilters() = runComposeUiTest {
        // render the palette alone so the only nodes are its own rows (the shell's sidebar would otherwise
        // also carry session titles and make a global text query meaningless)
        setContent { PocketTheme { CommandPalette(SeedDesktopModel()) {} } }
        waitForIdle()
        assertPresent("Jump to a project", substring = true) // placeholder
        assertPresent("Fix relay reconnect")                 // a session row
        assertPresent("cc-pocket")                           // a project row
        assertPresent("computer")                            // per-row type tag
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("relay")
        waitForIdle()
        assertPresent("Fix relay reconnect")                            // label matches "relay"
        assertTrue(!present("Tidy CI workflow"), "non-matching session filtered out")
        assertTrue(!present("dotfiles"), "non-matching project filtered out")
    }

    @Test
    fun shellOpensCommandPaletteFromFlag() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showPalette = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("Jump to a project", substring = true) // palette-unique placeholder
        assertPresent("navigate")                            // palette-unique footer hint
    }

    @Test
    fun settingsModalShowsPanesAndComputerActions() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showSettings = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("Default agent")            // General pane (default tab)
        assertPresent("Default permission mode")
        onAllNodes(hasText("Computers")).onFirst().performClick() // left-rail navigation
        waitForIdle()
        assertPresent("Paired computers")
        assertPresent("Rename")                   // per-computer actions (also fixes the accountId-label gap)
        assertPresent("Revoke")
    }

    @Test
    fun trayPopoverShowsApprovalsAndSessions() = runComposeUiTest {
        setContent { PocketTheme { TrayPopover() } }
        assertPresent("PENDING APPROVALS")
        assertPresent("RUNNING SESSIONS")
        assertPresent("Open cc-pocket")
    }

    @Test
    fun focusedModalNamesComputer() = runComposeUiTest {
        val ask = PermissionAsk(convoId = "c", askId = "a", tool = "Bash", inputPreview = "rm -rf ./build", title = "Run command")
        setContent {
            PocketTheme {
                FocusedModal("devbox-linux", ask, AgentKind.CLAUDE, "~/code/cc-pocket", "main", onAllow = {}, onDeny = {}, onDismiss = {})
            }
        }
        assertPresent("Claude needs permission")
        assertPresent("devbox-linux", substring = true)
        assertPresent("Allow")
        assertPresent("Deny")
    }

    // ── model logic (no composition) ─────────────────────────────────────────

    @Test
    fun seedModelSelectionTracksSession() {
        val m = SeedDesktopModel()
        assertEquals("s1", m.selectedSessionId)
        assertEquals("Fix relay reconnect", m.chatTitle)
        assertEquals(AgentKind.CLAUDE, m.chatAgent)
        assertEquals(null, m.ask) // Claude session, not pending
        m.selectSession(m.sessions[1])              // the Codex pending session
        assertEquals("s2", m.selectedSessionId)
        assertEquals(AgentKind.CODEX, m.chatAgent)
        assertTrue(m.ask?.diff != null, "Codex pending session surfaces a diff approval")
        m.resolve(allow = true, remember = false)
        assertEquals(null, m.ask, "resolving clears the ask")
    }

    @Test
    fun seedSettingsDefaultsAreMutable() {
        val m = SeedDesktopModel()
        assertEquals(AgentKind.CLAUDE, m.defaultAgent)
        m.defaultAgent = AgentKind.CODEX
        assertEquals(AgentKind.CODEX, m.defaultAgent)
        assertEquals("1.2.0", m.appVersion)
    }

    @Test
    fun seedDataInvariants() {
        val m = SeedDesktopModel()
        assertTrue(m.sessions.isNotEmpty())
        assertEquals(1, m.sessions.count { it.pending > 0 }, "exactly one session awaits approval")
        assertTrue(m.sessions.any { it.agent == AgentKind.CODEX }, "a Codex session exists")
        assertTrue(
            m.computers.any { it.online } && m.computers.any { !it.online },
            "seed has both online and offline computers",
        )
    }
}
