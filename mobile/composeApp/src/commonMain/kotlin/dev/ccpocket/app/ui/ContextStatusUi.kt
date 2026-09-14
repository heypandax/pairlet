package dev.ccpocket.app.ui

import dev.ccpocket.protocol.AgentKind

/**
 * Issue #320-A: what the context readout may claim, derived ONLY from the fields the session actually has.
 *
 * Both shells used to render a gap as silence (the composer gauge vanished, the desktop header dropped its
 * `ctx` segment) or as a bare `—`, which cannot tell "the backend hasn't sent a value" apart from "it is 0".
 * This projection names the evidence shape instead, and every surface renders from it:
 *
 * | used | window (> 0) | kind            | readout            | notes                    |
 * |------|--------------|-----------------|--------------------|--------------------------|
 * | ✓    | ✓            | USED_OF_WINDOW  | `~84k / 200k · 42%`| —                        |
 * | ✓    | ✗            | USED_ONLY       | `~84k`             | window unknown, no %     |
 * | ✗    | ✓            | WINDOW_ONLY     | `— / 200k`         | usage not received (≠ 0) |
 * | ✗    | ✗            | NO_DATA         | `—`                | no data yet ≠ unsupported|
 *
 * Plus [ContextStatusNote.WINDOW_USER_OVERRIDE] whenever the shown window is the user's hand-typed value.
 *
 * Deliberately NOT an input: [AgentKind]. "This backend doesn't support context" is only sayable once a
 * backend tells us so; a static per-agent table would go stale the day a backend starts reporting (#320's
 * DSH live `usage_update` already does). Until then the provable statement is "not received yet".
 */
enum class ContextStatusKind { USED_OF_WINDOW, USED_ONLY, WINDOW_ONLY, NO_DATA }

/** Caveats a surface must show next to the numbers. Order is display order. */
enum class ContextStatusNote {
    /** Neither value arrived. Says "no data yet" — never "unsupported". */
    NO_DATA,
    /** Occupancy is known, the window is not: no percentage can be computed. */
    WINDOW_UNKNOWN,
    /** The window is known, occupancy is not: it must not read as 0. */
    USED_PENDING,
    /** The shown window is the user's own override, not something the runtime reported. */
    WINDOW_USER_OVERRIDE,
}

data class ContextStatusUi(
    val kind: ContextStatusKind,
    /** Sanitized occupancy: null when absent or negative. 0 is a real measurement. */
    val used: Long?,
    /** Sanitized window: null when absent or ≤ 0. */
    val window: Long?,
    val windowIsUserOverride: Boolean,
) {
    /** Bar fill, clamped to the track. Null unless both facts exist. */
    val fraction: Float?
        get() = if (used != null && window != null) (used.toFloat() / window).coerceIn(0f, 1f) else null

    /** The ratio the desktop header always printed (`used * 100 / window`, unclamped). Null unless both facts exist. */
    val percent: Int?
        get() = if (used != null && window != null) (used * 100 / window).toInt() else null

    val notes: List<ContextStatusNote>
        get() = buildList {
            when (kind) {
                ContextStatusKind.NO_DATA -> add(ContextStatusNote.NO_DATA)
                ContextStatusKind.USED_ONLY -> add(ContextStatusNote.WINDOW_UNKNOWN)
                ContextStatusKind.WINDOW_ONLY -> add(ContextStatusNote.USED_PENDING)
                ContextStatusKind.USED_OF_WINDOW -> Unit
            }
            if (windowIsUserOverride) add(ContextStatusNote.WINDOW_USER_OVERRIDE)
        }
}

/**
 * Build the status from the repository's live values.
 *
 * [windowOverride] is the override in force for the running model (`repo.contextWindowOverrideFor(model)`);
 * it only marks the window as the user's when it is the very value being displayed, so a stale or
 * yielded catch-all is never credited.
 */
fun contextStatusUi(used: Long?, window: Long?, windowOverride: Long? = null): ContextStatusUi {
    val u = used?.takeIf { it >= 0L }
    val w = window?.takeIf { it > 0L }
    val kind = when {
        u != null && w != null -> ContextStatusKind.USED_OF_WINDOW
        u != null -> ContextStatusKind.USED_ONLY
        w != null -> ContextStatusKind.WINDOW_ONLY
        else -> ContextStatusKind.NO_DATA
    }
    return ContextStatusUi(kind, u, w, windowIsUserOverride = w != null && windowOverride == w)
}

/** `~84k` — the existing approximate-occupancy style, except a measured 0 stays a plain `0`.
 *  Shared with the composer gauge so both surfaces print the same token string. */
internal fun contextUsedTokens(used: Long): String = if (used == 0L) "0" else "~${formatTokens(used)}"

/** Language-neutral numbers for the session sheet / popover header row. */
fun contextStatusReadout(status: ContextStatusUi): String {
    val u = status.used
    val w = status.window
    return when (status.kind) {
        ContextStatusKind.USED_OF_WINDOW -> "${contextUsedTokens(u!!)} / ${formatTokens(w!!)} · ${status.percent}%"
        ContextStatusKind.USED_ONLY -> contextUsedTokens(u!!)
        ContextStatusKind.WINDOW_ONLY -> "— / ${formatTokens(w!!)}"
        ContextStatusKind.NO_DATA -> "—"
    }
}

/** The desktop header's mono `ctx …` segment — now present in every state instead of vanishing. */
fun contextStatusMetaSegment(status: ContextStatusUi): String = when (status.kind) {
    ContextStatusKind.USED_OF_WINDOW -> "ctx ${status.percent}%"
    ContextStatusKind.USED_ONLY -> "ctx ${contextUsedTokens(status.used!!)}"
    ContextStatusKind.WINDOW_ONLY -> "ctx — / ${formatTokens(status.window!!)}"
    ContextStatusKind.NO_DATA -> "ctx —"
}

/** What to print when a session has no model id. */
enum class SessionModelFallback {
    /** Claude only: the account really has a default model when none was pinned. */
    ACCOUNT_DEFAULT,
    /** Every other backend: it simply hasn't named its model — never substitute a default or a setting. */
    UNKNOWN,
}

/** Null = [model] is a real id, show it. Blank counts as absent. A null [agent] is an older daemon → Claude. */
fun sessionModelFallback(agent: AgentKind?, model: String?): SessionModelFallback? = when {
    !model.isNullOrBlank() -> null
    (agent ?: AgentKind.CLAUDE) == AgentKind.CLAUDE -> SessionModelFallback.ACCOUNT_DEFAULT
    else -> SessionModelFallback.UNKNOWN
}
