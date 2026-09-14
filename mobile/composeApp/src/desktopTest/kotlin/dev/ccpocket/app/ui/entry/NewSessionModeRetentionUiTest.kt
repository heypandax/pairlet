package dev.ccpocket.app.ui.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.cfg_mode_accept
import dev.ccpocket.app.resources.cfg_mode_auto
import dev.ccpocket.app.resources.cfg_mode_full
import dev.ccpocket.app.resources.cfg_mode_plan
import dev.ccpocket.app.resources.cfg_model_follow
import dev.ccpocket.app.resources.cfg_start
import dev.ccpocket.app.resources.cfm_cta
import dev.ccpocket.app.resources.cfm_title
import dev.ccpocket.app.resources.codex_preset_autonomous
import dev.ccpocket.app.resources.codex_preset_cautious
import dev.ccpocket.app.resources.new_session_mode_unavailable
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.ModelChoice
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentModePreset
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #363 — the MOBILE new-session sheet's mode retention matrix, through the real composable.
 *
 * Each test drives the same user action a person makes (tap a model, let a capability list land, open the
 * confirmation, …) and reads what [ConfigureSessionSheet] hands to `onPick` — the exact tuple App.kt forwards
 * into `PocketRepository.openSession`. Capability inputs are Compose state so a late / repeated / shrinking
 * ModelsList can be replayed mid-interaction without a daemon.
 */
@OptIn(ExperimentalTestApi::class)
class NewSessionModeRetentionUiTest {

    private data class Pick(val mode: PermissionMode, val agent: AgentKind, val native: String?, val model: String?)

    private val alpha = ModelChoice("Model Alpha", "m-alpha", "m-alpha", "", false)
    private val beta = ModelChoice("Model Beta", "m-beta", "m-beta", "", false)

    /** Mutable capability inputs: what a late / repeated ModelsList changes under the open sheet. */
    private class Caps {
        var autoAvailable by mutableStateOf(false)
        var models by mutableStateOf(listOf<ModelChoice>())
        var codexPresets by mutableStateOf(listOf<AgentModePreset>())
        var open by mutableStateOf(true)
        var wide by mutableStateOf(false)
    }

    private fun sheet(
        agent: AgentKind,
        persisted: PermissionMode,
        persistedNative: String? = null,
        caps: Caps = Caps(),
        picks: MutableList<Pick>,
        body: SkikoComposeUiTest.(Caps) -> Unit,
    ) = runDesktopComposeUiTest(900, 1600) {
        setContent {
            PocketTheme {
                // the host keys nothing on width: a wide/narrow flip must not remount the sheet
                Box(Modifier.width(if (caps.wide) 820.dp else 402.dp)) {
                    if (caps.open) ConfigureSessionSheet(
                        workdir = "/w/proj",
                        selected = persisted,
                        selectedNativeMode = persistedNative,
                        agent = agent,
                        computer = "box",
                        autoAvailable = caps.autoAvailable,
                        modelsFor = { caps.models },
                        defaultModelFor = { null },
                        modePresetsFor = { a -> if (a == AgentKind.CODEX) caps.codexPresets else emptyList() },
                        onPick = { m, a, n, model, _ -> picks += Pick(m, a, n, model) },
                        onDismiss = {},
                    )
                }
            }
        }
        advanceFrameAndWait()
        body(caps)
    }

    private fun SkikoComposeUiTest.tap(text: String) {
        onAllNodes(hasText(text)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        advanceFrameAndWait()
    }

    private fun SkikoComposeUiTest.start() {
        onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        advanceFrameAndWait()
    }

    private fun SkikoComposeUiTest.confirmIfAsked() {
        if (present(str(Res.string.cfm_title))) tap(str(Res.string.cfm_cta))
    }

    // ══ 模型选择：同 Agent A→B／跟随默认／列表迟到与重排 ═══════════════════════════════════════════

    private fun sameAgentModelSwitchKeeps(agent: AgentKind, persisted: PermissionMode) {
        val picks = mutableListOf<Pick>()
        val caps = Caps().apply { models = listOf(alpha, beta) }
        sheet(agent, persisted, caps = caps, picks = picks) {
            tap("Model Alpha")
            tap("Model Beta")
            start(); confirmIfAsked()
        }
        assertEquals(listOf(Pick(persisted, agent, null, "m-beta")), picks, "$agent: a model tap must not move the mode")
    }

    @Test fun claudeModelSwitchKeepsPersistedFullAccess() = sameAgentModelSwitchKeeps(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS)
    @Test fun codexModelSwitchKeepsPersistedFullAccess() = sameAgentModelSwitchKeeps(AgentKind.CODEX, PermissionMode.BYPASS_PERMISSIONS)
    @Test fun kimiModelSwitchKeepsPersistedFullAccess() = sameAgentModelSwitchKeeps(AgentKind.KIMI, PermissionMode.BYPASS_PERMISSIONS)
    @Test fun dshModelSwitchKeepsPersistedFullAccess() = sameAgentModelSwitchKeeps(AgentKind.DSH, PermissionMode.BYPASS_PERMISSIONS)
    @Test fun zcodeModelSwitchKeepsPersistedFullAccess() = sameAgentModelSwitchKeeps(AgentKind.ZCODE, PermissionMode.BYPASS_PERMISSIONS)
    // OpenCode has a single BYPASS row, so the MODE half is constant by construction — this only pins that the
    // model pick still travels; it is not evidence about mode retention.
    @Test fun openCodeModelPickTravelsModeIsConstant() = sameAgentModelSwitchKeeps(AgentKind.OPENCODE, PermissionMode.BYPASS_PERMISSIONS)

    @Test
    fun aHandPickedRungSurvivesModelSwitchAndFollowDefault() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { models = listOf(alpha, beta) }, picks = picks) {
            tap(str(Res.string.cfg_mode_plan))
            tap("Model Beta")
            tap(str(Res.string.cfg_model_follow))
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.PLAN, AgentKind.CLAUDE, null, null)), picks)
    }

    @Test
    fun aLateOrReorderedModelListDoesNotMoveTheMode() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) { caps ->
            caps.models = listOf(alpha, beta); advanceFrameAndWait() // list lands after open
            tap("Model Alpha")
            caps.models = listOf(beta, alpha); advanceFrameAndWait() // refresh reorders it
            caps.models = listOf(beta, alpha); advanceFrameAndWait() // …and repeats identically
            start(); confirmIfAsked()
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CLAUDE, null, "m-alpha")), picks)
    }

    // ══ 模式来源 × 能力刷新 ════════════════════════════════════════════════════════════════════════

    @Test
    fun persistedFullAccessSurvivesAutoCapabilityArrivingRepeatingAndLeaving() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) { caps ->
            caps.autoAvailable = true; advanceFrameAndWait()
            caps.autoAvailable = true; advanceFrameAndWait()
            caps.autoAvailable = false; advanceFrameAndWait()
            caps.autoAvailable = true; advanceFrameAndWait()
            start(); confirmIfAsked()
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CLAUDE, null, null)), picks)
    }

    @Test
    fun persistedNativeAutoIsRestoredWhenItsCapabilityLandsLate() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.DEFAULT, CLAUDE_PERMISSION_MODE_AUTO, picks = picks) { caps ->
            caps.autoAvailable = true; advanceFrameAndWait()
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.DEFAULT, AgentKind.CLAUDE, CLAUDE_PERMISSION_MODE_AUTO, null)), picks)
    }

    @Test
    fun aHandPickedRungSurvivesCapabilityRefresh() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) { caps ->
            tap(str(Res.string.cfg_mode_plan))
            caps.autoAvailable = true; advanceFrameAndWait()
            caps.models = listOf(alpha); advanceFrameAndWait()
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.PLAN, AgentKind.CLAUDE, null, null)), picks)
    }

    @Test
    fun codexPersistedFullAccessSurvivesAdvertisedVocabularyArrivingAndGrowing() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CODEX, PermissionMode.BYPASS_PERMISSIONS, picks = picks) { caps ->
            caps.codexPresets = listOf(
                AgentModePreset(PermissionMode.DEFAULT, "balanced", "Balanced", recommended = true),
                AgentModePreset(PermissionMode.BYPASS_PERMISSIONS, "full", "Full access", danger = true),
            ); advanceFrameAndWait()
            caps.codexPresets = caps.codexPresets + AgentModePreset(PermissionMode.PLAN, "cautious", "Cautious")
            advanceFrameAndWait()
            caps.codexPresets = emptyList(); advanceFrameAndWait() // empty = not advertised → built-in table
            start(); confirmIfAsked()
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CODEX, null, null)), picks)
    }

    /**
     * Contract 2: a hand-picked rung the refreshed capability no longer offers is not submittable — the
     * sheet says why, starts nothing, and never substitutes another rung. When the capability returns the
     * same answer is valid again.
     */
    @Test
    fun aHandPickedNativeAutoThatLosesItsCapabilityCannotStart() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { autoAvailable = true }, picks = picks) { caps ->
            tap(str(Res.string.cfg_mode_auto))
            caps.autoAvailable = false; advanceFrameAndWait()
            assertFalse(present(str(Res.string.cfg_mode_auto)), "the Auto row is gone from the ladder")
            assertTrue(present(str(Res.string.new_session_mode_unavailable)), "the sheet must say why it cannot start")
            start(); confirmIfAsked()
            assertEquals(emptyList(), picks, "an answer the computer no longer offers must not be submitted")
            caps.autoAvailable = true; advanceFrameAndWait()
            assertFalse(present(str(Res.string.new_session_mode_unavailable)))
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.DEFAULT, AgentKind.CLAUDE, CLAUDE_PERMISSION_MODE_AUTO, null)), picks)
    }

    @Test
    fun aHandPickedCodexPresetDroppedByTheDaemonCannotStartUntilRepicked() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CODEX, PermissionMode.DEFAULT, picks = picks) { caps ->
            tap(str(Res.string.codex_preset_autonomous))
            caps.codexPresets = listOf(
                AgentModePreset(PermissionMode.DEFAULT, "balanced", "Balanced", recommended = true),
                AgentModePreset(PermissionMode.PLAN, "cautious", "Cautious"),
            ); advanceFrameAndWait()
            assertTrue(present(str(Res.string.new_session_mode_unavailable)))
            start(); confirmIfAsked()
            assertEquals(emptyList(), picks, "a dropped preset must not ride out to the daemon")
            tap(str(Res.string.codex_preset_cautious))
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.PLAN, AgentKind.CODEX, null, null)), picks)
    }

    /** The confirmation page for Full access backs out when the daemon withdraws Full access under it. */
    @Test
    fun aConfirmationWhoseModeIsWithdrawnReturnsToThePanel() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CODEX, PermissionMode.BYPASS_PERMISSIONS, picks = picks) { caps ->
            start()
            assertTrue(present(str(Res.string.cfm_title)))
            caps.codexPresets = listOf(AgentModePreset(PermissionMode.DEFAULT, "balanced", "Balanced", recommended = true))
            advanceFrameAndWait()
            assertFalse(present(str(Res.string.cfm_title)), "the confirmed snapshot is gone — back to the panel")
            assertTrue(present(str(Res.string.new_session_mode_unavailable)))
            start()
        }
        assertEquals(emptyList(), picks)
    }

    // ══ 面板生命周期 ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun closeAndReopenSeedsFromThePersistedDefaultAgain() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) { caps ->
            tap(str(Res.string.cfg_mode_plan))
            caps.open = false; advanceFrameAndWait()
            caps.open = true; advanceFrameAndWait()
            start(); confirmIfAsked()
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CLAUDE, null, null)), picks)
    }

    @Test
    fun capabilityRefreshDuringTheFullAccessConfirmationStartsWhatWasConfirmed() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { models = listOf(alpha) }, picks = picks) { caps ->
            tap("Model Alpha")
            start()
            assertTrue(present(str(Res.string.cfm_title)))
            caps.autoAvailable = true; advanceFrameAndWait()
            caps.models = listOf(beta, alpha); advanceFrameAndWait()
            assertTrue(present(str(Res.string.cfm_title)), "a refresh must not drop or bypass the confirmation")
            val cta = onAllNodes(hasText(str(Res.string.cfm_cta))).onFirst()
            cta.performSemanticsAction(SemanticsActions.OnClick)
            // the confirmation leaves the tree after the first tap; a second callback has nothing to fire
            advanceFrameAndWait()
            if (present(str(Res.string.cfm_cta))) tap(str(Res.string.cfm_cta))
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CLAUDE, null, "m-alpha")), picks)
    }

    @Test
    fun cancelThenDoubleStartStillStartsOnce() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.KIMI, PermissionMode.BYPASS_PERMISSIONS, picks = picks) {
            start()
            tap(str(Res.string.cancel))
            assertEquals(0, picks.size, "cancel starts nothing")
            start(); tap(str(Res.string.cfm_cta))
            start() // a second Start after the latch
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.KIMI, null, null)), picks)
    }

    // ══ 跨 Agent：保存的默认沿用（#363），手选按值对旅行 ══════════════════════════════════════════

    /**
     * #363. The saved default is the USER'S answer, not the opening agent's.
     *
     * The 09-13 report is a person who had set Full access, found the sheet opened on Codex (App.kt persists
     * the last-picked agent on every start), tapped Claude — and got step-by-step approvals they never chose.
     * Claude HAS a Full-access rung, so the saved pair seeds it, and the confirmation still stands between
     * that tap and the start.
     */
    @Test
    fun theSavedDefaultSeedsTheAgentSwitchedTo() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CODEX, PermissionMode.BYPASS_PERMISSIONS, caps = Caps().apply { models = listOf(alpha) }, picks = picks) {
            tap(agentName(AgentKind.CLAUDE))
            tap("Model Alpha")
            assertTrue(present(str(Res.string.cfg_mode_full)), "the saved rung is the one on screen")
            start()
            assertTrue(present(str(Res.string.cfm_title)), "Full access confirms wherever it was seeded from")
            tap(str(Res.string.cfm_cta))
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CLAUDE, null, "m-alpha")), picks)
    }

    /** …but only a pair the target really offers: an impossible one falls back to that agent's own default. */
    @Test
    fun aSavedRungTheTargetLacksFallsBackToThatAgentsOwnDefault() {
        val picks = mutableListOf<Pick>()
        sheet(
            AgentKind.CLAUDE, PermissionMode.DEFAULT, CLAUDE_PERMISSION_MODE_AUTO,
            caps = Caps().apply { autoAvailable = true }, picks = picks,
        ) {
            assertTrue(present(str(Res.string.cfg_mode_auto)), "Claude opens on its native Auto")
            tap(agentName(AgentKind.DSH))
            start()
            assertFalse(present(str(Res.string.cfm_title)), "dsh's own default needs no confirmation")
        }
        assertEquals(
            listOf(Pick(PermissionMode.DEFAULT, AgentKind.DSH, null, null)), picks,
            "Claude's native Auto is not a dsh mode — dsh seeds from its own default, not from a wider one",
        )
    }

    /**
     * Contract 2 across agents: a HAND-PICKED rung the target has no row for is kept and gated, never
     * quietly replaced — least of all by the saved default, which here is the widest rung there is.
     */
    @Test
    fun aHandPickedRungTheTargetLacksBlocksStartInsteadOfFallingBackToTheSavedDefault() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) {
            tap(str(Res.string.cfg_mode_accept))
            tap(agentName(AgentKind.KIMI))
            assertTrue(present(str(Res.string.new_session_mode_unavailable)), "Kimi has no Accept edits rung")
            start(); confirmIfAsked()
            assertEquals(emptyList(), picks, "the saved Full access must never fill the hole")
            tap(agentName(AgentKind.CLAUDE))
            assertFalse(present(str(Res.string.new_session_mode_unavailable)))
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.ACCEPT_EDITS, AgentKind.CLAUDE, null, null)), picks)
    }

    /** OpenCode has no ladder to carry anything into: it runs `--auto`, and the sheet states that. */
    @Test
    fun openCodeStatesItsOwnModeRatherThanCarryingAHandPickedRung() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) {
            tap(str(Res.string.cfg_mode_plan))
            tap(agentName(AgentKind.OPENCODE))
            start(); confirmIfAsked()
        }
        assertEquals(
            listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.OPENCODE, null, null)), picks,
            "a Plan rung must not ride out to a backend that approves everything anyway",
        )
    }

    @Test
    fun aHandPickedRungComesBackAfterADetourThroughOpenCode() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) {
            tap(str(Res.string.cfg_mode_plan))
            tap(agentName(AgentKind.OPENCODE))
            tap(agentName(AgentKind.CLAUDE))
            start()
        }
        assertEquals(listOf(Pick(PermissionMode.PLAN, AgentKind.CLAUDE, null, null)), picks)
    }

    @Test
    fun switchingBackToTheOpenedAgentRestoresThePersistedDefault() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CODEX, PermissionMode.BYPASS_PERMISSIONS, picks = picks) {
            tap(agentName(AgentKind.CLAUDE))
            tap(agentName(AgentKind.CODEX))
            start(); confirmIfAsked()
        }
        assertEquals(listOf(Pick(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CODEX, null, null)), picks)
    }

    @Test
    fun fullAccessLabelIsWhatTheSheetShowsForTheSeed() {
        val picks = mutableListOf<Pick>()
        sheet(AgentKind.CLAUDE, PermissionMode.BYPASS_PERMISSIONS, picks = picks) {
            assertTrue(present(str(Res.string.cfg_mode_full)))
        }
    }
}
