package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.util.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaks the DeepSeek Harness `web` profile's local API (issue #255): a downlink WebSocket for events
 * plus plain HTTP POSTs for requests.
 *
 * ```
 *   WS   ws://127.0.0.1:<port>/api/events.mux      ← every session's events, push only
 *   HTTP POST http://127.0.0.1:<port>/api/<method> → RPC   (session.create, session.prompt, …)
 *   HTTP POST http://127.0.0.1:<port>/api/respond  → answer an approval/question server-request
 * ```
 *
 * Five source-verified facts shape this class, each of which fails confusingly if ignored:
 *  1. **The RPC path carries the method name.** It is `POST /api/session.prompt`, not a single `/api/rpc`
 *     endpoint — and the body's `method` field must EQUAL the path segment or dsh answers `bad-request`.
 *  2. **`content-type: application/json` is mandatory.** Anything else is a 415; the browser-trust fence
 *     relies on it (a form content-type would be simple-request forgeable).
 *  3. **Business errors are HTTP 200.** A 2xx only means the carrier worked; the verdict is in
 *     `result.ok`. Treating 200 as success is the classic way to swallow every real failure.
 *  4. **The WebSocket is DOWNLINK-ONLY.** Sending anything on it gets the socket closed with 1008, which
 *     would look exactly like a random disconnect. All uplink goes over HTTP.
 *  5. **Authorization is the header fence, not a token.** A loopback `Host` (and, if present, a matching
 *     `Origin`) is the whole credential — so there is no handshake, no clientId and no session token.
 *
 * Every failure degrades: a refused connection, a dead process or a malformed frame is logged and
 * surfaced through [onFatal], never thrown into the daemon.
 */
class DshApiClient(
    private val port: Int,
    private val scope: CoroutineScope,
    /** Raw MuxFrame JSON, in arrival order. The backend re-injects these into the Conversation pump. */
    private val onFrame: suspend (String) -> Unit,
    /** Terminal transport failure — the session cannot continue. */
    private val onFatal: suspend (String) -> Unit,
) {
    private val log = logger("DshApiClient")
    private val base = "http://127.0.0.1:$port"
    private val closed = AtomicBoolean(false)

    @Volatile private var wsJob: Job? = null

    private val http = HttpClient(CIO) {
        install(WebSockets)
        expectSuccess = false // business errors ride a 200; carrier faults are inspected explicitly
    }

    /** True once the mux socket has been established at least once. */
    @Volatile var connected: Boolean = false
        private set

    /**
     * Open the mux socket, retrying while dsh finishes booting.
     *
     * The retry window exists because the port is known from dsh's OWN boot line, which it prints when
     * the listener is up — but the WebSocket route can still 404/refuse for a moment while the app
     * finishes mounting. Bounded, so a genuinely broken server surfaces instead of retrying forever.
     */
    fun start() {
        if (closed.get()) return
        wsJob = scope.launch(CoroutineName("dsh-mux-$port")) {
            var attempt = 0
            while (isActive && !closed.get() && attempt < CONNECT_ATTEMPTS) {
                attempt += 1
                val ok = runCatching { pump() }.onFailure {
                    log.info("dsh mux connect attempt $attempt/$CONNECT_ATTEMPTS failed: ${it.message}")
                }.isSuccess
                // A clean return from pump() means the socket closed. If we never got connected at all,
                // keep retrying the boot window; once we HAVE been connected, a close is terminal for
                // this process — dsh does not resume a mux stream, and the Conversation relaunches.
                if (connected) {
                    if (!closed.get()) onFatal("the dsh event stream closed")
                    return@launch
                }
                if (ok) return@launch
                delay(CONNECT_RETRY_MS)
            }
            if (!connected && !closed.get()) {
                onFatal("could not reach the dsh local API on 127.0.0.1:$port after $CONNECT_ATTEMPTS attempts")
            }
        }
    }

    private suspend fun pump() {
        http.webSocket(
            urlString = "ws://127.0.0.1:$port$MUX_PATH",
            request = {
                // The trust fence keys on these: Host must be a loopback authority, and an Origin (if
                // sent) must match it exactly. Ktor sets Host itself; Origin is set to the same value so
                // the request can never look cross-site.
                header("Origin", base)
            },
        ) {
            connected = true
            log.info("dsh mux connected on $port")
            for (frame in incoming) {
                // Never send on this socket — dsh closes it with 1008 "downlink only".
                if (frame !is Frame.Text) continue
                val text = runCatching { frame.readText() }.getOrNull() ?: continue
                runCatching { onFrame(text) }
                    .onFailure { log.warn("dsh frame handler threw: ${it.message}") }
            }
        }
    }

    /**
     * One RPC. Returns the `result` object — `{ok:true,value:…}` or `{ok:false,error:…}` — or null when
     * the carrier itself failed (connection refused, non-200, unparseable body).
     *
     * The caller is expected to inspect `ok`: a non-null return does NOT mean the call succeeded.
     */
    suspend fun rpc(method: String, payload: JsonObject): JsonObject? {
        if (closed.get()) return null
        val rpcId = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", method) // MUST match the path segment below
            put("payload", payload)
        }
        return runCatching {
            val response = http.post("$base$API_PREFIX/$method") {
                contentType(ContentType.Application.Json) // 415 without this
                header("Origin", base)
                setBody(body.toString())
            }
            if (response.status != HttpStatusCode.OK) {
                log.warn("dsh rpc $method → HTTP ${response.status}")
                return null
            }
            val root = DshTranscript.json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: return null
            // dsh echoes the rpcId; a mismatch means we are reading someone else's answer, which is
            // never a benign condition — refuse it rather than acting on the wrong result.
            val echoed = root.str("rpcId")
            if (echoed != null && echoed != rpcId) {
                log.warn("dsh rpc $method rpcId mismatch (sent $rpcId, got $echoed)")
                return null
            }
            root.obj("result")
        }.getOrElse {
            log.warn("dsh rpc $method failed: ${it.message}")
            null
        }
    }

    /**
     * Answer a host→client server-request (an approval or a question). [value] is the method-specific
     * payload; dsh replies with an `RpcReceipt` — `{accepted, reason?}` — NOT a result envelope.
     *
     * The REASON is returned, not just the boolean, because the two failures mean opposite things and the
     * caller must treat them differently: `not-pending` is a benign race (another client claimed it first,
     * or the turn was cancelled), while `bad-response` means we built a shape dsh refused — the turn is
     * still hanging and somebody has to be told.
     */
    suspend fun respond(rpcId: String, value: JsonObject): DshRespond {
        if (closed.get()) return DshRespond.UNREACHABLE
        val body = buildJsonObject {
            put("type", "client-response")
            put("rpcId", rpcId)
            put("result", buildJsonObject { put("ok", true); put("value", value) })
        }
        return runCatching {
            val response = http.post("$base$API_PREFIX/respond") {
                contentType(ContentType.Application.Json)
                header("Origin", base)
                setBody(body.toString())
            }
            // Same rule as [rpc]: a non-200 is a CARRIER fault, not dsh's verdict on our shape. Reporting
            // it as a refusal would tell the user they built a bad response when the request never landed.
            if (response.status != HttpStatusCode.OK) {
                log.warn("dsh respond $rpcId → HTTP ${response.status}")
                return DshRespond.UNREACHABLE
            }
            val root = DshTranscript.json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            val accepted = root?.get("accepted")?.toString() == "true"
            val reason = root?.str("reason")
            if (!accepted) log.warn("dsh respond $rpcId not accepted: $reason")
            DshRespond(accepted, reason)
        }.getOrElse {
            log.warn("dsh respond $rpcId failed: ${it.message}")
            DshRespond.UNREACHABLE
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        wsJob?.cancel()
        runCatching { http.close() }
    }

    private companion object {
        const val API_PREFIX = "/api"
        const val MUX_PATH = "/api/events.mux"

        /** dsh's listener is already up when it prints the port, so this only covers the app finishing
         *  its mount — a few seconds at most. */
        const val CONNECT_ATTEMPTS = 20
        const val CONNECT_RETRY_MS = 250L
    }
}
