package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.conversation.PushHook
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.Challenge
import dev.ccpocket.protocol.DaemonAuth
import dev.ccpocket.protocol.DaemonHello
import dev.ccpocket.protocol.DevicePaired
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.NotifyPush
import dev.ccpocket.protocol.PairBegin
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.Ping
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Pong
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.ToRelay
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicLong
import io.ktor.websocket.Frame as WsFrame

/**
 * The daemon's outbound connection to the cloud relay. Authenticates by signing the relay's challenge
 * with its Ed25519 static key, then runs end-to-end-encrypted [DeviceSessions] over the opaque BINARY
 * data plane (the relay only routes ciphertext) and a small TEXT control plane for pairing.
 * Reconnects with exponential backoff.
 */
class RelayClient(
    private val relayWsBase: String, // e.g. ws://127.0.0.1:9000
    private val identity: Identity,
    private val core: DaemonCore,
) {
    private val log = logger("RelayClient")
    // keepalive ping well under Cloudflare's ~100s idle WS timeout, so the relay link stays up
    private val client = HttpClient(CIO) { install(WebSockets) { pingIntervalMillis = 20_000 } }

    private val controlOutbox = Channel<ToRelay>(Channel.BUFFERED)
    private val inboundControl = MutableSharedFlow<ToRelay>(extraBufferCapacity = 32)
    private val ctrlId = AtomicLong(0)

    @Volatile private var dataOut: Channel<ByteArray>? = null
    @Volatile private var peerOnline = false
    @Volatile private var lastPongAt = 0L  // last app-level Pong from the relay (heartbeat liveness)
    @Volatile private var sawPong = false  // this relay speaks Pong -> only then enforce the dead-link timeout
    private val sessions = DeviceSessions(core, identity) { deviceId, payload ->
        dataOut?.send(Wire.wrapDevice(deviceId, payload))
    }

    val accountId: String get() = identity.accountId

    suspend fun run() = coroutineScope {
        // wake an offline phone when a turn completes. peerOnline gates it here (an attached phone got the
        // TurnDone over the data plane already); the relay re-checks deviceCount before actually pushing.
        core.registry.pushHook = PushHook { workdir, finalText ->
            if (!peerOnline) controlOutbox.send(
                NotifyPush(
                    title = workdir.fileName?.toString() ?: "CC Pocket",
                    body = finalText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(140) ?: "Turn complete",
                ),
            )
        }
        launch { reaperLoop() } // reclaim sessions abandoned while the phone is offline
        var backoff = 1_000L
        while (true) {
            try {
                connectOnce()
                backoff = 1_000L
            } catch (t: Throwable) {
                log.warn("relay connection lost (${t.message}); retry in ${backoff}ms")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /** While no phone is attached, reclaim conversations idle longer than [IDLE_REAP_MS]. */
    private suspend fun reaperLoop() {
        while (true) {
            delay(60_000)
            if (!peerOnline) {
                val n = core.registry.reapIdle(IDLE_REAP_MS)
                if (n > 0) log.info("reaped $n idle background session(s)")
            }
        }
    }

    /** Ask the relay to mint a pairing ticket, and remember it as the next device's handshake PSK. */
    suspend fun mintTicket(): PairTicket? {
        controlOutbox.send(PairBegin(identity.e2ePubB64)) // relay needs our E2E pub to serve the code path
        return withTimeoutOrNull(10_000) { inboundControl.filterIsInstance<PairTicket>().first() }
            ?.also { sessions.onMintedTicket(it.ticket) }
    }

    private suspend fun connectOnce() {
        client.webSocket(urlString = "$relayWsBase/v1/daemon") {
            authenticate()
            log.info("attached to relay as daemon (account=${identity.accountId})")

            val outbox = Channel<ByteArray>(Channel.BUFFERED)
            dataOut = outbox
            val dataWriter = launch { for (bytes in outbox) outgoing.send(WsFrame.Binary(true, bytes)) }
            val ctrlWriter = launch { for (c in controlOutbox) outgoing.send(WsFrame.Text(controlText(c))) }
            // App-level heartbeat: a half-open/zombie link keeps the TCP socket ESTABLISHED and Ktor's
            // transport ping satisfied, yet no Pong returns through the dead relay app. Force a reconnect.
            sawPong = false; lastPongAt = System.currentTimeMillis()
            val heartbeat = launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    controlOutbox.send(Ping(System.currentTimeMillis()))
                    if (sawPong && System.currentTimeMillis() - lastPongAt > HEARTBEAT_DEAD_MS) {
                        log.warn("relay heartbeat timeout (no pong in ${HEARTBEAT_DEAD_MS}ms); forcing reconnect")
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "heartbeat timeout")); break
                    }
                }
            }
            try {
                for (frame in incoming) when (frame) {
                    is WsFrame.Binary -> Wire.unwrapDevice(frame.data)?.let { (deviceId, payload) ->
                        sessions.onFrame(deviceId, payload) // sequential: preserves per-device frame order
                    }
                    is WsFrame.Text -> onControl(runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull())
                    else -> {}
                }
            } finally {
                dataOut = null
                outbox.close(); dataWriter.cancel(); ctrlWriter.cancel(); heartbeat.cancel()
                withContext(NonCancellable) { sessions.onDisconnect() }
            }
        }
    }

    /** DaemonHello -> Challenge -> DaemonAuth -> Attached. Throws on rejection (caller retries). */
    private suspend fun DefaultClientWebSocketSession.authenticate() {
        outgoing.send(WsFrame.Text(controlText(DaemonHello(identity.accountId, identity.ed25519PubB64))))
        val challenge = nextControl() as? Challenge ?: error("expected challenge")
        outgoing.send(WsFrame.Text(controlText(DaemonAuth(identity.signChallenge(challenge.nonce)))))
        when (val r = nextControl()) {
            is Attached -> {}
            is AuthError -> error("relay rejected auth: ${r.code}")
            else -> error("expected attached, got ${r?.let { it::class.simpleName }}")
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextControl(): ToRelay? {
        val frame = incoming.receive() as? WsFrame.Text ?: return null
        return runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull() as? ToRelay
    }

    private suspend fun onControl(body: Any?) {
        when (body) {
            is PairTicket -> inboundControl.emit(body)
            is DevicePaired -> sessions.onDevicePaired(body.deviceId, body.devicePubKey)
            is PeerPresence -> { peerOnline = body.online; log.info("peer ${if (body.online) "online" else "offline"}") }
            is Pong -> { if (!sawPong) log.info("relay heartbeat armed (pong received)"); sawPong = true; lastPongAt = System.currentTimeMillis() }
            else -> {}
        }
    }

    private fun controlText(frame: ToRelay): String =
        PocketJson.encodeToString(Envelope(ctrlId.getAndIncrement().toString(), 0L, to = Route.RELAY, body = frame))

    private companion object {
        const val IDLE_REAP_MS = 15 * 60 * 1000L  // 15 min idle -> reclaim
        const val HEARTBEAT_INTERVAL_MS = 20_000L // app-level Ping cadence (relay echoes Pong)
        const val HEARTBEAT_DEAD_MS = 45_000L     // no Pong within this (after one was seen) -> reconnect
    }
}
