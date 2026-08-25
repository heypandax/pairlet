package dev.ccpocket.app.ui.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.proj_loading_on_machine
import dev.ccpocket.app.resources.proj_review
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.DirectoryScreen
import dev.ccpocket.app.ui.DirectorySkeleton
import dev.ccpocket.app.ui.LocalReduceMotion
import dev.ccpocket.app.ui.ProjectsLandingGate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The project list's loading state (issue #261), at the release baseline (iPhone 17, 402 × 874 pt).
 *
 * What is pinned here is the SHAPE of the wait, not the animation: five rows built like real project rows,
 * one wait sentence and only one, no Review doorway for a machine that has reported nothing, and a landing
 * reveal that is spent after the first list. The motion itself (breathing, the 180/220 ms swap) is left to
 * the eye — what a test can protect is that the state never regresses to "a few grey bars and a note to the
 * engineer who wrote them".
 */
@OptIn(ExperimentalTestApi::class)
class ProjectsLoadingUiTest {

    private val W = 402
    private val H = 874
    private val machine = "alex-macbook"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-loading", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = machine,
    )

    /** Two plain projects under one parent — the ordinary two-line project card, with no live session,
     *  no pin and no share turning it into one of the list's other row shapes. */
    private fun projects() = Directories(
        listOf(
            DirectoryEntry(path = "/Users/alex/code/cc-pocket", name = "cc-pocket", isDir = true, hasSessions = true),
            DirectoryEntry(path = "/Users/alex/code/cc-pocket-site", name = "cc-pocket-site", isDir = true, hasSessions = true),
        ),
    )

    private fun baseline(
        reduceMotion: Boolean = false,
        seed: PocketRepository.() -> Unit = {},
        content: @Composable (PocketRepository) -> Unit,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 1f),
                LocalReduceMotion provides reduceMotion,
            ) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply(seed) }
                PocketTheme(dark = true) { Box(Modifier.fillMaxSize()) { content(repo) } }
            }
        }
        waitForIdle()
        assertions()
    }

    // ══ the skeleton ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun theSkeletonIsFiveRowsShapedLikeTheProjectRowsThatReplaceThem() {
        var skeletonRow = 0f
        var skeletonRows = 0
        baseline(content = { DirectorySkeleton(it) }) {
            val rows = onAllNodes(hasTestTag("skeleton-row")).fetchSemanticsNodes()
            skeletonRows = rows.size
            skeletonRow = onAllNodes(hasTestTag("skeleton-row")).onFirst().getUnclippedBoundsInRoot().height.value
        }
        assertEquals(5, skeletonRows, "the wait shows the design's five rows")

        // …and each one stands where a real project row will: the swap must not move the list. The list is
        // seeded and the view mode asked for explicitly — both demo mode and the view/filter preferences
        // are process-wide state, so a test that inherits whatever ran before it measures a different row.
        var realRow = 0f
        baseline(
            seed = { receiveForTest(projects()); setTreeView(false); clearAgentFilter() },
            content = { DirectoryScreen(it) },
        ) {
            realRow = onAllNodes(hasClickAction() and hasText("cc-pocket-site", substring = true))
                .onFirst().getUnclippedBoundsInRoot().height.value
        }
        assertTrue(
            abs(skeletonRow - realRow) <= 3f,
            "a skeleton row ($skeletonRow) must stand as tall as the project row it becomes ($realRow)",
        )
    }

    @Test
    fun theWaitIsToldOnceInTheHeaderAndNeverAsAnEngineersNote() = baseline(
        content = { DirectorySkeleton(it) },
    ) {
        assertTrue(
            present(str(Res.string.proj_loading_on_machine, machine)),
            "the header names the computer being read",
        )
        // the sentence this replaced, in both languages it shipped in — the state is a product sentence now
        assertFalse(present("Waiting for the directory list", substring = true), "no engineer's note under the rows")
        assertFalse(present("正在等目录列表", substring = true), "no engineer's note under the rows (zh)")
        // …and the wait is told ONCE: the transport word does not get a second say beside the machine name
        assertFalse(present("connecting", substring = true), "the header does not also report the transport")
    }

    @Test
    fun reviewIsNotOnTheProjectsScreenAtAll() {
        // #261 originally pinned "hidden while waiting, back on landing"; the 08-16 batch then demoted the
        // whole P2P review surface off this screen (Review Center lives behind Settings now). The pin
        // follows the stronger fact: NO state of this screen offers the doorway — a regression on either
        // side (skeleton or loaded) turns this red.
        baseline(content = { DirectorySkeleton(it) }) {
            assertFalse(present(str(Res.string.proj_review), substring = true), "no review doorway while waiting")
        }
        baseline(seed = { receiveForTest(projects()) }, content = { DirectoryScreen(it) }) {
            assertFalse(present(str(Res.string.proj_review), substring = true), "…and none after landing either")
        }
    }

    @Test
    fun reduceMotionStillShowsTheWholeSkeleton() = baseline(
        reduceMotion = true,
        content = { DirectorySkeleton(it) },
    ) {
        // degrading motion may not degrade the state itself: same five rows, same sentence
        assertEquals(5, onAllNodes(hasTestTag("skeleton-row")).fetchSemanticsNodes().size)
        assertTrue(present(str(Res.string.proj_loading_on_machine, machine)))
    }

    // ══ the landing gate ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun aRecycledRowDoesNotReplayTheReveal() {
        // LazyColumn disposes items that scroll away; the reveal claim must outlive the item, or the top
        // rows flash (alpha 0 + stagger) every time they scroll back in (owner-reported, 08-16).
        val gate = ProjectsLandingGate()
        gate.arm()
        assertTrue(gate.claimFirstLanding(), "first landing plays")
        assertTrue(gate.claimRow(0), "row 0 plays once")
        assertFalse(gate.claimRow(0), "…and never again after recycling")
        assertTrue(gate.claimRow(1), "each row claims independently")
        assertFalse(gate.claimRow(99), "an index past the cascade never animates")
    }

    @Test
    fun theLandingRevealIsSpentAfterTheFirstList() {
        val gate = ProjectsLandingGate()
        gate.arm() // a skeleton was on screen
        assertTrue(gate.claimFirstLanding(), "the first list to replace the skeleton animates in")
        assertFalse(gate.claimFirstLanding(), "a refresh, or coming back to Projects, does not replay it")
        assertFalse(gate.claimFirstLanding())
    }

    @Test
    fun aListThatNeverWaitedIsSimplyThere() {
        // no skeleton preceded it (a cached list on open, a screenshot, the desktop shell): an entrance
        // animation here is a flicker on a screen nobody was waiting on
        assertFalse(ProjectsLandingGate().claimFirstLanding(), "nothing landed — nothing to reveal")
    }

    @Test
    fun reducedMotionNeverClaimsTheLandingReveal() {
        val gate = ProjectsLandingGate()
        gate.arm()
        assertFalse(gate.claimFirstLanding(reduceMotion = true), "reduced motion swaps instantly")
        // and the claim is spent either way: the reveal does not lie in wait for the next composition
        assertFalse(gate.claimFirstLanding(), "the one landing this gate guards has already happened")
    }
}
