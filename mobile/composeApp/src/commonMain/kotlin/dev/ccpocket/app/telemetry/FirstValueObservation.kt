package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.DiagnosticBudgetStore
import dev.ccpocket.observability.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One first observed value per analytics identity/environment/role, not a new-install assertion.
 * A worker atomically claims a fixed boolean marker before emitting; SDK delivery is still best effort.
 * The store is a separate partition and never reads or modifies diagnostic budgets or credentials. */
internal class FirstValueObservation(
    scope: CoroutineScope,
    private val store: (Environment) -> DiagnosticBudgetStore?,
    private val emit: (Map<TelKey, Any>) -> Unit = { Telemetry.track(TelEvent.FirstValueObserved, it) },
    private val captureConsent: () -> (() -> Boolean) = { TelemetryConsent.capture() },
) {
    @Serializable private data class Markers(val schema: Int = 1, val claimed: Set<String> = emptySet())
    private data class Work(val params: Map<TelKey, Any>, val allowed: () -> Boolean)
    private val queue = Channel<Work?>(8)
    @kotlin.concurrent.Volatile private var resetToken: Any = Any()
    private var clearedToken = resetToken
    private val known = mutableSetOf<Pair<Environment, String>>() // worker confined, at most 12 keys

    init {
        scope.launch {
            for (work in queue) {
                val reset = resetToken
                if (reset !== clearedToken) {
                    val cleared = Environment.entries.map { env ->
                        runCatching { store(env)?.update { Json.encodeToString(Markers()) } == true }.getOrDefault(false)
                    }.all { it }
                    if (!cleared) continue // never invent a fresh first value after an unconfirmed reset
                    known.clear(); clearedToken = reset
                }
                if (work == null || !work.allowed()) continue
                val env = when (work.params[TelKey.Environment]) {
                    "production" -> Environment.PRODUCTION
                    "staging" -> Environment.STAGING
                    "development" -> Environment.DEVELOPMENT
                    else -> continue
                }
                val key = "${work.params[TelKey.InternalTraffic]}:${work.params[TelKey.UsageMode]}"
                if (key !in KEYS || env to key in known) continue
                var claimed = false
                var alreadyClaimed = false
                val saved = runCatching {
                    store(env)?.update { raw ->
                        val markers = if (raw == null) Markers() else
                            runCatching { Json.decodeFromString<Markers>(raw) }.getOrNull()
                                ?.takeIf { it.schema == 1 && it.claimed.all { key -> key in KEYS } }
                                ?: Markers(claimed = KEYS) // corrupt state cannot mint a new cohort
                        alreadyClaimed = key in markers.claimed
                        claimed = !alreadyClaimed && work.allowed() && reset === resetToken
                        Json.encodeToString(if (claimed) markers.copy(claimed = markers.claimed + key) else markers)
                    } == true
                }.getOrDefault(false)
                if (saved && (claimed || alreadyClaimed)) known += env to key
                if (saved && claimed && work.allowed() && reset === resetToken) runCatching { emit(work.params) }
            }
        }
    }

    fun observe(event: TelEvent, prepared: Map<TelKey, Any>) {
        if (event != TelEvent.ValueReached || prepared[TelKey.UsageMode] !in setOf("own", "shared")) return
        queue.trySend(Work(prepared.toMap(), captureConsent()))
    }

    /** Called only when the platform also resets its Analytics identity (Android/iOS opt-out). */
    fun resetIdentity() { resetToken = Any(); queue.trySend(null) }

    private companion object { val KEYS = setOf("0:own", "0:shared", "1:own", "1:shared") }
}
