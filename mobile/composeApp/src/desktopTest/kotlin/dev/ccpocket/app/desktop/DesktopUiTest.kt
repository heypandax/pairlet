package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
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
        assertPresent("SESSIONS", substring = true) // docked pane label carries the project: "SESSIONS · CC-POCKET"
        assertPresent("PINNED")
        assertPresent("New session")
        assertPresent("Lidapeng-MacBook")      // machine switcher header
        assertPresent("Refactor auth module")  // selected session (sidebar + chat header)
        assertPresent("Tidy CI workflow")      // a Codex session in the list
        assertPresent("sonnet", substring = true) // Claude session header model line
    }

    @Test
    fun machineSwitcherListsTheFleet() = runComposeUiTest {
        val model = SeedDesktopModel().apply { switcherOpen = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("mac-studio")            // every paired machine, in the dropdown now
        assertPresent("devbox-linux")
        assertPresent("win-desktop")
        assertPresent("this Mac")              // local-daemon tag on the active row
        assertPresent("Add computer")          // pairing entry docked at the dropdown's bottom
        // "mac-studio" also labels a RUNNING row behind the scrim — the dropdown's node composes last
        onAllNodes(hasText("mac-studio")).onLast().performClick()
        waitForIdle()
        assertEquals("acct-studio", model.activeComputer?.accountId)
        assertTrue(!model.switcherOpen, "selecting a machine closes the switcher")
    }

    @Test
    fun pinnedZoneRendersAndJumps() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        assertPresent("PINNED")
        assertPresent("Port parser to Rust")   // a pinned session living on mac-studio
        model.jumpPin(2)                       // ⌘3 → remote pin switches the active machine
        waitForIdle()
        assertEquals("acct-studio", model.activeComputer?.accountId)
    }

    @Test
    fun pinAndUnpinRoundTrip() {
        val m = SeedDesktopModel()
        assertEquals(3, m.pins.size)
        m.pin(m.sessions[1])                   // "Fix stream parser test"
        assertEquals(4, m.pins.size)
        assertTrue(m.isPinned("s2"))
        m.pin(m.sessions[1])                   // idempotent — no duplicate pin
        assertEquals(4, m.pins.size)
        m.movePin(3, 0)
        assertEquals("s2", m.pins[0].sessionId)
        m.unpin(m.pins[0])
        assertEquals(3, m.pins.size)
        assertTrue(!m.isPinned("s2"))
    }

    @Test
    fun slashMenuFiltersAndCompletes() = runComposeUiTest {
        val model = SeedDesktopModel().apply { composer = "/re" }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("/review")                                      // the matching command surfaces
        assertTrue(!present("/help"), "non-matching commands are filtered out")
        onAllNodes(hasText("/review")).onLast().performClick()
        waitForIdle()
        assertEquals("/review", model.composer)                       // click completes the command word
    }

    @Test
    fun runningSectionAggregatesAcrossMachines() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        assertPresent("RUNNING")
        assertPresent("api-server")            // mac-studio's live project — visible with NO group expanded
        assertPresent("relay")                 // devbox-linux's live project
        onAllNodes(hasText("api-server")).onFirst().performClick() // remote row → switch over to that machine
        waitForIdle()
        assertEquals("acct-studio", model.activeComputer?.accountId)
    }

    @Test
    fun watchPaneRidesBesideTheChat() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertPresent("Run integration tests")                          // watch pane header
        assertPresent("pytest -x tests/integration", substring = true)  // its read-only stream
        assertPresent("waiting approval", substring = true)             // the ⏸ strip
    }

    @Test
    fun attentionPopoverListsAndResolvesCrossMachineApprovals() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showAttention = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("Needs you")
        assertPresent("rm -rf ./build && ./gradlew clean") // mac-studio's Bash ask
        assertEquals(2, model.attention.size)
        onAllNodes(hasText("Allow")).onFirst().performClick() // rows compose in queue order — first Allow = first row
        waitForIdle()
        assertEquals(1, model.attention.size) // a resolved row leaves the queue (and the badges)
    }

    @Test
    fun jumpMachineSwitchesTheActiveBinding() {
        val m = SeedDesktopModel()
        assertEquals("acct-mbp", m.activeComputer?.accountId)
        m.jumpMachine(1)
        assertEquals("acct-studio", m.activeComputer?.accountId)
        m.jumpMachine(0)
        assertEquals("acct-mbp", m.activeComputer?.accountId)
    }

    @Test
    fun selectingCodexSessionRevealsDiffApprovalAndModel() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertTrue(!present("Codex wants to edit files"), "no Codex diff before selecting it")
        onAllNodes(hasText("Tidy CI workflow")).onFirst().performClick()
        waitForIdle()
        assertPresent("Codex wants to edit files")       // inline diff approval card
        assertPresent("gpt-5.1-codex", substring = true) // header model flipped to Codex
    }

    @Test
    fun newSessionOpensPopover() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertTrue(!present("Start session"), "popover closed initially")
        onAllNodes(hasText("New session")).onFirst().performClick() // the Sessions-pane row (exact match)
        waitForIdle()
        assertPresent("Start session")
        assertPresent("Ask each step")
        assertPresent("~/code/cc-pocket") // path field seeded with the current project
    }

    @Test
    fun newSessionAtPathSeedsHome() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        onAllNodes(hasText("New session at path…")).onFirst().performClick() // the Projects-group row
        waitForIdle()
        assertPresent("Start session")
        assertPresent("~/") // path field seeded at the daemon host's home, ready to type into
    }

    @Test
    fun commandPaletteListsAndFilters() = runComposeUiTest {
        // render the palette alone so the only nodes are its own rows (the shell's sidebar would otherwise
        // also carry session titles and make a global text query meaningless)
        setContent { PocketTheme { CommandPalette(SeedDesktopModel()) {} } }
        waitForIdle()
        assertPresent("Jump to a project", substring = true) // placeholder
        assertPresent("Switch to mac-studio")                // machine verbs lead the blank-query list
        assertPresent("cc-pocket")                           // a project row
        // sessions sit below the lazy viewport on a blank query (machine verbs push them down) —
        // filtering brings one into view, which is also the real usage path
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("parser")
        waitForIdle()
        assertPresent("Fix stream parser test")              // label matches "parser"
        assertPresent("session")                             // per-row type tag (machine rows carry ⌘n keycaps instead)
        assertTrue(!present("Tidy CI workflow"), "non-matching session filtered out")
        assertTrue(!present("dotfiles"), "non-matching project filtered out")
    }

    @Test
    fun commandPaletteCarriesMachineVerbs() = runComposeUiTest {
        setContent { PocketTheme { CommandPalette(SeedDesktopModel()) {} } }
        waitForIdle()
        assertPresent("Switch to mac-studio")                     // machine verb + ⌘n hint
        assertPresent("⌘0 2") // switcher chord: ⌘0 opens it, the digit picks the machine
        assertPresent("New session on Lidapeng-MacBook…")         // machine-scoped action
        assertPresent("Approve pending on mac-studio")            // the "needs you" verb from the attention queue
        assertPresent("this Mac")                                 // local machine detail
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
        assertEquals("Refactor auth module", m.chatTitle)
        assertEquals(AgentKind.CLAUDE, m.chatAgent)
        assertEquals(null, m.ask) // Claude session, not pending
        m.selectSession(m.sessions[2])              // the Codex pending session
        assertEquals("s3", m.selectedSessionId)
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
