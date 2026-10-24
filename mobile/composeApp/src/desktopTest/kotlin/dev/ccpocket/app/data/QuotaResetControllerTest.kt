package dev.ccpocket.app.data

import dev.ccpocket.protocol.QuotaResetResult
import dev.ccpocket.protocol.RedeemQuotaReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaResetControllerTest {
    private class Harness(scope: CoroutineScope) {
        var allowed = true
        var count = 2L
        var saved: RedeemQuotaReset? = null
        var refreshes = 0
        val sent = mutableListOf<RedeemQuotaReset>()
        private var sequence = 0
        val controller = QuotaResetController(
            scope, canSend = { allowed }, availableCount = { count },
            newRequestId = { "request-${++sequence}" }, restore = { saved },
            persist = { saved = it },
            send = { assertEquals(it, saved, "persist before dispatch"); sent += it },
            refreshQuota = { refreshes++ },
        )
    }

    @Test fun every_unresolved_outcome_keeps_an_explicit_retry_for_the_same_card() = runTest {
        for (outcome in listOf("unknown", "unsupported", "invalid_request", "future-outcome")) {
            val h = Harness(backgroundScope)
            assertTrue(h.controller.redeem("card-a"))
            runCurrent()
            val original = h.sent.single()
            h.controller.onResult(QuotaResetResult(original.requestId, outcome))
            h.count = 0
            h.controller.onQuotaRefreshed()
            assertTrue(h.controller.canRetry)
            assertFalse(h.controller.canRedeem)
            assertFalse(h.controller.redeem("card-b"))
            assertTrue(h.controller.retry())
            runCurrent()
            assertEquals(listOf(original, original), h.sent)
            h.controller.reset()
        }
    }

    @Test fun terminal_results_require_a_fresh_balance_before_accepting_the_next_card() = runTest {
        for (outcome in listOf("reset", "alreadyRedeemed", "noCredit", "nothingToReset")) {
            val h = Harness(backgroundScope)
            h.controller.redeem("card-a")
            runCurrent()
            val original = h.sent.single()
            h.controller.onResult(QuotaResetResult(original.requestId, outcome))
            assertNull(h.saved)
            assertFalse(h.controller.pending)
            assertFalse(h.controller.canRetry)
            assertFalse(h.controller.redeem("card-b"))
            assertEquals(1, h.refreshes)
            h.controller.onQuotaRefreshed()
            assertTrue(h.controller.redeem("card-b"))
            runCurrent()
            assertEquals("card-b", h.sent.last().creditId)
            assertNotEquals(original.requestId, h.sent.last().requestId)
            h.controller.reset()
        }
    }

    @Test fun restarting_restores_the_pending_attempt_without_sending_or_switching_cards() = runTest {
        val h = Harness(backgroundScope)
        val original = RedeemQuotaReset("persisted", "card-a")
        h.saved = original
        h.controller.onQuotaRefreshed()
        assertTrue(h.controller.canRetry)
        assertFalse(h.controller.redeem("card-b"))
        assertTrue(h.sent.isEmpty())
        h.allowed = false
        assertFalse(h.controller.canRetry)
        assertFalse(h.controller.retry())
        assertEquals(original, h.saved)
        h.allowed = true
        assertTrue(h.controller.retry())
        runCurrent()
        assertEquals(listOf(original), h.sent)
        h.controller.reset()
    }

    @Test fun timeout_keeps_the_key_and_a_late_result_can_still_settle_it() = runTest {
        val h = Harness(backgroundScope)
        h.controller.redeem("card-a")
        runCurrent()
        val original = h.sent.single()
        advanceTimeBy(QuotaResetController.RESULT_TIMEOUT_MS)
        runCurrent()
        assertFalse(h.controller.busy.value)
        assertTrue(h.controller.canRetry)
        assertEquals(original, h.saved)
        h.controller.onResult(QuotaResetResult("unrelated", "reset"))
        assertTrue(h.controller.pending)
        assertEquals(0, h.refreshes)
        h.controller.onResult(QuotaResetResult(original.requestId, "reset"))
        assertFalse(h.controller.pending)
        assertNull(h.saved)
        assertEquals("reset", h.controller.outcome.value)
    }

    @Test fun leaving_a_computer_cancels_its_deadline_and_ignores_its_late_reply() = runTest {
        val h = Harness(backgroundScope)
        h.controller.redeem("card-a")
        runCurrent()
        val original = h.sent.single()
        h.controller.reset()
        // Persistence is per computer and survives the in-memory reset. Repository changes its key
        // before a different computer can restore anything.
        assertEquals(original, h.saved)
        advanceTimeBy(QuotaResetController.RESULT_TIMEOUT_MS + 1)
        runCurrent()
        h.controller.onResult(QuotaResetResult(original.requestId, "reset"))
        assertNull(h.controller.outcome.value)
        assertFalse(h.controller.busy.value)
        assertFalse(h.controller.needsRefresh.value)
        assertEquals(0, h.refreshes)
    }

    @Test fun leaving_before_dispatch_cannot_send_the_old_computers_attempt_on_the_next_link() = runTest {
        val h = Harness(backgroundScope)
        h.controller.redeem("card-a")
        h.controller.reset() // the shared repository scope survives a machine switch
        runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals("card-a", h.saved?.creditId, "the attempt remains recoverable on its own computer")
    }
}
