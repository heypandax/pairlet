package dev.ccpocket.app.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

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
        assertPresent(str(Res.string.switcher_recent).uppercase()) // the grouped sessions zone replaced PROJECTS + docked SESSIONS
        assertPresent(str(Res.string.dir_pinned).uppercase())
        assertPresent(str(Res.string.new_session_title))           // the single entry point under the header
        assertPresent(str(Res.string.switcher_all_projects) + "…") // the browse escape hatch docked above Settings
        assertPresent(str(Res.string.support_title))               // customer support stays one click from the sidebar
        assertPresent("Lidapeng-MacBook")      // machine switcher header
        assertPresent("Refactor auth module")  // selected session (sidebar + chat header)
        assertPresent("Tidy CI workflow")      // a Codex session in the list
        // the docked rows above Settings (Archived, Reviews) each take a row off the RECENT viewport, so
        // the last group sits below the fold at test size — the claim is that no EXPANDING is needed
        onNodeWithTag("sidebar-list").performScrollToNode(hasText("Bump maxFrame to 4MB"))
        waitForIdle()
        assertPresent("Bump maxFrame to 4MB")  // a previously visited project's session, already listed
        assertPresent("sonnet", substring = true) // Claude session header model line
    }

    @Test
    fun sharedGroupShowsProvenancePillAndExpiry() = runComposeUiTest {
        // a guest's shared folder in RECENT (issue #115): the neutral "Shared" pill + "owner · 6d left"
        // — the same provenance statement mobile's SharedProjectCell makes, on the desktop group header.
        // The pill/caption strings resolve via getString (the JVM locale picks the resource language).
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        waitForIdle()
        val badge = runBlocking { getString(Res.string.shared_badge) }
        val left = runBlocking { getString(Res.string.share_left_days, 6) }
        // the RECENT list outgrew the test viewport (the OpenCode seed row) — scroll the shared
        // group into view first; the assertions below are about RENDERING, not initial visibility
        onNodeWithTag("sidebar-list").performScrollToNode(hasText("acme-api"))
        waitForIdle()
        assertPresent("acme-api")                 // the shared group's header renders
        assertPresent(badge)                      // the hairline pill (shared_badge — same string as mobile)
        assertPresent("panda-mbp · $left")        // origin machine + remaining validity, at rest
    }

    @Test
    fun recentGroupsCollapse() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        // the last RECENT group sits below the fold at test size (the docked Archived/Reviews rows take
        // the space) — scroll it in, then the assertion is about EXPANDED vs collapsed, not visibility
        onNodeWithTag("sidebar-list").performScrollToNode(hasText("Bump maxFrame to 4MB"))
        waitForIdle()
        assertPresent("Bump maxFrame to 4MB")                // the relay group renders expanded
        // "relay" labels a RUNNING row first, then the RECENT group header — the header composes last
        onAllNodes(hasText("relay")).onLast().performClick()
        waitForIdle()
        assertTrue(!present("Bump maxFrame to 4MB"), "a collapsed group hides its sessions")
    }

    @Test
    fun currentProjectRendersCustomGroupSections() = runComposeUiTest {
        // issue #119: the live project's sessions render segmented under their custom groups + an Ungrouped
        // fallback, with the owner's "+ New group" create affordance at the foot.
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        waitForIdle()
        val ungrouped = runBlocking { getString(Res.string.group_ungrouped) }
        val newGroup = runBlocking { getString(Res.string.group_new) }
        assertPresent("Auth work")              // a named custom group header
        assertPresent("CI & release")           // the second group
        assertPresent(ungrouped)                // the fallback section (s2 is ungrouped)
        assertPresent(newGroup)                 // create affordance — owner-editable current project
        assertPresent("Refactor auth module")   // s1, under Auth work
        assertPresent("Fix stream parser test") // s2, under Ungrouped
    }

    @Test
    fun customGroupCollapseHidesSessionsAndRemembers() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("Tidy CI workflow")               // s3 under "CI & release", expanded (and not pinned)
        onAllNodes(hasText("CI & release")).onLast().performClick() // collapse that group
        waitForIdle()
        assertTrue(model.groupCollapsed("~/code/cc-pocket", "g-ci"), "the header click toggled collapse state")
        assertTrue(!present("Tidy CI workflow"), "collapsing a custom group hides its sessions")
    }

    @Test
    fun oldDaemonRendersFlatWithNoManagement() = runComposeUiTest {
        // degrade: an older daemon / guest omits groups → repo reports canEditGroups=false → flat list,
        // no group headers, no create entry. (groupsSupported=false is what folds into canEditGroups.)
        val model = object : DesktopModel by SeedDesktopModel() {
            override val customGroups = emptyList<DkGroup>()
            override val canEditGroups = false
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        val newGroup = runBlocking { getString(Res.string.group_new) }
        val ungrouped = runBlocking { getString(Res.string.group_ungrouped) }
        assertPresent("Refactor auth module")                             // sessions still render, flat
        assertTrue(!present("Auth work"), "no custom group headers when groups are empty")
        assertTrue(!present(newGroup), "no create affordance when the daemon isn't group-aware")
        assertTrue(!present(ungrouped), "no Ungrouped section in the flat view")
    }

    @Test
    fun groupAwareDaemonWithZeroGroupsStillOffersCreate() = runComposeUiTest {
        // a group-aware owner project that has NO groups yet: the list is flat (no headers / no Ungrouped),
        // but "+ New group" MUST show so the very first group is creatable (issue #119 — the create entry
        // lives outside the has-groups branch, gated on canEditGroups not on customGroups being non-empty).
        val model = object : DesktopModel by SeedDesktopModel() {
            override val customGroups = emptyList<DkGroup>()
            override val canEditGroups = true
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        val newGroup = runBlocking { getString(Res.string.group_new) }
        val ungrouped = runBlocking { getString(Res.string.group_ungrouped) }
        assertPresent("Refactor auth module")                             // sessions render, flat
        assertTrue(!present("Auth work"), "no headers until a group exists")
        assertTrue(!present(ungrouped), "no Ungrouped section while flat")
        assertPresent(newGroup)                                           // …but the first group is creatable
    }

    @Test
    fun seedGroupMutationsTrackState() {
        val m = SeedDesktopModel()
        assertEquals(2, m.customGroups.size)
        m.createGroup("Docs")
        assertEquals(3, m.customGroups.size)
        val docs = m.customGroups.first { it.name == "Docs" }
        m.assignGroup("s2", docs.id)                                   // move the ungrouped session in
        assertEquals(docs.id, m.sessions.first { it.sessionId == "s2" }.group)
        m.renameGroup(docs.id, "Documentation")
        assertEquals("Documentation", m.customGroups.first { it.id == docs.id }.name)
        m.assignGroup("s1", null)                                      // move s1 out of Auth work
        assertEquals(null, m.sessions.first { it.sessionId == "s1" }.group)
        m.deleteGroup(docs.id)                                         // deleting drops its sessions to Ungrouped
        assertTrue(m.customGroups.none { it.id == docs.id })
        assertEquals(null, m.sessions.first { it.sessionId == "s2" }.group)
    }

    @Test
    fun seedSessionRenameTracksState() {
        // issue #158: the sidebar's inline rename commits through the model and the row title follows
        // (production routes it daemon-side and the Sessions re-push refreshes; the seed applies locally)
        val m = SeedDesktopModel()
        assertTrue(m.canRenameSessions)
        val before = m.sessions.first { it.sessionId == "s2" }
        m.renameSession("s2", "  Stream parser hardening  ")
        val after = m.sessions.first { it.sessionId == "s2" }
        assertEquals("Stream parser hardening", after.title, "the committed title is trimmed and adopted")
        assertEquals(before.group, after.group, "a rename must not disturb group membership")
    }

    @Test
    fun refusedRenameShowsInlineErrorOnTheAskingRowAndEscDismisses() = runComposeUiTest {
        // issue #158: a rename_failed refusal re-opens the ASKING row's editor with the daemon's reason
        // inline — the feedback lands on the sessions surface, not as a Sys line in whatever chat happens
        // to be open (the common refusal, a terminal-held session, is renamed with no chat open at all).
        val reason = "session is live in another client — rename it there (/rename) or stop it first"
        val err = mutableStateOf<String?>(reason)
        val model = object : DesktopModel by SeedDesktopModel() {
            override fun renameError(sessionId: String): String? = err.value.takeIf { sessionId == "s2" }
            override fun dismissRenameError() { err.value = null }
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(reason)                       // the inline reason renders on s2's (re-opened) editor row
        // …with the editor prefilled with the still-current title, ready for a retry
        val field = onNode(hasSetTextAction() and hasText("Fix stream parser test"))
        field.requestFocus()
        waitForIdle()
        field.performKeyInput { pressKey(Key.Escape) } // Esc backs out and dismisses the refusal
        waitForIdle()
        assertTrue(!present(reason), "Esc must dismiss the inline refusal")
        assertPresent("Fix stream parser test")     // the plain row is back
    }

    @Test
    fun seedGroupCollapseToggles() {
        val m = SeedDesktopModel()
        assertTrue(!m.groupCollapsed("~/code/cc-pocket", "g-auth"))
        m.setGroupCollapsed("~/code/cc-pocket", "g-auth", true)
        assertTrue(m.groupCollapsed("~/code/cc-pocket", "g-auth"))
        m.setGroupCollapsed("~/code/cc-pocket", "g-auth", false)
        assertTrue(!m.groupCollapsed("~/code/cc-pocket", "g-auth"))
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
        assertPresent(str(Res.string.this_machine))   // local-daemon tag on the active row
        assertPresent(str(Res.string.add_device))     // pairing entry docked at the dropdown's bottom
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
        assertPresent(str(Res.string.dir_pinned).uppercase())
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

    /**
     * Issue #199 ②, the main line: every RECENT project header carries a ＋ that opens the new-session
     * popover already pointed at THAT project — the whole point is not having to walk into the project
     * first. The ＋ is deliberately not hover-gated, so this asserts it without synthesising a hover.
     */
    @Test
    fun recentGroupHeaderStartsASessionInThatProject() = runComposeUiTest {
        // ONE recent group, so the single ＋ in the tree is unambiguously that group's — and its path
        // differs from newSessionDir (where plain ⌘N would land), which is exactly the claim: the ＋
        // follows the row it sits on, not the current project. (With the full seed the RECENT list is
        // taller than the test viewport and the #83 reveal scroll decides which headers are composed.)
        val other = DkSessionGroup(
            "~/work/acme-api", "acme-api", current = false,
            sessions = listOf(DkSession("s6", "~/work/acme-api", "Add rate-limit middleware")),
        )
        val model = object : DesktopModel by SeedDesktopModel() {
            override val sessionGroups = listOf(other)
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertEquals("~/code/cc-pocket", model.newSessionDir) // ⌘N would land here…
        onAllNodesWithContentDescription(str(Res.string.new_session_here)).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showNewSession, "the ＋ opens the new-session popover")
        assertEquals(tilde(other.path), model.newSessionSeed, "…the ＋ seeds ITS project instead")
    }

    /** Issue #199 ①: a pinned PROJECT renders in the same PINNED zone as the session pins, and ⌘1–9 keeps
     *  running past the session pins into it — so neither kind of pin displaces or renumbers the other. */
    @Test
    fun pinnedProjectRendersBesideSessionPinsAndKeepsTheKeycapLadder() = runComposeUiTest {
        var opened: DkProjectPin? = null
        val model = object : SeedDesktopModel() {
            override fun openProjectPin(p: DkProjectPin) { opened = p }
        }
        setContent { PocketTheme { DesktopApp(model) } }
        model.pinProject("~/scratch/notes", "notes")
        waitForIdle()
        assertPresent("Port parser to Rust") // the session pins are still there…
        assertPresent("notes")               // …with the project pin under them
        model.jumpPin(model.pins.size)       // the keycap right after the last session pin
        assertEquals("~/scratch/notes", opened?.path)
        model.unpinProject("~/scratch/notes")
        waitForIdle()
        assertTrue(!present("notes"))
        assertPresent("Port parser to Rust")
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
    fun composerShiftEnterInsertsNewlineAndEnterSends() = runComposeUiTest {
        // Drives the real ChatPane key handling end-to-end through ComposerState (the retired
        // ImeSafeMirror's successor): shift+Enter splices a newline at the caret — the hint row's
        // "⇧⏎ newline" promise — and plain Enter submits, whose clear-on-send empties the field
        // through the model's String facade (an explicit external write; no reconcile pass exists).
        val model = SeedDesktopModel().apply { composer = "hello" } // the explicit write lands the caret at the end
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        val field = onAllNodes(hasSetTextAction()).onFirst()
        field.requestFocus()
        waitForIdle()
        field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals("hello\n", model.composer, "shift+Enter inserts a newline instead of sending")
        field.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("", model.composer, "Enter sends and clear-on-send empties the composer")
    }

    @Test
    fun runningSectionAggregatesAcrossMachines() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        assertPresent(str(Res.string.running).uppercase())
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
        assertPresent(str(Res.string.quick_actions_title).uppercase()) // the ⋯ popover's label (was a dead icon — this pins the wiring)
        assertPresent(str(Res.string.label_model))
        assertPresent(str(Res.string.qa_compact))
        onAllNodes(hasText(str(Res.string.label_mode))).onLast().performClick() // drill into the mode page
        waitForIdle()
        assertPresent(str(Res.string.mode_default_short))            // the four CLAUDE_MODES rows
        assertPresent(str(Res.string.mode_bypass_short))
        onAllNodes(hasText(str(Res.string.mode_bypass_short))).onLast().performClick() // picking one closes the popover
        waitForIdle()
        assertTrue(!model.showQuickActions, "picking a mode dismisses the quick-actions popover")
    }

    @Test
    fun composerModelChipOpensAnchoredPopover() = runComposeUiTest {
        // issue #157: the chip on the composer is the one-click model entrance. The seed streams by
        // default (chip inert mid-turn), so pin a quiet session to drive the open.
        val model = object : DesktopModel by SeedDesktopModel() {
            override val streaming = false
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("sonnet")                                                // the chip carries the current model
        onAllNodes(hasContentDescription(str(Res.string.qa_model))).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showModelPopover, "clicking the chip opens the model popover")
        assertPresent("Fable")            // the alias rows render in the anchored popover
        assertPresent(str(Res.string.model_gateway_show))          // collapsed presets row (no gateway in the seed)
        assertPresent(str(Res.string.model_custom_label).uppercase()) // the custom-id section
    }

    @Test
    fun composerModelChipInertWhileStreaming() = runComposeUiTest {
        // mid-turn the chip dims and disables (design model-chip.jsx state 3) — the running turn keeps
        // its model, so the entrance rests; the ⋯ Model shortcut still reaches the popover. Streaming
        // is snapshot state here so the second half can end the turn and pin the recovery: the chip
        // re-enables through recomposition and opens the popover again.
        val streamingState = mutableStateOf(true)
        val model = object : DesktopModel by SeedDesktopModel() {
            override val streaming: Boolean get() = streamingState.value
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasContentDescription(str(Res.string.qa_model))).onFirst().performClick()
        waitForIdle()
        assertTrue(!model.showModelPopover, "the dimmed chip must not open the popover mid-turn")
        streamingState.value = false // the turn ends
        waitForIdle()
        onAllNodes(hasContentDescription(str(Res.string.qa_model))).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showModelPopover, "once streaming ends the chip re-enables and opens the popover")
    }

    @Test
    fun quickActionsModelRowShortcutsToPopover() = runComposeUiTest {
        // issue #157: ⋯ → Model no longer drills a second-level page — it closes the menu and opens
        // the SAME anchored popover the composer chip owns.
        val model = SeedDesktopModel().apply { showQuickActions = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.label_model))).onLast().performClick()
        waitForIdle()
        assertTrue(!model.showQuickActions, "the shortcut closes the ⋯ menu")
        assertTrue(model.showModelPopover, "…and opens the shared model popover")
        assertPresent("Fable")                                // popover content anchored at the chip
        assertPresent(str(Res.string.model_next_turn_note))   // seed streams → the next-turn note shows
    }

    @Test
    fun watchPaneRidesBesideTheChat() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertPresent("Run integration tests")                          // watch pane header
        assertPresent("pytest -x tests/integration", substring = true)  // its read-only stream
        assertPresent(str(Res.string.watch_waiting), substring = true)  // the ⏸ strip
    }

    @Test
    fun attentionPopoverListsAndResolvesCrossMachineApprovals() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showAttention = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.tray_needs_you))
        assertPresent("rm -rf ./build && ./gradlew clean") // mac-studio's Bash ask
        assertEquals(2, model.attention.size)
        onAllNodes(hasText(str(Res.string.allow))).onFirst().performClick() // rows compose in queue order — first Allow = first row
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
        val codexWantsEdit = str(Res.string.agent_wants_edit, "Codex")
        assertTrue(!present(codexWantsEdit), "no Codex diff before selecting it")
        onAllNodes(hasText("Tidy CI workflow")).onFirst().performClick()
        waitForIdle()
        assertPresent(codexWantsEdit)                    // inline diff approval card
        assertPresent("gpt-5.1-codex", substring = true) // header model flipped to Codex
    }

    @Test
    fun newSessionOpensPopover() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        assertTrue(!present(str(Res.string.new_path_start)), "popover closed initially")
        onAllNodes(hasText(str(Res.string.new_session_title))).onFirst().performClick() // the Sessions-pane row (exact match)
        waitForIdle()
        assertPresent(str(Res.string.new_path_start))
        assertPresent(str(Res.string.mode_default_short))
        assertPresent("~/code/cc-pocket") // path field seeded with the current project
    }

    @Test
    fun newSessionPopoverStartsOnEnter() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        onAllNodes(hasText(str(Res.string.new_session_title))).onFirst().performClick()
        waitForIdle()
        assertPresent(str(Res.string.new_path_start))
        // the path field is auto-focused on open; Enter = the Start button
        onAllNodes(hasText("~/code/cc-pocket")).onFirst().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(!present(str(Res.string.new_path_start)), "Enter submits and closes the popover")
    }

    @Test
    fun newSessionAtPathSeedsHome() = runComposeUiTest {
        val model = SeedDesktopModel().apply { browseProjects() } // "All projects…" → project-scoped palette
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.palette_new_at_path))).onFirst().performClick() // the scoped palette's lead action
        waitForIdle()
        assertPresent(str(Res.string.new_path_start))
        assertPresent("~/") // path field seeded at the daemon host's home, ready to type into
    }

    @Test
    fun allProjectsOpensProjectScopedPalette() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        onAllNodes(hasText(str(Res.string.switcher_all_projects) + "…")).onFirst().performClick()
        waitForIdle()
        assertPresent(str(Res.string.palette_open_project))  // the scoped placeholder
        assertPresent("dotfiles")                            // every project row, even ones without sessions
        assertTrue(!present(str(Res.string.palette_switch_to, "mac-studio")), "machine verbs stay out of the project browser")
    }

    @Test
    fun commandPaletteListsAndFilters() = runComposeUiTest {
        // render the palette alone so the only nodes are its own rows (the shell's sidebar would otherwise
        // also carry session titles and make a global text query meaningless)
        setContent { PocketTheme { CommandPalette(SeedDesktopModel()) {} } }
        waitForIdle()
        assertPresent(str(Res.string.palette_placeholder))                // placeholder
        assertPresent(str(Res.string.palette_switch_to, "mac-studio"))    // machine verbs lead the blank-query list
        assertPresent("cc-pocket")                           // a project row
        // sessions sit below the lazy viewport on a blank query (machine verbs push them down) —
        // filtering brings one into view, which is also the real usage path
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("parser")
        waitForIdle()
        assertPresent("Fix stream parser test")              // label matches "parser"
        assertPresent(str(Res.string.tag_session))           // per-row type tag (machine rows carry ⌘n keycaps instead)
        assertTrue(!present("Tidy CI workflow"), "non-matching session filtered out")
        assertTrue(!present("dotfiles"), "non-matching project filtered out")
    }

    @Test
    fun commandPaletteCarriesMachineVerbs() = runComposeUiTest {
        setContent { PocketTheme { CommandPalette(SeedDesktopModel()) {} } }
        waitForIdle()
        assertPresent(str(Res.string.palette_switch_to, "mac-studio"))         // machine verb + ⌘n hint
        assertPresent("⌘0 2") // switcher chord: ⌘0 opens it, the digit picks the machine
        assertPresent(str(Res.string.palette_new_on, "Lidapeng-MacBook"))      // machine-scoped action
        assertPresent(str(Res.string.palette_approve_on, "mac-studio"))        // the "needs you" verb from the attention queue
        assertPresent(str(Res.string.this_machine))                            // local machine detail
    }

    @Test
    fun shellOpensCommandPaletteFromFlag() = runComposeUiTest {
        val model = SeedDesktopModel().apply { palette = PaletteScope.ALL }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.palette_placeholder))   // palette-unique placeholder
        assertPresent(str(Res.string.key_navigate))          // palette-unique footer hint
    }

    @Test
    fun settingsModalShowsPanesAndComputerActions() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showSettings = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.settings_default_agent))     // General pane (default tab)
        assertPresent(str(Res.string.settings_default_mode))
        onAllNodes(hasText(str(Res.string.settings_tab_computers))).onFirst().performClick() // left-rail navigation
        waitForIdle()
        assertPresent(str(Res.string.settings_paired_computers))
        assertPresent(str(Res.string.device_rename))              // per-computer actions (also fixes the accountId-label gap)
        assertPresent(str(Res.string.device_remove))
    }

    @Test
    fun appearanceControlSwitchesThemeMode() = runComposeUiTest {
        // desktop Appearance control (issue #63): a pick must reach model.themeMode — the live RepoDesktopModel
        // persists it and Main.kt feeds it to PocketTheme, so wiring the click through is the whole feature
        val model = SeedDesktopModel()
        setContent { PocketTheme { SettingsModal(model) {} } } // opens on the General tab; Appearance sits at its top
        assertPresent(str(Res.string.settings_appearance))
        onAllNodes(hasText(str(Res.string.appearance_light))).onFirst().performClick()
        waitForIdle()
        assertEquals(ThemeMode.LIGHT, model.themeMode)
        onAllNodes(hasText(str(Res.string.appearance_dark))).onFirst().performClick()
        waitForIdle()
        assertEquals(ThemeMode.DARK, model.themeMode)
    }

    @Test
    fun defaultModelOptionsFollowTheSelectedAgent() = runComposeUiTest {
        val model = SeedDesktopModel().apply {
            setDefaultModelFor(AgentKind.CLAUDE, "opus")
        }
        setContent { PocketTheme { SettingsModal(model) {} } }

        assertPresent("Fable")
        // by tag, not hasText("Codex"): the Appearance ▸ Accent color picker now also carries a "Codex"
        // option (issue #204), so the bare text is ambiguous — target the agent card explicitly
        onNodeWithTag("agent-card-CODEX").performScrollTo().performClick()
        waitForIdle()
        assertPresent("GPT 5.1 Codex")
        assertTrue(!present("Fable"), "Codex must not show Claude model choices")
        onAllNodes(hasText("GPT 5.1 Codex")).onFirst().performScrollTo().performClick()
        waitForIdle()

        assertEquals("gpt-5.1-codex", model.defaultModelFor(AgentKind.CODEX))
        assertEquals("opus", model.defaultModelFor(AgentKind.CLAUDE), "each agent keeps its own default")
    }

    @Test
    fun trayPopoverShowsRealApprovalsAndSessions() = runComposeUiTest {
        // was a static mockup showing the developer's own machine names (issue #111) — now driven by the
        // live-shaped SeedDesktopModel, so every row is real fleet state
        val model = SeedDesktopModel()
        setContent { PocketTheme { TrayPopover(model) } }
        assertPresent(str(Res.string.tray_needs_you).uppercase())  // menubar-presence handoff section grammar (#151)
        assertPresent(str(Res.string.running).uppercase())
        assertPresent("rm -rf ./build && ./gradlew clean") // a REAL fleet approval preview (mac-studio's Bash)
        assertPresent("mac-studio")                          // the owning machine chip, not a hardcoded name
        assertPresent("api-server")                          // a REAL running project on another machine
        // header derived from live fleet state — plural forms via the same resources the popover reads
        assertPresent(str(Res.string.tray_computers_many, 3) + " · " + str(Res.string.tray_sessions_many, 3))
        assertPresent(str(Res.string.tray_open_app))
        // (elapsed labels ride the process-wide TrayRunningSince clock — value coverage lives in
        // MenuBarStateTest, since "now" vs "1m" here would depend on suite timing)
        assertTrue(!present("⌘⏎"), "the keycap hint hides where the shortcut isn't wired (in-window overlay)")
    }

    @Test
    fun trayKeyHintShowsOnlyInTheMenuBarWindow() = runComposeUiTest {
        // the OS popover wires ⌘⏎ in its key handler and passes keyHint = true — only then is the cap honest
        setContent { PocketTheme { TrayPopover(SeedDesktopModel(), keyHint = true) } }
        assertPresent("⌘⏎")
    }

    @Test
    fun trayApprovalAllowRidesRealResolvePath() = runComposeUiTest {
        // Allow/Deny must go through model.resolveAttention — the same repo verdict the inline card and the
        // phone use — not a dead click. Resolving one leaves the fleet attention queue.
        val model = SeedDesktopModel()
        setContent { PocketTheme { TrayPopover(model) } }
        assertEquals(2, model.attention.size)
        onAllNodes(hasText(str(Res.string.allow))).onFirst().performClick() // rows compose in queue order — first Allow = first row
        waitForIdle()
        assertEquals(1, model.attention.size)
    }

    @Test
    fun trayOpenMainDismissesThePopover() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showTray = true }
        setContent { PocketTheme { TrayPopover(model) } }
        onAllNodes(hasText(str(Res.string.tray_open_app))).onFirst().performClick()
        waitForIdle()
        assertTrue(!model.showTray, "Open cc-pocket dismisses the tray popover")
    }

    @Test
    fun trayExitIsExplicitAndCallsTheApplicationExitPath() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showTray = true }
        var exited = false
        setContent { PocketTheme { TrayPopover(model, onExitApp = { exited = true }) } }

        onAllNodes(hasText(str(Res.string.tray_exit_app))).onFirst().performClick()
        waitForIdle()
        assertTrue(exited, "Exit cc-pocket must call the application-level exit callback")
        assertTrue(!model.showTray, "the in-window tray state is cleared before exiting")
    }

    @Test
    fun traySettingsGearOpensSettings() = runComposeUiTest {
        val model = SeedDesktopModel()
        var raised = false
        setContent { PocketTheme { TrayPopover(model, onOpenMain = { raised = true }) } }
        assertTrue(!model.showSettings)
        onAllNodes(hasContentDescription(str(Res.string.settings_title))).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showSettings, "the gear opens Settings (was a dead clickable)")
        // Settings lives in the main window — from the menu-bar popover the gear must surface it too,
        // or the modal opens under whatever covers the buried window and reads as a dead click
        assertTrue(raised, "the gear also raises the main window")
    }

    @Test
    fun settingsMenuBarToggleFlipsTheModel() = runComposeUiTest {
        // issue #151: the menu-bar presence opt-out — General pane, same ToggleRow idiom as phone push
        val model = SeedDesktopModel()
        setContent { PocketTheme { SettingsModal(model) {} } }
        assertTrue(model.menuBarEnabled, "menu-bar presence defaults on")
        // the group sits below the General pane's first viewport — scroll it in before clicking
        onAllNodes(hasText(str(Res.string.settings_menu_bar_toggle))).onFirst().performScrollTo().performClick()
        waitForIdle()
        assertTrue(!model.menuBarEnabled)
        onAllNodes(hasText(str(Res.string.settings_menu_bar_toggle))).onFirst().performClick()
        waitForIdle()
        assertTrue(model.menuBarEnabled)
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
    fun trayStatsLabelPluralizes() = runComposeUiTest {
        // singular counts flow through the *_one resources (the plural path is pinned by
        // trayPopoverShowsRealApprovalsAndSessions above) — assert through real composition, since
        // the label now resolves compose-resources and no longer exists as a pure function.
        val base = SeedDesktopModel()
        val model = object : DesktopModel by base {
            override val machines = base.machines.filter { it.computer.online }.take(1)
            override val running = base.running.take(1)
        }
        setContent { PocketTheme { TrayPopover(model) } }
        waitForIdle()
        assertPresent(str(Res.string.tray_computers_one, 1) + " · " + str(Res.string.tray_sessions_one, 1))
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
        assertPresent(str(Res.string.tray_answer_in_session))
        assertTrue(!present(str(Res.string.deny)), "question rows must not offer a bare Deny/Allow")
    }

    @Test
    fun focusedModalNamesComputer() = runComposeUiTest {
        val ask = PermissionAsk(convoId = "c", askId = "a", tool = "Bash", inputPreview = "rm -rf ./build", title = "Run command")
        setContent {
            PocketTheme {
                FocusedModal("devbox-linux", ask, AgentKind.CLAUDE, "~/code/cc-pocket", "main", onAllow = {}, onDeny = {}, onDismiss = {})
            }
        }
        assertPresent(str(Res.string.agent_needs_permission, "Claude"))
        assertPresent("devbox-linux", substring = true)
        assertPresent(str(Res.string.allow))
        assertPresent(str(Res.string.deny))
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
        assertPresent(str(Res.string.perm_remember_session))
        onAllNodes(hasText(str(Res.string.perm_remember_session))).onLast().performClick()
        waitForIdle()
        onAllNodes(hasText(str(Res.string.allow))).onLast().performClick()
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
        assertTrue(!present(str(Res.string.perm_remember_session)), "plan approvals must not offer remember")
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
@Test
    fun bridgeFormComposesInsideTheSettingsScrollContainer() = runComposeUiTest {
        setContent {
            PocketTheme {
                // the same shape SettingsModal gives every pane: an unbounded verticalScroll Box.
                // A nested unbounded scrollable inside the form crashed at measure time (infinite
                // max-height) — this test exists so that regression can't come back silently.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    NewBridgeForm(onCancel = {}, onCreate = { _, _, _, _, _ -> })
                }
            }
        }
        waitForIdle()
        assertPresent(str(Res.string.bridge_request_approval).uppercase())
        assertPresent(str(Res.string.bridge_manage_toggle))
    }

    // ── session archive (issue #202) ──────────────────────────────────────────────────────────────

    @Test
    fun archiveEntryIsHiddenUnlessTheDaemonSupportsIt() = runComposeUiTest {
        // the seed model leaves canArchiveSessions=false (an older daemon / a guest), so the sidebar must
        // not offer an entry whose frames the daemon would silently drop
        setContent { PocketTheme { DesktopApp(SeedDesktopModel()) } }
        waitForIdle()
        assertTrue(!present(str(Res.string.sidebar_archived)), "no archive row without the capability stamp")
    }

    @Test
    fun archiveEntryRendersWithItsCountWhenSupported() = runComposeUiTest {
        val archived = listOf(
            DkSession("a1", "~/code/cc-pocket", "Old spike", running = false),
            DkSession("a2", "~/code/other", "Retired experiment", running = false),
        )
        val model = object : DesktopModel by SeedDesktopModel() {
            override val canArchiveSessions = true
            override val archivedSessions = archived
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.sidebar_archived))
        // the count IS the desktop's receipt for an archive action — that's why this shell shows no toast
        assertPresent("2")
    }

    @Test
    fun archivedPaletteScopeListsEveryProjectsRowsWithTheUnarchiveVerb() = runComposeUiTest {
        val archived = listOf(
            DkSession("a1", "~/code/cc-pocket", "Old spike", running = false),
            DkSession("a2", "~/code/other", "Retired experiment", running = false),
        )
        var unarchived: String? = null
        val model = object : DesktopModel by SeedDesktopModel() {
            override val canArchiveSessions = true
            override val archivedSessions = archived
            override var palette: PaletteScope? = PaletteScope.ARCHIVED
            override fun unarchiveSession(s: DkSession) { unarchived = s.sessionId }
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        // the scope spans projects — that is the whole point of the entry
        assertPresent("Old spike")
        assertPresent("Retired experiment")
        // the persistent legend, not a hover-only hint: this is what makes the second verb discoverable
        assertPresent(str(Res.string.archive_unarchive))

        // by tag, not by text: the footer legend deliberately carries the same word, and the thing under
        // test is the SELECTED ROW's control
        onNodeWithTag("palette-secondary").performClick()
        waitForIdle()
        assertEquals("a1", unarchived, "the row's second verb restores that exact session")
    }

    // ── Review Center (REVIEW-REQUEST.md §12) ─────────────────────────────────────────────────────

    @Test
    fun reviewCenterOverlayRendersOnTheModelFlag() = runComposeUiTest {
        val model = SeedDesktopModel().apply { showReviewCenter = true }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.rv_title))
        // the seed has no daemon behind it, and a canned ledger would be the one place this feature is
        // allowed to show rows nobody's machine holds — so it states the absence instead
        assertPresent(str(Res.string.rv_offline))
    }

    @Test
    fun sidebarReviewsRowOpensTheCenter() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertTrue(!model.showReviewCenter, "the Center is closed until asked for")
        // docked beside Archived rather than buried in ⌘K: a colleague's review lands in the daemon
        // whether or not this window is open, and a count you go looking for is a count you miss
        onAllNodes(hasText(str(Res.string.rv_title))).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showReviewCenter)
    }
}
