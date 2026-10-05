package dev.ccpocket.protocol.e2e

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * The human-comparable fingerprint of a pairing static key: a phone's or desktop App's DEVICE key, or a
 * daemon's identity E2E key (the "computer" fingerprint). `pairlet devices` prints one per paired device
 * and the computer's own; the App shows its own and the connected computer's. The two humans (or one
 * owner with two screens) compare them group by group.
 *
 * Why it exists (pairing security, phase 0): pairing currently relies on the relay — it hands out the
 * ticket and forwards both static keys — so a compromised relay could substitute a key during the few
 * minutes of a pairing. Comparing these values after pairing is how an owner notices that.
 *
 * Construction, fixed so every end computes the SAME string:
 *  - SHA-256 over the domain tag `pairlet/pairing-fingerprint/v1`, one 0x00 byte, then the RAW public key
 *    bytes (the 65-byte uncompressed P-256 point) — never its base64 text, so encoding details cannot make
 *    two ends disagree;
 *  - the first 10 bytes (80 bits) of the digest, lowercase hex, in 5 groups of 4: `7132-8382-2950-2dfe-119b`.
 *
 * 80 bits: a relay that already knows one side's key would need ~2^80 key generations to forge a key with a
 * matching fingerprint — out of reach — while 20 characters still fit on a phone row and read out loud in
 * five chunks. The guarantee holds only for a FULL comparison; matching only the first group or two is
 * cheap to forge, which is why every surface says "compare all groups".
 *
 * Display-only. Not a wire type, never serialized, never an authorization input.
 */
object PairingFingerprint {
    const val BYTES = 10
    private val DOMAIN = "pairlet/pairing-fingerprint/v1".encodeToByteArray()
    private val hasher by lazy { CryptographyProvider.Default.get(SHA256).hasher() }

    /** The fingerprint of [publicRaw] (the raw key bytes, not base64). */
    fun of(publicRaw: ByteArray): String {
        val digest = hasher.hashBlocking(DOMAIN + byteArrayOf(0) + publicRaw)
        val hex = StringBuilder(BYTES * 2)
        for (i in 0 until BYTES) {
            val v = digest[i].toInt() and 0xff
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return hex.chunked(4).joinToString("-")
    }

    private const val HEX = "0123456789abcdef"
}
