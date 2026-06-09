package dev.ccpocket.relay.auth

import java.security.MessageDigest
import java.util.Base64

/** Shared encodings. account_id = lowercase RFC4648 base32 (no pad) of sha256(ed25519 pubkey). */
object Codec {
    private val B32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray()
    private val urlEnc = Base64.getUrlEncoder().withoutPadding()
    private val urlDec = Base64.getUrlDecoder()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun b64uEnc(b: ByteArray): String = urlEnc.encodeToString(b)
    fun b64uDec(s: String): ByteArray = urlDec.decode(s)

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** The public account fingerprint derived from the daemon's Ed25519 static key. */
    fun accountId(ed25519Pub: ByteArray): String = base32(sha256(ed25519Pub))

    fun base32(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32[(buffer ushr bits) and 0x1f])
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }
}
