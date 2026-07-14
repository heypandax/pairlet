package dev.ccpocket.app.net

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Role
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
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
class DirectE2EConnection {
    private val client = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = 4L * 1024 * 1024 // big history replays travel this path too (matches relay cap)
        }
    }
    private val outbox = Channel<Frame>(Channel.BUFFERED)
    val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    /** Mirrors the relay's control plane just enough for the repo's state machine: a synthetic [Attached]
     *  after the Noise handshake (the daemon IS the peer — no separate presence signal exists or is needed). */
    val control = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
    private var nextId = 0L

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

    /**
     * Dial + handshake, then serve for the life of the socket. Failure BEFORE the handshake completes
     * (refused/unreachable/timeout/bad handshake) throws [DirectUnreachableException] so the caller falls
     * back to the relay in the same connect attempt; a drop AFTER is a normal transport death (reconnect path).
     */
    suspend fun connect(url: String, paired: PairedDaemon, keys: E2ECrypto.KeyPair) = coroutineScope {
        val gen = ++connSeq
        account = paired.accountId
        var handshaken = false
        try {
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
                    throw DirectUnreachableException("handshake/key-confirmation timeout")
                }
                if (gen != connSeq) throw DeadLinkException() // superseded while handshaking — never touch the shared outbox (#142)
                handshaken = true
                connected = true
                control.emit(Attached(Role.DEVICE, paired.accountId))
                firstFrame?.let { inbound.emit(it) } // don't drop the confirming frame (usually DaemonInfo)
                outbox.dedupeBacklog() // collapse the reconnect-burst duplicates queued while the link was down (#143)
                val writer = launch {
                    for (f in outbox) {
                        // superseded mid-drain: hand the frame back to the live connection, then die (#142)
                        if (gen != connSeq) { outbox.send(f); throw DeadLinkException() }
                        val json = PocketJson.encodeToString(Envelope((nextId++).toString(), 0L, body = f))
                        sendOrDie { outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray())))) }
                    }
                }
                val pinger = launchHeartbeat() // WS ping under sendOrDie — a wedged LAN link dies in ≤10s, not minutes
                try {
                    for (frame in incoming) {
                        if (gen != connSeq) break // a stale reader must not emit into the shared inbound flow (#142)
                        if (frame is WsFrame.Binary && Wire.payloadType(frame.data) == Wire.TRANSPORT) {
                            val pt = session.open(Wire.payloadBody(frame.data)) ?: continue
                            runCatching { PocketJson.decodeFromString<Envelope>(pt.decodeToString()) }.getOrNull()?.let { inbound.emit(it.body) }
                        }
                    }
                } finally {
                    connected = false
                    writer.cancel(); pinger.cancel()
                }
            }
        } catch (t: Throwable) {
            connected = false
            // pre-handshake plumbing failures (connection refused, DNS, TLS, abrupt close) all mean the
            // SAME thing to the caller: this address doesn't work right now — fall back, don't error out
            if (!handshaken && t !is CancellationException && t !is DirectUnreachableException) {
                throw DirectUnreachableException(t.message ?: "connect failed")
            }
            throw t
        } finally {
            connected = false
        }
    }

    suspend fun send(frame: Frame) = outbox.send(frame)

    /** Frames queued but not yet written (the socket never came up / died first) — the caller re-routes
     *  them to the relay so nothing silently evaporates in a direct→relay fallback. */
    fun drainPending(): List<Frame> = outbox.drainAll()

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
                ?: throw DirectUnreachableException("key confirmation failed", keyMismatch = true)
            return runCatching { PocketJson.decodeFromString<Envelope>(pt.decodeToString()).body }.getOrNull()
        }
    }

    private companion object {
        // LAN/loopback: sub-second when reachable. Kept tight so an offline direct address only briefly
        // delays the relay fallback (the user-visible cost of trying direct first).
        const val DIRECT_HANDSHAKE_TIMEOUT_MS = 3_000L
    }
}

/** The direct address didn't pan out (unreachable / refused / handshake failed) — fall back to the relay.
 *  [keyMismatch] means something ANSWERED the handshake but doesn't hold this binding's daemon key — e.g. a
 *  remote daemon advertised its own 127.0.0.1, which on this machine is a DIFFERENT daemon. The caller should
 *  stop dialing that address for this binding (a plain retry can never succeed there). */
class DirectUnreachableException(message: String, val keyMismatch: Boolean = false) : Exception(message)
