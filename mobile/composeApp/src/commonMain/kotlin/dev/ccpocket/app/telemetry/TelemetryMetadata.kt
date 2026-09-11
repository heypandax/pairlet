package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.Component
import dev.ccpocket.observability.Environment

/** Runtime-owned dimensions. Missing configuration is unknown, never inferred to be production. */
internal data class TelemetryMetadata(
    val platform: Component,
    val environment: Environment? = null,
    val internalTraffic: Boolean? = null,
) {
    fun prepare(event: TelEvent, params: Map<TelKey, Any>): Map<TelKey, Any> = buildMap {
        putAll(params)
        // Agent/MCP tool names are dynamic. Keep only known built-in names; do not upload custom names.
        params[TelKey.Tool]?.let { put(TelKey.Tool, if (it is String && it in tools) it else "other") }
        // GA4 app custom dimensions ignore integer parameters. Keep this categorical on every SDK.
        put(TelKey.AnalyticsSchema, "v1")
        put(TelKey.AppVersion, dev.ccpocket.app.APP_VERSION.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,95}").matches(it) } ?: "unknown")
        put(TelKey.AppPlatform, platform.name.lowercase())
        put(TelKey.Environment, environment?.name?.lowercase() ?: "unknown")
        val internal = internalTraffic ?: when (environment) {
            Environment.DEVELOPMENT, Environment.STAGING -> true
            Environment.PRODUCTION -> false
            else -> null
        }
        put(TelKey.InternalTraffic, internal?.let { if (it) "1" else "0" } ?: "unknown")
        // Global navigation/startup events do not identify a real or demo work session.
        put(TelKey.UsageMode, when {
            params[TelKey.Demo] in listOf(1, 1L, "1", true) || event == TelEvent.DemoEntered -> "demo"
            params[TelKey.UsageMode] in setOf("own", "shared", "demo") -> params[TelKey.UsageMode] as String
            event in scopedEvents -> "real"
            else -> "unknown"
        })
    }

    companion object {
        private val tools = setOf("Bash", "Read", "Write", "Edit", "MultiEdit", "Glob", "Grep", "Task",
            "WebFetch", "WebSearch", "NotebookEdit", "AskUserQuestion", "ExitPlanMode", "EnterPlanMode", "TodoWrite")
        private val scopedEvents = setOf(TelEvent.PairStarted, TelEvent.Paired, TelEvent.PairFailed,
            TelEvent.Connected, TelEvent.SessionOpened, TelEvent.SessionOpenTimeout, TelEvent.PromptSent)
    }
}
