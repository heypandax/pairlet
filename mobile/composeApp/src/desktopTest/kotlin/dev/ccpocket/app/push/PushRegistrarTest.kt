package dev.ccpocket.app.push

import dev.ccpocket.observability.*
import dev.ccpocket.protocol.PushRegistrationOutcome
import dev.ccpocket.protocol.PushRegistrationResult
import dev.ccpocket.protocol.RegisterPush
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The push-registration coordinator: does a token that the user asked for actually END UP at the relay,
 * and does it get there again after every way that can fail?
 *
 * The behaviour under test only exists because the old path had none of it — enqueueing a `RegisterPush`
 * WAS the definition of "registered", so a relay that stored nothing, a frame that never left the
 * outbox, a rotated token and a revoked permission were all invisible. Every case below is one of those
 * silences turned into a state that converges. All timing is virtual: nothing here sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrarTest {

    private val key = PairingKey("wss://r", "acct", "dev-1")

    // ── fakes ───────────────────────────────────────────────────────────────────────────────────────

    private class FakeStore(val map: MutableMap<String, String> = mutableMapOf()) : PushStateStore {
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class FakePlatform : PushPlatform {
        val tokenState = MutableStateFlow<PushToken?>(null)
        val failureFlow = MutableSharedFlow<PushFailureEvent>(extraBufferCapacity = 8)
        override val token = tokenState
        override val failures = failureFlow
        /** What the OS hands back when asked; null = the callback never comes. */
        var autoToken: PushToken? = PushToken("ios", "tok-A")
        var authorization = PushAuthorization.AUTHORIZED
        val prompts = mutableListOf<Boolean>()
        /** The ask number of the latest [requestToken] — what a refusal of that ask is tagged with. */
        var lastRequest = 0L
        var autoFailure: PushRegistrationFailure? = null
        override fun requestToken(prompt: Boolean, request: Long) {
            lastRequest = request
            prompts += prompt
            autoFailure?.let { failureFlow.tryEmit(PushFailureEvent(request, it)) }
            autoToken?.let { tokenState.value = it }
        }
        override fun readAuthorization(cb: (PushAuthorization) -> Unit) = cb(authorization)
    }

    private class FakeLink(override val key: PairingKey, private val clock: () -> Long) : PairingLink {
        override val desiredEnabled = MutableStateFlow(true)
        override val connected = MutableStateFlow(true)
        val submitted = mutableListOf<RegisterPush>()
        val submittedAt = mutableListOf<Long>()
        var inFlight = 0
        var peakInFlight = 0
        /** Default: a relay that stores everything it is asked to. */
        var responder: suspend (RegisterPush) -> SubmitOutcome = { stored(it) }
        override suspend fun submit(frame: RegisterPush, ackTimeoutMs: Long): SubmitOutcome {
            submitted += frame
            submittedAt += clock()
            inFlight++
            peakInFlight = maxOf(peakInFlight, inFlight)
            try {
                return responder(frame)
            } finally {
                inFlight--
            }
        }
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────────

    private val scopes = mutableListOf<CoroutineScope>()

    /**
     * Every test body runs in here so the coordinator's scope dies INSIDE the test coroutine.
     *
     * A link that keeps failing is, by design, an endless ladder of scheduled retries — `runTest`'s own
     * end-of-test drain would spin on it forever in virtual time and the test would simply never return.
     * Tearing down afterwards (`@AfterTest`) is too late for exactly that reason.
     */
    private fun pushTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
            scopes.clear()
            Diagnostics.install(null)
        }
    }

    /**
     * A coordinator on this test's virtual clock.
     *
     * Deliberately NOT `backgroundScope`: coroutines launched there are not driven by
     * `advanceUntilIdle()` in this coroutines version, so the whole machine would sit unstarted and every
     * assertion below would pass vacuously on an empty submission list.
     */
    private fun TestScope.registrar(
        platform: FakePlatform,
        store: FakeStore,
        foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
        jitter: Double = 0.0,
        config: PushRegistrar.Config = PushRegistrar.Config(),
    ): PushRegistrar {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopes += scope
        return PushRegistrar(
            scope = scope, platform = platform, store = store, foreground = foreground,
            epochMillis = { testScheduler.currentTime }, jitter = { jitter }, config = config,
        )
    }

    private companion object {
        fun stored(f: RegisterPush) =
            SubmitOutcome.Acked(PushRegistrationResult(f.requestId!!, PushRegistrationOutcome.STORED))
        fun cleared(f: RegisterPush) =
            SubmitOutcome.Acked(PushRegistrationResult(f.requestId!!, PushRegistrationOutcome.CLEARED))
        fun verdict(f: RegisterPush) =
            if (f.token.isEmpty()) cleared(f) else stored(f)
        const val MIN = 60_000L
        /** One full round at the default config: three attempts, 5s and 30s apart. */
        const val ROUND_MS = 35_000L
    }

    private fun capturePush(): MutableList<DiagnosticRecord> {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.IOS, Environment.STAGING, "ios@test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 0))
        return records
    }

    @Test fun failedTokenRoundPreservesCauseAndCannotLookLikeRelayFailure() = pushTest {
        val records = capturePush()
        val platform = FakePlatform().apply { autoToken = null; autoFailure = PushRegistrationFailure.NETWORK }
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        reg.attach(link)
        advanceTimeBy(ROUND_MS + 1)
        assertTrue(link.submitted.isEmpty())
        assertTrue(records.any { it.stage == Stage.PUSH_TOKEN && it.code == ErrorCode.NETWORK_FAILED && it.attempt == 1 })
        val exhausted = records.single { it.code == ErrorCode.RETRY_EXHAUSTED }
        assertEquals(Stage.PUSH_TOKEN, exhausted.stage)
        assertEquals(3, exhausted.attempt)
        assertTrue(exhausted.steps.any { it.code == ErrorCode.NETWORK_FAILED })
    }

    @Test fun relayTimeoutAndSendFailureAreSeparateFromTokenFailure() = pushTest {
        val records = capturePush()
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        var count = 0
        link.responder = { SubmitOutcome.Failed(if (++count < 3) FailReason.ACK_TIMEOUT else FailReason.SEND_FAILED) }
        reg.attach(link)
        advanceTimeBy(ROUND_MS + 1)
        assertTrue(records.any { it.stage == Stage.PUSH_REGISTER && it.code == ErrorCode.ACK_TIMEOUT })
        assertTrue(records.any { it.stage == Stage.PUSH_REGISTER && it.code == ErrorCode.SEND_FAILED })
        val exhausted = records.single { it.code == ErrorCode.RETRY_EXHAUSTED }
        assertEquals(Stage.PUSH_REGISTER, exhausted.stage)
        assertEquals(3, exhausted.attempt)
        assertTrue(exhausted.steps.any { it.code == ErrorCode.TOKEN_RECEIVED })
    }

    @Test fun storedAndClearedHaveUnsampledEvidenceWithoutTokenOrIdentity() = pushTest {
        val records = capturePush()
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }.apply { responder = { verdict(it) } }
        reg.attach(link)
        advanceUntilIdle()
        link.desiredEnabled.value = false
        advanceUntilIdle()
        assertTrue(records.any { it.stage == Stage.PUSH_REGISTER && it.code == ErrorCode.STORED })
        assertTrue(records.any { it.stage == Stage.PUSH_CLEAR && it.code == ErrorCode.CLEARED })
        val history = Diagnostics.pushHistoryText()
        for (secret in listOf("tok-A", "acct", "dev-1", "wss://r")) assertTrue(secret !in history)
    }

    // ── 1. the happy path ───────────────────────────────────────────────────────────────────────────

    @Test fun a_cold_start_asks_for_a_token_registers_it_and_records_the_confirmation() = pushTest {
        val platform = FakePlatform()
        val store = FakeStore()
        val reg = registrar(platform, store)
        val link = FakeLink(key) { testScheduler.currentTime }

        reg.attach(link)
        advanceUntilIdle()

        assertEquals(listOf("tok-A"), link.submitted.map { it.token })
        assertTrue(link.submitted.single().requestId != null, "a registration that wants a receipt must carry an id")
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
        assertNotNull(reg.stateOf(key)?.lastConfirmedAt, "the confirmation time is what the 24h re-proof reads")
        assertTrue(PushRegistrar.pairingStoreKey(key) in store.map, "the state must survive a cold start")
        assertEquals(PushUiStatus.ENABLED, reg.status.value)
    }

    // ── 2. attempts, waits, cooldowns ───────────────────────────────────────────────────────────────

    @Test fun a_round_retries_twice_in_place_before_backing_off() = pushTest {
        val platform = FakePlatform()
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        var n = 0
        link.responder = { f -> if (++n < 3) SubmitOutcome.Failed(FailReason.SEND_FAILED) else stored(f) }

        reg.attach(link)
        advanceUntilIdle()

        assertEquals(3, link.submitted.size)
        assertEquals(5_000L, link.submittedAt[1] - link.submittedAt[0], "first in-round wait")
        assertEquals(30_000L, link.submittedAt[2] - link.submittedAt[1], "second in-round wait")
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
    }

    @Test fun a_spent_round_backs_off_and_the_cooldown_grows_to_a_cap() = pushTest {
        val platform = FakePlatform()
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { SubmitOutcome.Failed(FailReason.SEND_FAILED) }

        reg.attach(link)
        // NOTE: never advanceUntilIdle() against a link that always fails — the retry ladder is by design
        // an endless chain of scheduled work, so "until idle" never arrives in virtual time
        advanceTimeBy(ROUND_MS + 1)
        val first = assertNotNull(reg.stateOf(key))
        assertEquals(PairingStatus.RETRY_WAIT, first.status)
        assertEquals(1, first.failedRounds)
        assertEquals(link.submittedAt.last() + 5 * MIN, first.nextRetryAt)
        assertEquals(PushUiStatus.FAILED, reg.status.value)

        // the cooldowns expire on their own — no external trigger, and no polling in between
        advanceTimeBy(150 * MIN)
        assertTrue(link.submitted.size >= 15, "rounds of three attempts, one per cooldown: ${link.submitted.size}")
        // 5m → 15m → 60m, then 60m forever: a link broken for hours is not worth hammering
        assertEquals(5 * MIN, link.submittedAt[3] - link.submittedAt[2], "cooldown after round 1")
        assertEquals(15 * MIN, link.submittedAt[6] - link.submittedAt[5], "cooldown after round 2")
        assertEquals(60 * MIN, link.submittedAt[9] - link.submittedAt[8], "cooldown after round 3")
        assertEquals(60 * MIN, link.submittedAt[12] - link.submittedAt[11], "and capped there")
    }

    @Test fun the_cooldown_carries_up_to_ten_percent_of_jitter() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore(), jitter = 1.0)
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { SubmitOutcome.Failed(FailReason.SEND_FAILED) }

        reg.attach(link)
        advanceTimeBy(ROUND_MS + 1)

        // every phone on a recovering network would otherwise retry at the same instant
        assertEquals(link.submittedAt.last() + 5 * MIN + 30_000L, reg.stateOf(key)?.nextRetryAt)
    }

    // ── 3. who may interrupt a cooldown ─────────────────────────────────────────────────────────────

    @Test fun ordinary_triggers_respect_the_cooldown_but_an_explicit_retry_does_not() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { SubmitOutcome.Failed(FailReason.SEND_FAILED) }
        reg.attach(link)
        advanceTimeBy(ROUND_MS + 1)
        val spent = link.submitted.size

        listOf(TriggerReason.FOREGROUND, TriggerReason.CONNECTED, TriggerReason.PROMPT_SENT).forEach { reg.trigger(it) }
        advanceTimeBy(1_000)
        assertEquals(spent, link.submitted.size, "a backoff that any reconnect could cancel is not a backoff")

        link.responder = { f -> stored(f) }
        reg.trigger(TriggerReason.USER_RETRY) // the person is looking at the Settings row right now
        advanceTimeBy(1_000)
        assertTrue(link.submitted.size > spent)
        advanceUntilIdle()
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
        assertEquals(0, reg.stateOf(key)?.failedRounds, "an explicit retry starts from a clean budget")
    }

    // ── 4. a token that changes under an in-flight round ────────────────────────────────────────────

    @Test fun a_token_rotation_supersedes_the_in_flight_registration_and_reconfirms() = pushTest {
        val platform = FakePlatform()
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        val hold = CompletableDeferred<Unit>()
        link.responder = { f ->
            if (f.token == "tok-A") { hold.await(); stored(f) } else stored(f)
        }

        reg.attach(link)
        advanceUntilIdle()
        assertEquals(listOf("tok-A"), link.submitted.map { it.token })

        platform.tokenState.value = PushToken("ios", "tok-B") // APNs rotated mid-flight
        advanceUntilIdle()
        hold.complete(Unit) // A's receipt finally arrives — for a token nobody wants any more
        advanceUntilIdle()

        assertEquals("tok-B", link.submitted.last().token, "the surviving registration is the CURRENT token")
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
        // A may already have hit the relay, so B is proved twice before it counts
        assertEquals(2, link.submitted.count { it.token == "tok-B" },
            "a superseded write of unknown fate must be re-proved, not assumed harmless")
        assertEquals(false, reg.stateOf(key)?.staleWrittenPossible)
    }

    // ── 5. turning notifications off mid-flight ─────────────────────────────────────────────────────

    @Test fun switching_off_while_registering_converges_on_a_cleared_row() = pushTest {
        val platform = FakePlatform()
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        val hold = CompletableDeferred<Unit>()
        link.responder = { f -> if (f.token == "tok-A") { hold.await(); stored(f) } else verdict(f) }

        reg.attach(link)
        advanceUntilIdle()
        link.desiredEnabled.value = false // the user flipped the Settings switch
        advanceUntilIdle()
        hold.complete(Unit)
        advanceUntilIdle()

        assertEquals("", link.submitted.last().token, "off must reach the relay, or it keeps a live token")
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)

        platform.tokenState.value = PushToken("ios", "tok-late") // a refresh the OS pushes afterwards
        advanceUntilIdle()
        assertTrue(link.submitted.none { it.token == "tok-late" },
            "a late platform callback must not switch notifications back on behind the user")
        assertEquals(PushUiStatus.OFF, reg.status.value)
    }

    // ── 6. an old relay ─────────────────────────────────────────────────────────────────────────────

    @Test fun an_unacknowledging_relay_parks_the_pairing_until_it_can_answer() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { SubmitOutcome.SentLegacy }

        reg.attach(link)
        advanceUntilIdle()
        assertEquals(PairingStatus.LEGACY_UNCONFIRMED, reg.stateOf(key)?.status)
        assertEquals(PushUiStatus.UNCONFIRMED_LEGACY, reg.status.value)
        val sent = link.submitted.size

        // an old relay will never answer: retrying it on a timer would be a permanent, pointless loop
        advanceTimeBy(30 * MIN)
        assertEquals(sent, link.submitted.size)

        link.responder = { f -> stored(f) } // the relay was upgraded; a reconnect finds out
        reg.trigger(TriggerReason.CONNECTED)
        advanceUntilIdle()
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
    }

    // ── 7. verdicts that no retry can fix ───────────────────────────────────────────────────────────

    @Test fun a_rejected_identity_stops_instead_of_retrying_forever() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { f ->
            SubmitOutcome.Acked(PushRegistrationResult(f.requestId!!, PushRegistrationOutcome.REJECTED, "forbidden"))
        }

        reg.attach(link)
        advanceUntilIdle()

        assertEquals(1, link.submitted.size, "the relay said this identity may never register")
        assertEquals(PairingStatus.BLOCKED, reg.stateOf(key)?.status)
        assertEquals("forbidden", reg.stateOf(key)?.failure)
        advanceTimeBy(2 * 60 * MIN)
        assertEquals(1, link.submitted.size)
    }

    @Test fun a_dead_credential_stops_the_same_way() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { SubmitOutcome.Failed(FailReason.AUTH_REJECTED) }

        reg.attach(link)
        advanceUntilIdle()

        assertEquals(1, link.submitted.size)
        assertEquals(PairingStatus.BLOCKED, reg.stateOf(key)?.status)
    }

    @Test fun no_route_waits_for_a_route_without_spending_the_budget() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        link.connected.value = false
        link.responder = { f -> if (link.connected.value) stored(f) else SubmitOutcome.Failed(FailReason.NO_ROUTE) }

        reg.attach(link)
        advanceUntilIdle()
        assertEquals(PairingStatus.IN_FLIGHT, reg.stateOf(key)?.status, "parked, not failed")

        link.connected.value = true
        advanceUntilIdle()
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
        assertEquals(0, reg.stateOf(key)?.failedRounds, "waiting for a link is not a failed attempt")
    }

    // ── 8. permission ───────────────────────────────────────────────────────────────────────────────

    @Test fun a_denied_permission_blocks_every_pairing_and_heals_when_it_is_granted() = pushTest {
        val platform = FakePlatform().apply { autoToken = null; authorization = PushAuthorization.AUTHORIZED }
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }
        reg.attach(link)
        advanceTimeBy(1_000)

        platform.authorization = PushAuthorization.DENIED
        platform.failureFlow.tryEmit(PushFailureEvent(platform.lastRequest, PushRegistrationFailure.DENIED))
        advanceUntilIdle()

        assertEquals(PairingStatus.BLOCKED, reg.stateOf(key)?.status)
        assertEquals(PushUiStatus.DENIED, reg.status.value)
        val asks = platform.prompts.size
        listOf(TriggerReason.FOREGROUND, TriggerReason.CONNECTED).forEach { reg.trigger(it) }
        advanceTimeBy(1_000)
        assertEquals(asks, platform.prompts.size, "a refusal must not become a dialog on every foreground")

        platform.authorization = PushAuthorization.AUTHORIZED // the user turned it on in system Settings
        platform.autoToken = PushToken("ios", "tok-A")
        reg.trigger(TriggerReason.PERMISSION_CHANGED)
        advanceUntilIdle()

        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
    }

    // ── 9. the foreground-only token wait ───────────────────────────────────────────────────────────

    @Test fun the_token_wait_counts_foreground_time_only() = pushTest {
        val platform = FakePlatform().apply { autoToken = null } // the OS simply never calls back
        val fg = MutableStateFlow(false)
        val reg = registrar(platform, FakeStore(), foreground = fg)
        val link = FakeLink(key) { testScheduler.currentTime }

        reg.attach(link)
        advanceTimeBy(60_000)
        assertEquals(1, platform.prompts.size, "a backgrounded app has not failed at anything yet")

        fg.value = true
        advanceTimeBy(30_000 + 5_000 + 1_000) // the wait, then the in-round pause before attempt 2
        assertTrue(platform.prompts.size >= 2, "30s of FOREGROUND silence is a spent attempt")
    }

    // ── 10. several pairings at once ────────────────────────────────────────────────────────────────

    @Test fun pairings_are_independent_and_submit_at_a_bounded_concurrency() = pushTest {
        val platform = FakePlatform()
        val store = FakeStore()
        val reg = registrar(platform, store, config = PushRegistrar.Config(maxConcurrentSubmits = 2))
        val keys = (1..3).map { PairingKey("wss://r", "acct", "dev-$it") }
        val release = CompletableDeferred<Unit>()
        val links = keys.map { k ->
            FakeLink(k) { testScheduler.currentTime }.also { l ->
                l.responder = { f ->
                    release.await()
                    if (k == keys[1]) SubmitOutcome.Failed(FailReason.SEND_FAILED) else stored(f)
                }
            }
        }

        links.forEach { reg.attach(it) }
        advanceUntilIdle()
        assertEquals(2, links.sumOf { it.inFlight }, "three links must not open three sockets at once")
        release.complete(Unit)
        advanceTimeBy(ROUND_MS + 1) // the middle link fails forever; its ladder never goes idle

        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(keys[0])?.status)
        assertEquals(PairingStatus.RETRY_WAIT, reg.stateOf(keys[1])?.status)
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(keys[2])?.status)
        assertEquals(PushUiStatus.PARTIAL, reg.status.value, "\"on\" would be a lie while one computer cannot wake this phone")
        assertTrue(links.all { it.peakInFlight <= 2 })

        reg.detach(keys[1], forget = true)
        advanceUntilIdle()
        assertNull(reg.stateOf(keys[1]))
        assertTrue(PushRegistrar.pairingStoreKey(keys[1]) !in store.map, "a severed link leaves no state behind")
    }

    @Test fun a_pairing_waiting_for_a_route_does_not_hold_a_submission_slot() = pushTest {
        val reg = registrar(FakePlatform(), FakeStore(), config = PushRegistrar.Config(maxConcurrentSubmits = 2))
        val keys = (1..3).map { PairingKey("wss://r", "acct", "dev-$it") }
        val links = keys.map { k -> FakeLink(k) { testScheduler.currentTime } }
        // two computers are simply not reachable right now; their rounds park on `connected`
        links.take(2).forEach { l ->
            l.connected.value = false
            l.responder = { f -> if (l.connected.value) stored(f) else SubmitOutcome.Failed(FailReason.NO_ROUTE) }
        }

        links.forEach { reg.attach(it) }
        advanceUntilIdle()

        // the whole point: a round that is WAITING must not occupy one of the two submission permits,
        // or two offline satellites would starve the primary computer's registration indefinitely
        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(keys[2])?.status,
            "a reachable pairing must register while the unreachable ones wait")
        assertEquals(1, links[2].submitted.size)
        assertEquals(PairingStatus.IN_FLIGHT, reg.stateOf(keys[0])?.status, "parked, not failed")

        links.take(2).forEach { it.connected.value = true } // the LAN came back
        advanceUntilIdle()
        assertTrue(keys.all { reg.stateOf(it)?.status == PairingStatus.CONFIRMED })
    }

    // ── 11. restart ─────────────────────────────────────────────────────────────────────────────────

    @Test fun a_restart_honours_a_persisted_cooldown() = pushTest {
        val store = FakeStore()
        val first = registrar(FakePlatform(), store)
        val link = FakeLink(key) { testScheduler.currentTime }
        link.responder = { SubmitOutcome.Failed(FailReason.SEND_FAILED) }
        first.attach(link)
        advanceTimeBy(ROUND_MS + 1)
        val due = assertNotNull(first.stateOf(key)?.nextRetryAt)
        first.detach(key, forget = false)

        // a brand-new process reading the same store
        val second = registrar(FakePlatform(), store)
        val link2 = FakeLink(key) { testScheduler.currentTime }
        link2.responder = { f -> stored(f) }
        second.attach(link2)
        advanceTimeBy(1_000)

        assertEquals(PairingStatus.RETRY_WAIT, second.stateOf(key)?.status)
        assertEquals(due, second.stateOf(key)?.nextRetryAt, "a cooldown a restart could skip is not a cooldown")
        assertTrue(link2.submitted.isEmpty())
    }

    @Test fun an_impossible_persisted_retry_time_becomes_retryable_without_forgiving_the_failures() = pushTest {
        val store = FakeStore()
        // a clock that moved backwards (or a corrupted record) parked this device until next year
        store.map[PushRegistrar.pairingStoreKey(key)] =
            """{"schema":1,"desiredEnabled":true,"status":"RETRY_WAIT","failedRounds":2,"nextRetryAt":99999999999}"""
        val reg = registrar(FakePlatform(), store)
        val link = FakeLink(key) { testScheduler.currentTime }

        reg.attach(link)
        advanceUntilIdle()

        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status, "a broken clock must not disable notifications")
        // the failure history survives: if it fails again it backs off from where it was, not from zero
        assertTrue(link.submitted.isNotEmpty())
    }

    @Test fun an_unreadable_record_starts_over_rather_than_guessing() = pushTest {
        val store = FakeStore()
        store.map[PushRegistrar.pairingStoreKey(key)] = "{not json"
        val reg = registrar(FakePlatform(), store)

        reg.attach(FakeLink(key) { testScheduler.currentTime })
        advanceUntilIdle()

        assertEquals(PairingStatus.CONFIRMED, reg.stateOf(key)?.status)
    }

    // ── 12. the 24h re-proof ────────────────────────────────────────────────────────────────────────

    @Test fun a_confirmation_is_re_proved_after_a_day_but_only_while_visible_and_connected() = pushTest {
        val platform = FakePlatform()
        val fg = MutableStateFlow(true)
        val reg = registrar(platform, FakeStore(), foreground = fg)
        val link = FakeLink(key) { testScheduler.currentTime }
        reg.attach(link)
        advanceUntilIdle()
        assertEquals(1, link.submitted.size)

        reg.trigger(TriggerReason.FOREGROUND) // well inside the window: nothing to re-prove
        advanceTimeBy(1_000)
        assertEquals(1, link.submitted.size)

        advanceTimeBy(25 * 60 * MIN)
        fg.value = false
        reg.trigger(TriggerReason.CONNECTED)
        advanceTimeBy(1_000)
        assertEquals(1, link.submitted.size, "a re-proof is never worth waking a backgrounded app for")

        fg.value = true
        reg.trigger(TriggerReason.FOREGROUND)
        advanceUntilIdle()
        assertEquals(2, link.submitted.size, "a day-old confirmation is an assumption, not a fact")
    }

    // ── 13. when the permission dialog is allowed to appear ─────────────────────────────────────────

    @Test fun the_permission_prompt_belongs_to_the_first_attach_and_to_the_settings_switch_only() = pushTest {
        val platform = FakePlatform().apply { autoToken = null }
        val reg = registrar(platform, FakeStore())
        val link = FakeLink(key) { testScheduler.currentTime }

        reg.attach(link)
        advanceTimeBy(1_000)
        assertEquals(listOf(true), platform.prompts, "the first ask, right after a pairing came up")

        listOf(TriggerReason.FOREGROUND, TriggerReason.CONNECTED, TriggerReason.PROMPT_SENT, TriggerReason.START)
            .forEach { reg.trigger(it) }
        // long enough for the silent ask to spend a whole round and park in its cooldown, so what follows
        // is about the user's action and not about a retry that happened to be mid-flight
        advanceTimeBy(150_000)
        assertTrue(platform.prompts.drop(1).none { it }, "only a deliberate user action may raise the dialog again")

        link.responder = { f -> verdict(f) }
        link.desiredEnabled.value = false
        advanceTimeBy(1_000) // a StateFlow conflates: the OFF has to be observed before the ON
        platform.autoToken = PushToken("ios", "tok-A")
        link.desiredEnabled.value = true // the Settings switch going back on
        advanceUntilIdle()
        assertTrue(platform.prompts.last(), "the user asking for notifications is exactly when to ask the OS")
    }
}
