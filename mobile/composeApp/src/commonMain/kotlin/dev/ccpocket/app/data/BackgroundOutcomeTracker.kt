package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.*
import dev.ccpocket.observability.ErrorCode

/** Reports only transitions actually observed in this connection. A terminal replay is not a new
 * completed task. Duration starts at first observation, so coverage remains partial. IDs stay local. */
internal class BackgroundOutcomeTracker {
    private val entries = linkedMapOf<Triple<String, String, String>, ProductOutcome>()
    fun observe(convoId: String, source: String, id: String, status: String, dimensions: Map<TelKey, Any>) {
        val key = Triple(convoId, source, id)
        if (status == "RUNNING" && key !in entries) {
            if (entries.size >= 256) entries.remove(entries.keys.first())?.finish(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE, Coverage.PARTIAL)
            entries[key] = ProductOutcome(TelEvent.BackgroundTaskResult, dimensions + mapOf(TelKey.Source to source))
        }
        val entry = entries[key] ?: return
        when (status) {
            "DONE", "COMPLETED" -> entry.finish(ProductResult.SUCCESS, coverage = Coverage.PARTIAL)
            "FAILED" -> entry.finish(ProductResult.FAILURE, ErrorCode.PROCESS_EXITED, Coverage.PARTIAL)
            "KILLED" -> entry.finish(ProductResult.CANCELLED, ErrorCode.CANCELLED, Coverage.PARTIAL)
        }
    }
    fun reset() {
        entries.values.forEach { it.finish(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE, Coverage.PARTIAL) }
        entries.clear()
    }
}
