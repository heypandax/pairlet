package dev.ccpocket.observability

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Units are explicit: source records, SDK items and transport requests must never be summed. */
@Serializable enum class DiagnosticCounter {
    SOURCE_SUBMITTED, SOURCE_SAMPLED, SOURCE_SUPPRESSED, SOURCE_DROPPED,
    QUEUE_DROPPED, BUDGET_REJECTED, CLOSED_DROPPED,
    SDK_ERROR_LOST, SDK_LOG_LOST, SDK_SPAN_LOST, SDK_TRANSACTION_LOST, SDK_LOG_BYTES_LOST,
    SDK_LOG_BATCH_LOST,
    SDK_RATE_LIMITED, SDK_NETWORK_LOST, SDK_QUEUE_LOST, SDK_FILTERED,
    TRANSPORT_FAILED, TRANSPORT_RATE_LIMITED, STORAGE_FAILED, STORAGE_RESET,
}

@Serializable
data class DiagnosticCounterSnapshot(
    val schema: Int = 1,
    val totals: Map<DiagnosticCounter, Long> = emptyMap(),
)

/** Only bounded counters are staged in memory. Persistence is called on a diagnostic worker. */
class DiagnosticCounters(private val store: DiagnosticBudgetStore) {
    private val lock = DiagnosticLock()
    private val pending = mutableMapOf<DiagnosticCounter, Long>()
    private var flushing = false
    private var loaded = false
    private var last = DiagnosticCounterSnapshot()

    fun increment(counter: DiagnosticCounter, amount: Long = 1) = lock.withLock {
        if (amount > 0) pending[counter] = ((pending[counter] ?: 0) + amount.coerceAtMost(MAX)).coerceAtMost(MAX)
    }

    fun snapshot(): DiagnosticCounterSnapshot = lock.withLock {
        DiagnosticCounterSnapshot(1, merge(last.totals, pending))
    }

    /** Reserve a delta once; on storage failure merge it back with concurrent producer increments. */
    fun flush(): Boolean {
        var alreadyPersisted = false
        val delta = lock.withLock {
            if (flushing) return@withLock null
            if (pending.isEmpty() && loaded) {
                alreadyPersisted = true
                return@withLock null
            }
            flushing = true
            pending.toMap().also { pending.clear() }
        } ?: return alreadyPersisted
        var saved = DiagnosticCounterSnapshot()
        val success = runCatching {
            store.update { raw ->
                val parsed = raw?.takeIf { it.length <= 4096 }?.let {
                    runCatching { Json.decodeFromString<DiagnosticCounterSnapshot>(it) }.getOrNull()
                }?.takeIf { it.schema == 1 && it.totals.values.all { n -> n in 0..MAX } }
                val reset = if (raw != null && parsed == null) mapOf(DiagnosticCounter.STORAGE_RESET to 1L) else emptyMap()
                saved = DiagnosticCounterSnapshot(1, merge(merge(parsed?.totals.orEmpty(), reset), delta))
                Json.encodeToString(saved)
            }
        }.getOrDefault(false)
        lock.withLock {
            if (success) { last = saved; loaded = true } else {
                val restored = merge(pending, delta)
                pending.clear(); pending.putAll(restored)
                pending[DiagnosticCounter.STORAGE_FAILED] = ((pending[DiagnosticCounter.STORAGE_FAILED] ?: 0) + 1).coerceAtMost(MAX)
            }
            flushing = false
        }
        return success
    }

    private fun merge(a: Map<DiagnosticCounter, Long>, b: Map<DiagnosticCounter, Long>) = buildMap {
        for (key in a.keys + b.keys) put(key, ((a[key] ?: 0) + (b[key] ?: 0)).coerceAtMost(MAX))
    }

    private companion object { const val MAX = 1_000_000_000_000L }
}

/** Same private, bounded, atomic store as budgets, in an independent counters partition. */
fun diagnosticCounterStore(component: Component, environment: Environment, directory: String? = null): DiagnosticBudgetStore =
    diagnosticBudgetStore(component, environment, directory?.let { "$it/counters" }, counters = true)
