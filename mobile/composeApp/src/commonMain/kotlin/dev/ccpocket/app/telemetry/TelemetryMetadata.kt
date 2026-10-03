package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.Component
import dev.ccpocket.observability.Environment

/** Runtime-owned dimensions. Missing configuration is unknown, never inferred to be production. */
internal data class TelemetryMetadata(
    val platform: Component,
    val environment: Environment? = null,
    val internalTraffic: Boolean? = null,
    /** Where this install came from, when the platform can tell (issue #342): on iOS `store` for an App
     *  Store install and `sandbox` for TestFlight AND App Review — both run against the sandbox receipt, and
     *  review installs walk the demo on every submission, which made the top of the overseas funnel
     *  unreadable. Null = the platform has no such signal; nothing is sent. */
    val installChannel: String? = null,
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
        // only the top-of-funnel events, which carry no `source` of their own; never over a caller's value
        if (installChannel != null && event in channelEvents && TelKey.Source !in params) put(TelKey.Source, installChannel)
        // Global navigation/startup events do not identify a real or demo work session.
        put(TelKey.UsageMode, when {
            params[TelKey.Demo] in listOf(1, 1L, "1", true) || event == TelEvent.DemoEntered || event == TelEvent.DemoExited -> "demo"
            params[TelKey.UsageMode] in setOf("own", "shared", "demo") -> params[TelKey.UsageMode] as String
            event in scopedEvents -> "real"
            else -> "unknown"
        })
    }

    companion object {
        private val tools = setOf("Bash", "Read", "Write", "Edit", "MultiEdit", "Glob", "Grep", "Task",
            "WebFetch", "WebSearch", "NotebookEdit", "AskUserQuestion", "ExitPlanMode", "EnterPlanMode", "TodoWrite")
        private val channelEvents = setOf(TelEvent.AppLaunch, TelEvent.OnboardingShown, TelEvent.DemoEntered, TelEvent.DemoExited)
        private val scopedEvents = setOf(TelEvent.PairStarted, TelEvent.Paired, TelEvent.PairFailed,
            TelEvent.Connected, TelEvent.SessionOpened, TelEvent.SessionOpenTimeout, TelEvent.PromptSent)
    }
}
