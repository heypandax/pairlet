package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.approval.ApprovalOutcome
import dev.ccpocket.daemon.approval.ApprovalSource
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PendingApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One-off approval gate for a request submitted through an externally driven bridge — the
 * BRIDGE_REQUEST source adapter of [ApprovalCoordinator] (approval design M1).
 *
 * This is deliberately separate from [PermissionBridge]: the human approves the request before the
 * agent sees it, not a sequence of tools after execution has already started. An approval is consumed
 * by exactly one caller and is never recorded as an allow-rule.
 */
class BridgeRequestApprovalGate(
    private val convoId: String,
    private val coordinator: ApprovalCoordinator,
    private val scope: CoroutineScope,
    private val emit: suspend (Frame) -> Unit,
    private val timeoutMs: Long = ApprovalTimeout.bridgeMs(),
) {
    /** Ask the owner to approve this exact request. No response is a denial and never starts execution. */
    suspend fun awaitApproval(preview: String): Boolean {
        // SECURITY: an OWNER_APPROVED verdict grants full-turn authority to the ENTIRE request, so the
        // owner must have seen the entire request. If the preview would be truncated, the tail the owner
        // never read would still run with full authority — an untrusted member can hide a malicious
        // instruction past the cut inside a long quoted message. Fail closed: refuse rather than show a
        // partial request under full grant. MAX_PREVIEW_CHARS is sized above BridgeGuard.MAX_PROMPT_CHARS
        // (the vet ceiling) plus header headroom, so no legitimately vetted request is ever refused here.
        if (preview.length > MAX_PREVIEW_CHARS) return false
        val askId = "br-" + UUID.randomUUID()
        val result = CompletableDeferred<Boolean>()
        val ask = PermissionAsk(
            convoId = convoId,
            askId = askId,
            tool = TOOL,
            inputPreview = preview.take(MAX_PREVIEW_CHARS),
            mode = PermissionMode.DEFAULT,
            title = "Approve Feishu request",
            danger = true,
            dangerNote = "full access for this request",
            neverRemember = true,
            timeoutSec = (timeoutMs / 1000).toInt(),
            grantOptions = listOf("once"), // M2: a request approval is one-off by construction
        )
        coordinator.submit(ask, ApprovalSource.BRIDGE_REQUEST, owner = this, timeoutMs = timeoutMs, emit = emit) { outcome ->
            // `remember` is intentionally ignored: a request approval is one-off by construction
            result.complete(outcome is ApprovalOutcome.Answered && outcome.verdict.decision == Decision.ALLOW)
        }
        return try {
            result.await()
        } finally {
            // The bridge engine can be stopped/reconfigured while this coroutine waits. Retire the card
            // in that cancellation path; otherwise the phone keeps a live-looking approval whose caller no
            // longer exists. Normal verdict/timeout/cancelAll already resolved it (withdraw no-ops then).
            if (!result.isCompleted) scope.launch { coordinator.withdraw(convoId, askId) }
        }
    }

    fun hasPending(): Boolean = coordinator.hasPendingFor(this)

    /** Thread-safe point-in-time rows for the owner approval inbox. */
    fun pendingApprovals(): List<PendingApproval> = coordinator.rowsFor(this)

    suspend fun resurfacePending(to: suspend (Frame) -> Unit) = coordinator.resurfaceFor(this, to)

    suspend fun cancelAll() = coordinator.withdrawAllFor(this)

    internal companion object {
        const val TOOL = "FeishuRequest"
        // Above BridgeGuard.MAX_PROMPT_CHARS (32 KiB, the vet ceiling) + header headroom, so a vetted
        // request is always shown to the owner in full. The awaitApproval guard fails closed above this.
        const val MAX_PREVIEW_CHARS = 32 * 1024 + 2_048
    }
}
