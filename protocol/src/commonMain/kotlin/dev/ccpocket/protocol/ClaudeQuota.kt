package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ===========================================================================
//  Claude subscription quota — the numbers behind the CLI's own `/usage` panel
//  (5h session window + 7d windows), read by the daemon from
//  `GET https://api.anthropic.com/api/oauth/usage` with the machine's OAuth
//  access token.
//
//  This is a DIFFERENT thing from [Usage] / [FetchUsage] (issue #26), which
//  aggregates TOKENS the daemon counted itself out of local transcripts. That
//  one answers "how much did I burn"; this one answers "how much of the
//  subscription allowance is left, and when does it reset" — a number only
//  Anthropic knows, and one no transcript can be made to reveal.
//
//  Wire red lines (same family as Git.kt/Handoff.kt):
//   - [ClaudeQuota.status] and [ClaudeQuotaLimit.kind]/[group]/[severity] are
//     TOLERANT Strings, never enums. `coerceInputValues` rewrites an UNKNOWN
//     enum value to the field's DEFAULT, and the default of a status field is
//     "ok" — i.e. a future daemon's new failure mode would decode on an already
//     shipped app as a SUCCESS with zero limits. A String degrades honestly:
//     the app renders what it recognizes and hides what it doesn't. The upstream
//     `limits[]` vocabulary is Anthropic's, not ours, and it demonstrably grows
//     (weekly_scoped rows appeared with per-model scoping), so pinning it into a
//     Kotlin enum would break on THEIR schedule.
//   - every field has a default, so a field added later is a trailing optional
//     and an older peer drops the unknown key (ignoreUnknownKeys) instead of
//     failing the whole Envelope.
//   - [ClaudeQuotaLimit.resetsAt] keeps its `null` default and is epoch MILLIS,
//     not the upstream ISO string: the daemon owns the parse (it has the whole
//     JVM time library and the upstream's 6-digit fractional offsets), and a
//     client that cannot parse a moment must show NO countdown rather than a
//     wrong one. Absent (explicitNulls=false) == "unknown", never "now".
//   - OWNER-ONLY on the daemon side: [ClaudeQuotaGet] appears in no
//     GuestCaps/BridgeCaps/CollaboratorCaps allow-list (all three default-deny)
//     and RequestRouter re-checks the three credential classes at dispatch.
//     This is account-wide billing state for the machine's OWNER — strictly
//     wider than anything a scoped share covers.
//   - both types are NEW: an old daemon drops [ClaudeQuotaGet] (unknown "t") and
//     simply never answers, so the client arms a deadline and hides the block.
// ===========================================================================

/** [ClaudeQuota.status]: the read succeeded and [ClaudeQuota.limits] is the live picture. */
const val CLAUDE_QUOTA_OK = "ok"

/** [ClaudeQuota.status]: this machine has no usable OAuth credential — never signed in, signed out, the
 *  stored token is past its `expiresAt`, or the account authenticates with a plain API key (which has no
 *  subscription allowance to report at all). NOT an error to show: the client HIDES the quota block. */
const val CLAUDE_QUOTA_NO_TOKEN = "no_token"

/** [ClaudeQuota.status]: the request never got an answer (offline, DNS, TLS, timeout). Transient. */
const val CLAUDE_QUOTA_NETWORK = "network"

/** [ClaudeQuota.status]: the endpoint answered with a non-2xx (401 after a revoked token, 5xx, …).
 *  [ClaudeQuota.error] carries the status code; the body is never forwarded. */
const val CLAUDE_QUOTA_HTTP = "http_error"

/** [ClaudeQuotaLimit.severity] values seen upstream. Tolerant: an unrecognized one renders as normal. */
const val CLAUDE_QUOTA_SEVERITY_NORMAL = "normal"
const val CLAUDE_QUOTA_SEVERITY_WARNING = "warning"

/** [ClaudeQuotaLimit.kind] values seen upstream (Anthropic's vocabulary — may grow). */
const val CLAUDE_QUOTA_KIND_SESSION = "session"      // the rolling 5-hour window
const val CLAUDE_QUOTA_KIND_WEEKLY_ALL = "weekly_all"   // the 7-day all-model window
const val CLAUDE_QUOTA_KIND_WEEKLY_SCOPED = "weekly_scoped" // a 7-day window scoped to one model

/**
 * phone/desktop -> daemon: read the machine's Claude subscription allowance. Owner only.
 *
 * [forceRefresh] bypasses the daemon's short result cache (a pull-to-refresh); the default rides the
 * cache so opening the usage page repeatedly never fans out to Anthropic once per render.
 */
@Serializable
@SerialName("pocket/claude.quota.get")
data class ClaudeQuotaGet(val forceRefresh: Boolean = false) : ToDaemon

/**
 * daemon -> phone/desktop: the answer to one [ClaudeQuotaGet].
 *
 * [limits] is the source of truth for rendering — one row per window, INCLUDING the per-model
 * `weekly_scoped` rows, in upstream order. It is empty for every non-[CLAUDE_QUOTA_OK] status.
 * [fetchedAt] is when the daemon actually talked to Anthropic (epoch ms, 0 = unknown) — a cached reply
 * carries the ORIGINAL fetch moment, so the client can age the numbers honestly.
 * [status] is one of the `CLAUDE_QUOTA_*` constants; [error] is a short human-readable reason for the
 * failure statuses and null on success. Neither ever carries the access token or a response body.
 */
@Serializable
@SerialName("pocket/claude.quota")
data class ClaudeQuota(
    val limits: List<ClaudeQuotaLimit> = emptyList(),
    val fetchedAt: Long = 0,
    val status: String = CLAUDE_QUOTA_OK,
    val error: String? = null,
) : ToPhone

/**
 * One allowance window as Anthropic reports it.
 *
 * [kind] / [group] are the upstream row identity (`session` / `weekly_all` / `weekly_scoped`, grouped as
 * `session` / `weekly`). [percent] is the percentage CONSUMED (0-100), so the remaining allowance is
 * `100 - percent`. [severity] is Anthropic's own escalation ([CLAUDE_QUOTA_SEVERITY_WARNING] near the
 * cap); the client also treats a high [percent] as a warning so a daemon reading a severity-less future
 * payload still colors correctly. [resetsAt] is the epoch-ms moment the window rolls over (null =
 * unparseable/absent → no countdown). [isActive] marks the window upstream considers the binding one
 * right now. [modelDisplayName] is the human label from `scope.model.display_name` for a `weekly_scoped`
 * row (e.g. "Fable"); null for the unscoped windows and for a scope shape we do not recognize.
 */
@Serializable
data class ClaudeQuotaLimit(
    val kind: String = "",
    val group: String = "",
    val percent: Int = 0,
    val severity: String = CLAUDE_QUOTA_SEVERITY_NORMAL,
    val resetsAt: Long? = null,
    val isActive: Boolean = false,
    val modelDisplayName: String? = null,
)
