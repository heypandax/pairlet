package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FirstValueObservationTest {
    private class MemoryStore : DiagnosticBudgetStore {
        var raw: String? = null
        var writable = true
        override fun update(transform: (String?) -> String): Boolean {
            val next = transform(raw)
            if (!writable) return false
            raw = next
            return true
        }
    }
    private val params = mapOf<TelKey, Any>(TelKey.Environment to "production",
        TelKey.InternalTraffic to "0", TelKey.UsageMode to "own", TelKey.Feature to "session_view")

    @Test fun firstValueSurvivesRestartAndDoesNotResetForAnotherVersionOrFeature() = runTest {
        val stores = Environment.entries.associateWith { MemoryStore() }
        val events = mutableListOf<Map<TelKey, Any>>()
        fun recorder() = FirstValueObservation(backgroundScope, { stores[it] }, { events += it }, { { true } })
        val first = recorder()
        first.observe(TelEvent.FeatureUsed, params)
        first.observe(TelEvent.ValueReached, params + (TelKey.UsageMode to "demo"))
        first.observe(TelEvent.ValueReached, params)
        runCurrent()
        recorder().observe(TelEvent.ValueReached, params + mapOf(TelKey.Feature to "prompt_task", TelKey.AppVersion to "next"))
        runCurrent()
        assertEquals(1, events.size)
        first.observe(TelEvent.ValueReached, params + (TelKey.UsageMode to "shared"))
        runCurrent()
        assertEquals(listOf("own", "shared"), events.map { it[TelKey.UsageMode] })
    }

    @Test fun confirmedIdentityResetAllowsANewCohortButAnOldProducerCannotCrossConsent() = runTest {
        val stores = Environment.entries.associateWith { MemoryStore() }
        var generation = 0
        val events = mutableListOf<Map<TelKey, Any>>()
        val recorder = FirstValueObservation(backgroundScope, { stores[it] }, { events += it },
            { val captured = generation; { captured == generation } })
        recorder.observe(TelEvent.ValueReached, params)
        generation++
        recorder.resetIdentity()
        runCurrent()
        assertTrue(events.isEmpty())
        recorder.observe(TelEvent.ValueReached, params)
        runCurrent()
        assertEquals(1, events.size)
        generation++; recorder.resetIdentity(); runCurrent()
        recorder.observe(TelEvent.ValueReached, params)
        runCurrent()
        assertEquals(2, events.size)
    }

    @Test fun failedStorageAndCorruptMarkersNeverInventSuccessfulFirstObservation() = runTest {
        val store = MemoryStore().apply { writable = false }
        val events = mutableListOf<Map<TelKey, Any>>()
        val recorder = FirstValueObservation(backgroundScope, { store }, { events += it }, { { true } })
        recorder.observe(TelEvent.ValueReached, params); runCurrent()
        assertTrue(events.isEmpty())
        store.writable = true
        store.raw = "CORRUPT_SENTINEL"
        recorder.observe(TelEvent.ValueReached, params); runCurrent()
        assertTrue(events.isEmpty())
        assertFalse(store.raw!!.contains("CORRUPT_SENTINEL"))
    }
}
