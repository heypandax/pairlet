package dev.ccpocket.app.telemetry

import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.observability.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*

/** Cocoa owns SDK lifecycle; Kotlin owns classification, budgets and safe stack extraction. */
internal object DiagnosticBridge {
    private var reporter: DiagnosticReporter? = null
    private var budget: DiagnosticBudget? = null
    private var counters: DiagnosticCounters? = null
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var counterJob: Job? = null
    var onEnabled: ((Boolean) -> Unit)? = null

    fun register(environment: String, onRecord: (String) -> Boolean, onEnabled: (Boolean) -> Unit) {
        this.onEnabled = onEnabled
        val env = when (environment) {
            "production" -> Environment.PRODUCTION
            "staging" -> Environment.STAGING
            else -> Environment.DEVELOPMENT
        }
        budget = DiagnosticBudget(Component.IOS, diagnosticBudgetStore(Component.IOS, env))
        counters = DiagnosticCounters(diagnosticCounterStore(Component.IOS, env))
        if (counterJob == null) counterJob = healthScope.launch {
            while (isActive) { delay(1000); counters?.flush() }
        }
        TelemetrySink.metadata = TelemetryMetadata(Component.IOS,
            env.takeIf { environment in listOf("production", "staging", "development") })
        reporter = DiagnosticReporter(Component.IOS, env, "cc-pocket-ios@$APP_VERSION", DiagnosticSink {
            onRecord(Json.encodeToString(it))
        }, counters = counters)
        setEnabled(Telemetry.isEnabled())
    }

    /** Called by the Cocoa worker before SDK admission. File I/O never runs in the business callback. */
    fun admit(json: String): Boolean = runCatching {
        if (json.length > 16_384) return false
        val record = Json.decodeFromString<DiagnosticRecord>(json)
        (budget?.admit(record.kind, json.encodeToByteArray().size) == true).also {
            if (!it) counters?.increment(DiagnosticCounter.BUDGET_REJECTED)
        }
    }.getOrDefault(false)

    fun admitSpans(): Boolean = (budget?.admit(DiagnosticKind.RESULT, 16_384) == true).also {
        if (!it) counters?.increment(DiagnosticCounter.BUDGET_REJECTED)
    }

    fun setEnabled(enabled: Boolean) {
        Diagnostics.install(null)
        onEnabled?.invoke(enabled)
        reporter?.setEnabled(enabled)
        if (enabled) Diagnostics.install(reporter)
    }

    fun incrementCounter(name: String) {
        DiagnosticCounter.entries.firstOrNull { it.name == name }?.let { counters?.increment(it) }
    }

    fun flushCounters() { counters?.flush() }
}
