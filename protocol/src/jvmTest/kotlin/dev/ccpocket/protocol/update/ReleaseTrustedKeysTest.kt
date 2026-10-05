package dev.ccpocket.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the SHIPPED trusted-key list means. Today it is empty: update signing is NOT CONFIGURED and every
 * client keeps the SHA256SUMS-only behavior. Filling the list is a release-process change, not a code change.
 */
class ReleaseTrustedKeysTest {

    @Test
    fun shipped_trusted_release_keys_are_empty_until_the_owner_completes_the_update_signing_checklist() {
        assertEquals(
            emptyList(),
            ReleaseTrustedKeys.KEYS,
            """
            ReleaseTrustedKeys.KEYS is no longer empty. Every daemon and desktop app built from this tree will now
            REFUSE any update that is not signed by one of these keys (ENFORCED mode, no SHA256SUMS fallback).
            Before merging this, follow docs/RELEASE.md「更新包签名」 in order and confirm:
              1. the matching private key is stored by the owner and configured as the RELEASE_SIGNING_KEY Actions secret;
              2. the latest published release already carries a valid release-manifest.json + .sig
                 (python3 scripts/release-manifest.py verify …), so the first enforced clients can still update;
              3. the mirror runs the new deploy/mirror-sync.sh (scripts/provision-relay-mirror.sh) and serves that
                 release's manifest + .sig on /dl; set MIRROR_REQUIRE_SIGNATURE=1 once this release is out;
              4. every later release, re-run and daemon hotfix will be signed — an unsigned one strands these clients.
            Then replace this assertion with one that pins the new key list (and keep the RFC 8032 check below).
            """.trimIndent(),
        )
        assertTrue(ReleaseSignature.modeLine().startsWith("update signatures: NOT CONFIGURED"), ReleaseSignature.modeLine())
    }

    @Test
    fun every_listed_key_is_a_valid_ed25519_public_key() {
        for (key in ReleaseTrustedKeys.KEYS) {
            runCatching { ReleaseSignature.decodePublicKey(key) }
                .onFailure { throw AssertionError("not a base64 raw 32-byte Ed25519 public key: '$key'", it) }
        }
        assertEquals(ReleaseTrustedKeys.KEYS.size, ReleaseTrustedKeys.KEYS.toSet().size, "duplicate trusted key")
    }

    @Test
    fun the_public_rfc8032_test_vector_key_is_never_trusted() {
        // its private half is printed in RFC 8032 and used by the interop fixture — it must never sign a release
        assertFalse(RFC8032_VECTOR2_PUBLIC_BASE64 in ReleaseTrustedKeys.KEYS)
    }
}
