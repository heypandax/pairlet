package dev.ccpocket.app.net

import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import io.ktor.client.HttpClient
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

/** Thin WebSocket client to a daemon (local `/v1/ws`) or relay (`/v1/device`). Engine auto-resolves per platform. */
class RelayConnection {
    // ws keepalive + dead-connection detection — see RelayE2EConnection for the rationale (zombie sockets).
    private val client = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = 4L * 1024 * 1024 // accept big frames forwarded from the daemon (matches relay cap)
        }
    }
    private val outbox = Channel<Frame>(Channel.BUFFERED)
    val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    /** Mirrors [RelayE2EConnection.control] so the repository can collect symmetrically. Direct LAN has
     *  no relay control plane (no Attached/PeerPresence), so this stays empty. */
    val control = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
    private var nextId = 0L

    suspend fun connect(url: String) = coroutineScope {
        client.webSocket(urlString = url) {
            val writer = launch {
                for (f in outbox) {
                    outgoing.send(WsFrame.Text(PocketJson.encodeToString(Envelope((nextId++).toString(), 0L, body = f))))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is WsFrame.Text) {
                        val env = runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()) }.getOrNull()
                        if (env != null) inbound.emit(env.body)
                    }
                }
            } finally {
                writer.cancel()
            }
        }
    }

    suspend fun send(frame: Frame) = outbox.send(frame)
}
