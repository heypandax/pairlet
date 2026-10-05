package dev.ccpocket.protocol.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Signed release manifests — the trust root for self-update that does NOT come from the download source.
 *
 * A release publishes `release-manifest.json` (version, publish time, sha256 of every asset) and a detached
 * Ed25519 signature over its RAW bytes in `release-manifest.json.sig` (base64 of the 64-byte signature).
 * Both are produced by `scripts/release-manifest.py` in the release workflows. The mirror and GitHub only
 * carry bytes: whoever serves them, a client in ENFORCED mode (a non-empty [ReleaseTrustedKeys.KEYS])
 * installs an artifact only after [verify] accepted it.
 *
 * Manifest shape (`schema` pins the document type, so a signature over anything else never parses as one):
 * ```
 * {"schema":"pairlet-release-manifest/1","version":"2.4.0","publishedAt":"2026-10-05T08:00:00Z",
 *  "assets":{"cc-pocket-daemon-2.4.0-linux-x86_64.tar.gz":{"sha256":"<64 hex>"}, …}}
 * ```
 * Ed25519 comes from the JDK (`java.security`, JDK ≥ 15) — the daemon and the desktop app both run on a
 * bundled JDK 17, and this file lives in the JVM-only source set, so no dependency is added anywhere.
 */
object ReleaseSignature {
    const val MANIFEST_ASSET = "release-manifest.json"
    const val SIGNATURE_ASSET = "release-manifest.json.sig"
    const val SCHEMA = "pairlet-release-manifest/1"

    /** Upper bounds for what a client will read — a manifest is a few KB; anything larger is not ours. */
    const val MAX_MANIFEST_BYTES = 1 shl 20
    const val MAX_SIGNATURE_BYTES = 4 * 1024

    /** Same rule the release scripts enforce: plain dotted version with an optional suffix, nothing that
     *  could walk a path (the daemon uses the version as a directory name). */
    private val VERSION_RE = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+([-.][0-9A-Za-z.]+)?$")
    private val ASSET_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")
    private val SHA256_RE = Regex("^[0-9a-f]{64}$")

    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112) + BIT STRING of 32 bytes. */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    private val json = Json { ignoreUnknownKeys = true } // strict syntax; unknown fields are future additions

    data class Manifest(val version: String, val publishedAt: Instant, val assets: Map<String, String>)

    /** Why an update was refused in ENFORCED mode. [summary] is what the user / log sees first. */
    enum class Failure(val summary: String) {
        MANIFEST_MISSING("the release has no signed manifest"),
        SIGNATURE_MISSING("the release manifest is not signed"),
        SIGNATURE_MALFORMED("the release signature is malformed"),
        SIGNATURE_INVALID("the release signature does not match any trusted key"),
        MANIFEST_MALFORMED("the signed release manifest is malformed"),
        VERSION_MISMATCH("the signed manifest is for a different version"),
        NOT_NEWER("the signed manifest is not newer than the running version"),
        ASSET_NOT_LISTED("the signed manifest does not list this download"),
        HASH_MISMATCH("the download does not match the signed manifest"),
    }

    /** Thrown for every ENFORCED-mode refusal. An [IllegalStateException] like the existing checksum
     *  failure, so every caller's "update failed" path reports it unchanged. */
    class RejectedException(val failure: Failure, detail: String) :
        IllegalStateException("update refused — ${failure.summary}: $detail (nothing was installed)")

    /** One line for the startup log: which mode this build runs in. */
    fun modeLine(trustedKeys: List<String> = ReleaseTrustedKeys.KEYS): String =
        if (trustedKeys.isEmpty()) {
            "update signatures: NOT CONFIGURED — no trusted release key is built in, so updates are checked " +
                "against the release SHA256SUMS only (enable per docs/RELEASE.md「更新包签名」)"
        } else {
            "update signatures: ENFORCED — ${trustedKeys.size} trusted release key(s); an update without a " +
                "valid signed $MANIFEST_ASSET is refused"
        }

    /** A trusted-list entry (base64 of the raw 32-byte key) as a JDK public key; throws on anything else. */
    fun decodePublicKey(base64: String): PublicKey {
        val raw = Base64.getDecoder().decode(base64.trim())
        require(raw.size == 32) { "an Ed25519 public key is 32 bytes, got ${raw.size}" }
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw))
    }

    /**
     * The whole ENFORCED-mode decision for one downloaded artifact, without any I/O. [manifestBytes] /
     * [signatureBytes] are null when the release did not provide them (or they could not be fetched).
     * [expectedVersion] is the version the client chose to install (from latest.json / the GitHub API),
     * [currentVersion] the one running. Returns the verified manifest; throws [RejectedException] otherwise.
     */
    fun verify(
        manifestBytes: ByteArray?,
        signatureBytes: ByteArray?,
        trustedKeys: List<String>,
        expectedVersion: String,
        currentVersion: String,
        asset: String,
        actualSha256: String,
    ): Manifest {
        require(trustedKeys.isNotEmpty()) { "verify() is the ENFORCED path; an empty trusted list must not get here" }
        if (manifestBytes == null) reject(Failure.MANIFEST_MISSING, "no $MANIFEST_ASSET for v$expectedVersion")
        if (signatureBytes == null) reject(Failure.SIGNATURE_MISSING, "no $SIGNATURE_ASSET for v$expectedVersion")
        val signature = decodeSignature(signatureBytes)
        if (!signedByAny(manifestBytes, signature, trustedKeys)) {
            reject(Failure.SIGNATURE_INVALID, "$MANIFEST_ASSET for v$expectedVersion (tampered, or signed by an untrusted key)")
        }
        val manifest = parseManifest(manifestBytes)
        if (manifest.version != expectedVersion) {
            reject(Failure.VERSION_MISMATCH, "signed for v${manifest.version}, offered as v$expectedVersion")
        }
        if (!ReleaseVersions.isNewer(manifest.version, currentVersion)) {
            reject(Failure.NOT_NEWER, "signed v${manifest.version}, running v$currentVersion")
        }
        val expected = manifest.assets[asset] ?: reject(Failure.ASSET_NOT_LISTED, asset)
        if (!expected.equals(actualSha256, ignoreCase = true)) {
            reject(Failure.HASH_MISMATCH, "$asset\n  signed   $expected\n  actual   ${actualSha256.lowercase()}")
        }
        return manifest
    }

    /** True when [signature] over [message] verifies against at least one key in [trustedKeys]. A list entry
     *  that is not a valid key is skipped (ReleaseTrustedKeysTest keeps the shipped list valid). */
    fun signedByAny(message: ByteArray, signature: ByteArray, trustedKeys: List<String>): Boolean =
        trustedKeys.any { key ->
            runCatching {
                Signature.getInstance("Ed25519").run {
                    initVerify(decodePublicKey(key))
                    update(message)
                    verify(signature)
                }
            }.getOrDefault(false)
        }

    private fun decodeSignature(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.US_ASCII).trim()
        val raw = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            ?: reject(Failure.SIGNATURE_MALFORMED, "$SIGNATURE_ASSET is not base64")
        if (raw.size != 64) reject(Failure.SIGNATURE_MALFORMED, "$SIGNATURE_ASSET decodes to ${raw.size} bytes, expected 64")
        return raw
    }

    /** Parse an (already signature-checked) manifest strictly; any deviation is MANIFEST_MALFORMED. */
    fun parseManifest(bytes: ByteArray): Manifest {
        fun bad(why: String): Nothing = reject(Failure.MANIFEST_MALFORMED, why)
        val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: bad("not a JSON object")
        fun str(key: String) = ((obj[key] as? JsonPrimitive)?.takeIf { it.isString })?.contentOrNull
        if (str("schema") != SCHEMA) bad("schema is not $SCHEMA")
        val version = str("version")?.takeIf { VERSION_RE.matches(it) } ?: bad("missing or invalid version")
        val publishedAt = str("publishedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: bad("missing or invalid publishedAt")
        val assets = (obj["assets"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: bad("no assets")
        val hashes = assets.mapValues { (name, entry) ->
            if (!ASSET_RE.matches(name)) bad("invalid asset name")
            val sha = (((entry as? JsonObject)?.get("sha256") as? JsonPrimitive)?.takeIf { it.isString })?.contentOrNull
            sha?.takeIf { SHA256_RE.matches(it) } ?: bad("invalid sha256 for $name")
        }
        return Manifest(version, publishedAt, hashes)
    }

    private fun reject(failure: Failure, detail: String): Nothing = throw RejectedException(failure, detail)
}
