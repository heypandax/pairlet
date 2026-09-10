package dev.ccpocket.observability

import kotlinx.serialization.json.Json
import kotlin.test.*

class DiagnosticBudgetTest {
    private var now = 1_800_000_000_000L
    private class MemoryStore(var value: String? = null) : DiagnosticBudgetStore {
        var writable = true
        override fun update(transform: (String?) -> String): Boolean {
            val updated = transform(value)
            if (!writable) return false
            value = updated
            return true
        }
    }
    private fun budget(store: MemoryStore, component: Component = Component.DAEMON) =
        DiagnosticBudget(component, store) { now }

    @Test fun newRuntimeInstancesShareDailyQuotaAndSuccessfulDayRollover() {
        val store = MemoryStore()
        repeat(10) { assertTrue(budget(store).admit(DiagnosticKind.ERROR, 100)) }
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, 100))
        assertTrue(budget(store).admit(DiagnosticKind.LOG, 100), "error allowance is independent of logs")
        now += 86_400_000
        assertTrue(budget(store).admit(DiagnosticKind.ERROR, 100))
        val state = Json.decodeFromString<BudgetState>(store.value!!)
        assertEquals(12L, state.admitted)
        assertEquals(1L, state.suppressed)
    }

    @Test fun clockRollbackAndRestoringAnOldRuntimeCannotResetQuota() {
        val store = MemoryStore()
        val olderRuntime = budget(store)
        repeat(10) { assertTrue(olderRuntime.admit(DiagnosticKind.ERROR, 100)) }
        now -= 3 * 86_400_000L
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, 100))
        now += 3 * 86_400_000L
        assertFalse(olderRuntime.admit(DiagnosticKind.ERROR, 100))
        now += 86_400_000
        assertTrue(budget(store).admit(DiagnosticKind.ERROR, 100))
        repeat(9) { assertTrue(olderRuntime.admit(DiagnosticKind.ERROR, 100)) }
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, 100))
    }

    @Test fun damagedInvalidAndOversizedStateNeverGrantFreshQuota() {
        for (raw in listOf("broken", "x".repeat(5000), "{}", """{"schema":2,"day":1}""",
                """{"day":1,"errors":-1}""", """{"day":1,"admitted":9223372036854775807}""")) {
            val store = MemoryStore(raw)
            assertFalse(budget(store).admit(DiagnosticKind.ERROR, 10), raw.take(60))
            repeat(3) { assertFalse(budget(store).admit(DiagnosticKind.LOG, 10)) }
            assertTrue(store.value!!.length < 4096)
            now += 86_400_000
            assertTrue(budget(store).admit(DiagnosticKind.ERROR, 10))
        }
    }

    @Test fun failedPersistenceCannotUploadOrBreakTheCaller() {
        val store = MemoryStore().also { it.writable = false }
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, 100))
        assertNull(store.value)
        val throwing = DiagnosticBudget(Component.IOS, DiagnosticBudgetStore { error("LOCAL_STORAGE_SECRET") })
        assertFalse(throwing.admit(DiagnosticKind.ERROR, 100))
        store.writable = true
        assertTrue(budget(store).admit(DiagnosticKind.ERROR, 100))
    }

    @Test fun byteAndLogCapsSurviveRecreationAndStoreNoEventContents() {
        val store = MemoryStore()
        repeat(54) { assertTrue(budget(store).admit(DiagnosticKind.LOG, 16_384)) }
        assertFalse(budget(store).admit(DiagnosticKind.LOG, 1))
        repeat(10) { assertTrue(budget(store).admit(DiagnosticKind.ERROR, 16_384), "logs must leave space for errors") }
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, 100))
        val state = Json.decodeFromString<BudgetState>(store.value!!)
        assertEquals(1_048_576L, state.bytes)
        assertEquals(2L, state.dropped)
        assertTrue(store.value!!.length < 256)
        now += 86_400_000
        repeat(500) { assertTrue(budget(store).admit(DiagnosticKind.LOG, 1)) }
        assertFalse(budget(store).admit(DiagnosticKind.RESULT, 1))
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, 16_385))
        assertFalse(budget(store).admit(DiagnosticKind.ERROR, -1))
    }

    @Test fun relayHasItsOwnFixedAllowance() {
        val store = MemoryStore()
        repeat(20) { assertTrue(budget(store, Component.RELAY).admit(DiagnosticKind.ERROR, 100)) }
        assertFalse(budget(store, Component.RELAY).admit(DiagnosticKind.ERROR, 100))
    }
}
