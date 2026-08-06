package dev.ccpocket.protocol

/**
 * Shared error-attribution for the CLI's `<synthetic>` API-failure placeholder (issue #65 / #208).
 *
 * When every API call of a turn fails, the CLI writes a `<synthetic>` placeholder reply. Historically
 * the daemon live prompt AND the app's history replay both blamed the same cause — "the session has
 * outgrown its context window, /clear" — but a third-party gateway 502 (custom ANTHROPIC_BASE_URL /
 * local inference gateway) produces an identical placeholder while context is at 2%. Attribution must
 * follow the evidence: if the placeholder text itself carries a clear upstream-failure signal, say so
 * and stop pointing the user at /clear.
 *
 * This helper is pure text classification with no wire type, kept in `:protocol` so the daemon's live
 * TurnDone error and the app's replayed placeholder row word the attribution identically (issue #208).
 */
object SyntheticAttribution {
    // Signals inside the placeholder that point at an upstream API / gateway failure rather than a
    // blown context window: HTTP 5xx, bad-gateway/overloaded wording, connection errors, and the
    // "inference gateway" phrasing custom endpoints print.
    private val API_FAILURE = Regex(
        "api error|status(?: code)? 5\\d\\d|\\b5\\d\\d\\b|bad gateway|\\bgateway\\b|upstream|" +
            "service unavailable|internal server error|overloaded|connection error|inference",
        RegexOption.IGNORE_CASE,
    )

    /** True when the placeholder text carries an explicit upstream API / gateway failure signal. */
    fun looksLikeApiFailure(placeholder: String?): Boolean =
        !placeholder.isNullOrBlank() && API_FAILURE.containsMatchIn(placeholder)

    /**
     * The attribution clause that follows "the agent wrote a placeholder, not a real reply." — a
     * gateway-failure branch that never mentions context, and a context-window fallback that now also
     * flags the API-link possibility when occupancy is low. Both the daemon live prompt and the app
     * replay row append this, so the two read identically.
     */
    fun attribution(placeholder: String?): String =
        if (looksLikeApiFailure(placeholder)) {
            "The API/gateway reported an upstream failure — this is a network or gateway problem, " +
                "not your context. Wait and retry; if you point the CLI at a custom endpoint or " +
                "inference gateway, check that it's healthy."
        } else {
            "If this keeps happening the session has likely outgrown its context window: start a new " +
                "session or send /clear. But if your context usage is still low, it's most likely an " +
                "API/gateway failure rather than context — wait and retry."
        }
}
