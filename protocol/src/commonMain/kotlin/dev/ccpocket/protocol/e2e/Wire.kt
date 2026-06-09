package dev.ccpocket.protocol.e2e

/**
 * Framing for the opaque binary data plane.
 *
 * Two layers:
 *  - **E2E payload** (device <-> daemon, end-to-end; the relay never decodes it):
 *      `[type:1][body]`  where type = HANDSHAKE (body = ephemeral public key) or TRANSPORT (body = sealed frame).
 *  - **relay routing wrapper** (relay <-> daemon only; lets one daemon multiplex many devices):
 *      `[idLen:1][deviceId utf8][payload]`. The deviceId is routing metadata the relay already knows,
 *      so wrapping it in the clear keeps the relay zero-knowledge about content.
 *
 * A device speaks only the inner payload; the relay adds/strips the wrapper toward the daemon.
 */
object Wire {
    const val HANDSHAKE: Byte = 1
    const val TRANSPORT: Byte = 2

    fun payload(type: Byte, body: ByteArray): ByteArray = byteArrayOf(type) + body
    fun payloadType(payload: ByteArray): Byte = payload[0]
    fun payloadBody(payload: ByteArray): ByteArray = payload.copyOfRange(1, payload.size)

    /** relay/daemon wrapper: prepend the source/target deviceId. */
    fun wrapDevice(deviceId: String, payload: ByteArray): ByteArray {
        val id = deviceId.encodeToByteArray()
        return byteArrayOf(id.size.toByte()) + id + payload
    }

    /** Returns (deviceId, payload) or null if malformed. */
    fun unwrapDevice(framed: ByteArray): Pair<String, ByteArray>? {
        if (framed.isEmpty()) return null
        val len = framed[0].toInt() and 0xff
        if (framed.size < 1 + len) return null
        val id = framed.copyOfRange(1, 1 + len).decodeToString()
        return id to framed.copyOfRange(1 + len, framed.size)
    }
}
