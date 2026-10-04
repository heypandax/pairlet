package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.relay.ReconnectBackoff.LinkEnd
import kotlin.test.Test
import kotlin.test.assertEquals

/** Audit 2026-10-04 (session-relay M2): the reconnect pacing of [RelayClient.run]. Nominal waits, pre-jitter. */
class ReconnectBackoffTest {

    private val neverAttached = LinkEnd(clean = false, attachedForMs = null)
    private val hoursThenLost = LinkEnd(clean = false, attachedForMs = 3 * 60 * 60 * 1000L)

    @Test
    fun `failed attempts double up to the cap`() {
        val b = ReconnectBackoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { b.next(neverAttached) })
    }

    /** Boot-time failures pushed the backoff up; hours later a heartbeat timeout / network switch throws. That
     *  reconnect must be prompt again — it used to keep doubling (16s, 30s…) because only a clean close reset. */
    @Test
    fun `a stable link that dies abnormally resets the backoff`() {
        val b = ReconnectBackoff()
        repeat(4) { b.next(neverAttached) } // 1, 2, 4, 8
        assertEquals(1_000L, b.next(hoursThenLost))
        assertEquals(2_000L, b.next(neverAttached), "and the next failure starts doubling from the base again")
    }

    /** A link that attaches and drops straight away is a flap, not a recovery — it keeps backing off. */
    @Test
    fun `a link that drops right after attaching keeps backing off`() {
        val b = ReconnectBackoff()
        val flap = LinkEnd(clean = true, attachedForMs = 500)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), List(4) { b.next(flap) })
    }
}
