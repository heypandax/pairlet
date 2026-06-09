package dev.ccpocket.daemon.identity

import dev.ccpocket.protocol.e2e.E2ECrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The daemon's self-generated, persisted identity — this IS the tenant (no login, no PII).
 *
 * - Ed25519 static key authenticates the daemon to the relay (signed challenge); its fingerprint is
 *   the public [accountId].
 * - P-256 static key is the daemon end of the end-to-end channel ([E2ECrypto]); the relay never sees it.
 *
 * Stored at ~/.cc-pocket/identity.json with 0600 perms. The relay only ever learns the PUBLIC keys.
 */
class Identity private constructor(
    val accountId: String,
    val ed25519PubRaw: ByteArray,
    private val ed25519Priv: PrivateKey,
    val e2ePrivRaw: ByteArray,   // P-256 scalar, 32 bytes
    val e2ePubRaw: ByteArray,    // P-256 uncompressed point, 65 bytes
) {
    val ed25519PubB64: String get() = b64uEnc(ed25519PubRaw)
    val e2ePubB64: String get() = b64uEnc(e2ePubRaw)

    /** Sign the relay's challenge: Ed25519 over "ccpocket/daemon-auth/v1"|0x00|accountId|nonce. */
    fun signChallenge(nonceB64: String): String {
        val msg = DOMAIN + byteArrayOf(0) + accountId.toByteArray() + b64uDec(nonceB64)
        val sig = Signature.getInstance("Ed25519").run { initSign(ed25519Priv); update(msg); sign() }
        return b64uEnc(sig)
    }

    companion object {
        private val DOMAIN = "ccpocket/daemon-auth/v1".toByteArray()

        fun defaultPath(): File =
            System.getenv("CC_POCKET_IDENTITY")?.let(::File)
                ?: File(System.getProperty("user.home"), ".cc-pocket/identity.json")

        /** Load the identity from [path], or generate + persist a fresh one (also on a format change). */
        fun loadOrCreate(path: File = defaultPath()): Identity {
            if (path.exists()) runCatching { return load(path.readText()) }
            val id = generate()
            path.parentFile?.mkdirs()
            path.writeText(JSON.encodeToString(id))
            runCatching { Files.setPosixFilePermissions(path.toPath(), PosixFilePermissions.fromString("rw-------")) }
            return load(path.readText())
        }

        private fun generate(): Stored {
            val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val e2e = E2ECrypto.generateKeyPair()
            return Stored(
                v = 2,
                ed25519Priv = b64uEnc(ed.private.encoded), // PKCS#8
                ed25519Pub = b64uEnc(rawTail(ed.public.encoded, 32)),
                e2ePriv = b64uEnc(e2e.privateRaw),
                e2ePub = b64uEnc(e2e.publicRaw),
            )
        }

        private fun load(jsonText: String): Identity {
            val s = JSON.decodeFromString<Stored>(jsonText)
            require(s.v == 2) { "unsupported identity version ${s.v}" }
            val edPriv = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(b64uDec(s.ed25519Priv)))
            val edPub = b64uDec(s.ed25519Pub)
            return Identity(base32(sha256(edPub)), edPub, edPriv, b64uDec(s.e2ePriv), b64uDec(s.e2ePub))
        }

        // Ed25519 SPKI ends with the raw 32-byte key.
        private fun rawTail(encoded: ByteArray, n: Int) = encoded.copyOfRange(encoded.size - n, encoded.size)

        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }

    @Serializable
    private class Stored(
        val v: Int,
        val ed25519Priv: String,
        val ed25519Pub: String,
        val e2ePriv: String,
        val e2ePub: String,
    )
}

// --- encodings (must match the relay's Codec so account ids agree) ---

private val B32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray()
private val urlEnc = Base64.getUrlEncoder().withoutPadding()
private val urlDec = Base64.getUrlDecoder()

internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
internal fun b64uEnc(b: ByteArray): String = urlEnc.encodeToString(b)
internal fun b64uDec(s: String): ByteArray = urlDec.decode(s)

internal fun base32(data: ByteArray): String {
    val sb = StringBuilder((data.size * 8 + 4) / 5)
    var buffer = 0
    var bits = 0
    for (b in data) {
        buffer = (buffer shl 8) or (b.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            sb.append(B32[(buffer ushr bits) and 0x1f])
        }
    }
    if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 0x1f])
    return sb.toString()
}
