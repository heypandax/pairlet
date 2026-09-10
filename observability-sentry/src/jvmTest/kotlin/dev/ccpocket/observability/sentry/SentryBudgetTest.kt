package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*
import io.sentry.*
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SentryBudgetTest {
    private val dsn = "https://0123456789abcdef0123456789abcdef@example.invalid/1"
    private fun record(): DiagnosticRecord {
        var result: DiagnosticRecord? = null
        DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test", DiagnosticSink { result = it; true })
            .report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException())
        return result!!
    }

    private fun sink(store: DiagnosticBudgetStore, sent: AtomicInteger) = SentryDiagnosticSink.createConfigured(
        dsn, Component.DAEMON, DiagnosticBudget(Component.DAEMON, store),
    ) { options ->
        options.setTransportFactory { _, _ -> object : ITransport {
            override fun send(envelope: SentryEnvelope, hint: Hint) { sent.incrementAndGet() }
            override fun flush(timeoutMillis: Long) = Unit
            override fun getRateLimiter(): RateLimiter? = null
            override fun close() = Unit
            override fun close(isRestarting: Boolean) = Unit
        } }
    }

    @Test fun sdkReplacementCannotResetAdmissionAndFailedStorageNeverSends() {
        var saved: String? = null
        val store = DiagnosticBudgetStore { update -> saved = update(saved); true }
        val sent = AtomicInteger()
        repeat(12) {
            val sink = sink(store, sent)
            assertTrue(sink.tryEmit(record()))
            sink.flush()
            sink.close()
        }
        assertEquals(10, sent.get())
        val unavailable = sink(DiagnosticBudgetStore { false }, sent)
        try { unavailable.tryEmit(record()); unavailable.flush(); assertEquals(10, sent.get()) }
        finally { unavailable.close() }
    }

    @Test fun diskReservationRunsOffCallerAndClosingWhileBlockedCannotUpload() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = AtomicInteger()
        val caller = Thread.currentThread()
        val store = DiagnosticBudgetStore { update ->
            check(Thread.currentThread() !== caller)
            entered.countDown()
            try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* Simulate late I/O completion. */ }
            update(null)
            true
        }
        val sink = sink(store, sent)
        try {
            assertTrue(sink.tryEmit(record()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(0, sent.get(), "SDK admission must wait for persistence")
            sink.close()
            release.countDown()
            assertFalse(sink.tryEmit(record()))
            assertEquals(0, sent.get())
        } finally { release.countDown(); sink.close() }
    }
}
