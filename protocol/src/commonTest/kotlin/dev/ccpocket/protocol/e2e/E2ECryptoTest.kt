package dev.ccpocket.protocol.e2e

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E2ECryptoTest {

    @Test fun ecdh_agreement_is_symmetric() {
        val a = E2ECrypto.generateKeyPair()
        val b = E2ECrypto.generateKeyPair()
        assertEquals(32, a.privateRaw.size)
        assertEquals(65, a.publicRaw.size) // uncompressed P-256 point
        assertContentEquals(E2ECrypto.agree(a.privateRaw, b.publicRaw), E2ECrypto.agree(b.privateRaw, a.publicRaw))
    }

    @Test fun hkdf_is_deterministic_and_sized() {
        val ikm = ByteArray(32) { it.toByte() }
        val k1 = E2ECrypto.hkdf(ikm, salt = byteArrayOf(1, 2, 3), info = "ctx".encodeToByteArray(), length = 64)
        val k2 = E2ECrypto.hkdf(ikm, salt = byteArrayOf(1, 2, 3), info = "ctx".encodeToByteArray(), length = 64)
        assertEquals(64, k1.size)
        assertContentEquals(k1, k2)
        // different info => different output
        assertNotEquals(k1.toList(), E2ECrypto.hkdf(ikm, byteArrayOf(1, 2, 3), "other".encodeToByteArray(), 64).toList())
    }

    @Test fun aead_round_trip_and_tamper_detection() {
        val key = E2ECrypto.hkdf("secret".encodeToByteArray(), ByteArray(0), "key".encodeToByteArray(), 32)
        val nonce = ByteArray(12) { it.toByte() }
        val msg = "hello cc-pocket".encodeToByteArray()
        val aad = "v1".encodeToByteArray()

        val ct = E2ECrypto.seal(key, nonce, msg, aad)
        assertNotEquals(msg.toList(), ct.toList()) // not plaintext
        assertContentEquals(msg, E2ECrypto.open(key, nonce, ct, aad))

        // wrong aad, wrong nonce, wrong key, and a flipped byte all fail closed
        assertNull(E2ECrypto.open(key, nonce, ct, "v2".encodeToByteArray()))
        assertNull(E2ECrypto.open(key, ByteArray(12), ct, aad))
        assertNull(E2ECrypto.open(ByteArray(32), nonce, ct, aad))
        val tampered = ct.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(E2ECrypto.open(key, nonce, tampered, aad))
    }

    @Test fun mitm_with_swapped_key_yields_different_secret() {
        val daemon = E2ECrypto.generateKeyPair()
        val device = E2ECrypto.generateKeyPair()
        val attacker = E2ECrypto.generateKeyPair()
        // device thinks it's talking to the daemon; a relay that swaps in the attacker's key
        // produces a secret the daemon never derives -> the derived session keys won't match.
        val deviceView = E2ECrypto.agree(device.privateRaw, attacker.publicRaw)
        val daemonView = E2ECrypto.agree(daemon.privateRaw, device.publicRaw)
        assertTrue(!deviceView.contentEquals(daemonView))
    }
}
