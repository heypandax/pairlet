package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.ErrorCode
import kotlin.test.*

class ProductOutcomeTest {
    @Test fun timeoutKeepsItsDenominatorAndRecoveryIsSeparateAndSingle() {
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        val outcome = ProductOutcome(TelEvent.SessionOpenResult, emit = { e, p -> events += e to p }, allowed = { true })
        assertTrue(outcome.finish(ProductResult.TIMEOUT, ErrorCode.TIMEOUT))
        assertFalse(outcome.finish(ProductResult.SUCCESS))
        assertTrue(outcome.recover(TelEvent.SessionOpenRecovered))
        assertFalse(outcome.recover(TelEvent.SessionOpenRecovered))
        assertEquals(listOf(TelEvent.SessionOpenResult, TelEvent.SessionOpenRecovered), events.map { it.first })
        assertEquals(listOf("timeout", "success"), events.map { it.second[TelKey.Result] })
    }

    @Test fun consentRevocationNeverReplaysThePreviousResult() {
        var allowed = true
        val events = mutableListOf<TelEvent>()
        val outcome = ProductOutcome(TelEvent.FileViewResult, emit = { e, _ -> events += e }, allowed = { allowed })
        allowed = false
        assertTrue(outcome.finish(ProductResult.FAILURE))
        allowed = true
        assertFalse(outcome.finish(ProductResult.SUCCESS))
        assertTrue(events.isEmpty())
    }

    @Test fun waitingAndUnknownStayOutOfFailureAndRecoveryCounts() {
        for (result in listOf(ProductResult.WAITING, ProductResult.UNKNOWN, ProductResult.CANCELLED)) {
            val values = mutableListOf<Map<TelKey, Any>>()
            val outcome = ProductOutcome(TelEvent.ApprovalApplyResult, emit = { _, p -> values += p }, allowed = { true })
            outcome.finish(result, ErrorCode.INCOMPLETE, Coverage.PARTIAL)
            assertFalse(outcome.recover(TelEvent.SessionOpenRecovered))
            assertEquals(result.name.lowercase(), values.single()[TelKey.Result])
            assertEquals("partial", values.single()[TelKey.Coverage])
        }
    }
}
