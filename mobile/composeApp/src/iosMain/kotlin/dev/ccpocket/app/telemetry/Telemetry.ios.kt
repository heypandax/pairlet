package dev.ccpocket.app.telemetry

import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.observability.*

private val firstValue = FirstValueObservation(
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    { env -> diagnosticBudgetStore(Component.IOS, env,
        platform.Foundation.NSHomeDirectory() + "/Library/Application Support/cc-pocket/first-value") },
)

/** Firebase lives in Swift on iOS; iOSApp.swift registers these sinks at launch via [setTelemetrySink]. */
object TelemetrySink {
    internal var metadata = TelemetryMetadata(Component.IOS)
    var onEvent: ((String, Map<String, Any>) -> Unit)? = null
    var enabled: Boolean = SecureStore.getString("telemetry_enabled") != "false"
    var onEnabled: ((Boolean) -> Unit)? = null
}

actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) {
        TelemetryConsent.changed()
        TelemetrySink.enabled = enabled
        DiagnosticBridge.setEnabled(enabled)
        TelemetrySink.onEnabled?.invoke(enabled)
        if (!enabled) firstValue.resetIdentity()
        SecureStore.putString("telemetry_enabled", enabled.toString())
    }
    actual fun isEnabled(): Boolean = TelemetrySink.enabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        if (!TelemetrySink.enabled) return
        val prepared = TelemetrySink.metadata.prepare(event, params)
        TelemetrySink.onEvent?.let { it(event.id, prepared.mapKeys { it.key.id }); firstValue.observe(event, prepared) }
    }
}
