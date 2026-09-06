package dev.ccpocket.app.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.mode_bypass_short
import dev.ccpocket.app.resources.mode_plan_short
import dev.ccpocket.app.resources.new_path_start
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Issue #333 review (HIGH), through the REAL popover rather than its resolver.
 *
 * [carryModeAcrossAgents] is unit-tested next door, but the defect lived in the composable's STATE: a
 * positional `modeIdx` that `remember` never re-keyed on the agent. A correct resolver wired to a stale
 * index is still broken, and the only thing that can tell them apart is driving the widget and reading
 * what it hands to `onStart` — which is exactly what the user gets.
 *
 * The failure being pinned is not cosmetic. Under Claude, "Plan" is index 2 of four rungs; dsh's ladder
 * is default/plan/bypass, so index 2 was **Full access** — the popover started a session with the
 * dangerous mode after the user had explicitly chosen the safest one.
 */
@OptIn(ExperimentalTestApi::class)
class NewSessionModeCarryUiTest {

    private fun popover(
        onStart: (String, AgentKind, PermissionMode, String?, String?, String?) -> Unit,
        body: androidx.compose.ui.test.SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(420, 1400) {
        setContent {
            PocketTheme {
                NewSessionPopover(
                    initialPath = "/w/proj",
                    defaultAgent = AgentKind.CLAUDE,
                    availableAgents = listOf(AgentKind.CLAUDE, AgentKind.DSH),
                    onStart = onStart,
                )
            }
        }
        waitForIdle()
        body()
    }

    private fun androidx.compose.ui.test.SkikoComposeUiTest.tap(text: String) {
        onAllNodes(hasText(text)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    @Test
    fun choosingPlanUnderClaudeThenSwitchingToDshStartsPlanNotFullAccess() {
        var started: PermissionMode? = null
        var startedAgent: AgentKind? = null
        popover(onStart = { _, agent, mode, _, _, _ -> startedAgent = agent; started = mode }) {
            tap(str(Res.string.mode_plan_short))
            tap(agentName(AgentKind.DSH))
            tap(str(Res.string.new_path_start))
        }
        assertEquals(AgentKind.DSH, startedAgent)
        assertEquals(PermissionMode.PLAN, started, "the chosen rung must survive the agent switch")
        assertNotEquals(
            PermissionMode.BYPASS_PERMISSIONS,
            started,
            "the selection slid onto the dangerous rung — this is the #333 review defect",
        )
    }

    /** …and the same in reverse: nothing about the carry may depend on which direction you switch. */
    @Test
    fun theRungAlsoSurvivesSwitchingBackToClaude() {
        var started: PermissionMode? = null
        popover(onStart = { _, _, mode, _, _, _ -> started = mode }) {
            tap(str(Res.string.mode_plan_short))
            tap(agentName(AgentKind.DSH))
            tap(agentName(AgentKind.CLAUDE))
            tap(str(Res.string.new_path_start))
        }
        assertEquals(PermissionMode.PLAN, started)
    }

    /** A rung picked AFTER the switch still wins — the carry must not pin the selection. */
    @Test
    fun aRungChosenAfterTheSwitchIsTheOneThatStarts() {
        var started: PermissionMode? = null
        popover(onStart = { _, _, mode, _, _, _ -> started = mode }) {
            tap(str(Res.string.mode_plan_short))
            tap(agentName(AgentKind.DSH))
            tap(str(Res.string.mode_bypass_short))
            tap(str(Res.string.new_path_start))
        }
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, started)
    }
}
