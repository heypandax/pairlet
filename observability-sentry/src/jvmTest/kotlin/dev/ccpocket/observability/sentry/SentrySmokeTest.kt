package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*
import kotlin.test.*

class SentrySmokeTest {
    @Test fun probeIsExplicitlySyntheticAndHasNoBusinessContext() {
        val records = mutableListOf<DiagnosticRecord>()
        val reporter = DiagnosticReporter(Component.DESKTOP, Environment.STAGING, "pairlet-diagnostic-smoke@1",
            DiagnosticSink { records.add(it) })
        val ids = emitSmoke(reporter)
        assertEquals(listOf(ids.first, ids.second), records.map { it.eventId })
        assertEquals(listOf(DiagnosticKind.ERROR, DiagnosticKind.LOG), records.map { it.kind })
        records.forEach {
            assertEquals(ErrorPath.DIAGNOSTICS, it.path)
            assertEquals(ErrorCode.SMOKE_TEST, it.code)
            assertEquals(Environment.STAGING, it.environment)
            assertNull(it.traceId)
            assertEquals(SafeMetrics(), it.metrics)
        }
        assertTrue(records.first().exception!!.frames.any { it.symbol.contains("emitSmoke") })
    }

    @Test fun jvmProbeCannotImpersonateAMobileSdk() {
        assertEquals(Component.DAEMON, smokeComponent("daemon"))
        assertEquals(Component.RELAY, smokeComponent("relay"))
        assertFailsWith<IllegalStateException> { smokeComponent("ios") }
        assertFailsWith<IllegalStateException> { smokeComponent("android") }
    }
}
