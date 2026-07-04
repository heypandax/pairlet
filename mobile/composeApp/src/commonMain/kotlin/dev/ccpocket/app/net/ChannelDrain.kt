package dev.ccpocket.app.net

import kotlinx.coroutines.channels.Channel

/** Everything queued but not yet written, non-suspending. Connection outboxes deliberately buffer
 *  across reconnects; a machine switch / direct→relay fallback drains them through this so leftover
 *  frames are re-routed instead of flushing into the wrong link. */
internal fun <T> Channel<T>.drainAll(): List<T> = buildList {
    while (true) add(this@drainAll.tryReceive().getOrNull() ?: break)
}
