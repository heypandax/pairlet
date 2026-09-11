package dev.ccpocket.relay.analytics

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless anonymous install token (DESKTOP-GA4-INGRESS.md §2): `1.<install_id>.<exp_s>.<hmac>`,
 * base64url. It is NOT user authentication — the install id is a resettable client seed. What it buys
 * is a server-verified rate-limit key per install, a lockout-limited rate of NEW identities per IP
 * (register is the only way to mint one), and a single key rotation that voids every token at once.
 */
class InstallToken(private val key: ByteArray, private val clock: () -> Long = System::currentTimeMillis) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun issue(installId: String, ttlMs: Long = TTL_MS): String {
        val exp = (clock() + ttlMs) / 1000
        val payload = "1.$installId.$exp"
        return b64.encodeToString("$payload.${mac(payload)}".toByteArray())
    }

    /** @return the install id the token was issued for, or null when invalid or expired. */
    fun verify(token: String?): String? {
        if (token.isNullOrEmpty() || token.length > 512) return null
        val raw = runCatching { String(Base64.getUrlDecoder().decode(token)) }.getOrNull() ?: return null
        val parts = raw.split('.')
        if (parts.size != 5 || parts[0] != "1") return null // install id itself contains one dot
        val installId = "${parts[1]}.${parts[2]}"
        val exp = parts[3].toLongOrNull() ?: return null
        if (!AnalyticsValidator.clientId.matches(installId)) return null
        val expected = mac("1.$installId.$exp")
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), parts[4].toByteArray())) return null
        if (clock() / 1000 >= exp) return null
        return installId
    }

    private fun mac(payload: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(payload.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    companion object { const val TTL_MS = 24 * 3_600_000L }
}
