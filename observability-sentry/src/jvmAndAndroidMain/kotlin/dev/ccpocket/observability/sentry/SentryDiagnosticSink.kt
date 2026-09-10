package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*
import io.sentry.*
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryId
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.transport.ITransportGate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One isolated SDK client for explicitly constructed diagnostics. No global scope, crash handler,
 * network instrumentation or raw logger interception. Uses the SDK's HTTP transport and batching.
 */
class SentryDiagnosticSink private constructor(
    private val options: SentryOptions,
    private val accepting: AtomicBoolean,
    private val component: Component,
    private val budget: DiagnosticBudget?,
    private val counters: DiagnosticCounters?,
) : DiagnosticSink, AutoCloseable {
    private val client = SentryClient(options)
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "cc-pocket-diagnostics").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val dropped = AtomicLong()
    private var day = Long.MIN_VALUE
    private var bytesToday = 0L

    override fun tryEmit(record: DiagnosticRecord): Boolean {
        if (!accepting.get()) return false
        return try {
            executor.execute {
                if (!accepting.get()) return@execute
                try {
                    val size = Json.encodeToString(record).toByteArray(Charsets.UTF_8).size
                    val today = System.currentTimeMillis() / 86_400_000L
                    if (today > day) { day = today; bytesToday = 0 }
                    val byteLimit = if (component == Component.RELAY) 20 * 1024 * 1024L else 1024 * 1024L
                    if (size > 16 * 1024 || bytesToday + size > byteLimit) { dropped.incrementAndGet(); counters?.increment(DiagnosticCounter.BUDGET_REJECTED); return@execute }
                    if (budget?.admit(record.kind, size) == false) { dropped.incrementAndGet(); counters?.increment(DiagnosticCounter.BUDGET_REJECTED); return@execute }
                    bytesToday += size
                    // Opt-out may have happened during storage I/O; the reservation is not refunded.
                    if (!accepting.get()) { counters?.increment(DiagnosticCounter.CLOSED_DROPPED); return@execute }
                    if (record.kind == DiagnosticKind.ERROR) client.captureEvent(toEvent(record), null, null)
                    else client.captureLog(toLog(record), null)
                    manualTransaction(record)?.let { transaction ->
                        // Conservatively reserve the maximum allowed envelope, separately from its log/error.
                        if (bytesToday + 16_384 <= byteLimit && budget?.admit(DiagnosticKind.RESULT, 16_384) != false && accepting.get()) {
                            bytesToday += 16_384
                            client.captureTransaction(transaction, null, null, null, null)
                        } else counters?.increment(DiagnosticCounter.BUDGET_REJECTED)
                    }
                } catch (_: Exception) {
                    dropped.incrementAndGet() // Never report failures through this same sink.
                    counters?.increment(DiagnosticCounter.SOURCE_DROPPED)
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet(); counters?.increment(DiagnosticCounter.QUEUE_DROPPED); false }
    }

    fun droppedCount(): Long = dropped.get()
    fun isRateLimited(): Boolean = client.rateLimiter?.isAnyRateLimitActive == true

    /** Only call at a lifecycle/test boundary, never in a business coroutine. */
    fun flush(timeoutMs: Long = 2000) {
        if (!accepting.get()) return
        val deadline = System.nanoTime() + timeoutMs.coerceIn(0, 2000) * 1_000_000
        runCatching { executor.submit {}.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
        client.flush(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
    }

    /** Privacy stop: transport gate shuts before queue disposal. No persisted envelopes to replay. */
    override fun close() {
        if (!accepting.getAndSet(false)) return
        counters?.increment(DiagnosticCounter.CLOSED_DROPPED, executor.shutdownNow().size.toLong())
        client.close(true)
    }

    companion object {
        fun create(dsn: String?, component: Component, budget: DiagnosticBudget? = null,
                   counters: DiagnosticCounters? = null): SentryDiagnosticSink? {
            if (!validDsn(dsn)) return null
            return runCatching { createConfigured(dsn!!, component, budget, counters) }.getOrNull()
        }

        internal fun createConfigured(dsn: String, component: Component,
                                      budget: DiagnosticBudget? = null,
                                      counters: DiagnosticCounters? = null,
                                      customize: (SentryOptions) -> Unit = {}): SentryDiagnosticSink {
            val accepting = AtomicBoolean(true)
            val localReports = LocalClientReports(counters)
            val options = object : SentryOptions() {
                override fun getClientReportRecorder(): io.sentry.clientreport.IClientReportRecorder = localReports
            }.apply {
                this.dsn = dsn
                isEnableExternalConfiguration = false
                isSendDefaultPii = false
                isAttachServerName = false
                isAttachStacktrace = false
                isAttachThreads = false
                isEnableUncaughtExceptionHandler = false
                isEnableAutoSessionTracking = false
                isEnableShutdownHook = false
                isEnableScopePersistence = false
                isSendModules = false
                isSendClientReports = false
                tracesSampleRate = 0.0
                logs.isEnabled = true
                metrics.isEnabled = false
                eventProcessors.clear()
                integrations.clear()
                maxQueueSize = 16
                maxCacheItems = 16
                shutdownTimeoutMillis = 0
                connectionTimeoutMillis = 3000
                readTimeoutMillis = 3000
                setTransportGate(ITransportGate { accepting.get() })
                // Scope is always null, processors are empty, all event construction is below.
                setBeforeSend { event, _ -> if (accepting.get()) event else null }
                setBeforeSendTransaction { event, _ -> if (accepting.get()) event else null }
                logs.setBeforeSend { event -> if (accepting.get()) event else null }
                setTransportFactory { opts, details ->
                    CountedSentryTransport(AsyncHttpTransportFactory().create(opts, details), counters)
                }
            }
            customize(options)
            return SentryDiagnosticSink(options, accepting, component, budget, counters)
        }

        internal fun validDsn(dsn: String?): Boolean = runCatching {
            val uri = URI(dsn ?: return false)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo?.matches(Regex("[a-fA-F0-9]{16,64}")) == true &&
                uri.path.matches(Regex("/[0-9]+")) && uri.query == null && uri.fragment == null && dsn.length <= 512
        }.getOrDefault(false)

        internal fun tags(record: DiagnosticRecord): Map<String, String> = buildMap {
            put("diag_schema", record.schemaVersion.toString())
            put("diag_event_id", record.eventId)
            record.traceId?.let { put("diag_trace_id", it) }
            record.spanId?.let { put("diag_span_id", it) }
            record.connectionId?.let { put("connection_id", it) }
            record.peerConnectionId?.let { put("peer_connection_id", it) }
            put("error_path", record.path.id)
            put("operation", record.path.name.lowercase())
            put("stage", record.stage.name.lowercase())
            put("code", record.code.name.lowercase())
            put("component", record.component.name.lowercase())
            put("coverage", "client_only")
            record.exception?.type?.let { put("exception_type", it) }
            record.metrics.backend?.let { put("agent", it.name.lowercase()) }
            record.outcome?.let { put("outcome", it.name.lowercase()) }
        }

        internal fun toEvent(record: DiagnosticRecord): SentryEvent = SentryEvent().apply {
            eventId = SentryId(record.eventId)
            timestamp = Date(record.occurredAtMs)
            release = record.release
            environment = record.environment.name.lowercase()
            platform = "java"
            val traceId = record.traceId
            val spanId = record.spanId
            if (traceId != null && spanId != null) contexts.setTrace(SpanContext(
                SentryId(traceId), SpanId(spanId), record.path.name.lowercase(),
                record.parentSpanId?.let(::SpanId), null))
            logger = "cc-pocket.diagnostics"
            level = SentryLevel.ERROR
            tags(record).forEach { (key, value) -> setTag(key, value) }
            message = Message().apply { message = "${record.path.id}:${record.code.name.lowercase()}" }
            val error = record.exception
            if (error != null) {
                exceptions = listOf(SentryException().apply {
                    type = error.type
                    value = record.code.name.lowercase()
                    stacktrace = SentryStackTrace(error.frames.reversed().map { safe ->
                        SentryStackFrame().apply { function = safe.symbol; filename = safe.file; lineno = safe.line; isInApp = true }
                    })
                })
            }
            fingerprints = listOf(record.component.name, record.path.id, record.stage.name, record.code.name,
                error?.frames?.firstOrNull()?.symbol ?: error?.type ?: "no_stack")
            contexts["diagnostic"] = diagnosticContext(record)
        }

        private fun diagnosticContext(record: DiagnosticRecord): Map<String, Any> = buildMap {
            put("attempt", record.attempt)
            put("suppressed_count", record.suppressedCount)
            record.elapsedMs?.let { put("elapsed_ms", it) }
            val m = record.metrics
            m.totalCount?.let { put("total_count", it) }; m.failedCount?.let { put("failed_count", it) }
            m.returnedCount?.let { put("returned_count", it) }; m.byteCount?.let { put("byte_count", it) }
            m.queueSize?.let { put("queue_size", it) }; m.exitCode?.let { put("exit_code", it) }
            m.transport?.let { put("transport", it.name.lowercase()) }
            m.resultQuality?.let { put("result_quality", it.name.lowercase()) }
            put("steps", record.steps.map { mapOf("stage" to it.stage.name.lowercase(), "code" to it.code.name.lowercase(), "elapsed_ms" to it.elapsedMs) })
        }

        internal fun toLog(record: DiagnosticRecord): SentryLogEvent = SentryLogEvent(
            SentryId(record.traceId ?: record.eventId), record.occurredAtMs / 1000.0,
            "${record.path.id}:${record.code.name.lowercase()}", SentryLogLevel.INFO,
        ).apply {
            tags(record).forEach { (key, value) -> setAttribute(key, SentryLogEventAttributeValue("string", value)) }
            setAttribute("release", SentryLogEventAttributeValue("string", record.release))
            setAttribute("environment", SentryLogEventAttributeValue("string", record.environment.name.lowercase()))
            diagnosticContext(record).filterKeys { it != "steps" }.forEach { (key, value) ->
                setAttribute(key, SentryLogEventAttributeValue(if (value is Number) "integer" else "string", value))
            }
        }
    }
}
