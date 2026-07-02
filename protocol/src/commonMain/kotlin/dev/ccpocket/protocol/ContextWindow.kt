package dev.ccpocket.protocol

/** Standard Claude context window (tokens). */
const val DEFAULT_CONTEXT_WINDOW = 200_000L

/** The 1M-token context window. */
const val LARGE_CONTEXT_WINDOW = 1_000_000L

/**
 * Claude model ids (substring, lowercased) whose 1M window is their NATIVE standard — no beta header
 * needed — yet the id carries no `[1m]`/`-1m` marker. A pure suffix sniff then under-reports the window
 * and the phone shows 100% far too early (issue #20). Mirrors the community approach (ccstatusline's
 * known-1M table). SINGLE SOURCE OF TRUTH — update as models ship; both the daemon (which stamps
 * [SessionLive.contextWindow]) and the phone fallback read it.
 *
 * Deliberately NOT listed: beta-gated 1M models (canonical `sonnet-4-5`, `sonnet-4-20250514`) — their
 * canonical id defaults to 200k, and listing them would over-claim 5x for non-beta users (the inverse
 * of issue #20). Beta-enabled sessions self-correct via the observed-usage upgrade in the daemon
 * (occupancy beyond 200k proves the 1M window; see Conversation.live / ObserveSession).
 */
private val KNOWN_1M_MODELS = listOf(
    "opus-4-8", "opus-4-7", "opus-4-6",
    "sonnet-5", "sonnet-4-6",
    "fable-5", "mythos-5",
)

/**
 * Bare aliases the phone/daemon can legitimately hold as the "model" before init resolves the real id
 * (`/model opus` pre-first-turn, the picker's optimistic set). Substring matching can't handle these —
 * `"opus"` alone would also match the 200k opus-4-5/4-1 — so they get an exact-match table. Values track
 * what each alias currently resolves to (Opus → 4.8, Sonnet → 5: both natively 1M; Haiku → 200k).
 */
private val ALIAS_WINDOWS = mapOf(
    "opus" to LARGE_CONTEXT_WINDOW,
    "sonnet" to LARGE_CONTEXT_WINDOW,
    "haiku" to DEFAULT_CONTEXT_WINDOW,
)

/**
 * Context-window capacity (tokens) for a Claude model id: exact [ALIAS_WINDOWS] match first, then the
 * `[1m]`/`-1m` marker, then [KNOWN_1M_MODELS] substring; else [DEFAULT_CONTEXT_WINDOW]. Null → default.
 */
fun contextWindowFor(model: String?): Long {
    val m = model?.trim()?.lowercase() ?: return DEFAULT_CONTEXT_WINDOW
    ALIAS_WINDOWS[m]?.let { return it }
    if ("[1m]" in m || "-1m" in m) return LARGE_CONTEXT_WINDOW
    return if (KNOWN_1M_MODELS.any { it in m }) LARGE_CONTEXT_WINDOW else DEFAULT_CONTEXT_WINDOW
}
