package dev.ccpocket.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlin.math.abs
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
@OptIn(ExperimentalTestApi::class)
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
    fun the_degradation_seam_and_observed_result_keep_the_priority_ladder() {
        // FAILURE remains unreachable from today's data; NEW_RESULT is supplied only by the repository's
        // observed working-to-settled transition. Both still obey the shared priority ladder.
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

    @Test
    fun observed_results_stay_active_in_daemon_order_until_opened() {
        val sessions = listOf(s("fresh-2"), s("ordinary"), s("fresh-1"))
        val split = splitSessions(sessionRows(sessions, emptyList(), setOf("fresh-1", "fresh-2")))
        assertEquals(listOf("fresh-2", "fresh-1"), split.active.map { it.session.sessionId })
        assertEquals(listOf(SurfaceState.NEW_RESULT, SurfaceState.NEW_RESULT), split.active.map { it.state })
        assertEquals(listOf("ordinary"), split.recent.map { it.session.sessionId })
    }

    @Test
    fun running_and_intervention_truth_outrank_a_stale_new_result_mark() {
        val sessions = listOf(s("running", live = true), s("blocked"))
        val rows = sessionRows(
            sessions,
            listOf(ask("blocked")),
            setOf("running", "blocked"),
            currentlyWorking = setOf("running"),
        )
        assertEquals(SurfaceState.RUNNING, rows[0].state)
        assertEquals(SurfaceState.APPROVAL, rows[1].state)
    }

    @Test
    fun authoritative_settled_result_outranks_the_recent_mtime_live_heuristic() {
        val staleLive = s("fresh", live = true)
        val row = sessionRows(
            listOf(staleLive),
            emptyList(),
            newResults = setOf("fresh"),
            currentlyWorking = emptySet(),
        ).single()
        assertEquals(SurfaceState.NEW_RESULT, row.state)
    }

    @Test
    fun anExpiredTerminalHeuristicFallsToCompleteWithoutInventingANewResult() {
        val row = sessionRows(
            listOf(s("terminal", live = true, lastModified = Long.MAX_VALUE / 2)),
            emptyList(),
            newResults = emptySet(),
            currentlyWorking = emptySet(),
        ).single()

        assertEquals(SurfaceState.COMPLETE, row.state)
        val split = splitSessions(listOf(row))
        assertTrue(split.active.isEmpty())
        assertEquals(listOf("terminal"), split.recent.map { it.session.sessionId })
    }

    // ── shape / tone / action vocabulary ────────────────────────────────────────────────────────────

    /**
     * #239 · R3: the round marks form a fill ladder — filled while running, HALF while there is a result
     * you have not opened, hollow once it is ordinary history. New Result and Complete previously shared
     * the ring, which left them separable by colour alone in a vocabulary that must survive greyscale.
     */
    @Test
    fun a_new_result_is_the_half_filled_mark_and_nothing_else_moved() {
        assertEquals(StateMark.HALF_DOT, SurfaceState.NEW_RESULT.mark)
        assertEquals(StateMark.DOT, SurfaceState.RUNNING.mark, "running stays the filled dot")
        assertEquals(StateMark.RING, SurfaceState.COMPLETE.mark, "complete stays the hollow ring")
        assertEquals(
            listOf(StateMark.DOT, StateMark.HALF_DOT, StateMark.RING).distinct().size, 3,
            "the three round marks stay distinct shapes",
        )
        // tone, label ownership, action and partition are untouched by the mark change
        assertEquals(StateTone.ATTENTION, SurfaceState.NEW_RESULT.tone)
        assertNull(SurfaceState.NEW_RESULT.action)
        assertTrue(SurfaceState.NEW_RESULT.pinsToActive)
        assertTrue(!SurfaceState.COMPLETE.pinsToActive)
    }

    @Test
    fun sessions_marks_follow_the_master_geometry_at_large_type() {
        assertEquals(10.dp, sessionStateMarkSize(1f))
        assertEquals(10.dp, sessionStateMarkSize(1.49f))
        assertEquals(14.dp, sessionStateMarkSize(1.5f))
        assertEquals(14.dp, sessionStateMarkSize(2f))
        assertEquals(2.dp, SessionStateMarkStroke)
    }

    @Test
    fun theRealLargeTypeSessionRowRendersAHalfFilledFourteenDpResultMark() =
        runDesktopComposeUiTest(120, 100) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    PocketTheme(dark = false) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            SessionListRow(
                                SessionRowUi(s("new-result"), SurfaceState.NEW_RESULT),
                                onOpen = {},
                                onLongPress = null,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            fun hasInk(x: Int, y: Int): Boolean {
                val pixel = bitmap.getColor(x, y)
                val r = pixel shr 16 and 0xff
                val g = pixel shr 8 and 0xff
                val b = pixel and 0xff
                return abs(255 - r) + abs(255 - g) + abs(255 - b) > 45
            }
            val ink = buildList {
                for (y in 0 until bitmap.height) for (x in 0 until 20) if (hasInk(x, y)) add(x to y)
            }
            assertTrue(ink.isNotEmpty(), "the row must render its state mark")
            val minX = ink.minOf { it.first }
            val maxX = ink.maxOf { it.first }
            val minY = ink.minOf { it.second }
            val maxY = ink.maxOf { it.second }
            assertTrue(maxX - minX + 1 >= 13, "the wired mark is at least 13 px wide at 200% type")
            assertTrue(maxY - minY + 1 >= 13, "the wired mark is at least 13 px tall at 200% type")

            val centerY = (minY + maxY) / 2
            assertTrue(hasInk(minX + 3, centerY), "the leading half is filled")
            assertTrue(!hasInk(maxX - 3, centerY), "the trailing interior stays hollow")
            assertTrue(hasInk((minX + maxX) / 2, minY), "the outer ring remains visible")
        }

    /** The whole ladder, adjacent, in its fixed order — a mark change may not reshuffle precedence. */
    @Test
    fun the_full_precedence_ladder_is_unchanged() {
        assertEquals(
            listOf(
                SurfaceState.APPROVAL, SurfaceState.ANSWER, SurfaceState.FAILURE,
                SurfaceState.RUNNING, SurfaceState.NEW_RESULT, SurfaceState.COMPLETE,
            ),
            SurfaceState.entries.toList(),
        )
        val row = s("a", live = true)
        assertEquals(SurfaceState.APPROVAL, sessionState(row, ask("a"), failed = true, newResult = true, currentlyWorking = true))
        assertEquals(SurfaceState.ANSWER, sessionState(row, ask("a", question = true), failed = true, newResult = true, currentlyWorking = true))
        assertEquals(SurfaceState.FAILURE, sessionState(row, null, failed = true, newResult = true, currentlyWorking = true))
        assertEquals(SurfaceState.RUNNING, sessionState(row, null, newResult = true, currentlyWorking = true))
        assertEquals(SurfaceState.NEW_RESULT, sessionState(row, null, newResult = true, currentlyWorking = false))
        assertEquals(SurfaceState.COMPLETE, sessionState(row, null, currentlyWorking = false))
    }

    @Test
    fun every_state_has_a_distinct_enough_mark_and_only_interventions_act() {
        assertEquals(StateMark.DIAMOND, SurfaceState.APPROVAL.mark)
        assertEquals(StateMark.DIAMOND, SurfaceState.ANSWER.mark)
        assertEquals(StateMark.SQUARE, SurfaceState.FAILURE.mark)
        assertEquals(StateMark.DOT, SurfaceState.RUNNING.mark)
        assertEquals(StateMark.HALF_DOT, SurfaceState.NEW_RESULT.mark)
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
