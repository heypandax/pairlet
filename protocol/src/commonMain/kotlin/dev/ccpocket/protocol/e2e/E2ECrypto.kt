@file:OptIn(DelicateCryptographyApi::class)

package dev.ccpocket.protocol.e2e

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * Multiplatform crypto primitives for the end-to-end channel, backed by cryptography-kotlin (JDK on
 * JVM/Android, CryptoKit on iOS). Suite: P-256 ECDH + HKDF(HMAC-SHA256) + AES-256-GCM.
 *
 * (P-256 rather than X25519 only because this is the curve cryptography-kotlin exposes in the version
 * whose Kotlin/Native klib is consumable by the project's Kotlin 2.1.21; the handshake construction is
 * curve-agnostic.)
 */
object E2ECrypto {
    private val provider = CryptographyProvider.Default
    private val ecdh get() = provider.get(ECDH)
    private val aes get() = provider.get(AES.GCM)
    private val hmac get() = provider.get(HMAC)

    /** P-256 keypair as raw bytes: private = 32-byte scalar, public = 65-byte uncompressed point. */
    class KeyPair(val privateRaw: ByteArray, val publicRaw: ByteArray)

    fun generateKeyPair(): KeyPair {
        val kp = ecdh.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
        return KeyPair(
            privateRaw = kp.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.RAW),
            publicRaw = kp.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW),
        )
    }

    /** ECDH: raw 32-byte shared secret from our private key and the peer's public key. */
    fun agree(privateRaw: ByteArray, peerPublicRaw: ByteArray): ByteArray {
        val priv = ecdh.privateKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PrivateKey.Format.RAW, privateRaw)
        val pub = ecdh.publicKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, peerPublicRaw)
        return priv.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(pub)
    }

    /** HKDF-SHA256 (RFC 5869) built on HMAC, to avoid the value-class output-size API. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ArrayList<Byte>(length)
        var t = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            for (b in t) { if (out.size < length) out.add(b) }
            counter++
        }
        return out.toByteArray()
    }

    /** AES-256-GCM seal. [nonce] is 12 bytes; returns ciphertext‖tag. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        gcm(key).encryptWithIvBlocking(nonce, plaintext, aad)

    /** AES-256-GCM open. Returns null on any authentication/format failure. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
        runCatching { gcm(key).decryptWithIvBlocking(nonce, ciphertext, aad) }.getOrNull()

    private fun gcm(key: ByteArray) =
        aes.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key).cipher()

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key).signatureGenerator().generateSignatureBlocking(data)
}
