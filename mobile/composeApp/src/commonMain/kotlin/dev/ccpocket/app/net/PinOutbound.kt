package dev.ccpocket.app.net

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.SyncProjectPins
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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

/**
 * Whether a transient frame still speaks for the intent it was queued for — the batch, the memo attempt, the
 * binding. Like [PinDispatchFence] it is checked when the frame is queued AND again when a writer takes it.
 */
fun interface TransientDispatchFence {
    fun isValid(): Boolean
}

enum class TransientEnqueueResult {
    /** Queued on the exact connection it was captured for. NOT a delivery: see [TransientTicket.outcome]. */
    ACCEPTED,

    /** The outbox has no room right now. Nothing was queued. */
    FULL,

    /** The fence, the captured connection or the computer is gone. Nothing was queued. */
    RETIRED,
}

/** The single terminal state of a transient frame. Exactly one is reported per accepted entry. */
enum class TransientDisposition {
    /** Proven never handed to a socket: dropped by a fence, a supersede, a drain or a closing connection. */
    NOT_WRITTEN,

    /** Handed to the local socket. Still not a receipt — the peer's acknowledgement is a separate frame. */
    WRITTEN,

    /** The write had started when it failed or was cancelled; the peer may or may not have the frame. */
    INDETERMINATE,
}

/** What a transient enqueue returns: whether it was queued, and the entry's terminal state once it has one. An
 *  entry that was not [TransientEnqueueResult.ACCEPTED] is already [TransientDisposition.NOT_WRITTEN]. */
class TransientTicket internal constructor(
    val result: TransientEnqueueResult,
    val outcome: Deferred<TransientDisposition>,
) {
    internal companion object {
        /** A ticket whose fate is already known — for a writer that answers on the spot. */
        fun settled(disposition: TransientDisposition) = TransientTicket(
            if (disposition == TransientDisposition.NOT_WRITTEN) TransientEnqueueResult.RETIRED else TransientEnqueueResult.ACCEPTED,
            CompletableDeferred(disposition),
        )
    }
}

/**
 * A transient entry's fence and its once-only terminal state. A writer and a drop path can reach the same entry
 * at the same moment (a connection closing while its writer takes the next frame), so the entry is CLAIMED first:
 * whoever claims it decides its fate, and the loser does nothing. Reporting "not written" for a frame a writer
 * went on to send would invite exactly the duplicate this kind of frame exists to prevent.
 */
internal class TransientSend(val fence: TransientDispatchFence, private val onSettled: () -> Unit = {}) {
    val outcome = CompletableDeferred<TransientDisposition>()
    private val claim = CompletableDeferred<Boolean>()

    /** The writer's claim. False = a drop path got there first; the frame must not be written. */
    fun claimForWrite(): Boolean = claim.complete(true)

    /** A drop path's claim. Does nothing once a writer holds the entry. */
    fun drop() { if (claim.complete(false)) settle(TransientDisposition.NOT_WRITTEN) }

    /** The claiming writer's verdict. */
    fun written(ok: Boolean) = settle(if (ok) TransientDisposition.WRITTEN else TransientDisposition.INDETERMINATE)

    private fun settle(disposition: TransientDisposition) { if (outcome.complete(disposition)) onSettled() }
}

/**
 * One outbox entry: an ordinary frame, a scoped pin frame ([pinFence]) or a transient frame ([transient]). The two
 * scoped kinds are bound to connection [connGen] and never outlive it.
 */
internal class OutboundEntry(
    val frame: Frame,
    val pinFence: PinDispatchFence? = null,
    val connGen: Int = 0,
    val transient: TransientSend? = null,
) {
    val scoped: Boolean get() = pinFence != null || transient != null

    /** A scoped entry that is being discarded instead of written. */
    fun dropped() { transient?.drop() }

    fun fenceHolds(): Boolean = transient?.fence?.isValid() ?: (pinFence?.isValid() == true)
}

/**
 * An E2E connection's cross-reconnect data outbox. Ordinary frames keep the long-standing behaviour: they buffer
 * across reconnects, a superseded writer hands them back to the live connection, and a switch or LAN→relay fallback
 * drains them for re-routing. Scoped pin frames are the exception — they belong to ONE connection generation, so
 * every one of those paths drops them instead; the durable pin outbox is fetched and flushed again on the next
 * connection's own subscription. Transient frames (voice memo upload and dispatch) follow the same rule and
 * additionally report what became of them: a frame the user confirmed once must never be re-sent by a reconnect,
 * so its owner has to learn whether it was written, provably not written, or lost mid-write.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ScopedOutbox {
    // onUndeliveredElement: an entry handed to a writer that is cancelled at that very moment is dropped by the
    // channel itself and never reaches [runWriter] or [retire]. A transient entry lost that way was provably
    // not written, and its owner must hear so instead of waiting for a verdict nobody is left to give.
    private val channel = Channel<OutboundEntry>(Channel.BUFFERED, onUndeliveredElement = { it.dropped() })

    // Transient entries queued and not yet settled. While it is zero — always, unless a memo frame is in the
    // queue right now — [retire] does not touch the queue, so ordinary and pin frames keep their exact order.
    private val unsettled = AtomicInt(0)

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

    /** Queue [frame] for connection [expectedGen] only if that is still the live connection ([liveGen]) and
     *  [fence] still holds. Never suspends. */
    fun tryEnqueueTransient(frame: Frame, fence: TransientDispatchFence, expectedGen: Int, liveGen: () -> Int): TransientTicket {
        val send = TransientSend(fence) { unsettled.decrementAndFetch() }
        // counted before the connection check and before the entry can be seen, so a connection closing at this
        // very moment still looks for it; every path below settles the entry, which takes the count back
        unsettled.incrementAndFetch()
        val result = when {
            expectedGen == 0 || liveGen() != expectedGen || !fence.isValid() -> TransientEnqueueResult.RETIRED
            else -> {
                val sent = channel.trySend(OutboundEntry(frame, connGen = expectedGen, transient = send))
                when {
                    sent.isSuccess -> TransientEnqueueResult.ACCEPTED
                    sent.isClosed -> TransientEnqueueResult.RETIRED
                    else -> TransientEnqueueResult.FULL
                }
            }
        }
        if (result != TransientEnqueueResult.ACCEPTED) send.drop()
        return TransientTicket(result, send.outcome)
    }

    /** Ordinary frames queued but not yet written, for re-routing. Scoped frames are discarded. */
    fun drainOrdinary(): List<Frame> {
        val all = channel.drainAll()
        all.forEach { if (it.scoped) it.dropped() }
        return all.filterNot { it.scoped }.map { it.frame }
    }

    /** Connection [gen] is over: whatever it still had queued for itself was never written. Ordinary frames stay
     *  for the next connection. Without this a transient frame queued behind a dead socket would keep its owner
     *  waiting until some later connection happened to clean the queue. */
    fun retire(gen: Int) {
        if (unsettled.load() == 0) return // no memo frame is waiting: leave the queue exactly as it is
        val all = channel.drainAll()
        if (all.isEmpty()) return
        val (gone, kept) = all.partition { it.transient != null && it.connGen == gen }
        gone.forEach { it.dropped() }
        requeue(kept)
    }

    /** Put back what a drain kept. An entry that no longer fits is lost; a transient one among them was never
     *  written, and says so. */
    private fun requeue(kept: List<OutboundEntry>) {
        val lost = kept.filter { channel.trySend(it).isFailure }
        if (lost.isEmpty()) return
        lost.forEach { it.dropped() }
        dev.ccpocket.observability.Diagnostics.report(
            dev.ccpocket.observability.ErrorPath.OUTBOX, dev.ccpocket.observability.Stage.QUEUE,
            dev.ccpocket.observability.ErrorCode.QUEUE_CLOSED,
            metrics = dev.ccpocket.observability.SafeMetrics(totalCount = kept.size.toLong(), failedCount = lost.size.toLong()),
        )
    }

    /** Right after connection [gen]'s handshake: collapse ordinary reconnect duplicates (issue #143) and drop every
     *  pin frame queued for another connection. Order is otherwise preserved. */
    fun prepareFor(gen: Int) {
        val all = channel.drainAll()
        if (all.isEmpty()) return
        val kept = all.filter { !it.scoped || it.connGen == gen }
        all.forEach { if (it.scoped && it.connGen != gen) it.dropped() }
        val ordinary = kept.filterNot { it.scoped }
        val keepOrdinary = dedupeReconnectMask(ordinary.map { it.frame })
        var i = 0
        val retained = kept.filter { e -> if (e.scoped) true else keepOrdinary[i++] }
        requeue(retained)
    }

    /**
     * Connection [gen]'s writer. [isCurrent] is the connection's own generation check (#142). A superseded writer
     * hands its ordinary frame back to the live connection and dies; a scoped frame is dropped. A scoped frame whose
     * connection or fence no longer holds is dropped immediately before it would be encoded, sealed and written.
     * A transient frame reports [TransientDisposition.WRITTEN] only after [write] returned; a [write] that throws or
     * is cancelled reports [TransientDisposition.INDETERMINATE], because bytes may already be on the wire.
     */
    suspend fun runWriter(gen: Int, isCurrent: () -> Boolean, write: suspend (Frame) -> Unit) {
        for (entry in channel) {
            if (!isCurrent()) {
                if (entry.scoped) entry.dropped() else channel.send(entry)
                throw DeadLinkException()
            }
            if (entry.scoped && (entry.connGen != gen || !entry.fenceHolds())) {
                entry.dropped()
                continue
            }
            val transient = entry.transient
            if (transient == null) {
                write(entry.frame)
                continue
            }
            if (!transient.claimForWrite()) continue // retired while it sat in the queue
            try {
                write(entry.frame)
            } catch (t: Throwable) {
                transient.written(ok = false)
                throw t
            }
            transient.written(ok = true)
        }
    }
}
