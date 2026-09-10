package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*
import io.sentry.*
import io.sentry.protocol.*

/** Real SDK transaction/span objects from our closed recipe; no global scope or automatic spans. */
internal fun manualTransaction(record: DiagnosticRecord): SentryTransaction? {
    val recipe = NativeTraceRecipe.from(record) ?: return null
    val traceId = SentryId(recipe.traceId)
    val root = SpanId(recipe.spanId)
    val status = if (recipe.failed) SpanStatus.INTERNAL_ERROR else SpanStatus.OK
    val spans = recipe.children.map { step ->
        SentrySpan(step.startMs / 1000.0, step.endMs / 1000.0, traceId, SpanId(), root,
            step.operation, null, SpanStatus.OK, "manual", emptyMap(), emptyMap(), emptyMap())
    }
    return SentryTransaction(recipe.operation, recipe.startMs / 1000.0, recipe.endMs / 1000.0,
        spans, emptyMap(), TransactionInfo("custom")).apply {
        release = record.release
        environment = record.environment.name.lowercase()
        platform = "java"
        contexts.setTrace(SpanContext(traceId, root, recipe.operation, recipe.parentSpanId?.let(::SpanId), TracesSamplingDecision(true)).apply {
            setStatus(status)
        })
        SentryDiagnosticSink.tags(record).forEach { (key, value) -> setTag(key, value) }
    }
}
