package dev.ccpocket.app.telemetry

/** Firebase lives in Swift on iOS; iOSApp.swift registers these sinks at launch via [setTelemetrySink]. */
object TelemetrySink {
    var onEvent: ((String, Map<String, Any>) -> Unit)? = null
    var onError: ((String, String?) -> Unit)? = null
    var enabled: Boolean = true
}

actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) { TelemetrySink.enabled = enabled }
    actual fun isEnabled(): Boolean = TelemetrySink.enabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        if (!TelemetrySink.enabled) return
        TelemetrySink.onEvent?.invoke(event.id, params.mapKeys { it.key.id })
    }

    actual fun recordError(message: String, phase: String?) {
        if (!TelemetrySink.enabled) return
        TelemetrySink.onError?.invoke(message, phase)
    }
}
