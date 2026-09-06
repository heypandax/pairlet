package dev.ccpocket.daemon.dsh

import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for a `dsh --profile web` host, speaking exactly the three carrier facts [DshApiClient]
 * depends on: RPC is `POST /api/<method>`, the answer is a `server-response` envelope echoing the rpcId,
 * and business errors ride an HTTP 200 with `ok:false`. Plus the downlink `/api/events.mux`, which the
 * backend treats as its readiness signal.
 *
 * WHY A REAL SERVER: the ordering guarantees issue #333 turns on — an `agentPreset.select` that lands
 * BEFORE the first `session.prompt`, a `session.selectModel` that lands after `session.create` — are
 * properties of the RPC SEQUENCE the backend issues over a live socket. A hand-stubbed client would let
 * a reordering pass, which is precisely the regression (a preset selected one RPC too late is silently
 * refused as `agent-preset-locked` and the session runs on the wrong persona).
 */
class FakeDshHost(
    /** method -> the `result` object to answer with, as a JSON string. */
    private val handlers: Map<String, (JsonObject) -> String>,
) {
    /** Every method the backend called, in call order — the ordering assertions read this. */
    val calls = CopyOnWriteArrayList<String>()

    /** Every payload the backend sent, same order as [calls]. */
    val payloads = CopyOnWriteArrayList<JsonObject>()

    /** Signalled once the backend's mux socket is up, so a test never races the readiness poll. */
    val muxConnected = Channel<Unit>(Channel.CONFLATED)

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    var port: Int = 0
        private set

    suspend fun start(): FakeDshHost {
        val engine = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/api/events.mux") {
                    muxConnected.trySend(Unit)
                    // Downlink only, and this host pushes nothing: hold the socket open so the backend
                    // reads "connected", not "the stream closed" (which is its fatal path).
                    for (frame in incoming) Unit
                }
                post("/api/{method}") {
                    val method = call.parameters["method"].orEmpty()
                    val body = DshTranscript.json.parseToJsonElement(call.receiveText()) as JsonObject
                    val rpcId = body.str("rpcId").orEmpty()
                    val payload = body.obj("payload") ?: JsonObject(emptyMap())
                    calls += method
                    payloads += payload
                    val result = handlers[method]?.invoke(payload)
                        ?: """{"ok":false,"error":{"code":"not-found","message":"no handler for $method"}}"""
                    call.respondText(
                        """{"type":"server-response","rpcId":"$rpcId","result":$result}""",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
            }
        }
        engine.start(wait = false)
        server = engine
        port = engine.engine.resolvedConnectors().first().port
        return this
    }

    /** The stdout line the backend learns the port from — feeding this to `parse` is what starts it all. */
    fun bootLine(): String = "dsh web: http://127.0.0.1:$port"

    fun stop() {
        server?.stop(0, 0)
    }

    companion object {
        fun ok(value: String) = """{"ok":true,"value":$value}"""
        fun err(code: String, message: String) =
            """{"ok":false,"error":{"code":"$code","message":"$message"}}"""

        /** The `llm.models` / `session.models` group listing this daemon's local dsh really returns
         *  (probe-verified 2026-09-07, dsh 0.1.0-rc.6). */
        const val GROUPS = """
            [{"id":"deepseek-official","name":"DeepSeek","models":[
              {"id":"deepseek-v4-flash","name":"DeepSeek-V4-Flash",
               "reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"},
                                       {"id":"max","name":"Max"}],"defaultEffort":"high"}},
              {"id":"deepseek-v4-pro","name":"DeepSeek-V4-Pro",
               "reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"},
                                       {"id":"max","name":"Max"}],"defaultEffort":"high"}}]}]
        """

        const val PRESETS = """
            {"presets":[
              {"id":"standard","trust":"system","isDefault":true,"name":"标准模式","description":"功能完整的编码 Agent。"},
              {"id":"code","trust":"system","isDefault":false,"name":"PTC 模式","description":"Code Mode SDK。"},
              {"id":"minimal","trust":"system","isDefault":false,"name":"极简模式","description":"双工具编码 Agent。"},
              {"id":"mine","trust":"user","isDefault":false,"name":"我的 preset","description":"用户自建。"}],
             "authorable":true,"hasDocument":true}
        """
    }
}
