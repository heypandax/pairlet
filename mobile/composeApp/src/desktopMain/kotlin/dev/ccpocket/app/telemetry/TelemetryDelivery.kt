package dev.ccpocket.app.telemetry

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Bounded, serial, opt-out-aware delivery. An old generation never enters a reopened collector. */
internal class TelemetryDelivery<T>(
    private val scope: CoroutineScope,
    initiallyEnabled: Boolean,
    capacity: Int = 32,
    private val send: suspend (T) -> Unit,
) {
    private data class Pending<T>(val generation: Long, val value: T)
    private val lock = Any()
    private val queue = Channel<Pending<T>>(capacity)
    private var enabled = initiallyEnabled
    private var generation = 0L
    private var inFlight: Job? = null
    private val worker = scope.launch {
        for (pending in queue) {
            val job = synchronized(lock) {
                if (!enabled || pending.generation != generation) return@synchronized null
                scope.launch(start = CoroutineStart.LAZY) {
                    try { send(pending.value) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* No retry or recursive diagnostics. */ }
                }.also { inFlight = it }
            } ?: continue
            job.start()
            job.join()
            synchronized(lock) { if (inFlight === job) inFlight = null }
        }
    }

    fun currentGeneration(): Long? = synchronized(lock) { generation.takeIf { enabled } }

    fun offer(value: T, expectedGeneration: Long? = null): Boolean = synchronized(lock) {
        enabled && (expectedGeneration == null || expectedGeneration == generation) &&
            queue.trySend(Pending(generation, value)).isSuccess
    }

    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value
        generation++
        while (queue.tryReceive().isSuccess) Unit
        inFlight?.cancel()
        inFlight = null
    }

    fun close() { setEnabled(false); queue.close(); worker.cancel() }
}
