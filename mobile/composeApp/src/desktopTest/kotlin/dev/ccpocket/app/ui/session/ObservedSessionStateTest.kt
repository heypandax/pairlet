package dev.ccpocket.app.ui.session

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ObservationBinding
import dev.ccpocket.protocol.ObservedFreshness
import dev.ccpocket.protocol.ObservedProgress
import dev.ccpocket.protocol.ObservedStates
import dev.ccpocket.protocol.SessionObservation
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/DOTS-SESSION-OBSERVABILITY.md §4.4 on the client: a read-only observed member's row state comes from its
 * native snapshot — `live`/`busy` and the directory's process truth do not promote it, silence does not settle it, an
 * unknown vocabulary value is UNKNOWN, and nothing observed is ever actionable here.
 */
class ObservedSessionStateTest {
    private fun observed(state: String?, freshness: String = ObservedFreshness.CURRENT, readOnly: Boolean = true, live: Boolean = false, busy: Boolean = false) =
        SessionSummary(
            "sid", "t", "", 1, "/p", 0L, live = live, busy = busy, agent = AgentKind.CODEX,
            observation = SessionObservation(
                binding = ObservationBinding(),
                readOnly = readOnly,
                progress = state?.let { ObservedProgress(state = it, freshness = freshness, observedAt = 1) },
            ),
        )

    @Test
    fun observed_states_map_from_the_snapshot_not_from_live_or_busy() {
        assertEquals(SurfaceState.RUNNING, sessionState(observed(ObservedStates.RUNNING), null))
        assertEquals(SurfaceState.WAITING_EXTERNAL, sessionState(observed(ObservedStates.WAITING_INPUT), null))
        assertEquals(SurfaceState.COMPLETE, sessionState(observed(ObservedStates.IDLE), null))
        assertEquals(SurfaceState.COMPLETE, sessionState(observed(ObservedStates.CANCELLED), null))
        assertEquals(SurfaceState.FAILURE, sessionState(observed(ObservedStates.FAILED), null))
        assertEquals(SurfaceState.UNKNOWN, sessionState(observed(ObservedStates.UNKNOWN), null))
        // `live || busy` would read RUNNING for a plain row; an observed idle row stays COMPLETE
        assertEquals(SurfaceState.COMPLETE, sessionState(observed(ObservedStates.IDLE, live = true, busy = true), null))
        // and the directory's "currently working" truth is about writers this client may own — not this row's
        assertEquals(SurfaceState.COMPLETE, sessionState(observed(ObservedStates.IDLE), null, currentlyWorking = true))
        assertEquals(SurfaceState.RUNNING, sessionState(observed(ObservedStates.RUNNING), null, currentlyWorking = false))
    }

    @Test
    fun unknown_vocabulary_and_missing_snapshot_read_as_unknown_never_complete() {
        assertEquals(SurfaceState.UNKNOWN, sessionState(observed("paused_in_cloud"), null))
        assertEquals(SurfaceState.UNKNOWN, sessionState(observed(null), null))
        assertEquals(SurfaceState.UNKNOWN, sessionState(observed(null, live = true), null))
    }

    @Test
    fun a_stale_running_snapshot_keeps_running_the_meta_line_says_last_recorded() {
        assertEquals(SurfaceState.RUNNING, sessionState(observed(ObservedStates.RUNNING, ObservedFreshness.STALE), null))
    }

    @Test
    fun observed_rows_are_never_actionable_and_unknown_files_under_recent() {
        assertNull(SurfaceState.WAITING_EXTERNAL.action)
        assertNull(SurfaceState.UNKNOWN.action)
        assertTrue(SurfaceState.WAITING_EXTERNAL.pinsToActive)
        assertFalse(SurfaceState.UNKNOWN.pinsToActive)
        assertEquals(StateMark.DIAMOND, SurfaceState.WAITING_EXTERNAL.mark)
        assertEquals(StateTone.ATTENTION, SurfaceState.WAITING_EXTERNAL.tone)
        assertEquals(StateMark.RING, SurfaceState.UNKNOWN.mark)
        assertEquals(StateTone.NEUTRAL, SurfaceState.UNKNOWN.tone)
    }

    @Test
    fun a_non_read_only_snapshot_does_not_override_the_ordinary_ladder() {
        // a plain observe (writer detected, not a policy) carries readOnly = false: today's mapping applies
        assertEquals(SurfaceState.RUNNING, sessionState(observed(ObservedStates.IDLE, readOnly = false, live = true), null))
        assertNull(observedState(observed(ObservedStates.IDLE, readOnly = false)))
    }

    @Test
    fun a_real_pending_intervention_still_outranks_the_snapshot() {
        // the attention ladder stays on top: an ask the daemon really holds for this session is the truth
        val att = SessionAttention("sid", "/p", isQuestion = false)
        assertEquals(SurfaceState.APPROVAL, sessionState(observed(ObservedStates.IDLE), att))
    }
}
