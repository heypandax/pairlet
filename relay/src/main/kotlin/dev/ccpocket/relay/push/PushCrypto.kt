package dev.ccpocket.relay.push

import dev.ccpocket.relay.auth.Codec
import java.util.Base64

/** JWT/PEM helpers shared by the APNs (ES256) and FCM (RS256) senders. JDK-native, no crypto deps. */
internal object PushCrypto {
    private val mimeDec = Base64.getMimeDecoder()

    fun b64u(b: ByteArray): String = Codec.b64uEnc(b) // one base64url(no-pad) source, shared with relay auth
    fun b64u(s: String): String = b64u(s.toByteArray())

    /** Strip a PEM envelope ("-----BEGIN …-----") and decode the base64 body to DER bytes. */
    fun pemToDer(pem: String): ByteArray =
        mimeDec.decode(pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim())

    /** ECDSA DER (SEQUENCE{INTEGER r, INTEGER s}) -> JOSE fixed-width R||S (each [partLen] bytes). */
    fun derToJose(der: ByteArray, partLen: Int): ByteArray {
        require(der.size > 8 && der[0].toInt() == 0x30) { "bad DER ECDSA signature" }
        var i = 1
        val seqLen = der[i].toInt() and 0xff; i++
        if (seqLen and 0x80 != 0) i += seqLen and 0x7f // skip long-form length octets (rare for P-256)
        require(der[i].toInt() == 0x02) { "bad DER (r)" }
        val rLen = der[i + 1].toInt() and 0xff; i += 2
        val r = der.copyOfRange(i, i + rLen); i += rLen
        require(der[i].toInt() == 0x02) { "bad DER (s)" }
        val sLen = der[i + 1].toInt() and 0xff; i += 2
        val s = der.copyOfRange(i, i + sLen)
        val out = ByteArray(partLen * 2)
        rightAlign(r, out, 0, partLen)
        rightAlign(s, out, partLen, partLen)
        return out
    }

    /** Right-align a (possibly sign-prefixed or short) big-endian integer into a fixed [len] field. */
    private fun rightAlign(num: ByteArray, out: ByteArray, dstOff: Int, len: Int) {
        val src = num.dropWhile { it.toInt() == 0 }.toByteArray()
        require(src.size <= len) { "ECDSA integer wider than field" }
        src.copyInto(out, dstOff + len - src.size)
    }
}
