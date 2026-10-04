package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import dev.ccpocket.protocol.e2e.Wire
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit M1: the relay accepted a device frame up to the 4 MiB wire cap and then prepended the routing
 * header (1 + deviceId bytes) toward a daemon whose own cap is the same 4 MiB — so a frame in the last
 * 23 bytes below the cap killed the daemon's whole relay link (every device of the account) instead of
 * failing alone. The device leg must stop exactly where the wrapped frame would cross the daemon's cap.
 */
class RouteFrameCapTest {
    private val deviceId = "CCCCCCCCCCCCCCCCCCCCCC" // 22 chars, the shape of a minted id
    private val header = 1 + deviceId.length

    private fun withRelay(block: suspend (RelayWsHarness, LinkedBlockingQueue<ByteArray>) -> Unit) = runBlocking {
        val store = InMemoryRelayStore()
        RelayWsHarness.seedDevice(store, "acct", deviceId)
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            val toDaemon = LinkedBlockingQueue<ByteArray>()
            h.relay.broker.attachDaemon(Conn("acct", Role.DAEMON, null, sendText = {}, sendBinary = { toDaemon += it }, close = {}))
            block(h, toDaemon)
        }
    }

    @Test fun a_device_frame_that_would_cross_the_daemon_cap_once_wrapped_is_refused_on_the_device_leg() = withRelay { h, toDaemon ->
        val phone = h.device()
        phone.sendControl(RelayWsHarness.helloFor(deviceId))
        phone.expectControl<Attached>()
        // the relay refuses on the frame header, so the client may see its own send cut off mid-frame
        runCatching { phone.sendBinary(ByteArray(WIRE_MAX_FRAME_BYTES.toInt() - 5)) }
        val forwarded = toDaemon.poll(3, TimeUnit.SECONDS)
        assertNull(forwarded?.size, "relay forwarded a ${forwarded?.size} B frame to a daemon that accepts at most $WIRE_MAX_FRAME_BYTES B")
        phone.closed.get(5, TimeUnit.SECONDS) // only the offending device socket ends…
        val gone = withTimeoutOrNull(3_000) { while (h.relay.broker.deviceCount("acct") != 0) delay(20) }
        assertNotNull(gone, "the device socket that sent the oversized frame is still attached")
        assertTrue(h.relay.broker.daemonOnline("acct"), "…the daemon link is untouched")
    }

    @Test fun the_largest_device_frame_that_fits_once_wrapped_still_goes_through() = withRelay { h, toDaemon ->
        val phone = h.device()
        phone.sendControl(RelayWsHarness.helloFor(deviceId))
        phone.expectControl<Attached>()
        val payload = ByteArray(WIRE_MAX_FRAME_BYTES.toInt() - header) { 7 }
        phone.sendBinary(payload)
        val forwarded = assertNotNull(toDaemon.poll(5, TimeUnit.SECONDS), "a frame that fits was dropped")
        assertEquals(WIRE_MAX_FRAME_BYTES.toInt(), forwarded.size)
        assertEquals(deviceId to payload.size, Wire.unwrapDevice(forwarded)!!.let { it.first to it.second.size })
    }
}
