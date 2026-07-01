package dev.ccpocket.protocol

/** Standard Claude context window (tokens). */
const val DEFAULT_CONTEXT_WINDOW = 200_000L

/** The 1M-token context window. */
const val LARGE_CONTEXT_WINDOW = 1_000_000L

/**
 * Claude model ids (substring, lowercased) that run the 1M context window even when the id Claude Code
 * reports does NOT carry a `[1m]`/`-1m` marker — because 1M can be turned on via a beta header / env /
 * `/model` variant while `system/init` still reports the canonical model name. A pure suffix sniff then
 * under-reports the window and the phone shows 100% far too early (issue #20). This mirrors the community
 * approach (ccstatusline's known-1M-model table). SINGLE SOURCE OF TRUTH — update this list here as models
 * ship; both the daemon (which stamps [SessionLive.contextWindow]) and the phone fallback read it.
 */
private val KNOWN_1M_MODELS = listOf(
    "opus-4-8", "opus-4-7", "opus-4-6",
    "sonnet-5", "sonnet-4-6", "sonnet-4-5",
    "fable-5",
)

/**
 * Context-window capacity (tokens) for a Claude model id: [LARGE_CONTEXT_WINDOW] when the id carries a
 * `[1m]`/`-1m` marker or matches a [KNOWN_1M_MODELS] entry, else [DEFAULT_CONTEXT_WINDOW]. Null model → default.
 */
fun contextWindowFor(model: String?): Long {
    val m = model?.lowercase() ?: return DEFAULT_CONTEXT_WINDOW
    if ("[1m]" in m || "-1m" in m) return LARGE_CONTEXT_WINDOW
    return if (KNOWN_1M_MODELS.any { it in m }) LARGE_CONTEXT_WINDOW else DEFAULT_CONTEXT_WINDOW
}
