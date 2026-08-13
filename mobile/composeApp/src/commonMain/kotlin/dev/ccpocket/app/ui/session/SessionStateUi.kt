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
 * [FAILURE] has no authoritative source in `SessionSummary` yet. [NEW_RESULT] is narrower: it is selected
 * only when this client observed a known-running session become settled while the user was elsewhere. It
 * is never guessed from timestamps, prompt text, model or transcript content.
 */
enum class SurfaceState { APPROVAL, ANSWER, FAILURE, RUNNING, NEW_RESULT, COMPLETE }

/** The non-color half of a state (foundations · "Status marks"): the shape carries it in greyscale too.
 *  Fill IS the ladder among the round marks: filled [DOT] still running, [HALF_DOT] settled but unseen,
 *  hollow [RING] settled and seen. */
enum class StateMark { DIAMOND, SQUARE, DOT, HALF_DOT, RING }

/** The color ROLE. Resolved to a live `Tok` slot at the call site, so both palettes stay reactive. */
enum class StateTone { ATTENTION, DANGER, RUNNING, NEUTRAL }

/** The intent behind a row's action. Only an intervention state offers one — everything else is read-only. */
enum class StateAction { REVIEW, ANSWER }

val SurfaceState.mark: StateMark
    get() = when (this) {
        SurfaceState.APPROVAL, SurfaceState.ANSWER -> StateMark.DIAMOND
        SurfaceState.FAILURE -> StateMark.SQUARE
        SurfaceState.RUNNING -> StateMark.DOT
        // #239: a result you have not opened yet is half-filled, not a second hollow ring. Sharing
        // Complete's ring left the two settled states separable by colour alone, which is exactly the
        // signalling the vocabulary forbids — the written label was carrying it unaided in greyscale.
        SurfaceState.NEW_RESULT -> StateMark.HALF_DOT
        SurfaceState.COMPLETE -> StateMark.RING
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

/** Which half of the list owns this state. Anything that still needs the user's attention stays in Active;
 *  that includes a newly completed result until the session is successfully opened. */
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
 * [failed] remains a degradation seam because `SessionSummary` carries no authoritative failure field.
 * [newResult] may be true only when the repository's working-set tracker observed a running-to-settled
 * transition while this session was not open. Neither state is inferred from `lastModified`, prompt text,
 * model or transcript content — a stale row is a row we know nothing new about, not a failure or result.
 */
fun sessionState(
    summary: SessionSummary,
    attention: SessionAttention?,
    failed: Boolean? = null,
    newResult: Boolean? = null,
    /** null = no directory truth available, so fall back to SessionSummary's compatibility flags. */
    currentlyWorking: Boolean? = null,
): SurfaceState = when {
    attention != null && !attention.isQuestion -> SurfaceState.APPROVAL
    attention != null -> SurfaceState.ANSWER
    failed == true -> SurfaceState.FAILURE
    currentlyWorking == true -> SurfaceState.RUNNING
    newResult == true -> SurfaceState.NEW_RESULT
    currentlyWorking == false -> SurfaceState.COMPLETE
    summary.live || summary.busy -> SurfaceState.RUNNING
    else -> SurfaceState.COMPLETE
}

/** Classify a whole list, preserving the daemon's order. */
fun sessionRows(
    sessions: List<SessionSummary>,
    attention: List<SessionAttention>,
    newResults: Set<String> = emptySet(),
    currentlyWorking: Set<String>? = null,
): List<SessionRowUi> = sessions.map {
    SessionRowUi(
        it,
        sessionState(
            it,
            attentionFor(it, attention),
            newResult = it.sessionId in newResults,
            currentlyWorking = currentlyWorking?.let { work -> it.sessionId in work },
        ),
    )
}

/** Split classified rows into the Active and Recent halves, each keeping its incoming order. */
fun splitSessions(rows: List<SessionRowUi>): SessionSplit =
    SessionSplit(
        active = rows.filter { it.state.pinsToActive },
        recent = rows.filterNot { it.state.pinsToActive },
    )
