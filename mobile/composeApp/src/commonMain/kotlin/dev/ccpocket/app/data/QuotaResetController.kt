package dev.ccpocket.app.data

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.protocol.QuotaResetResult
import dev.ccpocket.protocol.RedeemQuotaReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns one computer's reset attempt. Repository supplies transport/storage; UI only reads state.
 * An unresolved attempt keeps its original card and idempotency key across retries and reconnects. */
internal class QuotaResetController(
    private val scope: CoroutineScope,
    private val canSend: () -> Boolean,
    private val availableCount: () -> Long,
    private val newRequestId: () -> String,
    private val restore: () -> RedeemQuotaReset?,
    private val persist: (RedeemQuotaReset?) -> Unit,
    private val send: suspend (RedeemQuotaReset) -> Unit,
    private val refreshQuota: () -> Unit,
) {
    private val _busy = mutableStateOf(false)
    private val _needsRefresh = mutableStateOf(false)
    private val _outcome = mutableStateOf<String?>(null)
    private val request = mutableStateOf<RedeemQuotaReset?>(null)
    private var deadline: Job? = null
    private var dispatchJob: Job? = null

    val busy: State<Boolean> = _busy
    val needsRefresh: State<Boolean> = _needsRefresh
    val outcome: State<String?> = _outcome
    val pending: Boolean get() = request.value != null
    val canRedeem: Boolean get() = canSend() && !_busy.value && !_needsRefresh.value && !pending && availableCount() > 0
    val canRetry: Boolean get() = canSend() && !_busy.value && pending && _outcome.value != null

    fun redeem(creditId: String?): Boolean {
        if (!canSend() || _busy.value) return false
        restorePending()
        if (!canRedeem) return false
        dispatch(RedeemQuotaReset(newRequestId(), creditId))
        return true
    }

    fun retry(): Boolean {
        if (!canSend() || _busy.value) return false
        restorePending()
        // A lost reply may have consumed the last credit. Retrying queries the ORIGINAL operation,
        // so it must remain possible even when the refreshed balance is zero.
        val original = request.value ?: return false
        dispatch(original)
        return true
    }

    fun onResult(result: QuotaResetResult) {
        if (result.requestId != request.value?.requestId) return
        deadline?.cancel(); deadline = null
        _busy.value = false
        _outcome.value = result.outcome
        _needsRefresh.value = true
        if (result.outcome in TERMINAL_OUTCOMES) {
            request.value = null
            persist(null)
        }
        refreshQuota()
    }

    /** Called only for a successful, correlated Codex quota refresh. */
    fun onQuotaRefreshed() {
        _needsRefresh.value = false
        restorePending()
    }

    /** Forget the departed computer's in-memory state; its persisted operation stays recoverable. */
    fun reset() {
        deadline?.cancel(); deadline = null
        dispatchJob?.cancel(); dispatchJob = null
        request.value = null
        _busy.value = false
        _needsRefresh.value = false
        _outcome.value = null
    }

    private fun restorePending() {
        if (request.value != null) return
        val saved = restore() ?: return
        request.value = saved
        _outcome.value = "unknown"
    }

    private fun dispatch(attempt: RedeemQuotaReset) {
        persist(attempt) // Save before sending: a process exit must not lose the idempotency key.
        request.value = attempt
        _busy.value = true
        _outcome.value = null
        deadline?.cancel()
        deadline = scope.launch {
            delay(RESULT_TIMEOUT_MS)
            _busy.value = false
            _outcome.value = "unknown"
        }
        dispatchJob?.cancel()
        dispatchJob = scope.launch { send(attempt) }
    }

    companion object {
        internal const val RESULT_TIMEOUT_MS = 15_000L
        private val TERMINAL_OUTCOMES = setOf("reset", "alreadyRedeemed", "noCredit", "nothingToReset")
    }
}
