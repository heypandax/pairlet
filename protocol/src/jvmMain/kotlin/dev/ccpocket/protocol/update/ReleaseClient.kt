package dev.ccpocket.protocol.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * The JVM half of the self-update plumbing shared by the daemon and the desktop app: read the latest GitHub
 * release (version + asset download URLs), fetch an asset, and verify it against the release's SHA256SUMS.
 * Both binaries publish under the same repo with one SHA256SUMS over every asset, so this reads identically
 * for either — the only thing that differs is which asset name each downloads (a daemon tarball vs a desktop
 * dmg/msi), which the caller decides. Kept dependency-light (java.net.http only) so pulling it into the
 * Compose Desktop client adds nothing the daemon didn't already carry.
 */
object ReleaseClient {
    const val DEFAULT_REPO = "heypandax/cc-pocket"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** A release: its version (no `v` prefix) and every asset's name → browser_download_url. */
    data class Release(val version: String, val assetUrls: Map<String, String>)

    /** The newest published release, or null when GitHub is unreachable / returns nothing usable. */
    fun latest(repo: String = DEFAULT_REPO): Release? = try {
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/$repo/releases/latest"))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "cc-pocket")
            .timeout(Duration.ofSeconds(20))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) null else {
            val obj = json.parseToJsonElement(res.body()) as? JsonObject
            val tag = (obj?.get("tag_name") as? JsonPrimitive)?.contentOrNull
            if (obj == null || tag == null) null else {
                val assets = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val a = el as? JsonObject ?: return@mapNotNull null
                    val name = (a["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val url = (a["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    name to url
                }.toMap()
                Release(tag.removePrefix("v"), assets)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Download [url] to [dest] (10-minute ceiling for a large artifact). Throws on a non-2xx status. */
    fun download(url: String, dest: Path) {
        val req = HttpRequest.newBuilder(URI(url)).header("User-Agent", "cc-pocket")
            .timeout(Duration.ofMinutes(10)).build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
        check(res.statusCode() in 200..299) { "download failed (HTTP ${res.statusCode()}): $url" }
    }

    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(file).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify [file] (published as [asset]) against the release's SHA256SUMS asset. A missing sums entry (old
     * releases, or an asset the manifest doesn't list) warns via [onSkip] and passes — a PRESENT mismatch is
     * fatal (corrupted download or tampered artifact).
     */
    fun verifyAgainstSums(release: Release, asset: String, file: Path, onSkip: (String) -> Unit = {}) {
        val sumsUrl = release.assetUrls["SHA256SUMS"] ?: run {
            onSkip("release has no SHA256SUMS — skipping checksum verification"); return
        }
        val req = HttpRequest.newBuilder(URI(sumsUrl)).header("User-Agent", "cc-pocket")
            .timeout(Duration.ofSeconds(30)).build()
        val body = http.send(req, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
            ?: run { onSkip("could not fetch SHA256SUMS — skipping checksum verification"); return }
        val expected = ReleaseVersions.parseSums(body)[asset] ?: run {
            onSkip("SHA256SUMS has no entry for $asset — skipping checksum verification"); return
        }
        val actual = sha256(file)
        check(actual.equals(expected, ignoreCase = true)) {
            "checksum mismatch for $asset\n  expected $expected\n  actual   $actual\n(corrupted download or tampered artifact — aborting)"
        }
    }
}
