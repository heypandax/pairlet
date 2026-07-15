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
    return frames.filterIndexed { i, f ->
        when (f) {
            is ListDirectories -> lastListDirs[f.root] == i
            is ListSessions -> lastSessions[f.workdir] == i
            is OpenSession -> lastOpen[f.workdir to f.resumeId] == i
            else -> true
        }
    }
}

/** Apply [dedupeReconnectBacklog] to a connection outbox in place — called right after a fresh socket's
 *  handshake, before the writer starts flushing what accumulated while the link was down. */
internal fun Channel<Frame>.dedupeBacklog() {
    val all = drainAll()
    if (all.isEmpty()) return
    dedupeReconnectBacklog(all).forEach { trySend(it) }
}
