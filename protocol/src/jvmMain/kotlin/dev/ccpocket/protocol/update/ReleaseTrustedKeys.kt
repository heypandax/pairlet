package dev.ccpocket.protocol.update

/**
 * The Ed25519 public keys this build trusts to sign `release-manifest.json` (see [ReleaseSignature]).
 *
 * ── EMPTY = NOT CONFIGURED ──────────────────────────────────────────────────────────────────────────────
 * While this list is empty, self-update behaves exactly as before signing existed: the downloaded artifact
 * is checked against the release's SHA256SUMS only ([ReleaseClient.verifyAgainstSums]), which comes from
 * the same place as the artifact and so is NOT an independent root of trust.
 *
 * As soon as ONE key is listed, every daemon and desktop app built from this tree switches to ENFORCED
 * mode: an update is installed only if the release carries a manifest + signature that verifies against
 * one of these keys and the artifact's sha256 matches the manifest. There is no fallback to SHA256SUMS.
 * From then on every release — including daemon hotfixes and what the mirror serves — MUST be signed, or
 * those clients refuse to update. Follow docs/RELEASE.md「更新包签名」 in order before adding a key.
 *
 * ── HOW TO ADD A KEY ─────────────────────────────────────────────────────────────────────────────────────
 * Format: the standard base64 (with padding, 44 characters) of the RAW 32-byte Ed25519 public key.
 * Get it from the key pair the project owner generated:
 *
 *     python3 scripts/release-manifest.py public-key          # reads RELEASE_SIGNING_KEY_FILE or RELEASE_SIGNING_KEY
 *
 * (`release-manifest.py keygen` prints the same line when the pair is created.) Add it as a string
 * literal with a comment naming the key and the date it was created, e.g.
 *
 *     "<44-char base64>", // release key 2026-10, created by <owner>
 *
 * Rotation: ship a release that lists BOTH the old and the new key, switch the signing secret to the new
 * key, and only drop the old key from a later release (see the rotation steps in docs/RELEASE.md).
 * ReleaseTrustedKeysTest pins the current (empty) state — update it in the same change.
 */
object ReleaseTrustedKeys {
    val KEYS: List<String> = listOf(
    )
}
