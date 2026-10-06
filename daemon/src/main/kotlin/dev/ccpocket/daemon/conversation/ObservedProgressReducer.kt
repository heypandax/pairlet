package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.OBSERVATION_ACTION_MAX_CHARS
import dev.ccpocket.protocol.OBSERVATION_STALE_AFTER_MS
import dev.ccpocket.protocol.ObservedEvidence
import dev.ccpocket.protocol.ObservedFreshness
import dev.ccpocket.protocol.ObservedProgress
import dev.ccpocket.protocol.ObservedStates

/**
 * What a native transcript PROVES about its latest turn (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.4). Collected
 * by the backend's transcript scanner in its single pass; agent-agnostic so the reducer below is the one place the
 * list row and the live observe view both get their state from.
 *
 * Codex rollouts (probed on codex-cli 0.155.1, see scripts/probe-dots-observation.py): `event_msg/task_started`
 * opens a turn, `event_msg/task_complete` ends it normally, `event_msg/turn_aborted` (reason `interrupted`) cancels
 * it. No `request_user_input` / approval record was found in any local rollout, so WAITING_INPUT is never derived
 * in v1 — an unverified event name is not something to guess. `event_msg/error` as a turn failure is likewise
 * unverified and only honoured when it arrives AFTER the turn started and nothing ended the turn since.
 *
 * All timestamps are epoch millis taken from the record's own `timestamp` (never the wall clock of the read).
 */
data class TurnEvidence(
    val turnId: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** `complete` / `aborted` / `error` — how the latest turn ended, null while it has not. */
    val endKind: String? = null,
    val endReason: String? = null,
    /** Newest lifecycle (task_started / task_complete / turn_aborted / error) record time. */
    val lastEventAt: Long? = null,
    /** Newest business record of any kind (message, reasoning, tool call, tool result, item completion). */
    val lastActivityAt: Long? = null,
    /** The tool call most recently STARTED and not yet seen finishing, as a bounded preview; null when none. */
    val lastAction: String? = null,
) {
    val ended: Boolean get() = endedAt != null && (startedAt == null || endedAt >= startedAt)
    val running: Boolean get() = startedAt != null && !ended

    companion object {
        const val END_COMPLETE = "complete"
        const val END_ABORTED = "aborted"
        const val END_ERROR = "error"

        /** One bounded line for [lastAction]: the tool name plus the first line of its input. */
        fun actionPreview(tool: String?, input: String?): String {
            val name = tool?.takeIf { it.isNotBlank() } ?: "tool"
            val line = input?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return (if (line.isEmpty()) name else "$name · $line").take(OBSERVATION_ACTION_MAX_CHARS)
        }
    }
}

/**
 * [TurnEvidence] + file facts → the wire [ObservedProgress]. Pure, so the mapping table of the design document is
 * testable without a daemon:
 *
 *  - file missing / unreadable → state UNKNOWN, freshness UNAVAILABLE (never "finished", never "cancelled");
 *  - no lifecycle record at all → UNKNOWN with evidence TRANSCRIPT_ACTIVITY ("recent activity", not "running");
 *  - started, not ended → RUNNING (+ the running tool as currentAction);
 *  - ended → IDLE / CANCELLED / FAILED by how it ended; a new task_started later re-opens the turn;
 *  - freshness: CURRENT while the newest evidence is younger than [staleAfterMs], STALE after. A terminal state is
 *    kept whatever its age; the UI decides what to say about an old one.
 *
 * Re-reading the same bytes never refreshes anything: every time here comes from the record or the file's mtime.
 */
object ObservedProgressReducer {
    fun reduce(
        turns: TurnEvidence?,
        fileMtime: Long?,
        exists: Boolean,
        now: Long,
        staleAfterMs: Long = OBSERVATION_STALE_AFTER_MS,
    ): ObservedProgress {
        if (!exists) return ObservedProgress(state = ObservedStates.UNKNOWN, observedAt = now, freshness = ObservedFreshness.UNAVAILABLE)
        val activity = listOfNotNull(turns?.lastActivityAt, turns?.lastEventAt, fileMtime?.takeIf { it > 0 }).maxOrNull()
        fun freshness(at: Long?): String = when {
            at == null -> ObservedFreshness.UNKNOWN
            now - at < staleAfterMs -> ObservedFreshness.CURRENT
            else -> ObservedFreshness.STALE
        }
        if (turns == null || (turns.startedAt == null && turns.endedAt == null)) {
            return ObservedProgress(
                state = ObservedStates.UNKNOWN,
                evidence = if (activity != null) ObservedEvidence.TRANSCRIPT_ACTIVITY else null,
                lastActivityAt = activity,
                observedAt = now,
                freshness = freshness(activity),
            )
        }
        return if (turns.ended) {
            ObservedProgress(
                state = when (turns.endKind) {
                    TurnEvidence.END_COMPLETE -> ObservedStates.IDLE
                    TurnEvidence.END_ABORTED -> ObservedStates.CANCELLED
                    TurnEvidence.END_ERROR -> ObservedStates.FAILED
                    else -> ObservedStates.UNKNOWN
                },
                evidence = ObservedEvidence.NATIVE_EVENT,
                sourceEventAt = turns.endedAt,
                lastActivityAt = activity,
                observedAt = now,
                freshness = freshness(activity),
                turnId = turns.turnId,
            )
        } else {
            ObservedProgress(
                state = ObservedStates.RUNNING,
                evidence = ObservedEvidence.NATIVE_EVENT,
                sourceEventAt = turns.startedAt,
                lastActivityAt = activity,
                observedAt = now,
                freshness = freshness(activity),
                currentAction = turns.lastAction,
                turnId = turns.turnId,
            )
        }
    }
}
