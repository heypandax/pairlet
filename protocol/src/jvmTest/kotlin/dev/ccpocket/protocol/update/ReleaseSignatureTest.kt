package dev.ccpocket.protocol.update

import dev.ccpocket.protocol.update.ReleaseSignature.Failure
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ENFORCED-mode decision in isolation ([ReleaseSignature.verify]): every way a release can fail to prove
 * itself must refuse with its own reason, and only a correctly signed, newer, matching manifest passes.
 * Keys are generated per run.
 */
class ReleaseSignatureTest {
    private val key = TestSigningKey()
    private val asset = "cc-pocket-daemon-2.5.0-linux-x86_64.tar.gz"
    private val payload = "daemon bytes".toByteArray()
    private val manifest = manifestJson("2.5.0", mapOf(asset to sha256Hex(payload), "SOMETHING-else.dmg" to "0".repeat(64)))

    private fun verify(
        manifestBytes: ByteArray? = manifest,
        signature: ByteArray? = key.signatureFile(manifest),
        keys: List<String> = listOf(key.publicBase64),
        expected: String = "2.5.0",
        current: String = "2.4.0",
        assetName: String = asset,
        actual: String = sha256Hex(payload),
    ) = ReleaseSignature.verify(manifestBytes, signature, keys, expected, current, assetName, actual)

    private fun refused(failure: Failure, block: () -> Unit) {
        val e = assertFailsWith<ReleaseSignature.RejectedException> { block() }
        assertEquals(failure, e.failure, e.message)
        assertTrue(e.message!!.contains(failure.summary) && e.message!!.contains("nothing was installed"), e.message)
        assertIs<IllegalStateException>(e) // rides every caller's existing "update failed" path
    }

    @Test
    fun a_correctly_signed_newer_matching_manifest_is_accepted() {
        val m = verify()
        assertEquals("2.5.0", m.version)
        assertEquals(sha256Hex(payload), m.assets[asset])
    }

    @Test
    fun the_hash_comparison_ignores_hex_case() {
        verify(actual = sha256Hex(payload).uppercase())
    }

    @Test
    fun missing_manifest_or_signature_is_refused() {
        refused(Failure.MANIFEST_MISSING) { verify(manifestBytes = null) }
        refused(Failure.SIGNATURE_MISSING) { verify(signature = null) }
    }

    @Test
    fun a_tampered_signature_is_refused() {
        val raw = key.sign(manifest).also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        refused(Failure.SIGNATURE_INVALID) { verify(signature = Base64.getEncoder().encodeToString(raw).toByteArray()) }
    }

    @Test
    fun a_signature_that_is_not_one_is_refused_as_malformed() {
        refused(Failure.SIGNATURE_MALFORMED) { verify(signature = "not base64 !!".toByteArray()) }
        refused(Failure.SIGNATURE_MALFORMED) { verify(signature = Base64.getEncoder().encodeToString(ByteArray(32)).toByteArray()) }
        refused(Failure.SIGNATURE_MALFORMED) { verify(signature = ByteArray(0)) }
    }

    @Test
    fun a_manifest_changed_after_signing_is_refused() {
        val signature = key.signatureFile(manifest)
        val tampered = String(manifest).replace(sha256Hex(payload), sha256Hex("evil".toByteArray())).toByteArray()
        refused(Failure.SIGNATURE_INVALID) { verify(manifestBytes = tampered, signature = signature, actual = sha256Hex("evil".toByteArray())) }
        // even a whitespace-only change: the signature covers the raw bytes, not a re-serialization
        refused(Failure.SIGNATURE_INVALID) { verify(manifestBytes = manifest + " ".toByteArray(), signature = signature) }
    }

    @Test
    fun a_manifest_signed_by_an_untrusted_key_is_refused() {
        val other = TestSigningKey()
        refused(Failure.SIGNATURE_INVALID) { verify(signature = other.signatureFile(manifest)) }
    }

    @Test
    fun an_artifact_that_does_not_match_the_signed_hash_is_refused() {
        refused(Failure.HASH_MISMATCH) { verify(actual = sha256Hex("other bytes".toByteArray())) }
    }

    @Test
    fun an_artifact_the_manifest_does_not_list_is_refused() {
        refused(Failure.ASSET_NOT_LISTED) { verify(assetName = "cc-pocket-daemon-2.5.0-linux-arm64.tar.gz") }
    }

    @Test
    fun a_signed_manifest_not_newer_than_the_running_version_is_refused() {
        refused(Failure.NOT_NEWER) { verify(current = "2.5.0") }  // equal
        refused(Failure.NOT_NEWER) { verify(current = "2.6.1") }  // older than running: a replayed old release
    }

    @Test
    fun a_signed_manifest_for_another_version_is_refused() {
        // e.g. a mirror offering "2.6.0" but carrying the (genuinely signed) 2.5.0 manifest
        refused(Failure.VERSION_MISMATCH) { verify(expected = "2.6.0") }
    }

    @Test
    fun a_validly_signed_but_malformed_manifest_is_refused() {
        fun signedVerify(body: ByteArray) = verify(manifestBytes = body, signature = key.signatureFile(body))
        val sha = sha256Hex(payload)
        refused(Failure.MANIFEST_MALFORMED) { signedVerify(manifestJson("2.5.0", mapOf(asset to sha), schema = "something-else/1")) }
        refused(Failure.MANIFEST_MALFORMED) { signedVerify(manifestJson("../../2.5.0", mapOf(asset to sha))) }
        refused(Failure.MANIFEST_MALFORMED) { signedVerify(manifestJson("2.5.0", mapOf(asset to sha), publishedAt = "yesterday")) }
        refused(Failure.MANIFEST_MALFORMED) { signedVerify(manifestJson("2.5.0", mapOf(asset to "abc"))) }
        refused(Failure.MANIFEST_MALFORMED) { signedVerify(manifestJson("2.5.0", mapOf("../$asset" to sha))) }
        refused(Failure.MANIFEST_MALFORMED) { signedVerify("""{"schema":"${ReleaseSignature.SCHEMA}","version":"2.5.0","publishedAt":"2026-10-05T08:00:00Z","assets":{}}""".toByteArray()) }
        refused(Failure.MANIFEST_MALFORMED) { signedVerify("[1,2,3]".toByteArray()) }
    }

    @Test
    fun any_one_of_several_trusted_keys_is_enough() {
        val old = TestSigningKey()
        val new = TestSigningKey()
        val both = listOf(old.publicBase64, new.publicBase64)
        verify(signature = old.signatureFile(manifest), keys = both)
        verify(signature = new.signatureFile(manifest), keys = both)
    }

    @Test
    fun a_key_removed_from_the_trusted_list_is_no_longer_accepted() {
        val old = TestSigningKey()
        val new = TestSigningKey()
        verify(signature = old.signatureFile(manifest), keys = listOf(old.publicBase64, new.publicBase64))
        refused(Failure.SIGNATURE_INVALID) { verify(signature = old.signatureFile(manifest), keys = listOf(new.publicBase64)) }
    }

    @Test
    fun an_invalid_entry_in_the_trusted_list_is_skipped_not_trusted() {
        refused(Failure.SIGNATURE_INVALID) { verify(keys = listOf("not-a-key")) }
        verify(keys = listOf("not-a-key", key.publicBase64))
    }

    @Test
    fun the_enforced_path_refuses_to_run_with_an_empty_list() {
        assertFailsWith<IllegalArgumentException> { verify(keys = emptyList()) }
    }

    @Test
    fun the_mode_line_names_the_mode() {
        assertTrue(ReleaseSignature.modeLine(emptyList()).startsWith("update signatures: NOT CONFIGURED"))
        assertTrue(ReleaseSignature.modeLine(listOf(key.publicBase64)).startsWith("update signatures: ENFORCED — 1 trusted"))
    }
}
