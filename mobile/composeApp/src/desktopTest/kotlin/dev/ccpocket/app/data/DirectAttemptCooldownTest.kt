package dev.ccpocket.app.data

import dev.ccpocket.app.net.DirectFallbackReason
import dev.ccpocket.app.net.DirectUnreachableException
import dev.ccpocket.app.net.LocalIpv4
import dev.ccpocket.app.net.NetKind
import dev.ccpocket.app.net.NetworkSnapshot
import dev.ccpocket.app.net.parseIpv4
import dev.ccpocket.app.pairing.PairedDaemon
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * #403 at the repository: a failed direct attempt — budget expiry, or the 12 s connect watchdog cutting a hung
 * dial — leaves a cooldown, so the next attempt goes straight to the relay; an eligibility skip leaves none.
 * Observed through the dial itself: [PocketRepository.directConnectForTest] counts direct attempts across a
 * disconnect/reconnect, which keeps the cooldown (ResetInventoryTest: directCooldownUntil is K everywhere).
 */
class DirectAttemptCooldownTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var scope: CoroutineScope
    private val wifi = NetworkSnapshot(listOf(LocalIpv4(NetKind.WIFI, parseIpv4("192.168.1.20")!!, 24)))
    private val cellular = NetworkSnapshot(listOf(LocalIpv4(NetKind.CELLULAR, parseIpv4("10.64.0.2")!!, 30)))

    @BeforeTest fun setUp() {
        scheduler = TestCoroutineScheduler()
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun repo(dial: suspend () -> Unit) = PocketRepository(scope).apply {
        // relay: a closed loopback port, so the relay leg fails fast instead of reaching anything
        paired.value = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "acct-test", daemonPub = "pk",
            deviceId = "dev", credential = "cred", directUrl = "ws://192.168.1.5:8799")
        sameMachineClient = false
        networkSnapshotProvider = { wifi }
        directConnectForTest = { _, _ -> dial() }
    }

    private fun PocketRepository.reconnect() { disconnect(); startRelay() }

    @Test fun budgetExpiryCoolsDownSoTheNextAttemptUsesTheRelay() {
        var dials = 0
        val r = repo { dials++; throw DirectUnreachableException("budget", DirectFallbackReason.BUDGET_EXPIRED) }
        r.startRelay()
        assertEquals(1, dials)
        r.reconnect()
        assertEquals(1, dials, "inside the cooldown the attempt must not dial the same address first again")
    }

    @Test fun watchdogAbortOfAHungDirectDialCoolsDown() {
        var dials = 0
        val r = repo { dials++; awaitCancellation() } // a dial the budget somehow didn't end
        r.startRelay()
        assertEquals(1, dials)
        scheduler.advanceTimeBy(PocketRepository.CONNECT_TIMEOUT_MS + 1)
        scheduler.runCurrent()
        r.reconnect()
        assertEquals(1, dials, "the watchdog's cancel wrote the cooldown — no 'always the direct first' loop")
    }

    @Test fun ineligibleAddressIsSkippedWithoutCooldown() {
        var dials = 0
        val r = repo { dials++; awaitCancellation() }
        r.networkSnapshotProvider = { cellular }
        r.startRelay()
        assertEquals(0, dials, "cellular only: a stored private address is not dialed")
        r.networkSnapshotProvider = { wifi }
        r.reconnect()
        assertEquals(1, dials, "back on the home Wi-Fi the next attempt dials — the skip left no cooldown")
    }

    @Test fun loopbackIsDialedOnlyBySameMachineClient() {
        var dials = 0
        val r = repo { dials++; awaitCancellation() }
        r.paired.value = r.paired.value!!.copy(directUrl = "ws://127.0.0.1:8799")
        r.startRelay()
        assertEquals(0, dials, "a phone never dials its own loopback")
        r.sameMachineClient = true
        r.reconnect()
        assertEquals(1, dials)
    }
}
