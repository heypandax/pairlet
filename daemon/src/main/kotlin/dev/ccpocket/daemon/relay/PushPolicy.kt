package dev.ccpocket.daemon.relay

import dev.ccpocket.protocol.NotifyPush
import dev.ccpocket.protocol.PROTO_V_TARGETED_PUSH
import dev.ccpocket.protocol.ToRelay
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
     * locked phone knows the session can't proceed until the window resets (issue #138). Copy only — the
     * relay client goes through [turnPushFor] (switch gate + urgent) and [TurnPushCoalescer] (issue #382).
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

    /** Which flavor of turn end [error] describes — the coalescing severity and the log's `kind=`. */
    fun turnKindOf(error: String?): TurnKind = when {
        isUsageLimit(error) -> TurnKind.LIMIT
        error != null -> TurnKind.ERROR
        else -> TurnKind.COMPLETE
    }

    /**
     * The turn-end push the relay client actually sends (issue #382), or null when the desktop's
     * "notify my phone when a reply finishes" switch ([pushEnabled], daemon `prefs.pushEnabled`) is off.
     *
     * That switch is now the ONLY gate. Presence — the phone attached over the relay, the desktop App (or
     * any client) attached over LAN, other interactive devices on the account — deliberately no longer
     * suppresses a complete / error / usage-limit push: the desktop being online says nothing about whether
     * the user is looking at it. A phone that is in the foreground on this very session hides the banner
     * itself (app-side, #382).
     *
     * `urgent = true` here means ONLY "skip the relay's interactive-device check" ([NotifyPush.urgent] /
     * relay `NotifyGate.shouldSend`). It does not change APNs/FCM priority, sound or channel; [NotifyPush.kind]
     * stays null so Android keeps routing it to the `task_complete` channel, not `approvals`.
     */
    fun turnPushFor(pushEnabled: Boolean, workdir: Path, sessionId: String?, finalText: String?, error: String?): NotifyPush? =
        if (!pushEnabled) null else turnPush(workdir, sessionId, finalText, error).copy(urgent = true)

    /**
     * The push for a pending permission ask — copy only; [askPushFor] applies the gate. Two flavors:
     *
     *  - [origin] non-null (bridge, issue #91): the bridge can neither see nor answer the ask, so the title
     *    names the bridge and the body names the project.
     *  - owner session ([origin] null, issue #138): the title names the project.
     *
     * Both ride `urgent = true` and `kind = "approval"`. Urgent means only "skip the relay's interactive-device
     * check" (relay `NotifyGate.shouldSend`): the owner's desktop App is an interactive device attached around
     * the clock, so that check would otherwise mute every owner ask. Presence is no longer an input (2026-10,
     * issue #382 applied to asks): a client attached to the conversation, a relay peer online, a LAN client
     * connected — none of it says the user is LOOKING, and on a machine with the desktop App open the old
     * presence gate never let a single owner ask through. A phone showing this very session in the foreground
     * hides the banner itself (app-side `shouldPresentForegroundPush`); everyone else gets the alert.
     */
    fun askPush(workdir: Path, sessionId: String?, origin: String?, tool: String): NotifyPush {
        val project = workdir.fileName?.toString() ?: "session"
        return if (origin != null) NotifyPush(
            title = "Approval needed — $origin",
            body = "$project: $tool is waiting for your decision",
            workdir = workdir.toString(),
            sessionId = sessionId,
            urgent = true,
            kind = "approval", // P2-4: dedicated notification category
        ) else NotifyPush(
            title = "Approval needed — $project",
            body = "$tool is waiting for your decision",
            workdir = workdir.toString(),
            sessionId = sessionId,
            urgent = true,
            kind = "approval",
        )
    }

    /**
     * The ask push the relay client actually sends, or null when the desktop's "notify my phone" switch
     * ([pushEnabled], daemon `prefs.pushEnabled`) is off — the ONLY gate, bridge and owner asks alike, exactly
     * as [turnPushFor] gates turn ends. Bursts are bounded by the conversation's per-conversation coalesce
     * window (`Conversation.askPushCoalesceMs`), with the relay's per-account ceiling as the backstop.
     */
    fun askPushFor(pushEnabled: Boolean, workdir: Path, sessionId: String?, origin: String?, tool: String): NotifyPush? =
        if (!pushEnabled) null else askPush(workdir, sessionId, origin, tool)

    /**
     * May [frame] be WRITTEN to a relay socket whose announced capability is [relayProtoV]
     * ([dev.ccpocket.protocol.Attached.relayProtoV], 0 from a relay that predates the field)?
     *
     * A device-TARGETED push ([NotifyPush.deviceId]) is unsafe on a relay below [PROTO_V_TARGETED_PUSH]: an
     * older relay does not reject the unknown field — it ignores it and falls back to the ACCOUNT fan-out,
     * ringing the OWNER's own phones. Nothing in this daemon produces one any more (the session-handoff
     * offer push was the only producer, retired 2026-10); the check stays as the guard at the one place a
     * control frame reaches the wire, since the control outbox buffers across reconnects and the next
     * link's writer may face a different relay build.
     *
     * Returns false only for the frames that are unsafe on this link; everything else passes through.
     */
    fun mayWrite(frame: ToRelay, relayProtoV: Int): Boolean =
        !(frame is NotifyPush && frame.deviceId != null && relayProtoV < PROTO_V_TARGETED_PUSH)

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: text.trim()

    private fun finalLineOf(finalText: String?): String? =
        finalText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(BODY_MAX)

    // same lock-screen budget the turn-complete push always used
    private const val BODY_MAX = 140
}
