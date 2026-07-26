package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PendingApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One-off approval gate for a request submitted through an externally driven bridge.
 *
 * This is deliberately separate from [PermissionBridge]: the human approves the request before the
 * agent sees it, not a sequence of tools after execution has already started. An approval is consumed
 * by exactly one caller and is never recorded as an allow-rule.
 */
class BridgeRequestApprovalGate(
    private val convoId: String,
    private val scope: CoroutineScope,
    private val emit: suspend (Frame) -> Unit,
    private val timeoutMs: Long = ApprovalTimeout.bridgeMs(),
) {
    private class Pending(
        val ask: PermissionAsk,
        val expiresAt: Long,
        val result: CompletableDeferred<Boolean>,
        val timeout: Job,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Ask the owner to approve this exact request. No response is a denial and never starts execution. */
    suspend fun awaitApproval(preview: String): Boolean {
        val askId = "br-" + UUID.randomUUID()
        val result = CompletableDeferred<Boolean>()
        lateinit var timeout: Job
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
        )
        timeout = scope.launch {
            delay(timeoutMs)
            if (pending.remove(askId) != null) {
                emit(AskWithdrawn(convoId, askId, AskWithdrawnReason.TIMED_OUT))
                result.complete(false)
            }
        }
        pending[askId] = Pending(ask, System.currentTimeMillis() + timeoutMs, result, timeout)
        emit(ask)
        return try {
            result.await()
        } finally {
            timeout.cancel()
            // The bridge engine can be stopped/reconfigured while this coroutine waits. Retire the card
            // from the conversation scope in that cancellation path; otherwise the phone keeps a live-looking
            // approval whose caller no longer exists. Normal verdict/timeout/cancelAll already removed it.
            if (pending.remove(askId) != null && !result.isCompleted) {
                scope.launch { emit(AskWithdrawn(convoId, askId, AskWithdrawnReason.WITHDRAWN)) }
            }
        }
    }

    /** Returns true only when this gate owned the verdict. `remember` is intentionally ignored. */
    fun onVerdict(verdict: PermissionVerdict): Boolean {
        val request = pending.remove(verdict.askId) ?: return false
        request.timeout.cancel()
        request.result.complete(verdict.decision == Decision.ALLOW)
        return true
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Thread-safe point-in-time rows for the owner approval inbox. */
    fun pendingApprovals(): List<PendingApproval> =
        pending.values.map { PendingApproval(it.ask, expiresAt = it.expiresAt) }

    suspend fun resurfacePending(to: suspend (Frame) -> Unit) {
        pending.values.forEach { to(it.ask) }
    }

    suspend fun cancelAll() {
        val open = pending.entries.toList()
        pending.clear()
        open.forEach { (askId, request) ->
            request.timeout.cancel()
            emit(AskWithdrawn(convoId, askId, AskWithdrawnReason.WITHDRAWN))
            request.result.complete(false)
        }
    }

    private companion object {
        const val TOOL = "FeishuRequest"
        const val MAX_PREVIEW_CHARS = 12_000
    }
}
