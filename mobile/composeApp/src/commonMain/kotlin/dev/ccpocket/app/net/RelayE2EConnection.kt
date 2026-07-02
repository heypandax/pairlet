package dev.ccpocket.app.net

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.readText
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
 * The device end of the end-to-end channel to a paired daemon, over the relay's opaque binary data
 * plane. Mirrors the (production-verified) JVM test-client: DeviceHello -> Attached -> Noise
 * handshake (initiator) -> AES-GCM transport. The relay only ever sees ciphertext.
 */
class RelayE2EConnection {
    // pingInterval matches the daemon's relay leg (RelayClient): without it Ktor never pings, so a silently
    // dead ws (iOS foreground-idle, NAT/relay idle timeout, network switch) is a zombie until the OS TCP
    // stack eventually errors — minutes of "connected but can't reach the computer". The ping doubles as
    // keepalive AND triggers a fast close (no pong → connect() returns → the repo's backoff reconnects).
    private val client = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = 4L * 1024 * 1024 // accept big frames forwarded from the daemon, e.g. long transcript history replays (matches relay cap)
        }
    }
    private val outbox = Channel<Frame>(Channel.BUFFERED)
    // relay control-plane (TEXT) frames the device originates — e.g. RegisterPush. Buffered across
    // reconnects like [outbox]; the per-connection writer drains it once a socket is live.
    private val controlOutbox = Channel<dev.ccpocket.protocol.ToRelay>(Channel.BUFFERED)
    val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    /** Relay control-plane frames (Attached, AuthError, PeerPresence) — NOT E2E daemon traffic. The
     *  repository reads this to drive an honest connection state (e.g. "computer offline"). */
    val control = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
    private var nextId = 0L

    /** @param firstTicket the pairing ticket — supplied as PSK only on the very first connect after pairing. */
    suspend fun connect(paired: PairedDaemon, keys: E2ECrypto.KeyPair, firstTicket: String?) = coroutineScope {
        client.webSocket(urlString = "${paired.relay}/v1/device") {
            // Bound the whole pre-heartbeat prelude: the repo's connect watchdog only guards up to Attached,
            // and the pinger below isn't armed yet — a link that wedges BETWEEN Attached and the Noise
            // handshake would otherwise hang forever with no guard. Rethrown as DeadLinkException because a
            // TimeoutCancellationException IS a CancellationException, which the repo reads as intentional
            // teardown and would swallow without reconnecting.
            val session = try {
                withTimeout(HANDSHAKE_TIMEOUT_MS) {
                    outgoing.send(WsFrame.Text(control(DeviceHello(paired.deviceId, paired.credential))))
                    awaitAttached()
                    val psk = (firstTicket ?: "").encodeToByteArray()
                    val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, B64Url.decode(paired.daemonPub), psk)
                    outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
                    awaitHandshake(init)
                }
            } catch (e: TimeoutCancellationException) {
                throw DeadLinkException()
            }

            val writer = launch {
                for (f in outbox) {
                    val json = PocketJson.encodeToString(Envelope((nextId++).toString(), 0L, body = f))
                    sendOrDie { outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray())))) }
                }
            }
            // control frames (e.g. RegisterPush) ride the TEXT plane in the clear — the relay parses these
            val ctrlWriter = launch { for (c in controlOutbox) sendOrDie { outgoing.send(WsFrame.Text(control(c))) } }
            // Heartbeat (see LinkHealth.launchHeartbeat): an idle-link WS ping under sendOrDie, so a wedged socket
            // (network switch / NAT / relay idle-drop) trips the write timeout and reconnects. Ktor's own ping
            // can't catch this — it rides the same outgoing path and wedges too. The relay's ktor auto-pongs it.
            val pinger = launchHeartbeat()
            try {
                for (frame in incoming) when {
                    frame is WsFrame.Binary && Wire.payloadType(frame.data) == Wire.TRANSPORT -> {
                        val pt = session.open(Wire.payloadBody(frame.data)) ?: continue
                        runCatching { PocketJson.decodeFromString<Envelope>(pt.decodeToString()) }.getOrNull()?.let { inbound.emit(it.body) }
                    }
                    // relay control frames ride the TEXT plane after the handshake (e.g. PeerPresence)
                    frame is WsFrame.Text ->
                        runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull()?.let { control.emit(it) }
                    else -> {}
                }
            } finally {
                writer.cancel(); ctrlWriter.cancel(); pinger.cancel()
            }
        }
    }

    suspend fun send(frame: Frame) = outbox.send(frame)

    /** Send a relay control-plane frame (e.g. RegisterPush) on the TEXT plane. Buffers until connected. */
    suspend fun sendControl(frame: dev.ccpocket.protocol.ToRelay) = controlOutbox.send(frame)

    private suspend fun DefaultClientWebSocketSession.awaitAttached() {
        while (true) {
            val f = incoming.receive() as? WsFrame.Text ?: continue
            when (val b = runCatching { PocketJson.decodeFromString<Envelope>(f.readText()).body }.getOrNull()) {
                is Attached -> { control.emit(b); return }
                is AuthError -> { control.emit(b); throw RelayAuthException(b.code) }
                else -> {}
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitHandshake(init: E2ESession.Initiator): E2ESession {
        while (true) {
            val f = incoming.receive() as? WsFrame.Binary ?: continue
            if (Wire.payloadType(f.data) == Wire.HANDSHAKE) return init.finish(Wire.payloadBody(f.data))
        }
    }

    private fun control(frame: dev.ccpocket.protocol.ToRelay): String =
        PocketJson.encodeToString(Envelope("h", 0L, to = Route.RELAY, body = frame))
}

/** The relay rejected our device credential — re-pairing is required (not a transient network error). */
class RelayAuthException(val code: String) : Exception("relay auth failed: $code")
