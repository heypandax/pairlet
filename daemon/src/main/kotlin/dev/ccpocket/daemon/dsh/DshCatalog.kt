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
 * offer models against a session that no longer exists. [current] answers for the picker's WORKDIR first (the
 * session the user is most likely looking at), then for any live session, and in both cases takes the one that
 * reported MOST RECENTLY: every `session/new`, `session/resume` and config read-back re-publishes, so the newest
 * entry is the latest word on the user's dsh install — a plugin installed an hour ago is visible in a session
 * opened since, not in the oldest one still running.
 */
object DshCatalog {
    private data class Entry(val workdir: String?, val options: DshConfigOptions, val stamp: Long)

    private val live = ConcurrentHashMap<Any, Entry>()
    private val clock = java.util.concurrent.atomic.AtomicLong()

    fun publish(owner: Any, options: DshConfigOptions, workdir: String? = null) {
        if (options.isEmpty) return // never displace a real catalogue with an empty read-back
        live[owner] = Entry(workdir?.takeIf { it.isNotBlank() }, options, clock.incrementAndGet())
    }

    fun unpublish(owner: Any) {
        live.remove(owner)
    }

    /** The most recently published catalogue for [workdir], else the most recent of any live session, else null
     *  when the daemon drives no dsh session. */
    fun current(workdir: String? = null): DshConfigOptions? {
        val entries = live.values.toList()
        val wd = workdir?.takeIf { it.isNotBlank() }
        val pick = (if (wd != null) entries.filter { it.workdir == wd }.maxByOrNull { it.stamp } else null)
            ?: entries.maxByOrNull { it.stamp }
        return pick?.options
    }

    /** VISIBLE FOR TESTS: a leaked publish from one test would silently satisfy the next one's
     *  "no live session" branch. */
    internal fun clearForTest() {
        live.clear()
    }
}
