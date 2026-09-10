package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.DiagnosticCounter
import dev.ccpocket.observability.DiagnosticCounters
import io.sentry.*
import io.sentry.clientreport.DiscardReason
import io.sentry.clientreport.IClientReportRecorder

/** SDK loss notifications go to local aggregate counters, never to a new upload or the global scope. */
internal class LocalClientReports(private val counters: DiagnosticCounters?) : IClientReportRecorder {
    override fun recordLostEvent(reason: DiscardReason, category: DataCategory) = recordLostEvent(reason, category, 1)
    override fun recordLostEvent(reason: DiscardReason, category: DataCategory, quantity: Long) {
        val counter = when (category) {
            DataCategory.Error -> DiagnosticCounter.SDK_ERROR_LOST
            DataCategory.LogItem -> DiagnosticCounter.SDK_LOG_LOST
            DataCategory.LogByte -> DiagnosticCounter.SDK_LOG_BYTES_LOST
            DataCategory.Span -> DiagnosticCounter.SDK_SPAN_LOST
            DataCategory.Transaction -> DiagnosticCounter.SDK_TRANSACTION_LOST
            else -> return
        }
        counters?.increment(counter, quantity)
        // Reason counts use items, not LogByte (which would mix units).
        if (category == DataCategory.LogByte) return
        counters?.increment(when (reason) {
            DiscardReason.RATELIMIT_BACKOFF -> DiagnosticCounter.SDK_RATE_LIMITED
            DiscardReason.NETWORK_ERROR, DiscardReason.SEND_ERROR -> DiagnosticCounter.SDK_NETWORK_LOST
            DiscardReason.QUEUE_OVERFLOW, DiscardReason.CACHE_OVERFLOW, DiscardReason.BACKPRESSURE -> DiagnosticCounter.SDK_QUEUE_LOST
            else -> DiagnosticCounter.SDK_FILTERED
        }, quantity)
    }
    override fun recordLostEnvelope(reason: DiscardReason, envelope: SentryEnvelope?) {
        envelope?.items?.forEach { recordLostEnvelopeItem(reason, it) }
    }
    override fun recordLostEnvelopeItem(reason: DiscardReason, item: SentryEnvelopeItem?) {
        // The HTTP transport reports a failed log batch here, without exposing its item count.
        // Keep the batch unit separate instead of silently losing it or guessing one log item.
        if (item?.header?.type == SentryItemType.Event) recordLostEvent(reason, DataCategory.Error)
        if (item?.header?.type == SentryItemType.Transaction) recordLostEvent(reason, DataCategory.Transaction)
        if (item?.header?.type == SentryItemType.Log) counters?.increment(DiagnosticCounter.SDK_LOG_BATCH_LOST)
    }
    override fun attachReportToEnvelope(envelope: SentryEnvelope): SentryEnvelope = envelope
}
