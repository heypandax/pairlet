package dev.ccpocket.observability

/** Process reporter budget: at most 64 operations × 32 fixed-size steps. No message strings.
 * Eviction loses old breadcrumbs, never the operation's business outcome or another trace's steps. */
internal class DiagnosticSteps {
    private val lock = DiagnosticLock()
    private val traces = linkedMapOf<Any, ArrayDeque<DiagnosticStep>>()
    fun register(key: Any) = lock.withLock {
        if (traces.size >= 64) traces.remove(traces.keys.first())
        traces[key] = ArrayDeque()
    }
    fun append(key: Any, step: DiagnosticStep) = lock.withLock {
        val steps = traces[key] ?: return@withLock
        if (steps.size == 32) steps.removeFirst()
        steps.addLast(step)
    }
    fun snapshot(key: Any): List<DiagnosticStep> = lock.withLock { traces[key]?.toList().orEmpty() }
    fun clear() = lock.withLock { traces.clear() }
}
