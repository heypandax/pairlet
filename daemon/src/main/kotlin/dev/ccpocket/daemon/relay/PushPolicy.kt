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

    /**
     * The offer push for an OFFLINE Collaborator Link recipient
     * (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4) — deliberately the only push in this file with NO
     * variable content at all.
     *
     * Everything the other pushes carry is cleartext to the relay and to a lock screen someone else may be
     * looking over: project name, workdir, the assistant's first line, the tool being asked about. An offer
     * lives on the far side of a trust boundary the owner just opened for ONE person, so nothing about it —
     * brief, project, path, session title, initiator label — may ride the alert. The push is a doorbell; the
     * app pulls the real, end-to-end-encrypted offer with `ListHandoffs()` once it is awake, over the
     * collaborator's own credential, which is what proves it may see it at all.
     *
     * [handoffId] is opaque and routable: it deep-links into the offer inbox and is useless to anyone who
     * cannot authenticate as this recipient. It rides [NotifyPush.handoffId] (relay → APNs/FCM `hid`), never
     * workdir/sessionId. [recipientDeviceId] makes the delivery TARGETED so the owner's own phones — the
     * account fan-out — are not woken by someone else's offer.
     */
    fun offerPush(handoffId: String, recipientDeviceId: String): NotifyPush = NotifyPush(
        title = OFFER_TITLE,
        body = OFFER_BODY,
        workdir = null,
        sessionId = null,
        urgent = false, // the relay gates on the RECIPIENT's own socket; urgency never enters into it
        deviceId = recipientDeviceId,
        handoffId = handoffId,
    )

    /** Fixed offer-alert copy. Constants (not inline literals) so the "no identifying content" test can
     *  assert against the same strings the wire carries. */
    const val OFFER_TITLE = "cc-pocket"
    const val OFFER_BODY = "You have a new handoff offer. Open the app to see the details."

    /**
     * May an [offerPush] actually be put on the wire, given the relay's announced capability
     * ([dev.ccpocket.protocol.Attached.relayProtoV], 0 from a relay that predates the field)?
     *
     * This gate FAILS CLOSED for a reason that is worse than "the feature is missing": an older relay does
     * not reject the unknown `deviceId` — it ignores it and falls back to the ACCOUNT fan-out, i.e. it rings
     * the OWNER's own phone about a colleague's offer. Not sending is the honest degradation; the recipient
     * still finds the offer on its next connect / foreground pull.
     */
    fun offerPushAllowed(relayProtoV: Int): Boolean = relayProtoV >= PROTO_V_TARGETED_PUSH

    /**
     * The same gate applied at the moment a control frame is actually WRITTEN to a relay socket — the only
     * place it is sound.
     *
     * The daemon's control outbox outlives any single connection (it deliberately buffers across
     * reconnects), so a frame that passed [offerPushAllowed] against one link can be flushed by the NEXT
     * link's writer. That successor is not guaranteed to be the same relay build: an in-place redeploy or a
     * rollback lands mid-reconnect, and the frames most likely to still be sitting in the buffer are exactly
     * the ones queued in the seconds before the disconnect (a stalled write is what declares the link dead).
     * A targeted push flushed to an older relay is not merely dropped — it degrades to the ACCOUNT fan-out
     * and rings the OWNER's phones about a contact's offer.
     *
     * Returns false only for the frames that are unsafe on this link; everything else passes through.
     */
    fun mayWrite(frame: ToRelay, relayProtoV: Int): Boolean =
        !(frame is NotifyPush && frame.deviceId != null && !offerPushAllowed(relayProtoV))

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: text.trim()

    private fun finalLineOf(finalText: String?): String? =
        finalText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(BODY_MAX)

    // same lock-screen budget the turn-complete push always used
    private const val BODY_MAX = 140
}
