package dev.ccpocket.observability

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class DiagnosticsTest {
    private val records = mutableListOf<DiagnosticRecord>()
    private var now = 1_800_000_000_000L
    private fun reporter(sink: DiagnosticSink = DiagnosticSink { records.add(it) }) = DiagnosticReporter(
        Component.DAEMON, Environment.STAGING, "cc-pocket-daemon@1.9.8", sink, epochMs = { now }, successSamplePercent = 100,
    )

    @Test fun exceptionMessagesCausesAndPathsNeverEnterPayload() {
        val error = IllegalStateException("PROMPT_SENTINEL /Users/private/key?token=TOKEN_SENTINEL",
            RuntimeException("CAUSE_SENTINEL"))
        reporter().report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, error)
        val payload = Json.encodeToString(records.single())
        for (forbidden in listOf("PROMPT_SENTINEL", "/Users/private", "TOKEN_SENTINEL", "CAUSE_SENTINEL")) {
            assertFalse(payload.contains(forbidden), forbidden)
        }
        assertTrue(records.single().exception!!.type.contains("IllegalStateException"))
    }

    private fun captureOriginalLocation(): Throwable = IllegalStateException("PRIVATE_MESSAGE")

    @Test fun capturesTheThrowablesOriginalFunctionOnJvmAndNative() {
        val captured = safeException(captureOriginalLocation())
        assertTrue(captured.frames.any { it.symbol.contains("captureOriginalLocation") }, captured.frames.toString())
    }

    @Test fun parallelOperationsDoNotShareBreadcrumbsAndTerminateOnce() {
        val r = reporter()
        val a = r.begin(ErrorPath.SESSION_OPEN)
        val b = r.begin(ErrorPath.FILE_READ)
        a.stage(Stage.SCAN); b.stage(Stage.DOWNLOAD)
        a.retry()
        a.finish(Outcome.TIMEOUT, Stage.READ, ErrorCode.TIMEOUT)
        assertNull(a.finish(Outcome.SUCCESS))
        b.finish(Outcome.SUCCESS)
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(Stage.SCAN), records[0].steps.map { it.stage })
        assertEquals(listOf(Stage.DOWNLOAD), records[1].steps.map { it.stage })
        assertEquals(1, records[0].attempt)
        assertNotNull(a.recovered())
        assertNull(a.recovered())
        assertEquals(3, records.size)
        assertEquals(Outcome.TIMEOUT, records[0].outcome)
        assertEquals(Outcome.RECOVERED, records[2].outcome)
    }

    @Test fun supportCopyUsesTheAcceptedEventIdAndFailedAdmissionDoesNotReplaceIt() {
        var accept = true
        val r = reporter(DiagnosticSink { if (accept) records.add(it) else false })
        val operation = r.begin(ErrorPath.SESSION_OPEN)
        val eventId = operation.finish(Outcome.SUCCESS)
        assertNotNull(eventId)
        assertEquals(records.single().eventId, r.latestId())
        assertNotEquals(operation.id, r.latestId())
        accept = false
        r.report(ErrorPath.CONTENT, Stage.DECODE, ErrorCode.DECODE_FAILED)
        assertEquals(eventId, r.latestId())
    }

    @Test fun cancellationIsNotAnErrorAndOptOutInvalidatesInFlightTraces() {
        val r = reporter()
        val old = r.begin(ErrorPath.SESSION_OPEN)
        r.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, CancellationException())
        r.begin(ErrorPath.FILE_READ).finish(Outcome.CANCELLED)
        r.setEnabled(false)
        r.report(ErrorPath.SESSION_OPEN, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException())
        r.setEnabled(true)
        old.finish(Outcome.FAILURE, Stage.READ, ErrorCode.READ_FAILED)
        assertTrue(records.isEmpty())
        r.begin(ErrorPath.SESSION_OPEN).finish(Outcome.SUCCESS)
        assertEquals(1, records.size)
    }

    @Test fun duplicateFloodAndDailyQuotaRemainBoundedAcrossToggles() {
        val r = reporter()
        repeat(100) { r.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException()) }
        assertEquals(2, records.size)
        assertEquals(98, r.health().suppressed)
        r.setEnabled(false); r.setEnabled(true)
        r.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException())
        assertEquals(2, records.size)
        ErrorPath.entries.forEach { r.report(it, Stage.START, ErrorCode.UNEXPECTED, IllegalStateException()) }
        assertEquals(10, records.size)
        now += 86_400_000L
        r.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, IllegalStateException())
        assertEquals(11, records.size)
    }

    @Test fun rejectedAndThrowingSinksCannotBreakBusinessOrRecurse() {
        val r = reporter(DiagnosticSink { throw IllegalStateException("sink secret") })
        assertNull(r.report(ErrorPath.OUTBOX, Stage.WRITE, ErrorCode.WRITE_FAILED, IllegalStateException()))
        assertEquals(1, r.health().dropped)
        assertTrue(records.isEmpty())
    }

    @Test fun successSamplingNeverDropsFailuresOrRecovery() {
        val r = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test", DiagnosticSink { records.add(it) },
            successSamplePercent = 0)
        assertNull(r.begin(ErrorPath.SESSION_OPEN).finish(Outcome.SUCCESS))
        val failed = r.begin(ErrorPath.SESSION_OPEN)
        assertNotNull(failed.finish(Outcome.FAILURE, code = ErrorCode.TIMEOUT))
        assertNotNull(failed.recovered())
        assertEquals(listOf(DiagnosticKind.ERROR, DiagnosticKind.RECOVERY), records.map { it.kind })
    }

    @Test fun repeatedConnectionLogsAreAggregatedWithoutConsumingErrorBudget() {
        val r = reporter()
        repeat(1000) { r.report(ErrorPath.RELAY, Stage.CONNECT, ErrorCode.SUPERSEDED) }
        assertEquals(3, records.size)
        assertEquals(997L, r.health().suppressed)
        r.report(ErrorPath.RELAY, Stage.CONNECT, ErrorCode.UNEXPECTED, IllegalStateException())
        assertEquals(DiagnosticKind.ERROR, records.last().kind)
        now += 60_000
        r.report(ErrorPath.RELAY, Stage.CONNECT, ErrorCode.SUPERSEDED)
        assertEquals(5, records.size)
        assertEquals(997L, records.last().suppressedCount)
    }

    @Test fun numericFieldsAndBreadcrumbsAreBounded() {
        val op = reporter().begin(ErrorPath.HISTORY_READ)
        repeat(1000) { op.stage(Stage.READ) }
        op.finish(Outcome.FAILURE, code = ErrorCode.READ_FAILED, metrics = SafeMetrics(totalCount = -1, byteCount = Long.MAX_VALUE))
        assertEquals(32, records.single().steps.size)
        assertEquals(0, records.single().metrics.totalCount)
        assertEquals(1_000_000_000_000L, records.single().metrics.byteCount)
    }
}
