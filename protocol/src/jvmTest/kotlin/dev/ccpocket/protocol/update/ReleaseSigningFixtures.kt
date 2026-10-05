package dev.ccpocket.protocol.update

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** A throwaway Ed25519 key pair generated per test run — never written anywhere, never a release key. */
internal class TestSigningKey {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** The trusted-list form: base64 of the raw 32-byte public key (the tail of the X.509 encoding). */
    val publicBase64: String = Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())

    fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run { initSign(pair.private); update(message); sign() }

    /** What `release-manifest.json.sig` holds: base64 of the signature plus a newline. */
    fun signatureFile(message: ByteArray): ByteArray =
        (Base64.getEncoder().encodeToString(sign(message)) + "\n").toByteArray()
}

internal fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** RFC 8032 §7.1 TEST 2 — a PUBLISHED test key (its secret is printed in the RFC), never a release key. */
internal const val RFC8032_VECTOR2_PUBLIC_HEX = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
internal const val RFC8032_VECTOR2_MESSAGE_HEX = "72"
internal const val RFC8032_VECTOR2_SIGNATURE_HEX =
    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
internal val RFC8032_VECTOR2_PUBLIC_BASE64: String = Base64.getEncoder().encodeToString(hexBytes(RFC8032_VECTOR2_PUBLIC_HEX))

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A manifest body in the shape scripts/release-manifest.py writes. */
internal fun manifestJson(
    version: String,
    assets: Map<String, String>,
    publishedAt: String = "2026-10-05T08:00:00Z",
    schema: String = ReleaseSignature.SCHEMA,
): ByteArray {
    val entries = assets.entries.joinToString(",\n") { (name, sha) -> "    \"$name\": {\"sha256\": \"$sha\"}" }
    return """
        |{
        |  "assets": {
        |$entries
        |  },
        |  "publishedAt": "$publishedAt",
        |  "schema": "$schema",
        |  "version": "$version"
        |}
        |""".trimMargin().toByteArray()
}
