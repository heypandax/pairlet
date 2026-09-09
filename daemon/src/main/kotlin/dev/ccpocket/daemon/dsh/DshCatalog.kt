package dev.ccpocket.daemon.dsh

import java.util.concurrent.ConcurrentHashMap

/**
 * The model / reasoning-effort catalogue of the dsh sessions this daemon currently drives (issue #333, re-sourced
 * by the dsh 0.1.2 ACP switch) — the cheap source the picker prefers over booting a dsh of its own.
 *
 * WHY IT EXISTS: on the ACP surface the catalogue is a property of a SESSION's answer (`session/new` and
 * `session/resume` both return `configOptions`), not of a host anybody can query. So when the user
 * already has a dsh session open, its own read-back is both free and the freshest thing on the machine;
 * [DshModelService] only boots a throwaway `dsh --profile acp` when nothing is open. That matters more
 * here than it did for the old web transport, because a throwaway ACP client must CREATE a session to be
 * told anything, and every created session is a row in dsh's own store.
 *
 * Entries are keyed by the owning backend and dropped when its process ends — a stale catalogue would
 * offer models against a session that no longer exists. Ordering is registration order and [current]
 * takes the oldest live one; any of them answers equivalently, since the catalogue describes the user's
 * dsh install rather than one conversation.
 */
object DshCatalog {
    private val live = ConcurrentHashMap<Any, DshConfigOptions>()
    private val order = java.util.concurrent.CopyOnWriteArrayList<Any>()

    fun publish(owner: Any, options: DshConfigOptions) {
        if (options.isEmpty) return // never displace a real catalogue with an empty read-back
        if (live.put(owner, options) == null) order.addIfAbsent(owner)
    }

    fun unpublish(owner: Any) {
        live.remove(owner)
        order.remove(owner)
    }

    /** The oldest still-published catalogue, or null when the daemon drives no dsh session. */
    fun current(): DshConfigOptions? = order.firstNotNullOfOrNull { live[it] }

    /** VISIBLE FOR TESTS: a leaked publish from one test would silently satisfy the next one's
     *  "no live session" branch. */
    internal fun clearForTest() {
        live.clear()
        order.clear()
    }
}
