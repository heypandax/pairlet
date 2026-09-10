package dev.ccpocket.observability.sentry

import dev.ccpocket.observability.*
import java.util.Properties

/** Configuration is read explicitly. Public DSNs never enable collection without the product's opt-in. */
object SentryRuntime {
    @Volatile private var sink: SentryDiagnosticSink? = null
    private var active: Boolean = false
    private var reporter: DiagnosticReporter? = null
    private var reporterKey: Triple<Component, String, Environment>? = null
    @Volatile private var counters: DiagnosticCounters? = null
    private var counterKey: Triple<Component, Environment, String?>? = null
    private val counterWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cc-pocket-diagnostic-counters").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({ runCatching { counters?.flush() } }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    private var shutdownHookInstalled = false

    @Synchronized
    fun configure(component: Component, version: String, enabled: Boolean, dsn: String? = configuredDsn(component),
                  environment: Environment = configuredEnvironment(), budgetDirectory: String? = null) {
        Diagnostics.install(null)
        runCatching { sink?.close() }
        sink = null
        active = false
        if (!enabled || System.getProperty("ccpocket.test") == "true" || !SentryDiagnosticSink.validDsn(dsn)) return
        val nextCounterKey = Triple(component, environment, budgetDirectory)
        if (counterKey != nextCounterKey) {
            counters?.let { previous -> counterWorker.execute { previous.flush() } }
            counters = runCatching { DiagnosticCounters(diagnosticCounterStore(component, environment, budgetDirectory)) }.getOrNull()
            counterKey = nextCounterKey
            reporterKey = null
        }
        counterWorker // Lazy start; no disk I/O on this caller.
        val created = runCatching {
            SentryDiagnosticSink.create(dsn, component,
                DiagnosticBudget(component, diagnosticBudgetStore(component, environment, budgetDirectory)), counters)
        }.getOrNull() ?: return
        sink = created
        if (component != Component.ANDROID && !shutdownHookInstalled) {
            shutdownHookInstalled = runCatching {
                Runtime.getRuntime().addShutdownHook(Thread({
                    runCatching { flush() }
                    close()
                }, "cc-pocket-diagnostic-shutdown"))
                true
            }.getOrDefault(false)
        }
        val key = Triple(component, version, environment)
        if (reporterKey != key) {
            reporter = DiagnosticReporter(component, environment, "cc-pocket-${component.name.lowercase()}@$version",
                DiagnosticSink { sink?.tryEmit(it) ?: false }, counters = counters)
            reporterKey = key
        }
        reporter?.setEnabled(true)
        Diagnostics.install(reporter)
        active = true
    }

    @Synchronized fun isActive(): Boolean = active
    @Synchronized fun close() {
        Diagnostics.install(null); runCatching { sink?.close() }; sink = null; active = false
        counters?.let { current -> counterWorker.execute { current.flush() } }
    }
    @Synchronized fun flush() { sink?.flush(); counters?.flush() }
    fun counterSnapshot(): DiagnosticCounterSnapshot? = counters?.snapshot()

    fun configuredDsn(component: Component): String? {
        val key = component.name.lowercase()
        return System.getenv("CCPOCKET_SENTRY_DSN_${component.name}")?.takeIf { it.isNotBlank() }
            ?: bundled().getProperty("dsn.$key")?.takeIf { it.isNotBlank() }
    }

    fun configuredEnvironment(): Environment = configuredEnvironmentOrNull() ?: Environment.DEVELOPMENT

    fun configuredEnvironmentOrNull(): Environment? = when (System.getenv("CCPOCKET_SENTRY_ENVIRONMENT") ?: bundled().getProperty("environment")) {
        "production" -> Environment.PRODUCTION
        "staging" -> Environment.STAGING
        "development" -> Environment.DEVELOPMENT
        else -> null
    }

    private fun bundled(): Properties = Properties().apply {
        runCatching { SentryRuntime::class.java.getResourceAsStream("/cc-pocket-sentry.properties")?.use(::load) }
    }
}
