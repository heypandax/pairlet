package dev.ccpocket.daemon.approval

import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The daemon-wide pending-approval ledger (approval design M1): one pending map, one timeout policy,
 *  one verdict routing point, exactly-one-terminal-outcome semantics shared by all four gates. */
class ApprovalCoordinatorTest {
    private fun ask(id: String, convo: String = "c1", tool: String = "Bash", rule: String? = "git status") =
        PermissionAsk(convo, id, tool, "preview", title = "Run command", rule = rule)

    @Test
    fun verdict_resolves_exactly_once_and_duplicates_report_unclaimed() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val outcomes = mutableListOf<ApprovalOutcome>()
        coord.submit(ask("a1"), ApprovalSource.AGENT, owner = this, timeoutMs = 10_000, emit = { emitted += it }) { outcomes += it }
        assertIs<PermissionAsk>(emitted.single()) // submit surfaces the card

        assertTrue(coord.onVerdict(PermissionVerdict("c1", "a1", Decision.ALLOW)))
        val answered = assertIs<ApprovalOutcome.Answered>(outcomes.single())
        assertEquals(Decision.ALLOW, answered.verdict.decision)

        // a double-tap / late duplicate must NOT deliver a second outcome — the caller surfaces ask_expired
        assertFalse(coord.onVerdict(PermissionVerdict("c1", "a1", Decision.DENY)))
        assertEquals(1, outcomes.size)
        scope.cancel()
    }

    @Test
    fun timeout_withdraws_the_card_then_reports_timed_out() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = CopyOnWriteArrayList<Frame>() // the timeout fires on a background delay thread
        val outcomes = CopyOnWriteArrayList<ApprovalOutcome>()
        coord.submit(ask("a1"), ApprovalSource.SHELL, owner = this, timeoutMs = 50, emit = { emitted += it }) { outcomes += it }
        delay(500)

        val withdrawn = emitted.filterIsInstance<AskWithdrawn>().single()
        assertEquals("a1", withdrawn.askId)
        assertEquals(AskWithdrawnReason.TIMED_OUT, withdrawn.reason)
        assertIs<ApprovalOutcome.TimedOut>(outcomes.single())
        // and a verdict racing in after the timeout stays unclaimed
        assertFalse(coord.onVerdict(PermissionVerdict("c1", "a1", Decision.ALLOW)))
        assertEquals(1, outcomes.size)
        scope.cancel()
    }

    @Test
    fun withdraw_retires_the_card_and_wins_over_a_later_verdict() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val outcomes = mutableListOf<ApprovalOutcome>()
        coord.submit(ask("a1"), ApprovalSource.AGENT, owner = this, timeoutMs = 10_000, emit = { emitted += it }) { outcomes += it }

        assertTrue(coord.withdraw("c1", "a1"))
        assertEquals(AskWithdrawnReason.WITHDRAWN, emitted.filterIsInstance<AskWithdrawn>().single().reason)
        assertIs<ApprovalOutcome.Withdrawn>(outcomes.single())
        assertFalse(coord.withdraw("c1", "a1")) // already terminal
        assertFalse(coord.onVerdict(PermissionVerdict("c1", "a1", Decision.ALLOW)))
        scope.cancel()
    }

    @Test
    fun bulk_withdraw_is_scoped_to_the_owner_instance() = runBlocking {
        // a relaunched agent process gets a fresh PermissionBridge — the stale instance's cleanup must not
        // retire the new instance's open cards
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val ownerA = Any()
        val ownerB = Any()
        val emitted = mutableListOf<Frame>()
        val outcomesA = mutableListOf<ApprovalOutcome>()
        val outcomesB = mutableListOf<ApprovalOutcome>()
        coord.submit(ask("a1"), ApprovalSource.AGENT, ownerA, timeoutMs = 10_000, emit = { emitted += it }) { outcomesA += it }
        coord.submit(ask("a2"), ApprovalSource.AGENT, ownerA, timeoutMs = 10_000, emit = { emitted += it }) { outcomesA += it }
        coord.submit(ask("b1"), ApprovalSource.AGENT, ownerB, timeoutMs = 10_000, emit = { emitted += it }) { outcomesB += it }

        coord.withdrawAllFor(ownerA)
        assertEquals(2, outcomesA.size)
        assertTrue(outcomesA.all { it is ApprovalOutcome.Withdrawn })
        assertTrue(outcomesB.isEmpty(), "another owner's pending ask must survive")
        assertFalse(coord.hasPendingFor(ownerA))
        assertTrue(coord.hasPendingFor(ownerB))
        scope.cancel()
    }

    @Test
    fun snapshots_and_resurface_keep_arrival_order_and_exclude_questions() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val owner = Any()
        coord.submit(ask("a1"), ApprovalSource.AGENT, owner, timeoutMs = 10_000, emit = { }) { }
        coord.submit(ask("q1", tool = "AskUserQuestion"), ApprovalSource.AGENT, owner, timeoutMs = 10_000, isQuestion = true, emit = { }) { }
        coord.submit(ask("a2"), ApprovalSource.AGENT, owner, timeoutMs = 10_000, emit = { }) { }

        // rows: security approvals only (questions are conversation-scoped answer UI), arrival order
        assertEquals(listOf("a1", "a2"), coord.rowsFor(owner).map { it.ask.askId })
        // resurface: EVERY open ask returns to a reattaching sink, questions included, arrival order
        val resurfaced = mutableListOf<Frame>()
        coord.resurfaceFor(owner) { resurfaced += it }
        assertEquals(listOf("a1", "q1", "a2"), resurfaced.filterIsInstance<PermissionAsk>().map { it.askId })
        scope.cancel()
    }

    @Test
    fun same_askId_in_different_conversations_never_cross_resolves() = runBlocking {
        // askIds are only unique per CLI process — Codex mints JSON-RPC ids from a per-connection counter,
        // so two live conversations both ask as "1". A verdict must resolve ONLY the conversation it names
        // (crypto review: otherwise a guest's vetted own-convo verdict could approve an owner's ask).
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val outcomesA = mutableListOf<ApprovalOutcome>()
        val outcomesB = mutableListOf<ApprovalOutcome>()
        coord.submit(ask("1", convo = "owner-convo"), ApprovalSource.AGENT, Any(), timeoutMs = 10_000, emit = { }) { outcomesA += it }
        coord.submit(ask("1", convo = "guest-convo"), ApprovalSource.AGENT, Any(), timeoutMs = 10_000, emit = { }) { outcomesB += it }

        assertTrue(coord.onVerdict(PermissionVerdict("guest-convo", "1", Decision.ALLOW)))
        assertTrue(outcomesA.isEmpty(), "the owner's same-askId ask must be untouched")
        assertEquals(1, outcomesB.size)

        assertTrue(coord.onVerdict(PermissionVerdict("owner-convo", "1", Decision.DENY)))
        val ownerOutcome = assertIs<ApprovalOutcome.Answered>(outcomesA.single())
        assertEquals(Decision.DENY, ownerOutcome.verdict.decision)
        scope.cancel()
    }

    @Test
    fun source_snapshot_only_reports_that_sources_rows() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        coord.submit(ask("sh-1"), ApprovalSource.SHELL, Any(), timeoutMs = 10_000, emit = { }) { }
        coord.submit(ask("xp-1"), ApprovalSource.EXPORT, Any(), timeoutMs = 10_000, emit = { }) { }
        assertEquals(listOf("sh-1"), coord.rows(ApprovalSource.SHELL).map { it.ask.askId })
        assertEquals(listOf("xp-1"), coord.rows(ApprovalSource.EXPORT).map { it.ask.askId })
        scope.cancel()
    }
}
