package dev.ccpocket.protocol.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Release mirror on the relay box (deploy/mirror-sync.sh) — tried before GitHub, because GitHub's
     * CDN crawls from mainland China. Its `latest.json` carries the release's COMPLETE asset map
     * (mirrored artifacts point at the mirror, everything else keeps its GitHub URL), so a [Release]
     * from either source is interchangeable — including for assets the mirror doesn't host (desktop
     * dmg/msi). Env `CC_POCKET_MIRROR=off` disables it; any other value overrides the base URL.
     */
    const val DEFAULT_MIRROR = "https://pocket.ark-nexus.cc/dl"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** A release: its version (no `v` prefix) and every asset's name → browser_download_url. */
    data class Release(val version: String, val assetUrls: Map<String, String>)

    /** The newest published release — mirror first (our repo only), GitHub as fallback and authority.
     *  Null when both are unreachable / return nothing usable. */
    fun latest(repo: String = DEFAULT_REPO): Release? =
        (if (repo == DEFAULT_REPO) mirrorLatest() else null) ?: githubLatest(repo)

    private fun mirrorLatest(): Release? = try {
        val base = when (val m = System.getenv("CC_POCKET_MIRROR")?.trim()) {
            null, "" -> DEFAULT_MIRROR
            "off", "0", "false" -> return null
            else -> m.trimEnd('/')
        }
        val req = HttpRequest.newBuilder(URI("$base/latest.json"))
            .header("User-Agent", "cc-pocket")
            .timeout(Duration.ofSeconds(5)) // a dead mirror may only ever cost 5s before GitHub takes over
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) null else parseManifest(res.body())
    } catch (_: Exception) {
        null
    }

    /** Parse the mirror's `latest.json` (`{"version":"1.6.2","assets":{"<name>":"<url>",…}}`).
     *  Null on any shape mismatch, so a broken/foreign body degrades to the GitHub path. */
    internal fun parseManifest(body: String): Release? = try {
        val obj = json.parseToJsonElement(body) as? JsonObject
        val version = (obj?.get("version") as? JsonPrimitive)?.contentOrNull?.removePrefix("v")
        val assets = (obj?.get("assets") as? JsonObject)?.mapNotNull { (name, url) ->
            ((url as? JsonPrimitive)?.contentOrNull)?.let { name to it }
        }?.toMap()
        if (version.isNullOrBlank() || assets.isNullOrEmpty()) null else Release(version, assets)
    } catch (_: Exception) {
        null
    }

    private fun githubLatest(repo: String): Release? = try {
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
    } catch (_: Exception) {
        null
    }

    /**
     * One observation of a running download (issue #381). [totalBytes] is the response's Content-Length
     * only while it is still believable — absent, zero, or already exceeded by what arrived all mean
     * "unknown", so a renderer never has to invent a percentage.
     */
    data class DownloadProgress(val receivedBytes: Long, val totalBytes: Long?) {
        companion object {
            fun of(received: Long, declared: Long?): DownloadProgress =
                DownloadProgress(received, declared?.takeIf { it > 0 && received <= it })
        }
    }

    /**
     * Download [url] to [dest] with no total duration limit. Throws on a non-2xx status.
     * [onProgress] is called from the calling thread about every 100 ms from the moment the request is sent —
     * `(0, null)` while connecting / redirecting / waiting for headers, then the real count — also when no new
     * bytes arrived, so an observer can tell "waiting for the network" apart, and once more with the final
     * count. It is display-only: an exception from it is swallowed and never affects the file.
     *
     * Waiting for headers or receiving no body data for 2 minutes aborts with an [HttpTimeoutException].
     * A slow transfer that keeps delivering bytes may run as long as needed. This applies to EVERY caller,
     * including the desktop standalone updater. A non-2xx response never writes its error page into [dest].
     */
    fun download(url: String, dest: Path, onProgress: (DownloadProgress) -> Unit = {}) =
        download(url, dest, onProgress, DOWNLOAD_HEADERS, DOWNLOAD_STALL)

    private val DOWNLOAD_HEADERS: Duration = Duration.ofMinutes(2)
    private val DOWNLOAD_STALL: Duration = Duration.ofMinutes(2)

    /** [download] with injectable limits (tests). The body is streamed straight into [dest] one network
     *  buffer at a time — the next buffer is only requested after the previous one is on disk — so memory
     *  stays bounded however large the artifact is. On success, failure, timeout or interruption the file
     *  channel is closed and the HTTP exchange is cancelled; no thread is left parked on the socket. */
    internal fun download(
        url: String,
        dest: Path,
        onProgress: (DownloadProgress) -> Unit,
        headerTimeout: Duration,
        stallTimeout: Duration,
        tick: Duration = Duration.ofMillis(100),
    ) {
        val startNs = System.nanoTime()
        // Bound the wait for headers below, independently of the body. A request-wide deadline would
        // cut off healthy downloads on slow links (large artifacts can take 1500s or longer).
        val req = HttpRequest.newBuilder(URI(url)).header("User-Agent", "cc-pocket").build()
        val sinkRef = AtomicReference<FileSink?>() // set on an HttpClient thread, read by this one
        val aborted = AtomicBoolean(false)
        val handler = HttpResponse.BodyHandler<Unit> { info ->
            if (info.statusCode() !in 200..299 || aborted.get()) HttpResponse.BodySubscribers.replacing(Unit)
            else {
                val sink = FileSink(dest, info.headers().firstValueAsLong("content-length").orElse(-1L))
                sinkRef.set(sink)
                // abort() may have run between the check above and set(): it sets the flag BEFORE reading
                // sinkRef, so re-checking here guarantees one side closes the channel we just opened
                if (aborted.get()) sink.abort()
                sink
            }
        }
        val future = http.sendAsync(req, handler)
        var observerBroken = false
        fun report() {
            if (observerBroken) return
            val s = sinkRef.get()
            val p = if (s == null) DownloadProgress(0, null) // connecting / redirecting / awaiting headers
            else DownloadProgress.of(s.received.get(), s.declared.takeIf { it > 0 })
            try { onProgress(p) }
            catch (_: Exception) { observerBroken = true } // a renderer bug must not cost the download
        }
        fun abort(cause: Throwable): Nothing {
            aborted.set(true)
            sinkRef.get()?.abort()
            future.cancel(true)
            throw cause
        }
        val res = try {
            var result: HttpResponse<Unit>? = null
            while (result == null) {
                try {
                    result = future.get(tick.toMillis(), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    val now = System.nanoTime()
                    val s = sinkRef.get()
                    if (s == null && now - startNs > headerTimeout.toNanos()) {
                        abort(HttpTimeoutException("download timed out waiting for response after ${headerTimeout.toSeconds()}s: $url"))
                    }
                    if (s != null && now - s.lastByteNs.get() > stallTimeout.toNanos()) {
                        abort(HttpTimeoutException(
                            "download stalled — no data for ${stallTimeout.toSeconds()}s after ${s.received.get()} bytes: $url"))
                    }
                    report()
                }
            }
            result
        } catch (e: ExecutionException) {
            abort(e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            abort(e)
        } catch (e: CancellationException) {
            abort(e)
        }
        check(res.statusCode() in 200..299) { "download failed (HTTP ${res.statusCode()}): $url" }
        report()
    }

    /** Streams a 2xx body into [dest]: counts bytes, writes each buffer before asking for the next. */
    private class FileSink(dest: Path, val declared: Long) : HttpResponse.BodySubscriber<Unit> {
        val received = AtomicLong(0)
        val lastByteNs = AtomicLong(System.nanoTime()) // headers just arrived: the stall clock starts now
        private val lock = Any()
        private val channel: FileChannel = FileChannel.open(
            dest, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        private var subscription: Flow.Subscription? = null
        private var closed = false
        private val done = CompletableFuture<Unit>()

        override fun getBody(): CompletionStage<Unit> = done

        override fun onSubscribe(s: Flow.Subscription) {
            val cancelled = synchronized(lock) { subscription = s; closed }
            if (cancelled) s.cancel() else s.request(1)
        }

        override fun onNext(item: List<ByteBuffer>) {
            val s = synchronized(lock) {
                if (closed) return
                try {
                    for (buf in item) {
                        val n = buf.remaining().toLong()
                        while (buf.hasRemaining()) channel.write(buf)
                        received.addAndGet(n)
                    }
                } catch (e: IOException) {
                    closeLocked()
                    subscription?.cancel()
                    done.completeExceptionally(e)
                    return
                }
                lastByteNs.set(System.nanoTime())
                subscription
            }
            s?.request(1)
        }

        override fun onError(t: Throwable) {
            synchronized(lock) { closeLocked() }
            done.completeExceptionally(t)
        }

        override fun onComplete() {
            val error = synchronized(lock) {
                if (closed) return
                runCatching { channel.force(false) }
                closeLocked()
                if (declared > 0 && received.get() < declared)
                    IOException("download ended after ${received.get()} of $declared bytes")
                else null
            }
            if (error != null) done.completeExceptionally(error) else done.complete(Unit)
        }

        /** Caller-side abort (timeout / interrupt): stop writing, release the file, drop the exchange. */
        fun abort() {
            val s = synchronized(lock) { closeLocked(); subscription }
            s?.cancel()
            done.completeExceptionally(CancellationException("download aborted"))
        }

        private fun closeLocked() {
            if (closed) return
            closed = true
            runCatching { channel.close() }
        }
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
     * fatal (corrupted download or tampered artifact). Returns true when the checksum was actually verified,
     * false when verification was skipped.
     */
    fun verifyAgainstSums(release: Release, asset: String, file: Path, onSkip: (String) -> Unit = {}): Boolean {
        val sumsUrl = release.assetUrls["SHA256SUMS"] ?: run {
            onSkip("release has no SHA256SUMS — skipping checksum verification"); return false
        }
        val req = HttpRequest.newBuilder(URI(sumsUrl)).header("User-Agent", "cc-pocket")
            .timeout(Duration.ofSeconds(30)).build()
        val body = http.send(req, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
            ?: run { onSkip("could not fetch SHA256SUMS — skipping checksum verification"); return false }
        val expected = ReleaseVersions.parseSums(body)[asset] ?: run {
            onSkip("SHA256SUMS has no entry for $asset — skipping checksum verification"); return false
        }
        val actual = sha256(file)
        check(actual.equals(expected, ignoreCase = true)) {
            "checksum mismatch for $asset\n  expected $expected\n  actual   $actual\n(corrupted download or tampered artifact — aborting)"
        }
        return true
    }
}
