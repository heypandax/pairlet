package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ccpocket.app.resources.mode_auto_short
import dev.ccpocket.app.resources.mode_accept_short
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.ccpocket.app.resources.new_session_mode_unavailable
import dev.ccpocket.app.resources.value_model_default
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
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
import kotlin.test.assertTrue
import dev.ccpocket.app.present

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

    // ══ #363 retention matrix — desktop side of the same user actions as NewSessionModeRetentionUiTest ══

    private data class Start(val agent: AgentKind, val mode: PermissionMode, val native: String?, val model: String?)

    private val alpha = dev.ccpocket.app.ui.ModelChoice("Model Alpha", "m-alpha", "m-alpha", "", false)
    private val beta = dev.ccpocket.app.ui.ModelChoice("Model Beta", "m-beta", "m-beta", "", false)

    private class Caps {
        var autoAvailable by androidx.compose.runtime.mutableStateOf(false)
        var models by androidx.compose.runtime.mutableStateOf(listOf<dev.ccpocket.app.ui.ModelChoice>())
    }

    private fun matrix(
        defaultAgent: AgentKind,
        defaultMode: PermissionMode,
        defaultNative: String? = null,
        caps: Caps = Caps(),
        starts: MutableList<Start>,
        body: androidx.compose.ui.test.SkikoComposeUiTest.(Caps) -> Unit,
    ) = runDesktopComposeUiTest(420, 1600) {
        setContent {
            PocketTheme {
                NewSessionPopover(
                    initialPath = "/w/proj",
                    defaultAgent = defaultAgent,
                    availableAgents = AgentKind.entries,
                    defaultMode = defaultMode,
                    defaultPermissionMode = defaultNative,
                    autoAvailable = caps.autoAvailable,
                    modelsFor = { caps.models },
                    onStart = { _, a, m, n, model, _ -> starts += Start(a, m, n, model) },
                )
            }
        }
        waitForIdle()
        body(caps)
    }

    /** Opens the model disclosure (its summary reads the "Default" label while nothing is chosen) and picks [name]. */
    private fun androidx.compose.ui.test.SkikoComposeUiTest.pickModel(name: String, currentSummary: String) {
        tap(currentSummary)
        tap(name)
    }

    private fun sameAgentModelSwitchKeeps(agent: AgentKind) {
        val starts = mutableListOf<Start>()
        val defaultLabel = str(Res.string.value_model_default)
        matrix(agent, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { models = listOf(alpha, beta) }, starts = starts) {
            pickModel("Model Alpha", defaultLabel)
            pickModel("Model Beta", "Model Alpha")
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(agent, PermissionMode.BYPASS_PERMISSIONS, null, "m-beta")), starts, "$agent")
    }

    @Test fun claudeModelSwitchKeepsDefaultFullAccess() = sameAgentModelSwitchKeeps(AgentKind.CLAUDE)
    @Test fun codexModelSwitchKeepsDefaultFullAccess() = sameAgentModelSwitchKeeps(AgentKind.CODEX)
    @Test fun kimiModelSwitchKeepsDefaultFullAccess() = sameAgentModelSwitchKeeps(AgentKind.KIMI)
    @Test fun dshModelSwitchKeepsDefaultFullAccess() = sameAgentModelSwitchKeeps(AgentKind.DSH)
    @Test fun zcodeModelSwitchKeepsDefaultFullAccess() = sameAgentModelSwitchKeeps(AgentKind.ZCODE)
    // OpenCode: the popover forces BYPASS for this agent by construction, so the MODE half is constant here —
    // the case only pins that the model pick still travels; it is not evidence about mode retention.
    @Test fun openCodeModelPickTravelsModeIsConstant() = sameAgentModelSwitchKeeps(AgentKind.OPENCODE)

    @Test
    fun aHandPickedRungSurvivesModelSwitchFollowDefaultAndLateLists() {
        val starts = mutableListOf<Start>()
        val defaultLabel = str(Res.string.value_model_default)
        matrix(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, starts = starts) { caps ->
            tap(str(Res.string.mode_plan_short))
            caps.models = listOf(alpha, beta); waitForIdle()
            pickModel("Model Beta", defaultLabel)
            caps.models = listOf(beta, alpha); waitForIdle()
            pickModel(defaultLabel, "Model Beta")
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.PLAN, null, null)), starts)
    }

    @Test
    fun defaultFullAccessSurvivesAutoCapabilityArrivingRepeatingAndLeaving() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, starts = starts) { caps ->
            caps.autoAvailable = true; waitForIdle()
            caps.autoAvailable = true; waitForIdle()
            caps.autoAvailable = false; waitForIdle()
            caps.autoAvailable = true; waitForIdle()
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, null, null)), starts)
    }

    /** Settings default = Claude native Auto, popover opened before ModelsList advertised it. */
    @Test
    fun defaultNativeAutoIsRestoredWhenItsCapabilityLandsLate() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.DEFAULT, CLAUDE_PERMISSION_MODE_AUTO, starts = starts) { caps ->
            caps.autoAvailable = true; waitForIdle()
            tap(str(Res.string.new_path_start))
        }
        assertEquals(
            listOf(Start(AgentKind.CLAUDE, PermissionMode.DEFAULT, CLAUDE_PERMISSION_MODE_AUTO, null)),
            starts,
            "the persisted Auto default must be selected once the capability arrives, not left on per-step Default",
        )
    }

    @Test
    fun aHandPickedRungSurvivesCapabilityRefresh() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, starts = starts) { caps ->
            tap(str(Res.string.mode_plan_short))
            caps.autoAvailable = true; waitForIdle()
            caps.autoAvailable = false; waitForIdle()
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.PLAN, null, null)), starts)
    }

    /**
     * Settings default = Full access, the user picks Auto, then the capability disappears. The old re-carry fell
     * back to the Settings default and started FULL ACCESS — a silent escalation. Now: no start, a reason, and
     * the same answer becomes valid again when the capability returns.
     */
    @Test
    fun aHandPickedNativeAutoThatLosesItsCapabilityNeverBecomesFullAccess() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { autoAvailable = true }, starts = starts) { caps ->
            tap(str(Res.string.mode_auto_short))
            caps.autoAvailable = false; waitForIdle()
            tap(str(Res.string.new_path_start))
            assertTrue(
                starts.none { it.mode == PermissionMode.BYPASS_PERMISSIONS },
                "a withdrawn Auto must never start as Full access: $starts",
            )
            assertEquals(emptyList(), starts, "an answer the computer no longer offers must not start")
            assertTrue(present(str(Res.string.new_session_mode_unavailable)), "the popover says why")
            caps.autoAvailable = true; waitForIdle()
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.DEFAULT, CLAUDE_PERMISSION_MODE_AUTO, null)), starts)
    }

    /**
     * #363 review P1: an ANSWER the target agent does not offer must not fall back to the Settings default.
     * Settings = Full access, the user picks Accept edits on Claude and switches to dsh (no Accept edits):
     * the old carry landed on the default index — BYPASS — and started it. Now the answer is kept, flagged
     * invalid with the reason, and nothing starts (click or Enter) until the user picks a rung.
     */
    private fun answerMissingOnTargetAgentNeverFallsBackToFullAccess(pick: String, autoAvailable: Boolean) {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { this.autoAvailable = autoAvailable }, starts = starts) {
            tap(pick)
            tap(agentName(AgentKind.DSH))
            tap(str(Res.string.new_path_start))
            pressEnterInPath()
            assertTrue(starts.none { it.mode == PermissionMode.BYPASS_PERMISSIONS }, "fell back to Full access: $starts")
            assertEquals(emptyList(), starts, "an answer dsh does not offer must not start")
            assertTrue(present(str(Res.string.new_session_mode_unavailable)), "the popover says why")
            tap(str(Res.string.mode_plan_short))
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.DSH, PermissionMode.PLAN, null, null)), starts)
    }

    @Test fun acceptEditsToDshNeverFallsBackToFullAccess() =
        answerMissingOnTargetAgentNeverFallsBackToFullAccess(str(Res.string.mode_accept_short), autoAvailable = false)

    @Test fun nativeAutoToDshNeverFallsBackToFullAccess() =
        answerMissingOnTargetAgentNeverFallsBackToFullAccess(str(Res.string.mode_auto_short), autoAvailable = true)

    /** …and switching back to an agent that offers the answer makes it valid again, unchanged. */
    @Test
    fun switchingBackRestoresTheKeptAnswer() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, starts = starts) {
            tap(str(Res.string.mode_accept_short))
            tap(agentName(AgentKind.DSH))
            tap(agentName(AgentKind.CLAUDE))
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.ACCEPT_EDITS, null, null)), starts)
    }

    private fun androidx.compose.ui.test.SkikoComposeUiTest.pressEnterInPath() {
        onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        waitForIdle()
    }

    /** Review P2: the Enter shortcut obeys the same validity gate as the Start button (positive control included). */
    @Test
    fun enterDoesNotStartAnInvalidModeButStartsAValidOne() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CLAUDE, PermissionMode.DEFAULT, caps = Caps().apply { autoAvailable = true }, starts = starts) { caps ->
            tap(str(Res.string.mode_auto_short))
            caps.autoAvailable = false; waitForIdle()
            pressEnterInPath()
            assertEquals(emptyList(), starts, "Enter must not start an answer the computer no longer offers")
            caps.autoAvailable = true; waitForIdle()
            pressEnterInPath()
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.DEFAULT, CLAUDE_PERMISSION_MODE_AUTO, null)), starts)
    }

    /** Existing desktop cross-agent policy (carry the rung the target also has) — pinned, not changed. */
    @Test
    fun switchingFromCodexToClaudeCarriesFullAccess() {
        val starts = mutableListOf<Start>()
        matrix(AgentKind.CODEX, PermissionMode.BYPASS_PERMISSIONS, starts = starts) {
            tap(agentName(AgentKind.CLAUDE))
            tap(str(Res.string.new_path_start))
        }
        assertEquals(listOf(Start(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, null, null)), starts)
    }
}
