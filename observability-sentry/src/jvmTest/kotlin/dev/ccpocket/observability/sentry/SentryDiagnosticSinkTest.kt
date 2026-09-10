package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*
import io.sentry.*
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.test.*

class SentryDiagnosticSinkTest {
    private val dsn = "https://0123456789abcdef0123456789abcdef@example.invalid/1"

    @Test fun manualTransactionsKeepIdentityAndUnknownResultsNeverBecomeSuccessfulSpans() {
        val records = mutableListOf<DiagnosticRecord>()
        val reporter = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "daemon@test",
            DiagnosticSink { records += it; true }, successSamplePercent = 100)
        val traceId = "00000064000000000000000000000001"
        reporter.begin(ErrorPath.SESSION_OPEN, traceId, "1234567890abcdef").also {
            it.stage(Stage.READ); it.stage(Stage.WRITE); it.finish(Outcome.SUCCESS)
        }
        reporter.begin(ErrorPath.SESSION_OPEN, traceId).finish(Outcome.UNKNOWN)
        val transaction = assertNotNull(manualTransaction(records[0]))
        assertEquals(traceId, transaction.contexts.trace!!.traceId.toString())
        assertEquals("1234567890abcdef", transaction.contexts.trace!!.parentSpanId.toString())
        assertEquals(2, transaction.spans.size)
        assertNull(manualTransaction(records[1]))
        val writer = java.io.StringWriter()
        JsonSerializer(SentryOptions()).serialize(transaction, writer)
        val payload = writer.toString()
        assertTrue(payload.contains("session_open"), payload)
        assertFalse(payload.contains("server_name"), payload)
        assertFalse(payload.contains("ip_address"), payload)
    }

    @Test fun finalSdkEnvelopesContainOnlySafeFieldsAndOriginalApplicationFrames() {
        val envelopes = Collections.synchronizedList(mutableListOf<String>())
        val sink = SentryDiagnosticSink.createConfigured(dsn, Component.DAEMON) { options ->
            options.setTransportFactory { opts, _ -> object : ITransport {
                override fun send(envelope: SentryEnvelope, hint: Hint) {
                    val out = ByteArrayOutputStream()
                    opts.serializer.serialize(envelope, out)
                    envelopes.add(out.toString("UTF-8"))
                }
                override fun flush(timeoutMillis: Long) = Unit
                override fun getRateLimiter(): RateLimiter? = null
                override fun close() = Unit
                override fun close(isRestarting: Boolean) = Unit
            } }
        }
        try {
            val reporter = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "daemon@1.9.8", sink)
            val exception = IllegalStateException("PROMPT_SECRET /private/path TOKEN_SECRET", RuntimeException("CAUSE_SECRET"))
            exception.stackTrace = arrayOf(
                StackTraceElement("dev.ccpocket.daemon.disk.TranscriptReplay", "read", "TranscriptReplay.kt", 42),
                StackTraceElement("secret.owner.Host", "token", "/Users/SECRET_USER/file.kt", 123),
            )
            reporter.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, exception)
            reporter.report(ErrorPath.SESSION_LIST, Stage.SCAN, ErrorCode.PARTIAL_RESULT,
                metrics = SafeMetrics(totalCount = 3, failedCount = 1, resultQuality = ResultQuality.PARTIAL))
            sink.flush()
            val raw = envelopes.joinToString("\n")
            assertTrue(raw.contains("TranscriptReplay.kt"), raw)
            assertTrue(raw.contains("EP-11"), raw)
            assertTrue(raw.contains("EP-09"), raw)
            assertTrue(raw.contains("partial"), raw)
            assertTrue(raw.contains("total_count"), raw)
            assertTrue(raw.contains("result_quality"), raw)
            assertTrue(raw.contains("suppressed_count"), raw)
            assertFalse(raw.contains("resultQuality"), raw)
            for (value in listOf("PROMPT_SECRET", "TOKEN_SECRET", "CAUSE_SECRET", "SECRET_USER", "secret.owner", "server_name", "ip_address")) {
                assertFalse(raw.contains(value), "unexpected $value")
            }
            assertEquals(2, envelopes.size)
        } finally { sink.close() }
    }

    @Test fun missingAndMalformedConfigurationAreNoOp() {
        assertNull(SentryDiagnosticSink.create(null, Component.DESKTOP))
        assertFalse(SentryDiagnosticSink.validDsn("http://key@example.invalid/1"))
        assertFalse(SentryDiagnosticSink.validDsn("$dsn?token=secret"))
        assertTrue(SentryDiagnosticSink.validDsn(dsn))
    }

    @Test fun closedSinkRejectsNewRecords() {
        val sink = SentryDiagnosticSink.createConfigured(dsn, Component.DAEMON)
        sink.close()
        val reporter = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "daemon@1.9.8", sink)
        assertNull(reporter.report(ErrorPath.OUTBOX, Stage.WRITE, ErrorCode.WRITE_FAILED, IllegalStateException()))
        assertEquals(1L, reporter.health().dropped)
    }
    @Test fun realSdkTransportHonors429WithoutRetryStorm() {
        val received = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            received.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "60")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        // Internal test constructor permits loopback HTTP; production configuration requires HTTPS.
        val sink = SentryDiagnosticSink.createConfigured(
            "http://0123456789abcdef0123456789abcdef@127.0.0.1:${server.address.port}/1", Component.DAEMON)
        try {
            val reporter = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test", sink)
            reporter.report(ErrorPath.SESSION_OPEN, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException())
            sink.flush()
            assertEquals(1, received.get())
            assertTrue(sink.isRateLimited())
            repeat(8) { reporter.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException()) }
            sink.flush()
            assertEquals(1, received.get(), "the SDK must suppress sends throughout Retry-After")
        } finally { sink.close(); server.stop(0) }
    }

    @Test fun queuePressureAndThrowingTransportCannotEscapeIntoBusinessCode() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sink = SentryDiagnosticSink.createConfigured(dsn, Component.DAEMON) { options ->
            options.setTransportFactory { _, _ -> object : ITransport {
                override fun send(envelope: SentryEnvelope, hint: Hint) {
                    entered.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    throw java.io.IOException("TRANSPORT_SECRET")
                }
                override fun flush(timeoutMillis: Long) = Unit
                override fun getRateLimiter(): RateLimiter? = null
                override fun close() = Unit
                override fun close(isRestarting: Boolean) = Unit
            } }
        }
        try {
            val source = mutableListOf<DiagnosticRecord>()
            DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test", DiagnosticSink { source.add(it) })
                .report(ErrorPath.OUTBOX, Stage.WRITE, ErrorCode.WRITE_FAILED, IllegalStateException())
            val record = source.single()
            assertTrue(sink.tryEmit(record))
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            var accepted = 0
            repeat(100) { if (sink.tryEmit(record)) accepted++ }
            assertTrue(accepted <= 16)
            assertTrue(sink.droppedCount() >= 84)
            // Opt out while entries are queued. They must never reach the transport after reopening.
            sink.close()
            assertFalse(sink.tryEmit(record))
        } finally { release.countDown(); sink.close() }
    }

    @Test fun implicitRuntimeCannotUploadFromTheTestRunnerEvenWithAConfiguredDsn() {
        assertEquals("true", System.getProperty("ccpocket.test"))
        SentryRuntime.configure(Component.DESKTOP, "test", enabled = true, dsn = dsn)
        assertFalse(SentryRuntime.isActive())
        assertNull(Diagnostics.report(ErrorPath.STARTUP, Stage.START, ErrorCode.UNEXPECTED, IllegalStateException()))
    }

}
