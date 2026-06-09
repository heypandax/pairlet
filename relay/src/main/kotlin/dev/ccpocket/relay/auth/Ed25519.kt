package dev.ccpocket.relay.auth

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Ed25519 verification via JDK 17's native provider (no external crypto dependency on the relay). */
object Ed25519 {
    // X.509 SubjectPublicKeyInfo prefix for an Ed25519 key; append the raw 32-byte public key.
    private val SPKI_PREFIX = Codec.hex("302a300506032b6570032100")

    /** Verify a raw 64-byte Ed25519 [sig] over [msg] under the raw 32-byte [rawPub]. Never throws. */
    fun verify(rawPub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (rawPub.size != 32 || sig.size != 64) return false
        return try {
            val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + rawPub))
            Signature.getInstance("Ed25519").run { initVerify(pub); update(msg); verify(sig) }
        } catch (e: Exception) {
            false
        }
    }
}
