package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.*
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared by focused/side panes but keyed by random prompt identity, never by 'current conversation'. */
internal class PromptOutcomeTracker(
    private val scope: CoroutineScope,
    private val foreground: () -> Boolean,
    private val responseTimeoutMs: () -> Long = { 45_000L },
    private val emit: (TelEvent, Map<TelKey, Any>) -> Unit = Telemetry::track,
    private val captureConsent: () -> (() -> Boolean) = { TelemetryConsent.capture() },
) {
    private class Entry(val promptId: String, var convoId: String, val dimensions: Map<TelKey, Any>, val negotiated: Boolean,
                        val consent: () -> Boolean, emit: (TelEvent, Map<TelKey, Any>) -> Unit) {
        private val traceId = Diagnostics.newId()
        val response = ProductOutcome(TelEvent.PromptResponseResult, dimensions, emit, consent)
        val turn = ProductOutcome(TelEvent.TurnResult, dimensions, emit, consent)
        val trace = (if (dimensions[TelKey.UsageMode] == "demo") null else Diagnostics.begin(ErrorPath.PROMPT, traceId))?.also { it.stage(Stage.REQUEST) }
        var context = DiagnosticContext(traceId, spanId = trace?.spanId)
        var acknowledged = false
        var consumed = false
        var responseDeadline: Job? = null
        var output = false
        var value = false
    }
    private val entries = linkedMapOf<String, Entry>()
    private val layoutRevision = mutableStateOf(0)
    fun layoutToken(convoId: String?): String? {
        layoutRevision.value
        return entries.values.firstOrNull { it.convoId == convoId && it.output && !it.value }?.context?.traceId
    }
    fun visible(convoId: String, token: String, messages: List<ChatItem>, lastVisibleContent: Int) {
        val entry = entries.values.firstOrNull { it.convoId == convoId && it.context.traceId == token && it.output && !it.value } ?: return
        val promptRow = messages.indexOfLast { it is ChatItem.User && it.promptId == entry.promptId }
        if (promptRow < 0 || lastVisibleContent <= promptRow) return
        entry.value = true
        layoutRevision.value++
        if (entry.consent()) emit(TelEvent.ValueReached, entry.dimensions + mapOf(TelKey.Feature to "prompt_task", TelKey.Coverage to "complete"))
    }
    fun request(frame: SendPrompt, supported: Boolean, dimensions: Map<TelKey, Any>): SendPrompt {
        val id = frame.promptId ?: return frame
        val entry = entries[id] ?: Entry(id, frame.convoId, dimensions, supported, captureConsent(), emit).also {
            if (entries.size >= 256) {
                entries.remove(entries.keys.first())?.let(::abandon)
            }
            entries[id] = it
            if (it.consent()) emit(TelEvent.FeatureUsed, dimensions + mapOf(TelKey.Feature to "prompt_task"))
            if (!supported) {
                it.response.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
                it.turn.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
                it.trace?.finish(Outcome.UNKNOWN, Stage.REQUEST, ErrorCode.UNSUPPORTED,
                    metrics = SafeMetrics(resultQuality = ResultQuality.UNKNOWN))
            }
        }
        if (entry.convoId != frame.convoId) {
            entry.responseDeadline?.cancel()
            entry.convoId = frame.convoId
            entry.acknowledged = false; entry.consumed = false
            entry.context = entry.context.copy(attempt = (entry.context.attempt + 1).coerceAtMost(1000))
            entry.trace?.retry()
        } // business retry keeps one product attempt; old conversation receipts no longer match
        return frame.copy(diagnostic = entry.context.takeIf { supported && entry.negotiated })
    }
    fun progress(frame: PromptProgress) {
        val context = frame.diagnostic.validated() ?: return
        val entry = entries.values.firstOrNull { it.context == context && it.convoId == frame.convoId && it.negotiated } ?: return
        when (frame.stage) {
            "ack" -> { entry.acknowledged = true; entry.trace?.stage(Stage.ACK) }
            "queued" -> { entry.acknowledged = true; entry.trace?.stage(Stage.QUEUE) }
            "consumed" -> {
                if (!entry.consumed) {
                    entry.consumed = true
                    entry.trace?.stage(Stage.EXECUTE)
                    if (!foreground()) responseExpired(entry.convoId, entry.promptId, false, false)
                    else entry.responseDeadline = scope.launch {
                        delay(responseTimeoutMs())
                        if (entries[entry.promptId] === entry && !entry.output)
                            responseExpired(entry.convoId, entry.promptId, foreground(), false)
                    }
                }
            }
            "awaiting_redelivery" -> entry.trace?.stage(Stage.QUEUE, ErrorCode.PROCESS_EXITED)
            "first_output" -> output(entry)
            "unobserved" -> abandon(entry)
            "handled" -> {
                entry.responseDeadline?.cancel()
                entry.response.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
                entry.turn.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
                entry.trace?.finish(Outcome.UNKNOWN, Stage.COMPLETE, ErrorCode.UNSUPPORTED)
            }
            "complete" -> {
                entry.responseDeadline?.cancel()
                val result = when (frame.result) {
                    "success" -> ProductResult.SUCCESS
                    "failure" -> ProductResult.FAILURE
                    "cancelled" -> ProductResult.CANCELLED
                    else -> ProductResult.UNKNOWN
                }
                if (frame.output) output(entry)
                val reason = when (result) {
                    ProductResult.FAILURE -> ErrorCode.PROCESS_EXITED
                    ProductResult.UNKNOWN -> ErrorCode.INCOMPLETE
                    else -> ErrorCode.OK
                }
                entry.response.finish(if (result == ProductResult.SUCCESS) ProductResult.UNKNOWN else result,
                    if (result == ProductResult.SUCCESS) ErrorCode.INCOMPLETE else reason, Coverage.PARTIAL)
                entry.turn.finish(result, reason,
                    if (result == ProductResult.UNKNOWN) Coverage.PARTIAL else Coverage.COMPLETE)
                entry.trace?.finish(when (result) {
                    ProductResult.SUCCESS -> Outcome.SUCCESS
                    ProductResult.FAILURE -> Outcome.FAILURE
                    ProductResult.CANCELLED -> Outcome.CANCELLED
                    else -> Outcome.UNKNOWN
                }, Stage.COMPLETE, reason, metrics = SafeMetrics(resultQuality =
                    if (result == ProductResult.UNKNOWN) ResultQuality.UNKNOWN else ResultQuality.COMPLETE))
            }
        }
    }
    fun acknowledged(convoId: String, promptId: String) {
        entries[promptId]?.takeIf { it.convoId == convoId }?.acknowledged = true
    }
    fun receiptExpired(convoId: String?, promptId: String?, foreground: Boolean) {
        val entry = entries[promptId]?.takeIf { it.convoId == convoId && !it.acknowledged && !it.output } ?: return
        entry.response.finish(if (foreground) ProductResult.TIMEOUT else ProductResult.WAITING,
            if (foreground) ErrorCode.TIMEOUT else ErrorCode.INCOMPLETE, Coverage.PARTIAL)
        entry.trace?.finish(if (foreground) Outcome.TIMEOUT else Outcome.CANCELLED, Stage.ACK,
            if (foreground) ErrorCode.TIMEOUT else ErrorCode.INCOMPLETE)
        // Missing ACK cannot prove the Agent did not run. Keep its turn open for a later receipt.
    }
    fun sendFailed(frame: SendPrompt) {
        val entry = entries[frame.promptId]?.takeIf { it.convoId == frame.convoId } ?: return
        entry.responseDeadline?.cancel()
        entry.response.finish(ProductResult.FAILURE, ErrorCode.SEND_FAILED, Coverage.PARTIAL)
        entry.trace?.finish(Outcome.FAILURE, Stage.WRITE, ErrorCode.SEND_FAILED)
    }
    fun responseExpired(convoId: String?, promptId: String?, foreground: Boolean, queued: Boolean) {
        val entry = entries[promptId]?.takeIf { it.convoId == convoId && !it.output } ?: return
        if (queued && foreground) { entry.trace?.stage(Stage.QUEUE); return }
        val waiting = !foreground
        val result = if (waiting) ProductResult.WAITING else if (entry.consumed) ProductResult.TIMEOUT else ProductResult.UNKNOWN
        entry.response.finish(result, if (result == ProductResult.TIMEOUT) ErrorCode.TIMEOUT else ErrorCode.INCOMPLETE, Coverage.PARTIAL)
        // End only the observation. An ACK or a silent interval cannot settle the Agent's business turn.
        entry.trace?.finish(if (result == ProductResult.TIMEOUT) Outcome.TIMEOUT else Outcome.CANCELLED,
            if (entry.consumed) Stage.EXECUTE else Stage.QUEUE,
            if (result == ProductResult.TIMEOUT) ErrorCode.TIMEOUT else ErrorCode.INCOMPLETE)
    }
    private fun output(entry: Entry) {
        entry.responseDeadline?.cancel()
        entry.response.finish(ProductResult.SUCCESS)
        entry.response.recover(TelEvent.PromptResponseRecovered)
        entry.trace?.recovered()
        if (!entry.output) { entry.output = true; layoutRevision.value++ }
    }
    private fun abandon(entry: Entry) {
        entry.responseDeadline?.cancel()
        entry.response.finish(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE, Coverage.PARTIAL)
        entry.turn.finish(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE, Coverage.PARTIAL)
        entry.trace?.finish(Outcome.UNKNOWN, Stage.RECONCILE, ErrorCode.INCOMPLETE,
            metrics = SafeMetrics(resultQuality = ResultQuality.UNKNOWN))
    }
    fun background() {
        entries.values.filter { it.consumed && !it.output }.forEach {
            it.responseDeadline?.cancel()
            responseExpired(it.convoId, it.promptId, false, false)
        }
    }
    fun reset() { entries.values.forEach(::abandon); entries.clear() }
}
