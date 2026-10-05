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

    init {
        server.createContext("/") { ex ->
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

    private fun manifest(version: String, entries: Map<String, String>) = """
        {"schema":"${ReleaseSignature.SCHEMA}","version":"$version","publishedAt":"2026-10-05T08:00:00Z",
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
            UpdateService.apply(release, inst, rec, trustedKeys = listOf(key.public), current = current)
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
        UpdateService.apply(release, inst, rec, trustedKeys = listOf(key.public), current = "1.0.0")
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
}
