package dev.ccpocket.app.net

import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_DEAF_MAX_WINDOW_MS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_DEAF_MIN_SENDS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_DEAF_WINDOW_MS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_STRIKE_MEMORY_MS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.SILENCE_YOUNG_LINK_MS
import dev.ccpocket.app.net.RelayE2EConnection.Companion.silenceDeafTripped
import dev.ccpocket.app.net.RelayE2EConnection.Companion.silenceWindowMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The #298 silence watchdog, widened on evidence (docs/design/SLOW-LINK-RESILIENCE.md 3.1, as revised).
 *
 * On a lossy link a big frame in flight is as silent as a zombie link: frames arrive in order and everything
 * behind it waits. The flat 20s window rebuilt such a link every 20s, throwing the in-flight reply away each
 * time. The window now depends on two clocks: a link whose silence began within a minute of its handshake gets
 * twice the window, and every silence trip doubles it again, up to 160s, until five minutes pass without one.
 * Downlink does NOT forgive a trip: the daemon seals a DaemonInfo right after every handshake, so "this link has
 * heard back" is true of every rebuilt link within a round trip and could never let the window grow.
 *
 * The thresholds stay pure functions on the connection's companion; [SilenceWatchdog] is the state they are
 * applied to, driven here by an explicit clock. The #146 / #298 cases in ReconnectStormTest are untouched.
 */
class SilenceWatchdogTest {

    private val base = SILENCE_DEAF_WINDOW_MS
    private val ceiling = SILENCE_DEAF_MAX_WINDOW_MS
    private val young = SILENCE_YOUNG_LINK_MS
    private val memory = SILENCE_STRIKE_MEMORY_MS

    /** [count] sends spaced one second apart, starting at [from]; true if any of them tripped. */
    private fun SilenceWatchdog.Link.sendBurst(from: Long, count: Int = SILENCE_DEAF_MIN_SENDS): Boolean =
        (0 until count).map { onSent(from + it * 1_000L) }.any { it }

    /** Sends once a second from [from] until one trips (or [until] passes); the time it tripped, or null. */
    private fun SilenceWatchdog.Link.sendUntilTrip(from: Long, until: Long): Long? {
        var t = from
        while (t <= until) {
            if (onSent(t)) return t
            t += 1_000L
        }
        return null
    }

    // ── the window function ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theWindowFollowsTheDesignTable() {
        val old = young // 60s and over
        val fresh = young - 1 // under 60s
        // the rows of SLOW-LINK-RESILIENCE 3.1
        assertEquals(20_000L, silenceWindowMs(strikes = 0, linkAgeMs = old), "unchanged for a link long past its handshake")
        assertEquals(40_000L, silenceWindowMs(strikes = 0, linkAgeMs = fresh))
        assertEquals(40_000L, silenceWindowMs(strikes = 1, linkAgeMs = old))
        assertEquals(80_000L, silenceWindowMs(strikes = 1, linkAgeMs = fresh))
        assertEquals(80_000L, silenceWindowMs(strikes = 2, linkAgeMs = old))
        assertEquals(160_000L, silenceWindowMs(strikes = 2, linkAgeMs = fresh), "the ceiling")
        assertEquals(160_000L, silenceWindowMs(strikes = 3, linkAgeMs = fresh))
        // and on either side of the table
        assertEquals(160_000L, silenceWindowMs(strikes = 3, linkAgeMs = old))
        assertEquals(40_000L, silenceWindowMs(strikes = 0, linkAgeMs = 0), "a link that went silent at its handshake is young")
        assertEquals(20_000L, silenceWindowMs(strikes = 0, linkAgeMs = 3_600_000), "an hour-old link is not")
    }

    @Test
    fun aRunawayStrikeCountNeitherOverflowsNorPassesTheCeiling() {
        // 62/63/64 are where an uncapped `20_000L shl n` goes negative or wraps back to 20s
        for (strikes in listOf(5, 16, 17, 30, 31, 32, 61, 62, 63, 64, 65, 1_000, Int.MAX_VALUE)) {
            assertEquals(ceiling, silenceWindowMs(strikes, linkAgeMs = young), "strikes=$strikes, old link")
            assertEquals(ceiling, silenceWindowMs(strikes, linkAgeMs = 0), "strikes=$strikes, young link")
        }
        assertEquals(base, silenceWindowMs(strikes = -1, linkAgeMs = young), "never produced, but still total")
    }

    @Test
    fun theTripTakesTheWindowItIsGiven() {
        assertFalse(silenceDeafTripped(SILENCE_DEAF_MIN_SENDS, 2 * base - 1, windowMs = 2 * base))
        assertTrue(silenceDeafTripped(SILENCE_DEAF_MIN_SENDS, 2 * base, windowMs = 2 * base))
        // the send-count half still holds under a wide window: an idle link never trips, however stale
        assertFalse(silenceDeafTripped(SILENCE_DEAF_MIN_SENDS - 1, ceiling * 10, windowMs = ceiling))
    }

    // ── behaviour ────────────────────────────────────────────────────────────────────────────────────

    /** The zombie the watchdog exists for (#298): a link well past its handshake, the daemon talking on it,
     *  then nothing comes back under sends. Exactly as before — three sends and 20s of silence trip it, once. */
    @Test
    fun aLinkPastItsFirstMinuteStillTripsAtTwentySeconds() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        val lastHeard = young + 1_000 // e.g. a poll's answer, a minute in
        link.onInbound(lastHeard)
        assertEquals(base, link.windowMs(lastHeard))

        assertFalse(link.sendBurst(from = lastHeard + 1_000), "three sends inside the window are not silence yet")
        assertFalse(link.onSent(lastHeard + base - 1))
        assertTrue(link.onSent(lastHeard + base), "20s after the last inbound, with sends outstanding: deaf")
        assertEquals(1, watch.strikesAt(lastHeard + base), "the trip is charged")
        assertFalse(link.onSent(lastHeard + base + 1), "it signals once; the forced re-handshake takes over")
    }

    /** A young link gets twice the window EVEN THOUGH it has already heard back: the daemon's DaemonInfo
     *  lands right after every handshake, and must not make a fresh link look like a zombie. */
    @Test
    fun aYoungLinkThatHeardItsDaemonInfoWaitsFortySecondsNotTwenty() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        link.onInbound(now = 100) // the DaemonInfo the daemon seals right after the handshake
        assertEquals(2 * base, link.windowMs(100))

        assertFalse(link.sendBurst(from = 1_000))
        assertFalse(link.onSent(100 + base), "20s is not enough on a young link")
        assertFalse(link.onSent(100 + 2 * base - 1))
        assertEquals(0, watch.strikesAt(100 + 2 * base - 1))
        assertTrue(link.onSent(100 + 2 * base), "40s is")
        assertEquals(1, watch.strikesAt(100 + 2 * base))
    }

    /** Strikes belong to the connection: a decrypt does not forgive them; five quiet minutes after the last
     *  trip do — and a trip after that starts over at one. */
    @Test
    fun aStrikeSurvivesDownlinkAndAgesOutAfterFiveMinutes() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        val trip = assertNotNull(link.sendUntilTrip(from = 1_000, until = 60_000))
        assertEquals(40_000L, trip, "a young link's first trip: 40s")
        assertEquals(1, watch.strikesAt(trip))

        val next = watch.linkUp(now = trip + 1_000) // the forced re-handshake
        next.onInbound(now = trip + 1_100) // its DaemonInfo …
        next.onInbound(now = trip + 30_000) // … and plenty more downlink
        assertEquals(1, watch.strikesAt(trip + 30_000), "downlink does not clear a strike")
        assertEquals(1, watch.strikesAt(trip + memory - 1), "still remembered just short of five minutes")
        assertEquals(0, watch.strikesAt(trip + memory), "forgotten five minutes after the last trip")

        // a link that goes quiet after the memory lapsed is judged as if it had never tripped
        val later = watch.linkUp(now = trip + memory + 10_000)
        assertEquals(2 * base, later.windowMs(trip + memory + 10_000), "young, no strikes: 40s again")
        val again = assertNotNull(later.sendUntilTrip(from = trip + memory + 11_000, until = trip + memory + 200_000))
        assertEquals(trip + memory + 10_000 + 2 * base, again)
        assertEquals(1, watch.strikesAt(again), "the count starts over at one, not two")
    }

    /** The incident, replayed: rebuild → DaemonInfo lands at once → the big reply never makes it. Three rounds
     *  must widen the window 40, 80, 160 — the flat 20s rebuilt the link every 20s, and "has this link heard
     *  back" (the first draft) never let it grow at all. */
    @Test
    fun theIncidentLoopBacksOffFortyEightyOneSixty() {
        val watch = SilenceWatchdog()
        var upAt = 0L
        val windows = mutableListOf<Long>()
        repeat(3) {
            val link = watch.linkUp(now = upAt)
            val heard = upAt + 100
            link.onInbound(heard) // the DaemonInfo
            val window = link.windowMs(heard) // read before the trip, which charges the NEXT round's strike
            val trip = assertNotNull(link.sendUntilTrip(from = heard + 1_000, until = heard + 2 * ceiling), "round ${it + 1} never tripped")
            assertEquals(window, trip - heard, "round ${it + 1} tripped exactly when its window closed, not before")
            windows += window
            upAt = trip + 1_000 // the forced re-handshake follows the trip
        }
        assertEquals(listOf(40_000L, 80_000L, 160_000L), windows)
        assertEquals(3, watch.strikesAt(upAt))
    }

    /** What is young is the SILENCE: one that began within a minute of the handshake keeps the doubled window
     *  for its whole length, even after the link itself turns a minute old. Read at the moment of each send, a
     *  silence from second 30 would trip at second 60 with 30s elapsed — and a struck young link would never
     *  see its 80s window (the incident replay above would read 40/40/80). */
    @Test
    fun aSilenceThatBeganYoungKeepsItsWindowPastTheFirstMinute() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        link.onInbound(now = 30_000) // the last thing heard, half a minute in
        assertFalse(link.sendBurst(from = 31_000))
        assertFalse(link.onSent(young + 1_000), "the link is now past a minute, but its silence began young")
        assertFalse(link.onSent(30_000 + 2 * base - 1))
        val trip = 30_000 + 2 * base
        assertTrue(link.onSent(trip))

        // on the rebuilt link, a silence that begins after its first minute is an old link's: the 20s base,
        // doubled once for the strike just earned
        val next = watch.linkUp(now = trip + 1_000)
        assertEquals(4 * base, next.windowMs(trip + 1_000), "young, one strike: 80s")
        next.onInbound(now = trip + 1_000 + young)
        assertEquals(2 * base, next.windowMs(trip + 1_000 + young), "old, one strike: 40s")
    }

    /** A decrypt restarts its link's silence clock and send count, as the inline counters did. */
    @Test
    fun inboundRestartsTheWindowItIsMeasuredFrom() {
        val watch = SilenceWatchdog()
        val link = watch.linkUp(now = 0)
        link.onInbound(now = young) // an old link from here on: 20s
        assertFalse(link.sendBurst(from = young + 1_000))
        link.onInbound(now = young + 15_000) // a reply lands just before the window would have closed
        assertFalse(link.onSent(young + 25_000), "measured from the newest inbound, with a fresh send count")
        assertFalse(link.sendBurst(from = young + 26_000, count = 2))
        assertFalse(link.onSent(young + 15_000 + base - 1))
        assertTrue(link.onSent(young + 15_000 + base))
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
        assertEquals(0, watch.strikesAt(2 * base))
    }
}
