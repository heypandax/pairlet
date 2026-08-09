package dev.ccpocket.app.ui.approval

import dev.ccpocket.app.data.ApprovalKey
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionRiskUpdated
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.oneOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Secure Approval CLASSIFIER (Mobile UI 2.0). Everything the sheet may draw is decided by
 * [approvalUi]; these are the rules that decide it, pinned without a composition.
 *
 * The through-line: a client may never offer an action the daemon did not offer, and may never turn a
 * capability into a recommendation.
 */
class ApprovalUiModelTest {

    private fun ask(
        tool: String = "Bash",
        grants: List<String>? = null,
        rule: String? = "Bash(ls:*)",
        danger: Boolean = false,
        neverRemember: Boolean = false,
        noAutoDeny: Boolean = false,
        timeoutSec: Int? = null,
        questions: List<AskQuestion>? = null,
    ) = PermissionAsk(
        convoId = "c1", askId = "a1", tool = tool, inputPreview = "ls", title = "Run command",
        rule = rule, grantOptions = grants, danger = danger, neverRemember = neverRemember,
        noAutoDeny = noAutoDeny, timeoutSec = timeoutSec, questions = questions,
    )

    private fun ids(ui: ApprovalUi) = ui.actions.map { it.id }

    // ── one-off: the flag, its legacy alias, and its precedence over everything ────────────────────

    @Test
    fun oneOffCoversNeverRememberAndTheLegacyExitPlanModeAliases() {
        assertTrue(ask(neverRemember = true).oneOff)
        assertTrue(ask(tool = "ExitPlanMode").oneOff, "a pre-neverRemember daemon is caught by the tool name")
        assertTrue(ask(tool = "exit_plan_mode").oneOff, "…in either spelling")
        assertFalse(ask().oneOff)
    }

    @Test
    fun oneOffOffersExactlyDenyAndAllowOnce() {
        val ui = approvalUi(ask(neverRemember = true))
        assertEquals(ApprovalFamily.ONE_OFF, ui.family)
        assertEquals(listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE), ids(ui))
        assertNull(ui.sessionAction, "a one-off decision must not become a standing rule")
    }

    @Test
    fun oneOffWinsOverAGrantAwareDaemonsOffer() {
        // the daemon offered every scope; the ask is still a one-off human decision
        val ui = approvalUi(ask(neverRemember = true, grants = listOf("once", "task", "session")))
        assertEquals(ApprovalFamily.ONE_OFF, ui.family)
        assertEquals(listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE), ids(ui))
        assertNull(ui.sessionAction)
    }

    @Test
    fun aShellCommandUnderAReviewHandoffIsOneOffEvenWhenScopesWereOffered() {
        val ui = approvalUi(ask(grants = listOf("once", "task", "session")), handoffReview = true)
        assertEquals(ApprovalFamily.ONE_OFF, ui.family)
        assertEquals(listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE), ids(ui))
        assertTrue(ui.recordedShell, "the body has to say the command is recorded")

        // only the command runner: a review handoff does not strip scopes from every tool
        val fetch = approvalUi(ask(tool = "WebFetch", grants = listOf("once", "task")), handoffReview = true)
        assertEquals(ApprovalFamily.V2, fetch.family)
        assertFalse(fetch.recordedShell)
    }

    // ── V2: capability, and only capability, decides availability ─────────────────────────────────

    @Test
    fun v2IsAnyNonNullGrantList_andAnEmptyOneNeverFallsBackToLegacy() {
        val empty = approvalUi(ask(grants = emptyList()))
        assertEquals(ApprovalFamily.V2, empty.family, "an empty offer is still a grant-aware daemon")
        assertEquals(
            listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE, ApprovalActionId.RETRY_SAFER), ids(empty),
        )
        assertFalse(
            ApprovalActionId.ALWAYS_ALLOW in ids(empty),
            "falling back to legacy here would hand out a session rule the daemon never offered",
        )
        assertNull(empty.sessionAction)
    }

    @Test
    fun v2RendersOnlyTheScopesThatWereOffered() {
        val onceOnly = approvalUi(ask(grants = listOf("once")))
        assertEquals(
            listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE, ApprovalActionId.RETRY_SAFER), ids(onceOnly),
        )
        assertNull(onceOnly.sessionAction)

        val task = approvalUi(ask(grants = listOf("once", "task")))
        assertEquals(
            listOf(
                ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE,
                ApprovalActionId.RETRY_SAFER, ApprovalActionId.ALLOW_TASK,
            ),
            ids(task),
        )
        assertNull(task.sessionAction, "session was not offered — no cell, and nothing behind More options")

        val session = approvalUi(ask(grants = listOf("once", "session")))
        assertFalse(ApprovalActionId.ALLOW_TASK in ids(session))
        assertEquals(ApprovalActionId.ALLOW_SESSION, session.sessionAction?.id)
        assertFalse(
            ApprovalActionId.ALLOW_SESSION in ids(session),
            "the broadest grant is never on the main grid — it stays one deliberate tap away",
        )
    }

    @Test
    fun unknownFutureScopesAreIgnoredRatherThanRendered() {
        val ui = approvalUi(ask(grants = listOf("once", "workspace", "forever", "TASK")))
        assertEquals(
            listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE, ApprovalActionId.RETRY_SAFER), ids(ui),
            "a scope this build can't honor degrades to ignored, never to a button",
        )
        assertNull(ui.sessionAction)
    }

    @Test
    fun legacyIsOnlyANullGrantList() {
        val ui = approvalUi(ask(grants = null))
        assertEquals(ApprovalFamily.LEGACY, ui.family)
        assertEquals(
            listOf(ApprovalActionId.DENY, ApprovalActionId.ALLOW_ONCE, ApprovalActionId.ALWAYS_ALLOW), ids(ui),
        )
        assertFalse(ApprovalActionId.RETRY_SAFER in ids(ui), "a pre-M2 daemon can't act on a structured retry")
        assertNull(ui.sessionAction)
    }

    // ── capability is not recommendation ──────────────────────────────────────────────────────────

    @Test
    fun anOrdinaryV2RequestCarriesNoRecommendationSignalAtAll() {
        val ui = approvalUi(ask(grants = listOf("once", "task", "session")))
        assertTrue(
            ui.actions.all { it.emphasis == ActionEmphasis.NEUTRAL },
            "\"Allow for task\" must not be filled merely because task scope exists",
        )
    }

    @Test
    fun dangerMovesEmphasisToLeastPrivilegeWithoutChangingTheActionSet() {
        val ordinary = approvalUi(ask(grants = listOf("once", "task", "session")))
        val danger = approvalUi(ask(grants = listOf("once", "task", "session"), danger = true))

        assertEquals(ids(ordinary), ids(danger), "danger is emphasis, never a different set of choices")
        assertEquals(ordinary.sessionAction?.id, danger.sessionAction?.id)
        assertEquals(ApprovalFamily.V2, danger.family)

        fun emphasis(ui: ApprovalUi, id: ApprovalActionId) = ui.actions.first { it.id == id }.emphasis
        assertEquals(ActionEmphasis.PRIMARY, emphasis(danger, ApprovalActionId.ALLOW_ONCE))
        assertEquals(ActionEmphasis.BOUNDARY, emphasis(danger, ApprovalActionId.DENY))
        assertEquals(ActionEmphasis.CAUTION, emphasis(danger, ApprovalActionId.ALLOW_TASK))
    }

    @Test
    fun aStandingGrantsSublabelIsTheRuleOrNothing() {
        val withRule = approvalUi(ask(grants = listOf("task", "session"), rule = "Bash(git status:*)"))
        assertEquals("Bash(git status:*)", withRule.actions.first { it.id == ApprovalActionId.ALLOW_TASK }.sublabel)
        assertEquals("Bash(git status:*)", withRule.sessionAction?.sublabel)

        val noRule = approvalUi(ask(grants = listOf("task", "session"), rule = null))
        assertNull(
            noRule.actions.first { it.id == ApprovalActionId.ALLOW_TASK }.sublabel,
            "absent rule → absent sublabel; the tool name is not the scope the daemon will remember",
        )
        assertNull(noRule.sessionAction?.sublabel)
    }

    // ── the question route never reaches this sheet ───────────────────────────────────────────────

    @Test
    fun isQuestionRoutesAwayFromTheApprovalSheetEvenForAnEmptyQuestionList() {
        assertTrue(ask(questions = listOf(AskQuestion("Which?"))).isQuestion)
        assertTrue(ask(questions = emptyList()).isQuestion, "an empty list is still an AskUserQuestion frame")
        assertFalse(ask(questions = null).isQuestion)
        assertFailsWith<IllegalArgumentException> {
            approvalUi(ask(questions = listOf(AskQuestion("Which?"))))
        }
    }

    // ── timer ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun noAutoDenyIsAWaitingStateWithNoCountdownToShow() {
        val ui = approvalUi(ask(grants = listOf("once"), noAutoDeny = true, timeoutSec = 86_400))
        assertEquals(ApprovalTimer.Waiting, ui.timer, "a renewed window has no number and no ∞ to draw")
        assertFalse(ui.isTerminal(0), "a card the daemon keeps renewing is never locally terminal")
    }

    @Test
    fun theLegacyThirtySecondFallbackSurvivesAMissingTimeout() {
        assertEquals(ApprovalTimer.Countdown(LEGACY_TIMEOUT_SEC), approvalUi(ask()).timer)
        assertEquals(ApprovalTimer.Countdown(600), approvalUi(ask(timeoutSec = 600)).timer)
        assertEquals(
            ApprovalTimer.Countdown(0), approvalUi(ask(timeoutSec = -10)).timer,
            "a malformed peer must not produce a negative countdown",
        )
    }

    @Test
    fun aLocalZeroIsATerminalDisplayOnlyForAPreM2AutoDenyingDaemon() {
        assertTrue(approvalUi(ask()).isTerminal(0), "the legacy card matches its daemon's own 30s auto-deny")
        assertFalse(
            approvalUi(ask(grants = listOf("once"))).isTerminal(0),
            "a grant-aware daemon can pause its budget — the local zero is a display floor, not a verdict",
        )
        assertTrue(
            approvalUi(ask(grants = listOf("once")), timedOutSignal = true).isTerminal(30),
            "only the daemon's own TIMED_OUT ends a grant-aware card",
        )
    }

    // ── queue and risk are modifiers, not variants ────────────────────────────────────────────────

    @Test
    fun theQueueCounterExistsOnlyWhenABurstReallyIsQueued() {
        assertNull(approvalUi(ask(), queueProgress = null).queue)
        assertNull(approvalUi(ask(), queueProgress = 1 to 1).queue, "there is no honest \"1 of 1\"")
        assertNull(approvalUi(ask(), queueProgress = 0 to 3).queue, "a nonsense position is dropped, not drawn")
        assertEquals(2 to 3, approvalUi(ask(), queueProgress = 2 to 3).queue)
    }

    @Test
    fun theFullRiskEventReachesTheUiAndChangesNothingElse() {
        val risk = PermissionRiskUpdated(
            "c1", "a1", "high", reason = "deletes files",
            reasonCodes = listOf("fs.delete.recursive"), assessedAt = 1_770_000_000_000,
        )
        val before = approvalUi(ask(grants = listOf("once", "task")), timedOutSignal = false)
        val after = approvalUi(ask(grants = listOf("once", "task")), risk = risk)

        assertNull(before.risk, "absence stays absence — never rendered as low or safe")
        assertEquals(risk, after.risk, "reason, codes and assessed time are evidence, not decoration")
        assertEquals(before.family, after.family, "a late assessment must not re-classify the request")
        assertEquals(before.actions, after.actions)
        assertEquals(before.timer, after.timer, "…and must not restart the countdown")

        val stale = risk.copy(convoId = "another-session")
        assertNull(approvalUi(ask(grants = listOf("once")), risk = stale).risk)
    }

    @Test
    fun anAbsentWorkdirOmitsTheProjectRowInsteadOfSubstitutingSomething() {
        assertNull(approvalUi(ask(), workdir = null).workdir)
        assertNull(approvalUi(ask(), workdir = "  ").workdir)
        assertEquals("/w/cc-pocket", approvalUi(ask(), workdir = "/w/cc-pocket").workdir)
    }

    // ── the repository seam: which withdrawal produces a terminal card ────────────────────────────

    @Test
    fun timedOutKeepsTheCardTerminalWhileOrdinaryWithdrawalAdvancesTheQueue() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.convoId.value = "c1"
        val a1 = PermissionAsk("c1", "ask-1", "Bash", "git status", timeoutSec = 60)
        val a2 = PermissionAsk("c1", "ask-2", "Bash", "git diff", timeoutSec = 60)
        repo.receiveForTest(a1)
        repo.receiveForTest(a2)

        // TIMED_OUT: the card STAYS, flipped to the read-only outcome the returning user needs to see
        repo.receiveForTest(AskWithdrawn("c1", "ask-1", AskWithdrawnReason.TIMED_OUT))
        assertEquals("ask-1", repo.pendingAsk.value?.askId, "a vanished card would have read as success")
        assertEquals(ApprovalKey("c1", "ask-1"), repo.timedOutAskId.value)
        val timedOut = approvalUi(repo.pendingAsk.value!!, timedOutSignal = repo.askTimedOut(repo.pendingAsk.value!!))
        assertTrue(timedOut.isTerminal(60), "the terminal state is the daemon's signal, not the local clock")

        // dismissing it (the only close that isn't a decision) advances to the queued ask
        repo.dismissAsk()
        assertEquals("ask-2", repo.pendingAsk.value?.askId)

        // ordinary withdrawal: no terminal, no dismissible remnant — the sheet just goes
        repo.receiveForTest(AskWithdrawn("c1", "ask-2"))
        assertNull(repo.pendingAsk.value)
        scope.cancel()
    }

    @Test
    fun theRepositorySurfacesTheWholeRiskEventNotJustItsLevel() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.convoId.value = "c1"
        val a = PermissionAsk("c1", "ask-1", "Bash", "rm -rf build", timeoutSec = 60)
        repo.receiveForTest(a)
        val risk = PermissionRiskUpdated(
            "c1", "ask-1", "high", reason = "deletes files",
            reasonCodes = listOf("fs.delete.recursive"), assessedAt = 1_770_000_000_000,
        )
        repo.receiveForTest(risk)

        assertEquals("high", repo.riskFor(a), "the level stays available for the desktop badge")
        val full = repo.riskDetailFor(a)
        assertNotNull(full)
        assertEquals("deletes files", full.reason)
        assertEquals(listOf("fs.delete.recursive"), full.reasonCodes)
        assertEquals(1_770_000_000_000, full.assessedAt)
        scope.cancel()
    }
}
