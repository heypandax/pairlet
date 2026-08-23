package dev.ccpocket.app.ui

import dev.ccpocket.app.ui.session.forkParentTitle
import dev.ccpocket.app.ui.session.rewoundSuccessorTitle
import dev.ccpocket.app.ui.session.splitRewound
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #282 §2 铁律 3, as an executable rule: **a rewind must not grow the default session list.**
 *
 * This is the acceptance anchor for the whole feature's "no session increase" promise, and it holds
 * structurally rather than by bookkeeping — one field ([SessionSummary.rewindOf]) both creates the new
 * row's meaning and removes the old row from view, so the two halves cannot get out of step. A fork is
 * the deliberate opposite and is pinned here too, so nobody "fixes" it into folding.
 */
class SessionLineageTest {

    private fun s(id: String, title: String = id, rewindOf: String? = null, forkedFrom: String? = null) =
        SessionSummary(id, title, "p", 1, "/x", 0, rewindOf = rewindOf, forkedFrom = forkedFrom)

    @Test
    fun a_rewind_keeps_the_visible_count_exactly_where_it_was() {
        val before = listOf(s("orig", "Review replay"), s("other"))
        assertEquals(2, splitRewound(before).visible.size)

        // …now rewind "orig": the branch appears AND names what it replaced
        val after = listOf(s("branch", "Review replay, take 2", rewindOf = "orig"), s("orig", "Review replay"), s("other"))
        val split = splitRewound(after)

        assertEquals(2, split.visible.size, "one row out, one row in — the list is the length it was")
        assertEquals(listOf("branch", "other"), split.visible.map { it.sessionId })
        assertEquals(listOf("orig"), split.rewound.map { it.sessionId })
    }

    @Test
    fun a_fork_grows_the_list_on_purpose() {
        val after = listOf(s("branch", "Attempt key", forkedFrom = "orig"), s("orig", "Review replay"))
        val split = splitRewound(after)
        assertEquals(2, split.visible.size, "both sessions are peers — a fork explores, it does not replace")
        assertTrue(split.rewound.isEmpty())
    }

    @Test
    fun order_is_preserved_on_both_sides() {
        val rows = listOf(
            s("b1", rewindOf = "o1"),
            s("o1"),
            s("mid"),
            s("b2", rewindOf = "o2"),
            s("o2"),
        )
        val split = splitRewound(rows)
        assertEquals(listOf("b1", "mid", "b2"), split.visible.map { it.sessionId })
        assertEquals(listOf("o1", "o2"), split.rewound.map { it.sessionId })
    }

    @Test
    fun an_original_whose_successor_is_not_in_this_view_stays_visible() {
        // filtered by agent, or looking at another project: hiding a row with nothing on screen to point
        // at would just lose a session
        val split = splitRewound(listOf(s("orig")))
        assertEquals(listOf("orig"), split.visible.map { it.sessionId })
        assertTrue(split.rewound.isEmpty())
    }

    @Test
    fun a_self_reference_is_ignored_rather_than_hiding_the_row() {
        // only reachable through a corrupt ledger; the honest answer to "this replaced itself" is to keep
        // showing it, never to make it disappear
        val split = splitRewound(listOf(s("loop", rewindOf = "loop")))
        assertEquals(listOf("loop"), split.visible.map { it.sessionId })
    }

    @Test
    fun a_chain_of_rewinds_still_shows_exactly_one_row() {
        // rewound twice: orig → take2 → take3. Only the newest is current.
        val rows = listOf(s("take3", rewindOf = "take2"), s("take2", rewindOf = "orig"), s("orig"))
        val split = splitRewound(rows)
        assertEquals(listOf("take3"), split.visible.map { it.sessionId })
        assertEquals(listOf("take2", "orig"), split.rewound.map { it.sessionId })
    }

    @Test
    fun captions_name_the_other_end_of_the_edge() {
        val all = listOf(
            s("branch", "Attempt key", forkedFrom = "orig"),
            s("take2", "Take 2", rewindOf = "gone-orig"),
            s("orig", "Review replay"),
        )
        assertEquals("Review replay", forkParentTitle(all[0], all))
        assertEquals("Take 2", rewoundSuccessorTitle(s("gone-orig"), all))
        // no edge, or an edge whose other end isn't here → no caption rather than a dangling one
        assertNull(forkParentTitle(all[2], all))
        assertNull(rewoundSuccessorTitle(all[2], all))
    }
}
