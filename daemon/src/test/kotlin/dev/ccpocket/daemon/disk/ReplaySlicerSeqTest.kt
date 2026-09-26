package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every replay frame anchors paging on row LINES; the rows themselves must carry the same number so a
 * consumer that drops a window's oldest rows (the per-client frame fitter) can re-anchor on a kept row.
 * Only the Claude replay stamps `seq` itself; Codex/Kimi/DSH rows arrive unstamped and get it here.
 */
class ReplaySlicerSeqTest {

    private fun row(line: Long, text: String = "r$line", seq: Long? = null, compact: Boolean = false) =
        ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, text, seq = seq, compactSummary = compact), line)

    @Test
    fun unstamped_rows_get_their_line_as_seq_on_every_replay_path() {
        val rows = (1L..6L).map { row(it) }
        val full = ReplaySlicer.slice(rows, cursor = 6, sinceSeq = null, maxMessages = 100, maxBytes = 1_000_000)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), full.messages.map { it.seq })
        assertEquals(full.firstSeq, full.messages.first().seq)

        val delta = ReplaySlicer.slice(rows, cursor = 6, sinceSeq = 4, maxMessages = 100, maxBytes = 1_000_000)
        assertEquals(true, delta.delta)
        assertEquals(listOf(5L, 6L), delta.messages.map { it.seq })

        val page = ReplaySlicer.page(rows, beforeSeq = 4, limit = 2, maxBytes = 1_000_000)
        assertEquals(listOf(2L, 3L), page.messages.map { it.seq })
        assertEquals(2L, page.firstSeq)
    }

    @Test
    fun a_backend_that_stamps_its_own_seq_is_left_alone_and_a_compact_summary_stays_unstamped() {
        val rows = listOf(row(10, seq = 10), row(11, compact = true), row(12))
        val out = ReplaySlicer.slice(rows, cursor = 12, sinceSeq = null, maxMessages = 100, maxBytes = 1_000_000).messages
        assertEquals(10L, out[0].seq)
        assertNull(out[1].seq) // never a rewind anchor, and the fitter drops it rather than anchors on it
        assertEquals(12L, out[2].seq)
    }
}
