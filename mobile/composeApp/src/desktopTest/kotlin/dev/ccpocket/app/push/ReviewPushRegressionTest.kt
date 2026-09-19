package dev.ccpocket.app.push

import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Regressions from the 2026-09-19 review of #389 (problems 4 and 5): a failed stale-write re-confirmation
 * must fall back into the bounded retry/cooldown path instead of parking in PENDING forever, and a refusal
 * the platform delivers synchronously from inside the ask must not be lost to a listener that subscribed
 * too late — nor may a late refusal of an EARLIER ask be taken as the verdict on the current one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewPushRegressionTest {
    private class Memory : PushStateStore {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }
    /** [refuseSynchronously]: answer every ask with UNSUPPORTED from inside the call (Android no-Firebase). */
    private class Platform(initial: PushToken?, val refuseSynchronously: Boolean = true) : PushPlatform {
        override val token = MutableStateFlow(initial)
        override val failures = MutableSharedFlow<PushFailureEvent>(extraBufferCapacity = 8)
        val requests = mutableListOf<Long>()
        override fun requestToken(prompt: Boolean, request: Long) {
            requests += request
            if (refuseSynchronously) failures.tryEmit(PushFailureEvent(request, PushRegistrationFailure.UNSUPPORTED))
        }
        override fun readAuthorization(cb: (PushAuthorization) -> Unit) = cb(PushAuthorization.AUTHORIZED)
    }
    private class Link : PairingLink {
        override val key = PairingKey("wss://test", "account", "device")
        override val desiredEnabled = MutableStateFlow(true)
        override val connected = MutableStateFlow(true)
        var calls = 0
        var respond: suspend (RegisterPush) -> SubmitOutcome = { ack(it) }
        override suspend fun submit(frame: RegisterPush, ackTimeoutMs: Long): SubmitOutcome {
            calls++
            return respond(frame)
        }
    }
    @Test fun synchronous_unsupported_callback_must_block_without_retrying() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val platform = Platform(null)
            val reg = PushRegistrar(scope, platform, Memory(), epochMillis = { testScheduler.currentTime }, jitter = { 0.0 })
            reg.attach(Link())
            runCurrent()
            assertEquals(DeviceTokenStatus.BLOCKED, reg.deviceState().status)
            assertEquals(1, platform.requests.size, "a platform that said UNSUPPORTED must not be asked again")
        } finally { scope.cancel() }
    }
    @Test fun a_late_refusal_of_an_earlier_ask_is_not_the_verdict_on_the_current_one() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val platform = Platform(null, refuseSynchronously = false)
            val reg = PushRegistrar(scope, platform, Memory(), epochMillis = { testScheduler.currentTime }, jitter = { 0.0 })
            reg.attach(Link())
            runCurrent()
            assertEquals(1, platform.requests.size)
            // the first ask never answers: wait it out (30s foreground) plus the first in-round backoff (5s)
            advanceTimeBy(36_000)
            runCurrent()
            assertEquals(2, platform.requests.size, "the second ask must be in flight")
            platform.failures.tryEmit(PushFailureEvent(platform.requests[0], PushRegistrationFailure.UNSUPPORTED))
            runCurrent()
            assertEquals(DeviceTokenStatus.REQUESTING, reg.deviceState().status, "ask #1's late refusal leaked into ask #2")
            platform.failures.tryEmit(PushFailureEvent(platform.requests[1], PushRegistrationFailure.UNSUPPORTED))
            runCurrent()
            assertEquals(DeviceTokenStatus.BLOCKED, reg.deviceState().status)
        } finally { scope.cancel() }
    }
    @Test fun failed_stale_reconfirmation_must_schedule_another_attempt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val platform = Platform(PushToken("fcm", "old"))
            val reg = PushRegistrar(scope, platform, Memory(), epochMillis = { testScheduler.currentTime }, jitter = { 0.0 })
            val link = Link()
            link.respond = { f ->
                when (link.calls) {
                    1 -> awaitCancellation()
                    3 -> SubmitOutcome.Failed(FailReason.ACK_TIMEOUT, written = true)
                    else -> ack(f)
                }
            }
            reg.attach(link)
            runCurrent()
            assertEquals(1, link.calls)
            platform.token.value = PushToken("fcm", "new")
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(3, link.calls, "must exercise the extra confirmation")
            advanceTimeBy(3_600_000)
            runCurrent()
            assertTrue(link.calls > 3, "No retry after 1 hour; state=${reg.stateOf(link.key)}")
            assertEquals(PairingStatus.CONFIRMED, reg.stateOf(link.key)?.status)
        } finally { scope.cancel() }
    }
    @Test fun a_stale_reconfirmation_that_never_succeeds_ends_in_a_cooldown_with_a_wake_timer() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val platform = Platform(PushToken("fcm", "old"))
            val reg = PushRegistrar(scope, platform, Memory(), epochMillis = { testScheduler.currentTime }, jitter = { 0.0 })
            val link = Link()
            var failing = true
            link.respond = { f ->
                when {
                    link.calls == 1 -> awaitCancellation()
                    // every PLAIN attempt acks, every extra confirmation times out: never provable
                    failing && link.calls % 2 == 1 -> SubmitOutcome.Failed(FailReason.ACK_TIMEOUT, written = true)
                    else -> ack(f)
                }
            }
            reg.attach(link)
            runCurrent()
            platform.token.value = PushToken("fcm", "new")
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            val parked = reg.stateOf(link.key)!!
            assertEquals(PairingStatus.RETRY_WAIT, parked.status, "budget spent must mean a cooldown, not PENDING")
            assertEquals(1, parked.failedRounds)
            assertNotNull(parked.nextRetryAt)
            val before = link.calls
            failing = false
            advanceTimeBy(5 * 60_000L + 1)
            runCurrent()
            assertTrue(link.calls > before, "the cooldown timer must start another round on its own")
            assertEquals(PairingStatus.CONFIRMED, reg.stateOf(link.key)?.status)
        } finally { scope.cancel() }
    }
    companion object {
        fun ack(f: RegisterPush) = SubmitOutcome.Acked(PushRegistrationResult(f.requestId!!, PushRegistrationOutcome.STORED))
    }
}
