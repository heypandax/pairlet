package dev.ccpocket.app.ui.session

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sessions state ladder (Mobile UI 2.0 · A Master Proofs v1 "Safety invariants · State priority").
 *
 * These lock the two halves that a visual refactor could quietly break: the ORDER a session's one state is
 * chosen in, and the fact that a state the protocol cannot prove is never chosen at all.
 */
class SessionStateUiTest {

    private fun s(
        id: String, cwd: String = "/p", live: Boolean = false, busy: Boolean = false,
        lastModified: Long = 0L, branch: String? = null, count: Int = 0,
    ) = SessionSummary(
        sessionId = id, title = id, firstPrompt = "", messageCount = count, cwd = cwd,
        lastModified = lastModified, gitBranch = branch, live = live, busy = busy, agent = AgentKind.CLAUDE,
    )

    private fun ask(id: String, cwd: String = "/p", question: Boolean = false) =
        SessionAttention(sessionId = id, workdir = cwd, isQuestion = question)

    // ── priority ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun approval_outranks_answer_running_and_complete() {
        val row = s("a", live = true, busy = true)
        assertEquals(SurfaceState.APPROVAL, sessionState(row, ask("a")))
    }

    @Test
    fun question_selects_answer_not_approval() {
        assertEquals(SurfaceState.ANSWER, sessionState(s("a"), ask("a", question = true)))
    }

    @Test
    fun both_pending_on_one_session_resolves_to_the_security_gate() {
        val attention = listOf(ask("a", question = true), ask("a"))
        val picked = attentionFor(s("a"), attention)
        assertEquals(false, picked?.isQuestion, "an open approval outranks a question on the same session")
        assertEquals(SurfaceState.APPROVAL, sessionState(s("a"), picked))
    }

    @Test
    fun live_or_busy_is_running_and_neither_is_complete() {
        assertEquals(SurfaceState.RUNNING, sessionState(s("a", live = true), null))
        assertEquals(SurfaceState.RUNNING, sessionState(s("a", busy = true), null))
        assertEquals(SurfaceState.COMPLETE, sessionState(s("a"), null))
    }

    @Test
    fun the_degradation_seam_stays_wired_for_a_future_field() {
        // FAILURE / NEW_RESULT are unreachable from today's data, but the ladder already routes them —
        // so adding an authoritative field is one argument here, not a rewrite of the row chrome
        assertEquals(SurfaceState.FAILURE, sessionState(s("a", live = true), null, failed = true))
        assertEquals(SurfaceState.NEW_RESULT, sessionState(s("a"), null, newResult = true))
        // …and even then an intervention still outranks both
        assertEquals(SurfaceState.APPROVAL, sessionState(s("a"), ask("a"), failed = true, newResult = true))
    }

    // ── no invention ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_stale_session_is_complete_never_failed_or_new() {
        // an ancient row, a fresh row and a chatty row all land on the same settled state: lastModified,
        // messageCount and prompt text are not evidence of failure or of a result
        val ancient = s("a", lastModified = 1L, count = 400)
        val fresh = s("b", lastModified = Long.MAX_VALUE / 2, count = 1)
        for (row in listOf(ancient, fresh)) {
            val state = sessionState(row, null)
            assertEquals(SurfaceState.COMPLETE, state)
            assertTrue(state != SurfaceState.FAILURE && state != SurfaceState.NEW_RESULT)
        }
    }

    @Test
    fun attention_needs_both_identifiers_and_both_must_match() {
        val row = s("a", cwd = "/p")
        assertNull(attentionFor(row, listOf(ask("other", "/p"))), "a different session must not light this row")
        assertNull(attentionFor(row, listOf(ask("a", "/elsewhere"))), "a different project must not light this row")
        assertNull(attentionFor(row, listOf(SessionAttention(null, "/p", false))), "a row naming no session proves nothing")
        assertNull(attentionFor(row, listOf(SessionAttention("a", null, false))), "a row naming no project proves nothing")
        assertNull(attentionFor(row, listOf(SessionAttention("", "", false))), "blank is not a match")
        assertEquals(ask("a", "/p"), attentionFor(row, listOf(ask("a", "/p"))))
    }

    // ── partition ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun active_holds_intervention_and_running_recent_holds_the_settled_rest() {
        val sessions = listOf(
            s("done1"), s("blocked"), s("asked"), s("busy", busy = true), s("done2"), s("live", live = true),
        )
        val attention = listOf(ask("blocked"), ask("asked", question = true))
        val split = splitSessions(sessionRows(sessions, attention))
        assertEquals(listOf("blocked", "asked", "busy", "live"), split.active.map { it.session.sessionId })
        assertEquals(listOf("done1", "done2"), split.recent.map { it.session.sessionId })
    }

    @Test
    fun the_daemons_order_survives_the_partition() {
        // the split must not re-sort by state: the daemon already ordered these by recency
        val sessions = listOf(s("z", live = true), s("y", live = true), s("x", live = true))
        val split = splitSessions(sessionRows(sessions, emptyList()))
        assertEquals(listOf("z", "y", "x"), split.active.map { it.session.sessionId })
        assertTrue(split.recent.isEmpty())
    }

    // ── shape / tone / action vocabulary ────────────────────────────────────────────────────────────

    @Test
    fun every_state_has_a_distinct_enough_mark_and_only_interventions_act() {
        assertEquals(StateMark.DIAMOND, SurfaceState.APPROVAL.mark)
        assertEquals(StateMark.DIAMOND, SurfaceState.ANSWER.mark)
        assertEquals(StateMark.SQUARE, SurfaceState.FAILURE.mark)
        assertEquals(StateMark.DOT, SurfaceState.RUNNING.mark)
        assertEquals(StateMark.RING, SurfaceState.COMPLETE.mark)
        assertEquals(StateAction.REVIEW, SurfaceState.APPROVAL.action)
        assertEquals(StateAction.ANSWER, SurfaceState.ANSWER.action)
        for (quiet in listOf(SurfaceState.FAILURE, SurfaceState.RUNNING, SurfaceState.NEW_RESULT, SurfaceState.COMPLETE)) {
            assertNull(quiet.action, "$quiet must not offer an action — only a real intervention does")
        }
        assertEquals(StateTone.DANGER, SurfaceState.FAILURE.tone)
        assertEquals(StateTone.NEUTRAL, SurfaceState.COMPLETE.tone)
    }
}
