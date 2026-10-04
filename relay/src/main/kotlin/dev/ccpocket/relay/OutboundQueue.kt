package dev.ccpocket.relay

import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.util.concurrent.atomic.AtomicBoolean
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
 * [queued] tracks what this socket really owes. Past [limitBytes] the backlog is dropped at once and
 * [onOverflow] ends the socket as a slow consumer; the peer reconnects and asks again.
 */
internal class OutboundQueue(
    private val limitBytes: Long,
    private val write: suspend (Frame) -> Unit,
    private val onOverflow: (queuedBytes: Long) -> Unit,
) {
    private val frames = Channel<Frame>(Channel.UNLIMITED)
    private val queued = AtomicLong()
    private val overflowed = AtomicBoolean(false)

    /** Bytes accepted and not yet written (including the frame being written). */
    val queuedBytes: Long get() = queued.get()

    /** Queue [frame] without suspending; throws [ClosedSendChannelException] once the socket is done for. */
    fun offer(frame: Frame) {
        val size = frame.data.size.toLong()
        val total = queued.addAndGet(size)
        if (total > limitBytes) {
            queued.addAndGet(-size)
            if (overflowed.compareAndSet(false, true)) {
                frames.cancel() // drop the backlog now: freeing it is the point
                onOverflow(total)
            }
            throw ClosedSendChannelException("slow_consumer")
        }
        if (frames.trySend(frame).isFailure) {
            queued.addAndGet(-size)
            throw ClosedSendChannelException("socket closed")
        }
    }

    /** Write queued frames until [close] (or a write fails, i.e. the socket is gone). */
    suspend fun pump() {
        try {
            for (frame in frames) {
                try { write(frame) } finally { queued.addAndGet(-frame.data.size.toLong()) }
            }
        } catch (_: Throwable) {
            // socket closed or session cancelled: nothing left to deliver to
        } finally {
            frames.cancel()
        }
    }

    fun close() { frames.cancel() }
}
