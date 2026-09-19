package dev.ccpocket.app.data

import dev.ccpocket.app.net.DepositOutcome
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.push.DefaultPushPlatform
import dev.ccpocket.app.push.PairingKey
import dev.ccpocket.app.push.PairingStatus
import dev.ccpocket.app.push.PushRegistrar
import dev.ccpocket.app.push.PushStateStore
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.push.PushTokens
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.PushRegistrationOutcome
import dev.ccpocket.protocol.PushRegistrationResult
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.Role
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct-LAN push registration (#114 follow-up): a phone whose daemon is always on the same LAN never
 * relay-attaches, so the old "register on the next real relay attach" deferral never fired — the relay
 * kept a dead (or no) token forever. The repository must instead deposit the token through the one-shot
 * relay control dial.
 *
 * The dedup/rollback/5ms-timer machinery these tests used to pin has moved into [PushRegistrar] (and is
 * tested there, on virtual time). What is pinned HERE is the repository's remaining job: pick the right
 * transport, and report what really happened to the frame — a deposit that threw must never read back
 * as "registered".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegisterTest {

    private lateinit var scope: CoroutineScope
    private lateinit var scheduler: TestCoroutineScheduler
    private val key = PairingKey("wss://unit-test", "acct", "dev-1")

    /** One round at the default config: three attempts, 5s and 30s apart. */
    private val ROUND_MS = 35_000L

    /** The coordinator's state must not leak into (or out of) the real on-disk store. */
    private class MapStore : PushStateStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @BeforeTest fun setUp() {
        scheduler = TestCoroutineScheduler()
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        PushTokens.deliverForTest(null)
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        PushTokens.deliverForTest(null)
    }

    private fun registrar() = PushRegistrar(
        scope = scope, platform = DefaultPushPlatform, store = MapStore(),
        foreground = MutableStateFlow(true), jitter = { 0.0 },
    )

    private fun repo(
        reg: PushRegistrar = registrar(),
        dial: suspend (PairedDaemon, RegisterPush, Long) -> DepositOutcome,
    ) = PocketRepository(scope).apply {
        paired.value = PairedDaemon(
            relay = "wss://unit-test", accountId = "acct", daemonPub = "pk", deviceId = "dev-1", credential = "cred",
        )
        useRelay = true
        directLinkUp = { true } // an established direct-LAN link — the transport with no relay control plane
        notificationsOn.value = true // independent of whatever a previous test/user run persisted
        registrarOverride = reg
        pushDial = dial
    }

    private fun stored(f: RegisterPush) =
        DepositOutcome.Acked(PushRegistrationResult(f.requestId!!, PushRegistrationOutcome.STORED))

    private fun attach(r: PocketRepository) = r.receiveControlForTest(Attached(Role.DEVICE, "acct"))

    @Test fun directActiveDepositsTheTokenInsteadOfSkipping() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val calls = CopyOnWriteArrayList<RegisterPush>()
        attach(repo { _, f, _ -> calls += f; stored(f) })

        assertEquals(listOf("tok-A"), calls.map { it.token },
            "a direct-LAN transport must dial the relay, not strand the token")
        assertTrue(calls.single().requestId != null, "the deposit has to ask for a receipt to learn anything")
    }

    @Test fun aConfirmedTokenIsNotReRegisteredButARotatedOneIs() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f, _ -> calls += f; stored(f) }
        attach(r)
        assertEquals(1, calls.size)

        attach(r) // the reconnect storm re-attaching with the same token
        assertEquals(1, calls.size, "a confirmed registration must not redial on every attach")

        PushTokens.deliverForTest(PushToken("ios", "tok-B")) // a real APNs rotation
        assertEquals(listOf("tok-A", "tok-B"), calls.map { it.token })
    }

    @Test fun aFailedDepositIsNeverCountedAsRegistered() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val reg = registrar()
        var fail = true
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(reg) { _, f, _ -> if (fail) error("relay unreachable") else { calls += f; stored(f) } }

        attach(r)
        scheduler.advanceTimeBy(ROUND_MS + 1) // let the round spend its three attempts
        assertTrue(calls.isEmpty())
        assertNotEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status,
            "a deposit that threw must not satisfy anything — that is how a token silently rots")

        fail = false
        r.retryPushRegistration() // what the Settings row's "retry" does
        scheduler.advanceTimeBy(1_000)
        assertEquals(listOf("tok-A"), calls.map { it.token })
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
    }

    @Test fun relayTransportRidesTheLiveControlPlaneNotTheDial() {
        PushTokens.deliverForTest(PushToken("ios", "tok-C"))
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f, _ -> calls += f; stored(f) }
        r.directLinkUp = { false } // the main transport IS the relay — its control plane handles registration
        attach(r)

        assertTrue(calls.isEmpty(), "with a live relay control plane there is nothing to dial")
    }

    @Test fun notificationsOffDepositsAnEmptyTokenOverTheDial() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f, _ ->
            calls += f
            DepositOutcome.Acked(PushRegistrationResult(
                f.requestId!!,
                if (f.token.isEmpty()) PushRegistrationOutcome.CLEARED else PushRegistrationOutcome.STORED,
            ))
        }
        attach(r)
        assertEquals(1, calls.size)

        r.setNotificationsEnabled(false)

        assertEquals("", calls.last().token, "turning notifications off on the LAN must still de-register at the relay")
    }
}
