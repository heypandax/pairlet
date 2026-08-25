package dev.ccpocket.app.ui.fleet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.computers_title
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.fl_attention_title
import dev.ccpocket.app.resources.fl_clear_title
import dev.ccpocket.app.resources.fl_current
import dev.ccpocket.app.resources.fl_details
import dev.ccpocket.app.resources.fl_none_waiting
import dev.ccpocket.app.resources.fl_pair_new
import dev.ccpocket.app.resources.fl_pending_a11y_many
import dev.ccpocket.app.resources.fl_pending_a11y_one
import dev.ccpocket.app.resources.fl_sec_approval
import dev.ccpocket.app.resources.fl_sec_finished
import dev.ccpocket.app.resources.fl_status_offline
import dev.ccpocket.app.resources.fl_status_online
import dev.ccpocket.app.resources.fl_status_reconnecting
import dev.ccpocket.app.resources.fl_summary_computers_many
import dev.ccpocket.app.resources.fl_unlinked_title
import dev.ccpocket.app.resources.fl_summary_online
import dev.ccpocket.app.resources.fl_waiting_many
import dev.ccpocket.app.resources.fl_waiting_one
import dev.ccpocket.app.resources.st_act_review
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless tests for the MOBILE fleet surfaces (commonMain composables rendered on the desktop test scene).
 * Demo mode feeds the four-machine design scenario, so these exercise the same code paths the phone runs.
 *
 * Every assertion resolves its words through [str] rather than pinning English. That is not politeness: the
 * whole point of the Supporting Surfaces pass was that the fleet used to CONCATENATE English fragments in the
 * view-model, and a test that matched those literals would pass just as happily if they came back.
 */
@OptIn(ExperimentalTestApi::class)
class FleetUiTest {

    @BeforeTest
    fun resetDemo() = DemoFleet.reset()

    // ── the pure model: counts and states, no sentences ───────────────────────────────────────────

    /** Exactly three statuses exist. A row that is not online carries no invented fourth state. */
    @Test
    fun thereAreExactlyThreeMachineStatuses() {
        assertEquals(3, MachineStatus.entries.size)
        assertEquals(
            setOf(MachineStatus.ONLINE, MachineStatus.RECONNECTING, MachineStatus.OFFLINE),
            MachineStatus.entries.toSet(),
        )
        // …and the offline demo machine says nothing about work it cannot see
        val win = DemoFleet.machines().first { it.name == "win-desktop" }
        assertEquals(MachineStatus.OFFLINE, win.status)
        assertEquals(MachineActivity.Unknown, win.activity)
    }

    /** The badge is a real count, and zero means the badge is simply not there. */
    @Test
    fun demoFleetBadgesFollowTheQueue() {
        assertEquals(1, DemoFleet.machines().first { it.name == "mac-studio" }.pending)
        DemoFleet.resolve("demo-ask-1")
        assertEquals(0, DemoFleet.machines().first { it.name == "mac-studio" }.pending)
        assertEquals(1, DemoFleet.machines().first { it.name == "devbox-linux" }.pending)
        // a handled machine drops back to idle rather than keeping a stale "waiting" line
        assertEquals(MachineActivity.Idle, DemoFleet.machines().first { it.name == "mac-studio" }.activity)
    }

    // ── the words: singular, plural, and both languages by construction ───────────────────────────

    /** One computer is "1 computer", four are "4 computers" — the plural is real, not an "(s)". */
    @Test
    fun theSummaryPluralisesForRealInWhicheverLanguageIsLoaded() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
        }
        waitForIdle()
        val summary = "${str(Res.string.fl_summary_computers_many, 4)} · " +
            "${str(Res.string.fl_summary_online, 3)} · ${str(Res.string.fl_waiting_many, 2)}"
        assertPresent(summary)
        // the English fragments the old UI-model concatenated must not be reachable any more
        assertFalse(present("4 machines · 3 online", substring = true), "the summary is composed from resources")
    }

    @Test
    fun oneWaitingApprovalReadsAsOneNotAsAPluralWithAnS() = runComposeUiTest {
        DemoFleet.resolve("demo-ask-2") // leaves exactly one in the queue
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.fl_waiting_one, 1))
    }

    // ── Fleet home ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun fleetHomeShowsTheFirstHopHierarchyAndAWrittenStatusPerMachine() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.computers_title))
        assertPresent("Lidapeng-MacBook")
        assertPresent("mac-studio")
        assertPresent("devbox-linux")
        assertPresent("win-desktop")
        // the status is WRITTEN — three online rows and one offline one, never colour alone
        assertEquals(
            3, onAllNodes(hasText(str(Res.string.fl_status_online))).fetchSemanticsNodes().size,
            "each online machine states it in words",
        )
        assertPresent(str(Res.string.fl_status_offline))
        assertFalse(present(str(Res.string.fl_status_reconnecting)), "no machine in this scenario is reconnecting")
        // the current binding says so in words too
        assertPresent(str(Res.string.fl_current))
        // real paths and tools stay literal
        assertPresent("~/proj/app/cc-pocket", substring = true)
        assertPresent(str(Res.string.fl_pair_new))
    }

    /** The badge speaks: a bare accent "1" is not a state a screen reader can convey. */
    @Test
    fun thePendingBadgeCarriesItsMeaningAndDisappearsAtZero() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
        }
        waitForIdle()
        // two machines hold one approval each; the two with none carry no badge at all
        assertEquals(
            2,
            onAllNodes(hasContentDescription(str(Res.string.fl_pending_a11y_one, 1))).fetchSemanticsNodes().size,
            "exactly the machines that really hold an approval are badged",
        )
        assertEquals(
            0,
            onAllNodes(hasContentDescription(str(Res.string.fl_pending_a11y_many, 0))).fetchSemanticsNodes().size,
            "zero pending renders no badge",
        )
    }

    @Test
    fun theAttentionStripOnlyExistsWhenSomethingIsReallyWaiting() = runComposeUiTest {
        DemoFleet.attention().forEach { DemoFleet.resolve(it.askId) } // drain the queue
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
        }
        waitForIdle()
        assertFalse(present(str(Res.string.st_act_review)), "no pending approval → no attention strip")
    }

    // ── Attention inbox ───────────────────────────────────────────────────────────────────────────

    /** A decision is only offered once the request is on screen in full. */
    @Test
    fun aRequestOpensItsDetailBeforeItOffersAllowOrDeny() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { AttentionInboxScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.fl_attention_title))
        assertPresent(str(Res.string.fl_sec_approval).uppercase())
        assertPresent("Run command · Bash")
        assertPresent(str(Res.string.fl_sec_finished).uppercase())
        assertPresent("Refactor auth module") // a finished row
        // collapsed: no decision is reachable yet
        assertFalse(present(str(Res.string.allow)), "Allow must not sit under a truncated command")
        assertFalse(present(str(Res.string.deny)))

        onAllNodes(hasText(str(Res.string.fl_details))).onFirst().performClick() // soonest entry first
        waitForIdle()
        assertPresent("rm -rf ./build && ./gradlew clean") // mac-studio's Bash ask, in full
        assertEquals(2, DemoFleet.attention().size)
        onAllNodes(hasText(str(Res.string.allow))).onFirst().performClick()
        waitForIdle()
        assertEquals(1, DemoFleet.attention().size, "the resolved ask leaves the queue")
        assertFalse(present("rm -rf ./build && ./gradlew clean"), "…and its row with it")
    }

    /** All-clear is a CLAIM, and it needs a live link behind it. With one, an empty queue reads as empty. */
    @Test
    fun attentionInboxEmptyStateIsAllClear() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { connected.value = true }
            PocketTheme { AttentionInboxScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.fl_clear_title))
        assertPresent(str(Res.string.fl_none_waiting))
    }

    /**
     * …and WITHOUT one it must not. An unreachable computer can be blocked on an approval right now; "all
     * clear" here is how someone puts the phone down on a request that then expires unanswered.
     */
    @Test
    fun anUnlinkedInboxSaysSoInsteadOfClaimingAllClear() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()) // never connected
            PocketTheme { AttentionInboxScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.fl_unlinked_title))
        assertFalse(present(str(Res.string.fl_clear_title)), "an empty queue we could not read is not an empty queue")
        assertFalse(present(str(Res.string.fl_none_waiting)), "…and the header must not claim it either")
    }

    /** Finished-only is its own layout: the history stays, and the header says nothing is waiting. */
    @Test
    fun aFinishedOnlyInboxKeepsItsHistoryAndClaimsNoQueue() = runComposeUiTest {
        DemoFleet.attention().forEach { DemoFleet.resolve(it.askId) }
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { AttentionInboxScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.fl_sec_finished).uppercase())
        assertPresent("Refactor auth module")
        assertFalse(present(str(Res.string.fl_sec_approval).uppercase()), "an empty queue gets no section")
        assertFalse(present(str(Res.string.allow)), "and no decision without a request behind it")
    }

    /**
     * The release baseline, at double type: nothing may spill past the 402 pt viewport.
     *
     * `Density(1f, 2f)` makes one scene pixel one dp, so the bounds below are the real thing — this is the
     * "rows grow, they do not clip" half of the acceptance criteria rather than a smoke test.
     */
    @Test
    fun theFleetSurvivesTwoHundredPercentType() = runDesktopComposeUiTest(402, 874) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
                PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
            }
        }
        waitForIdle()
        listOf(str(Res.string.computers_title), str(Res.string.fl_current), "Lidapeng-MacBook").forEach { text ->
            val b = onAllNodes(hasText(text, substring = true)).onFirst().getUnclippedBoundsInRoot()
            assertTrue(b.left.value >= -0.5f, "\"$text\" starts off-screen at ${b.left}")
            assertTrue(b.right.value <= 402.5f, "\"$text\" overflows the 402pt viewport, ending at ${b.right}")
        }
    }

    @Test
    fun crossMachineBannerAggregatesEntries() = runComposeUiTest {
        val entries = DemoFleet.attention() // two cross-machine approvals from the demo fleet
        setContent { PocketTheme { CrossMachineBanner(entries, onReview = {}) } }
        waitForIdle()
        assertPresent(str(Res.string.fl_waiting_many, 2))
        assertPresent("mac-studio, devbox-linux", substring = true)
        assertTrue(present(str(Res.string.st_act_review)))
    }
}
