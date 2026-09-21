package dev.ccpocket.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PushDiagnosticsTest {
    @Test fun boundaryFactsSurviveSuccessSamplingAndShareOnlyAnEphemeralTrace() {
        val records = mutableListOf<DiagnosticRecord>()
        val r = DiagnosticReporter(Component.IOS, Environment.STAGING, "ios@test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 0)
        r.push(Stage.PUSH_TOKEN, ErrorCode.TOKEN_SANDBOX)
        r.push(Stage.PUSH_REGISTER, ErrorCode.ACK_TIMEOUT, 2)
        r.push(Stage.PUSH_REGISTER, ErrorCode.RETRY_EXHAUSTED, 3, isError = true)
        assertEquals(3, records.size)
        assertEquals(1, records.map { it.traceId }.distinct().size)
        assertTrue(records.first().traceId!!.matches(Regex("[0-9a-f]{32}")))
        assertEquals(3, records.last().attempt)
        assertEquals(listOf(ErrorCode.TOKEN_SANDBOX, ErrorCode.ACK_TIMEOUT, ErrorCode.RETRY_EXHAUSTED),
            records.last().steps.map { it.code })
        assertTrue(r.pushHistoryText().contains(records.first().traceId!!))
    }

    @Test fun localHistoryIsBoundedKeepsSuppressedFactsAndIsErasedOnOptOut() {
        val records = mutableListOf<DiagnosticRecord>()
        val r = DiagnosticReporter(Component.IOS, Environment.STAGING, "ios@test", DiagnosticSink { records.add(it) })
        repeat(100) { r.push(Stage.PUSH_TOKEN, ErrorCode.NETWORK_FAILED, it) }
        assertEquals(3, records.size) // regular cloud rate limit still applies
        assertEquals(66, r.pushHistoryText().lines().size) // 2 header lines + 64 facts
        assertTrue(r.pushHistoryText().contains("attempt=99"))
        val before = records.size
        r.setEnabled(false)
        r.push(Stage.PUSH_TOKEN, ErrorCode.TOKEN_RECEIVED)
        assertEquals(before, records.size)
        assertEquals("Push diagnostics disabled", r.pushHistoryText())
        r.setEnabled(true)
        assertFalse(r.pushHistoryText().contains("network_failed"))
    }

    @Test fun nativeErrorUsesClosedDomainAndBoundedCodeWithoutExceptionText() {
        val records = mutableListOf<DiagnosticRecord>()
        val r = DiagnosticReporter(Component.IOS, Environment.STAGING, "ios@test", DiagnosticSink { records.add(it) })
        r.push(Stage.PUSH_TOKEN, ErrorCode.NATIVE_FAILED,
            metrics = SafeMetrics(nativeErrorDomain = NativeErrorDomain.COCOA, nativeErrorCode = Int.MAX_VALUE),
            isError = true, error = IllegalStateException("SECRET_TOKEN /private/path"))
        val rec = records.single()
        assertEquals(NativeErrorDomain.COCOA, rec.metrics.nativeErrorDomain)
        assertEquals(1_000_000, rec.metrics.nativeErrorCode)
        assertFalse(Json.encodeToString(rec).contains("SECRET_TOKEN"))
        assertFalse(r.pushHistoryText().contains("SECRET_TOKEN"))
    }
}
