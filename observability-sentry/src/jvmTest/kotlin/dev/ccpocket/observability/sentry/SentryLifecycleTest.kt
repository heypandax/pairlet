package dev.ccpocket.observability.sentry

import com.sun.net.httpserver.HttpServer
import dev.ccpocket.observability.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real SDK HTTP and JVM lifetime boundaries. Every receiver and identity is a local test fixture. */
class SentryLifecycleTest {
    @Test fun dnsFailureInAnIsolatedResolverIsCountedWithoutAnyReceiver() {
        val directory = Files.createTempDirectory("sentry-dns-failure-")
        var child: Process? = null
        try {
            // The child JVM uses an empty hosts-only resolver: no external DNS or ingestion request.
            Files.writeString(directory.resolve("hosts"), "")
            child = startFixture("dns", "http://0123456789abcdef0123456789abcdef@sentry-fixture.invalid/1", directory)
            assertTrue(child.waitFor(12, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            assertEquals("1,1", Files.readString(directory.resolve("failure-counts")))
        } finally { child?.destroyForcibly(); directory.toFile().deleteRecursively() }
    }

    @Test fun tlsHandshakeFailureUsesTheActualSdkAndCountsOneFailedSubmission() {
        val receiver = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val connected = CountDownLatch(1)
        val worker = Thread {
            runCatching {
                receiver.accept().use { socket ->
                    connected.countDown()
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        val counters = DiagnosticCounters(DiagnosticBudgetStore { true })
        val sink = SentryDiagnosticSink.createConfigured(
            "https://0123456789abcdef0123456789abcdef@127.0.0.1:${receiver.localPort}/1",
            Component.DESKTOP, counters = counters,
        ) { it.connectionTimeoutMillis = 500; it.readTimeoutMillis = 500 }
        try {
            assertNotNull(fixtureReport(sink))
            sink.flush()
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            assertEquals(1L, counters.snapshot().totals[DiagnosticCounter.TRANSPORT_FAILED])
        } finally { sink.close(); receiver.close(); worker.join(1000) }
    }

    @Test fun readTimeoutCountsLossAndDoesNotBlockTheSource() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            calls.incrementAndGet(); received.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, -1) }
            exchange.close()
        }
        server.start()
        val counters = DiagnosticCounters(DiagnosticBudgetStore { true })
        val sink = SentryDiagnosticSink.createConfigured(fixtureDsn(server), Component.DESKTOP, counters = counters) {
            it.readTimeoutMillis = 150
            it.connectionTimeoutMillis = 500
        }
        try {
            assertNotNull(fixtureReport(sink))
            assertTrue(received.await(3, TimeUnit.SECONDS), "local receiver did not receive the request")
            sink.flush()
            assertEquals(1, calls.get(), "a read timeout must not start an automatic resend")
            assertEquals(1L, counters.snapshot().totals[DiagnosticCounter.TRANSPORT_FAILED],
                "SDK final submission failure must be visible even when no item-loss callback is supplied")
        } finally { release.countDown(); sink.close(); server.stop(0) }
    }

    @Test fun explicitFlushBeforeNormalExitDeliversOneSafeEnvelope() {
        val calls = AtomicInteger()
        val payload = StringBuilder()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val raw = exchange.requestBody.use { it.readBytes() }
            val bytes = if (exchange.requestHeaders.getFirst("Content-Encoding") == "gzip")
                java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
            synchronized(payload) { payload.append(bytes.toString(Charsets.UTF_8)) }
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        server.start()
        val directory = Files.createTempDirectory("sentry-normal-exit-")
        var child: Process? = null
        try {
            child = startFixture("flush", fixtureDsn(server), directory)
            assertTrue(child.waitFor(12, TimeUnit.SECONDS), "fixture failed to exit")
            assertEquals(0, child.exitValue())
            assertEquals(1, calls.get())
            val raw = synchronized(payload) { payload.toString() }
            assertTrue(raw.contains("EP-11"), "expected error envelope missing")
            assertFalse(raw.contains("PRIVATE_LIFECYCLE_SENTINEL"))
        } finally { child?.destroyForcibly(); server.stop(0); directory.toFile().deleteRecursively() }
    }

    @Test fun forcedExitPreservesReservationAndNewProcessDoesNotReplayUnacknowledgedRecord() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            calls.incrementAndGet(); received.countDown()
            release.await(12, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, -1) }
            exchange.close()
        }
        server.start()
        val directory = Files.createTempDirectory("sentry-forced-exit-")
        var child: Process? = null
        var replacement: Process? = null
        try {
            child = startFixture("hold", fixtureDsn(server), directory)
            assertTrue(received.await(8, TimeUnit.SECONDS), "fixture did not enter the HTTP request")
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            val budget = directory.resolve("budgets/desktop-staging.json")
            val before = Files.readString(budget)
            assertTrue(before.contains("\"errors\":1"), "reservation was not durable before the request")
            release.countDown()
            replacement = startFixture("empty", fixtureDsn(server), directory)
            assertTrue(replacement.waitFor(12, TimeUnit.SECONDS))
            assertEquals(0, replacement.exitValue())
            assertEquals(1, calls.get(), "a new process must not replay the old in-memory event")
            assertEquals(before, Files.readString(budget), "restart must preserve the spent reservation")
            Files.walk(directory).use { files ->
                files.filter(Files::isRegularFile).forEach { file ->
                    assertFalse(Files.readString(file).contains("PRIVATE_LIFECYCLE_SENTINEL"))
                }
            }
        } finally {
            release.countDown(); child?.destroyForcibly(); replacement?.destroyForcibly()
            server.stop(0); directory.toFile().deleteRecursively()
        }
    }

    private fun startFixture(mode: String, dsn: String, directory: Path): Process = ProcessBuilder(buildList {
        addAll(listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Duser.home=${directory}"))
        if (mode == "dns") add("-Djdk.net.hosts.file=${directory.resolve("hosts")}")
        addAll(listOf("-cp", requireNotNull(System.getProperty("ccpocket.sentry.fixtureClasspath")),
        SentryLifecycleFixture::class.java.name, mode, dsn, directory.toString(),
        ))
    }).redirectError(directory.resolve("fixture.err").toFile()).redirectOutput(directory.resolve("fixture.out").toFile()).start()
}

private fun fixtureDsn(server: HttpServer) =
    "http://0123456789abcdef0123456789abcdef@127.0.0.1:${server.address.port}/1"

private fun fixtureReport(sink: DiagnosticSink): String? =
    DiagnosticReporter(Component.DESKTOP, Environment.STAGING, "lifecycle-test", sink).report(
        ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException("PRIVATE_LIFECYCLE_SENTINEL"))

/** Test classpath only: no daemon, relay account, real DSN, or global SDK initialization. */
object SentryLifecycleFixture {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args[2])
        val budget = DiagnosticBudget(Component.DESKTOP,
            diagnosticBudgetStore(Component.DESKTOP, Environment.STAGING, directory.resolve("budgets").toString()))
        val counters = DiagnosticCounters(DiagnosticBudgetStore { true })
        SentryDiagnosticSink.createConfigured(args[1], Component.DESKTOP, budget, counters).use { sink ->
            if (args[0] != "empty") check(fixtureReport(sink) != null)
            if (args[0] == "hold") CountDownLatch(1).await()
            sink.flush()
            if (args[0] == "dns") Files.writeString(directory.resolve("failure-counts"),
                "${counters.snapshot().totals[DiagnosticCounter.SDK_ERROR_LOST]},${counters.snapshot().totals[DiagnosticCounter.TRANSPORT_FAILED]}")
        }
    }
}
