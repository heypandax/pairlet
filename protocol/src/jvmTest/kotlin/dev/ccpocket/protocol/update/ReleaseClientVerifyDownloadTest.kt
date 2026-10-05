package dev.ccpocket.protocol.update

import com.sun.net.httpserver.HttpServer
import dev.ccpocket.protocol.update.ReleaseSignature.Failure
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ReleaseClient.verifyDownload] over real HTTP (a local server — never GitHub or the mirror).
 *
 * NOT CONFIGURED (empty trusted list) must be byte-for-byte the old SHA256SUMS behavior. ENFORCED must apply
 * the same rules whether the asset map came from the GitHub API or from the mirror's latest.json, and must
 * never fall back to SHA256SUMS.
 */
class ReleaseClientVerifyDownloadTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val dir: Path = Files.createTempDirectory("rc-verify-test")
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    private val key = TestSigningKey()
    private val version = "9.1.0"
    private val asset = "cc-pocket-daemon-9.1.0-linux-x86_64.tar.gz"
    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val file: Path = dir.resolve(asset).also { Files.write(it, payload) }
    private val manifest = manifestJson(version, mapOf(asset to sha256Hex(payload)))

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val body = files[path]
            if (body == null) ex.sendResponseHeaders(404, -1)
            else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
            ex.close()
        }
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun publish(prefix: String, name: String, bytes: ByteArray) { files["$prefix/$name"] = bytes }

    /** Publish a release under [prefix]; the returned asset map lists exactly what was published. */
    private fun release(
        prefix: String,
        sums: String? = "${sha256Hex(payload)}  $asset\n",
        manifestBytes: ByteArray? = manifest,
        signature: ByteArray? = key.signatureFile(manifest),
    ): Map<String, String> {
        val urls = linkedMapOf(asset to "$base$prefix/$asset")
        publish(prefix, asset, payload)
        sums?.let { publish(prefix, "SHA256SUMS", it.toByteArray()); urls["SHA256SUMS"] = "$base$prefix/SHA256SUMS" }
        manifestBytes?.let {
            publish(prefix, ReleaseSignature.MANIFEST_ASSET, it)
            urls[ReleaseSignature.MANIFEST_ASSET] = "$base$prefix/${ReleaseSignature.MANIFEST_ASSET}"
        }
        signature?.let {
            publish(prefix, ReleaseSignature.SIGNATURE_ASSET, it)
            urls[ReleaseSignature.SIGNATURE_ASSET] = "$base$prefix/${ReleaseSignature.SIGNATURE_ASSET}"
        }
        return urls
    }

    /** The same published files seen through both sources: the GitHub-API shape and the mirror's latest.json. */
    private fun bothSources(urls: Map<String, String>): List<ReleaseClient.Release> {
        val github = ReleaseClient.Release(version, urls)
        val latestJson = """{"version":"v$version","assets":{${urls.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}"""
        val mirror = ReleaseClient.parseManifest(latestJson)!!
        assertEquals(github, mirror)
        return listOf(github, mirror)
    }

    private fun enforced(release: ReleaseClient.Release, keys: List<String> = listOf(key.publicBase64), current: String = "9.0.0") =
        ReleaseClient.verifyDownload(release, asset, file, current, trustedKeys = keys)

    private fun refused(failure: Failure, block: () -> Unit) {
        val e = assertFailsWith<ReleaseSignature.RejectedException> { block() }
        assertEquals(failure, e.failure, e.message)
    }

    // ── NOT CONFIGURED: identical to verifyAgainstSums ────────────────────────────────────────────────

    @Test
    fun not_configured_verifies_against_sha256sums_exactly_as_before() {
        val rel = ReleaseClient.Release(version, release("/nc-ok", manifestBytes = null, signature = null))
        assertTrue(ReleaseClient.verifyDownload(rel, asset, file, "9.0.0", trustedKeys = emptyList()))
        assertEquals(1, hits["/nc-ok/SHA256SUMS"]?.get())
    }

    @Test
    fun not_configured_keeps_the_present_mismatch_failure_and_message() {
        val rel = ReleaseClient.Release(version, release("/nc-bad", sums = "${"0".repeat(64)}  $asset\n"))
        val viaNew = assertFailsWith<IllegalStateException> { ReleaseClient.verifyDownload(rel, asset, file, "9.0.0", trustedKeys = emptyList()) }
        val viaOld = assertFailsWith<IllegalStateException> { ReleaseClient.verifyAgainstSums(rel, asset, file) }
        assertEquals(viaOld.message, viaNew.message)
        assertTrue(viaNew.message!!.startsWith("checksum mismatch"), viaNew.message)
    }

    @Test
    fun not_configured_keeps_the_fail_open_skip_on_missing_sums() {
        val rel = ReleaseClient.Release(version, release("/nc-none", sums = null, manifestBytes = null, signature = null))
        val skipped = mutableListOf<String>()
        assertFalse(ReleaseClient.verifyDownload(rel, asset, file, "9.0.0", onSkip = { skipped += it }, trustedKeys = emptyList()))
        assertEquals(listOf("release has no SHA256SUMS — skipping checksum verification"), skipped)
    }

    @Test
    fun not_configured_ignores_signature_files_entirely() {
        // a broken signature must not change today's behavior: the list is empty, so nothing is enforced
        val rel = ReleaseClient.Release(version, release("/nc-sig", signature = "garbage".toByteArray()))
        assertTrue(ReleaseClient.verifyDownload(rel, asset, file, "9.0.0", trustedKeys = emptyList()))
        assertEquals(null, hits["/nc-sig/${ReleaseSignature.SIGNATURE_ASSET}"])
    }

    // ── ENFORCED: the same rules for GitHub and the mirror ───────────────────────────────────────────

    @Test
    fun enforced_accepts_a_signed_release_from_either_source_without_reading_sha256sums() {
        for (rel in bothSources(release("/ok"))) assertTrue(enforced(rel))
        assertEquals(null, hits["/ok/SHA256SUMS"], "ENFORCED mode must not consult SHA256SUMS")
    }

    @Test
    fun enforced_refuses_a_mirror_that_has_valid_sums_but_no_signature() {
        val urls = release("/mirror-unsigned", manifestBytes = null, signature = null)
        for (rel in bothSources(urls)) refused(Failure.MANIFEST_MISSING) { enforced(rel) }
        val manifestOnly = release("/mirror-nosig", signature = null)
        for (rel in bothSources(manifestOnly)) refused(Failure.SIGNATURE_MISSING) { enforced(rel) }
        assertEquals(null, hits["/mirror-unsigned/SHA256SUMS"], "no fallback to SHA256SUMS")
    }

    @Test
    fun enforced_refuses_when_the_listed_signature_cannot_be_fetched() {
        val urls = release("/gone")
        files.remove("/gone/${ReleaseSignature.SIGNATURE_ASSET}") // listed, but the server 404s
        for (rel in bothSources(urls)) refused(Failure.SIGNATURE_MISSING) { enforced(rel) }
        files.remove("/gone/${ReleaseSignature.MANIFEST_ASSET}")
        for (rel in bothSources(urls)) refused(Failure.MANIFEST_MISSING) { enforced(rel) }
    }

    @Test
    fun enforced_refuses_tampering_from_either_source() {
        val forged = manifestJson(version, mapOf(asset to sha256Hex(payload), "extra.zip" to "1".repeat(64)))
        for (rel in bothSources(release("/swap", manifestBytes = forged))) refused(Failure.SIGNATURE_INVALID) { enforced(rel) }
        val stranger = TestSigningKey()
        for (rel in bothSources(release("/stranger", signature = stranger.signatureFile(manifest)))) {
            refused(Failure.SIGNATURE_INVALID) { enforced(rel) }
        }
        val otherBytes = manifestJson(version, mapOf(asset to sha256Hex("not this".toByteArray())))
        for (rel in bothSources(release("/hash", manifestBytes = otherBytes, signature = key.signatureFile(otherBytes)))) {
            refused(Failure.HASH_MISMATCH) { enforced(rel) }
        }
    }

    @Test
    fun enforced_refuses_a_replayed_release_that_is_not_newer() {
        for (rel in bothSources(release("/replay"))) {
            refused(Failure.NOT_NEWER) { enforced(rel, current = version) }
            refused(Failure.NOT_NEWER) { enforced(rel, current = "10.0.0") }
        }
    }

    @Test
    fun enforced_refuses_an_oversized_manifest() {
        val huge = ByteArray(ReleaseSignature.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }
        for (rel in bothSources(release("/huge", manifestBytes = huge, signature = key.signatureFile(huge)))) {
            refused(Failure.MANIFEST_MISSING) { enforced(rel) }
        }
    }

    @Test
    fun enforced_accepts_any_trusted_key_and_rejects_a_removed_one() {
        val next = TestSigningKey()
        val rel = ReleaseClient.Release(version, release("/rotate"))
        assertTrue(enforced(rel, keys = listOf(next.publicBase64, key.publicBase64)))
        refused(Failure.SIGNATURE_INVALID) { enforced(rel, keys = listOf(next.publicBase64)) }
    }
}
