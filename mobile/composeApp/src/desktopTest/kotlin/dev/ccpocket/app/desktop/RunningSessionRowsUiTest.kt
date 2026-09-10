package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #358 — the RUNNING zone names the SESSION, not just its folder.
 *
 * Two turns running in one project used to collapse into a single row carrying the project name: which
 * of the two it would open was unknowable, and the second one had no row at all. The zone now expands to
 * one row per running session wherever the shell can name them, keeping the project as a muted second
 * fact, and falls back to the project row for a machine whose satellite link only reports the flag.
 */
@OptIn(ExperimentalTestApi::class)
class RunningSessionRowsUiTest {

    /**
     * The seed with its session pins cleared. [DesktopModel.runningVisible] drops a project already
     * represented by a running PIN (so one live thing never shows twice), and the seed pins s1 in
     * ~/code/cc-pocket — which would hide the very rows under test. Nothing else is changed: s1
     * ("Refactor auth module") and s2 ("Fix stream parser test") are both running in that one project.
     */
    private class NoPinsModel : SeedDesktopModel() {
        override val pins: List<DkPin> = emptyList()
        val picked = mutableListOf<String>()
        override fun selectSession(s: DkSession) {
            picked += s.sessionId
            super.selectSession(s)
        }
    }

    @Test
    fun twoRunningSessionsInOneProjectRenderAsTwoRowsEachNamingItself() = runComposeUiTest {
        setContent { PocketTheme { Sidebar(NoPinsModel()) } }
        waitForIdle()

        onAllNodes(hasTestTag("running:s1")).assertCountEquals(1)
        onAllNodes(hasTestTag("running:s2")).assertCountEquals(1)
        // …and the project row they replaced is gone: one row per live turn, not one per folder
        onAllNodes(hasTestTag("running:~/code/cc-pocket")).assertCountEquals(0)

        // each row LEADS with its own title — the whole point of the issue. The row is clickable, so its
        // texts merge INTO the tagged node: match them on the node itself, not as descendants.
        onAllNodes(hasTestTag("running:s1") and hasText("Refactor auth module")).assertCountEquals(1)
        onAllNodes(hasTestTag("running:s2") and hasText("Fix stream parser test")).assertCountEquals(1)
        // the project stays on the row as the muted second fact
        onAllNodes(hasTestTag("running:s2") and hasText("cc-pocket")).assertCountEquals(1)
    }

    /** A machine reached through its satellite link reports [DkProject.running] with no sessions behind
     *  it — that row must keep naming the project rather than vanishing. */
    @Test
    fun anotherMachinesRunningProjectKeepsItsProjectRow() = runComposeUiTest {
        setContent { PocketTheme { Sidebar(NoPinsModel()) } }
        waitForIdle()

        onAllNodes(hasTestTag("running:~/work/api-server") and hasText("api-server")).assertCountEquals(1)
    }

    @Test
    fun clickingARunningSessionRowOpensThatSession() = runComposeUiTest {
        val model = NoPinsModel()
        setContent { PocketTheme { Sidebar(model) } }
        waitForIdle()

        onNodeWithTag("running:s2").performClick()
        waitForIdle()

        assertEquals(listOf("s2"), model.picked, "the row opens the session it names, not the project's live one")
    }
}
