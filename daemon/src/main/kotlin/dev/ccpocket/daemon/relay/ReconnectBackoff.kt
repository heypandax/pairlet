package dev.ccpocket.daemon.relay

/**
 * Reconnect pacing for [RelayClient.run]: how long to wait before the next attempt, given how the last link
 * ended. Pure — the caller adds jitter and does the waiting — so the sequence is unit-testable.
 *
 * The backoff resets after a link that was ESTABLISHED AND STABLE (attached for at least [STABLE_LINK_MS]),
 * however it ended. It used to reset only on a clean close, so boot-time failures followed by hours of
 * healthy link and a heartbeat timeout / network switch (both throw) kept doubling: after a few of those
 * every wake or network change waited 15–30s while the phone showed the computer offline (audit
 * 2026-10-04). A link that drops right after attaching is a flap, not a recovery, and keeps backing off.
 */
internal class ReconnectBackoff {
    /** How one link attempt ended. [attachedForMs] is null when the attempt never reached Attached. */
    data class LinkEnd(val clean: Boolean, val attachedForMs: Long?, val closeReason: String? = null) {
        /** The relay closed this link because another daemon attached with the same account. */
        val superseded: Boolean get() = clean && closeReason == SUPERSEDED_REASON
    }

    private var backoff = BASE_MS

    /** The nominal (un-jittered) wait before the next attempt. */
    fun next(end: LinkEnd): Long {
        // Superseded = another daemon holds this account. Coming straight back would kick it off, and it
        // would kick us back: a one-second ping-pong where neither link is usable. Wait long, every time,
        // and leave the backoff at its cap so a failure right after is not a 1s retry either.
        if (end.superseded) {
            backoff = MAX_MS
            return SUPERSEDED_MS
        }
        if ((end.attachedForMs ?: 0L) >= STABLE_LINK_MS) backoff = BASE_MS
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(MAX_MS)
        return wait
    }

    companion object {
        const val BASE_MS = 1_000L
        const val MAX_MS = 30_000L

        /** The close reason the relay sends the older daemon when a newer one attaches (RelayServer). */
        const val SUPERSEDED_REASON = "superseded"
        const val SUPERSEDED_MS = 60_000L

        /** Attached at least this long = a working link, not a flap. */
        const val STABLE_LINK_MS = 30_000L
    }
}
