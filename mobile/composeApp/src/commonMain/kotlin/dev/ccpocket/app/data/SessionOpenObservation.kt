package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.*
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.DiagnosticContext
import dev.ccpocket.protocol.HistoryComplete

/** Diagnostics never drive opening, retransmission, transcript clearing, or Agent lifetime. */
internal class SessionOpenObservation(private val dimensions: Map<TelKey, Any>) {
    private val consent = TelemetryConsent.capture()
    private val traceId = Diagnostics.newId()
    val trace = (if (dimensions[TelKey.UsageMode] == "demo") null else Diagnostics.begin(ErrorPath.SESSION_OPEN, traceId))?.also { it.stage(Stage.REQUEST) }
    val context = DiagnosticContext(traceId, spanId = trace?.spanId)
    private val product = ProductOutcome(TelEvent.SessionOpenResult, dimensions)
    var requested = false
    var negotiated = false
        private set
    var convoId: String? = null
        private set
    private var merged = false
    private var complete: HistoryComplete? = null
    var awaitingLayout = false
        private set
    private var valueRecorded = false

    fun live(id: String, context: DiagnosticContext?) {
        if (convoId != null && convoId != id) return
        convoId = id
        if (requested && matches(context)) negotiated = true
        trace?.stage(Stage.ATTACH)
        if (!negotiated) {
            product.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
            // A legacy live frame proves attachment only; never a full-history success.
            trace?.finish(Outcome.UNKNOWN, Stage.ATTACH, ErrorCode.UNSUPPORTED,
                metrics = SafeMetrics(resultQuality = ResultQuality.UNKNOWN))
        }
    }

    fun historyApplied(context: DiagnosticContext?) {
        if (!matches(context)) return
        merged = true
        trace?.stage(Stage.APPLY)
    }

    fun matches(other: DiagnosticContext?): Boolean = other?.validated() == context

    fun completed(frame: HistoryComplete): Boolean {
        if (!negotiated || frame.convoId != convoId || !matches(frame.diagnostic) || complete != null) return false
        if (frame.state != "ready" || frame.rows !in 0..1_000_000 || (frame.replaySent && !merged)) {
            fail(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE)
            return false
        }
        complete = frame
        awaitingLayout = true
        trace?.stage(Stage.APPLY)
        return true
    }

    fun laidOut(hasVisibleContent: Boolean) {
        if (!awaitingLayout) return
        awaitingLayout = false
        val quality = when (complete?.quality) {
            "complete", "not_required" -> ResultQuality.COMPLETE
            "partial" -> ResultQuality.PARTIAL
            else -> ResultQuality.UNKNOWN
        }
        val coverage = if (quality == ResultQuality.COMPLETE) Coverage.COMPLETE else Coverage.PARTIAL
        trace?.stage(Stage.LAYOUT)
        trace?.finish(Outcome.SUCCESS, Stage.LAYOUT, metrics = SafeMetrics(resultQuality = quality))
        trace?.recovered()
        product.finish(ProductResult.SUCCESS, coverage = coverage)
        product.recover(TelEvent.SessionOpenRecovered, coverage)
        if (hasVisibleContent && !valueRecorded && consent()) {
            valueRecorded = true
            Telemetry.track(TelEvent.ValueReached, dimensions + mapOf(TelKey.Feature to "session_view", TelKey.Coverage to coverage.name.lowercase()))
        }
    }

    fun background() {
        product.finish(ProductResult.WAITING, coverage = Coverage.PARTIAL)
        trace?.finish(Outcome.CANCELLED)
    }

    fun fail(result: ProductResult, code: ErrorCode, stage: Stage = Stage.WAIT) {
        product.finish(result, code, if (negotiated && result != ProductResult.UNKNOWN) Coverage.COMPLETE else Coverage.PARTIAL)
        trace?.finish(when (result) {
            ProductResult.CANCELLED -> Outcome.CANCELLED
            ProductResult.TIMEOUT -> Outcome.TIMEOUT
            ProductResult.FAILURE -> Outcome.FAILURE
            else -> Outcome.UNKNOWN
        }, stage, code)
    }
}
