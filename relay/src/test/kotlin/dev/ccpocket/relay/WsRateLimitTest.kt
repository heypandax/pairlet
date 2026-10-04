package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Ping
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit M5: one per-IP connection bucket (10/min) was shared by the daemon and device routes and charged
 * for every socket, authenticated or not. Two daemons on one machine superseding each other (~1 reconnect/s
 * each) used it up within seconds, and the phone on the same Wi-Fi was then refused as `rate_limited`.
 */
class WsRateLimitTest {
    private val deviceId = "FFFFFFFFFFFFFFFFFFFFFF"

    private fun RelayWsHarness.loginAndLeave() {
        val phone = device()
        phone.sendControl(RelayWsHarness.helloFor(deviceId))
        phone.expectControl<Attached>()
        phone.ws.sendClose(1000, "bye").get(5, TimeUnit.SECONDS)
        phone.closed.get(5, TimeUnit.SECONDS)
    }

    /** A socket that is turned away before authenticating (wrong first frame). */
    private fun RelayWsHarness.Peer.junk(): String {
        sendControl(Ping(0))
        return closed.get(5, TimeUnit.SECONDS).reason
    }

    @Test fun authenticated_reconnects_do_not_use_up_the_per_ip_budget() {
        val store = InMemoryRelayStore()
        runBlocking { RelayWsHarness.seedDevice(store, "acct", deviceId) }
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            repeat(15) { h.loginAndLeave() }
        }
    }

    @Test fun daemon_route_churn_does_not_lock_out_devices_on_the_same_ip() {
        val store = InMemoryRelayStore()
        runBlocking { RelayWsHarness.seedDevice(store, "acct", deviceId) }
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            repeat(12) { h.daemon().junk() }
            h.loginAndLeave()
        }
    }

    /** What the bucket is for is unchanged: sockets that never authenticate are still capped per route. */
    @Test fun unauthenticated_churn_is_still_rate_limited() {
        RelayWsHarness(RelayServer("127.0.0.1", 0, InMemoryRelayStore())).use { h ->
            val reasons = (1..12).map { h.device().junk() }
            assertEquals(List(10) { "expected_hello" } + List(2) { "rate_limited" }, reasons)
        }
    }
}
