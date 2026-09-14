package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.review.b64d
import dev.ccpocket.protocol.e2e.E2ECrypto
import java.security.MessageDigest

/**
 * The human-comparable fingerprint of an execution-link static key (#367 G0 security review HIGH-1/2).
 *
 * NOT [dev.ccpocket.protocol.collaboratorFingerprint]: that one is FNV-1a over 8 words ≈ 40 bits, a
 * wrong-QR aid that an attacker who controls the key (a compromised relay racing the ticket, or anyone
 * facing a long-lived target key offline) can collide. An execution grant is an execution permission, so
 * the value the two humans compare must resist a deliberate second-preimage search:
 *
 *  - SHA-256 over a domain tag and the key's base64url text;
 *  - 140 bits kept (28 symbols × 5 bits), far above the 80-bit floor;
 *  - a 32-symbol alphabet without `l`/`o`/`0`/`1`, shown as 7 groups of 4: `abcd-efgh-…`.
 *
 * Comparison ignores case, spaces and dashes, and refuses anything that is not exactly 28 alphabet symbols
 * (so a collaborator word fingerprint can never "match").
 *
 * WHAT IT IDENTIFIES (user decision 09-14 — state it wherever it is shown): for the SOURCE side this is the
 * fingerprint of THIS LINK's key (本次链路指纹), generated fresh at join — NOT the source machine's identity
 * (不是机器身份). Re-joining yields a different value; two links from one machine never share one. Binding
 * a link to the source daemon's long-term identity is deferred to the real-device acceptance stage. For
 * the TARGET side the pinned key is the target daemon's identity E2E key.
 */
object ExecutionFingerprint {
    /** Diagnostic label carried next to a source fingerprint, so no surface presents it as a machine id. */
    const val SOURCE_SCOPE = "execution_link_not_machine_identity"

    const val SYMBOLS = 28
    const val BITS = SYMBOLS * 5
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private val DOMAIN = "pairlet/execution-fingerprint/v1".encodeToByteArray()

    fun of(pubB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(DOMAIN + byteArrayOf(0) + pubB64.encodeToByteArray())
        val sb = StringBuilder(SYMBOLS + SYMBOLS / 4)
        var buffer = 0
        var bits = 0
        var i = 0
        var emitted = 0
        while (emitted < SYMBOLS) {
            if (bits < 5) {
                buffer = (buffer shl 8) or (digest[i++].toInt() and 0xff)
                bits += 8
            }
            bits -= 5
            if (emitted > 0 && emitted % 4 == 0) sb.append('-')
            sb.append(ALPHABET[(buffer ushr bits) and 0x1f])
            emitted++
        }
        return sb.toString()
    }

    /** Canonical comparable form, or null when [shown] is not a well-formed execution fingerprint. */
    fun normalize(shown: String): String? {
        val s = shown.lowercase().filterNot { it.isWhitespace() || it == '-' }
        return s.takeIf { it.length == SYMBOLS && it.all { c -> c in ALPHABET } }
    }

    fun matches(shown: String, pubB64: String): Boolean {
        val a = normalize(shown) ?: return false
        return MessageDigest.isEqual(a.encodeToByteArray(), normalize(of(pubB64))!!.encodeToByteArray())
    }
}

/**
 * The first-contact PSK of an execution link (#367 G0 review HIGH-1).
 *
 * The relay MINTS the connect ticket, so "the frame decrypted under the ticket" proves nothing against the
 * relay itself: it could redeem its own ticket for a key it chose and bind that key first. The PSK therefore
 * also mixes a 32-byte [ExecutionInvite.inviteSecret] generated on the target and carried only in the
 * out-of-band invite. The relay still redeems the RAW ticket; the handshake uses the derived value, which the
 * relay cannot compute.
 *
 * The derived value is returned as base64url TEXT so it can ride [dev.ccpocket.daemon.review.PeerLinkSecret.ticket]
 * unchanged — [dev.ccpocket.daemon.review.PeerHandshake.psk] mixes its UTF-8 bytes, and the target arms exactly
 * those bytes.
 */
object ExecutionPsk {
    private val SALT = "pairlet/execution-psk/v1".encodeToByteArray()
    const val SECRET_BYTES = 32

    fun derive(ticket: String, inviteSecretB64: String): String {
        val t = ticket.encodeToByteArray()
        val s = b64d(inviteSecretB64)
        require(s.size == SECRET_BYTES) { "invite secret must be $SECRET_BYTES bytes" }
        // length-prefixed so (ticket, secret) boundaries cannot be shifted
        val ikm = byteArrayOf((t.size ushr 24).toByte(), (t.size ushr 16).toByte(), (t.size ushr 8).toByte(), t.size.toByte()) + t + s
        return b64(E2ECrypto.hkdf(ikm, SALT, "first-contact".encodeToByteArray(), 32))
    }
}
