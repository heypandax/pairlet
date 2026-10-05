package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.daemon.conversation.*
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*
import java.util.concurrent.atomic.AtomicBoolean

/** One owner request, one sink identity. Never changes who is subscribed or allowed to drive. */
class SessionOpenDiagnostics(
    private val target: OutboundSink,
    private val context: DiagnosticContext,
) : KeyedSink(sinkKey(target), target, target.isWatching()) {
    private val trace = Diagnostics.begin(ErrorPath.SESSION_OPEN, context.traceId, context.spanId)?.also { it.stage(Stage.RECEIVE) }
    private val done = AtomicBoolean(false)
    /** Built by the router as it takes the OpenSession, so this is the open's start for the applied log line. */
    private val openedAt = System.nanoTime()

    override suspend fun emit(frame: Frame) {
        val outgoing = if (done.get()) frame else when (frame) {
            is SessionLive -> frame.copy(diagnostic = context).also { trace?.stage(Stage.ATTACH) }
            is ConvoHistory -> frame.copy(diagnostic = context).also { trace?.stage(Stage.READ) }
            else -> frame
        }
        try { target.emit(outgoing) }
        catch (error: Exception) {
            trace?.finish(Outcome.FAILURE, Stage.WRITE, ErrorCode.SEND_FAILED, error)
            throw error // preserve the existing transport/fan-out failure behavior
        }
    }

    suspend fun complete(convoId: String, rows: Int, replaySent: Boolean, quality: String, sourceRows: Long? = null, failedRows: Long? = null,
        frameBytes: Long? = null) {
        if (!done.compareAndSet(false, true)) return
        trace?.stage(Stage.WRITE)
        val key = ReceiptKey(sinkKey(target), convoId, context)
        expireReceipts()
        val displaced = mutableListOf<Receipt>()
        val receipt = Receipt(trace, System.nanoTime(), SafeMetrics(totalCount = sourceRows,
            failedCount = failedRows, returnedCount = rows.toLong(), byteCount = frameBytes, resultQuality = when (quality) {
                "complete", "not_required" -> ResultQuality.COMPLETE
                "partial" -> ResultQuality.PARTIAL
                else -> ResultQuality.UNKNOWN
            }), openedAt)
        synchronized(receipts) {
            if (!receipts.containsKey(key) && receipts.size >= 256)
                receipts.remove(receipts.keys.first())?.let { displaced += it }
            receipts.put(key, receipt)?.let { displaced += it }
        }
        displaced.forEach { it.unobserved() }
        try { target.emit(HistoryComplete(convoId, context, rows.coerceIn(0, 1_000_000),
            quality = quality, replaySent = replaySent)) }
        catch (error: Exception) {
            synchronized(receipts) { if (receipts[key] === receipt) receipts.remove(key) }
            trace?.finish(Outcome.FAILURE, Stage.WRITE, ErrorCode.SEND_FAILED, error)
            throw error
        }
    }

    companion object {
        private const val RECEIPT_TTL_NS = 120_000_000_000L
        private data class ReceiptKey(val sink: Any, val convoId: String, val context: DiagnosticContext)
        /** [started] = history complete (HistoryComplete sent); [openedAt] = the OpenSession was taken. */
        private data class Receipt(val trace: OperationTrace?, val started: Long, val metrics: SafeMetrics, val openedAt: Long)
        private val receipts = linkedMapOf<ReceiptKey, Receipt>()
        private val log = dev.ccpocket.daemon.util.logger("SessionOpen")

        /** The applied log line: the whole open as the client saw it settle, and how much of it was the daemon's. */
        internal fun appliedLine(convoId: String, openedAt: Long, historySentAt: Long, appliedAt: Long, frameBytes: Long?): String =
            "$convoId HistoryApplied +${(appliedAt - openedAt) / 1_000_000} ms after OpenSession " +
                "(history out at +${(historySentAt - openedAt) / 1_000_000} ms, frame ${frameBytes ?: "?"} B)"

        private fun Receipt.unobserved() = trace?.finish(Outcome.UNKNOWN, Stage.APPLY,
            ErrorCode.INCOMPLETE, metrics = metrics.copy(resultQuality = ResultQuality.UNKNOWN))

        /** Swept by the existing diagnostics preference worker; never cancels session work. */
        internal fun expireReceipts(now: Long = System.nanoTime()) {
            val expired = synchronized(receipts) {
                val keys = receipts.filterValues { now - it.started > RECEIPT_TTL_NS }.keys
                keys.mapNotNull { receipts.remove(it) }
            }
            expired.forEach { it.unobserved() }
        }

        /** Called only after existing ingress access checks and owner/capability checks. Returns the logged
         *  line when this acknowledged a receipt in time (same connection, conversation and attempt), else null. */
        fun applied(frame: HistoryApplied, sink: OutboundSink): String? {
            val context = frame.diagnostic.validated() ?: return null
            val receipt = synchronized(receipts) { receipts.remove(ReceiptKey(sinkKey(sink), frame.convoId, context)) } ?: return null
            val now = System.nanoTime()
            if (now - receipt.started > RECEIPT_TTL_NS) { receipt.unobserved(); return null }
            receipt.trace?.finish(Outcome.SUCCESS, Stage.APPLY, metrics = receipt.metrics)
            return appliedLine(frame.convoId, receipt.openedAt, receipt.started, now, receipt.metrics.byteCount).also { log.info(it) }
        }
    }
}

/** No-op for legacy clients and non-owner ingress. No extra wire messages for those peers. */
suspend fun OutboundSink.completeInitialHistory(convoId: String, rows: Int = 0,
    replaySent: Boolean = false, quality: String = "unknown", sourceRows: Long? = null, failedRows: Long? = null,
    frameBytes: Long? = null) {
    (this as? SessionOpenDiagnostics)?.complete(convoId, rows, replaySent, quality, sourceRows, failedRows, frameBytes)
}
