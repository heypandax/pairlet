package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*

/** Explicit business-store boundaries only; never used by diagnostic preferences/budgets/counters. */
fun storageReadFailed(error: Throwable) {
    Diagnostics.report(ErrorPath.STORAGE, Stage.READ, ErrorCode.READ_FAILED, error,
        SafeMetrics(resultQuality = ResultQuality.FALLBACK))
}
fun storageWriteFailed(error: Throwable) {
    Diagnostics.report(ErrorPath.STORAGE, Stage.COMMIT, ErrorCode.WRITE_FAILED, error)
}
