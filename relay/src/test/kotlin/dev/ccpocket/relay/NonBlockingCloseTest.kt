package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Role
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Audit M3: supersede and revoke used to close the OLD socket inline. `close()` is Close + flush, and the
 * flush waits for every frame already queued ahead of it — on a half-open socket with a backlog that is
 * "until the ping timeout" (or longer), so the NEW socket's Attached, or the daemon's whole read loop
 * (revoke runs inside it), waited with it.
 */
class NonBlockingCloseTest {
    private val deviceId = "AAAAAAAAAAAAAAAAAAAAAA" // 22 chars, the shape of a minted id

    /** A device socket that stopped reading, with enough queued toward it that its writer cannot drain. */
    private suspend fun stalledDevice(h: RelayWsHarness, store: InMemoryRelayStore): RelayWsHarness.Peer {
        val hello = RelayWsHarness.seedDevice(store, "acct", deviceId)
        val stuck = h.device(reading = false)
        stuck.sendControl(hello)
        withTimeout(5_000) { while (h.relay.broker.deviceCount("acct") == 0) delay(20) }
        // 12 MiB: well past loopback socket buffers + the writer's channel, still below the slow-consumer cap
        repeat(12) { h.relay.broker.toDevice("acct", deviceId, ByteArray(1 shl 20)) }
        delay(500)
        return stuck
    }

    @Test fun revoke_does_not_wait_for_a_stalled_socket_to_flush(): Unit = runBlocking {
        val store = InMemoryRelayStore()
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            stalledDevice(h, store)
            val returned = withTimeoutOrNull(3_000) { h.relay.broker.closeDevice("acct", deviceId) }
            assertNotNull(returned, "closeDevice (run on the daemon's read loop) blocked on the stalled socket's flush")
            // …and the revoked socket still goes: cut once its clean close has had its grace period
            val gone = withTimeoutOrNull(8_000) { while (h.relay.broker.deviceCount("acct") != 0) delay(50) }
            assertNotNull(gone, "the revoked, stalled socket was never cut")
        }
    }

    @Test fun superseding_socket_is_attached_without_waiting_for_the_old_one(): Unit = runBlocking {
        val store = InMemoryRelayStore()
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            stalledDevice(h, store)
            h.relay.broker.attachDaemon(Conn("acct", Role.DAEMON, null, sendText = {}, sendBinary = {}, close = {}))
            val fresh = h.device()
            fresh.sendControl(RelayWsHarness.helloFor(deviceId)) // same device, new socket
            // the phone's own handshake timeout is 15 s; the relay must answer well inside it
            fresh.expectControl<Attached>(timeoutMs = 3_000)
        }
    }
}
