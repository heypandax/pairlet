package dev.ccpocket.relay

import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One socket's outbound frames, bounded in BYTES (audit H1).
 *
 * Ktor's outgoing channel is unbounded, so `outgoing.send` never pushed back: a peer that stopped reading
 * (TCP zero window) had everything addressed to it buffered in relay memory until the ping timeout — and
 * the pings themselves queued behind that backlog, so a healthy but slow link could be timed out too.
 * Suspending the sender instead is not an option: the sender is the daemon's read loop, and one slow phone
 * would stall every device of the account.
 *
 * So [offer] never suspends. Frames wait here and [pump] hands them to Ktor ONE AT A TIME, waiting until
 * each is written ([write] = send + flush) before the next. Ktor's own queue therefore holds at most the
 * frame being written, the protocol pings go out right behind it instead of behind the whole backlog, and
 * [queuedBytes] tracks what this socket really owes. Past [limitBytes] the backlog is dropped at once and
 * [onOverflow] ends the socket as a slow consumer; the peer reconnects and asks again.
 *
 * Every queue also draws on the relay-wide [budget]: a per-socket cap alone still lets a dozen stalled sockets
 * hold a dozen caps between them. When the sum goes over, the queues owing the MOST are cut the same way until
 * it is back under ([OutboundBudget.enforce]).
 */
internal class OutboundQueue(
    private val limitBytes: Long,
    private val write: suspend (Frame) -> Unit,
    private val onOverflow: (queuedBytes: Long, relayWide: Boolean) -> Unit,
    private val budget: OutboundBudget? = null,
) {
    private val frames = Channel<Frame>(Channel.UNLIMITED)

    // [queued] and [dead] change together under [lock], and every change of [queued] is mirrored into the
    // budget in the same critical section — so the relay-wide sum never counts a byte twice or loses one,
    // whichever of offer / pump / overflow / close gets there first.
    private val lock = Any()
    private var queued = 0L
    private var dead = false

    init { budget?.register(this) }

    /** Bytes accepted and not yet written (including the frame being written). */
    val queuedBytes: Long get() = synchronized(lock) { queued }

    /** Queue [frame] without suspending; throws [ClosedSendChannelException] once the socket is done for. */
    fun offer(frame: Frame) {
        val size = frame.data.size.toLong()
        var overflowAt = -1L
        synchronized(lock) {
            if (dead) throw ClosedSendChannelException("socket closed")
            if (queued + size > limitBytes) {
                overflowAt = queued + size
                retire()
            } else if (frames.trySend(frame).isFailure) {
                throw ClosedSendChannelException("socket closed")
            } else {
                queued += size
                budget?.add(size)
            }
        }
        if (overflowAt >= 0) {
            onOverflow(overflowAt, false)
            throw ClosedSendChannelException("slow_consumer")
        }
        budget?.enforce()
        // this very queue may have been the one the budget cut
        if (synchronized(lock) { dead }) throw ClosedSendChannelException("slow_consumer")
    }

    /** Write queued frames until [close] (or a write fails, i.e. the socket is gone). */
    suspend fun pump() {
        try {
            for (frame in frames) {
                try { write(frame) } finally { written(frame.data.size.toLong()) }
            }
        } catch (_: Throwable) {
            // socket closed or session cancelled: nothing left to deliver to
        } finally {
            close()
        }
    }

    fun close() { synchronized(lock) { retire() } }

    /** Cut this socket because the relay as a whole owes too much; false when it was already done for. */
    internal fun evict(): Boolean {
        val owed = synchronized(lock) {
            if (dead) return false
            queued.also { retire() }
        }
        onOverflow(owed, true)
        return true
    }

    private fun written(size: Long) = synchronized(lock) {
        if (!dead) { queued -= size; budget?.add(-size) }
    }

    /** Under [lock]: drop the backlog now — freeing it is the point — and hand its bytes back to the budget. */
    private fun retire() {
        if (dead) return
        dead = true
        budget?.add(-queued)
        budget?.unregister(this)
        queued = 0
        frames.cancel()
    }
}

/**
 * The relay-wide ceiling on bytes owed to peers, across every [OutboundQueue]. Over it, the queue owing the most
 * is cut as a slow consumer — repeatedly, until the total is back within [limitBytes]. The largest backlog goes
 * first because it is both the likeliest stalled reader and the one whose loss frees the most; healthy readers
 * owe a frame or two and are reached only if the stalled ones were not enough.
 */
internal class OutboundBudget(val limitBytes: Long) {
    private val total = AtomicLong()
    private val queues = ConcurrentHashMap.newKeySet<OutboundQueue>()

    val totalBytes: Long get() = total.get()

    fun register(q: OutboundQueue) { queues.add(q) }

    fun unregister(q: OutboundQueue) { queues.remove(q) }

    fun add(delta: Long) { total.addAndGet(delta) }

    /** Cut the biggest backlogs until the total fits. Serialized: two offers over budget at once must not both
     *  pick victims for the same excess. */
    fun enforce() {
        if (total.get() <= limitBytes) return
        synchronized(this) {
            while (total.get() > limitBytes) {
                val victim = queues.maxByOrNull { it.queuedBytes }?.takeIf { it.queuedBytes > 0 } ?: return
                if (!victim.evict()) queues.remove(victim)
            }
        }
    }
}
