package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** Observes the existing consumption ledger; cannot enqueue, acknowledge, retry, or settle work.
 * Entries are local to one conversation. No ambient SDK scope and no business data in reports. */
class PromptDiagnostics(private val convoId: String, private val scope: CoroutineScope,
    private val emit: suspend (Frame) -> Unit) {
    private class Entry(val context: DiagnosticContext) {
        val started = TimeSource.Monotonic.markNow()
        val trace = Diagnostics.begin(ErrorPath.PROMPT, context.traceId, context.spanId)?.also { it.stage(Stage.RECEIVE) }
        var generation: Long? = null
        var writtenGeneration: Long? = null
        val milestones = hashSetOf<String>()
        var output = false
        var terminal = false
        var failed = false
        var recovering = false
    }
    private val entries = linkedMapOf<String, Entry>()
    private val outgoing = Channel<Frame>(64)
    init {
        scope.launch { for (frame in outgoing) runCatching { emit(frame) } }
        scope.launch {
            while (true) {
                delay(60_000)
                synchronized(entries) {
                    val expired = entries.filterValues { it.started.elapsedNow().inWholeHours >= 24 }.keys
                    expired.forEach { id -> entries.remove(id)?.let(::unobserved) }
                }
            }
        }
    }
    @Volatile private var needsOutput = false

    fun register(promptId: String?, context: DiagnosticContext?) {
        if (promptId == null) return
        val valid = context?.validated() ?: return
        synchronized(entries) {
            entries[promptId]?.let { entry ->
                if (entry.failed && entry.context == valid) { entry.terminal = false; entry.failed = false; entry.recovering = true; entry.generation = null }
                return
            }
            if (entries.size >= 256) entries.remove(entries.keys.first())?.let(::unobserved)
            entries[promptId] = Entry(valid)
        }
    }
    fun written(promptId: String?, generation: Long) {
        synchronized(entries) { entries[promptId]?.takeIf { !it.terminal }?.writtenGeneration = generation }
    }
    fun ack(promptId: String) = milestone(promptId, "ack", Stage.ACK)
    fun queued(promptId: String) = milestone(promptId, "queued", Stage.QUEUE)
    private fun milestone(promptId: String, stage: String, diagnosticStage: Stage) {
        synchronized(entries) {
            val entry = entries[promptId] ?: return
            if (entry.terminal || !entry.milestones.add(stage)) return
            entry.trace?.stage(diagnosticStage)
            publish(entry, stage)
        }
    }
    fun consumed(promptId: String, generation: Long) {
        synchronized(entries) {
            val entry = entries[promptId] ?: return
            if (entry.terminal) return
            entry.generation = generation
            needsOutput = true
            if (entry.milestones.add("consumed")) {
                entry.trace?.stage(Stage.EXECUTE)
                publish(entry, "consumed")
            }
        }
    }
    fun output(generation: Long) {
        if (!needsOutput) return
        synchronized(entries) {
            for (entry in entries.values) if (entry.generation == generation && !entry.terminal && !entry.output) {
                entry.output = true
                entry.trace?.stage(Stage.RECEIVE)
                publish(entry, "first_output")
            }
            needsOutput = entries.values.any { !it.terminal && it.generation != null && !it.output }
        }
    }
    fun complete(generation: Long, result: String) {
        synchronized(entries) {
            for (entry in entries.values) if (entry.generation == generation && !entry.terminal) {
                entry.terminal = true
                entry.failed = result == "failure"
                if (entry.recovering && result == "success") entry.trace?.recovered()
                else entry.trace?.finish(when (result) {
                    "success" -> Outcome.SUCCESS
                    "cancelled" -> Outcome.CANCELLED
                    "failure" -> Outcome.FAILURE
                    else -> Outcome.UNKNOWN
                }, Stage.COMPLETE, when (result) {
                    "failure" -> ErrorCode.PROCESS_EXITED
                    "success", "cancelled" -> ErrorCode.OK
                    else -> ErrorCode.INCOMPLETE
                }, metrics = SafeMetrics(resultQuality = if (result in setOf("success", "failure", "cancelled"))
                    ResultQuality.COMPLETE else ResultQuality.UNKNOWN))
                publish(entry, "complete", result)
            }
            needsOutput = entries.values.any { !it.terminal && it.generation != null && !it.output }
        }
    }
    fun processExited(generation: Long) {
        synchronized(entries) {
            for (entry in entries.values) if (!entry.terminal && entry.generation == null && entry.writtenGeneration == generation) {
                // The business ledger may redeliver this queued request. Record lost evidence without
                // declaring execution failure, changing its ledger, or inventing a consumption ACK.
                entry.trace?.stage(Stage.QUEUE, ErrorCode.PROCESS_EXITED)
                publish(entry, "awaiting_redelivery")
            }
        }
        complete(generation, "failure")
    }
    fun failed(promptId: String?, error: Throwable? = null) {
        synchronized(entries) {
            val entry = entries[promptId] ?: return
            if (entry.terminal) return
            entry.terminal = true; entry.failed = true
            entry.trace?.finish(Outcome.FAILURE, Stage.DISPATCH, ErrorCode.SEND_FAILED, error)
            publish(entry, "complete", "failure")
        }
    }
    fun handled(promptId: String?) {
        synchronized(entries) {
            val entry = entries[promptId] ?: return
            if (entry.terminal) return
            entry.terminal = true
            entry.trace?.finish(Outcome.UNKNOWN, Stage.COMPLETE, ErrorCode.INCOMPLETE,
                metrics = SafeMetrics(resultQuality = ResultQuality.UNKNOWN))
            publish(entry, "handled")
        }
    }
    private fun unobserved(entry: Entry) {
        if (entry.terminal) return
        entry.terminal = true
        entry.trace?.finish(Outcome.UNKNOWN, Stage.RECONCILE, ErrorCode.INCOMPLETE,
            metrics = SafeMetrics(resultQuality = ResultQuality.UNKNOWN))
        publish(entry, "unobserved")
    }
    fun close() {
        synchronized(entries) { entries.values.forEach(::unobserved); entries.clear(); needsOutput = false }
        outgoing.close()
    }
    private fun publish(entry: Entry, stage: String, result: String = "unknown") {
        val frame = PromptProgress(convoId, entry.context, stage, result, entry.output)
        // A diagnostic milestone must never hold the Agent pump or change its failure behavior.
        outgoing.trySend(frame)
    }
}
