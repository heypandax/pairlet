package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*

/** Counts only sinks already selected by the business authorization checks. */
class PeerDeliveryDiagnostics {
    private var attempted = 0L
    private var failed = 0L
    fun sent(result: Result<Unit>) {
        if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return
        attempted++; if (result.isFailure) failed++
    }
    fun finish() {
        if (failed == 0L) return
        Diagnostics.report(ErrorPath.PEER_DELIVERY, Stage.WRITE, ErrorCode.PARTIAL_RESULT,
            metrics = SafeMetrics(totalCount = attempted, failedCount = failed,
                returnedCount = attempted - failed, resultQuality = ResultQuality.PARTIAL))
    }
}

fun peerReconcileFailed(error: Throwable) {
    Diagnostics.report(ErrorPath.PEER_DELIVERY, Stage.RECONCILE, ErrorCode.APPLY_FAILED, error)
}
