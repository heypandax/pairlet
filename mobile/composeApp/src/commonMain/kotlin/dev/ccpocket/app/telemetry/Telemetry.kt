package dev.ccpocket.app.telemetry

/**
 * Anonymous usage events. Only enum-level metadata is ever sent — never prompts, directory paths,
 * tool inputs, account ids, or any user content. Mirrors cc-dashboard's Telemetry seam: Firebase is
 * the only backing and stays hidden behind this single API, so business code never imports Firebase.
 */
enum class TelEvent(val id: String) {
    AppLaunch("app_launch"),
    Paired("paired"),
    PairFailed("pair_failed"),
    Connected("connected"),
    Disconnected("disconnected"),
    // connection diagnostics: ConnPhase fires on every connection-state transition (the honest state the
    // user sees); ConnFailed fires when a transport attempt dies, carrying WHY. Together they let us see in
    // Firebase whether "can't connect" is a wedged handshake, relay-unreachable, computer-offline, or auth.
    ConnPhase("conn_phase"),
    ConnFailed("conn_failed"),
    SessionOpened("session_opened"),
    PromptSent("prompt_sent"),
    // delivery/turn diagnostics (issue #104): PromptTurnStalled fires when the daemon ACKED a prompt
    // (wrote it to the agent's stdin) but no turn frame followed within the deadline — the agent swallowed
    // it (wedged / mid-relaunch). PromptResent fires when the user acts on that cue. Together with the
    // no-ack stall (issue #78) they split "never delivered" from "delivered but no turn".
    PromptTurnStalled("prompt_turn_stalled"),
    PromptResent("prompt_resent"),
    ApprovalShown("approval_shown"),
    ApprovalDecided("approval_decided"),
}

/** Parameter keys — also enum-only; values are short categorical strings or counts, never content. */
enum class TelKey(val id: String) {
    Source("source"),       // qr | code | link
    Transport("transport"), // relay | direct
    Resume("resume"),       // 0 | 1
    Tool("tool"),
    Decision("decision"),   // allow | deny
    Phase("phase"),         // ConnPhase name, e.g. Ready | RelayUnreachable | ComputerOffline
    Reason("reason"),       // conn_failed cause: wedged | auth | closed | <exception name>
    Attempt("attempt"),     // reconnect attempt counter at the time of failure
    Version("version"),
}

/** The single seam over Firebase Analytics + Crashlytics. Default-on, opt-out via [setEnabled]. */
expect object Telemetry {
    fun setEnabled(enabled: Boolean)
    fun isEnabled(): Boolean
    fun track(event: TelEvent, params: Map<TelKey, Any> = emptyMap())
    fun recordError(message: String, phase: String? = null)
}
