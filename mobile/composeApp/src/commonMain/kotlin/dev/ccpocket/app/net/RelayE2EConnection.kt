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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import io.ktor.websocket.Frame as WsFrame

/**
 * The device end of the end-to-end channel to a paired daemon, over the relay's opaque binary data
 * plane. Mirrors the (production-verified) JVM test-client: DeviceHello -> Attached -> Noise
 * handshake (initiator) -> AES-GCM transport. The relay only ever sees ciphertext.
 */
class RelayE2EConnection {
    private val client = HttpClient { install(WebSockets) }
    private val outbox = Channel<Frame>(Channel.BUFFERED)
    val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    private var nextId = 0L

    /** @param firstTicket the pairing ticket — supplied as PSK only on the very first connect after pairing. */
    suspend fun connect(paired: PairedDaemon, keys: E2ECrypto.KeyPair, firstTicket: String?) = coroutineScope {
        client.webSocket(urlString = "${paired.relay}/v1/device") {
            outgoing.send(WsFrame.Text(control(DeviceHello(paired.deviceId, paired.credential))))
            awaitAttached()

            val psk = (firstTicket ?: "").encodeToByteArray()
            val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, B64Url.decode(paired.daemonPub), psk)
            outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
            val session = awaitHandshake(init)

            val writer = launch {
                for (f in outbox) {
                    val json = PocketJson.encodeToString(Envelope((nextId++).toString(), 0L, body = f))
                    outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray()))))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is WsFrame.Binary && Wire.payloadType(frame.data) == Wire.TRANSPORT) {
                        val pt = session.open(Wire.payloadBody(frame.data)) ?: continue
                        runCatching { PocketJson.decodeFromString<Envelope>(pt.decodeToString()) }.getOrNull()?.let { inbound.emit(it.body) }
                    }
                }
            } finally {
                writer.cancel()
            }
        }
    }

    suspend fun send(frame: Frame) = outbox.send(frame)

    private suspend fun DefaultClientWebSocketSession.awaitAttached() {
        while (true) {
            val f = incoming.receive() as? WsFrame.Text ?: continue
            when (val b = runCatching { PocketJson.decodeFromString<Envelope>(f.readText()).body }.getOrNull()) {
                is Attached -> return
                is AuthError -> error("relay auth failed: ${b.code}")
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
