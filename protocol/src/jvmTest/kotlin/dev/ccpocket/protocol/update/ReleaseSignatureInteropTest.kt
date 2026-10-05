package dev.ccpocket.protocol.update

import java.security.Signature
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-implementation check: the client (JDK Ed25519) verifies what the release tooling
 * (scripts/release-manifest.py → OpenSSL) produces.
 *
 * `resources/release-signing/` holds a manifest + signature that release-manifest.py built and signed with the
 * PUBLISHED RFC 8032 test-vector key; scripts/tests/test_release_manifest.py regenerates both and asserts they
 * are byte-identical, so this test always verifies the script's current output. The same RFC vector is also
 * checked on both sides directly.
 */
class ReleaseSignatureInteropTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/release-signing/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private val manifest = resource(ReleaseSignature.MANIFEST_ASSET)
    private val signature = resource(ReleaseSignature.SIGNATURE_ASSET)
    private val daemonAsset = "cc-pocket-daemon-2.4.0-linux-x86_64.tar.gz"
    private val daemonBytes = "pairlet release-manifest interop fixture\n".toByteArray()

    @Test
    fun the_jdk_verifies_rfc8032_test_vector_2() {
        val ok = Signature.getInstance("Ed25519").run {
            initVerify(ReleaseSignature.decodePublicKey(RFC8032_VECTOR2_PUBLIC_BASE64))
            update(hexBytes(RFC8032_VECTOR2_MESSAGE_HEX))
            verify(hexBytes(RFC8032_VECTOR2_SIGNATURE_HEX))
        }
        assertTrue(ok)
    }

    @Test
    fun the_client_accepts_the_manifest_and_signature_the_python_script_produced() {
        val m = ReleaseSignature.verify(
            manifest, signature, listOf(RFC8032_VECTOR2_PUBLIC_BASE64),
            expectedVersion = "2.4.0", currentVersion = "2.3.9", asset = daemonAsset, actualSha256 = sha256Hex(daemonBytes),
        )
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), m.publishedAt)
        assertEquals(sha256Hex("second interop asset\n".toByteArray()), m.assets["cc-pocket-desktop-macos-arm64.dmg"])
        assertEquals(2, m.assets.size)
    }

    @Test
    fun the_script_output_is_rejected_by_a_client_that_does_not_trust_its_key() {
        val e = assertFailsWith<ReleaseSignature.RejectedException> {
            ReleaseSignature.verify(manifest, signature, listOf(TestSigningKey().publicBase64),
                "2.4.0", "2.3.9", daemonAsset, sha256Hex(daemonBytes))
        }
        assertEquals(ReleaseSignature.Failure.SIGNATURE_INVALID, e.failure)
    }
}
