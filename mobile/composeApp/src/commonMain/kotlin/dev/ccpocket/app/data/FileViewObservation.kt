package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.*
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*

/** Owned by one viewer attempt. Metadata never controls file acceptance or permissions. */
internal class FileViewObservation(private val dimensions: Map<TelKey, Any>, private val local: Boolean = false) {
    private val id = Diagnostics.newId()
    private val trace = if (dimensions[TelKey.UsageMode] == "demo") null else Diagnostics.begin(ErrorPath.FILE_READ, id)
    val context = DiagnosticContext(id, spanId = trace?.spanId)
    val token: String get() = id
    private val outcome = ProductOutcome(TelEvent.FileViewResult, dimensions)
    private val allowed = TelemetryConsent.capture()
    private var correlated = local
    private var truncated = false
    private var received = false

    init { ProductFeatures.used(ProductFeature.FILE_VIEW, dimensions); trace?.stage(Stage.REQUEST) }

    fun received(content: FileContent) {
        val echoed = content.diagnostic?.validated()
        if (echoed != null && echoed != context) return
        correlated = local || echoed == context
        received = true
        truncated = content.truncated
        trace?.stage(Stage.APPLY, if (content.ok) ErrorCode.OK else ErrorCode.REJECTED)
        // The error card may coexist with a useful diff. Let the rendered surface settle the view.
    }

    fun displayed(result: ProductResult, code: ErrorCode = ErrorCode.OK, partial: Boolean = false) {
        val coverage = if (correlated && received && !truncated && !partial) Coverage.COMPLETE else Coverage.PARTIAL
        if (!outcome.finish(result, code, coverage)) return
        trace?.finish(when (result) {
            ProductResult.SUCCESS -> Outcome.SUCCESS
            ProductResult.FAILURE -> Outcome.FAILURE
            ProductResult.UNKNOWN -> Outcome.UNKNOWN
            else -> Outcome.CANCELLED
        }, Stage.LAYOUT, code, metrics = SafeMetrics(resultQuality = if (coverage == Coverage.COMPLETE) ResultQuality.COMPLETE else ResultQuality.PARTIAL))
        if (result == ProductResult.SUCCESS && allowed()) Telemetry.track(TelEvent.ValueReached,
            dimensions + mapOf(TelKey.Feature to "file_view", TelKey.Coverage to coverage.name.lowercase()))
    }

    fun timeout() {
        if (outcome.finish(ProductResult.TIMEOUT, ErrorCode.TIMEOUT, Coverage.PARTIAL))
            trace?.finish(Outcome.TIMEOUT, Stage.WAIT, ErrorCode.TIMEOUT)
    }
    fun cancel(background: Boolean = false) {
        if (outcome.finish(if (background) ProductResult.WAITING else ProductResult.CANCELLED, ErrorCode.CANCELLED, Coverage.PARTIAL))
            trace?.finish(Outcome.CANCELLED, Stage.WAIT, ErrorCode.CANCELLED)
    }
}
