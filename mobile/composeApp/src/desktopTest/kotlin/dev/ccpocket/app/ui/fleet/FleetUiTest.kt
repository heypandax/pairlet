package dev.ccpocket.app.ui.fleet

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless tests for the MOBILE fleet surfaces (commonMain composables rendered on the desktop test
 * scene). Demo mode feeds the four-machine design scenario, so these exercise the same code paths the
 * phone runs: the Fleet home overview, the Attention inbox triage loop, and the demo resolve store.
 */
@OptIn(ExperimentalTestApi::class)
class FleetUiTest {

    @BeforeTest
    fun resetDemo() = DemoFleet.reset()

    @Test
    fun fleetHomeShowsTheLiveOverview() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) }
        }
        waitForIdle()
        assertPresent("Your computers")
        assertPresent("4 machines · 3 online · 2 waiting approval") // FleetStrip
        assertPresent("2 approvals waiting")                        // the attention banner
        assertPresent("Lidapeng-MacBook")
        assertPresent("▶ 2 running", substring = true)              // ActivityLine
        assertPresent("mac-studio")
        assertPresent("devbox-linux")
        assertPresent("win-desktop")
        assertPresent("Pair a new computer")
    }

    @Test
    fun attentionInboxTriagesAndResolves() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { AttentionInboxScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent("Needs you")
        assertPresent("NEEDS APPROVAL")
        assertPresent("rm -rf ./build && ./gradlew clean")   // mac-studio's Bash ask (soonest timeout first)
        assertPresent("Run command · Bash")
        assertPresent("RECENTLY FINISHED")
        assertPresent("Refactor auth module")                // a finished row
        assertEquals(2, DemoFleet.attention().size)
        onAllNodes(hasText("Allow")).onFirst().performClick() // resolve the first (soonest) entry
        waitForIdle()
        assertEquals(1, DemoFleet.attention().size)          // it leaves the queue
        assertTrue(!present("rm -rf ./build && ./gradlew clean"), "resolved ask leaves the inbox")
    }

    @Test
    fun attentionInboxEmptyStateIsAllClear() = runComposeUiTest {
        DemoFleet.attention().forEach { DemoFleet.resolve(it.askId) } // drain the queue
        setContent {
            val repo = PocketRepository(rememberCoroutineScope()).apply { demoMode.value = true }
            PocketTheme { AttentionInboxScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent("All clear — nothing needs you")
    }

    @Test
    fun crossMachineBannerAggregatesEntries() = runComposeUiTest {
        val entries = DemoFleet.attention() // two cross-machine approvals from the demo fleet
        setContent { PocketTheme { CrossMachineBanner(entries, onReview = {}) } }
        waitForIdle()
        assertPresent("2 approvals waiting")
        assertPresent("mac-studio, devbox-linux", substring = true)
        assertPresent("Review")
    }

    @Test
    fun demoFleetBadgesFollowTheQueue() {
        assertEquals(1, DemoFleet.machines().first { it.name == "mac-studio" }.pending)
        DemoFleet.resolve("demo-ask-1")
        assertEquals(0, DemoFleet.machines().first { it.name == "mac-studio" }.pending)
        assertEquals(1, DemoFleet.machines().first { it.name == "devbox-linux" }.pending)
    }
}
