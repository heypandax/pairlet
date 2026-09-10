package dev.ccpocket.observability

/** SDK-neutral manual timing recipe. Samples by the operation ID before looking at its outcome.
 * The same E2E operation therefore makes the same 1% decision on both components. */
data class NativeTraceRecipe(val traceId: String, val spanId: String, val parentSpanId: String?,
    val operation: String, val startMs: Long, val endMs: Long, val failed: Boolean,
    val children: List<Child>) {
    data class Child(val operation: String, val startMs: Long, val endMs: Long)
    companion object {
        fun from(record: DiagnosticRecord): NativeTraceRecipe? {
            val id = record.traceId ?: return null
            val spanId = record.spanId ?: return null
            val elapsed = record.elapsedMs?.coerceIn(0, 86_400_000) ?: return null
            if (record.path !in setOf(ErrorPath.CONNECTION, ErrorPath.PAIRING, ErrorPath.SESSION_OPEN, ErrorPath.PROMPT) ||
                record.kind !in setOf(DiagnosticKind.RESULT, DiagnosticKind.ERROR) ||
                record.outcome !in setOf(Outcome.SUCCESS, Outcome.FAILURE, Outcome.TIMEOUT) ||
                (id.take(8).toLongOrNull(16) ?: 1) % 100 != 0L) return null
            val end = record.occurredAtMs
            val start = (end - elapsed).coerceAtLeast(0)
            val stages = record.steps.takeLast(15)
            return NativeTraceRecipe(id, spanId, record.parentSpanId, record.path.name.lowercase(), start, end,
                record.outcome in setOf(Outcome.FAILURE, Outcome.TIMEOUT), stages.mapIndexed { index, step ->
                    val from = (start + step.elapsedMs.coerceIn(0, elapsed)).coerceAtMost(end)
                    val to = stages.getOrNull(index + 1)?.elapsedMs?.coerceIn(0, elapsed)?.let { start + it } ?: end
                    Child(step.stage.name.lowercase(), from, to.coerceIn(from, end))
                })
        }
    }
}
