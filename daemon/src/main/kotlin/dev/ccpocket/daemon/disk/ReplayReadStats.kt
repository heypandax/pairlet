package dev.ccpocket.daemon.disk

import dev.ccpocket.observability.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** SQLite history accounting counts JSON records, never executes a second query or retains data. */
internal class ReplayReadStats {
    var total = 0L
        private set
    var failed = 0L
        private set
    fun parse(json: Json, raw: String?): JsonObject? {
        total++
        val result = runCatching { raw?.let { json.parseToJsonElement(it) as? JsonObject } }.getOrNull()
        if (result == null) failed++
        return result
    }
    fun slice(messages: List<dev.ccpocket.protocol.HistoryMessage>): ReplaySlice {
        if (failed > 0) Diagnostics.report(ErrorPath.HISTORY_READ, Stage.PARSE, ErrorCode.PARTIAL_RESULT,
            metrics = SafeMetrics(totalCount = total, failedCount = failed,
                returnedCount = messages.size.toLong(), resultQuality = ResultQuality.PARTIAL))
        return ReplaySlice(messages, quality = if (failed == 0L) "complete" else "partial", sourceRows = total, failedRows = failed)
    }
}
