package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Role
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
 * Audit H1: Ktor's outgoing channel is unbounded, so a socket that stops reading let the relay buffer
 * everything addressed to it (until the ping timeout — and the pings queue behind the backlog). Each socket
 * now has a byte-bounded outbound buffer; going over it ends that socket as a slow consumer.
 */
class SlowConsumerTest {
    private val deviceId = "BBBBBBBBBBBBBBBBBBBBBB"

    @Test fun a_socket_that_stops_reading_is_cut_instead_of_buffered_without_bound() = runBlocking {
        val store = InMemoryRelayStore()
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            val hello = RelayWsHarness.seedDevice(store, "acct", deviceId)
            h.device(reading = false).sendControl(hello)
            withTimeout(5_000) { while (h.relay.broker.deviceCount("acct") == 0) delay(20) }
            // 48 MiB toward a reader that takes none of it; every send must still return at once (the daemon's
            // read loop is the caller and must never stall on one device)
            withTimeout(10_000) { repeat(48) { h.relay.broker.toDevice("acct", deviceId, ByteArray(1 shl 20)) } }
            // cut after the close grace period (3 s), not at the 60 s ping timeout
            val gone = withTimeoutOrNull(8_000) { while (h.relay.broker.deviceCount("acct") != 0) delay(50) }
            assertNotNull(gone, "the stalled socket is still attached, its backlog held in relay memory")
        }
    }

    @Test fun overflow_fires_once_drops_the_backlog_and_refuses_further_frames(): Unit = runBlocking {
        val stall = CompletableDeferred<Unit>()
        var overflows = 0
        val q = OutboundQueue(10 * 1024, write = { stall.await() }, onOverflow = { _, _ -> overflows++ })
        val pump = launch { q.pump() }
        repeat(10) { q.offer(Frame.Binary(true, ByteArray(1024))) } // exactly at the cap: still accepted
        assertFailsWith<ClosedSendChannelException> { q.offer(Frame.Binary(true, ByteArray(1))) }
        assertFailsWith<ClosedSendChannelException> { q.offer(Frame.Text("x")) }
        assertEquals(1, overflows)
        pump.cancel(); stall.complete(Unit)
    }

    /** The cap must not touch a healthy reader: full-size frames (history windows go up to the 4 MiB wire
     *  cap) arrive complete and in order — three of them queued at once, then 32 MiB in total with the sender
     *  staying three frames ahead of the reader — and the socket stays attached. */
    @Test fun a_reading_socket_gets_full_size_frames_intact() = runBlocking {
        val store = InMemoryRelayStore()
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            val hello = RelayWsHarness.seedDevice(store, "acct", deviceId)
            h.relay.broker.attachDaemon(Conn("acct", Role.DAEMON, null, sendText = {}, sendBinary = {}, close = {}))
            val phone = h.device()
            phone.sendControl(hello)
            phone.expectControl<Attached>()
            val frame = 4 * 1024 * 1024 - 64
            val total = 8
            var sent = 0
            repeat(3) { h.relay.broker.toDevice("acct", deviceId, ByteArray(frame) { sent.toByte() }); sent++ }
            repeat(total) { i ->
                val got = phone.next(15_000) as? RelayWsHarness.In.Binary ?: error("frame $i missing")
                assertEquals(frame, got.bytes.size)
                assertTrue(got.bytes.all { it == i.toByte() }, "frame $i out of order or corrupted")
                if (sent < total) { h.relay.broker.toDevice("acct", deviceId, ByteArray(frame) { sent.toByte() }); sent++ }
            }
            assertEquals(1, h.relay.broker.deviceCount("acct"))
        }
    }
}
