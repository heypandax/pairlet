package dev.ccpocket.observability

import kotlin.test.*

class DiagnosticCountersTest {
    private class MemoryStore : DiagnosticBudgetStore {
        var value: String? = null
        var writable = true
        var duringUpdate: (() -> Unit)? = null
        override fun update(transform: (String?) -> String): Boolean {
            val next = transform(value)
            duringUpdate?.also { duringUpdate = null }?.invoke()
            if (!writable) return false
            value = next
            return true
        }
    }

    @Test fun restartAndConcurrentInstancesMergeDeltasWithoutReplayingOldTotals() {
        val store = MemoryStore()
        val first = DiagnosticCounters(store)
        first.increment(DiagnosticCounter.SOURCE_SUBMITTED, 3)
        assertTrue(first.flush())
        val second = DiagnosticCounters(store)
        second.increment(DiagnosticCounter.SOURCE_SUBMITTED, 5)
        assertTrue(second.flush())
        first.increment(DiagnosticCounter.SOURCE_SUBMITTED, 2)
        assertTrue(first.flush())
        val restarted = DiagnosticCounters(store)
        assertTrue(restarted.flush())
        assertEquals(10L, restarted.snapshot().totals[DiagnosticCounter.SOURCE_SUBMITTED])
    }

    @Test fun failedFlushRestoresReservedDeltaAlongsideConcurrentProducer() {
        val store = MemoryStore().apply { writable = false }
        val counters = DiagnosticCounters(store)
        counters.increment(DiagnosticCounter.SDK_LOG_LOST, 7)
        store.duringUpdate = { counters.increment(DiagnosticCounter.SDK_LOG_LOST, 2) }
        assertFalse(counters.flush())
        assertEquals(9L, counters.snapshot().totals[DiagnosticCounter.SDK_LOG_LOST])
        store.writable = true
        assertTrue(counters.flush())
        val restarted = DiagnosticCounters(store)
        assertTrue(restarted.flush())
        assertEquals(9L, restarted.snapshot().totals[DiagnosticCounter.SDK_LOG_LOST])
        assertEquals(1L, restarted.snapshot().totals[DiagnosticCounter.STORAGE_FAILED])
        assertNull(restarted.snapshot().totals[DiagnosticCounter.SDK_LOG_BYTES_LOST])
    }

    @Test fun corruptStateIsCountedAndHostileAmountsStayBounded() {
        val store = MemoryStore().apply { value = "TOKEN_SENTINEL damaged state" }
        val counters = DiagnosticCounters(store)
        counters.increment(DiagnosticCounter.TRANSPORT_FAILED, Long.MAX_VALUE)
        counters.increment(DiagnosticCounter.TRANSPORT_FAILED, Long.MAX_VALUE)
        counters.increment(DiagnosticCounter.SDK_ERROR_LOST, -1)
        assertTrue(counters.flush())
        assertEquals(1_000_000_000_000L, counters.snapshot().totals[DiagnosticCounter.TRANSPORT_FAILED])
        assertEquals(1L, counters.snapshot().totals[DiagnosticCounter.STORAGE_RESET])
        assertNull(counters.snapshot().totals[DiagnosticCounter.SDK_ERROR_LOST])
        assertFalse(store.value!!.contains("TOKEN_SENTINEL"))
    }
}
