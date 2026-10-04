package dev.ccpocket.relay

import dev.ccpocket.protocol.DaemonAuth
import dev.ccpocket.protocol.Challenge
import dev.ccpocket.protocol.DaemonHello
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.relay.auth.Codec
import dev.ccpocket.relay.store.Device
import dev.ccpocket.relay.store.RelayStore
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real-socket harness for relay tests: a [RelayServer] on an ephemeral loopback port, driven by the JDK's
 * own WebSocket client (no extra test dependency). [Peer.reading] = false gives a peer that never asks for
 * a message, i.e. a client that stops reading its socket — the slow-consumer shape.
 */
internal class RelayWsHarness(val relay: RelayServer) : AutoCloseable {
    private val engine: EmbeddedServer<*, *> = relay.server().also { it.start(wait = false) }
    val port: Int = runBlocking { engine.engine.resolvedConnectors().single().port }
    private val http = HttpClient.newHttpClient()

    sealed interface In {
        data class Text(val text: String) : In
        data class Binary(val bytes: ByteArray) : In
        data class Closed(val code: Int, val reason: String) : In
        data class Failed(val error: Throwable) : In
    }

    inner class Peer(path: String, val reading: Boolean = true) {
        val inbox = LinkedBlockingQueue<In>()
        val closed = CompletableFuture<In.Closed>()
        private val text = StringBuilder()
        private val bin = ByteArrayOutputStream()
        val ws: WebSocket = http.newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:$port$path"), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) { if (reading) webSocket.request(1) }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    text.append(data)
                    if (last) { inbox += In.Text(text.toString()); text.setLength(0) }
                    webSocket.request(1); return null
                }
                override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                    val b = ByteArray(data.remaining()); data.get(b); bin.write(b)
                    if (last) { inbox += In.Binary(bin.toByteArray()); bin.reset() }
                    webSocket.request(1); return null
                }
                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    In.Closed(statusCode, reason).also { inbox += it; closed.complete(it) }; return null
                }
                override fun onError(webSocket: WebSocket, error: Throwable) {
                    inbox += In.Failed(error); closed.complete(In.Closed(-1, error.toString()))
                }
            }).get(5, TimeUnit.SECONDS)

        fun sendControl(body: Frame) {
            ws.sendText(PocketJson.encodeToString(Envelope(id = "t", ts = 0, to = Route.RELAY, body = body)), true).get(5, TimeUnit.SECONDS)
        }

        fun sendBinary(bytes: ByteArray) { ws.sendBinary(ByteBuffer.wrap(bytes), true).get(30, TimeUnit.SECONDS) }

        fun next(timeoutMs: Long = 5_000): In? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

        /** The next TEXT control frame's body, skipping nothing else (a binary/close fails the cast). */
        inline fun <reified T : Frame> expectControl(timeoutMs: Long = 5_000): T {
            val got = next(timeoutMs) ?: error("no frame within ${timeoutMs}ms")
            val t = (got as? In.Text)?.text ?: error("expected ${T::class.simpleName}, got $got")
            return PocketJson.decodeFromString<Envelope>(t).body as? T ?: error("expected ${T::class.simpleName}, got $t")
        }
    }

    fun device(reading: Boolean = true) = Peer("/v1/device", reading)
    fun daemon(reading: Boolean = true) = Peer("/v1/daemon", reading)

    override fun close() { engine.stop(100, 500) }

    companion object {
        private fun secretOf(deviceId: String) = Codec.sha256(deviceId.encodeToByteArray())

        /** The login for a device seeded by [seedDevice] (the secret is derived from the id). */
        fun helloFor(deviceId: String) = DeviceHello(deviceId, Codec.b64uEnc(secretOf(deviceId)))

        /** A paired device row with a known bearer secret; returns the hello to log in with. */
        suspend fun seedDevice(store: RelayStore, account: String, deviceId: String, headless: Boolean = false): DeviceHello {
            if (store.getAccount(account) == null) store.insertAccount(account, ByteArray(32), 1)
            store.insertDevice(Device(deviceId, account, ByteArray(32), Codec.sha256(secretOf(deviceId)), 1, null, false, headless = headless))
            return helloFor(deviceId)
        }
    }

    /** A daemon Ed25519 identity, signing the relay's challenge exactly as the daemon does. */
    class DaemonKeys {
        private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val rawPub: ByteArray = kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
        val accountId: String = Codec.accountId(rawPub)
        val hello get() = DaemonHello(accountId, Codec.b64uEnc(rawPub), protoV = 5)
        fun auth(challenge: Challenge): DaemonAuth {
            val msg = "ccpocket/daemon-auth/v1".toByteArray() + byteArrayOf(0) + accountId.toByteArray() + Codec.b64uDec(challenge.nonce)
            return DaemonAuth(Codec.b64uEnc(Signature.getInstance("Ed25519").run { initSign(kp.private); update(msg); sign() }))
        }
    }
}
