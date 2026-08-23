package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rewind_caption_fork
import dev.ccpocket.app.resources.rewind_caption_rewound
import dev.ccpocket.app.resources.rewind_group_rewound
import dev.ccpocket.app.resources.st_complete
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.session.FORK_GLYPH
import dev.ccpocket.app.ui.session.REWOUND_GLYPH
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rewind/fork lineage as it REACHES THE SCREEN (issue #282, design frames D1/D2).
 *
 * `SessionLineageTest` proves the arithmetic of [dev.ccpocket.app.ui.session.splitRewound] and friends;
 * this file proves the mobile session list actually calls them. That gap is the exact failure mode this
 * feature already hit once — three green pure functions that no composable referenced, so the phone showed
 * a list with no fold group and no captions while every unit test passed. A pure-function test can never
 * catch that, because deleting the call sites leaves it green. These four assertions go red instead.
 *
 * Composed at the release baseline width (402 pt) but in a TALL scene: the fold group is emitted last, and
 * a LazyColumn only composes what it lays out, so a short viewport would make "the group is absent" and
 * "the group is off-screen" indistinguishable — and the first is the bug being guarded against.
 */
@OptIn(ExperimentalTestApi::class)
class SessionsRewindListUiTest {

    private val dir = "/Users/alex/code/cc-pocket"

    private fun summary(
        id: String,
        title: String,
        rewindOf: String? = null,
        forkedFrom: String? = null,
    ) = SessionSummary(
        sessionId = id, title = title, firstPrompt = "", messageCount = 4, cwd = dir,
        lastModified = 0L, gitBranch = "main", agent = AgentKind.CLAUDE,
        rewindOf = rewindOf, forkedFrom = forkedFrom,
    )

    private fun sessions(vararg items: SessionSummary) = Sessions(dir, items.toList())

    /** Compose the real [SessionsScreen] over a seeded Sessions frame. Static scene (nothing live, no
     *  approvals), so the clock is held still and every assertion reads the settled first frame. */
    private fun listScene(seed: Sessions, assertions: SkikoComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(402, 1600) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    val scope = rememberCoroutineScope()
                    val repo = remember { PocketRepository(scope).apply { receiveForTest(seed) } }
                    PocketTheme { Box(Modifier.fillMaxSize()) { SessionsScreen(repo) } }
                }
            }
            waitForIdle()
            assertions()
        }

    private fun SkikoComposeUiTest.tap(text: String) {
        onAllNodes(hasText(text)).onFirst().performClick()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /**
     * A rewind must not grow the list: the superseded original leaves the default view and reappears only
     * under the collapsed group, which counts it and says where it went.
     */
    @Test
    fun rewoundOriginalLeavesTheListAndReappearsUnderTheFoldGroup() = listScene(
        sessions(
            summary("branch", "Take 2", rewindOf = "orig"),
            summary("orig", "Review replay"),
            summary("peer", "Unrelated work"),
        ),
    ) {
        val group = str(Res.string.rewind_group_rewound, 1)
        assertTrue(present("Take 2"), "the rewind branch is an ordinary row")
        assertTrue(present("Unrelated work"), "an unrelated session is untouched by the fold")
        assertFalse(present("Review replay"), "the superseded original must NOT sit in the default list")
        assertTrue(present(group), "the fold group must be on screen, counting the one folded row")

        tap(group)
        assertTrue(present("Review replay"), "expanding the group reveals the original — it is not deleted")
        assertTrue(
            present(str(Res.string.rewind_caption_rewound, "Take 2")),
            "a folded row must say WHERE it went, or it just vanished",
        )
        assertTrue(present(REWOUND_GLYPH), "…behind the backward-pointing glyph, not the fork one")
    }

    /** A fork is the opposite: both sessions stay peers, and the child names its parent inline. */
    @Test
    fun forkChildStaysInTheListAndNamesItsParent() = listScene(
        sessions(
            summary("branch", "Attempt B", forkedFrom = "orig"),
            summary("orig", "Review replay"),
        ),
    ) {
        assertTrue(present("Attempt B"), "the fork child is an ordinary row")
        assertTrue(present("Review replay"), "a fork folds nothing away — the parent stays a peer")
        assertFalse(
            present(str(Res.string.rewind_group_rewound, 1)),
            "a fork must not produce a rewound group",
        )
        assertTrue(
            present(str(Res.string.rewind_caption_fork, "Review replay")),
            "the fork child must carry its lineage caption",
        )
        assertTrue(present(FORK_GLYPH), "…behind the branching glyph")
        assertFalse(present(REWOUND_GLYPH), "a fork is not a rewind — no backward pointer anywhere")
    }

    /** No lineage anywhere = no fold group at all. Guards against a group that renders empty or at zero. */
    @Test
    fun aPlainListShowsNoFoldGroup() = listScene(
        sessions(summary("a", "Plain one"), summary("b", "Plain two")),
    ) {
        assertTrue(present("Plain one") && present("Plain two"))
        assertFalse(present(str(Res.string.rewind_group_rewound, 0)), "no lineage, no group")
        assertFalse(present(str(Res.string.rewind_group_rewound, 1)), "no lineage, no group")
    }

    /**
     * Design frame D2: an expanded folded row is QUIET — title and destination only.
     *
     * The meta line is the specific thing being kept out. A fold exists to stop a rewind from lengthening
     * the list; reprinting "Complete · Claude · main · …" under every folded original would hand back most
     * of the height the fold just saved, and none of it says anything the group header hasn't.
     *
     * Asserted by counting: the ordinary rows keep their state word, so a bare absence check would pass
     * for the wrong reason. One row is visible here and one is folded, so exactly ONE "Complete" may be on
     * screen once the group is open.
     */
    @Test
    fun anExpandedFoldedRowCarriesNoMetaLine() = listScene(
        sessions(
            summary("branch", "Take 2", rewindOf = "orig"),
            summary("orig", "Review replay"),
        ),
    ) {
        val complete = str(Res.string.st_complete)
        tap(str(Res.string.rewind_group_rewound, 1))
        assertTrue(present("Review replay"), "the folded original is on screen")
        assertEquals(
            1,
            onAllNodes(hasText(complete, substring = true)).fetchSemanticsNodes().size,
            "only the ORDINARY row writes its state — a folded row carries no meta line",
        )
    }

    /**
     * The shape the device test actually produced: ONE parent with TWO children — rewound once and forked
     * once. Both edges are read off the same parent, and they must not cancel each other out: the parent
     * folds (a rewind claimed it) while still being nameable by the fork's caption (it is in the list,
     * just not in the visible half).
     */
    @Test
    fun oneParentRewoundAndForkedFoldsOnceAndStillNamesItself() = listScene(
        sessions(
            summary("fork-child", "Forked take", forkedFrom = "parent"),
            summary("rewind-child", "Rewound take", rewindOf = "parent"),
            summary("parent", "The original"),
        ),
    ) {
        assertTrue(present("Forked take") && present("Rewound take"), "both children are ordinary rows")
        assertFalse(present("The original"), "the parent folds — a rewind claimed it")
        assertTrue(
            present(str(Res.string.rewind_group_rewound, 1)),
            "folded exactly once, however many children point at it",
        )
        assertTrue(
            present(str(Res.string.rewind_caption_fork, "The original")),
            "a folded parent is still IN the list, so the fork child can still name it",
        )
    }
}
