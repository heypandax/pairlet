package dev.ccpocket.app.net

import kotlin.concurrent.Volatile // commonMain: JVM resolves kotlin.jvm.Volatile implicitly, Kotlin/Native (iOS) does not

/**
 * The evidence behind [RelayE2EConnection]'s #298 silence watchdog, driven by an explicit clock so its
 * decisions can be pinned without a socket. The thresholds and the window rule stay in the connection's
 * companion ([RelayE2EConnection.silenceDeafTripped], [RelayE2EConnection.silenceWindowMs]); this class only
 * holds what they are applied to.
 *
 * Two lifetimes, on purpose (docs/design/SLOW-LINK-RESILIENCE.md 3.1):
 *  - the strikes and the time of the last trip belong to the connection OBJECT and survive reconnects — that
 *    is their whole point: a rebuild that is soon followed by another trip says rebuilding did not help, so
 *    the next window is longer. They are forgotten only [RelayE2EConnection.SILENCE_STRIKE_MEMORY_MS] after
 *    the last trip, checked lazily ([strikesAt]); downlink never clears them, because the daemon seals a
 *    DaemonInfo right after every handshake and every rebuilt link would otherwise start forgiven;
 *  - everything else belongs to ONE handshaken link ([Link], from [linkUp]) — its age, its silence clock and its
 *    send count — so a superseded link (#142) keeps counting into its own state and never into its successor's.
 *
 * Fed from two coroutines that may run on different threads — the writer ([Link.onSent]) and the reader
 * ([Link.onInbound]) — hence @Volatile. Only the writer of the live link writes the strikes; the per-link
 * counters are benignly racy, exactly like the inline ones this replaced: an off-by-one send count or a late
 * reset costs at most one window that is a little longer or shorter than intended.
 */
internal class SilenceWatchdog {
    @Volatile private var strikes = 0
    @Volatile private var lastTripAt = 0L
    @Volatile private var target: Any? = null

    /** The silence trips still remembered at [now]: all of them until [RelayE2EConnection.SILENCE_STRIKE_MEMORY_MS]
     *  have passed since the last one, none after. */
    fun strikesAt(now: Long): Int =
        if (strikes > 0 && now - lastTripAt >= RelayE2EConnection.SILENCE_STRIKE_MEMORY_MS) 0 else strikes

    /** A handshake completed at [now]: the new link's age and its silence clock both start there, because the
     *  completed handshake IS inbound proof. [isCurrent] says whether that link still owns the connection (#142).
     *
     *  [to] names the computer this link reaches. The strikes are evidence about ONE computer's path — "rebuilding
     *  did not help HERE" — so a link to a different computer starts without them: the connection object outlives
     *  a machine switch, and a slow link to the computer just left must not buy the next one minutes of patience
     *  it has not earned (a zombie there would go unnoticed for up to the widest window). Null = not told, keep. */
    fun linkUp(now: Long, to: Any? = null, isCurrent: () -> Boolean = { true }): Link {
        if (to != null && to != target) {
            if (target != null) { strikes = 0; lastTripAt = 0L }
            target = to
        }
        return Link(now, isCurrent)
    }

    inner class Link internal constructor(private val upAt: Long, private val isCurrent: () -> Boolean) {
        @Volatile private var sentSinceInbound = 0
        @Volatile private var lastInboundAt = upAt

        /**
         * How long this link may stay silent under sends, as of [now]. The link's age is read where its CURRENT
         * silence began (the last decrypt, or the handshake), not at [now]: what is being judged is the silence,
         * and one that began on a young link is a reply in flight for its whole length. Read at [now], the doubling
         * would fall away the moment the link turns a minute old — a young link with one strike would trip at a
         * minute of silence instead of 80s, and the incident replay would widen 40/40/80 instead of 40/80/160.
         */
        fun windowMs(now: Long): Long = RelayE2EConnection.silenceWindowMs(strikesAt(now), lastInboundAt - upAt)

        /** A sealed frame went out at [now]. True = the link has gone silence-deaf and the caller forces a
         *  re-handshake. Signals once (the send count restarts), and only while this link still owns the
         *  connection: sendOrDie can stall ~10s, long enough for a #142 supersede, and a dying writer must
         *  neither tear down its healthy successor nor charge it a strike. */
        fun onSent(now: Long): Boolean {
            if (!RelayE2EConnection.silenceDeafTripped(++sentSinceInbound, now - lastInboundAt, windowMs(now))) return false
            sentSinceInbound = 0 // signal once, then let the forced re-handshake take over
            if (!isCurrent()) return false
            strikes = strikesAt(now) + 1 // an expired memory starts over at one
            lastTripAt = now
            return true
        }

        /** A transport frame decrypted at [now]: the daemon demonstrably holds our session. Restarts this link's
         *  silence clock and send count; the strikes stay, and only age out. */
        fun onInbound(now: Long) {
            sentSinceInbound = 0
            lastInboundAt = now
        }
    }
}
