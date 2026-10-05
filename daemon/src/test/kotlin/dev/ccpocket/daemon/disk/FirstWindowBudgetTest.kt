package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DiagnosticContext
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PocketJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The narrowed first window ([ReplaySlicer.slice]'s `firstWindowBytes`): whole rows, newest first, under an
 * ENCODED budget, never below the row floor, never splitting a source line — and, off (null), today's window
 * exactly. The seam against paging is what matters most: the rows it leaves out must page back in whole.
 */
class FirstWindowBudgetTest {

    private fun row(line: Long, text: String = "r$line", role: ChatRole = ChatRole.ASSISTANT) =
        ReplaySlicer.Row(HistoryMessage(role, text), line)

    /** [n] rows of ~[size] bytes each, one per source line, lines 1..n. */
    private fun rows(n: Int, size: Int = 3_000) = (1..n).map { row(it.toLong(), "x".repeat(size) + it) }

    private fun frameBytes(slice: ReplaySlice): Int = PocketJson.encodeToString(
        Envelope.serializer(),
        Envelope(
            "123456", 0L,
            body = ConvoHistory(
                "00000000-0000-0000-0000-000000000000", slice.messages, slice.lastSeq, slice.firstSeq,
                slice.delta, slice.hasMore, DiagnosticContext("1234567890abcdef1234567890abcdef", 1, "abcdef1234567890"),
            ),
        ),
    ).encodeToByteArray().size

    /** First window, then every page up to the top, the way the phone walks it (default limit 100). */
    private fun walk(rows: List<ReplaySlicer.Row>, first: ReplaySlice): List<HistoryMessage> {
        val out = first.messages.toMutableList()
        var before = first.firstSeq
        var more = first.hasMore
        while (more && before != null) {
            val page = ReplaySlicer.page(rows, before, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES)
            out.addAll(0, page.messages)
            before = page.firstSeq
            more = page.hasMore
        }
        return out
    }

    @Test
    fun off_is_todays_window_exactly() {
        val rs = rows(150)
        val off = ReplaySlicer.slice(rs, 150, sinceSeq = 0, maxMessages = 100, maxBytes = ReplayBudget.MAX_FRAME_TEXT_BYTES)
        val legacy = ReplaySlicer.page(rs, beforeSeq = 151, limit = 100, maxBytes = ReplayBudget.MAX_FRAME_TEXT_BYTES)
        assertEquals(legacy.messages, off.messages)
        assertEquals(legacy.firstSeq, off.firstSeq)
        assertEquals(legacy.hasMore, off.hasMore)
        assertEquals(0, off.budgetDropped)
    }

    @Test
    fun narrowed_window_fits_the_encoded_budget_and_pages_back_seamlessly() {
        val rs = rows(150) // ~3 KB a row: 100 rows ≈ 300 KB, well past the budget
        val first = ReplaySlicer.slice(rs, 150, 0, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES, ReplaySlicer.FIRST_WINDOW_BYTES)
        assertTrue(first.messages.size in ReplaySlicer.FIRST_WINDOW_MIN_ROWS until 100, "kept ${first.messages.size}")
        assertTrue(frameBytes(first) <= ReplaySlicer.FIRST_WINDOW_BYTES, "frame ${frameBytes(first)} B")
        assertTrue(first.hasMore)
        assertEquals(first.messages.first().seq, first.firstSeq)
        assertEquals(100 - first.messages.size, first.budgetDropped)
        // newest rows, untouched
        assertEquals(rs.takeLast(first.messages.size).map { it.stamped() }, first.messages)
        // one more row would not have fit — the cut is as late as the budget allows
        val oneMore = ReplaySlice(rs.takeLast(first.messages.size + 1).map { it.stamped() })
        assertTrue(frameBytes(oneMore) > ReplaySlicer.FIRST_WINDOW_BYTES - ReplaySlicer.FIRST_WINDOW_FRAME_RESERVE)
        // first window + paging to the top = every row, once, in order
        assertEquals(rs.map { it.stamped() }, walk(rs, first))
    }

    @Test
    fun a_single_row_over_budget_still_ships_with_the_floor() {
        val rs = rows(40, size = 500).toMutableList()
        rs[rs.lastIndex] = row(40, "y".repeat(300_000), ChatRole.USER) // a huge paste as the newest row
        val first = ReplaySlicer.slice(rs, 40, 0, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES, ReplaySlicer.FIRST_WINDOW_BYTES)
        assertEquals(ReplaySlicer.FIRST_WINDOW_MIN_ROWS, first.messages.size)
        assertTrue(frameBytes(first) > ReplaySlicer.FIRST_WINDOW_BYTES) // the documented exception
        assertEquals(300_000, first.messages.last().text.length) // never clipped by this budget
        assertEquals(33L, first.firstSeq)
        assertEquals(rs.map { it.stamped() }, walk(rs, first))
    }

    @Test
    fun a_short_session_is_sent_whole() {
        val rs = rows(5, size = 100_000) // over budget, but under the floor: all of it
        val first = ReplaySlicer.slice(rs, 5, 0, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES, ReplaySlicer.FIRST_WINDOW_BYTES)
        assertEquals(5, first.messages.size)
        assertFalse(first.hasMore)
        assertEquals(1L, first.firstSeq)
    }

    @Test
    fun the_cut_never_splits_one_source_lines_rows() {
        // lines 1..60, each line carrying TWO rows (a multi-block assistant record)
        val rs = (1..60).flatMap { l -> listOf(row(l.toLong(), "a".repeat(2_500)), row(l.toLong(), "b".repeat(2_500))) }
        val first = ReplaySlicer.slice(rs, 60, 0, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES, ReplaySlicer.FIRST_WINDOW_BYTES)
        assertEquals(0, first.messages.size % 2, "a line was split: ${first.messages.size} rows")
        assertTrue(frameBytes(first) <= ReplaySlicer.FIRST_WINDOW_BYTES)
        assertEquals(rs.map { it.stamped() }, walk(rs, first))
    }

    @Test
    fun a_split_at_the_floor_takes_the_line_whole() {
        // the 8 newest rows end mid-line: line 50 holds rows 7..9 counted from the end
        val tail = (51..56).map { row(it.toLong(), "z".repeat(60_000)) } // 6 rows, 360 KB
        val rs = (1..49).map { row(it.toLong()) } + List(3) { row(50, "w$it") } + tail
        val first = ReplaySlicer.slice(rs, 56, 0, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES, ReplaySlicer.FIRST_WINDOW_BYTES)
        assertEquals(9, first.messages.size) // floor 8 → extended to keep line 50 whole
        assertEquals(50L, first.firstSeq)
        assertEquals(rs.map { it.stamped() }, walk(rs, first))
    }

    @Test
    fun a_delta_is_never_narrowed() {
        val rs = rows(150)
        val delta = ReplaySlicer.slice(rs, 150, sinceSeq = 90, maxMessages = 100,
            maxBytes = ReplayBudget.MAX_FRAME_TEXT_BYTES, firstWindowBytes = ReplaySlicer.FIRST_WINDOW_BYTES)
        assertTrue(delta.delta)
        assertEquals(60, delta.messages.size) // ~180 KB of rows past the cursor — all of them, as before
        assertEquals(91L, delta.firstSeq)
    }
}
