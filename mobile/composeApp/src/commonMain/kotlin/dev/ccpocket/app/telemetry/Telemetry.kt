package dev.ccpocket.app.telemetry

/**
 * Anonymous usage events. Only enum-level metadata is ever sent — never prompts, directory paths,
 * tool inputs, account ids, or any user content. Mirrors cc-dashboard's Telemetry seam: Firebase is
 * the only backing and stays hidden behind this single API, so business code never imports Firebase.
 */
enum class TelEvent(val id: String) {
    AppLaunch("app_launch"),
    // activation funnel (issue #278): the stretch between app_launch and paired used to be a black box, so a
    // drop-off there was unattributable. OnboardingShown measures install-guide exposure; PairStarted fires on
    // every pairing ATTEMPT before any network action, which is what separates "tried and failed" from "never
    // tried"; PairFailed now carries WHY (TelKey.Reason), including the two rejects that never reach the relay.
    OnboardingShown("onboarding_shown"),
    PairStarted("pair_started"),
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
    // issue #340: an open that never got its SessionLive. The old banner blamed the computer whatever the
    // real cause was, and nothing was recorded — so the only evidence this path ever produced was a user's
    // screenshot. [TelKey.Link] splits "the link was never up" from "a Ready link went unanswered", and
    // [TelKey.Retried] says whether the silent auto-resend had already been spent on it.
    SessionOpenTimeout("session_open_timeout"),
    PromptSent("prompt_sent"),
    // delivery/turn diagnostics (issue #104): PromptTurnStalled fires when the daemon ACKED a prompt
    // (wrote it to the agent's stdin) but no turn frame followed within the deadline — the agent swallowed
    // it (wedged / mid-relaunch). PromptResent fires when the user acts on that cue. Together with the
    // no-ack stall (issue #78) they split "never delivered" from "delivered but no turn".
    // PromptTurnQueued is the mid-turn-send sibling: the same silent deadline hit while the prompt sat in
    // the CLI's queue behind a running turn — expected, surfaced as a calm status instead of a resend cue.
    PromptTurnStalled("prompt_turn_stalled"),
    PromptTurnQueued("prompt_turn_queued"),
    PromptResent("prompt_resent"),
    ApprovalShown("approval_shown"),
    ApprovalDecided("approval_decided"),
    // learning/help discovery: enum-only entry/task ids answer whether the native task guides are useful.
    // No search text, question, screen contents, or learning profile is collected.
    HelpOpened("help_opened"),
    HelpSupportOpened("help_support_opened"),
    HelpTaskOpened("help_task_opened"),
    HelpGuideOpened("help_guide_opened"),
    HelpDirectAction("help_direct_action"),
}

/** Parameter keys — also enum-only; values are short categorical strings or counts, never content. */
enum class TelKey(val id: String) {
    Source("source"),       // qr | qr-link | code | link | share | collaborator | code-add
    Transport("transport"), // relay | direct
    Resume("resume"),       // 0 | 1
    Tool("tool"),
    Decision("decision"),   // allow | deny
    Phase("phase"),         // ConnPhase name, e.g. Ready | RelayUnreachable | ComputerOffline
    // conn_failed cause: wedged | auth | closed | <exception name>
    // pair_failed cause: parse (rejected before any network) | code | redeem | <exception name>. A CLASS only —
    // the exception's message is never transmitted, since a redeem failure carries the relay's response body.
    Reason("reason"),
    Attempt("attempt"),     // reconnect attempt counter at the time of failure
    Link("link"),           // ready | down — the connection phase an open gave up under (issue #340)
    Retried("retried"),     // 0 | 1 — whether that open had already spent its silent auto-resend (#340)
    Version("version"),
    EntryPoint("entry_point"), // projects | sessions | chat | settings
    HelpTask("help_task"),     // one of the fixed HelpTaskId values
    // 1 when the event came from the no-pairing demo walkthrough; ABSENT on a real session. The demo drives the
    // real state machine, so connected/session_opened/prompt_sent fire either way — without this split, demo
    // browsing counted as activation (issue #278). The demo's own behaviour is untouched.
    Demo("demo"),
}

/** The single seam over Firebase Analytics + Crashlytics. Default-on, opt-out via [setEnabled]. */
expect object Telemetry {
    fun setEnabled(enabled: Boolean)
    fun isEnabled(): Boolean
    fun track(event: TelEvent, params: Map<TelKey, Any> = emptyMap())
    fun recordError(message: String, phase: String? = null)
}
