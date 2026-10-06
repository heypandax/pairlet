package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.ObservedEvidence
import dev.ccpocket.protocol.ObservedFreshness
import dev.ccpocket.protocol.ObservedStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The state table of docs/design/DOTS-SESSION-OBSERVABILITY.md §4.4, as the one reducer both the list row and the
 * observe view consume. Times are epoch millis from the record; `now` is the daemon's clock.
 */
class ObservedProgressReducerTest {
    private val now = 1_000_000L

    @Test
    fun missing_file_is_unavailable_never_finished() {
        val p = ObservedProgressReducer.reduce(TurnEvidence(startedAt = 1, endedAt = 2, endKind = TurnEvidence.END_COMPLETE), fileMtime = null, exists = false, now = now)
        assertEquals(ObservedStates.UNKNOWN, p.state)
        assertEquals(ObservedFreshness.UNAVAILABLE, p.freshness)
        assertNull(p.evidence)
    }

    @Test
    fun activity_without_lifecycle_records_is_unknown_with_transcript_evidence() {
        val fresh = ObservedProgressReducer.reduce(null, fileMtime = now - 5_000, exists = true, now = now)
        assertEquals(ObservedStates.UNKNOWN, fresh.state)
        assertEquals(ObservedEvidence.TRANSCRIPT_ACTIVITY, fresh.evidence)
        assertEquals(ObservedFreshness.CURRENT, fresh.freshness)
        assertEquals(now - 5_000, fresh.lastActivityAt)
        val old = ObservedProgressReducer.reduce(TurnEvidence(lastActivityAt = now - 90_000), fileMtime = now - 90_000, exists = true, now = now)
        assertEquals(ObservedStates.UNKNOWN, old.state)
        assertEquals(ObservedFreshness.STALE, old.freshness)
    }

    @Test
    fun started_turn_without_end_is_running_and_goes_stale_after_sixty_seconds() {
        val turns = TurnEvidence(turnId = "t1", startedAt = now - 30_000, lastEventAt = now - 30_000, lastActivityAt = now - 10_000, lastAction = "shell · ls")
        val running = ObservedProgressReducer.reduce(turns, fileMtime = now - 10_000, exists = true, now = now)
        assertEquals(ObservedStates.RUNNING, running.state)
        assertEquals(ObservedEvidence.NATIVE_EVENT, running.evidence)
        assertEquals(ObservedFreshness.CURRENT, running.freshness)
        assertEquals("shell · ls", running.currentAction)
        assertEquals("t1", running.turnId)
        assertEquals(now - 30_000, running.sourceEventAt)
        // a long silent tool: the state is kept, only the freshness moves — never "finished", never "failed"
        val later = ObservedProgressReducer.reduce(turns, fileMtime = now - 10_000, exists = true, now = now + 61_000)
        assertEquals(ObservedStates.RUNNING, later.state)
        assertEquals(ObservedFreshness.STALE, later.freshness)
        assertEquals("shell · ls", later.currentAction)
    }

    @Test
    fun re_reading_the_same_bytes_does_not_refresh() {
        val turns = TurnEvidence(startedAt = now - 100_000, lastActivityAt = now - 100_000)
        val p = ObservedProgressReducer.reduce(turns, fileMtime = now - 100_000, exists = true, now = now)
        assertEquals(ObservedFreshness.STALE, p.freshness) // observedAt is now, but no evidence time is
        assertEquals(now, p.observedAt)
        assertEquals(now - 100_000, p.lastActivityAt)
    }

    @Test
    fun ended_turns_map_to_idle_cancelled_failed_and_keep_their_terminal_state_when_old() {
        fun ended(kind: String) = TurnEvidence(turnId = "t", startedAt = now - 200_000, endedAt = now - 150_000, endKind = kind, lastActivityAt = now - 150_000)
        assertEquals(ObservedStates.IDLE, ObservedProgressReducer.reduce(ended(TurnEvidence.END_COMPLETE), now - 150_000, true, now).state)
        assertEquals(ObservedStates.CANCELLED, ObservedProgressReducer.reduce(ended(TurnEvidence.END_ABORTED), now - 150_000, true, now).state)
        assertEquals(ObservedStates.FAILED, ObservedProgressReducer.reduce(ended(TurnEvidence.END_ERROR), now - 150_000, true, now).state)
        val idle = ObservedProgressReducer.reduce(ended(TurnEvidence.END_COMPLETE), now - 150_000, true, now)
        assertEquals(ObservedFreshness.STALE, idle.freshness)
        assertEquals(now - 150_000, idle.sourceEventAt)
        assertNull(idle.currentAction)
    }

    @Test
    fun a_new_turn_after_a_finished_one_is_running_again() {
        val turns = TurnEvidence(turnId = "t2", startedAt = now - 1_000, endedAt = now - 5_000, endKind = TurnEvidence.END_COMPLETE, lastActivityAt = now - 1_000)
        val p = ObservedProgressReducer.reduce(turns, now - 1_000, true, now)
        assertEquals(ObservedStates.RUNNING, p.state)
        assertEquals("t2", p.turnId)
    }

    @Test
    fun waiting_input_is_never_derived_in_v1() {
        // no verified ask record exists in the probed corpus: nothing the reducer sees can produce it
        val every = listOf(
            ObservedProgressReducer.reduce(null, now, true, now),
            ObservedProgressReducer.reduce(TurnEvidence(startedAt = now), now, true, now),
            ObservedProgressReducer.reduce(TurnEvidence(startedAt = now - 1, endedAt = now, endKind = "weird"), now, true, now),
        )
        every.forEach { assert(it.state != ObservedStates.WAITING_INPUT) }
        assertEquals(ObservedStates.UNKNOWN, every[2].state) // an unknown end kind is not a terminal claim
    }

    @Test
    fun action_preview_is_one_bounded_line() {
        assertEquals("shell · ls -la", TurnEvidence.actionPreview("shell", "ls -la\nsecond line"))
        assertEquals("tool", TurnEvidence.actionPreview(null, "   "))
        assertEquals(160, TurnEvidence.actionPreview("x", "y".repeat(500)).length)
    }
}
