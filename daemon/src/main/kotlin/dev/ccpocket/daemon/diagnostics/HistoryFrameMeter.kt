package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The numbers of ONE replayed history window, for the single daemon log line an open (or reattach) writes.
 *
 * Until 2026-10 the daemon logged nothing between `← OpenSession` and the client's `HistoryApplied`, so a
 * seven-second open could not be split into "the daemon was slow" and "the frame was slow to arrive". This
 * carries what the conversation knows (read time, transcript size, window shape) and, riding the coroutine
 * context of the history emit, what only the transport knows: the relay sealer encodes in the emitting
 * coroutine and reports the encoded `ConvoHistory` size and whether [dev.ccpocket.daemon.server.FrameFitter]
 * had to shrink it. The LAN writer encodes later on its own coroutine, so a LAN-only open logs the frame as `?`.
 *
 * Content-free by construction: counts, sizes and durations only.
 */
class HistoryFrameMeter(private val readNanos: Long) : AbstractCoroutineContextElement(HistoryFrameMeter) {

    companion object Key : CoroutineContext.Key<HistoryFrameMeter> {
        /** Called by a sealer right after encoding [frame] inline; a no-op outside a metered history emit. */
        suspend fun record(frame: Frame, bytes: Int, shrunk: Boolean) {
            if (frame is ConvoHistory) currentCoroutineContext()[Key]?.recorded(bytes, shrunk)
        }
    }

    private var bytes = -1
    private var shrunk = false
    private var recipients = 0

    @Synchronized
    internal fun recorded(bytes: Int, shrunk: Boolean) {
        this.bytes = maxOf(this.bytes, bytes)
        this.shrunk = this.shrunk || shrunk
        recipients++
    }

    /** The largest encoded `ConvoHistory` a sealer reported, or null when none did (LAN, or nothing sent). */
    val frameBytes: Long? @Synchronized get() = bytes.takeIf { it >= 0 }?.toLong()

    /**
     * The log line, e.g. `c0ffee… history: read 14 ms, transcript 1723 lines / 6817580 B, window 100 rows
     * (byte budget dropped 0, more above), frame 401233 B`. [sent] = a frame went out ([slice] can be an
     * empty delta, a read error, or an empty transcript, none of which emit one).
     */
    @Synchronized
    fun line(convoId: String, slice: ReplaySlice, sent: Boolean): String = buildString {
        append(convoId).append(" history: read ").append(readNanos / 1_000_000).append(" ms")
        append(", transcript ").append(slice.sourceRows ?: "?").append(" lines / ").append(slice.sourceBytes ?: "?").append(" B")
        append(", ").append(if (slice.delta) "delta " else "window ").append(slice.messages.size).append(" rows")
        append(" (byte budget dropped ").append(slice.budgetDropped)
        if (slice.hasMore) append(", more above")
        append(")")
        when {
            slice.readError != null -> append(", read failed — not sent")
            !sent -> append(", nothing to send")
            bytes < 0 -> append(", frame ?")
            else -> {
                append(", frame ").append(bytes).append(" B")
                if (shrunk) append(" (shrunk to the client's frame cap)")
                if (recipients > 1) append(" to ").append(recipients).append(" clients")
            }
        }
    }
}
