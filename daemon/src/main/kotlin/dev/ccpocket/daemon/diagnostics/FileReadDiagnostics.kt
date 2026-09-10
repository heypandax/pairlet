package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*

/** Source read completion and transport emission are separate facts. No content is inspected. */
class FileReadDiagnostics(context: DiagnosticContext?, private val emit: suspend (Frame) -> Unit) {
    private val context = context?.validated()
    private val trace = Diagnostics.begin(ErrorPath.FILE_READ, this.context?.traceId, this.context?.spanId)
        ?.also { it.stage(Stage.READ) }
    suspend fun send(frame: Frame) {
        val output = when (frame) {
            is FileContent -> frame.copy(diagnostic = context)
            is FileContentChunk -> frame.copy(diagnostic = context)
            else -> frame
        }
        try { emit(output) }
        catch (error: Exception) {
            trace?.finish(Outcome.FAILURE, Stage.WRITE, ErrorCode.SEND_FAILED, error)
            throw error
        }
        when (frame) {
            is FileContent -> trace?.finish(if (frame.ok) Outcome.SUCCESS else Outcome.FAILURE,
                Stage.WRITE, if (frame.ok) ErrorCode.OK else ErrorCode.REJECTED,
                metrics = SafeMetrics(byteCount = frame.totalBytes,
                    resultQuality = if (frame.truncated) ResultQuality.PARTIAL else if (frame.ok) ResultQuality.COMPLETE else ResultQuality.UNKNOWN))
            is FileContentChunk -> if (frame.last) trace?.finish(Outcome.SUCCESS, Stage.WRITE,
                metrics = SafeMetrics(byteCount = frame.totalBytes, resultQuality = ResultQuality.COMPLETE))
            else -> {}
        }
    }
    fun failed(error: Throwable) { trace?.finish(Outcome.FAILURE, Stage.READ, ErrorCode.READ_FAILED, error) }
}
