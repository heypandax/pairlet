package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BridgeRequestApprovalGateTest {
    @Test
    fun approval_is_one_off_and_remember_is_ignored() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val gate = BridgeRequestApprovalGate("c1", coord, scope, { emitted += it }, timeoutMs = 10_000)

        val first = async { gate.awaitApproval("sender: ou_a\n\nrun tests") }
        yield()
        val firstAsk = assertIs<PermissionAsk>(emitted.single())
        assertTrue(firstAsk.neverRemember)
        assertTrue(firstAsk.danger)
        assertEquals("full access for this request", firstAsk.dangerNote)
        assertEquals("FeishuRequest", firstAsk.tool)
        assertTrue(coord.onVerdict(PermissionVerdict("c1", firstAsk.askId, Decision.ALLOW, remember = true)))
        assertTrue(first.await())

        emitted.clear()
        val second = async { gate.awaitApproval("sender: ou_a\n\nrun tests again") }
        yield()
        val secondAsk = assertIs<PermissionAsk>(emitted.single())
        assertNotEquals(firstAsk.askId, secondAsk.askId)
        assertFalse(second.isCompleted, "the first approval must not authorize a later request")
        assertTrue(coord.onVerdict(PermissionVerdict("c1", secondAsk.askId, Decision.DENY)))
        assertFalse(second.await())
        scope.cancel()
    }

    @Test
    fun over_limit_request_is_refused_without_ever_showing_a_card() = runBlocking {
        // SECURITY regression guard: an approval grants full-turn authority to the WHOLE request, so a
        // request that cannot be shown in full must be denied outright — never shown truncated and run
        // whole. A malicious tail hidden past the preview cut inside a long quoted message must not run.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val gate = BridgeRequestApprovalGate("c1", coord, scope, { emitted += it }, timeoutMs = 10_000)

        val oversized = "x".repeat(BridgeRequestApprovalGate.MAX_PREVIEW_CHARS + 1)
        val result = async { gate.awaitApproval(oversized) }
        yield()

        assertFalse(result.await(), "an un-showable request must be denied, not partially shown")
        assertTrue(emitted.isEmpty(), "no approval card may reach the owner for a truncated request")
        assertFalse(gate.hasPending())
        scope.cancel()
    }

    @Test
    fun at_limit_request_is_shown_in_full() = runBlocking {
        // A legitimately vetted request (≤ BridgeGuard.MAX_PROMPT_CHARS + header) must never be refused
        // by the fail-closed guard and must reach the owner un-truncated.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val gate = BridgeRequestApprovalGate("c1", coord, scope, { emitted += it }, timeoutMs = 10_000)

        val atLimit = "y".repeat(BridgeRequestApprovalGate.MAX_PREVIEW_CHARS)
        val result = async { gate.awaitApproval(atLimit) }
        yield()
        val ask = assertIs<PermissionAsk>(emitted.single())
        assertEquals(atLimit, ask.inputPreview, "the full request must be shown, not a truncated preview")
        assertTrue(coord.onVerdict(PermissionVerdict("c1", ask.askId, Decision.ALLOW)))
        assertTrue(result.await())
        scope.cancel()
    }

    @Test
    fun pending_request_resurfaces_and_timeout_withdraws_it() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val gate = BridgeRequestApprovalGate("c1", coord, scope, { emitted += it }, timeoutMs = 20)

        val result = async { gate.awaitApproval("exact request") }
        yield()
        val ask = assertIs<PermissionAsk>(emitted.single())
        val snapshot = gate.pendingApprovals().single()
        assertEquals(ask, snapshot.ask)
        assertTrue(requireNotNull(snapshot.expiresAt) > System.currentTimeMillis())
        val resurfaced = mutableListOf<Frame>()
        gate.resurfacePending { resurfaced += it }
        assertEquals(ask, resurfaced.single())

        delay(40)
        assertFalse(result.await())
        assertIs<AskWithdrawn>(emitted.last())
        assertFalse(gate.hasPending())
        assertTrue(gate.pendingApprovals().isEmpty())
        scope.cancel()
    }

    @Test
    fun cancelling_the_waiter_withdraws_the_orphaned_card() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val gate = BridgeRequestApprovalGate("c1", coord, scope, { emitted += it }, timeoutMs = 10_000)

        val waiter = async { gate.awaitApproval("exact request") }
        yield()
        assertIs<PermissionAsk>(emitted.single())
        waiter.cancelAndJoin()
        yield()

        assertIs<AskWithdrawn>(emitted.last())
        assertFalse(gate.hasPending())
        scope.cancel()
    }
}
