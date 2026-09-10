package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.*
import kotlin.time.TimeSource

enum class ProductResult { SUCCESS, FAILURE, TIMEOUT, CANCELLED, UNKNOWN, WAITING }
enum class Coverage { COMPLETE, PARTIAL, UNKNOWN }
enum class ProductFeature { SESSION_VIEW, PROMPT_TASK, APPROVAL, FILE_VIEW, BACKGROUND_TASK }

/** Business denominators are independent of Sentry sampling/admission. One instance per user operation.
 * Main-thread confined, like the repository that owns it. Never carries business identifiers/text. */
class ProductOutcome(
    private val event: TelEvent,
    private val dimensions: Map<TelKey, Any> = emptyMap(),
    private val emit: (TelEvent, Map<TelKey, Any>) -> Unit = Telemetry::track,
    private val allowed: () -> Boolean = TelemetryConsent.capture(),
) {
    private val started = TimeSource.Monotonic.markNow()
    var result: ProductResult? = null
        private set
    private var recovered = false

    fun finish(value: ProductResult, reason: ErrorCode = ErrorCode.OK, coverage: Coverage = Coverage.COMPLETE): Boolean {
        if (result != null) return false
        result = value
        if (allowed()) emit(event, params(value, reason, coverage))
        return true
    }

    fun recover(event: TelEvent, coverage: Coverage = Coverage.COMPLETE): Boolean {
        if (recovered || result !in listOf(ProductResult.FAILURE, ProductResult.TIMEOUT)) return false
        recovered = true
        if (allowed()) emit(event, params(ProductResult.SUCCESS, ErrorCode.RECOVERED, coverage))
        return true
    }

    private fun params(result: ProductResult, reason: ErrorCode, coverage: Coverage) = dimensions + mapOf(
        TelKey.Result to result.name.lowercase(), TelKey.Reason to reason.name.lowercase(),
        TelKey.Coverage to coverage.name.lowercase(),
        TelKey.DurationMs to started.elapsedNow().inWholeMilliseconds.coerceIn(0, 86_400_000),
    )
}
