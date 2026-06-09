package dev.ccpocket.daemon

import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Base64
import io.ktor.websocket.Frame as WsFrame

/**
 * A minimal REPL that drives the daemon over a WebSocket. Two modes:
 *  - direct: plaintext [Envelope]s straight to a daemon's local `/v1/ws`.
 *  - relay: a full device simulator — generate a key, redeem a pairing ticket, then run the
 *    end-to-end Noise channel over the relay's opaque binary data plane.
 */
class TestClient private constructor(
    private val directUrl: String?,
    private val relayWs: String?,
    private val daemonPubB64: String?,
    private val ticket: String?,
) {
    @Volatile private var convoId: String? = null
    @Volatile private var askId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    private var nextId = 0L

    fun run() = runBlocking { if (relayWs != null) runRelay() else runDirect() }

    // ---- direct mode (local daemon, plaintext) ----

    private suspend fun runDirect() {
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            client.webSocket(urlString = directUrl!!) {
                val reader = launch {
                    for (frame in incoming) if (frame is WsFrame.Text)
                        runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull()?.let(::render)
                }
                commandLoop { body -> outgoing.send(WsFrame.Text(PocketJson.encodeToString(envelope(body)))) }
                reader.cancel()
            }
        } finally {
            client.close()
        }
    }

    // ---- relay mode (full device: redeem + E2E) ----

    private suspend fun runRelay() {
        val keys = E2ECrypto.generateKeyPair()
        val (deviceId, credential) = redeem(keys.publicRaw)
        println("[device] redeemed: id=${deviceId.take(8)}…")

        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            client.webSocket(urlString = "$relayWs/v1/device") {
                login(deviceId, credential)
                val session = e2eHandshake(keys)
                println("[device] E2E channel up")

                val reader = launch {
                    for (frame in incoming) {
                        if (frame !is WsFrame.Binary) continue
                        if (Wire.payloadType(frame.data) != Wire.TRANSPORT) continue
                        val plain = session.open(Wire.payloadBody(frame.data)) ?: continue
                        runCatching { PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body }.getOrNull()?.let(::render)
                    }
                }
                commandLoop { body ->
                    outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(envelope(body)).encodeToByteArray()))))
                }
                reader.cancel()
            }
        } finally {
            client.close()
        }
    }

    /** POST /v1/pair/redeem with our public key; returns (deviceId, credential). */
    private suspend fun redeem(devicePub: ByteArray): Pair<String, String> {
        val http = HttpClient(CIO)
        try {
            val httpBase = relayWs!!.replace("ws://", "http://").replace("wss://", "https://")
            val body = http.post("$httpBase/v1/pair/redeem") {
                setBody("""{"ticket":"$ticket","devicePubKey":"${b64u(devicePub)}"}""")
            }.bodyAsText()
            val id = FIELD("deviceId").find(body)?.groupValues?.get(1)
            val cred = FIELD("credential").find(body)?.groupValues?.get(1)
            require(id != null && cred != null) { "redeem failed: $body" }
            return id to cred
        } finally {
            http.close()
        }
    }

    private suspend fun DefaultClientWebSocketSession.login(deviceId: String, credential: String) {
        outgoing.send(WsFrame.Text(PocketJson.encodeToString(Envelope("h", 0L, to = Route.RELAY, body = DeviceHello(deviceId, credential)))))
        while (true) {
            val frame = incoming.receive() as? WsFrame.Text ?: continue
            when (val body = runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull()) {
                is Attached -> { println("[relay] attached to account ${body.accountId}"); return }
                is AuthError -> error("relay auth failed: ${body.code}")
                else -> {}
            }
        }
    }

    /** initiator handshake: send our ephemeral, await the daemon's, derive the session. */
    private suspend fun DefaultClientWebSocketSession.e2eHandshake(keys: E2ECrypto.KeyPair): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, b64uDec(daemonPubB64!!), psk = ticket!!.encodeToByteArray())
        outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
        while (true) {
            val frame = incoming.receive() as? WsFrame.Binary ?: continue
            if (Wire.payloadType(frame.data) == Wire.HANDSHAKE) return init.finish(Wire.payloadBody(frame.data))
        }
    }

    // ---- shared REPL ----

    private fun envelope(body: Frame) = Envelope((nextId++).toString(), 0L, body = body)

    private suspend fun commandLoop(send: suspend (Frame) -> Unit) {
        println(HELP)
        val br = System.`in`.bufferedReader()
        while (true) {
            print("\n> "); System.out.flush()
            val line = withContext(Dispatchers.IO) { br.readLine() } ?: break
            val trimmed = line.trim()
            val sp = trimmed.indexOf(' ')
            val cmd = if (sp == -1) trimmed else trimmed.substring(0, sp)
            val arg = if (sp == -1) null else trimmed.substring(sp + 1).trim()
            when (cmd) {
                "" -> {}
                "quit", "q" -> break
                "help", "?" -> println(HELP)
                "dirs" -> send(ListDirectories())
                "ls" -> if (arg != null) send(ListSessions(arg)) else println("usage: ls <workdir>")
                "open" -> {
                    val a = arg?.split(Regex("\\s+")) ?: emptyList()
                    val wd = a.getOrNull(0)
                    if (wd == null) println("usage: open <workdir> [resumeId]")
                    else send(OpenSession(workdir = wd, resumeId = a.getOrNull(1), mode = mode))
                }
                "say" -> { val c = convoId; if (c != null && arg != null) send(SendPrompt(c, arg)) else println("(no convo / text)") }
                "cd" -> { val c = convoId; if (c != null && arg != null) send(SwitchDirectory(c, arg)) else println("(no convo / dir)") }
                "allow" -> {
                    val c = convoId; val a = askId
                    if (c != null && a != null) { send(PermissionVerdict(c, a, Decision.ALLOW)); askId = null } else println("(no pending ask)")
                }
                "deny" -> {
                    val c = convoId; val a = askId
                    if (c != null && a != null) { send(PermissionVerdict(c, a, Decision.DENY, message = arg)); askId = null } else println("(no pending ask)")
                }
                "mode" -> {
                    val m = arg?.let { runCatching { PocketJson.decodeFromString<PermissionMode>("\"$it\"") }.getOrNull() }
                    if (m != null) { mode = m; println("mode = $arg (applies to next open)") } else println("usage: mode <default|acceptEdits|auto|plan|dontAsk|bypassPermissions>")
                }
                else -> println("unknown: $cmd")
            }
        }
    }

    private fun render(body: Frame) {
        when (body) {
            is SessionLive -> { convoId = body.convoId; println("\n[live] convo=${body.convoId.take(8)} session=${body.sessionId?.take(8) ?: "(new)"} cwd=${body.workdir}") }
            is Directories -> { println("\n[dirs]"); body.entries.forEach { println("  ${if (it.recent) "*" else " "} ${it.path}${if (it.hasSessions) "  (history)" else ""}") } }
            is Sessions -> { println("\n[sessions] ${body.workdir}"); body.items.forEach { println("  ${it.sessionId.take(8)}  msgs=${it.messageCount} branch=${it.gitBranch ?: "-"}  ${it.title}") } }
            is AssistantChunk -> { when (val p = body.piece) { is StreamPiece.Text -> print(p.text); is StreamPiece.Thinking -> print("[2m${p.text}[0m") }; System.out.flush() }
            is ToolEvent -> println("\n[36m[tool] ${body.tool} ${body.inputPreview ?: ""}[0m")
            is PermissionAsk -> { askId = body.askId; println("\n[33m[ASK] ${body.inputPreview}[0m   -> type: allow / deny") }
            is TurnDone -> println("\n[2m[done] in=${body.usage?.inputTokens ?: 0} out=${body.usage?.outputTokens ?: 0}[0m")
            is PocketError -> println("\n[31m[error:${body.code}] ${body.message}[0m")
            else -> println("\n[frame] $body")
        }
    }

    companion object {
        fun direct(url: String) = TestClient(url, null, null, null)
        fun relay(relayWs: String, daemonPubB64: String, ticket: String) = TestClient(null, relayWs, daemonPubB64, ticket)

        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()
        private fun b64u(b: ByteArray) = B64.encodeToString(b)
        private fun b64uDec(s: String) = B64D.decode(s)
        private fun FIELD(name: String) = Regex(""""$name"\s*:\s*"([^"]*)"""")

        private const val HELP =
            "commands: dirs | ls <wd> | open <wd> [resumeId] | say <text> | cd <wd> | allow | deny [reason] | mode <m> | quit"
    }
}
