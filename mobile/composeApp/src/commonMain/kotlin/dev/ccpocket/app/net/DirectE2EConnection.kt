package dev.ccpocket.app.net

import dev.ccpocket.observability.Diagnostics
import dev.ccpocket.observability.ErrorPath
import dev.ccpocket.observability.ErrorCode
import dev.ccpocket.observability.Stage as DiagnosticStage
import dev.ccpocket.observability.SafeMetrics

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.SyncProjectPins
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile // commonMain: JVM resolves kotlin.jvm.Volatile implicitly, Kotlin/Native (iOS) does not
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import io.ktor.websocket.Frame as WsFrame

/**
 * The direct (no relay) end-to-end channel to a paired daemon on the same machine or LAN, dialed at
 * the daemon-advertised [PairedDaemon.directUrl] BEFORE the relay. Same Noise handshake + AES-GCM
 * transport as [RelayE2EConnection]'s data plane; in place of the relay control plane the socket opens
 * with a cleartext [LanHello] naming the paired device so the daemon can look up our static key.
 * Always an empty PSK — the device is already allow-listed; Noise KK's mutual static-key auth carries
 * the trust.
 *
 * KEY CONFIRMATION: handshake math alone can't expose an impostor — anyone can answer msg1 with a fresh
 * ephemeral and we'd derive a key that decrypts nothing (a LAN squatter could wedge us "connected" but
 * deaf, defeating the relay fallback). So the link only counts as up when the daemon's FIRST sealed
 * frame (it always sends DaemonInfo right after the gate) actually decrypts. Failure or silence within
 * the timeout → [DirectUnreachableException] → same-attempt relay fallback + cooldown.
 *
 * Known residual (accepted): the cleartext LanHello leaks the high-entropy deviceId to whatever host
 * holds the stored address — on a foreign network reusing the same RFC1918 subnet that's a third party
 * (linkability only; the handshake still can't be completed by them). Scoping directUrl to the network
 * it was learned on is follow-up work.
 */
class DirectE2EConnection(
    /** Wall-clock ceiling on one attempt from dial to key confirmation (#403). Injectable for tests. */
    private val establishBudgetMs: Long = DIRECT_ESTABLISH_BUDGET_MS,
) {
    private val client = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = MAX_FRAME_BYTES // big history replays travel this path too (matches relay cap; see RelayE2EConnection)
        }
    }
    private val outbox = ScopedOutbox()
    val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    /** Mirrors the relay's control plane just enough for the repo's state machine: a synthetic [Attached]
     *  after the Noise handshake (the daemon IS the peer — no separate presence signal exists or is needed). */
    val control = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
    /** Deaf-link signal, symmetric with [RelayE2EConnection.deaf] (issue #146): the daemon keeps ONE
     *  active session per device across BOTH legs, so a fleet satellite's relay handshake can flip that
     *  session out from under this direct socket too. Consecutive undecryptable frames → force re-handshake. */
    val deaf = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var nextId = 0L

    /** Transport frames decrypted on this object's connections, ever — the key-confirmation frame included: only
     *  grows, never reset by a reconnect. Symmetric with [RelayE2EConnection.inboundFrames]; the repo sums the
     *  two legs, so it never needs to know which one carried a frame (SLOW-LINK-RESILIENCE 3.2). */
    @Volatile var inboundFrames: Long = 0L
        private set

    /** True between handshake completion and socket teardown — the repo routes sends here while it holds. */
    @Volatile var connected: Boolean = false
        private set

    /** The accountId this socket was last dialed for. The repo's send() routing checks it because a machine
     *  switch tears the old socket down ASYNCHRONOUSLY — [connected] alone can still read true (old machine)
     *  while frames for the new machine are being sent, which would strand them in a dying outbox. */
    @Volatile var account: String? = null
        private set

    // connection generation (issue #142) — mirrors RelayE2EConnection: a superseded connect() must stop
    // touching the shared cross-reconnect outbox / inbound flows the moment a newer one takes over
    @Volatile private var connSeq = 0

    // the handshaken generation serving the outbox (0 = none): the only target of a scoped pin frame (#362)
    @Volatile private var liveGen = 0

    /** The live connection's generation (0 = none), captured by a pin sync generation when it starts. */
    val liveConnection: Int get() = liveGen

    /**
     * Dial + handshake, then serve for the life of the socket. Failure BEFORE the handshake completes
     * (refused/unreachable/timeout/bad handshake) throws [DirectUnreachableException] so the caller falls
     * back to the relay in the same connect attempt; a drop AFTER is a normal transport death (reconnect path).
     */
    suspend fun connect(url: String, paired: PairedDaemon, keys: E2ECrypto.KeyPair) {
        val gen = ++connSeq
        account = paired.accountId
        val attempt = Attempt()
        try {
            // #403: the handshake timeout below starts only once the WebSocket is up; on iOS the Darwin engine
            // puts no bound on the dial itself, so a black-holed address held the attempt until the 12 s connect
            // watchdog. The dial runs as a child that a budget timer cancels; only THAT cancellation (the scope
            // returns normally with budgetFired set) becomes "unreachable" — a caller's cancel stays a cancel.
            coroutineScope {
                val dial = launch { dialAndServe(url, paired, keys, gen, attempt) }
                attempt.timer = launch {
                    delay(establishBudgetMs)
                    if (!attempt.handshaken) { attempt.budgetFired = true; dial.cancel() }
                }
                dial.invokeOnCompletion { attempt.timer?.cancel() }
            }
            if (attempt.budgetFired && !attempt.handshaken)
                throw DirectUnreachableException("establish budget expired", reason = DirectFallbackReason.BUDGET_EXPIRED)
        } catch (t: Throwable) {
            connected = false
            // pre-handshake plumbing failures (connection refused, DNS, TLS, abrupt close) all mean the
            // SAME thing to the caller: this address doesn't work right now — fall back, don't error out
            if (!attempt.handshaken && t !is CancellationException) {
                val unreachable = t as? DirectUnreachableException
                    ?: DirectUnreachableException(t.message ?: "connect failed", reason = DirectFallbackReason.REFUSED)
                Diagnostics.report(ErrorPath.CONNECTION, DiagnosticStage.CONNECT, unreachable.reason.code, t,
                    metrics = SafeMetrics(transport = dev.ccpocket.observability.Transport.DIRECT), isError = false)
                throw unreachable
            }
            throw t
        } finally {
            connected = false
            if (liveGen == gen) liveGen = 0
            outbox.retire(gen) // what this connection never wrote is reported as such, not left waiting
        }
    }

    /** Per-attempt flags shared between the dial child and the budget timer. */
    private class Attempt {
        @Volatile var handshaken = false
        @Volatile var budgetFired = false
        @Volatile var timer: Job? = null
    }

    private suspend fun dialAndServe(url: String, paired: PairedDaemon, keys: E2ECrypto.KeyPair, gen: Int, attempt: Attempt) =
        coroutineScope {
            client.webSocket(urlString = url) {
                val (session, firstFrame) = try {
                    withTimeout(DIRECT_HANDSHAKE_TIMEOUT_MS) {
                        outgoing.send(WsFrame.Text(PocketJson.encodeToString(Envelope("h", 0L, body = LanHello(paired.deviceId)))))
                        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, B64Url.decode(paired.daemonPub), ByteArray(0))
                        outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
                        val s = awaitHandshake(init)
                        // key confirmation (see class doc): the daemon proves key possession with its first
                        // sealed frame; a decrypt failure = impostor answered the handshake → unreachable
                        s to awaitKeyConfirmation(s)
                    }
                } catch (e: TimeoutCancellationException) {
                    throw DirectUnreachableException("handshake/key-confirmation timeout", reason = DirectFallbackReason.HANDSHAKE_FAILED)
                }
                if (gen != connSeq) throw DeadLinkException() // superseded while handshaking — never touch the shared outbox (#142)
                attempt.handshaken = true
                attempt.timer?.cancel() // established within budget: the timer has nothing left to guard
                connected = true
                liveGen = gen
                control.emit(Attached(Role.DEVICE, paired.accountId))
                firstFrame?.let { inbound.emit(it) } // don't drop the confirming frame (usually DaemonInfo)
                // collapse the reconnect-burst duplicates queued while the link was down (#143); pin frames of
                // another connection go (#362)
                outbox.prepareFor(gen)
                val writer = launch {
                    // superseded mid-drain: an ordinary frame goes back to the live connection, then the writer
                    // dies (#142); a pin frame of this connection is dropped (#362)
                    outbox.runWriter(gen, isCurrent = { gen == connSeq }) { f ->
                        val json = PocketJson.encodeToString(Envelope((nextId++).toString(), 0L, body = f))
                        sendOrDie { outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray())))) }
                    }
                }
                val pinger = launchHeartbeat() // WS ping under sendOrDie — a wedged LAN link dies in ≤10s, not minutes
                var deafRun = 0 // consecutive undecryptable inbound frames (#146 deaf-link detection)
                try {
                    for (frame in incoming) {
                        if (gen != connSeq) break // a stale reader must not emit into the shared inbound flow (#142)
                        if (frame is WsFrame.Binary && Wire.payloadType(frame.data) == Wire.TRANSPORT) {
                            val pt = session.open(Wire.payloadBody(frame.data))
                            if (pt == null) {
                                if (RelayE2EConnection.deafTripped(++deafRun)) { deaf.emit(Unit); deafRun = 0 }
                                continue
                            }
                            deafRun = 0
                            inboundFrames++ // downlink evidence, counted before decode: an undecodable frame still arrived
                            runCatching { PocketJson.decodeFromString<Envelope>(pt.decodeToString()) }
                                .onFailure { Diagnostics.protocolDecodeFailed(it, pt.size.toLong()) }.getOrNull()?.let { inbound.emit(it.body) }
                        }
                    }
                } finally {
                    connected = false
                    writer.cancel(); pinger.cancel()
                }
            }
        }

    suspend fun send(frame: Frame) = outbox.send(frame)

    /** Queue a project-pin frame for connection [expectedConnection] only, without suspending (#362). */
    fun tryEnqueuePin(frame: SyncProjectPins, fence: PinDispatchFence, expectedConnection: Int): PinEnqueueResult =
        outbox.tryEnqueuePin(frame, fence, expectedConnection) { liveGen }

    /** Queue a transient frame for connection [expectedConnection] only, without suspending. The ticket's outcome
     *  says whether it was written; it is never buffered for, or re-routed to, another connection. */
    fun tryEnqueueTransient(frame: Frame, fence: TransientDispatchFence, expectedConnection: Int): TransientTicket =
        outbox.tryEnqueueTransient(frame, fence, expectedConnection) { liveGen }

    /** Frames queued but not yet written (the socket never came up / died first) — the caller re-routes
     *  them to the relay so nothing silently evaporates in a direct→relay fallback. Pin frames are dropped
     *  instead: a pin subscribed on this connection never rides the relay's unrelated subscription (#362). */
    fun drainPending(): List<Frame> = outbox.drainOrdinary()

    private suspend fun DefaultClientWebSocketSession.awaitHandshake(init: E2ESession.Initiator): E2ESession {
        while (true) {
            val f = incoming.receive() as? WsFrame.Binary ?: continue
            if (Wire.payloadType(f.data) == Wire.HANDSHAKE) return init.finish(Wire.payloadBody(f.data))
        }
    }

    /** Waits for the daemon's first sealed frame and proves the derived key opens it. A frame that fails
     *  to decrypt means the handshake was answered by something that doesn't hold the daemon's static key. */
    private suspend fun DefaultClientWebSocketSession.awaitKeyConfirmation(session: E2ESession): Frame? {
        while (true) {
            val f = incoming.receive() as? WsFrame.Binary ?: continue
            if (Wire.payloadType(f.data) != Wire.TRANSPORT) continue
            val pt = session.open(Wire.payloadBody(f.data))
                ?: throw DirectUnreachableException("key confirmation failed", reason = DirectFallbackReason.KEY_MISMATCH)
            inboundFrames++
            return runCatching { PocketJson.decodeFromString<Envelope>(pt.decodeToString()).body }.getOrNull()
        }
    }

    companion object {
        /** Same ceiling as the relay leg — one number the app declares, whichever transport carries the frame. */
        const val MAX_FRAME_BYTES: Long = WIRE_MAX_FRAME_BYTES

        // LAN/loopback: sub-second when reachable. Kept tight so an offline direct address only briefly
        // delays the relay fallback (the user-visible cost of trying direct first).
        private const val DIRECT_HANDSHAKE_TIMEOUT_MS = 3_000L
        /** #403: dial + upgrade + LanHello + Noise + key confirmation, all of it. The handshake timeout above
         *  stays as an inner bound; whichever expires first ends the attempt. */
        const val DIRECT_ESTABLISH_BUDGET_MS = 3_000L
    }
}

/** The direct address didn't pan out (unreachable / refused / handshake failed) — fall back to the relay.
 *  [keyMismatch] means something ANSWERED the handshake but doesn't hold this binding's daemon key — e.g. a
 *  remote daemon advertised its own 127.0.0.1, which on this machine is a DIFFERENT daemon. The caller should
 *  stop dialing that address for this binding (a plain retry can never succeed there). */
class DirectUnreachableException(
    message: String,
    val reason: DirectFallbackReason = DirectFallbackReason.REFUSED,
) : Exception(message) {
    /** Wrong daemon at that address — retries can never succeed, so the caller forgets the URL. */
    val keyMismatch: Boolean get() = reason == DirectFallbackReason.KEY_MISMATCH
}

/** Why a direct attempt fell back to the relay (#403), reported through the existing CONNECTION/CONNECT
 *  diagnostic as its [ErrorCode] — no new ErrorPath, no address or identifier attached. */
enum class DirectFallbackReason(val code: ErrorCode) {
    /** Refused, DNS, TLS, abrupt close, superseded — the socket never carried a handshake. */
    REFUSED(ErrorCode.UNAVAILABLE),
    /** [DirectE2EConnection.DIRECT_ESTABLISH_BUDGET_MS] ran out (black hole, wedged upgrade). */
    BUDGET_EXPIRED(ErrorCode.TIMEOUT),
    /** The socket came up but the handshake/key confirmation didn't finish in time. */
    HANDSHAKE_FAILED(ErrorCode.INCOMPLETE),
    /** Something answered the handshake without the daemon's static key. */
    KEY_MISMATCH(ErrorCode.REJECTED),
}
