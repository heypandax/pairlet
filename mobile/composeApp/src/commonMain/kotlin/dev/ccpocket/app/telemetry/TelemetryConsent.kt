package dev.ccpocket.app.telemetry

/** Old in-flight observations cannot upload after an opt-out/opt-in cycle. Contains no event buffer. */
internal object TelemetryConsent {
    @kotlin.concurrent.Volatile private var generation: Any = Any()
    fun changed() { generation = Any() }
    fun capture(): () -> Boolean {
        val token = generation
        val enabled = Telemetry.isEnabled()
        return { enabled && token === generation && Telemetry.isEnabled() }
    }
}
