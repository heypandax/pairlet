package dev.ccpocket.observability.sentry

import com.sun.net.httpserver.HttpServer
import dev.ccpocket.observability.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SentryTransportFailureTest {
    private fun records(): List<DiagnosticRecord> = buildList {
        val reporter = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { add(it); true })
        reporter.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException())
        reporter.report(ErrorPath.RELAY, Stage.CONNECT, ErrorCode.UNAVAILABLE, isError = false)
    }

    @Test fun httpFailureAccountsForErrorItemsAndOpaqueLogBatchesSeparately() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val counters = DiagnosticCounters(DiagnosticBudgetStore { true })
        val sink = SentryDiagnosticSink.createConfigured(
            "http://0123456789abcdef0123456789abcdef@127.0.0.1:${server.address.port}/1",
            Component.DAEMON, counters = counters)
        try {
            records().forEach { assertTrue(sink.tryEmit(it)) }
            sink.flush()
            val totals = counters.snapshot().totals
            assertTrue(requests.get() >= 1)
            assertEquals(1L, totals[DiagnosticCounter.SDK_ERROR_LOST])
            assertEquals(1L, totals.entries.singleOrNull { it.key.name == "SDK_LOG_BATCH_LOST" }?.value,
                "An opaque failed log envelope must be counted as a batch, not silently lost or guessed to be one item")
        } finally { sink.close(); server.stop(0) }
    }

    @Test fun refusedReceiverCannotThrowIntoBusinessCodeOrReplayOnANewClient() {
        val port = ServerSocket(0).use { it.localPort }
        val counters = DiagnosticCounters(DiagnosticBudgetStore { true })
        val dsn = "http://0123456789abcdef0123456789abcdef@127.0.0.1:$port/1"
        val sink = SentryDiagnosticSink.createConfigured(dsn, Component.DAEMON, counters = counters) {
            it.connectionTimeoutMillis = 250; it.readTimeoutMillis = 250
        }
        try {
            assertTrue(sink.tryEmit(records().first()))
            sink.flush()
            assertEquals(1L, counters.snapshot().totals[DiagnosticCounter.SDK_ERROR_LOST])
        } finally { sink.close() }
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/") { exchange -> requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        val replacement = SentryDiagnosticSink.createConfigured(dsn, Component.DAEMON)
        try { replacement.flush(); assertEquals(0, requests.get()) }
        finally { replacement.close(); server.stop(0) }
    }
}
