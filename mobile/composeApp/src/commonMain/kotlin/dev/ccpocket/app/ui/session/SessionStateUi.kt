package dev.ccpocket.app.ui.session

import dev.ccpocket.protocol.SessionSummary

/**
 * The Sessions/Chat state vocabulary — pure, Compose-free, unit-testable (Mobile UI 2.0 · A Master Core v1).
 *
 * Sessions and Chat must agree on the state of the same session in the same frame (Proofs · implementation
 * review checklist), so the priority ladder lives HERE once instead of being re-derived inside two sets of
 * composable conditionals. The renderers read a [SurfaceState] and draw its mark, tone and label; they never
 * re-decide what the session is doing.
 *
 * The ladder is deliberately wider than what today's protocol can produce — see [sessionState].
 */

/**
 * The one state a session may claim. Declaration order IS the priority order
 * (Proofs · "Approval required › Answer required › Failure › Running › New result › Complete").
 *
 * [FAILURE] and [NEW_RESULT] have no authoritative source in `SessionSummary` yet. They are named, shaped
 * and toned anyway so the day a real field arrives it becomes one argument to [sessionState] instead of a
 * rewrite of every row's chrome. Until then nothing may select them — absence is not evidence.
 */
enum class SurfaceState { APPROVAL, ANSWER, FAILURE, RUNNING, NEW_RESULT, COMPLETE }

/** The non-color half of a state (foundations · "Status marks"): the shape carries it in greyscale too. */
enum class StateMark { DIAMOND, SQUARE, DOT, RING }

/** The color ROLE. Resolved to a live `Tok` slot at the call site, so both palettes stay reactive. */
enum class StateTone { ATTENTION, DANGER, RUNNING, NEUTRAL }

/** The intent behind a row's action. Only an intervention state offers one — everything else is read-only. */
enum class StateAction { REVIEW, ANSWER }

val SurfaceState.mark: StateMark
    get() = when (this) {
        SurfaceState.APPROVAL, SurfaceState.ANSWER -> StateMark.DIAMOND
        SurfaceState.FAILURE -> StateMark.SQUARE
        SurfaceState.RUNNING -> StateMark.DOT
        SurfaceState.NEW_RESULT, SurfaceState.COMPLETE -> StateMark.RING
    }

val SurfaceState.tone: StateTone
    get() = when (this) {
        SurfaceState.APPROVAL, SurfaceState.ANSWER, SurfaceState.NEW_RESULT -> StateTone.ATTENTION
        SurfaceState.FAILURE -> StateTone.DANGER
        SurfaceState.RUNNING -> StateTone.RUNNING
        SurfaceState.COMPLETE -> StateTone.NEUTRAL
    }

/** Only a real pending intervention gets an action — the tap hands the decision to its own owner surface
 *  (Secure Approval / QuestionCard), so a row never becomes a second place a request can be answered. */
val SurfaceState.action: StateAction?
    get() = when (this) {
        SurfaceState.APPROVAL -> StateAction.REVIEW
        SurfaceState.ANSWER -> StateAction.ANSWER
        else -> null
    }

/** Which half of the list owns this state. Anything that is not finished is work in progress, so a future
 *  [SurfaceState.FAILURE] / [SurfaceState.NEW_RESULT] lands in Active without another partition rule. */
val SurfaceState.pinsToActive: Boolean get() = this != SurfaceState.COMPLETE

/**
 * One pending intervention, already resolved to real facts by the caller: a `fleetAttention()` row's real
 * `sessionId`/`workdir` plus its real ask's `isQuestion`.
 *
 * A plain value rather than the fleet row itself, so this mapper stays free of the fleet/Compose layer and
 * a test can state the exact truth it wants to exercise.
 */
data class SessionAttention(val sessionId: String?, val workdir: String?, val isQuestion: Boolean)

/** A session plus the single state it is allowed to claim. */
data class SessionRowUi(val session: SessionSummary, val state: SurfaceState) {
    val mark: StateMark get() = state.mark
    val tone: StateTone get() = state.tone
    val action: StateAction? get() = state.action
}

/** The Active/Recent partition. Order inside each half is the daemon's own (never re-sorted by state). */
data class SessionSplit(val active: List<SessionRowUi>, val recent: List<SessionRowUi>)

/**
 * The intervention waiting on [summary], if any.
 *
 * Requires BOTH identifiers to be present and to match: a row that names no session, or names one in another
 * project, is not evidence that THIS session is blocked. Under-reporting is harmless (opening the session
 * still surfaces its own modal); a false "Approval required" on the wrong row is not.
 *
 * A session with both kinds pending resolves to the approval — the security gate outranks the question.
 */
fun attentionFor(summary: SessionSummary, attention: List<SessionAttention>): SessionAttention? {
    val mine = attention.filter {
        !it.sessionId.isNullOrBlank() && it.sessionId == summary.sessionId &&
            !it.workdir.isNullOrBlank() && it.workdir == summary.cwd
    }
    return mine.firstOrNull { !it.isQuestion } ?: mine.firstOrNull()
}

/**
 * Select the ONE state [summary] may claim.
 *
 * [failed] and [newResult] are the degradation seam: `SessionSummary` carries no authoritative failure or
 * new-result field, so every caller leaves them null and neither state is reachable today. They are NOT
 * inferred from `lastModified`, prompt text, model or transcript content — a stale row is a row we know
 * nothing new about, not a failure, and "the daemon touched this file" is not a result the user asked for.
 */
fun sessionState(
    summary: SessionSummary,
    attention: SessionAttention?,
    failed: Boolean? = null,
    newResult: Boolean? = null,
): SurfaceState = when {
    attention != null && !attention.isQuestion -> SurfaceState.APPROVAL
    attention != null -> SurfaceState.ANSWER
    failed == true -> SurfaceState.FAILURE
    summary.live || summary.busy -> SurfaceState.RUNNING
    newResult == true -> SurfaceState.NEW_RESULT
    else -> SurfaceState.COMPLETE
}

/** Classify a whole list, preserving the daemon's order. */
fun sessionRows(sessions: List<SessionSummary>, attention: List<SessionAttention>): List<SessionRowUi> =
    sessions.map { SessionRowUi(it, sessionState(it, attentionFor(it, attention))) }

/** Split classified rows into the Active and Recent halves, each keeping its incoming order. */
fun splitSessions(rows: List<SessionRowUi>): SessionSplit =
    SessionSplit(
        active = rows.filter { it.state.pinsToActive },
        recent = rows.filterNot { it.state.pinsToActive },
    )
