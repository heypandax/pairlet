package dev.ccpocket.app.push

import dev.ccpocket.app.epochMillis as platformEpochMillis
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.observability.Diagnostics
import dev.ccpocket.observability.ErrorCode
import dev.ccpocket.observability.ErrorPath
import dev.ccpocket.observability.Outcome
import dev.ccpocket.observability.SafeMetrics
import dev.ccpocket.observability.Stage as DiagnosticStage
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.PushRegistrationOutcome
import dev.ccpocket.protocol.PushRegistrationResult
import dev.ccpocket.protocol.RegisterPush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

// ── the pieces a pairing supplies ───────────────────────────────────────────────────────────────────

/** One registerable identity: which relay, whose account, and the deviceId the token is filed under.
 *  The primary computer, every fleet satellite and every collaborator inbox is a separate one. */
data class PairingKey(val relay: String, val accountId: String, val deviceId: String)

/** Why a submission ended. [written] on a failure separates "never reached a socket" from "may already
 *  be at the relay", which is the difference between a clean retry and a state the next round has to
 *  re-confirm (see `staleWrittenPossible`). */
sealed interface SubmitOutcome {
    /** The relay answered. The result still has to be matched against the request id by the caller. */
    data class Acked(val result: PushRegistrationResult) : SubmitOutcome
    /** Written to a socket, but the relay predates PROTO_V_PUSH_ACK — no verdict is ever coming. */
    data object SentLegacy : SubmitOutcome
    data class Failed(val reason: FailReason, val written: Boolean = false) : SubmitOutcome
}

enum class FailReason {
    /** The frame could not be handed to a socket. */
    SEND_FAILED,
    /** Written, ack-capable relay, no verdict within the budget. */
    ACK_TIMEOUT,
    /** The credential is dead (relay AuthError). Retrying is pointless until the user re-pairs. */
    AUTH_REJECTED,
    /** Nothing to write to right now — not a failure of this attempt, just "not yet". */
    NO_ROUTE,
}

/**
 * The transport half of one pairing, implemented by `PocketRepository`.
 *
 * [submit] returns only once the frame has REALLY been written to a socket (never merely queued) and —
 * when the relay announces `PROTO_V_PUSH_ACK` — once the matching receipt has arrived or [ackTimeoutMs]
 * has elapsed. "Queued" used to be the client's definition of registered, which is exactly how a token
 * could sit in an outbox forever while the UI said notifications were on.
 */
interface PairingLink {
    val key: PairingKey
    /** Notifications wanted AND this identity is allowed to register at all. */
    val desiredEnabled: StateFlow<Boolean>
    /** A usable path to the relay exists right now (attached, or the direct link can dial). */
    val connected: StateFlow<Boolean>
    suspend fun submit(frame: RegisterPush, ackTimeoutMs: Long): SubmitOutcome
}

/** The device-wide platform token source. Separate from [PushTokens] purely so tests can drive it. */
interface PushPlatform {
    val token: StateFlow<PushToken?>
    val failures: Flow<PushRegistrationFailure>
    fun requestToken(prompt: Boolean)
    fun readAuthorization(cb: (PushAuthorization) -> Unit)
}

/** Tiny persistence seam. The default is [SecureStore]; tests use a map. */
interface PushStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** What the Settings row shows. Aggregated across every attached pairing — "notifications are on" is
 *  only honest when EVERY computer that should be able to wake this phone actually can. */
enum class PushUiStatus { OFF, NEEDS_PERMISSION, DENIED, PREPARING, FAILED, ENABLED, PARTIAL, UNCONFIRMED_LEGACY }

/** What woke the coordinator. The three USER/permission reasons additionally RESET the retry budget:
 *  a person who just asked for notifications must not be told to wait out a backoff they never saw. */
enum class TriggerReason {
    START, FOREGROUND, CONNECTED, TOKEN, PAIRING_ADDED, PERMISSION_CHANGED, USER_ENABLED, USER_RETRY, PROMPT_SENT
}

internal enum class PairingStatus { PENDING, IN_FLIGHT, CONFIRMED, RETRY_WAIT, BLOCKED, LEGACY_UNCONFIRMED }
internal enum class DeviceTokenStatus { IDLE, REQUESTING, AVAILABLE, RETRY_WAIT, BLOCKED }

internal data class PairingState(
    val status: PairingStatus = PairingStatus.PENDING,
    val desiredEnabled: Boolean = false,
    val lastConfirmedAt: Long? = null,
    val failure: String? = null,
    val failedRounds: Int = 0,
    val nextRetryAt: Long? = null,
    // in-process only: which token generation the confirmation belongs to. A restored state starts
    // UNKNOWN_GEN so a cold start always re-registers — the relay row may have been pruned while the app
    // was dead, and re-registering the same token is idempotent (#114 fix ①).
    val confirmedGen: Int = UNKNOWN_GEN,
    val staleWrittenPossible: Boolean = false,
)

internal data class DeviceTokenState(
    val status: DeviceTokenStatus = DeviceTokenStatus.IDLE,
    val failedRounds: Int = 0,
    val nextRetryAt: Long? = null,
    val failure: PushRegistrationFailure? = null,
)

internal const val UNKNOWN_GEN = -1

// ── the coordinator ─────────────────────────────────────────────────────────────────────────────────

/**
 * Drives every pairing from "notifications are wanted" to "the relay says it stored the token", and
 * keeps them there.
 *
 * The old arrangement had no state at all: the repository pushed a `RegisterPush` into an outbox, set a
 * boolean, and never looked again. So a relay that could not store the token, a frame that never left
 * the queue, a satellite that was excluded by construction, and a permission the user revoked later all
 * produced the same result — a phone that believes notifications work and never rings. Everything here
 * exists to make each of those a distinguishable, self-healing state:
 *
 *  - a DEVICE-level token machine (one OS token, shared) with a bounded ask-and-wait,
 *  - a PER-PAIRING machine ("the relay confirmed THIS deviceId"), persisted so a cold start resumes,
 *  - one serialized [evaluate] pass, so the eight things that can trigger a re-check (attach, connect,
 *    foreground, token rotation, permission change, the toggle, an explicit retry, sending a prompt)
 *    collapse into at most one decision instead of eight overlapping rounds.
 *
 * Nothing here ever persists a token, a request id or an in-flight object; the persisted records hold
 * only status, counters and timestamps. [PairingKey] parts are LOCAL storage keys and never leave the
 * device — no diagnostic or telemetry call below carries any of them.
 */
class PushRegistrar(
    private val scope: CoroutineScope,
    private val platform: PushPlatform = DefaultPushPlatform,
    private val store: PushStateStore = SecureStorePushStateStore,
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
    private val epochMillis: () -> Long = { platformEpochMillis() },
    private val jitter: () -> Double = { Random.nextDouble() },
    private val config: Config = Config(),
) {
    /**
     * @param attemptsPerRound submissions per round before the pairing backs off.
     * @param attemptWaitsMs waits BETWEEN attempts of one round (so attemptsPerRound-1 entries).
     * @param roundCooldownsMs cooldown after the 1st, 2nd, 3rd+ failed round — the last value is the cap.
     * @param reconfirmAfterMs a confirmation older than this is re-proved (only while foreground AND
     *        connected, so it can never become a background wake-up).
     * @param tokenWaitForegroundMs how long to wait for the OS token, counting FOREGROUND time only:
     *        a backgrounded app whose callback is simply frozen has not failed at anything.
     */
    data class Config(
        val attemptsPerRound: Int = 3,
        val attemptWaitsMs: List<Long> = listOf(5_000L, 30_000L),
        val ackTimeoutMs: Long = 10_000L,
        val roundCooldownsMs: List<Long> = listOf(5 * 60_000L, 15 * 60_000L, 60 * 60_000L),
        val reconfirmAfterMs: Long = 24 * 60 * 60_000L,
        val tokenWaitForegroundMs: Long = 30_000L,
        val maxConcurrentSubmits: Int = 2,
    )

    private val links = mutableMapOf<PairingKey, PairingLink>()
    private val watchers = mutableMapOf<PairingKey, Job>()
    private val rounds = mutableMapOf<PairingKey, Job>()
    private val states = mutableMapOf<PairingKey, PairingState>()
    private val generations = mutableMapOf<PairingKey, Int>()

    private var device = DeviceTokenState()
    private var tokenJob: Job? = null
    private var tokenJobPrompted = false
    private var wakeJob: Job? = null
    private var authorization: PushAuthorization = PushAuthorization.UNKNOWN
    /** Bumped on every genuine token change: the identity of "what we are trying to register". */
    private var tokenGen = 0
    private var promptedOnce = false

    private val gate = Semaphore(config.maxConcurrentSubmits)
    // UNLIMITED + drain-all rather than CONFLATED: conflation keeps the LAST reason, which would let an
    // ordinary FOREGROUND swallow the USER_RETRY that arrived a millisecond earlier — and USER_RETRY is
    // the one reason that resets the budget. Draining merges them instead, which dedups just as well.
    private val triggers = Channel<TriggerReason>(Channel.UNLIMITED)

    private val _status = MutableStateFlow(PushUiStatus.OFF)
    /** What the Settings row renders. */
    val status: StateFlow<PushUiStatus> = _status

    init {
        device = loadDeviceState()
        scope.launch {
            for (first in triggers) {
                val reasons = mutableSetOf(first)
                while (true) reasons += (triggers.tryReceive().getOrNull() ?: break)
                runCatching { evaluate(reasons) }
            }
        }
        scope.launch {
            var previous: PushToken? = null
            platform.token.collect { t ->
                if (t == null) return@collect
                val rotated = previous != null && previous != t
                previous = t
                tokenGen++
                // a real token settles every earlier verdict: a DENIED/backoff record that the OS has
                // since contradicted must not keep a working registration parked
                device = DeviceTokenState(status = DeviceTokenStatus.AVAILABLE)
                saveDeviceState()
                if (rotated) {
                    // a ROTATION makes every in-flight round about the WRONG value. Letting them finish
                    // would confirm the old token and leave the new one unregistered until something else
                    // happened to ask — which is how a phone goes quiet after an APNs rotation.
                    links.keys.toList().forEach { onExpectationChanged(it) }
                }
                // ...but the FIRST token of a launch is not news: it is the same value the persisted
                // records were written about, so it must not reset a cooldown that a restart would then
                // be able to skip at will.
                trigger(if (rotated) TriggerReason.TOKEN else TriggerReason.START)
            }
        }
    }

    // ── public API ──────────────────────────────────────────────────────────────────────────────────

    /** Register a pairing (idempotent by [PairingKey]); re-attaching the same key replaces the link. */
    fun attach(link: PairingLink) {
        val key = link.key
        val known = key in links || store.get(pairingStoreKey(key)) != null
        links[key] = link
        if (key !in states) states[key] = loadPairingState(key)
        watchers.remove(key)?.cancel()
        watchers[key] = scope.launch {
            launch {
                var seen: Boolean? = null
                link.desiredEnabled.collect { want ->
                    val changed = seen != null && seen != want
                    if (changed) onExpectationChanged(key)
                    seen = want
                    // Only a genuine off->on TRANSITION counts as the user enabling notifications. The
                    // REPLAYED first value is just an observation — treating it as the user acting would
                    // re-arm the permission prompt (and clear any cooldown) on every single attach.
                    trigger(if (changed && want) TriggerReason.USER_ENABLED else TriggerReason.CONNECTED)
                }
            }
            launch { link.connected.collect { up -> if (up) trigger(TriggerReason.CONNECTED) } }
        }
        // PAIRING_ADDED resets the retry budget, so only a pairing with NO history may claim it: an app
        // restart re-attaches every link, and letting that count as "new" would turn any cooldown into a
        // suggestion the user could clear by force-quitting.
        trigger(if (known) TriggerReason.CONNECTED else TriggerReason.PAIRING_ADDED)
    }

    /** Stop driving a pairing. [forget] also deletes its persisted record — for an unpaired or severed
     *  link, whose state would otherwise resurrect if the same deviceId ever came back. */
    fun detach(key: PairingKey, forget: Boolean) {
        links.remove(key)
        watchers.remove(key)?.cancel()
        rounds.remove(key)?.cancel()
        generations.remove(key)
        if (forget) {
            states.remove(key)
            store.remove(pairingStoreKey(key))
        }
        recomputeStatus()
    }

    /** Ask for a re-evaluation. Cheap and safe to call from anywhere; the work is serialized. */
    fun trigger(reason: TriggerReason) { triggers.trySend(reason) }

    /**
     * Submit ONE clearing registration for [key] and then forget it entirely — the severed-link path
     * (a Collaborator Link removed, a daemon unpaired). Best effort by design: the credential is about
     * to be discarded, so this is the last moment the relay row can ever be cleared, but a failure here
     * must not block the removal the user asked for.
     */
    suspend fun clearAndForget(key: PairingKey) {
        val link = links[key]
        generations[key] = (generations[key] ?: 0) + 1 // fence any round still believing in the old expectation
        rounds.remove(key)?.cancel()
        if (link != null) {
            val tag = platform.token.value?.platform ?: lastPlatformTag() ?: UNKNOWN_PLATFORM
            runCatching { gate.withPermit { link.submit(RegisterPush(tag, "", newRequestId()), config.ackTimeoutMs) } }
        }
        detach(key, forget = true)
    }

    /** Test seam: the current per-pairing record. */
    internal fun stateOf(key: PairingKey): PairingState? = states[key]

    /** Test seam: the current device-level token record. */
    internal fun deviceState(): DeviceTokenState = device

    // ── evaluation ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun evaluate(reasons: Set<TriggerReason>) {
        val privileged = reasons.any { it in BUDGET_RESETTING }
        if (TriggerReason.PERMISSION_CHANGED in reasons || TriggerReason.FOREGROUND in reasons) readAuthorizationNow()

        val wanted = links.values.filter { it.desiredEnabled.value }
        if (wanted.isEmpty()) {
            // every pairing is off: converge the relay on "no token" rather than just stopping
            links.values.forEach { link -> maybeStartRound(link, privileged, reconnected = true) }
            // a CLEARING round backs off like any other, and its cooldown needs a timer too — without
            // this an "off" that failed would sit in RETRY_WAIT until something unrelated happened
            scheduleWake()
            recomputeStatus()
            return
        }
        if (privileged) {
            // a person acted: clear the device-level backoff (and a DENIED that the OS may have lifted)
            if (device.status == DeviceTokenStatus.RETRY_WAIT ||
                (device.status == DeviceTokenStatus.BLOCKED && authorization.allowsPush())
            ) {
                device = DeviceTokenState(status = DeviceTokenStatus.IDLE)
                saveDeviceState()
            }
        }
        // prompt exactly where the product says: the first time a pairing is up and wants notifications,
        // or when the user just flipped the switch on. Everything else registers silently.
        val mayPrompt = !promptedOnce || TriggerReason.USER_ENABLED in reasons || TriggerReason.USER_RETRY in reasons
        ensureTokenRequested(prompt = mayPrompt && authorization != PushAuthorization.DENIED)

        links.values.forEach { link -> maybeStartRound(link, privileged, TriggerReason.CONNECTED in reasons) }
        scheduleWake()
        recomputeStatus()
    }

    private fun maybeStartRound(link: PairingLink, privileged: Boolean, reconnected: Boolean = false) {
        val key = link.key
        val state = states.getOrPut(key) { loadPairingState(key) }
        if (rounds[key]?.isActive == true) return
        val want = link.desiredEnabled.value
        val now = epochMillis()

        if (device.status == DeviceTokenStatus.BLOCKED && want) {
            // no token is coming; say so per pairing instead of leaving them all "pending" forever
            update(key, state.copy(status = PairingStatus.BLOCKED, failure = device.failure?.name, desiredEnabled = true))
            return
        }
        when (state.status) {
            // a confirmation is good for [reconfirmAfterMs], and only re-proved when the app is both
            // foreground and connected — a re-confirmation is never worth waking anything up for
            PairingStatus.CONFIRMED -> {
                val fresh = state.confirmedGen == tokenGen && state.desiredEnabled == want &&
                    (state.lastConfirmedAt ?: 0) + config.reconfirmAfterMs > now
                if (fresh) return
                val stale = state.confirmedGen == tokenGen && state.desiredEnabled == want
                if (stale && !(foreground.value && link.connected.value)) return
            }
            // an old relay cannot answer, so a timer would loop forever — but a RECONNECT is exactly when
            // a relay may have been upgraded, and that is the only cheap way to find out
            PairingStatus.LEGACY_UNCONFIRMED ->
                if (state.desiredEnabled == want && state.confirmedGen == tokenGen && !privileged && !reconnected) return
            PairingStatus.RETRY_WAIT ->
                if (!privileged && state.desiredEnabled == want && now < (state.nextRetryAt ?: 0)) return
            PairingStatus.BLOCKED ->
                if (!privileged && state.desiredEnabled == want) return
            PairingStatus.PENDING, PairingStatus.IN_FLIGHT -> {}
        }
        if (want && platform.token.value == null) return // nothing to register yet; TOKEN re-enters here
        if (privileged) update(key, state.copy(failedRounds = 0, nextRetryAt = null))
        startRound(link)
    }

    /** The expectation itself changed (token rotated, switch flipped): the in-flight round is now about
     *  the WRONG thing. Cancel it, bump the generation, and let the next pass start a fresh-budget round. */
    private fun onExpectationChanged(key: PairingKey) {
        generations[key] = (generations[key] ?: 0) + 1
        rounds.remove(key)?.cancel()
        states[key]?.let { update(key, it.copy(failedRounds = 0, nextRetryAt = null, status = PairingStatus.PENDING)) }
    }

    // ── device-level token acquisition ──────────────────────────────────────────────────────────────

    private fun ensureTokenRequested(prompt: Boolean) {
        if (platform.token.value != null) return
        if (device.status == DeviceTokenStatus.BLOCKED) return
        if (tokenJob?.isActive == true) {
            // merge into the in-flight ask — UNLESS this one may prompt and that one may not. A person
            // who just turned notifications on must not be silently swallowed by a background retry that
            // happened to be mid-flight; that is a dialog they asked for and would never see.
            if (!prompt || tokenJobPrompted) return
            tokenJob?.cancel()
        }
        if (device.status == DeviceTokenStatus.RETRY_WAIT && epochMillis() < (device.nextRetryAt ?: 0)) return
        device = device.copy(status = DeviceTokenStatus.REQUESTING)
        if (prompt) promptedOnce = true
        tokenJobPrompted = prompt
        tokenJob = scope.launch { requestTokenRound(prompt) }
    }

    private suspend fun requestTokenRound(prompt: Boolean) {
        for (attempt in 1..config.attemptsPerRound) {
            // only the FIRST attempt of a prompting round may prompt: a dialog re-appearing on a silent
            // retry is the single most obnoxious thing this machine could do
            platform.requestToken(prompt && attempt == 1)
            when (val r = awaitTokenAttempt()) {
                is TokenAttempt.Got -> return // the token collector owns the transition to AVAILABLE
                is TokenAttempt.Failed -> when (r.failure) {
                    PushRegistrationFailure.DENIED, PushRegistrationFailure.UNSUPPORTED -> {
                        blockDevice(r.failure)
                        return
                    }
                    else -> {} // NETWORK / UNKNOWN: spend an attempt
                }
                TokenAttempt.TimedOut ->
                    // the platform never called back at all. Not an error stream event: an unreachable
                    // APNs/FCM is an environment fact the retry budget already handles.
                    Diagnostics.report(ErrorPath.PUSH, DiagnosticStage.REQUEST, ErrorCode.TIMEOUT, isError = false)
            }
            if (attempt < config.attemptsPerRound) delay(config.attemptWaitsMs.getOrElse(attempt - 1) { config.attemptWaitsMs.last() })
        }
        val rounds = device.failedRounds + 1
        device = DeviceTokenState(
            status = DeviceTokenStatus.RETRY_WAIT,
            failedRounds = rounds,
            nextRetryAt = epochMillis() + cooldownFor(rounds),
        )
        saveDeviceState()
        Diagnostics.report(ErrorPath.PUSH, DiagnosticStage.WAIT, ErrorCode.UNAVAILABLE, isError = true,
            metrics = SafeMetrics(totalCount = rounds.toLong()))
        scheduleWake()
        recomputeStatus()
    }

    private fun blockDevice(failure: PushRegistrationFailure) {
        device = DeviceTokenState(status = DeviceTokenStatus.BLOCKED, failure = failure)
        saveDeviceState()
        if (failure == PushRegistrationFailure.DENIED) {
            // double-check against the OS itself: a stale DENIED callback must not outlive a permission
            // the user has since granted
            platform.readAuthorization { a ->
                authorization = a
                if (a.allowsPush()) {
                device = DeviceTokenState()
                unblockDeviceCausedPairings()
                trigger(TriggerReason.PERMISSION_CHANGED)
            }
                recomputeStatus()
            }
            Diagnostics.report(ErrorPath.PUSH, DiagnosticStage.REQUEST, ErrorCode.PERMISSION_DENIED, isError = false)
        }
        links.values.forEach { link ->
            states[link.key]?.let { update(link.key, it.copy(status = PairingStatus.BLOCKED, failure = failure.name)) }
        }
        recomputeStatus()
    }

    private sealed interface TokenAttempt {
        data object Got : TokenAttempt
        data class Failed(val failure: PushRegistrationFailure) : TokenAttempt
        data object TimedOut : TokenAttempt
    }

    /** Whichever comes first: a token, a refusal, or [Config.tokenWaitForegroundMs] of FOREGROUND time. */
    private suspend fun awaitTokenAttempt(): TokenAttempt = coroutineScope {
        val done = CompletableDeferred<TokenAttempt>()
        val racers = listOf(
            launch { platform.token.first { it != null }; done.complete(TokenAttempt.Got) },
            launch { done.complete(TokenAttempt.Failed(platform.failures.first())) },
            launch { foregroundWait(config.tokenWaitForegroundMs); done.complete(TokenAttempt.TimedOut) },
        )
        val result = done.await()
        racers.forEach { it.cancel() }
        result
    }

    /** Sleeps [totalMs] of foreground time. Background seconds do not count: the OS freezes the very
     *  callback we are waiting for, so charging that silence against the retry budget would turn a
     *  pocketed phone into a "notifications failed" badge. */
    private suspend fun foregroundWait(totalMs: Long) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            delay(TICK_MS)
            if (foreground.value) elapsed += TICK_MS
        }
    }

    private fun readAuthorizationNow() = platform.readAuthorization { a ->
        val was = authorization
        authorization = a
        if (was != a) {
            if (a == PushAuthorization.DENIED) blockDevice(PushRegistrationFailure.DENIED)
            else if (a.allowsPush() && device.status == DeviceTokenStatus.BLOCKED) {
                device = DeviceTokenState()
                saveDeviceState()
                unblockDeviceCausedPairings()
                trigger(TriggerReason.PERMISSION_CHANGED)
            }
        }
        recomputeStatus()
    }

    /** The device stopped being the obstacle, so the pairings it dragged down with it are pending again.
     *  A pairing the RELAY refused ("forbidden", "no_device", a dead credential) stays blocked: nothing
     *  about the OS permission changes that verdict. */
    private fun unblockDeviceCausedPairings() {
        val deviceCauses = PushRegistrationFailure.entries.map { it.name }.toSet()
        states.keys.toList().forEach { key ->
            val state = states[key] ?: return@forEach
            if (state.status == PairingStatus.BLOCKED && state.failure in deviceCauses) {
                update(key, state.copy(status = PairingStatus.PENDING, failure = null))
            }
        }
    }

    // ── one round for one pairing ───────────────────────────────────────────────────────────────────

    private fun startRound(link: PairingLink) {
        val key = link.key
        val gen = generations.getOrPut(key) { 0 }
        rounds[key] = scope.launch { runRound(link, gen) }
    }

    private suspend fun runRound(link: PairingLink, gen: Int) {
        val key = link.key
        val want = link.desiredEnabled.value
        val token = platform.token.value
        if (want && token == null) return
        val platformTag = token?.platform ?: lastPlatformTag() ?: UNKNOWN_PLATFORM
        if (want && token != null) store.put(K_LAST_PLATFORM, token.platform)
        // "off" is an expectation too, and it has to converge even with no token in hand: a blank-token
        // register is what actually clears the relay row, and the relay ignores the platform of one.
        val body = if (want) token!!.token else ""
        val expected = if (want) PushRegistrationOutcome.STORED else PushRegistrationOutcome.CLEARED
        val expectedGen = tokenGen

        update(key, (states[key] ?: PairingState()).copy(status = PairingStatus.IN_FLIGHT, desiredEnabled = want))
        val trace = Diagnostics.begin(ErrorPath.PUSH)
        var wroteSomething = false
        var attempt = 0
        try {
            // NOT `gate.withPermit { whole round }`: the permit must cover a WRITE, never a wait. An
            // attempt that is parked on NO_ROUTE, or sleeping out its in-round backoff, holding a slot
            // is how two disconnected fleet satellites would starve the primary computer's registration
            // for as long as they stayed down.
            run {
                while (attempt < config.attemptsPerRound) {
                    if (generations[key] != gen) return@run
                    attempt++
                    trace?.stage(DiagnosticStage.REQUEST)
                    val requestId = newRequestId()
                    val outcome = try {
                        gate.withPermit { link.submit(RegisterPush(platformTag, body, requestId), config.ackTimeoutMs) }
                    } catch (c: CancellationException) {
                        // cancelled INSIDE submit: whether the bytes reached the relay is unknowable, so
                        // the next confirmation has to re-prove itself rather than trust a receipt that
                        // may belong to this dead attempt
                        markStaleWritten(key)
                        throw c
                    } catch (e: Throwable) {
                        SubmitOutcome.Failed(FailReason.SEND_FAILED)
                    }
                    if (outcome !is SubmitOutcome.Failed || outcome.written) { wroteSomething = true; trace?.stage(DiagnosticStage.WRITE) }
                    when (outcome) {
                        is SubmitOutcome.Acked -> {
                            trace?.stage(DiagnosticStage.ACK)
                            val r = outcome.result
                            if (r.requestId != requestId) continue // a receipt for a superseded attempt
                            when (r.result) {
                                expected -> {
                                    confirm(key, link, expectedGen, trace, attempt)
                                    return@run
                                }
                                PushRegistrationOutcome.REJECTED -> {
                                    // the identity may not register — no budget can fix that
                                    update(key, (states[key] ?: PairingState()).copy(
                                        status = PairingStatus.BLOCKED, failure = r.code ?: "rejected", desiredEnabled = want))
                                    trace?.finish(Outcome.FAILURE, DiagnosticStage.ACK, ErrorCode.REJECTED,
                                        metrics = SafeMetrics(totalCount = attempt.toLong()))
                                    return@run
                                }
                                else -> {
                                    if (r.code == NO_DEVICE) { // the row is gone; a retry writes to nothing
                                        update(key, (states[key] ?: PairingState()).copy(
                                            status = PairingStatus.BLOCKED, failure = NO_DEVICE, desiredEnabled = want))
                                        trace?.finish(Outcome.FAILURE, DiagnosticStage.ACK, ErrorCode.WRITE_FAILED,
                                            metrics = SafeMetrics(totalCount = attempt.toLong()))
                                        return@run
                                    }
                                    trace?.retry()
                                }
                            }
                        }
                        SubmitOutcome.SentLegacy -> {
                            // written, but this relay cannot answer. Honest state, not a failure: the next
                            // CONNECTED against an upgraded relay turns it back into PENDING.
                            update(key, (states[key] ?: PairingState()).copy(
                                status = PairingStatus.LEGACY_UNCONFIRMED, desiredEnabled = want,
                                lastConfirmedAt = epochMillis(), confirmedGen = expectedGen,
                                failedRounds = 0, nextRetryAt = null, failure = null))
                            trace?.finish(Outcome.SUCCESS, DiagnosticStage.WRITE, ErrorCode.FALLBACK_USED,
                                metrics = SafeMetrics(totalCount = attempt.toLong()))
                            return@run
                        }
                        is SubmitOutcome.Failed -> when (outcome.reason) {
                            FailReason.AUTH_REJECTED -> {
                                update(key, (states[key] ?: PairingState()).copy(
                                    status = PairingStatus.BLOCKED, failure = "auth", desiredEnabled = want))
                                trace?.finish(Outcome.FAILURE, DiagnosticStage.WRITE, ErrorCode.REJECTED,
                                    metrics = SafeMetrics(totalCount = attempt.toLong()))
                                return@run
                            }
                            // nothing to write to: waiting for a route is not a failed attempt
                            FailReason.NO_ROUTE -> { attempt--; link.connected.first { it } }
                            FailReason.ACK_TIMEOUT, FailReason.SEND_FAILED -> trace?.retry()
                        }
                    }
                    if (attempt in 1 until config.attemptsPerRound) {
                        delay(config.attemptWaitsMs.getOrElse(attempt - 1) { config.attemptWaitsMs.last() })
                    }
                }
                // budget spent: back off, and say so once (this is the state a user can actually feel)
                val prev = states[key] ?: PairingState()
                val failedRounds = prev.failedRounds + 1
                update(key, prev.copy(
                    status = PairingStatus.RETRY_WAIT, desiredEnabled = want, failedRounds = failedRounds,
                    nextRetryAt = epochMillis() + cooldownFor(failedRounds)))
                trace?.finish(Outcome.FAILURE, DiagnosticStage.WRITE, ErrorCode.SEND_FAILED,
                    metrics = SafeMetrics(totalCount = attempt.toLong()))
                Diagnostics.report(ErrorPath.PUSH, DiagnosticStage.WAIT, ErrorCode.UNAVAILABLE, isError = true,
                    metrics = SafeMetrics(totalCount = failedRounds.toLong()))
                scheduleWake()
            }
        } catch (c: CancellationException) {
            if (wroteSomething) markStaleWritten(key)
            trace?.finish(Outcome.CANCELLED, DiagnosticStage.WRITE, ErrorCode.SUPERSEDED)
            throw c
        } finally {
            recomputeStatus()
        }
    }

    /** A round succeeded. When an earlier, superseded attempt may already have been written, one more
     *  round-trip is required before believing it: the relay's last write could be the stale one. */
    private suspend fun confirm(key: PairingKey, link: PairingLink, gen: Int, trace: dev.ccpocket.observability.OperationTrace?, attempt: Int) {
        val prev = states[key] ?: PairingState()
        if (prev.staleWrittenPossible) {
            update(key, prev.copy(staleWrittenPossible = false))
            delay(STALE_RECONFIRM_DELAY_MS)
            val want = link.desiredEnabled.value
            val token = platform.token.value
            val platformTag = token?.platform ?: lastPlatformTag() ?: UNKNOWN_PLATFORM
            val body = if (want) token?.token ?: return else ""
            val expected = if (want) PushRegistrationOutcome.STORED else PushRegistrationOutcome.CLEARED
            val rid = newRequestId()
            val again = runCatching {
                gate.withPermit { link.submit(RegisterPush(platformTag, body, rid), config.ackTimeoutMs) }
            }.getOrNull()
            val ok = again is SubmitOutcome.Acked && again.result.requestId == rid && again.result.result == expected
            if (!ok) {
                update(key, (states[key] ?: prev).copy(status = PairingStatus.PENDING))
                trace?.finish(Outcome.FAILURE, DiagnosticStage.ACK, ErrorCode.WRITE_FAILED,
                    metrics = SafeMetrics(totalCount = attempt.toLong()))
                return
            }
        }
        val recovering = (states[key] ?: prev).failedRounds > 0
        update(key, (states[key] ?: prev).copy(
            status = PairingStatus.CONFIRMED, desiredEnabled = link.desiredEnabled.value,
            lastConfirmedAt = epochMillis(), confirmedGen = gen, failedRounds = 0, nextRetryAt = null,
            failure = null, staleWrittenPossible = false))
        trace?.finish(Outcome.SUCCESS, DiagnosticStage.COMPLETE, ErrorCode.OK,
            metrics = SafeMetrics(totalCount = attempt.toLong()))
        if (recovering) trace?.recovered()
    }

    private fun markStaleWritten(key: PairingKey) {
        states[key]?.let { states[key] = it.copy(staleWrittenPossible = true) }
    }

    // ── backoff plumbing ────────────────────────────────────────────────────────────────────────────

    private fun cooldownFor(failedRounds: Int): Long {
        val base = config.roundCooldownsMs.getOrElse(failedRounds - 1) { config.roundCooldownsMs.last() }
        // up to +10%: many phones come back from a network outage at the same instant, and a fleet of
        // links retrying in lockstep is a self-inflicted thundering herd against one relay
        return base + (base * JITTER_FRACTION * jitter()).toLong()
    }

    /** One timer for the earliest due retry, instead of polling. A background app whose `delay` the OS
     *  stretches simply wakes late — which is the same "do not count background time" rule as above. */
    private fun scheduleWake() {
        val now = epochMillis()
        val pairingDue = states.values.filter { it.status == PairingStatus.RETRY_WAIT }.mapNotNull { it.nextRetryAt }
        val deviceDue = listOfNotNull(device.nextRetryAt.takeIf { device.status == DeviceTokenStatus.RETRY_WAIT })
        // strictly FUTURE only. A cooldown that already elapsed was just acted on by this very pass, and
        // re-arming a zero-delay timer for it would hand the coordinator an endless trigger→evaluate→timer
        // loop that never advances the clock.
        val due = (pairingDue + deviceDue).filter { it > now }.minOrNull() ?: return
        wakeJob?.cancel()
        wakeJob = scope.launch {
            delay(due - epochMillis())
            trigger(TriggerReason.START)
        }
    }

    // ── status aggregation ──────────────────────────────────────────────────────────────────────────

    private fun recomputeStatus() {
        val attached = links.keys.mapNotNull { states[it] }
        val wanted = links.values.filter { it.desiredEnabled.value }
        _status.value = when {
            wanted.isEmpty() -> PushUiStatus.OFF
            device.status == DeviceTokenStatus.BLOCKED && device.failure == PushRegistrationFailure.DENIED -> PushUiStatus.DENIED
            authorization == PushAuthorization.DENIED -> PushUiStatus.DENIED
            device.status == DeviceTokenStatus.BLOCKED -> PushUiStatus.NEEDS_PERMISSION
            attached.any { it.status == PairingStatus.IN_FLIGHT } ||
                device.status == DeviceTokenStatus.REQUESTING -> PushUiStatus.PREPARING
            attached.isEmpty() -> PushUiStatus.PREPARING
            attached.all { it.status == PairingStatus.CONFIRMED } -> PushUiStatus.ENABLED
            attached.all { it.status == PairingStatus.LEGACY_UNCONFIRMED } -> PushUiStatus.UNCONFIRMED_LEGACY
            attached.any { it.status == PairingStatus.CONFIRMED } -> PushUiStatus.PARTIAL
            attached.any { it.status == PairingStatus.RETRY_WAIT || it.status == PairingStatus.BLOCKED } -> PushUiStatus.FAILED
            else -> PushUiStatus.PREPARING
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────────

    private fun update(key: PairingKey, state: PairingState) {
        states[key] = state
        runCatching { store.put(pairingStoreKey(key), json.encodeToString(state.persisted())) }
        recomputeStatus()
    }

    private fun loadPairingState(key: PairingKey): PairingState {
        val raw = runCatching { store.get(pairingStoreKey(key)) }.getOrNull() ?: return PairingState()
        // a record we cannot read is not evidence of anything — start over rather than guess
        val rec = runCatching { json.decodeFromString<PersistedPairing>(raw) }.getOrNull() ?: return PairingState()
        if (rec.schema != SCHEMA) return PairingState()
        val status = runCatching { PairingStatus.valueOf(rec.status) }.getOrNull() ?: PairingStatus.PENDING
        val now = epochMillis()
        // a nextRetryAt further out than any cooldown can produce means the clock moved (or the record
        // rotted): become retryable NOW, but keep the failure count so a genuinely broken link still backs off
        val retryAt = rec.nextRetryAt?.takeIf { it <= now + MAX_PLAUSIBLE_RETRY_MS }
        return PairingState(
            status = if (status == PairingStatus.IN_FLIGHT) PairingStatus.PENDING else status,
            desiredEnabled = rec.desiredEnabled,
            lastConfirmedAt = rec.lastConfirmedAt,
            failure = rec.failure,
            failedRounds = rec.failedRounds,
            nextRetryAt = retryAt,
        )
    }

    private fun PairingState.persisted() = PersistedPairing(
        desiredEnabled = desiredEnabled,
        // IN_FLIGHT is a process fact, never a stored one: a killed app has no in-flight anything
        status = (if (status == PairingStatus.IN_FLIGHT) PairingStatus.PENDING else status).name,
        lastConfirmedAt = lastConfirmedAt, failure = failure,
        failedRounds = failedRounds, nextRetryAt = nextRetryAt,
    )

    private fun loadDeviceState(): DeviceTokenState {
        val raw = runCatching { store.get(K_DEVICE) }.getOrNull() ?: return DeviceTokenState()
        val rec = runCatching { json.decodeFromString<PersistedDevice>(raw) }.getOrNull() ?: return DeviceTokenState()
        if (rec.schema != SCHEMA) return DeviceTokenState()
        val failure = rec.failure?.let { f -> runCatching { PushRegistrationFailure.valueOf(f) }.getOrNull() }
        val now = epochMillis()
        val retryAt = rec.nextRetryAt?.takeIf { it <= now + MAX_PLAUSIBLE_RETRY_MS }
        // AVAILABLE is deliberately not restorable: the process holds no token after a cold start, so the
        // only honest resting state is "ask again".
        return DeviceTokenState(
            status = if (retryAt != null) DeviceTokenStatus.RETRY_WAIT else DeviceTokenStatus.IDLE,
            failedRounds = rec.failedRounds, nextRetryAt = retryAt, failure = failure,
        )
    }

    private fun saveDeviceState() {
        runCatching {
            store.put(K_DEVICE, json.encodeToString(PersistedDevice(
                failedRounds = device.failedRounds, nextRetryAt = device.nextRetryAt, failure = device.failure?.name)))
        }
    }

    private fun lastPlatformTag(): String? = runCatching { store.get(K_LAST_PLATFORM) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun newRequestId(): String = B64Url.encode(Random.nextBytes(REQUEST_ID_BYTES))

    @Serializable
    private data class PersistedPairing(
        @SerialName("schema") val schema: Int = SCHEMA,
        val desiredEnabled: Boolean = false,
        val status: String = PairingStatus.PENDING.name,
        val lastConfirmedAt: Long? = null,
        val failure: String? = null,
        val failedRounds: Int = 0,
        val nextRetryAt: Long? = null,
    )

    @Serializable
    private data class PersistedDevice(
        @SerialName("schema") val schema: Int = SCHEMA,
        val failedRounds: Int = 0,
        val nextRetryAt: Long? = null,
        val failure: String? = null,
    )

    companion object {
        private const val SCHEMA = 1
        private const val TICK_MS = 1_000L
        private const val REQUEST_ID_BYTES = 16
        private const val JITTER_FRACTION = 0.10
        private const val STALE_RECONFIRM_DELAY_MS = 2_000L
        /** No legitimate cooldown reaches two hours; anything beyond it is a broken clock or record. */
        private const val MAX_PLAUSIBLE_RETRY_MS = 2 * 60 * 60_000L
        private const val UNKNOWN_PLATFORM = "unknown"
        private const val NO_DEVICE = "no_device"
        internal const val K_DEVICE = "push.device.v1"
        internal const val K_PAIRING_PREFIX = "push.reg.v1."
        private const val K_LAST_PLATFORM = "push_platform_last"

        private val BUDGET_RESETTING = setOf(
            TriggerReason.USER_ENABLED, TriggerReason.USER_RETRY, TriggerReason.PERMISSION_CHANGED,
            TriggerReason.TOKEN, TriggerReason.PAIRING_ADDED,
        )

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        internal fun pairingStoreKey(key: PairingKey) = "$K_PAIRING_PREFIX${key.accountId}.${key.deviceId}"

        /** The one shared instance every link attaches to. */
        @Volatile private var instance: PushRegistrar? = null

        /** Process-wide foreground flag, written by whichever repository receives the app lifecycle
         *  callback and read by the shared coordinator. Deliberately NOT per-repository: the coordinator
         *  is a singleton, and "is the app on screen" is a property of the app, not of one link. */
        val appForeground = MutableStateFlow(true)

        /** The process-wide coordinator, created on first use with [scope] as its host. */
        fun shared(scope: CoroutineScope): PushRegistrar =
            instance ?: PushRegistrar(scope, foreground = appForeground).also { instance = it }

        /** Test seam: drop the singleton so a test never inherits another test's coordinator. */
        internal fun resetSharedForTest() { instance = null }
    }
}

private fun PushAuthorization.allowsPush() = this == PushAuthorization.AUTHORIZED ||
    this == PushAuthorization.PROVISIONAL || this == PushAuthorization.EPHEMERAL

/** The production platform: the one process-wide token hub. */
object DefaultPushPlatform : PushPlatform {
    override val token: StateFlow<PushToken?> get() = PushTokens.token
    override val failures: Flow<PushRegistrationFailure> get() = PushTokens.failures
    override fun requestToken(prompt: Boolean) = PushTokens.requestToken(prompt)
    override fun readAuthorization(cb: (PushAuthorization) -> Unit) = PushTokens.readAuthorization(cb)
}

/** The production store. Keys are local-only; nothing here is ever sent anywhere. */
object SecureStorePushStateStore : PushStateStore {
    override fun get(key: String): String? = SecureStore.getString(key)
    override fun put(key: String, value: String) = SecureStore.putString(key, value)
    override fun remove(key: String) = SecureStore.remove(key)
}
