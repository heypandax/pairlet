package dev.ccpocket.app.net

import kotlin.concurrent.Volatile // commonMain: JVM resolves kotlin.jvm.Volatile implicitly, Kotlin/Native (iOS) does not

/**
 * The evidence behind [RelayE2EConnection]'s #298 silence watchdog, driven by an explicit clock so its
 * decisions can be pinned without a socket. The thresholds and the window rule stay in the connection's
 * companion ([RelayE2EConnection.silenceDeafTripped], [RelayE2EConnection.silenceWindowMs]); this class only
 * holds what they are applied to.
 *
 * Two lifetimes, on purpose (docs/design/SLOW-LINK-RESILIENCE.md 3.1):
 *  - [strikes] belongs to the connection OBJECT and survives reconnects — that is its whole point: a silence
 *    rebuild that is followed by silence again says rebuilding did not help, so the next window is longer;
 *  - everything else belongs to ONE handshaken link ([Link], from [linkUp]), so a superseded link (#142)
 *    keeps counting into its own state and never into its successor's.
 *
 * Fed from two coroutines that may run on different threads — the writer ([Link.onSent]) and the reader
 * ([Link.onInbound]) — hence @Volatile. Benignly racy, exactly like the inline counters this replaced: an
 * off-by-one send count, a late reset, or a strike that lands just after a decrypt cleared it costs at most
 * one window that is a little longer or shorter than intended, until the next decrypt clears it again.
 */
internal class SilenceWatchdog {
    /** Silence trips since a transport frame last decrypted — across reconnects. */
    @Volatile var strikes: Int = 0
        private set

    /** A handshake completed at [now]. The new link's silence clock starts there, because the completed
     *  handshake IS inbound proof. [isCurrent] says whether that link still owns the connection (#142). */
    fun linkUp(now: Long, isCurrent: () -> Boolean = { true }): Link = Link(now, isCurrent)

    inner class Link internal constructor(now: Long, private val isCurrent: () -> Boolean) {
        @Volatile private var sentSinceInbound = 0
        @Volatile private var lastInboundAt = now

        /** Whether any transport frame has decrypted on THIS link since its handshake. */
        @Volatile var inboundSeen = false
            private set

        /** How long this link may stay silent under sends, as of now. */
        val windowMs: Long get() = RelayE2EConnection.silenceWindowMs(strikes, inboundSeen)

        /** A sealed frame went out at [now]. True = the link has gone silence-deaf and the caller forces a
         *  re-handshake. Signals once (the send count restarts), and only while this link still owns the
         *  connection: sendOrDie can stall ~10s, long enough for a #142 supersede, and a dying writer must
         *  neither tear down its healthy successor nor charge it a strike. */
        fun onSent(now: Long): Boolean {
            if (!RelayE2EConnection.silenceDeafTripped(++sentSinceInbound, now - lastInboundAt, windowMs)) return false
            sentSinceInbound = 0 // signal once, then let the forced re-handshake take over
            if (!isCurrent()) return false
            strikes++
            return true
        }

        /** A transport frame decrypted at [now]: the daemon demonstrably holds our session. Disarms this
         *  link's window and forgives every strike. */
        fun onInbound(now: Long) {
            sentSinceInbound = 0
            lastInboundAt = now
            inboundSeen = true
            strikes = 0
        }
    }
}
