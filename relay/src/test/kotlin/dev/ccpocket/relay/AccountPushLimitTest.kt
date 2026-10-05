package dev.ccpocket.relay

import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.NotifyPush
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.relay.push.NotifyRoute
import dev.ccpocket.relay.push.PushService
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit H2: an ACCOUNT-level NotifyPush (deviceId == null) had no rate limit at all — with urgent=true the
 * presence gate always passes — and every frame launched its own coroutine that queued on the store's single
 * lock (shared with every login) and called out to APNs/FCM with the relay's credentials.
 */
class AccountPushLimitTest {
    private class CountingPush(private val hold: CompletableDeferred<Unit>? = null) : PushService {
        val started = AtomicInteger()
        override suspend fun notify(account: String, title: String, body: String, route: NotifyRoute?) {
            started.incrementAndGet(); hold?.await()
        }
        override suspend fun notifyDevice(account: String, deviceId: String, title: String, body: String, route: NotifyRoute?) {
            started.incrementAndGet(); hold?.await()
        }
    }

    private fun frame(urgent: Boolean = true) =
        PocketJson.encodeToString(Envelope(id = "c", ts = 0, to = Route.RELAY, body = NotifyPush("t", "b", urgent = urgent)))

    @Test fun account_push_flood_from_one_daemon_is_capped() = runBlocking {
        val push = CountingPush()
        val server = RelayServer("127.0.0.1", 0, InMemoryRelayStore(), pushService = push, clock = { 1_000 })
        repeat(1_000) { server.handleDaemonControl("acct", frame()) }
        delay(300)
        assertTrue(push.started.get() <= 60, "${push.started.get()} account pushes went out from one tight loop")
        assertTrue(push.started.get() >= 20, "the cap must leave room for real bursts (${push.started.get()})")
    }

    @Test fun one_account_hitting_its_cap_does_not_silence_another() = runBlocking {
        val push = CountingPush()
        val server = RelayServer("127.0.0.1", 0, InMemoryRelayStore(), pushService = push, clock = { 1_000 })
        repeat(200) { server.handleDaemonControl("noisy", frame()) }
        delay(200)
        val before = push.started.get()
        server.handleDaemonControl("quiet", frame())
        delay(200)
        assertEquals(before + 1, push.started.get())
    }

    @Test fun in_flight_push_work_is_bounded_when_the_provider_stalls(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val push = CountingPush(hold)
        val server = RelayServer("127.0.0.1", 0, InMemoryRelayStore(), pushService = push, clock = { 1_000 })
        // many accounts, one push each: no per-account cap applies, only the global in-flight bound
        repeat(2_000) { server.handleDaemonControl("acct-$it", frame()) }
        delay(500)
        assertTrue(push.started.get() <= 256, "${push.started.get()} push jobs in flight against a stalled provider")
        hold.complete(Unit)
    }
}
