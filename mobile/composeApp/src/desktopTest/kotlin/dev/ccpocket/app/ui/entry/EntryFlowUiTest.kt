package dev.ccpocket.app.ui.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.proj_search_cancel
import dev.ccpocket.app.resources.proj_search
import dev.ccpocket.app.resources.new_task
import dev.ccpocket.app.resources.cfg_mode_default
import dev.ccpocket.app.resources.cfg_mode_full
import dev.ccpocket.app.resources.cfg_mode_plan
import dev.ccpocket.app.resources.cfg_model_none
import dev.ccpocket.app.resources.cfg_opencode_body
import dev.ccpocket.app.resources.cfg_opencode_title
import dev.ccpocket.app.resources.cfg_start
import dev.ccpocket.app.resources.cfm_cta
import dev.ccpocket.app.resources.cfm_title
import dev.ccpocket.app.resources.cfm_workdir
import dev.ccpocket.app.resources.codex_preset_balanced
import dev.ccpocket.app.resources.dir_picker_options
import dev.ccpocket.app.resources.dir_picker_use_here
import dev.ccpocket.app.resources.label_mode
import dev.ccpocket.app.resources.pair_code_label
import dev.ccpocket.app.resources.pair_cta
import dev.ccpocket.app.resources.pair_helper_incomplete
import dev.ccpocket.app.resources.pair_route_scan
import dev.ccpocket.app.resources.dir_projects
import dev.ccpocket.app.resources.proj_help
import dev.ccpocket.app.resources.proj_more
import dev.ccpocket.app.resources.proj_open_any
import dev.ccpocket.app.resources.proj_open_computers
import dev.ccpocket.app.resources.proj_review
import dev.ccpocket.app.resources.proj_review_n
import dev.ccpocket.app.resources.proj_settings
import dev.ccpocket.app.resources.settings_cat_connections
import dev.ccpocket.app.resources.support_title
import dev.ccpocket.app.resources.scan_kept_many
import dev.ccpocket.app.resources.scan_without_camera
import dev.ccpocket.app.resources.scan_use_code
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.DirectoryPickerSheet
import dev.ccpocket.app.ui.PairScanRoute
import dev.ccpocket.app.ui.PairingScreen
import dev.ccpocket.app.ui.DirectoryScreen
import dev.ccpocket.app.ui.DirectorySkeleton
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The entry flow as it actually reaches the screen, at the release baseline (iPhone 17, 402 × 874 pt).
 *
 * `EntryUiTest` pins the rules; this file pins the CONSEQUENCES: that the camera is never mounted by
 * default, that Projects creates work through exactly one control (the #260 FAB) and no longer carries a
 * standalone open-folder row, that `Options` and a mode row start nothing, and that `Start` starts exactly
 * once. Every one of those is a behaviour a visual pass can silently invert.
 */
@OptIn(ExperimentalTestApi::class)
class EntryFlowUiTest {

    private val W = 402
    private val H = 874
    private val dir = "/Users/alex/code/cc-pocket"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-entry", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    /** Compose [content] against a repository in a real 402 × 874 scene at [fontScale]. */
    private fun baseline(
        fontScale: Float = 1f,
        dark: Boolean = true,
        seed: PocketRepository.() -> Unit = {},
        content: @Composable (PocketRepository) -> Unit,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply(seed) }
                PocketTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content(repo) } }
            }
        }
        waitForIdle()
        assertions()
    }

    /** Nothing consequential may spill past the 402 pt viewport. */
    private fun SkikoComposeUiTest.assertWithinViewport(text: String) {
        val b = onAllNodes(hasText(text, substring = true)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(b.left.value >= -0.5f, "\"$text\" starts off-screen at ${b.left}")
        assertTrue(b.right.value <= W + 0.5f, "\"$text\" overflows the ${W}pt viewport, ending at ${b.right}")
    }

    // ══ pairing ════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun pairingIsCodeFirstAndNeverOpensTheCameraByDefault() = baseline(
        content = { PairingScreen(it) },
    ) {
        // the six-digit field and its canonical action own the hierarchy…
        assertTrue(
            onAllNodes(hasContentDescription(str(Res.string.pair_code_label))).fetchSemanticsNodes().isNotEmpty(),
            "the six-digit field is on the first frame and is announced",
        )
        assertTrue(present(str(Res.string.pair_cta)), "Pair computer is the canonical action")
        assertTrue(present(str(Res.string.pair_helper_incomplete)), "…and it says what the field is waiting for")
        // …while scanning is an explicit ROUTE, not the page itself: the scanner surface is not mounted
        assertTrue(present(str(Res.string.pair_route_scan)), "Scan QR code stays reachable as a secondary route")
        // judged by copy only the scanner shows: its title is, by design, the same words as the route's label
        assertFalse(present(str(Res.string.scan_without_camera)), "the scanner surface must not be on the default frame")
        assertFalse(present(str(Res.string.scan_use_code)), "…nor any of its camera-free fallbacks")

        val codeBottom = onAllNodes(hasContentDescription(str(Res.string.pair_code_label))).onFirst()
            .getUnclippedBoundsInRoot().bottom
        val scanTop = onAllNodes(hasText(str(Res.string.pair_route_scan))).onFirst().getUnclippedBoundsInRoot().top
        assertTrue(scanTop > codeBottom, "the scan route sits BELOW the code field, never above it")
        assertWithinViewport(str(Res.string.pair_cta))
    }

    @Test
    fun openingScanRoutesToTheScannerAndBackWithoutLosingTheDigits() = baseline(
        content = { PairingScreen(it) },
    ) {
        // type two digits, then take the explicit scan route
        onAllNodes(hasContentDescription(str(Res.string.pair_code_label))).onFirst().performTextInput("42")
        waitForIdle()
        onAllNodes(hasText(str(Res.string.pair_route_scan))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.scan_without_camera)), "the scanner is entered on purpose")
        // …and the scanner is not a dead end: it states that the digits survived and repeats both routes
        assertTrue(present(str(Res.string.scan_kept_many, 2)), "the entered digits are preserved and said so")
        assertTrue(present(str(Res.string.scan_use_code)), "the code route is repeated here, not linked away")

        onAllNodes(hasText(str(Res.string.scan_use_code))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.pair_cta)), "back on the pairing surface")
        assertTrue(present("4") && present("2"), "the digits are still in the field")
    }

    @Test
    fun anUnusableCameraKeepsBothPairingRoutes() = runDesktopComposeUiTest(W, H) {
        var usedCode = 0
        var pasted = 0
        setContent {
            PocketTheme {
                // 3 digits already typed; the route reports what survived regardless of the camera
                PairScanRoute(digitsEntered = 3, onBack = {}, onScanned = {}, onUseCode = { usedCode++ }, onPasteLink = { pasted++ })
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.scan_kept_many, 3)))
        onAllNodes(hasText(str(Res.string.scan_use_code))).onFirst().performClick()
        assertEquals(1, usedCode, "the code route completes pairing on its own")
    }

    // ══ Projects ═══════════════════════════════════════════════════════════════════════════════════

    /** The unclipped bounds of a control identified by its localized content description. */
    private fun SkikoComposeUiTest.target(description: String) =
        onAllNodes(hasContentDescription(description)).onFirst().getUnclippedBoundsInRoot()

    /**
     * Issue #260 replaced the standalone open-folder ROW with the new-task FAB. What used to be pinned here
     * — "exactly one doorway, in the content hierarchy" — is now pinned in the other direction: the row is
     * GONE from this screen (its capability moved into the new-task sheet's project picker), and the FAB is
     * the one thing on Projects that creates anything.
     */
    @Test
    fun projectsCreatesOnlyThroughTheFabAndNoLongerCarriesAnOpenFolderRow() = baseline(
        seed = { enterDemo() },
        content = { DirectoryScreen(it) },
    ) {
        assertEquals(
            0,
            onAllNodes(hasText(str(Res.string.proj_open_any))).fetchSemanticsNodes().size,
            "the standalone open-folder row is gone from Projects — it lives in the new-task project picker",
        )
        assertEquals(
            1,
            onAllNodes(hasContentDescription(str(Res.string.new_task))).fetchSemanticsNodes().size,
            "…and exactly one control on this screen creates work",
        )
        // the hierarchy: title, then the written machine state, then the work — and the FAB below all of it
        assertTrue(present(str(Res.string.dir_projects)), "the screen names itself")
        assertTrue(present("alex-macbook", substring = true), "the machine row names the computer")
        val title = onAllNodes(hasText(str(Res.string.dir_projects))).onFirst().getUnclippedBoundsInRoot()
        val machine = onAllNodes(hasText("alex-macbook", substring = true)).onFirst().getUnclippedBoundsInRoot()
        val fab = target(str(Res.string.new_task))
        assertTrue(machine.top >= title.top, "the machine state sits under the title")
        assertTrue(fab.top > machine.bottom, "…and the create action sits below both")
        assertTrue(fab.width.value >= 47.5f && fab.height.value >= 47.5f, "the FAB is below the 48dp floor: $fab")
        assertTrue(fab.right.value <= W + 0.5f, "the FAB overflows the ${W}pt viewport at ${fab.right}")
    }

    /**
     * Search is a MODE of this screen (issue #260): the icon expands a field in place of the title, and the
     * FAB steps out of the way while it is open — the field and the primary action never share the frame.
     *
     * The other half of the rule — collapsing CLEARS the query — is pinned in `DirListTest` against
     * [dev.ccpocket.app.ui.ProjectSearch], not here: asserting it through the list would depend on which
     * rows a LazyColumn happens to have composed at this viewport, which is not what the rule is about.
     */
    @Test
    fun searchExpandsInPlaceAndTheFabStepsAside() = baseline(
        seed = { enterDemo() },
        content = { DirectoryScreen(it) },
    ) {
        assertEquals(1, onAllNodes(hasContentDescription(str(Res.string.new_task))).fetchSemanticsNodes().size)
        assertTrue(present(str(Res.string.dir_projects)), "the title owns row 1 while search is collapsed")

        onAllNodes(hasContentDescription(str(Res.string.proj_search))).onFirst().performClick()
        waitForIdle()

        assertEquals(
            1,
            onAllNodes(hasTestTag("projects-search")).fetchSemanticsNodes().size,
            "the field expands in the title row itself, not on a second surface",
        )
        assertTrue(present(str(Res.string.proj_search_cancel)), "…with the way back out beside it")
        assertEquals(
            0,
            onAllNodes(hasContentDescription(str(Res.string.new_task))).fetchSemanticsNodes().size,
            "the FAB hides while the search field owns the screen",
        )

        onAllNodes(hasText(str(Res.string.proj_search_cancel))).onFirst().performClick()
        waitForIdle()

        assertEquals(
            0,
            onAllNodes(hasTestTag("projects-search")).fetchSemanticsNodes().size,
            "cancelling puts the field away",
        )
        assertEquals(
            1,
            onAllNodes(hasContentDescription(str(Res.string.new_task))).fetchSemanticsNodes().size,
            "…and the FAB comes back",
        )
    }

    @Test
    fun theHeaderTrailingEdgeCarriesThreeRealControls() {
        var fleet = 0
        baseline(seed = { enterDemo() }, content = { DirectoryScreen(it, onOpenFleet = { fleet++ }) }) {
            // row 1 ends in three page-level controls — search (issue #260, where the always-on filter row
            // went), the computer doorway, and the overflow, in that order out to the edge
            val search = target(str(Res.string.proj_search))
            val computers = target(str(Res.string.proj_open_computers))
            val more = target(str(Res.string.proj_more))
            val title = onAllNodes(hasText(str(Res.string.dir_projects))).onFirst().getUnclippedBoundsInRoot()
            assertTrue(search.left > title.left, "search is on the TRAILING edge, not under the title")
            assertTrue(computers.left.value >= search.right.value - 0.5f, "the computer doorway follows search")
            assertTrue(more.left.value >= computers.right.value - 0.5f, "the overflow is the outermost control")
            for ((name, b) in listOf("Search" to search, "Computers" to computers, "More options" to more)) {
                assertTrue(b.width.value >= 47.5f && b.height.value >= 47.5f, "$name is below the 48dp floor: $b")
                assertTrue(b.right.value <= W + 0.5f, "$name overflows the ${W}pt viewport at ${b.right}")
            }
            // and the doorway still reaches the SAME fleet route the machine line used to
            onAllNodes(hasContentDescription(str(Res.string.proj_open_computers))).onFirst().performClick()
            waitForIdle()
            assertEquals(1, fleet, "the computer control opens the existing fleet surface")
        }
    }

    @Test
    fun reviewIsDirectlyReachableOnTheSecondRow() = baseline(
        seed = { enterDemo() },
        content = { DirectoryScreen(it) },
    ) {
        assertTrue(present(str(Res.string.proj_review)), "Review is one tap from Projects, not inside a menu")
        val review = onAllNodes(hasText(str(Res.string.proj_review))).onFirst().getUnclippedBoundsInRoot()
        val machine = onAllNodes(hasText("alex-macbook", substring = true)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(review.left > machine.left, "Review sits on the trailing edge of the state row")
        assertTrue(review.height.value >= 47.5f, "Review is below the 48dp floor: $review")
        assertTrue(review.right.value <= W + 0.5f, "Review overflows the ${W}pt viewport at ${review.right}")
        // nothing waiting → the WORD, never "Review 0": a printed zero reads as a broken badge
        assertFalse(present(str(Res.string.proj_review_n, 0)), "an empty queue prints no count")
    }

    @Test
    fun helpAndSettingsLiveInTheOverflowAndKeepTheirRoutes() = baseline(
        seed = { enterDemo() },
        content = { DirectoryScreen(it) },
    ) {
        // they are doorways to specialist surfaces: deliberately one tap away, not a leading text band
        assertFalse(present(str(Res.string.proj_help)), "Help is inside the overflow while it is closed")
        assertFalse(present(str(Res.string.proj_settings)), "…and so is Settings")

        onAllNodes(hasContentDescription(str(Res.string.proj_more))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.proj_help)), "opening the overflow reveals Help")
        assertTrue(present(str(Res.string.proj_settings)), "…and Settings")
        val help = onAllNodes(hasText(str(Res.string.proj_help))).onFirst().getUnclippedBoundsInRoot()
        assertTrue(help.height.value >= 47.5f, "a menu row is below the 48dp floor: $help")
        assertTrue(help.right.value <= W + 0.5f, "the menu overflows the ${W}pt viewport at ${help.right}")

        // choosing a row closes the menu AND opens the existing full-screen route
        onAllNodes(hasText(str(Res.string.proj_help))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.support_title)), "Help still opens the Help centre")
    }

    @Test
    fun theOverflowOpensSettingsAndAnOutsideTapDecidesNothing() = baseline(
        seed = { enterDemo() },
        content = { DirectoryScreen(it) },
    ) {
        onAllNodes(hasContentDescription(str(Res.string.proj_more))).onFirst().performClick()
        waitForIdle()
        // An outside tap is a dismissal, not a decision. The target is the screen TITLE: it has no action of
        // its own, and it is the one thing in the header that sits ABOVE the menu panel — the panel hangs
        // from below the title row and reaches ~280dp back across the frame, so a target on row 2 (the
        // machine sentence, Review) can land on a menu row instead of the scrim depending on text metrics.
        onAllNodes(hasText(str(Res.string.dir_projects))).onFirst().performClick()
        waitForIdle()
        assertFalse(present(str(Res.string.proj_settings)), "an outside tap closes the overflow")
        // a category row of the Settings LANDING — unique to that screen, so it proves a route opened
        assertFalse(present(str(Res.string.settings_cat_connections)), "…and opens nothing")

        onAllNodes(hasContentDescription(str(Res.string.proj_more))).onFirst().performClick()
        waitForIdle()
        onAllNodes(hasText(str(Res.string.proj_settings))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.settings_cat_connections)), "Settings still opens Settings")
    }

    @Test
    fun theConnectingSkeletonWearsTheSameHeaderInTheSamePlace() {
        // ONE header component, so skeleton → list cannot shift the geometry when directories arrive —
        // and the skeleton's controls are real: waiting for a list is no reason to lose the way out.
        fun headerGeometry(content: @Composable (PocketRepository) -> Unit): List<Float> {
            var box: List<Float> = emptyList()
            baseline(seed = { enterDemo() }, content = content) {
                box = listOf(str(Res.string.proj_open_computers), str(Res.string.proj_more)).flatMap {
                    val b = target(it)
                    listOf(b.left.value, b.top.value, b.width.value, b.height.value)
                }
            }
            return box
        }
        assertEquals(
            headerGeometry { DirectoryScreen(it) },
            headerGeometry { DirectorySkeleton(it) },
            "the connecting skeleton must place the header controls exactly where the list does",
        )
    }

    @Test
    fun theHeaderReflowsAtDoubleTypeInsteadOfClipping() = baseline(
        fontScale = 2f,
        seed = { enterDemo() },
        content = { DirectoryScreen(it) },
    ) {
        // everything the header promises is still inside the release frame, just taller
        for (text in listOf(str(Res.string.dir_projects), str(Res.string.proj_review))) {
            assertWithinViewport(text)
        }
        for (cd in listOf(str(Res.string.proj_search), str(Res.string.proj_open_computers), str(Res.string.proj_more))) {
            val b = target(cd)
            assertTrue(b.right.value <= W + 0.5f, "\"$cd\" overflows the ${W}pt viewport at ${b.right}")
            assertTrue(b.width.value >= 47.5f && b.height.value >= 47.5f, "\"$cd\" lost its 48dp floor: $b")
        }
        // row 2 REFLOWS: Review drops beneath the state sentence rather than crushing it
        val machine = onAllNodes(hasText("alex-macbook", substring = true)).onFirst().getUnclippedBoundsInRoot()
        val review = onAllNodes(hasText(str(Res.string.proj_review))).onFirst().getUnclippedBoundsInRoot()
        assertTrue(review.top >= machine.top, "Review reflows below the state sentence, it does not shrink it")
        // …and the one canonical create action is still on screen without scrolling. The FAB is anchored to
        // the frame rather than the type ramp, so 200% type must not push it out of reach.
        val fab = target(str(Res.string.new_task))
        assertTrue(fab.bottom.value <= H + 0.5f, "the new-task FAB must stay reachable at 200% type")
        assertTrue(fab.right.value <= W + 0.5f, "…and inside the ${W}pt viewport")
    }

    // ══ directory picker ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun pickerStartsFromStartHereAndNeverFromOptions() = runDesktopComposeUiTest(W, H) {
        var options = 0
        var starts = 0
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember { PocketRepository(scope).also { it.enterDemo(); it.directories.clear() } }
            PocketTheme {
                DirectoryPickerSheet(
                    repo, onDismiss = {}, onTypePath = {},
                    onOptions = { options++ }, onStart = { starts++ },
                )
            }
        }
        waitForIdle()
        // Options opens configuration and starts NOTHING
        onAllNodes(hasText(str(Res.string.dir_picker_options))).onFirst().performClick()
        waitForIdle()
        assertEquals(1, options)
        assertEquals(0, starts, "Options must not start a session")
        assertNull(repo.convoId.value)

        // Start here starts exactly once, however many times it is tapped
        val startHere = onAllNodes(hasText(str(Res.string.dir_picker_use_here), substring = true)).onFirst()
        startHere.performClick(); waitForIdle()
        startHere.performClick(); waitForIdle()
        assertEquals(1, starts, "a repeated tap on Start here must not start a second session")
    }

    // ══ configure ══════════════════════════════════════════════════════════════════════════════════

    /** The configuration sheet against a real repository, with the picked combination captured. */
    private fun configure(
        agent: AgentKind = AgentKind.CLAUDE,
        fontScale: Float = 1f,
        onPicked: (PermissionMode, AgentKind, String?, String?) -> Unit = { _, _, _, _ -> },
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                PocketTheme {
                    ConfigureSessionSheet(
                        workdir = dir, agent = agent, computer = "alex-macbook",
                        onPick = onPicked, onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        assertions()
    }

    @Test
    fun selectingAnOrdinaryModeSelectsAndDoesNotStart() {
        var picks = 0
        configure(onPicked = { _, _, _, _ -> picks++ }) {
            onAllNodes(hasText(str(Res.string.cfg_mode_plan))).onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(0, picks, "a mode row selects — it never commits the session")
            // …and Start then commits THAT selection, exactly once
            val start = onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst()
            start.performClick(); waitForIdle()
            start.performClick(); waitForIdle()
            assertEquals(1, picks, "Start commits once; a second tap has nothing left to start")
        }
    }

    @Test
    fun switchingAgentResetsModelAndMode() {
        var picked: Triple<PermissionMode, AgentKind, String?>? = null
        configure(onPicked = { m, a, native, model -> picked = Triple(m, a, model); }) {
            // choose a non-default Claude rung, then switch backends
            onAllNodes(hasText(str(Res.string.cfg_mode_plan))).onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onAllNodes(hasText("Codex")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            // Codex's own ladder is on screen, seeded at its recommended preset
            assertTrue(present(str(Res.string.codex_preset_balanced)), "the Codex ladder replaces Claude's")
            assertFalse(present(str(Res.string.cfg_mode_plan)), "a Claude rung must not survive the switch")

            onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst().performClick()
            waitForIdle()
            val p = picked
            assertTrue(p != null && p.second == AgentKind.CODEX)
            assertEquals(agentDefaultMode(AgentKind.CODEX), p!!.first, "the mode reset to Codex's default")
            assertNull(p.third, "and the model reset to 'follow the computer's default'")
        }
    }

    @Test
    fun openCodeStatesItsAutomaticBehaviourInsteadOfAFakeLadder() = configure(agent = AgentKind.OPENCODE) {
        assertTrue(present(str(Res.string.cfg_opencode_title)), "the automatic behaviour is stated")
        assertTrue(present(str(Res.string.cfg_opencode_body), substring = true))
        assertFalse(present(str(Res.string.label_mode).uppercase()), "there is no Mode section to disable")
        assertFalse(present(str(Res.string.cfg_mode_plan)), "…and no greyed-out rung implying one exists")
        assertTrue(present(str(Res.string.cfg_start), substring = true), "one Start action, as everywhere else")
    }

    @Test
    fun withNoReportedModelListTheRowSaysSoInsteadOfNamingOne() = configure(agent = AgentKind.OPENCODE) {
        // OpenCode has NO static model fallback on purpose — an empty list is the truth
        assertTrue(present(str(Res.string.cfg_model_none)), "the sheet states the absence rather than inventing a model")
    }

    @Test
    fun fullAccessConfirmsFirstAndCancelStartsNothing() {
        var picks = 0
        configure(onPicked = { _, _, _, _ -> picks++ }) {
            onAllNodes(hasText(str(Res.string.cfg_mode_full))).onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(0, picks)
            onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst().performClick()
            waitForIdle()
            // the confirmation names the agent, the workdir and the computer — all real
            assertTrue(present(str(Res.string.cfm_title)), "Start on Full access opens the confirmation")
            assertTrue(present(str(Res.string.cfm_workdir)))
            assertTrue(present("alex-macbook", substring = true), "…and names the computer it reaches")
            assertEquals(0, picks, "the confirmation has not started anything yet")

            onAllNodes(hasText(str(Res.string.cancel))).onFirst().performClick()
            waitForIdle()
            assertEquals(0, picks, "Cancel starts nothing")
            assertTrue(present(str(Res.string.cfg_mode_full)), "…and returns to configuration")
            // the selection survived: Start opens the SAME confirmation again
            onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst().performClick()
            waitForIdle()
            assertTrue(present(str(Res.string.cfm_title)), "Full access is still the selected mode")
            onAllNodes(hasText(str(Res.string.cfm_cta))).onFirst().performClick()
            waitForIdle()
            assertEquals(1, picks, "confirming starts exactly one session")
        }
    }

    @Test
    fun at200PercentTypeTheContextAndTheStartControlStayReachable() = configure(fontScale = 2f) {
        // the pinned zones survive the body growing: the workdir is still readable and Start is on screen
        assertTrue(present("cc-pocket", substring = true), "the workdir context stays pinned at the top")
        val start = onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst()
            .getUnclippedBoundsInRoot()
        assertTrue(start.bottom.value <= H + 0.5f, "the final decision must stay on screen at 200% type")
        assertTrue(start.right.value <= W + 0.5f, "…and inside the 402pt viewport")
        assertTrue(present(str(Res.string.cfg_mode_default)), "the ladder is still legible, just scrollable")
    }
}
