package dev.ccpocket.daemon.update

import com.sun.net.httpserver.HttpServer
import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseSignature
import dev.ccpocket.protocol.update.ReleaseTrustedKeys
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * [UpdateService.apply] with signed release manifests, end to end against a local HTTP fixture (never the
 * real mirror or GitHub) and a temp versions dir (never a real install). The shipped trusted list is empty,
 * so the default call must keep today's SHA256SUMS behavior; ENFORCED mode is entered by injecting keys
 * generated for this run.
 */
class UpdateSignatureTest {
    @TempDir lateinit var temp: Path

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val files = mutableMapOf<String, ByteArray>()
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val requests = java.util.concurrent.atomic.AtomicInteger()

    init {
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            val body = synchronized(files) { files[ex.requestURI.path] }
            if (body == null) ex.sendResponseHeaders(404, -1)
            else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
            ex.close()
        }
    }

    @AfterTest
    fun stop() = server.stop(0)

    private class Key {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val public: String = Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())
        fun sigFile(m: ByteArray) = (Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run { initSign(pair.private); update(m); sign() }) + "\n").toByteArray()
    }

    private val key = Key()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Per-test anti-rollback mark (never the shared test-home one, so test order cannot matter). */
    private val highWater: Path get() = temp.resolve("state").resolve("highwater.json")

    private fun manifest(version: String, entries: Map<String, String>, publishedAt: String = "2026-10-05T08:00:00Z") = """
        {"schema":"${ReleaseSignature.SCHEMA}","version":"$version","publishedAt":"$publishedAt",
         "assets":{${entries.entries.joinToString(",") { "\"${it.key}\":{\"sha256\":\"${it.value}\"}" }}}}
    """.trimIndent().toByteArray()

    /** A real archive in the layout apply() expects: `cc-pocket-daemon/bin/cc-pocket-daemon`. */
    private fun archive(): ByteArray {
        val src = Files.createDirectories(temp.resolve("src/cc-pocket-daemon/bin"))
        val launcher = src.resolve("cc-pocket-daemon")
        Files.writeString(launcher, "#!/bin/sh\necho fixture\n")
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"))
        val out = temp.resolve("fixture.tar.gz")
        val code = ProcessBuilder("tar", "-czf", out.toString(), "-C", temp.resolve("src").toString(), "cc-pocket-daemon")
            .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        check(code == 0) { "could not build the fixture archive" }
        return Files.readAllBytes(out)
    }

    /** Publish [version] under [prefix] with whichever of SHA256SUMS / manifest / signature are given. */
    private fun publish(
        prefix: String,
        version: String,
        body: ByteArray,
        sums: Boolean = true,
        manifestBytes: ((String) -> ByteArray?)? = { asset -> manifest(version, mapOf(asset to sha(body))) },
        signature: ((ByteArray) -> ByteArray?)? = { key.sigFile(it) },
    ): Pair<ReleaseClient.Release, String> {
        val asset = UpdateService.assetNameFor(version)
        assumeTrue(asset != null && !System.getProperty("os.name").lowercase().contains("win"), "needs a mac/linux asset")
        val urls = linkedMapOf<String, String>()
        fun put(name: String, bytes: ByteArray) { synchronized(files) { files["$prefix/$name"] = bytes }; urls[name] = "$base$prefix/$name" }
        put(asset!!, body)
        if (sums) put("SHA256SUMS", "${sha(body)}  $asset\n".toByteArray())
        val m = manifestBytes?.invoke(asset)
        if (m != null) {
            put(ReleaseSignature.MANIFEST_ASSET, m)
            signature?.invoke(m)?.let { put(ReleaseSignature.SIGNATURE_ASSET, it) }
        }
        return ReleaseClient.Release(version, urls) to asset
    }

    private fun install() = UpdateService.ManagedInstall(
        versionsDir = Files.createDirectories(temp.resolve("cc-pocket/versions")),
        launcher = temp.resolve("bin/cc-pocket-daemon"),
        serviceAnchored = false,
    )

    private class Recorder : UpdateProgressListener {
        val events = mutableListOf<String>()
        override fun onPhase(phase: UpdatePhase, detail: String) { events += "phase:$phase" }
        override fun onFailed(phase: UpdatePhase?, error: Throwable) { events += "failed:$phase" }
        override fun onSwitched(version: String) { events += "switched" }
    }

    private fun assertSwitchedTo(inst: UpdateService.ManagedInstall, version: String) {
        assertTrue(inst.launcher.isSymbolicLink(), "launcher must be flipped")
        assertTrue(inst.launcher.readSymbolicLink().startsWith(inst.versionsDir.resolve(version)), "${inst.launcher.readSymbolicLink()}")
    }

    private fun refused(failure: ReleaseSignature.Failure, release: ReleaseClient.Release, current: String = "1.0.0") {
        val rec = Recorder()
        val inst = install()
        val e = assertFailsWith<ReleaseSignature.RejectedException> {
            UpdateService.apply(release, inst, rec, trustedKeys = listOf(key.public), current = current, highWaterFile = highWater)
        }
        assertEquals(failure, e.failure, e.message)
        assertEquals(listOf("phase:DOWNLOAD", "phase:VERIFY", "failed:VERIFY"), rec.events)
        assertFalse(inst.launcher.exists(), "launcher must not be switched")
        assertFalse(inst.versionsDir.resolve(release.version).exists(), "nothing may be installed")
    }

    // ── NOT CONFIGURED: the shipped default ─────────────────────────────────────────────────────────

    @Test
    fun the_default_call_uses_the_shipped_empty_list_and_installs_on_sha256sums_alone() {
        assertTrue(ReleaseTrustedKeys.KEYS.isEmpty())
        val (release, _) = publish("/default", "99.3.0", archive(), manifestBytes = null)
        val inst = install()
        UpdateService.apply(release, inst, Recorder())
        assertSwitchedTo(inst, "99.3.0")
    }

    // ── ENFORCED ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun enforced_installs_a_correctly_signed_release() {
        val (release, _) = publish("/ok", "99.4.0", archive(), sums = false)
        val rec = Recorder()
        val inst = install()
        UpdateService.apply(release, inst, rec, trustedKeys = listOf(key.public), current = "1.0.0", highWaterFile = highWater)
        assertSwitchedTo(inst, "99.4.0")
        assertEquals(listOf("phase:DOWNLOAD", "phase:VERIFY", "phase:EXTRACT", "phase:INSTALL", "switched"), rec.events)
    }

    @Test
    fun enforced_refuses_valid_sums_without_a_signature() {
        refused(ReleaseSignature.Failure.MANIFEST_MISSING, publish("/nosig", "99.5.0", archive(), manifestBytes = null).first)
        refused(ReleaseSignature.Failure.SIGNATURE_MISSING, publish("/nosig2", "99.5.1", archive(), signature = { null }).first)
    }

    @Test
    fun enforced_refuses_a_signature_from_an_untrusted_key() {
        val stranger = Key()
        refused(ReleaseSignature.Failure.SIGNATURE_INVALID, publish("/stranger", "99.6.0", archive(), signature = { stranger.sigFile(it) }).first)
    }

    @Test
    fun enforced_refuses_an_artifact_that_differs_from_the_signed_hash() {
        val other = "not the archive".toByteArray()
        val (release, _) = publish("/hash", "99.7.0", archive(),
            manifestBytes = { asset -> manifest("99.7.0", mapOf(asset to sha(other))) })
        refused(ReleaseSignature.Failure.HASH_MISMATCH, release)
    }

    @Test
    fun enforced_refuses_a_release_that_is_not_newer_than_the_running_one() {
        refused(ReleaseSignature.Failure.NOT_NEWER, publish("/old", "99.8.0", archive()).first, current = "99.8.0")
    }

    @Test
    fun enforced_refuses_the_pre_hotfix_manifest_once_the_re_signed_one_was_seen() {
        val body = archive()
        // the re-signed (hotfix) manifest was seen first — here its download did not match, so nothing installed
        val (resigned, _) = publish("/hotfix", "99.9.0", body,
            manifestBytes = { a -> manifest("99.9.0", mapOf(a to sha("other".toByteArray())), publishedAt = "2026-10-06T00:00:00Z") })
        refused(ReleaseSignature.Failure.HASH_MISMATCH, resigned)
        // a mirror then serves the older, genuinely signed manifest of the same version with its package
        refused(ReleaseSignature.Failure.ROLLBACK, publish("/pre-hotfix", "99.9.0", body).first)
    }

    @Test
    fun a_path_like_version_is_refused_before_anything_is_downloaded_or_written() {
        for (keys in listOf(emptyList(), listOf(key.public))) {
            val inst = install()
            synchronized(files) { files["/evil/x"] = archive() }
            requests.set(0)
            val release = ReleaseClient.Release("../../evil", mapOf(
                "cc-pocket-daemon-../../evil-linux-x86_64.tar.gz" to "$base/evil/x",
                "cc-pocket-daemon-../../evil-macos-arm64.tar.gz" to "$base/evil/x",
                "cc-pocket-daemon-../../evil-macos-x86_64.tar.gz" to "$base/evil/x"))
            val e = assertFailsWith<IllegalStateException> {
                UpdateService.apply(release, inst, Recorder(), trustedKeys = keys, current = "1.0.0", highWaterFile = highWater)
            }
            assertTrue(e.message!!.contains("invalid version"), e.message)
            assertEquals(emptyList(), Files.list(inst.versionsDir).use { it.toList() }, "nothing written under versions/")
            assertFalse(inst.launcher.exists())
            assertFalse(highWater.exists())
            assertEquals(0, requests.get(), "nothing may be downloaded")
        }
    }
}
