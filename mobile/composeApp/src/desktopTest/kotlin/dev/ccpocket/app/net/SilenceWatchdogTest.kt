package dev.ccpocket.app.net

import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_DEAF_MAX_WINDOW_MS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_DEAF_MIN_SENDS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_DEAF_WINDOW_MS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.silenceDeafTripped
import dev.ccpocket.app.net.RelayE2EConnection.Companion.silenceWindowMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The #298 silence watchdog, widened on evidence (docs/design/SLOW-LINK-RESILIENCE.md 3.1).
 *
 * On a lossy link a big frame in flight is as silent as a zombie link: frames arrive in order and everything
 * behind it waits. The flat 20s window rebuilt such a link every 20s, throwing the in-flight reply away each
 * time. The window now depends on what the link has shown: a link that has carried downlink keeps 20s (the
 * zombie shape the watchdog exists for), a link with no downlink since its handshake gets twice that, and a
 * silence rebuild followed by silence again doubles it, up to 160s, until any frame decrypts.
 *
 * The thresholds stay pure functions on the connection's companion; [SilenceWatchdog] is the state they are
 * applied to, driven here by an explicit clock. The #146 / #298 cases in ReconnectStormTest are untouched.
 */
class SilenceWatchdogTest {

    private val base = SILENCE_DEAF_WINDOW_MS
    private val ceiling = SILENCE_DEAF_MAX_WINDOW_MS

    /** [count] sends spaced one second apart, starting at [from]; true if any of them tripped. */
    private fun SilenceWatchdog.Link.sendBurst(from: Long, count: Int = SILENCE_DEAF_MIN_SENDS): Boolean =
        (0 until count).map { onSent(from + it * 1_000L) }.any { it }

    // ── the window function ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theWindowFollowsTheDesignTable() {
        // the rows of SLOW-LINK-RESILIENCE 3.1
        assertEquals(20_000L, silenceWindowMs(strikes = 0, inboundSeenThisLink = true), "unchanged for a link that heard back")
        assertEquals(40_000L, silenceWindowMs(strikes = 0, inboundSeenThisLink = false))
        assertEquals(80_000L, silenceWindowMs(strikes = 1, inboundSeenThisLink = false))
        assertEquals(160_000L, silenceWindowMs(strikes = 2, inboundSeenThisLink = false))
        assertEquals(160_000L, silenceWindowMs(strikes = 3, inboundSeenThisLink = false), "the ceiling")
        // the same doubling for a link that did hear back, one step behind
        assertEquals(40_000L, silenceWindowMs(strikes = 1, inboundSeenThisLink = true))
        assertEquals(80_000L, silenceWindowMs(strikes = 2, inboundSeenThisLink = true))
        assertEquals(160_000L, silenceWindowMs(strikes = 3, inboundSeenThisLink = true))
        assertEquals(160_000L, silenceWindowMs(strikes = 4, inboundSeenThisLink = true))
    }

    @Test
    fun aRunawayStrikeCountNeitherOverflowsNorPassesTheCeiling() {
        // 62/63/64 are where an uncapped `20_000L shl n` goes negative or wraps back to 20s
        for (strikes in listOf(5, 16, 17, 30, 31, 32, 61, 62, 63, 64, 65, 1_000, Int.MAX_VALUE)) {
            assertEquals(ceiling, silenceWindowMs(strikes, inboundSeenThisLink = true), "strikes=$strikes, heard back")
            assertEquals(ceiling, silenceWindowMs(strikes, inboundSeenThisLink = false), "strikes=$strikes, silent")
        }
        assertEquals(base, silenceWindowMs(strikes = -1, inboundSeenThisLink = true), "never produced, but still total")
    }

    @Test
    fun theTripTakesTheWindowItIsGiven() {
        assertFalse(silenceDeafTripped(SILENCE_DEAF_MIN_SENDS, 2 * base - 1, windowMs = 2 * base))
        assertTrue(silenceDeafTripped(SILENCE_DEAF_MIN_SENDS, 2 * base, windowMs = 2 * base))
        // the send-count half still holds under a wide window: an idle link never trips, however stale
        assertFalse(silenceDeafTripped(SILENCE_DEAF_MIN_SENDS - 1, ceiling * 10, windowMs = ceiling))
    }

    // ── behaviour ────────────────────────────────────────────────────────────────────────────────────

    /** The zombie the watchdog exists for (#298): the daemon talked on this link, then nothing comes back
     *  under sends. Exactly as before — three sends and 20s of silence trip it, once. */
    @Test
    fun aLinkThatHasHeardTheDaemonStillTripsAtTwentySeconds() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        link.onInbound(now = 1_000) // e.g. a poll's answer — the daemon held our session at that point
        assertEquals(base, link.windowMs)

        assertFalse(link.sendBurst(from = 2_000), "three sends inside the window are not silence yet")
        assertFalse(link.onSent(1_000 + base - 1))
        assertTrue(link.onSent(1_000 + base), "20s after the last inbound, with sends outstanding: deaf")
        assertEquals(1, watch.strikes, "the trip is charged")
        assertFalse(link.onSent(1_000 + base + 1), "it signals once; the forced re-handshake takes over")
    }

    /** A link that has not heard back since its handshake gets twice the window: the handshake already proved
     *  the daemon holds our session, so silence there is the first reply still in flight. */
    @Test
    fun aFreshLinkWithNoDownlinkWaitsFortySecondsNotTwenty() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        assertFalse(link.inboundSeen)
        assertEquals(2 * base, link.windowMs)

        assertFalse(link.sendBurst(from = 1_000))
        assertFalse(link.onSent(base), "20s is not enough on a link that has not heard back yet")
        assertFalse(link.onSent(2 * base - 1))
        assertEquals(0, watch.strikes)
        assertTrue(link.onSent(2 * base), "40s is")
        assertEquals(1, watch.strikes)
    }

    /** Strikes belong to the connection, not the link: a rebuild that is met by silence again widens the
     *  next window, and the first frame that decrypts forgives all of it. */
    @Test
    fun aStrikeOutlivesTheRebuildAndAnyDecryptClearsIt() {
        val watch = SilenceWatchdog()
        val first = watch.linkUp(now = 0)
        assertFalse(first.sendBurst(from = 1_000))
        assertTrue(first.onSent(40_000))
        assertEquals(1, watch.strikes)

        val second = watch.linkUp(now = 41_000) // the forced re-handshake
        assertEquals(1, watch.strikes, "the strike survives the reconnect it caused")
        assertEquals(80_000L, second.windowMs)
        assertFalse(second.sendBurst(from = 42_000))
        assertFalse(second.onSent(41_000 + 80_000 - 1))
        assertTrue(second.onSent(41_000 + 80_000))
        assertEquals(2, watch.strikes)

        val third = watch.linkUp(now = 130_000)
        assertEquals(ceiling, third.windowMs, "160s: the ceiling")
        third.onInbound(now = 131_000)
        assertEquals(0, watch.strikes, "one decrypted frame clears every strike")
        assertEquals(base, third.windowMs, "…and a link that heard back is back on the 20s zombie window")
        assertFalse(third.sendBurst(from = 132_000))
        assertTrue(third.onSent(131_000 + base))
        assertEquals(1, watch.strikes)
    }

    /** A decrypt resets the silence clock and the send count of its link, as the inline counters did. */
    @Test
    fun inboundDisarmsTheWindowItIsMeasuredFrom() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        link.onInbound(now = 0)
        assertFalse(link.sendBurst(from = 1_000))
        link.onInbound(now = 15_000) // a reply lands just before the window would have closed
        assertFalse(link.onSent(25_000), "measured from the newest inbound, with a fresh send count")
        assertFalse(link.sendBurst(from = 26_000, count = 2))
        assertFalse(link.onSent(15_000 + base - 1))
        assertTrue(link.onSent(15_000 + base))
    }

    /** #142: a superseded link's writer can finish one stalled send after a newer connect() took over. Its
     *  trip must neither signal (that would tear down the healthy successor) nor be charged as a strike. */
    @Test
    fun aSupersededLinkNeitherSignalsNorChargesAStrike() {
        val watch = SilenceWatchdog()
        var current = true
        val stale = watch.linkUp(now = 0) { current }
        assertFalse(stale.sendBurst(from = 1_000))
        current = false // a newer connect() owns the connection now
        assertFalse(stale.onSent(2 * base))
        assertEquals(0, watch.strikes)
    }
}
