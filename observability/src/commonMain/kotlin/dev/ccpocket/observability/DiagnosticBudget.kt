package dev.ccpocket.observability

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Atomic read/replace. Implementations run only on the diagnostic worker, never a business caller. */
fun interface DiagnosticBudgetStore {
    fun update(transform: (String?) -> String): Boolean
}

/** Only aggregate counters live here: no event IDs, stack symbols, credentials or user content. */
@Serializable
internal data class BudgetState(
    val schema: Int = 1,
    val day: Long,
    val errors: Int = 0,
    val logs: Int = 0,
    val bytes: Long = 0,
    val admitted: Long = 0,
    val suppressed: Long = 0,
    val dropped: Long = 0,
) {
    fun valid() = schema == 1 && day >= 0 && errors in 0..20 && logs in 0..500 &&
        bytes in 0..20_971_520 && listOf(admitted, suppressed, dropped).all { it in 0..MAX_COUNTER }
}

private const val MAX_COUNTER = 1_000_000_000_000L
private fun Long.incrementBounded() = (this + 1).coerceAtMost(MAX_COUNTER)

/**
 * Reserve before handing a record to the SDK. Failed delivery is not refunded, so a crash after a
 * reservation cannot manufacture quota. Re-read under the store's lock for every reservation, including
 * after a runtime/SDK replacement. Failures disable this send, never the product operation.
 */
class DiagnosticBudget(
    private val component: Component,
    private val store: DiagnosticBudgetStore,
    private val epochMs: () -> Long = ::diagnosticEpochMs,
) {
    fun admit(kind: DiagnosticKind, bytes: Int): Boolean {
        var admitted = false
        val saved = runCatching {
            store.update { raw ->
                val today = epochMs().coerceAtLeast(0) / 86_400_000L
                val errorLimit = if (component == Component.RELAY) 20 else 10
                val byteLimit = if (component == Component.RELAY) 20_971_520L else 1_048_576L
                val parsed = raw?.takeIf { it.length <= 4096 }?.let {
                    runCatching { Json.decodeFromString<BudgetState>(it) }.getOrNull()?.takeIf(BudgetState::valid)
                }
                // A damaged counter file must not become a fresh daily allowance on every restart.
                var state = when {
                    raw == null -> BudgetState(day = today)
                    parsed == null -> BudgetState(day = today, errors = errorLimit, logs = 500, bytes = byteLimit)
                    today > parsed.day -> parsed.copy(day = today, errors = 0, logs = 0, bytes = 0)
                    else -> parsed // A backwards clock never resets a quota.
                }
                // Repeated logs cannot spend the bytes needed for the remaining bounded error reports.
                val availableBytes = byteLimit - state.bytes - if (kind == DiagnosticKind.ERROR) 0L else
                    (errorLimit - state.errors).coerceAtLeast(0) * 16_384L
                state = when {
                    bytes !in 1..16_384 || bytes > availableBytes ->
                        state.copy(dropped = state.dropped.incrementBounded())
                    (kind == DiagnosticKind.ERROR && state.errors >= errorLimit) ||
                        (kind != DiagnosticKind.ERROR && state.logs >= 500) ->
                        state.copy(suppressed = state.suppressed.incrementBounded())
                    else -> {
                        admitted = true
                        state.copy(errors = state.errors + if (kind == DiagnosticKind.ERROR) 1 else 0,
                            logs = state.logs + if (kind == DiagnosticKind.ERROR) 0 else 1,
                            bytes = state.bytes + bytes, admitted = state.admitted.incrementBounded())
                    }
                }
                Json.encodeToString(state)
            }
        }.getOrDefault(false)
        return saved && admitted
    }
}

/** Directory is private application storage; component/environment, never release or DSN, keys quota. */
expect fun diagnosticBudgetStore(component: Component, environment: Environment,
                                directory: String? = null, counters: Boolean = false): DiagnosticBudgetStore
