package dev.ccpocket.observability

/**
 * The analytics event/parameter whitelist shared by the desktop sender and the relay ingress
 * (docs/observability/DESKTOP-GA4-INGRESS.md §4). It mirrors the app's `TelEvent`/`TelKey` ids; the
 * mobile desktopTest `AnalyticsCatalogAlignmentTest` fails when either side drifts, so adding an
 * event means adding it here too. Names only — never values, content, or identifiers.
 */
object AnalyticsCatalog {
    const val SCHEMA = "v1"

    val events: Set<String> = setOf(
        "session_open_result", "session_open_recovered", "prompt_response_result", "prompt_response_recovered",
        "turn_result", "connection_recovery_result", "approval_apply_result", "file_view_result",
        "background_task_result", "value_reached", "first_value_observed", "feature_exposed", "feature_used",
        "app_launch", "onboarding_shown", "onboarding_cta", "demo_entered", "pair_started", "paired",
        "pair_failed", "connected", "disconnected", "conn_phase", "conn_failed", "session_opened",
        "session_open_timeout", "prompt_sent", "prompt_turn_stalled", "prompt_turn_queued", "prompt_resent",
        "approval_shown", "approval_decided", "help_opened", "help_support_opened", "help_task_opened",
        "help_guide_opened", "help_direct_action",
    )

    /** Product parameter keys (`TelKey.id`). */
    val params: Set<String> = setOf(
        "app_version", "result", "coverage", "duration_ms", "feature", "reuse", "backend", "source", "transport",
        "resume", "tool", "decision", "phase", "reason", "attempt", "link", "retried", "version", "entry_point",
        "help_task", "demo", "target", "value", "analytics_schema", "app_platform", "app_environment",
        "internal_traffic", "usage_mode",
    )

    /** Transport keys the desktop sender adds beside product params; the ingress accepts exactly these. */
    val transportParams: Set<String> = setOf("edition", "session_id")

    /** Keys the ingress sets itself; a client value is rejected. */
    val serverOwnedParams: Set<String> = setOf("engagement_time_msec", "debug_mode")

    val environments: Set<String> = setOf("production", "staging", "development", "unknown")

    const val MAX_EVENTS_PER_REQUEST = 10
    const val MAX_BODY_BYTES = 8192
    const val MAX_STRING_LENGTH = 64
    const val MAX_INTEGER_MAGNITUDE = 1_000_000_000_000L
    val stringValue = Regex("[A-Za-z0-9_.:+/ -]*")
    val appVersion = Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,95}")
    val clientId = Regex("[0-9]{1,20}\\.[0-9]{1,20}")
    val sessionId = Regex("[0-9]{1,20}")
}
