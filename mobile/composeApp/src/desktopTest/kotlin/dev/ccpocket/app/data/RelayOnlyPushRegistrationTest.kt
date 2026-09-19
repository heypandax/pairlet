package dev.ccpocket.app.data

import dev.ccpocket.app.net.FakeRelayServer
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.push.DefaultPushPlatform
import dev.ccpocket.app.push.PairingKey
import dev.ccpocket.app.push.PairingStatus
import dev.ccpocket.app.push.PushRegistrar
import dev.ccpocket.app.push.PushStateStore
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.push.PushTokens
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.e2e.E2ECrypto
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #389 (plan B/C; review problem 3), end to end through the repository: with ONLY a relay — the computer
 * off, so the E2E handshake never completes — the coordinator still gets its token stored and CONFIRMED by
 * the relay's receipt, over the one device socket (no helper dial that would supersede it).
 *
 * Virtual time never advances here, so neither the handshake timeout nor any ack timeout can fire: the
 * confirmation can only come from the receipt itself, while the handshake is still pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayOnlyPushRegistrationTest {
    private lateinit var scope: CoroutineScope
    private lateinit var relay: FakeRelayServer

    private class MapStore : PushStateStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = synchronized(map) { map[key] }
        override fun put(key: String, value: String) { synchronized(map) { map[key] = value } }
        override fun remove(key: String) { synchronized(map) { map.remove(key) } }
    }

    @BeforeTest fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(TestCoroutineScheduler()))
        relay = FakeRelayServer()
        PushTokens.deliverForTest(null)
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        relay.close()
        PushTokens.deliverForTest(null)
    }

    @Test fun theTokenIsConfirmedByTheRelayAloneWhileTheHandshakeIsStillPending() {
        PushTokens.deliverForTest(PushToken("fcm", "tok-A"))
        val reg = PushRegistrar(scope, DefaultPushPlatform, MapStore(), foreground = MutableStateFlow(true), jitter = { 0.0 })
        val dials = CopyOnWriteArrayList<RegisterPush>()
        val repo = PocketRepository(scope).apply {
            paired.value = PairedDaemon(relay.url, "acct",
                B64Url.encode(E2ECrypto.generateKeyPair().publicRaw), "dev-1", "cred")
            useRelay = true
            directLinkUp = { false }
            notificationsOn.value = true
            registrarOverride = reg
            pushDial = { _, f, _ -> dials += f; error("a helper dial would supersede the device's own socket") }
            sessionActive.value = true
        }
        repo.retryConnection()

        val key = PairingKey(relay.url, "acct", "dev-1")
        val deadline = System.currentTimeMillis() + 10_000
        while (reg.stateOf(key)?.status != PairingStatus.CONFIRMED && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status, "state=${reg.stateOf(key)}")
        assertEquals(listOf("tok-A"), relay.registrations.map { it.token })
        assertTrue(relay.handshakes.get() >= 1, "the handshake was offered to a daemon that is not there")
        assertEquals(1, relay.connections.get(), "exactly one socket for this device")
        assertTrue(dials.isEmpty(), "no helper dial")
    }
}
