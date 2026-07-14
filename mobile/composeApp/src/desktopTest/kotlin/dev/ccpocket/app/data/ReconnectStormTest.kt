package dev.ccpocket.app.data

import dev.ccpocket.app.net.dedupeReconnectBacklog
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The 07-13 disconnect-storm batch, phone side:
 *  - #142 the old socket must be truly retired before a new one dials ([retireJobBounded]);
 *  - #143 reconnect triggers coalesce while an attempt is in flight ([PocketRepository.shouldCoalesceReconnect])
 *    and the cross-reconnect outbox collapses duplicated idempotent requests ([dedupeReconnectBacklog]);
 *  - #144 the retry-backoff ladder resets only after the link stays up stable, not on a single healthy
 *    round-trip;
 *  - #145 a PeerPresence(false→true) edge on a HEALTHY transport re-syncs over the live socket instead of
 *    tearing it down, and escalates to a real reconnect only when the probe gets no answer.
 */
class ReconnectStormTest {

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel() // kills leaked list-wait / retry / probe jobs a test armed
    }

    private fun repo() = PocketRepository(scope).apply {
        paired.value = PairedDaemon(
            // a closed loopback port: an escalation test's real dial fails instantly instead of hanging
            relay = "wss://127.0.0.1:9", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        useRelay = true
        sessionActive.value = true
    }

    private suspend fun awaitCue(what: String, cond: () -> Boolean) {
        repeat(150) { if (cond()) return; delay(20) }
        fail("$what did not surface within the 3s ceiling")
    }

    // ── #143: outbox backlog dedup ───────────────────────────────────────────────────────────────

    @Test
    fun backlogDedupKeepsOnlyTheNewestIdempotentRequestAndAllTheRest() {
        val frames = listOf<Frame>(
            ListDirectories(),
            OpenSession("/proj/a", "sid-1"),
            SendPrompt("c1", "must survive"),
            ListDirectories(),
            ListSessions("/proj/a"),
            OpenSession("/proj/a", "sid-1"),   // same convo — only this newer one survives
            OpenSession("/proj/b", null),      // different key — untouched
            ListSessions("/proj/a"),
        )
        val out = dedupeReconnectBacklog(frames)
        assertEquals(1, out.count { it is ListDirectories }, "a trigger burst flushes ONE list request")
        assertEquals(1, out.count { it is OpenSession && (it as OpenSession).workdir == "/proj/a" })
        assertEquals(1, out.count { it is ListSessions })
        assertEquals(1, out.count { it is OpenSession && (it as OpenSession).workdir == "/proj/b" })
        assertEquals(1, out.count { it is SendPrompt }, "non-idempotent frames are never dropped")
        // relative order preserved: the prompt still precedes the (surviving, newest) list request
        assertTrue(out.indexOfFirst { it is SendPrompt } < out.indexOfFirst { it is ListDirectories })
    }

    // ── #143: trigger coalescing (pure rule) ─────────────────────────────────────────────────────

    @Test
    fun reconnectTriggersCoalesceOnlyIntoARecentInFlightAttempt() {
        val c = PocketRepository.Companion
        assertTrue(c.shouldCoalesceReconnect(force = false, reconnect = true, attemptInFlight = true, sinceLastLaunchMs = 400))
        // a healthy connection's connectJob stays active for its whole life — an OLD launch must not absorb a deliberate teardown
        assertFalse(c.shouldCoalesceReconnect(force = false, reconnect = true, attemptInFlight = true, sinceLastLaunchMs = PocketRepository.TRANSPORT_COALESCE_MS))
        assertFalse(c.shouldCoalesceReconnect(force = false, reconnect = true, attemptInFlight = false, sinceLastLaunchMs = 400), "no attempt in flight — nothing to merge into")
        assertFalse(c.shouldCoalesceReconnect(force = true, reconnect = true, attemptInFlight = true, sinceLastLaunchMs = 0), "forced teardown (deaf-link retry, manual) always wins")
        assertFalse(c.shouldCoalesceReconnect(force = false, reconnect = false, attemptInFlight = true, sinceLastLaunchMs = 0), "a fresh explicit connect never coalesces")
    }

    // ── #142: bounded retire of the previous socket ──────────────────────────────────────────────

    @Test
    fun retireWaitsForTheOldConnectionToActuallyFinishClosing() = runBlocking {
        var closed = false
        val ext = CoroutineScope(Job() + Dispatchers.Default)
        val prev = ext.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(120) } // a slow (but not wedged) socket close
                closed = true
            }
        }
        awaitCue("previous job start") { prev.isActive }
        retireJobBounded(prev, 5_000)
        assertTrue(closed, "retire must not return before the previous connection finished closing — cancel() alone was the #142 overlap")
    }

    @Test
    fun retireIsBoundedSoAWedgedCloseCannotStallReconnecting() = runBlocking {
        val ext = CoroutineScope(Job() + Dispatchers.Default)
        val prev = ext.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(30_000) } // a wedged close (network-switch zombie)
            }
        }
        awaitCue("previous job start") { prev.isActive }
        val t0 = System.currentTimeMillis()
        retireJobBounded(prev, 150)
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "retire must give up at the bound, not wait out the zombie")
        ext.cancel()
    }

    // ── #144: the backoff ladder resets on stability, not on one healthy round-trip ─────────────

    @Test
    fun aSingleHealthyRoundTripNoLongerResetsTheBackoffLadder() = runBlocking {
        val r = repo()
        r.stableLinkResetMs = 150
        r.retryAttempts = 4 // mid-flap: the ladder has climbed
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-test"))
        r.receiveForTest(Directories(emptyList()))
        assertEquals(4, r.retryAttempts, "one Directories round-trip must NOT reset the ladder (#144 — that kept a flapping link at 1s forever)")

        awaitCue("the stability reset") { r.retryAttempts == 0 } // the link staying up past the window DOES reset it
    }

    // ── #145: presence edge on a healthy transport re-syncs instead of tearing down ─────────────

    @Test
    fun aDaemonComebackOnAHealthyLinkResyncsWithoutRebuildingTheSocket() = runBlocking {
        val r = repo()
        r.linkHealthOverride = { true }
        r.presenceProbeMs = 200
        val sent = mutableListOf<Frame>()
        r.onSendForTest = { sent.add(it) }
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-test"))
        r.receiveForTest(Directories(emptyList()))
        val launches = r.transportLaunches

        r.receiveControlForTest(PeerPresence(false))
        r.receiveControlForTest(PeerPresence(true))

        assertEquals(launches, r.transportLaunches, "a healthy transport must NOT be torn down on the presence edge (#145)")
        assertTrue(sent.any { it is ListDirectories }, "the healthy path re-syncs the page over the live socket")

        r.receiveForTest(Directories(emptyList())) // the computer answered — the probe must stand down
        delay(500)
        assertEquals(launches, r.transportLaunches, "an answered probe never escalates")
    }

    @Test
    fun aSilentDaemonComebackEscalatesToExactlyOneFullReconnect() = runBlocking {
        val r = repo()
        r.linkHealthOverride = { true }
        r.presenceProbeMs = 100
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-test"))
        r.receiveForTest(Directories(emptyList()))
        val launches = r.transportLaunches

        r.receiveControlForTest(PeerPresence(false))
        r.receiveControlForTest(PeerPresence(true))
        // no Directories reply: the daemon really restarted (our E2E session died with it) — the probe
        // must escalate to a full re-handshake, or the phone would sit deaf on a "healthy" socket
        awaitCue("the probe escalation") { r.transportLaunches > launches }
    }

    @Test
    fun aDaemonComebackOnADeadTransportStillReconnectsImmediately() = runBlocking {
        val r = repo()
        r.linkHealthOverride = { false }
        val launches = r.transportLaunches

        r.receiveControlForTest(PeerPresence(false))
        r.receiveControlForTest(PeerPresence(true))

        assertEquals(launches + 1, r.transportLaunches, "an unhealthy transport takes the full reconnect path, as before")
    }
}
