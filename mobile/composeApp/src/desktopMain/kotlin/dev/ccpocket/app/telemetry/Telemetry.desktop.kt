package dev.ccpocket.app.telemetry

/** Desktop has no analytics backend — every call is a no-op. */
actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) {}
    actual fun isEnabled(): Boolean = false
    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {}
    actual fun recordError(message: String, phase: String?) {}
}
