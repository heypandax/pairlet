package dev.ccpocket.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.ThemeMode
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

    @Test
    fun shellShowsCoreNavigation() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertPresent("RECENT")                // the grouped sessions zone replaced PROJECTS + docked SESSIONS
        assertPresent("PINNED")
        assertPresent("New session")           // the single entry point under the header
        assertPresent("All projects…")         // the browse escape hatch docked above Settings
        assertPresent("Lidapeng-MacBook")      // machine switcher header
        assertPresent("Refactor auth module")  // selected session (sidebar + chat header)
        assertPresent("Tidy CI workflow")      // a Codex session in the list
        assertPresent("Bump maxFrame to 4MB")  // a previously visited project's session — no expanding needed
        assertPresent("sonnet", substring = true) // Claude session header model line
    }

    @Test
    fun recentGroupsCollapse() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertPresent("Bump maxFrame to 4MB")                // the relay group renders expanded
        // "relay" labels a RUNNING row first, then the RECENT group header — the header composes last
        onAllNodes(hasText("relay")).onLast().performClick()
        waitForIdle()
        assertTrue(!present("Bump maxFrame to 4MB"), "a collapsed group hides its sessions")
    }

    @Test
    fun runningRowsDedupeAgainstRunningPins() {
        val m = SeedDesktopModel()
        assertEquals(3, m.running.size)
        // cc-pocket is already represented by the running pin "Refactor auth module" — shown once, not twice
        assertEquals(2, m.runningVisible.size)
        assertTrue(m.runningVisible.none { it.second.name == "cc-pocket" })
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
        assertEquals("/review ", model.composer)                      // completes the word + a trailing space (cursor ready for args)
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
    fun quickActionsOpensAndSwitchesMode() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showQuickActions = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("QUICK ACTIONS") // the ⋯ popover's label (was a dead icon — this pins the wiring)
        assertPresent("Model")
        assertPresent("Compact context")
        onAllNodes(hasText("Mode")).onLast().performClick()          // drill into the mode page
        waitForIdle()
        assertPresent("Ask each step")                               // the four CLAUDE_MODES rows
        assertPresent("Full auto")
        onAllNodes(hasText("Full auto")).onLast().performClick()     // picking one closes the popover
        waitForIdle()
        assertTrue(!model.showQuickActions, "picking a mode dismisses the quick-actions popover")
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
    fun newSessionPopoverStartsOnEnter() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        onAllNodes(hasText("New session")).onFirst().performClick()
        waitForIdle()
        assertPresent("Start session")
        // the path field is auto-focused on open; Enter = the Start button
        onAllNodes(hasText("~/code/cc-pocket")).onFirst().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(!present("Start session"), "Enter submits and closes the popover")
    }

    @Test
    fun newSessionAtPathSeedsHome() = runComposeUiTest {
        val model = SeedDesktopModel().apply { browseProjects() } // "All projects…" → project-scoped palette
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasText("New session at path…")).onFirst().performClick() // the scoped palette's lead action
        waitForIdle()
        assertPresent("Start session")
        assertPresent("~/") // path field seeded at the daemon host's home, ready to type into
    }

    @Test
    fun allProjectsOpensProjectScopedPalette() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        onAllNodes(hasText("All projects…")).onFirst().performClick()
        waitForIdle()
        assertPresent("Open a project", substring = true)  // the scoped placeholder
        assertPresent("dotfiles")                          // every project row, even ones without sessions
        assertTrue(!present("Switch to mac-studio"), "machine verbs stay out of the project browser")
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
        val model = SeedDesktopModel().apply { palette = PaletteScope.ALL }
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
    fun appearanceControlSwitchesThemeMode() = runComposeUiTest {
        // desktop Appearance control (issue #63): a pick must reach model.themeMode — the live RepoDesktopModel
        // persists it and Main.kt feeds it to PocketTheme, so wiring the click through is the whole feature
        val model = SeedDesktopModel()
        setContent { PocketTheme { SettingsModal(model) {} } } // opens on the General tab; Appearance sits at its top
        assertPresent("Appearance")
        onAllNodes(hasText("Light")).onFirst().performClick()
        waitForIdle()
        assertEquals(ThemeMode.LIGHT, model.themeMode)
        onAllNodes(hasText("Dark")).onFirst().performClick()
        waitForIdle()
        assertEquals(ThemeMode.DARK, model.themeMode)
    }

    @Test
    fun trayPopoverShowsRealApprovalsAndSessions() = runComposeUiTest {
        // was a static mockup showing the developer's own machine names (issue #111) — now driven by the
        // live-shaped SeedDesktopModel, so every row is real fleet state
        val model = SeedDesktopModel()
        setContent { PocketTheme { TrayPopover(model) } }
        assertPresent("PENDING APPROVALS")
        assertPresent("RUNNING SESSIONS")
        assertPresent("rm -rf ./build && ./gradlew clean") // a REAL fleet approval preview (mac-studio's Bash)
        assertPresent("mac-studio")                          // the owning machine, not a hardcoded name
        assertPresent("api-server")                          // a REAL running project on another machine
        assertPresent("3 computers · 3 sessions")            // header derived from live fleet state
        assertPresent("Open cc-pocket")
    }

    @Test
    fun trayApprovalAllowRidesRealResolvePath() = runComposeUiTest {
        // Allow/Deny must go through model.resolveAttention — the same repo verdict the inline card and the
        // phone use — not a dead click. Resolving one leaves the fleet attention queue.
        val model = SeedDesktopModel()
        setContent { PocketTheme { TrayPopover(model) } }
        assertEquals(2, model.attention.size)
        onAllNodes(hasText("Allow")).onFirst().performClick() // rows compose in queue order — first Allow = first row
        waitForIdle()
        assertEquals(1, model.attention.size)
    }

    @Test
    fun trayOpenMainDismissesThePopover() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showTray = true }
        setContent { PocketTheme { TrayPopover(model) } }
        onAllNodes(hasText("Open cc-pocket")).onFirst().performClick()
        waitForIdle()
        assertTrue(!model.showTray, "Open cc-pocket dismisses the tray popover")
    }

    @Test
    fun traySettingsGearOpensSettings() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { TrayPopover(model) } }
        assertTrue(!model.showSettings)
        onAllNodes(hasContentDescription("Settings")).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showSettings, "the gear opens Settings (was a dead clickable)")
    }

    @Test
    fun trayHeaderCountsAggregateAcrossTheFleet() {
        val m = SeedDesktopModel()
        val (computers, sessions) = trayHeaderCounts(m)
        assertEquals(3, computers)             // three online computers (win-desktop is offline)
        assertEquals(3, sessions)              // three running projects across the whole fleet
        assertEquals(m.running.size, sessions) // the header count matches the un-deduped list the tray renders
    }

    @Test
    fun trayStatsLabelPluralizes() {
        assertEquals("3 computers · 3 sessions", trayStatsLabel(3, 3))
        assertEquals("1 computer · 1 session", trayStatsLabel(1, 1))
        assertEquals("0 computers · 0 sessions", trayStatsLabel(0, 0))
    }

    @Test
    fun trayVisibleCapsAndCountsOverflow() {
        assertEquals(listOf(1, 2, 3) to 2, trayVisible(listOf(1, 2, 3, 4, 5), 3)) // caps to max, 2 hidden
        assertEquals(listOf(1, 2) to 0, trayVisible(listOf(1, 2), 3))             // under the cap, none hidden
        assertEquals(emptyList<Int>() to 0, trayVisible(emptyList(), 3))
        // the seed fleet fits both section caps without overflow
        val m = SeedDesktopModel()
        assertTrue(m.attention.size <= TRAY_MAX_APPROVALS && m.running.size <= TRAY_MAX_RUNNING)
    }

    @Test
    fun trayQuestionAskRoutesToSessionInsteadOfBareAllow() = runComposeUiTest {
        // an AskUserQuestion's answer must ride the ALLOW as an answers map — a bare ALLOW reads "did not
        // answer" to the CLI — so question rows swap Deny/Allow for an "Answer in session" jump
        val q = DkAttention(
            "ask-q", "acct-studio", "mac-studio", DkOs.MAC, "AskUserQuestion", "Which approach should I take?",
            seconds = null, live = true, question = true,
        )
        val model = object : DesktopModel by SeedDesktopModel() {
            override val attention = listOf(q)
        }
        setContent { PocketTheme { TrayPopover(model) } }
        assertPresent("Answer in session ↗")
        assertTrue(!present("Deny"), "question rows must not offer a bare Deny/Allow")
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

    @Test
    fun rememberCheckboxTogglesAndRidesAllow() = runComposeUiTest {
        // regression: the checkbox used to be a dead decoration — unclickable, remember always false
        val ask = PermissionAsk(convoId = "c", askId = "a", tool = "Bash", inputPreview = "npm test", title = "Run command", rule = "Bash(npm test:*)")
        var allowedRemember: Boolean? = null
        setContent {
            PocketTheme {
                FocusedModal("devbox-linux", ask, AgentKind.CLAUDE, "~/code", null, onAllow = { allowedRemember = it }, onDeny = {}, onDismiss = {})
            }
        }
        assertPresent("Remember for this session")
        onAllNodes(hasText("Remember for this session")).onLast().performClick()
        waitForIdle()
        onAllNodes(hasText("Allow")).onLast().performClick()
        waitForIdle()
        assertEquals(true, allowedRemember)
    }

    @Test
    fun rememberCheckboxHiddenWithoutRule() = runComposeUiTest {
        // no rule to remember → the checkbox would be a lie; plan decisions are one-off too (issue #10)
        val ask = PermissionAsk(convoId = "c", askId = "a", tool = "ExitPlanMode", inputPreview = "plan", title = "Approve plan", rule = "Plan(x)")
        setContent {
            PocketTheme {
                FocusedModal("devbox-linux", ask, AgentKind.CLAUDE, "~/code", null, onAllow = {}, onDeny = {}, onDismiss = {})
            }
        }
        assertTrue(!present("Remember for this session"), "plan approvals must not offer remember")
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
        assertTrue(m.appVersion.isNotBlank()) // don't pin the literal — the seed tracks each release's version
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
