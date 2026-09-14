package dev.ccpocket.app.net

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import kotlinx.coroutines.channels.Channel

/** Everything queued but not yet written, non-suspending. Connection outboxes deliberately buffer
 *  across reconnects; a machine switch / direct→relay fallback drains them through this so leftover
 *  frames are re-routed instead of flushing into the wrong link. */
internal fun <T> Channel<T>.drainAll(): List<T> = buildList {
    while (true) add(this@drainAll.tryReceive().getOrNull() ?: break)
}

/**
 * Collapse duplicated idempotent requests that piled up across reconnects (issue #143): every reconnect
 * trigger queues its own ListDirectories + reattach OpenSession into the cross-reconnect outbox, and the
 * triggers don't know about each other — a burst used to flush as N identical reattach volleys on the
 * fresh socket (daemon logs showed 4 OpenSessions inside 400ms on one live link). Keeps only the LAST
 * ListDirectories per root, the LAST ListSessions per workdir, and the LAST OpenSession per
 * (workdir, resumeId) — the newest one carries the freshest mode/model flags. Everything else (prompts,
 * verdicts, uploads…) is preserved untouched and in order: those are NOT idempotent and must never be
 * dropped here.
 */
internal fun dedupeReconnectBacklog(frames: List<Frame>): List<Frame> {
    if (frames.size < 2) return frames
    val keep = dedupeReconnectMask(frames)
    return frames.filterIndexed { i, _ -> keep[i] }
}

/** [dedupeReconnectBacklog]'s decision per index (true = keep), for outboxes that wrap their frames. */
internal fun dedupeReconnectMask(frames: List<Frame>): BooleanArray {
    val lastListDirs = HashMap<String?, Int>()
    val lastSessions = HashMap<String, Int>()
    val lastOpen = HashMap<Pair<String, String?>, Int>()
    frames.forEachIndexed { i, f ->
        when (f) {
            is ListDirectories -> lastListDirs[f.root] = i
            is ListSessions -> lastSessions[f.workdir] = i
            is OpenSession -> lastOpen[f.workdir to f.resumeId] = i
            else -> {}
        }
    }
    return BooleanArray(frames.size) { i ->
        when (val f = frames[i]) {
            is ListDirectories -> lastListDirs[f.root] == i
            is ListSessions -> lastSessions[f.workdir] == i
            is OpenSession -> lastOpen[f.workdir to f.resumeId] == i
            else -> true
        }
    }
}

/** Put back what a connection outbox kept after its post-handshake dedupe — reporting anything that no longer fit. */
internal fun <T> Channel<T>.requeueRetained(retained: List<T>) {
    var dropped = 0L
    retained.forEach { if (trySend(it).isFailure) dropped++ }
    if (dropped > 0) dev.ccpocket.observability.Diagnostics.report(
        dev.ccpocket.observability.ErrorPath.OUTBOX, dev.ccpocket.observability.Stage.QUEUE,
        dev.ccpocket.observability.ErrorCode.QUEUE_CLOSED,
        metrics = dev.ccpocket.observability.SafeMetrics(totalCount = retained.size.toLong(), failedCount = dropped),
    )
}
