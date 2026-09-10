package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*

/** A scheduled dispatch can finish before its Agent turn. Follow only the matching consumption
 * receipt; unrelated turns on the scheduler's shared sink cannot settle this operation. */
class BackgroundExecutionDiagnostics {
    private val id = Diagnostics.newId()
    private val trace = Diagnostics.begin(ErrorPath.BACKGROUND, id)?.also { it.stage(Stage.REQUEST) }
    val context = DiagnosticContext(id, spanId = trace?.spanId)
    fun frame(frame: Frame) {
        if (frame !is PromptProgress || frame.diagnostic.validated() != context) return
        when (frame.stage) {
            "ack" -> trace?.stage(Stage.ACK)
            "queued" -> trace?.stage(Stage.QUEUE)
            "consumed" -> trace?.stage(Stage.EXECUTE)
            "first_output" -> trace?.stage(Stage.RECEIVE)
            "complete" -> trace?.finish(when (frame.result) {
                "success" -> Outcome.SUCCESS
                "cancelled" -> Outcome.CANCELLED
                "failure" -> Outcome.FAILURE
                else -> Outcome.UNKNOWN
            }, Stage.COMPLETE, when (frame.result) {
                "failure" -> ErrorCode.PROCESS_EXITED
                "success", "cancelled" -> ErrorCode.OK
                else -> ErrorCode.INCOMPLETE
            }, metrics = SafeMetrics(resultQuality = if (frame.result in setOf("success", "failure", "cancelled"))
                ResultQuality.COMPLETE else ResultQuality.UNKNOWN))
            "handled", "unobserved" -> trace?.finish(Outcome.UNKNOWN, Stage.RECONCILE, ErrorCode.INCOMPLETE,
                metrics = SafeMetrics(resultQuality = ResultQuality.UNKNOWN))
        }
    }
    fun dispatchFailed() { trace?.finish(Outcome.FAILURE, Stage.DISPATCH, ErrorCode.UNAVAILABLE) }
}
