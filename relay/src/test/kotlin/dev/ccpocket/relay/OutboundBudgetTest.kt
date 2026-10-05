package dev.ccpocket.relay

import dev.ccpocket.relay.store.InMemoryRelayStore
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Each socket's backlog is capped (16 MiB, [SlowConsumerTest]), but nothing bounded their SUM: a dozen stalled
 * readers each just under the cap could still exhaust the relay's 256 MiB heap. A relay-wide budget now cuts
 * the largest backlogs first until the total fits again.
 */
class OutboundBudgetTest {
    private val kib = 1024

    private class Probe(budget: OutboundBudget, perSocket: Long = Long.MAX_VALUE) {
        val stall = CompletableDeferred<Unit>()
        val cuts = mutableListOf<Pair<Long, Boolean>>()
        val q = OutboundQueue(perSocket, write = { stall.await() }, onOverflow = { owed, relay -> cuts += owed to relay }, budget = budget)
    }

    @Test fun over_budget_the_largest_backlog_is_cut_and_the_smaller_ones_survive(): Unit = runBlocking {
        val budget = OutboundBudget(10L * kib)
        val big = Probe(budget)
        val small = Probe(budget)
        val pumps = listOf(launch { big.q.pump() }, launch { small.q.pump() })
        repeat(6) { big.q.offer(Frame.Binary(true, ByteArray(kib))) }
        repeat(3) { small.q.offer(Frame.Binary(true, ByteArray(kib))) }
        assertEquals(9L * kib, budget.totalBytes, "within budget: nobody is cut")
        assertTrue(big.cuts.isEmpty() && small.cuts.isEmpty())

        // the offer that crosses the line goes to the SMALL queue — the big one is still the one to go
        small.q.offer(Frame.Binary(true, ByteArray(2 * kib)))
        assertEquals(listOf(6L * kib to true), big.cuts, "the largest backlog is cut, flagged relay-wide")
        assertTrue(small.cuts.isEmpty(), "the smaller backlog survives")
        assertEquals(5L * kib, budget.totalBytes, "the cut queue's bytes are handed back")
        assertFailsWith<ClosedSendChannelException> { big.q.offer(Frame.Text("x")) }
        small.q.offer(Frame.Text("still open"))

        pumps.forEach { it.cancel() }; big.stall.complete(Unit); small.stall.complete(Unit)
    }

    @Test fun the_offering_queue_itself_is_cut_when_it_is_the_largest(): Unit = runBlocking {
        val budget = OutboundBudget(4L * kib)
        val other = Probe(budget)
        val hog = Probe(budget)
        val pumps = listOf(launch { other.q.pump() }, launch { hog.q.pump() })
        other.q.offer(Frame.Binary(true, ByteArray(kib)))
        repeat(3) { hog.q.offer(Frame.Binary(true, ByteArray(kib))) }
        assertFailsWith<ClosedSendChannelException> { hog.q.offer(Frame.Binary(true, ByteArray(kib))) }
        assertEquals(1, hog.cuts.size)
        assertTrue(other.cuts.isEmpty())
        assertEquals(1L * kib, budget.totalBytes)
        pumps.forEach { it.cancel() }; other.stall.complete(Unit); hog.stall.complete(Unit)
    }

    /** Written frames, closed sockets and per-socket overflows all hand their bytes back — the sum never drifts. */
    @Test fun the_relay_wide_total_tracks_writes_closes_and_per_socket_overflows(): Unit = runBlocking {
        val budget = OutboundBudget(1L shl 30)
        val written = CompletableDeferred<Unit>()
        val flowing = OutboundQueue(Long.MAX_VALUE, write = { written.complete(Unit) }, onOverflow = { _, _ -> }, budget = budget)
        val pump = launch { flowing.pump() }
        flowing.offer(Frame.Binary(true, ByteArray(kib)))
        withTimeout(5_000) { written.await(); while (budget.totalBytes != 0L) delay(10) }

        val capped = Probe(budget, perSocket = 2L * kib)
        val cappedPump = launch { capped.q.pump() }
        repeat(2) { capped.q.offer(Frame.Binary(true, ByteArray(kib))) }
        assertFailsWith<ClosedSendChannelException> { capped.q.offer(Frame.Binary(true, ByteArray(kib))) }
        assertEquals(listOf(3L * kib to false), capped.cuts, "the per-socket cap still fires on its own")
        assertEquals(0L, budget.totalBytes)

        val closing = Probe(budget)
        closing.q.offer(Frame.Binary(true, ByteArray(kib)))
        closing.q.close()
        assertEquals(0L, budget.totalBytes)

        pump.cancel(); cappedPump.cancel(); capped.stall.complete(Unit); closing.stall.complete(Unit)
    }

    /** End to end: two stalled phones on different accounts, the relay over its (test-sized) budget — the one
     *  owing more is disconnected, the other stays attached. */
    @Test fun a_relay_over_budget_disconnects_the_biggest_stalled_reader_only() = runBlocking {
        val store = InMemoryRelayStore()
        RelayWsHarness(RelayServer("127.0.0.1", 0, store, outboundBudgetBytes = 12L shl 20)).use { h ->
            val a = RelayWsHarness.seedDevice(store, "acct-a", "AAAAAAAAAAAAAAAAAAAAAA")
            val b = RelayWsHarness.seedDevice(store, "acct-b", "BBBBBBBBBBBBBBBBBBBBBB")
            h.device(reading = false).sendControl(a)
            h.device(reading = false).sendControl(b)
            withTimeout(5_000) {
                while (h.relay.broker.deviceCount("acct-a") == 0 || h.relay.broker.deviceCount("acct-b") == 0) delay(20)
            }
            // A falls 11 MiB behind (under both its own 16 MiB cap and the 12 MiB budget)…
            repeat(11) { h.relay.broker.toDevice("acct-a", a.deviceId, ByteArray(1 shl 20)) }
            delay(500)
            assertEquals(1, h.relay.broker.deviceCount("acct-a"), "within budget: A stays")
            // …then B's backlog pushes the relay over: A, owing the most, is the one cut
            repeat(6) { h.relay.broker.toDevice("acct-b", b.deviceId, ByteArray(1 shl 20)) }
            val gone = withTimeoutOrNull(8_000) { while (h.relay.broker.deviceCount("acct-a") != 0) delay(50) }
            assertNotNull(gone, "the biggest stalled reader is still attached, its backlog held in relay memory")
            assertEquals(1, h.relay.broker.deviceCount("acct-b"), "the smaller backlog must survive")
            assertTrue(h.relay.outboundBudget.totalBytes <= 12L shl 20)
        }
    }
}
