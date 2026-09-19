package dev.ccpocket.app.net

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PROTO_V_PUSH_ACK
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.PushRegistrationOutcome
import dev.ccpocket.protocol.PushRegistrationResult
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A relay with NO daemon behind it, speaking just enough RFC 6455 over a raw loopback socket to drive the
 * real Ktor client: it authenticates any DeviceHello with [Attached] (at [PROTO_V_PUSH_ACK], so receipts are
 * expected), answers every [RegisterPush] with a [PushRegistrationResult], and swallows the Noise HANDSHAKE
 * without ever answering it — exactly what the device sees while the computer is off.
 */
class FakeRelayServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val url = "ws://127.0.0.1:${server.localPort}"
    /** Every accepted WebSocket connection — a helper dial for the same device would show up here. */
    val connections = AtomicInteger()
    /** HANDSHAKE payloads received (and left unanswered: nobody is there to answer). */
    val handshakes = AtomicInteger()
    val registrations = CopyOnWriteArrayList<RegisterPush>()
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "fake-relay-accept") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                sockets += s
                thread(isDaemon = true, name = "fake-relay-conn") { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val key = readUpgradeRequest(input) ?: return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()))
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        connections.incrementAndGet()
        while (true) {
            val (opcode, payload) = readFrame(input) ?: return
            when (opcode) {
                OP_TEXT -> onText(out, payload.decodeToString())
                OP_BINARY -> if (Wire.payloadType(payload) == Wire.HANDSHAKE) handshakes.incrementAndGet()
                OP_PING -> writeFrame(out, OP_PONG, payload)
                OP_CLOSE -> { runCatching { writeFrame(out, OP_CLOSE, ByteArray(0)) }; return }
            }
        }
    }

    private fun onText(out: OutputStream, text: String) {
        when (val body = runCatching { PocketJson.decodeFromString<Envelope>(text).body }.getOrNull()) {
            is DeviceHello -> send(out, Attached(Role.DEVICE, "acct", relayProtoV = PROTO_V_PUSH_ACK))
            is RegisterPush -> {
                registrations += body
                val rid = body.requestId ?: return
                send(out, PushRegistrationResult(rid,
                    if (body.token.isBlank()) PushRegistrationOutcome.CLEARED else PushRegistrationOutcome.STORED))
            }
            else -> {}
        }
    }

    private fun send(out: OutputStream, frame: Frame) =
        writeFrame(out, OP_TEXT, PocketJson.encodeToString(Envelope("r", 0L, body = frame)).encodeToByteArray())

    @Synchronized private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65536 -> { out.write(126); out.write(payload.size ushr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(payload)
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        /** Reads the HTTP upgrade request; returns its Sec-WebSocket-Key. */
        fun readUpgradeRequest(input: DataInputStream): String? {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return null
                head.append(b.toChar())
            }
            return head.lines().firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
        }

        /** One (unfragmented) client frame, unmasked; null at end of stream. */
        fun readFrame(input: DataInputStream): Pair<Int, ByteArray>? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(len.toInt()).also { input.readFully(it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return (b0 and 0x0F) to payload
        }
    }
}
