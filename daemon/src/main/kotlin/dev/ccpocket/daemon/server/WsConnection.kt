package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import io.ktor.websocket.Frame as WsFrame

/**
 * One client socket. An outbound write actor serializes all daemon->client sends; the inbound read
 * pump decodes envelopes and dispatches them without blocking. On disconnect, every conversation
 * this connection opened is reaped (no orphaned claude trees).
 */
class WsConnection(
    private val session: WebSocketSession,
    private val router: RequestRouter,
    private val registry: SessionRegistry,
) {
    private val outbox = Channel<Envelope>(Channel.BUFFERED)
    private val nextId = AtomicLong(0)
    private val owned: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val log = logger("WsConnection")

    private val sink = OutboundSink { frame ->
        outbox.send(Envelope(nextId.getAndIncrement().toString(), System.currentTimeMillis(), body = frame))
    }

    suspend fun serve() = coroutineScope {
        val writer = launch {
            for (env in outbox) {
                session.outgoing.send(WsFrame.Text(PocketJson.encodeToString(env)))
            }
        }
        try {
            for (frame in session.incoming) {
                if (frame is WsFrame.Text) {
                    val text = frame.readText()
                    val env = runCatching { PocketJson.decodeFromString<Envelope>(text) }.getOrNull()
                    if (env != null) {
                        log.info("recv ${env.body::class.simpleName}")
                        launch {
                            try {
                                router.handle(env.body, sink) { owned.add(it) }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                log.warn("handle ${env.body::class.simpleName} failed: ${e.message}")
                                runCatching { sink.emit(PocketError("internal", e.message ?: "request failed")) }
                            }
                        }
                    } else {
                        log.warn("undecodable frame: ${text.take(120)}")
                    }
                }
            }
        } finally {
            outbox.close()
            writer.cancel()
            withContext(NonCancellable) {
                // grace-close, not immediate: a flaky LAN socket / backgrounded phone can reconnect and reattach
                // the still-warm session instead of paying a kill + transcript rewrite + cold resume every blip.
                owned.toList().forEach { runCatching { registry.scheduleClose(it) } }
            }
        }
    }
}
