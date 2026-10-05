package dev.ccpocket.protocol.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [PairingFingerprint] is compared across the daemon (`pairlet devices`), the phone and the desktop App, so
 * its exact output is a contract: the known-answer vectors below pin it (computed independently with
 * Python's hashlib: sha256(b"pairlet/pairing-fingerprint/v1" + b"\x00" + key)[:10].hex(), grouped by 4).
 */
class PairingFingerprintTest {

    /** The P-256 generator point, uncompressed — a fixed, well-known 65-byte public key. */
    private val generator = hex(
        "04" +
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296" +
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
    )

    @Test
    fun known_answer_vectors() {
        assertEquals("7132-8382-2950-2dfe-119b", PairingFingerprint.of(generator))
        assertEquals("aa00-c619-a889-e9d9-e89f", PairingFingerprint.of(ByteArray(65)))
    }

    @Test
    fun is_deterministic_and_shaped_as_five_groups_of_four_hex() {
        val key = E2ECrypto.generateKeyPair().publicRaw
        val a = PairingFingerprint.of(key)
        assertEquals(a, PairingFingerprint.of(key.copyOf()), "same key bytes, same fingerprint")
        assertTrue(Regex("^[0-9a-f]{4}(-[0-9a-f]{4}){4}$").matches(a), a)
    }

    @Test
    fun different_keys_give_different_fingerprints() {
        val seen = HashSet<String>()
        repeat(16) { assertTrue(seen.add(PairingFingerprint.of(E2ECrypto.generateKeyPair().publicRaw))) }
        // a single flipped bit changes it too: the hash covers every byte of the key
        val flipped = generator.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertNotEquals(PairingFingerprint.of(generator), PairingFingerprint.of(flipped))
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
