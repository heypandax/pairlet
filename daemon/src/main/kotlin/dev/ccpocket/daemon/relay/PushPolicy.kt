package dev.ccpocket.daemon.relay

import dev.ccpocket.protocol.NotifyPush
import java.nio.file.Path

/**
 * Pure push copy + gating for the relay client's notify hooks (issue #138) — extracted so the
 * decisions are unit-testable without a websocket. Three flavors of turn push (complete / error /
 * usage-limit) and the permission-ask push gate (bridge #91 + owner sessions #138) live here; the
 * relay client only supplies presence flags and puts the returned frame on its control outbox.
 */
object PushPolicy {

    // ---- usage-limit detection (issue #138) ----
    // There is NO captured limit-hit sample in this repo (nor in local transcripts — grepped 07-14),
    // so the matcher is PATTERN-BASED on the Claude CLI's known usage-limit wordings. Sources:
    //  - `claude -p` returns the result text `Claude AI usage limit reached|<unix-epoch>` when the
    //    subscription window is exhausted (long-standing wording, widely reported on
    //    github.com/anthropics/claude-code issues);
    //  - newer CLIs word the interactive banner "5-hour limit reached ∙ resets 3am" /
    //    "Weekly limit reached" — the same strings can ride an error result's text;
    //  - a raw API 429 surfaces as `rate_limit_error` (the API error type literal) or prose
    //    "Rate limit reached";
    //  - extra-usage balance exhaustion reads "out of extra usage".
    // Keep the patterns NARROW: they run against the CLI's own turn-error text (never tool output),
    // but an ordinary error that merely mentions "limit" (context/size/frame limits) must not match.
    // If the CLI's wording drifts, scripts/probe-claude-wire.py is the place to re-probe.
    private val USAGE_LIMIT_PATTERNS = listOf(
        Regex("(?i)usage limit"), // "Claude AI usage limit reached|<ts>", "You've hit your usage limit" (Codex)
        Regex("(?i)rate[_ -]?limit"), // API 429: rate_limit_error / "Rate limit reached"
        Regex("(?i)(5-hour|weekly|session) limit reached"), // interactive-banner wordings
        Regex("(?i)out of extra usage"),
    )

    /** True when a turn-error text reads as a usage/rate limit rather than an ordinary failure. */
    fun isUsageLimit(error: String?): Boolean =
        error != null && USAGE_LIMIT_PATTERNS.any { it.containsMatchIn(error) }

    // "Claude AI usage limit reached|1751990400" — the CLI appends the window's reset moment as a
    // pipe-separated unix epoch (seconds). 10–11 digits = seconds (through year ~5138); 12–13 = a
    // peer that already sends millis. Anchored to '|' so an ordinary number in prose never matches.
    private val RESET_EPOCH = Regex("""\|(\d{10,13})\b""")

    /**
     * The usage-limit window's reset moment as EPOCH MILLIS, parsed from a turn-error text — what
     * [dev.ccpocket.protocol.TurnDone.usageLimitResetAt] carries so the client can offer "auto-continue
     * when the limit resets" (issue #137). Null when [error] isn't a usage-limit hit, or the CLI's
     * wording carries no parseable epoch (newer banner wordings like "resets 3am" don't) — the client
     * simply shows no button then.
     */
    fun usageLimitResetAtMs(error: String?): Long? {
        if (!isUsageLimit(error)) return null
        val raw = RESET_EPOCH.find(error!!)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (raw < 1_000_000_000_000L) raw * 1000 else raw
    }

    /**
     * The push for a finished turn. [error] non-null = the turn ended abnormally (error result,
     * synthetic placeholder, or the agent process dying — see [dev.ccpocket.daemon.conversation.PushHook]):
     * worded distinctly from a normal turn-complete, with the usage-limit case called out by name so a
     * locked phone knows the session can't proceed until the window resets (issue #138). The caller
     * gates on presence (peer offline + LAN empty + pushEnabled) exactly as before.
     */
    fun turnPush(workdir: Path, sessionId: String?, finalText: String?, error: String?): NotifyPush {
        val project = workdir.fileName?.toString() ?: "CC Pocket"
        return when {
            isUsageLimit(error) -> NotifyPush(
                title = "Usage limit hit — $project",
                body = ("Turn couldn't finish: " + firstLine(error!!)).take(BODY_MAX),
                workdir = workdir.toString(),
                sessionId = sessionId,
            )
            error != null -> NotifyPush(
                title = "Session error — $project",
                body = ("Turn stopped: " + firstLine(error)).take(BODY_MAX),
                workdir = workdir.toString(),
                sessionId = sessionId,
            )
            else -> NotifyPush(
                title = project,
                body = finalLineOf(finalText) ?: "Turn complete",
                workdir = workdir.toString(),
                sessionId = sessionId,
            )
        }
    }

    /**
     * The push for a pending permission ask, or null = don't push (a live client already has the card).
     *
     *  - [origin] non-null (bridge, issue #91): ALWAYS pushed, urgent — the bridge can neither see nor
     *    answer the ask, and the owner's phone being online elsewhere doesn't put the ask on its screen.
     *  - owner session, [watched] false (issue #138): nobody is attached to the conversation, so the ask
     *    frame reached NO client — urgent, because the relay's "interactive socket live" suppression
     *    would wrongly swallow it when the phone is online in a DIFFERENT session.
     *  - owner session, watched but the phone is gone everywhere ([peerOnline]/[lanConnected] both
     *    false — locked phone with a stale sink): pushed non-urgent, so the relay's own interactive-
     *    socket check stays as the second gate (our presence flags can lag a reconnect).
     *  - otherwise: an attached, present client received the ask on the data plane — no push.
     */
    fun askPush(
        workdir: Path,
        sessionId: String?,
        origin: String?,
        tool: String,
        watched: Boolean,
        peerOnline: Boolean,
        lanConnected: Boolean,
    ): NotifyPush? {
        val project = workdir.fileName?.toString() ?: "session"
        return when {
            origin != null -> NotifyPush(
                title = "Approval needed — $origin",
                body = "$project: $tool is waiting for your decision",
                workdir = workdir.toString(),
                sessionId = sessionId,
                urgent = true, // deliver even if a phone is attached elsewhere — the ask isn't on its data plane
            )
            !watched -> NotifyPush(
                title = "Approval needed — $project",
                body = "$tool is waiting for your decision",
                workdir = workdir.toString(),
                sessionId = sessionId,
                urgent = true, // no client holds the card; an online-but-elsewhere phone must still hear it
            )
            !peerOnline && !lanConnected -> NotifyPush(
                title = "Approval needed — $project",
                body = "$tool is waiting for your decision",
                workdir = workdir.toString(),
                sessionId = sessionId,
                urgent = false, // relay re-checks interactive sockets — belt and suspenders on stale presence
            )
            else -> null
        }
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: text.trim()

    private fun finalLineOf(finalText: String?): String? =
        finalText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(BODY_MAX)

    // same lock-screen budget the turn-complete push always used
    private const val BODY_MAX = 140
}
