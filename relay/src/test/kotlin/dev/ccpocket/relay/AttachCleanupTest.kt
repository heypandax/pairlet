package dev.ccpocket.relay

import dev.ccpocket.protocol.Challenge
import dev.ccpocket.relay.store.Device
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.RelayStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Audit M4: a socket was registered in the broker BEFORE the try/finally that unregisters it, so anything
 * that threw in between (the Attached send on a socket the peer already left, a store read failing) left a
 * ghost connection behind: a ghost device kept the daemon from ever hearing "phone offline" and held one of
 * the account's live-device slots; a ghost daemon kept every device believing the computer was online.
 */
class AttachCleanupTest {

    @Test fun daemon_whose_attach_replay_throws_is_unregistered() = runBlocking {
        val base = InMemoryRelayStore()
        val store = object : RelayStore by base {
            override suspend fun devicesForAccount(accountId: String): List<Device> = throw SQLException("disk I/O error")
        }
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            val keys = RelayWsHarness.DaemonKeys()
            val d = h.daemon()
            d.sendControl(keys.hello)
            d.sendControl(keys.auth(d.expectControl<Challenge>()))
            d.closed.get(5, TimeUnit.SECONDS) // attached, then the handler died on the replay
            val gone = withTimeoutOrNull(3_000) { while (h.relay.broker.daemonOnline(keys.accountId)) delay(20) }
            assertNotNull(gone, "the dead daemon socket is still the account's daemon")
        }
    }

    @Test fun device_that_leaves_before_its_attached_is_sent_is_unregistered() = runBlocking {
        val base = InMemoryRelayStore()
        val deviceId = "EEEEEEEEEEEEEEEEEEEEEE"
        val hello = RelayWsHarness.seedDevice(base, "acct", deviceId)
        val reads = AtomicInteger()
        val pastAuth = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        // getDevice #1 is the credential check, #2 the headless lookup right before attach: hold #2 until
        // the client has gone, so everything after the attach runs against a socket that is already closing.
        // (A guard: with Ktor 3.5.2 this exact interleaving did not leave a ghost even before the fix, because
        // the Attached send still lands in the unbounded outgoing buffer. The daemon test above is the repro.)
        val store = object : RelayStore by base {
            override suspend fun getDevice(deviceId: String): Device? {
                if (reads.incrementAndGet() == 2) { pastAuth.complete(Unit); resume.await() }
                return base.getDevice(deviceId)
            }
        }
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            val phone = h.device()
            phone.sendControl(hello)
            pastAuth.await()
            phone.ws.sendClose(1000, "gone").get(5, TimeUnit.SECONDS)
            delay(500)
            resume.complete(Unit)
            delay(1_000) // let the handler run its attach (an early poll would see the not-yet-attached 0)
            val gone = withTimeoutOrNull(3_000) { while (h.relay.broker.deviceCount("acct") != 0) delay(20) }
            assertNotNull(gone, "a ghost device socket is still registered (${h.relay.broker.deviceCount("acct")})")
            assertEquals(0, h.relay.broker.interactiveDeviceCount("acct"))
        }
    }
}
