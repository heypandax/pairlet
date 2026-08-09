package dev.ccpocket.app.ui.approval

import dev.ccpocket.app.ui.isShellTool
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionRiskUpdated
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.oneOff

/**
 * The Secure Approval presentation model — pure, Compose-free, unit-testable.
 *
 * Everything the sheet is allowed to show is decided HERE, from authoritative fields only. The renderer
 * draws what this says and nothing else: no client-authored summary, no placeholder for data that never
 * arrived, no action the daemon did not offer (Mobile UI 2.0 · Approval Protocol Handoff v1).
 */

/** Which decision set a request can honor. Modifiers (danger, noAutoDeny, queue, risk) never change it. */
enum class ApprovalFamily {
    /** A human decision that must not become a standing rule: `ask.oneOff`, or a shell command under a
     *  REVIEW handoff (the one way a "read-only" review can still write). Deny + Allow once, nothing else. */
    ONE_OFF,

    /** A grant-aware daemon (`grantOptions != null`, INCLUDING an empty list): Deny + Allow once + Retry
     *  safer, plus exactly the scopes it offered. */
    V2,

    /** A pre-M2 daemon (`grantOptions == null`): Deny + Allow once + Always allow. */
    LEGACY,
}

enum class ApprovalActionId { DENY, ALLOW_ONCE, RETRY_SAFER, ALLOW_TASK, ALLOW_SESSION, ALWAYS_ALLOW }

/**
 * How loudly a tile is drawn. Capability is NOT recommendation (design brief §"Accepted implementation
 * deltas" 1): an ordinary request leaves every tile [NEUTRAL] — a filled "Allow for task" would read as an
 * endorsement the product never made. Only a daemon-flagged `danger` moves emphasis, and it only moves it:
 * the action SET is identical either way.
 */
enum class ActionEmphasis {
    NEUTRAL,

    /** The least-privilege path on a flagged danger — filled accent (Allow once). */
    PRIMARY,

    /** A danger boundary on Deny. Deliberately not a fill: two filled tiles would read as two endorsements. */
    BOUNDARY,

    /** A standing grant demoted under a flagged danger — outline + secondary ink. */
    CAUTION,
}

/** One decision tile. [sublabel] is the daemon's `rule`, never a synthesized scope description. */
data class ApprovalAction(
    val id: ApprovalActionId,
    val emphasis: ActionEmphasis = ActionEmphasis.NEUTRAL,
    val sublabel: String? = null,
)

/** The header's waiting treatment. A [Countdown] is a display floor, never a verdict. */
sealed interface ApprovalTimer {
    /** [totalSec] is the daemon's real window, or the legacy fallback when it sent none. */
    data class Countdown(val totalSec: Int) : ApprovalTimer

    /** `noAutoDeny`: the daemon renews instead of expiring. No ring, no number, no `∞` — just the truth. */
    data object Waiting : ApprovalTimer
}

/** The legacy client-side window for a daemon that predates `timeoutSec` (issue #100). */
const val LEGACY_TIMEOUT_SEC = 30

data class ApprovalUi(
    val ask: PermissionAsk,
    /** The session's real working directory. Null → the Project row is omitted, never substituted. */
    val workdir: String?,
    /** The FULL risk event, not just its level — reason/codes/assessed time are evidence, not decoration. */
    val risk: PermissionRiskUpdated?,
    /** 1-based position and burst total, already filtered to the meaningful case (total > 1). */
    val queue: Pair<Int, Int>?,
    val family: ApprovalFamily,
    /** Tiles in render order. [ApprovalActionId.ALLOW_SESSION] is never here — see [sessionAction]. */
    val actions: List<ApprovalAction>,
    /** The session grant, when offered: one deliberate tap behind More options, never on the main grid. */
    val sessionAction: ApprovalAction?,
    /** A shell command inside a REVIEW handoff — the body carries the "recorded, never remembered" band. */
    val recordedShell: Boolean,
    val timer: ApprovalTimer,
    /** The daemon's authoritative `AskWithdrawn(TIMED_OUT)`. The only remote path into the terminal state. */
    val timedOutSignal: Boolean,
) {
    /**
     * Whether the sheet shows its read-only terminal state.
     *
     * [secondsLeft] reaching zero is terminal ONLY for a pre-M2 daemon that also auto-denies: a grant-aware
     * daemon can pause its budget under an AttentionLease (§18.2 P2-1) and a `noAutoDeny` ask is renewed
     * rather than expired (#201), so for those the local zero is a display floor and the daemon's signal is
     * the only terminal. Either way the client never SENDS a verdict of its own — it only stops offering one.
     */
    fun isTerminal(secondsLeft: Int): Boolean =
        timedOutSignal || (secondsLeft <= 0 && ask.grantOptions == null && !ask.noAutoDeny)
}

/**
 * Classify one pending ask into everything the sheet may render.
 *
 * [handoffReview] is "a REVIEW handoff is in progress on this device"; combined with a shell tool it forces
 * [ApprovalFamily.ONE_OFF] regardless of what the daemon offered — the client half of "confirmed one command
 * at a time" (implementation review §2.2/§4.3).
 */
fun approvalUi(
    ask: PermissionAsk,
    workdir: String? = null,
    risk: PermissionRiskUpdated? = null,
    queueProgress: Pair<Int, Int>? = null,
    handoffReview: Boolean = false,
    timedOutSignal: Boolean = false,
): ApprovalUi {
    require(!ask.isQuestion) { "AskUserQuestion belongs in the conversation card, not Secure Approval" }
    val recordedShell = handoffReview && isShellTool(ask.tool)
    val family = when {
        ask.oneOff || recordedShell -> ApprovalFamily.ONE_OFF
        // non-null is the capability signal — an EMPTY list is still a grant-aware daemon that happens to
        // offer no standing scope, so it must not fall back to legacy and hand out "Always allow"
        ask.grantOptions != null -> ApprovalFamily.V2
        else -> ApprovalFamily.LEGACY
    }
    val danger = ask.danger
    val deny = ApprovalAction(ApprovalActionId.DENY, if (danger) ActionEmphasis.BOUNDARY else ActionEmphasis.NEUTRAL)
    val once = ApprovalAction(ApprovalActionId.ALLOW_ONCE, if (danger) ActionEmphasis.PRIMARY else ActionEmphasis.NEUTRAL)
    val standing = if (danger) ActionEmphasis.CAUTION else ActionEmphasis.NEUTRAL
    val scopes = ask.grantOptions.orEmpty()

    val actions = when (family) {
        ApprovalFamily.ONE_OFF -> listOf(deny, once)
        ApprovalFamily.V2 -> buildList {
            add(deny)
            add(once)
            add(ApprovalAction(ApprovalActionId.RETRY_SAFER))
            // unknown future scope strings are ignored; no cell is reserved for one that wasn't offered
            if ("task" in scopes) add(ApprovalAction(ApprovalActionId.ALLOW_TASK, standing, ask.rule))
        }
        ApprovalFamily.LEGACY -> listOf(deny, once, ApprovalAction(ApprovalActionId.ALWAYS_ALLOW, standing, ask.rule))
    }
    return ApprovalUi(
        ask = ask,
        workdir = workdir?.takeIf { it.isNotBlank() },
        // Keep the pure boundary defensive as well as the repository lookup: stale late-arriving evidence
        // from a different ask must never be presented as proof for this request.
        risk = risk?.takeIf { it.convoId == ask.convoId && it.askId == ask.askId },
        queue = queueProgress?.takeIf { (pos, total) -> total > 1 && pos in 1..total },
        family = family,
        actions = actions,
        sessionAction = if (family == ApprovalFamily.V2 && "session" in scopes) {
            ApprovalAction(ApprovalActionId.ALLOW_SESSION, sublabel = ask.rule)
        } else {
            null
        },
        recordedShell = recordedShell,
        timer = if (ask.noAutoDeny) ApprovalTimer.Waiting else {
            ApprovalTimer.Countdown((ask.timeoutSec ?: LEGACY_TIMEOUT_SEC).coerceAtLeast(0))
        },
        timedOutSignal = timedOutSignal,
    )
}
