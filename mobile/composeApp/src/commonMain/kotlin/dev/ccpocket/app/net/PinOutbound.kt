package dev.ccpocket.app.net

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.channels.Channel

/**
 * Whether a queued project-pin frame (issue #362) still speaks for the sync generation, binding lease and computer
 * it was built for. Checked when the frame is queued AND again when a writer actually takes it — a frame that stopped
 * being valid in between is dropped, never handed to whatever connection or computer came next. Holds no secret.
 */
fun interface PinDispatchFence {
    fun isValid(): Boolean
}

enum class PinEnqueueResult {
    /** Queued on the exact connection it was captured for. It may still be dropped before it is written. */
    ACCEPTED,

    /** The outbox has no room right now. Nothing was queued; retry later. Not a transport failure. */
    FULL,

    /** The fence, the captured connection or the computer is gone. Nothing was queued; the generation is over. */
    RETIRED,
}

/** A project-pin sync generation's only way out: non-suspending, and never a trigger for a reconnect. */
fun interface PinOutbound {
    fun tryEnqueue(frame: SyncProjectPins, fence: PinDispatchFence): PinEnqueueResult
}

/** One outbox entry: an ordinary frame ([pinFence] null), or a scoped pin frame bound to connection [connGen]. */
internal class OutboundEntry(val frame: Frame, val pinFence: PinDispatchFence? = null, val connGen: Int = 0) {
    val scoped: Boolean get() = pinFence != null
}

/**
 * An E2E connection's cross-reconnect data outbox. Ordinary frames keep the long-standing behaviour: they buffer
 * across reconnects, a superseded writer hands them back to the live connection, and a switch or LAN→relay fallback
 * drains them for re-routing. Scoped pin frames are the exception — they belong to ONE connection generation, so
 * every one of those paths drops them instead; the durable pin outbox is fetched and flushed again on the next
 * connection's own subscription.
 */
internal class ScopedOutbox {
    private val channel = Channel<OutboundEntry>(Channel.BUFFERED)

    suspend fun send(frame: Frame) = channel.send(OutboundEntry(frame))

    /** Queue [frame] for connection [expectedGen] only if that is still the live connection ([liveGen]) and
     *  [fence] still holds. Never suspends. */
    fun tryEnqueuePin(frame: SyncProjectPins, fence: PinDispatchFence, expectedGen: Int, liveGen: () -> Int): PinEnqueueResult {
        if (expectedGen == 0 || liveGen() != expectedGen || !fence.isValid()) return PinEnqueueResult.RETIRED
        val sent = channel.trySend(OutboundEntry(frame, fence, expectedGen))
        return when {
            sent.isSuccess -> PinEnqueueResult.ACCEPTED
            sent.isClosed -> PinEnqueueResult.RETIRED
            else -> PinEnqueueResult.FULL
        }
    }

    /** Ordinary frames queued but not yet written, for re-routing. Scoped pin frames are discarded. */
    fun drainOrdinary(): List<Frame> = channel.drainAll().filterNot { it.scoped }.map { it.frame }

    /** Right after connection [gen]'s handshake: collapse ordinary reconnect duplicates (issue #143) and drop every
     *  pin frame queued for another connection. Order is otherwise preserved. */
    fun prepareFor(gen: Int) {
        val all = channel.drainAll()
        if (all.isEmpty()) return
        val kept = all.filter { !it.scoped || it.connGen == gen }
        val ordinary = kept.filterNot { it.scoped }
        val keepOrdinary = dedupeReconnectMask(ordinary.map { it.frame })
        var i = 0
        val retained = kept.filter { e -> if (e.scoped) true else keepOrdinary[i++] }
        channel.requeueRetained(retained)
    }

    /**
     * Connection [gen]'s writer. [isCurrent] is the connection's own generation check (#142). A superseded writer
     * hands its ordinary frame back to the live connection and dies; a scoped pin frame is dropped. A pin frame whose
     * connection or fence no longer holds is dropped immediately before it would be encoded, sealed and written.
     */
    suspend fun runWriter(gen: Int, isCurrent: () -> Boolean, write: suspend (Frame) -> Unit) {
        for (entry in channel) {
            if (!isCurrent()) {
                if (!entry.scoped) channel.send(entry)
                throw DeadLinkException()
            }
            if (entry.scoped && (entry.connGen != gen || entry.pinFence?.isValid() != true)) continue
            write(entry.frame)
        }
    }
}
