package dev.ccpocket.protocol.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #381: the streaming download behind `cc-pocket-daemon update`, against a real local HTTP server.
 * Covers byte-exact output, progress with and without Content-Length, redirects, HTTP errors, a body cut
 * short, a body that stalls after the headers (the connection must actually be torn down), a lying
 * Content-Length, callback rate and callback-exception isolation.
 */
class ReleaseClientDownloadTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val dir: Path = Files.createTempDirectory("rc-download-test")
    private val payload = Random(381).nextBytes(3 * 1024 * 1024 + 17)

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun route(path: String, handler: (HttpExchange) -> Unit) = server.createContext(path) { ex ->
        try { handler(ex) } catch (_: IOException) { } finally { runCatching { ex.close() } }
    }

    private fun serveFixed(path: String, bytes: ByteArray) = route(path) { ex ->
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    @Test
    fun legacy_two_argument_call_still_compiles_and_writes_identical_bytes() {
        serveFixed("/a", payload)
        val dest = dir.resolve("a.bin")
        ReleaseClient.download("$base/a", dest)
        assertContentEquals(payload, Files.readAllBytes(dest))
    }

    @Test
    fun known_length_reports_a_trusted_total_and_ends_at_the_full_size() {
        serveFixed("/k", payload)
        val seen = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
        val dest = dir.resolve("k.bin")
        ReleaseClient.download("$base/k", dest) { seen += it }
        assertContentEquals(payload, Files.readAllBytes(dest))
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.totalBytes == payload.size.toLong() })
        assertEquals(payload.size.toLong(), seen.last().receivedBytes)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.receivedBytes >= a.receivedBytes }, "progress never goes backwards")
    }

    @Test
    fun chunked_body_reports_no_total() {
        route("/chunked") { ex ->
            ex.sendResponseHeaders(200, 0) // 0 = chunked, no Content-Length
            payload.asList().chunked(256 * 1024).forEach { ex.responseBody.write(it.toByteArray()); ex.responseBody.flush() }
        }
        val seen = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
        val dest = dir.resolve("c.bin")
        ReleaseClient.download("$base/chunked", dest) { seen += it }
        assertContentEquals(payload, Files.readAllBytes(dest))
        assertTrue(seen.all { it.totalBytes == null })
        assertEquals(payload.size.toLong(), seen.last().receivedBytes)
    }

    @Test
    fun follows_redirects_to_the_asset() {
        serveFixed("/real", payload)
        route("/moved") { ex ->
            ex.responseHeaders.add("Location", "$base/real")
            ex.sendResponseHeaders(302, -1)
        }
        val dest = dir.resolve("r.bin")
        ReleaseClient.download("$base/moved", dest) {}
        assertContentEquals(payload, Files.readAllBytes(dest))
    }

    @Test
    fun http_errors_fail_with_the_status() {
        route("/missing") { ex -> ex.sendResponseHeaders(404, 3); ex.responseBody.write("nope".toByteArray(), 0, 3) }
        route("/broken") { ex -> ex.sendResponseHeaders(503, -1) }
        for ((path, code) in listOf("/missing" to 404, "/broken" to 503)) {
            val progress = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
            val e = assertFailsWith<IllegalStateException> { ReleaseClient.download("$base$path", dir.resolve("e.bin")) { progress += it } }
            assertTrue(e.message!!.contains("HTTP $code"), e.message)
            // only pre-header "(0, null)" ticks are allowed — an error page is not download progress
            assertTrue(progress.all { it == ReleaseClient.DownloadProgress(0, null) }, "$progress")
            assertFalse(Files.exists(dir.resolve("e.bin")), "the error page is not written to the destination")
        }
    }

    @Test
    fun body_interrupted_before_the_declared_length_fails() {
        // raw socket: promise 2 MB, send 1 KB, hang up
        val raw = ServerSocket(0)
        thread(isDaemon = true) {
            raw.accept().use { s ->
                s.getInputStream().read(ByteArray(8192))
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2097152\r\nConnection: close\r\n\r\n".toByteArray())
                s.getOutputStream().write(ByteArray(1024))
                s.getOutputStream().flush()
            }
        }
        // The JDK client itself fails a fixed-length body that hits EOF early (IOException); FileSink's own
        // received < declared check in onComplete is defense in depth for a client that would not.
        try {
            assertFailsWith<IOException> { ReleaseClient.download("http://127.0.0.1:${raw.localPort}/", dir.resolve("short.bin")) }
        } finally { raw.close() }
    }

    @Test
    fun declared_length_smaller_than_body_never_reports_over_100_percent() {
        // raw socket: promise 1000 bytes, send 5000 — whatever the client keeps, progress must stay honest
        val raw = ServerSocket(0)
        thread(isDaemon = true) {
            raw.accept().use { s ->
                s.getInputStream().read(ByteArray(8192))
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n".toByteArray())
                s.getOutputStream().write(ByteArray(5000) { 7 })
                s.getOutputStream().flush()
            }
        }
        val seen = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
        val dest = dir.resolve("long.bin")
        try {
            // HTTP/1.1 framing: the client reads exactly the declared 1000 bytes and succeeds; the extra bytes
            // are never part of the body (a wrong file is then rejected by the SHA256SUMS check upstream)
            ReleaseClient.download("http://127.0.0.1:${raw.localPort}/", dest) { seen += it }
        } finally { raw.close() }
        assertEquals(1000L, Files.size(dest))
        assertEquals(ReleaseClient.DownloadProgress(1000, 1000), seen.last())
        assertTrue(seen.none { p -> p.totalBytes != null && p.receivedBytes > p.totalBytes!! }, "no progress above the declared total: $seen")
        // and the pure rule itself: once the body overshoots, the total is no longer trusted
        assertNull(ReleaseClient.DownloadProgress.of(1001, 1000).totalBytes)
        assertEquals(1000L, ReleaseClient.DownloadProgress.of(1000, 1000).totalBytes)
        assertNull(ReleaseClient.DownloadProgress.of(10, 0).totalBytes)
    }

    @Test
    fun stalled_body_times_out_and_tears_down_the_connection() {
        val clientGaveUp = CountDownLatch(1)
        val serverSawAbort = AtomicBoolean(false)
        val handlerDone = CountDownLatch(1)
        server.createContext("/stall") { ex ->
            try {
                ex.sendResponseHeaders(200, 50L * 1024 * 1024)
                ex.responseBody.write(ByteArray(64 * 1024)); ex.responseBody.flush()
                clientGaveUp.await(20, TimeUnit.SECONDS) // stall until the client has failed
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < until) { // then keep writing: a torn-down socket must reject this
                    ex.responseBody.write(ByteArray(64 * 1024)); ex.responseBody.flush()
                    Thread.sleep(10)
                }
            } catch (_: IOException) {
                serverSawAbort.set(true)
            } finally {
                runCatching { ex.close() }
                handlerDone.countDown()
            }
        }
        val seen = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
        val started = System.nanoTime()
        val e = assertFailsWith<IOException> {
            ReleaseClient.download(
                "$base/stall", dir.resolve("stall.bin"), { seen += it },
                headerTimeout = Duration.ofMinutes(10), stallTimeout = Duration.ofMillis(700),
            )
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        clientGaveUp.countDown()
        assertTrue(e.message!!.contains("stalled"), e.message)
        assertTrue(elapsedMs < 5_000, "gave up in ${elapsedMs}ms")
        assertTrue(seen.isNotEmpty() && seen.last().receivedBytes > 0, "the partial progress was observed before the stall")
        assertTrue(handlerDone.await(15, TimeUnit.SECONDS))
        assertTrue(serverSawAbort.get(), "the client really closed the connection instead of leaving a reader parked on it")
    }

    @Test
    fun slow_headers_still_produce_progress_ticks_before_the_body() {
        route("/slowhead") { ex ->
            Thread.sleep(1_200) // connect / TLS / redirect / server think time, as seen by the client
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload)
        }
        val seen = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
        val dest = dir.resolve("slowhead.bin")
        ReleaseClient.download("$base/slowhead", dest, { seen += it },
            headerTimeout = Duration.ofMinutes(1), stallTimeout = Duration.ofSeconds(30), tick = Duration.ofMillis(100))
        val beforeHeaders = seen.takeWhile { it == ReleaseClient.DownloadProgress(0, null) }
        assertTrue(beforeHeaders.size >= 5, "an observer hears about the wait for headers: ${beforeHeaders.size} ticks")
        assertEquals(ReleaseClient.DownloadProgress(payload.size.toLong(), payload.size.toLong()), seen.last())
        assertContentEquals(payload, Files.readAllBytes(dest))
    }

    /**
     * Regression guard: headers that arrive after the caller aborted must not create the destination file.
     * This does NOT prove the aborted/sinkRef race is closed — on JDK 17 a cancelled exchange may never invoke
     * the BodyHandler at all, so the narrow window is not exercised here. That fix rests on code ordering
     * (abort() sets the flag before reading sinkRef; the handler re-checks the flag after publishing its sink).
     */
    @Test
    fun aborting_before_the_headers_never_opens_the_destination_later() {
        val headersSent = CountDownLatch(1)
        route("/lateheaders") { ex ->
            Thread.sleep(1_000)
            try { ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.write(payload) }
            finally { headersSent.countDown() }
        }
        val dest = dir.resolve("late.bin")
        val error = assertFailsWith<java.net.http.HttpTimeoutException> {
            ReleaseClient.download("$base/lateheaders", dest, {},
                headerTimeout = Duration.ofMillis(300), stallTimeout = Duration.ofSeconds(30))
        }
        assertTrue(error.message!!.contains("waiting for response"), error.message)
        assertTrue(headersSent.await(10, TimeUnit.SECONDS))
        Thread.sleep(300) // let any late response processing happen before looking
        assertFalse(Files.exists(dest), "late headers after an abort must not create the destination")
    }

    @Test
    fun continuing_download_outlives_both_timeout_windows() {
        val chunks = 40
        val chunk = ByteArray(64) { 7 }
        route("/trickle") { ex ->
            ex.sendResponseHeaders(200, (chunks * chunk.size).toLong())
            repeat(chunks) { ex.responseBody.write(chunk); ex.responseBody.flush(); Thread.sleep(75) }
        }
        val dest = dir.resolve("t.bin")
        val seen = CopyOnWriteArrayList<ReleaseClient.DownloadProgress>()
        val started = System.nanoTime()
        ReleaseClient.download(
            "$base/trickle", dest, { seen += it },
            headerTimeout = Duration.ofMillis(700), stallTimeout = Duration.ofMillis(700),
        )
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 2_100,
            "the transfer survives several timeout windows as long as bytes keep arriving")
        assertContentEquals(ByteArray(chunks * chunk.size) { 7 }, Files.readAllBytes(dest))
        assertEquals(ReleaseClient.DownloadProgress((chunks * chunk.size).toLong(), (chunks * chunk.size).toLong()), seen.last())
    }

    @Test
    fun progress_callbacks_are_rate_limited_by_the_tick() {
        route("/slow") { ex ->
            ex.sendResponseHeaders(200, 64L * 1024)
            repeat(64) { ex.responseBody.write(ByteArray(1024)); ex.responseBody.flush(); Thread.sleep(20) } // ~1.3s, 64 writes
        }
        val calls = CopyOnWriteArrayList<Long>()
        val started = System.nanoTime()
        ReleaseClient.download("$base/slow", dir.resolve("s.bin"), { calls += System.nanoTime() },
            headerTimeout = Duration.ofMinutes(1), stallTimeout = Duration.ofSeconds(30), tick = Duration.ofMillis(100))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        // one per tick plus the final report — never one per network read
        assertTrue(calls.size <= elapsedMs / 100 + 3, "${calls.size} callbacks in ${elapsedMs}ms")
        assertTrue(calls.size >= 3, "a slow download keeps reporting while it runs: ${calls.size}")
    }

    @Test
    fun a_throwing_progress_observer_does_not_break_the_download() {
        serveFixed("/boom", payload)
        val dest = dir.resolve("boom.bin")
        ReleaseClient.download("$base/boom", dest) { throw IllegalStateException("renderer bug") }
        assertContentEquals(payload, Files.readAllBytes(dest))
    }
}
