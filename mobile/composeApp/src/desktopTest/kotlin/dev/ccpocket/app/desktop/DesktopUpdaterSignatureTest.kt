package dev.ccpocket.app.desktop

import com.sun.net.httpserver.HttpServer
import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseSignature
import dev.ccpocket.protocol.update.ReleaseTrustedKeys
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The desktop self-updater's download + verify step ([DesktopUpdater.downloadVerified]) against a local HTTP
 * fixture. The shipped trusted list is empty, so the default keeps the SHA256SUMS check; injected keys put
 * it in ENFORCED mode, where only a signed, newer, matching release gets through to the swap/installer.
 */
class DesktopUpdaterSignatureTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val files = mutableMapOf<String, ByteArray>()
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())
    private val asset = "cc-pocket-desktop-macos-arm64.dmg"
    private val dmg = ByteArray(32 * 1024) { (it * 7).toByte() }

    init {
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            val body = synchronized(files) { files[ex.requestURI.path] }
            if (body == null) ex.sendResponseHeaders(404, -1)
            else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
            ex.close()
        }
    }

    /** Per-test anti-rollback mark — never the real ~/.cc-pocket-app one. */
    private val stateDir = Files.createTempDirectory("desktop-updater-state")
    private val hw = stateDir.resolve("highwater.json")
    private val requests = java.util.concurrent.atomic.AtomicInteger()

    @AfterTest
    fun stop() {
        server.stop(0)
        stateDir.toFile().deleteRecursively()
    }

    @Test
    fun a_path_like_version_is_refused_before_anything_is_downloaded() {
        synchronized(files) { files["/evil/$asset"] = dmg }
        for (keys in listOf(emptyList(), listOf(publicKey))) {
            requests.set(0)
            val e = assertFailsWith<IllegalStateException> {
                DesktopUpdater.downloadVerified(ReleaseClient.Release("../evil", mapOf(asset to "$base/evil/$asset")), asset,
                    current = "2.0.0", trustedKeys = keys, highWaterFile = hw)
            }
            assertTrue(e.message!!.contains("invalid version"), e.message)
            assertEquals(0, requests.get(), "nothing may be downloaded")
        }
    }

    @Test
    fun enforced_records_the_mark_and_refuses_an_older_manifest_of_the_same_version() {
        DesktopUpdater.downloadVerified(release("/hw", "9.5.0", signed = true, publishedAt = "2026-10-06T00:00:00Z"), asset,
            current = "2.0.0", trustedKeys = listOf(publicKey), highWaterFile = hw)
        val e = assertFailsWith<ReleaseSignature.RejectedException> {
            DesktopUpdater.downloadVerified(release("/hw-old", "9.5.0", signed = true, publishedAt = "2026-10-05T00:00:00Z"), asset,
                current = "2.0.0", trustedKeys = listOf(publicKey), highWaterFile = hw)
        }
        assertEquals(ReleaseSignature.Failure.ROLLBACK, e.failure)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sign(m: ByteArray) = (Base64.getEncoder().encodeToString(
        Signature.getInstance("Ed25519").run { initSign(pair.private); update(m); sign() }) + "\n").toByteArray()

    private fun release(
        prefix: String,
        version: String,
        signed: Boolean,
        sumsHash: String = sha(dmg),
        publishedAt: String = "2026-10-05T08:00:00Z",
    ): ReleaseClient.Release {
        val urls = linkedMapOf<String, String>()
        fun put(name: String, bytes: ByteArray) { synchronized(files) { files["$prefix/$name"] = bytes }; urls[name] = "$base$prefix/$name" }
        put(asset, dmg)
        put("SHA256SUMS", "$sumsHash  $asset\n".toByteArray())
        if (signed) {
            val m = """{"schema":"${ReleaseSignature.SCHEMA}","version":"$version","publishedAt":"$publishedAt","assets":{"$asset":{"sha256":"${sha(dmg)}"}}}""".toByteArray()
            put(ReleaseSignature.MANIFEST_ASSET, m)
            put(ReleaseSignature.SIGNATURE_ASSET, sign(m))
        }
        return ReleaseClient.Release(version, urls)
    }

    @Test
    fun not_configured_by_default_and_verifies_against_sha256sums_as_before() {
        assertTrue(ReleaseTrustedKeys.KEYS.isEmpty())
        val file = DesktopUpdater.downloadVerified(release("/d", "9.0.0", signed = false), asset, current = "2.0.0", highWaterFile = hw)
        assertContentEquals(dmg, Files.readAllBytes(file))
        val e = assertFailsWith<IllegalStateException> {
            DesktopUpdater.downloadVerified(release("/d2", "9.0.0", signed = false, sumsHash = "0".repeat(64)), asset, current = "2.0.0", highWaterFile = hw)
        }
        assertTrue(e.message!!.startsWith("checksum mismatch"), e.message)
    }

    @Test
    fun enforced_accepts_a_signed_release() {
        val file = DesktopUpdater.downloadVerified(release("/e", "9.1.0", signed = true), asset, current = "2.0.0", trustedKeys = listOf(publicKey), highWaterFile = hw)
        assertContentEquals(dmg, Files.readAllBytes(file))
    }

    @Test
    fun enforced_refuses_an_unsigned_release_even_with_valid_sums() {
        val e = assertFailsWith<ReleaseSignature.RejectedException> {
            DesktopUpdater.downloadVerified(release("/u", "9.2.0", signed = false), asset, current = "2.0.0", trustedKeys = listOf(publicKey), highWaterFile = hw)
        }
        assertEquals(ReleaseSignature.Failure.MANIFEST_MISSING, e.failure)
    }

    @Test
    fun enforced_refuses_a_downgrade_or_reinstall() {
        val e = assertFailsWith<ReleaseSignature.RejectedException> {
            DesktopUpdater.downloadVerified(release("/r", "9.3.0", signed = true), asset, current = "9.3.0", trustedKeys = listOf(publicKey), highWaterFile = hw)
        }
        assertEquals(ReleaseSignature.Failure.NOT_NEWER, e.failure)
    }

    @Test
    fun enforced_refuses_a_key_that_is_not_trusted() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(other.public.encoded.takeLast(32).toByteArray())
        val e = assertFailsWith<ReleaseSignature.RejectedException> {
            DesktopUpdater.downloadVerified(release("/k", "9.4.0", signed = true), asset, current = "2.0.0", trustedKeys = listOf(otherKey), highWaterFile = hw)
        }
        assertEquals(ReleaseSignature.Failure.SIGNATURE_INVALID, e.failure)
    }
}
