package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.*
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*

internal class ApprovalOutcomeTracker {
    private class Entry(val convoId: String, dimensions: Map<TelKey, Any>) {
        val context = DiagnosticContext(Diagnostics.newId())
        val outcome = ProductOutcome(TelEvent.ApprovalApplyResult, dimensions)
    }
    private val entries = linkedMapOf<Pair<String, String>, Entry>()
    fun request(verdict: PermissionVerdict, supported: Boolean, dimensions: Map<TelKey, Any>): PermissionVerdict {
        val key = verdict.convoId to verdict.askId
        val entry = entries[key] ?: Entry(verdict.convoId, dimensions).also {
            if (entries.size >= 128) entries.remove(entries.keys.first())?.outcome?.finish(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE, Coverage.PARTIAL)
            entries[key] = it
            Telemetry.track(TelEvent.FeatureUsed, dimensions + mapOf(TelKey.Feature to "approval"))
            if (!supported) it.outcome.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
        }
        return verdict.copy(diagnostic = entry.context.takeIf { supported })
    }
    fun progress(frame: ApprovalProgress) {
        val context = frame.diagnostic.validated() ?: return
        val entry = entries.values.firstOrNull { it.context == context && it.convoId == frame.convoId } ?: return
        when (frame.stage) {
            "adapter_failed" -> entry.outcome.finish(ProductResult.FAILURE, ErrorCode.APPLY_FAILED)
            // All current respondPermission adapters return Unit, not an Agent application receipt.
            "adapter_returned", "gate_resolved" -> entry.outcome.finish(ProductResult.UNKNOWN, ErrorCode.UNSUPPORTED, Coverage.PARTIAL)
        }
    }
    fun reset() {
        entries.values.forEach { it.outcome.finish(ProductResult.UNKNOWN, ErrorCode.INCOMPLETE, Coverage.PARTIAL) }
        entries.clear()
    }
}
