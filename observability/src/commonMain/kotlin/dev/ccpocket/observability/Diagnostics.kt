package dev.ccpocket.observability

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/** Implementations must enqueue without blocking; false means the bounded delivery queue is full. */
fun interface DiagnosticSink { fun tryEmit(record: DiagnosticRecord): Boolean }

data class DiagnosticHealth(val submitted: Long, val suppressed: Long, val dropped: Long)

class DiagnosticReporter(
    private val component: Component,
    private val environment: Environment,
    release: String,
    private val sink: DiagnosticSink,
    private val dailyErrorLimit: Int = if (component == Component.RELAY) 20 else 10,
    private val epochMs: () -> Long = ::diagnosticEpochMs,
    private val newId: () -> String = ::diagnosticId,
    private val successSamplePercent: Int = 1,
    private val counters: DiagnosticCounters? = null,
) {
    private val lock = DiagnosticLock()
    internal val traceSteps = DiagnosticSteps()
    private val release = SafeSymbols.release(release)
    private var enabled = true
    private var generation = 0L
    private var day = Long.MIN_VALUE
    private var errorsToday = 0
    private var logsToday = 0
    private var bytesToday = 0L
    private var latestId: String? = null
    fun latestId(): String? = lock.withLock { latestId }
    private var submitted = 0L
    private var suppressed = 0L
    private var dropped = 0L
    private val limits = linkedMapOf<String, Pair<Long, Int>>()
    private val logLimits = linkedMapOf<String, Pair<Long, Int>>()

    fun setEnabled(value: Boolean) = lock.withLock {
        enabled = value
        generation++
        traceSteps.clear()
        // Keep rate budgets across toggles; reopening collection must not reset the daily quota.
    }

    fun health(): DiagnosticHealth = lock.withLock { DiagnosticHealth(submitted, suppressed, dropped) }
    fun begin(path: ErrorPath, traceId: String? = null, parentSpanId: String? = null): OperationTrace = lock.withLock {
        OperationTrace(this, path, traceId?.takeIf { it.length == 32 && it.any { c -> c != '0' } && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: newId(), generation, parentSpanId?.takeIf { it.length == 16 && it.any { c -> c != '0' } && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })
    }

    fun report(
        path: ErrorPath, stage: Stage, code: ErrorCode, error: Throwable? = null,
        metrics: SafeMetrics = SafeMetrics(), isError: Boolean = error != null,
    ) = emit(path, stage, code, error, metrics, if (isError) DiagnosticKind.ERROR else DiagnosticKind.LOG)

    internal fun emit(
        path: ErrorPath, stage: Stage, code: ErrorCode, error: Throwable?, metrics: SafeMetrics,
        kind: DiagnosticKind, traceId: String? = null, outcome: Outcome? = null, elapsedMs: Long? = null,
        attempt: Int = 0, steps: List<DiagnosticStep> = emptyList(), expectedGeneration: Long? = null,
        spanId: String? = null, parentSpanId: String? = null,
        connectionId: String? = null, peerConnectionId: String? = null,
    ): String? {
        if (error is CancellationException || outcome == Outcome.CANCELLED) return null
        val record = lock.withLock {
            if (!enabled || (expectedGeneration != null && generation != expectedGeneration)) return@withLock null
            if (outcome in setOf(Outcome.SUCCESS, Outcome.UNKNOWN) && traceId != null &&
                (traceId.take(8).toLongOrNull(16) ?: 99L) % 100 >= successSamplePercent.coerceIn(0, 100)) {
                suppressed++; counters?.increment(DiagnosticCounter.SOURCE_SAMPLED); return@withLock null
            }
            val now = epochMs()
            val today = now / 86_400_000L
            if (today > day) { day = today; errorsToday = 0; logsToday = 0; bytesToday = 0 }
            val isError = kind == DiagnosticKind.ERROR
            val captured = error?.let(::safeException)
            // Fingerprints use bounded categories and safe code symbols, never exception prose or IDs.
            val baseKey = "${path.name}:${stage.name}:${code.name}"
            val key = "$baseKey:${captured?.type}:${captured?.frames?.firstOrNull()?.symbol}"
            if (kind == DiagnosticKind.LOG) {
                val previous = logLimits[baseKey]
                val count = if (previous != null && now - previous.first in 0 until 60_000L) previous.second else 0
                if (count >= 3) { suppressed++; counters?.increment(DiagnosticCounter.SOURCE_SUPPRESSED); return@withLock null }
                if (count == 0 && logLimits.size >= 256) logLimits.remove(logLimits.keys.first())
                logLimits[baseKey] = (if (count == 0) now else previous!!.first) to count + 1
            }
            val last = limits[key]
            val repeat = if (last != null && now - last.first in 0 until 21_600_000L) last.second else 0
            if ((isError && (errorsToday >= dailyErrorLimit || repeat >= 2)) || (!isError && logsToday >= 500)) {
                suppressed++; counters?.increment(DiagnosticCounter.SOURCE_SUPPRESSED); return@withLock null
            }
            if (isError) {
                errorsToday++
                if (repeat == 0 && limits.size >= 256) limits.remove(limits.keys.first())
                limits[key] = (if (repeat == 0) now else last!!.first) to repeat + 1
            } else logsToday++
            val record = DiagnosticRecord(
                eventId = newId(), traceId = traceId, path = path, component = component,
                environment = environment, release = release, kind = kind, stage = stage, code = code,
                outcome = outcome, occurredAtMs = now, elapsedMs = elapsedMs?.coerceAtLeast(0), attempt = attempt.coerceIn(0, 1000),
                metrics = metrics.bounded(), exception = captured, steps = steps.takeLast(32),
                suppressedCount = suppressed, spanId = spanId, parentSpanId = parentSpanId,
                connectionId = connectionId, peerConnectionId = peerConnectionId,
            )
            val bytes = Json.encodeToString(record).encodeToByteArray().size
            val byteBudget = if (component == Component.RELAY) 20 * 1024 * 1024L else 1024 * 1024L
            val reservedErrors = if (isError) 0L else (dailyErrorLimit - errorsToday).coerceAtLeast(0) * 16_384L
            if (bytes > 16 * 1024 || bytesToday + bytes > byteBudget - reservedErrors) {
                dropped++; counters?.increment(DiagnosticCounter.SOURCE_DROPPED); return@withLock null
            }
            bytesToday += bytes
            // tryEmit only enqueues. Keeping this inside the gate makes opt-out and sink replacement
            // atomic with admission; an old operation cannot slip into a newly enabled SDK instance.
            val accepted = try { sink.tryEmit(record) } catch (_: Exception) { false }
            if (accepted) { submitted++; latestId = record.eventId } else dropped++
            counters?.increment(if (accepted) DiagnosticCounter.SOURCE_SUBMITTED else DiagnosticCounter.SOURCE_DROPPED)
            record.eventId.takeIf { accepted }
        }
        return record
    }
}

/** Per-operation state is never put on a global SDK scope, including parallel panes and retries. */
class OperationTrace internal constructor(
    private val reporter: DiagnosticReporter, private val path: ErrorPath, val id: String,
    private val generation: Long, private val parentSpanId: String? = null,
) {
    val spanId: String = diagnosticId().take(16)
    private val lock = DiagnosticLock()
    private val start = TimeSource.Monotonic.markNow()
    private val stepKey = Any().also { reporter.traceSteps.register(it) }
    private var terminal: Outcome? = null
    private var recovered = false
    private var attempt = 0

    fun stage(stage: Stage, code: ErrorCode = ErrorCode.OK) = lock.withLock {
        if (terminal != null) return@withLock
        reporter.traceSteps.append(stepKey, DiagnosticStep(stage, start.elapsedNow().inWholeMilliseconds, code))
    }
    fun retry() = lock.withLock { if (terminal == null) attempt = (attempt + 1).coerceAtMost(1000) }

    fun finish(outcome: Outcome, stage: Stage = Stage.COMPLETE, code: ErrorCode = ErrorCode.OK,
               error: Throwable? = null, metrics: SafeMetrics = SafeMetrics()): String? {
        val snapshot = lock.withLock {
            if (terminal != null) return@withLock null
            terminal = outcome
            reporter.traceSteps.snapshot(stepKey) to attempt
        } ?: return null
        return reporter.emit(path, stage, code, error, metrics,
            if ((outcome == Outcome.FAILURE || outcome == Outcome.TIMEOUT) && code !in setOf(ErrorCode.REJECTED, ErrorCode.EXPIRED, ErrorCode.PERMISSION_DENIED, ErrorCode.CANCELLED, ErrorCode.SUPERSEDED, ErrorCode.NOT_FOUND, ErrorCode.SIZE_LIMIT)) DiagnosticKind.ERROR else DiagnosticKind.RESULT,
            id, outcome, start.elapsedNow().inWholeMilliseconds, snapshot.second, snapshot.first, generation, spanId, parentSpanId)
    }

    fun recovered(): String? {
        val snapshot = lock.withLock {
            if (terminal !in listOf(Outcome.FAILURE, Outcome.TIMEOUT) || recovered) return@withLock null
            recovered = true
            reporter.traceSteps.snapshot(stepKey) to attempt
        } ?: return null
        return reporter.emit(path, Stage.COMPLETE, ErrorCode.RECOVERED, null, SafeMetrics(), DiagnosticKind.RECOVERY,
            id, Outcome.RECOVERED, start.elapsedNow().inWholeMilliseconds, snapshot.second, snapshot.first, generation, spanId, parentSpanId)
    }
}

/** Process-local facade. A checkout without explicit platform configuration remains a no-op. */
object Diagnostics {
    private val lock = DiagnosticLock()
    private var reporter: DiagnosticReporter? = null
    fun install(value: DiagnosticReporter?) = lock.withLock {
        if (reporter !== value) { reporter?.setEnabled(false); reporter = value }
    }
    /** New peer message variants are expected with independent release schedules. Text is inspected
     * locally only to recognize the serializer's discriminator diagnostic, and never leaves this API. */
    private val unknownFrameError = Regex("""^(?:Unexpected JSON token at offset -?[0-9]+: )?Serializer for subclass '[^'\r\n]{1,160}' is not found in the polymorphic scope of 'Frame'(?: at path: \$\.body)?$""")
    fun protocolDecodeFailed(error: Throwable, bytes: Long): String? {
        val unsupported = error is kotlinx.serialization.SerializationException &&
            error.message?.substringBefore('\n')?.take(512)?.let(unknownFrameError::matches) == true
        return report(ErrorPath.PROTOCOL, Stage.DECODE,
            if (unsupported) ErrorCode.UNSUPPORTED else ErrorCode.DECODE_FAILED,
            if (unsupported) null else error, SafeMetrics(byteCount = bytes), isError = !unsupported)
    }
    /** Only already-authenticated peers supply connection labels; malformed metadata is discarded. */
    fun connection(id: String?, peerId: String? = null, code: ErrorCode = ErrorCode.OK): String? {
        fun valid(value: String?) = value?.takeIf { it.length == 32 && it.any { c -> c != '0' } && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        val own = valid(id) ?: return null
        return lock.withLock { reporter }?.emit(ErrorPath.RELAY, Stage.CONNECT, code, null, SafeMetrics(transport = Transport.RELAY),
            DiagnosticKind.LOG, connectionId = own, peerConnectionId = valid(peerId))
    }
    fun latestId(): String? = lock.withLock { reporter }?.latestId()?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    fun newId(): String = diagnosticId()
    fun begin(path: ErrorPath, traceId: String? = null, parentSpanId: String? = null): OperationTrace? = lock.withLock { reporter }?.begin(path, traceId, parentSpanId)
    fun report(path: ErrorPath, stage: Stage, code: ErrorCode, error: Throwable? = null,
               metrics: SafeMetrics = SafeMetrics(), isError: Boolean = error != null): String? =
        lock.withLock { reporter }?.report(path, stage, code, error, metrics, isError)
}
