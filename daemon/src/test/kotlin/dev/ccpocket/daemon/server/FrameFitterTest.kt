package dev.ccpocket.daemon.server

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.LEGACY_CLIENT_MAX_FRAME_BYTES
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-client frame cap (KTOR-6963): a shipped iOS build drops its link on any sealed message over 1 MiB,
 * so what reaches such a client is measured on the real encoding and shrunk, cheapest loss first.
 */
class FrameFitterTest {

    private val legacy = LEGACY_CLIENT_MAX_FRAME_BYTES

    private fun env(body: Frame) = Envelope("1", 0L, body = body)
    private fun sealed(bytes: ByteArray) = bytes.size + FrameFitter.SEAL_OVERHEAD_BYTES
    private fun image(kb: Int) = ImageData("image/png", "A".repeat(kb * 1000))
    private fun row(seq: Long?, text: String = "row $seq", image: ImageData? = null) =
        HistoryMessage(ChatRole.TOOL, text, tool = "Read", images = listOfNotNull(image), seq = seq)
    private fun decode(bytes: ByteArray): Frame = PocketJson.decodeFromString<Envelope>(bytes.decodeToString()).body
    private val big = "x".repeat(200_000) // 200 KB of text: nothing on the row to shed, only rows to drop

    @Test
    fun a_frame_under_the_cap_is_the_plain_encoding() {
        val e = env(ConvoHistory("c", listOf(row(1), row(2))))
        val reports = mutableListOf<String>()
        assertContentEquals(PocketJson.encodeToString(e).encodeToByteArray(), FrameFitter.encodeWithin(e, legacy) { reports += it })
        assertTrue(reports.isEmpty())
    }

    // the reported case: a 100-row window whose screenshots sum past 1 MiB. Rows stay, pictures go.
    @Test
    fun a_window_over_the_cap_sheds_pictures_before_rows_and_stays_a_delta() {
        val rows = (1L..6L).map { row(it, image = image(200)) } // 1.2 MB of pictures on 6 rows
        val e = env(ConvoHistory("c", rows, lastSeq = 6, firstSeq = 1, delta = true, hasMore = false))
        val reports = mutableListOf<String>()
        val out = FrameFitter.encodeWithin(e, legacy) { reports += it }
        assertTrue(sealed(out) <= legacy, "sealed ${sealed(out)} B")
        val got = decode(out) as ConvoHistory
        assertEquals(6, got.messages.size)
        assertTrue(got.delta)
        assertEquals(1L, got.firstSeq)
        assertFalse(got.hasMore)
        assertTrue(got.messages.last().images.isNotEmpty(), "the newest rows keep their pictures")
        val stripped = got.messages.filter { it.images.isEmpty() }
        assertTrue(stripped.isNotEmpty() && stripped.all { it.imagesTruncated }, "the shed is announced on the row")
        assertEquals(1, reports.size)
        assertTrue("shrunk to" in reports[0], reports[0])
    }

    @Test
    fun a_window_of_unsheddable_rows_drops_the_oldest_and_reanchors_on_the_kept_row() {
        val rows = (10L..21L).map { row(it, text = big) } // 2.4 MB, all text
        val e = env(ConvoHistory("c", rows, lastSeq = 21, firstSeq = 10, delta = true, hasMore = false))
        val out = FrameFitter.encodeWithin(e, legacy)
        assertTrue(sealed(out) <= legacy, "sealed ${sealed(out)} B")
        val got = decode(out) as ConvoHistory
        assertTrue(got.messages.size in 2 until rows.size, "kept ${got.messages.size}")
        assertEquals(21L, got.messages.last().seq) // the newest rows survive
        assertEquals(got.messages.first().seq, got.firstSeq) // paging resumes exactly where the window starts
        assertTrue(got.hasMore)
        assertFalse(got.delta) // a continuation missing its oldest rows is a window, not a delta
        assertEquals(21L, got.lastSeq)
    }

    @Test
    fun a_leading_row_without_a_cursor_is_dropped_so_paging_cannot_show_it_twice() {
        // the straddling row is a compact summary (no cursor): it goes rather than anchoring the window
        val rows = (1L..4L).map { row(it, text = big) } + row(null, text = big) + (6L..9L).map { row(it, text = big) }
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows, lastSeq = 9, firstSeq = 1)), legacy)) as ConvoHistory
        assertTrue(got.messages.none { it.seq == null })
        assertEquals(6L, got.firstSeq)
        assertEquals(listOf(6L, 7L, 8L, 9L), got.messages.map { it.seq })
        assertTrue(got.hasMore)
    }

    @Test
    fun rows_without_any_cursor_are_kept_but_not_anchored() {
        val rows = (1..12).map { HistoryMessage(ChatRole.ASSISTANT, big) } // a backend that does not page
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows)), legacy)) as ConvoHistory
        assertTrue(got.messages.size in 1 until rows.size)
        assertNull(got.firstSeq)
        assertTrue(got.hasMore)
    }

    @Test
    fun a_page_over_the_cap_drops_its_oldest_rows() {
        val rows = (100L..111L).map { row(it, text = big) }
        val out = FrameFitter.encodeWithin(env(ConvoHistoryPage("c", rows, firstSeq = 100, hasMore = false)), legacy)
        assertTrue(sealed(out) <= legacy)
        val got = decode(out) as ConvoHistoryPage
        assertTrue(got.messages.size < rows.size)
        assertEquals(111L, got.messages.last().seq)
        assertEquals(got.messages.first().seq, got.firstSeq)
        assertTrue(got.hasMore)
    }

    @Test
    fun a_tool_result_over_the_cap_loses_its_pictures_not_its_text() {
        val ev = ToolEvent("c", 7, ToolPhase.RESULT, "Read", inputPreview = "shot.png", ok = true, images = (1..6).map { image(200) })
        val out = FrameFitter.encodeWithin(env(ev), legacy)
        assertTrue(sealed(out) <= legacy)
        val got = decode(out) as ToolEvent
        assertTrue(got.images.isEmpty())
        assertEquals("shot.png", got.inputPreview)
        assertEquals(true, got.ok)
    }

    @Test
    fun a_file_body_over_the_cap_becomes_the_too_large_refusal() {
        val fc = FileContent("/w", "s", "a.pdf", base64 = "A".repeat(1_500_000), mediaType = "application/pdf", totalBytes = 1_125_000)
        val got = decode(FrameFitter.encodeWithin(env(fc), legacy)) as FileContent
        assertFalse(got.ok)
        assertNull(got.base64)
        assertTrue(got.error!!.contains("too large"), got.error)
        assertEquals("a.pdf", got.path)
    }

    @Test
    fun a_text_file_over_the_cap_is_clipped_and_marked() {
        val fc = FileContent("/w", "s", "big.log", text = "y".repeat(1_200_000), totalBytes = 1_200_000)
        val out = FrameFitter.encodeWithin(env(fc), legacy)
        assertTrue(sealed(out) <= legacy)
        val got = decode(out) as FileContent
        assertTrue(got.truncated)
        assertTrue(got.text!!.length in 1 until 1_200_000)
    }

    @Test
    fun a_diff_over_the_cap_is_clipped_on_a_line_boundary_and_marked() {
        // audit 2026-10-04 D: FileDiff was not shrinkable, so an escape-heavy diff went out as is and dropped the link
        val line = "+" + "\u001b[0m".repeat(50) + "\n"
        // 202 characters per line, 453 bytes once JSON-escaped: 4000 lines ≈ 0.8M chars but 1.8 MB on the wire
        val fd = FileDiff("/w", "s", "a.log", diff = line.repeat(4_000), adds = 4_000)
        val reports = mutableListOf<String>()
        val out = FrameFitter.encodeWithin(env(fd), legacy) { reports += it }
        assertTrue(sealed(out) <= legacy, "sealed ${sealed(out)} B")
        val got = decode(out) as FileDiff
        assertTrue(got.ok)
        assertTrue(got.truncated)
        assertTrue(got.diff!!.isNotEmpty() && got.diff!!.endsWith("\n"))
        assertTrue(fd.diff!!.startsWith(got.diff!!))
        assertEquals(4_000, got.adds)
        assertTrue("shrunk" in reports.single(), reports.single())
    }

    /** A project listing over the cap used to be sent as it was: the link dropped on every tap of that
     *  project and the list never opened. Previews go first, then the oldest rows (rows are newest-first). */
    @Test
    fun a_session_list_over_the_cap_sheds_previews_then_its_oldest_rows() {
        fun summary(i: Int, prompt: String) =
            SessionSummary("s$i", "title $i", prompt, messageCount = 1, cwd = "/w", lastModified = 10_000L - i)
        val previews = Sessions("/w", (0 until 40).map { summary(it, "p".repeat(60_000)) })
        val reports = mutableListOf<String>()
        val out = FrameFitter.encodeWithin(env(previews), legacy) { reports += it }
        assertTrue(sealed(out) <= legacy)
        val got = decode(out) as Sessions
        assertEquals(40, got.items.size)
        assertTrue(got.items.all { it.firstPrompt.isEmpty() && it.title.isNotEmpty() })
        assertTrue("shrunk" in reports.single(), reports.single())

        val many = Sessions("/w", (0 until 12_000).map { summary(it, "") })
        val fitted = FrameFitter.encodeWithin(env(many), legacy)
        assertTrue(sealed(fitted) <= legacy)
        val kept = (decode(fitted) as Sessions).items
        assertTrue(kept.size in 1 until 12_000)
        assertEquals("s0", kept.first().sessionId)
    }

    @Test
    fun an_unshrinkable_frame_is_sent_as_is_and_reported() {
        val e = env(PocketError("boom", "z".repeat(1_200_000)))
        val reports = mutableListOf<String>()
        assertContentEquals(PocketJson.encodeToString(e).encodeToByteArray(), FrameFitter.encodeWithin(e, legacy) { reports += it })
        assertEquals(1, reports.size)
        assertTrue("cannot be shrunk" in reports[0], reports[0])
    }

    @Test
    fun a_client_that_declared_the_wire_ceiling_gets_the_whole_window() {
        val rows = (1L..6L).map { row(it, image = image(200)) }
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows, delta = true)), WIRE_MAX_FRAME_BYTES)) as ConvoHistory
        assertEquals(6, got.messages.count { it.images.isNotEmpty() })
        assertTrue(got.delta)
    }

    @Test
    fun declared_caps_are_clamped_to_what_the_wire_can_carry() {
        val cap = RequestRouter.ClientCapsHolder
        assertEquals(LEGACY_CLIENT_MAX_FRAME_BYTES, cap.frameCap(0)) // an old build never sends the field
        assertEquals(LEGACY_CLIENT_MAX_FRAME_BYTES, cap.frameCap(-5))
        assertEquals(WIRE_MAX_FRAME_BYTES, cap.frameCap(WIRE_MAX_FRAME_BYTES))
        assertEquals(WIRE_MAX_FRAME_BYTES, cap.frameCap(64L shl 20)) // the relay drops anything larger anyway
        assertEquals(RequestRouter.ClientCapsHolder.MIN_FRAME_BYTES, cap.frameCap(10))
        assertEquals(2L shl 20, cap.frameCap(2L shl 20))
    }

    // ── the history soft cap (docs/design/SLOW-LINK-RESILIENCE.md 3.3) ───────────────────────────────
    // Frames here FIT the client (the wire ceiling is declared, so the hard cap never moves): they are only heavy
    // for a lossy link. The one loss allowed is whole rows from the oldest end, which the phone pages back in.

    private val softCap = FrameFitter.HISTORY_SOFT_CAP_BYTES
    private val floor = FrameFitter.HISTORY_SOFT_MIN_ROWS
    private fun plain(e: Envelope) = PocketJson.encodeToString(e).encodeToByteArray()
    private fun text(kb: Int) = "t".repeat(kb * 1000)
    private fun rowJson(r: HistoryMessage) = PocketJson.encodeToString(HistoryMessage.serializer(), r)

    @Test
    fun a_first_window_over_the_soft_cap_ships_its_newest_rows_and_leaves_the_oldest_to_paging() {
        val rows = (1L..100L).map { row(it, text = text(5)) } // ≈ 500 KB: fits every client, heavy for a lossy link
        val window = ConvoHistory("c", rows, lastSeq = 100, firstSeq = 1)
        val trims = mutableListOf<String>()
        val oversize = mutableListOf<String>()
        val out = FrameFitter.encodeWithin(env(window), WIRE_MAX_FRAME_BYTES, onSoftTrim = { trims += it }) { oversize += it }
        val got = decode(out) as ConvoHistory

        assertTrue(out.size <= softCap, "${out.size} B")
        val kept = got.messages.size
        assertTrue(kept in floor until rows.size, "kept $kept")
        assertEquals(rows.takeLast(kept), got.messages, "whole rows, from the oldest end only, each untouched")
        assertEquals(got.messages.first().seq, got.firstSeq, "paging resumes exactly where the window starts")
        assertTrue(got.hasMore)
        assertFalse(got.delta)
        assertEquals(100L, got.lastSeq)
        // the fewest rows that do it: keeping one more would put the frame back over the soft cap
        val oneMore = window.copy(messages = rows.takeLast(kept + 1), firstSeq = rows[rows.size - kept - 1].seq, hasMore = true)
        assertTrue(plain(env(oneMore)).size > softCap)

        val line = trims.single()
        assertTrue(line.startsWith("ConvoHistory:") && "100 rows / ${plain(env(window)).size} B" in line, line)
        assertTrue("→ $kept rows / ${out.size} B" in line, line)
        assertFalse("ttt" in line, "content-free: $line")
        assertTrue(oversize.isEmpty(), "a soft trim is not a frame-cap event")
    }

    @Test
    fun a_delta_over_the_soft_cap_becomes_a_window_anchored_on_its_first_kept_row() {
        val rows = (501L..560L).map { row(it, text = text(8)) } // a 60-row continuation, ≈ 480 KB
        val e = env(ConvoHistory("c", rows, lastSeq = 560, firstSeq = 501, delta = true, hasMore = false))
        val out = FrameFitter.encodeWithin(e, WIRE_MAX_FRAME_BYTES)
        assertTrue(out.size <= softCap, "${out.size} B")
        val got = decode(out) as ConvoHistory
        assertTrue(got.messages.size in floor until rows.size, "kept ${got.messages.size}")
        assertEquals(rows.takeLast(got.messages.size), got.messages)
        assertFalse(got.delta, "a continuation missing its oldest rows would leave a hole the phone cannot see")
        assertTrue(got.hasMore)
        assertEquals(got.messages.first().seq, got.firstSeq)
        assertEquals(560L, got.lastSeq)
    }

    @Test
    fun a_page_over_the_soft_cap_keeps_its_newest_rows_and_says_there_is_more() {
        val rows = (100L..199L).map { row(it, text = text(5)) }
        val out = FrameFitter.encodeWithin(env(ConvoHistoryPage("c", rows, firstSeq = 100, hasMore = false)), WIRE_MAX_FRAME_BYTES)
        assertTrue(out.size <= softCap, "${out.size} B")
        val got = decode(out) as ConvoHistoryPage
        assertTrue(got.messages.size in floor until rows.size, "kept ${got.messages.size}")
        assertEquals(rows.takeLast(got.messages.size), got.messages)
        assertEquals(199L, got.messages.last().seq)
        assertEquals(got.messages.first().seq, got.firstSeq, "the next page is asked for before the first kept row")
        assertTrue(got.hasMore)
    }

    @Test
    fun frames_under_the_soft_cap_are_unchanged_byte_for_byte() {
        val trims = mutableListOf<String>()
        // an ordinary window, comfortably past half the soft cap but under it
        val ordinary = env(ConvoHistory("c", (1L..200L).map { row(it, text = "o".repeat(600)) }, lastSeq = 200, firstSeq = 1, delta = true))
        assertTrue(plain(ordinary).size in (softCap / 2)..softCap, "${plain(ordinary).size} B")
        assertContentEquals(plain(ordinary), FrameFitter.encodeWithin(ordinary, WIRE_MAX_FRAME_BYTES, onSoftTrim = { trims += it }))
        // the soft cap is for history windows only: any other frame over it is the hard cap's business alone
        val file = env(FileContent("/w", "s", "a.log", text = "f".repeat(400_000), totalBytes = 400_000))
        assertContentEquals(plain(file), FrameFitter.encodeWithin(file, WIRE_MAX_FRAME_BYTES, onSoftTrim = { trims += it }))
        assertTrue(trims.isEmpty(), trims.toString())
    }

    @Test
    fun the_soft_cap_drops_rows_but_never_their_pictures() {
        val rows = (1L..40L).map { row(it, image = image(10)) } // 40 screenshots of 10 KB ≈ 400 KB
        val out = FrameFitter.encodeWithin(env(ConvoHistory("c", rows, lastSeq = 40, firstSeq = 1)), WIRE_MAX_FRAME_BYTES)
        assertTrue(out.size <= softCap, "${out.size} B")
        val got = decode(out) as ConvoHistory
        assertTrue(got.messages.size in floor until rows.size, "kept ${got.messages.size}")
        assertEquals(rows.takeLast(got.messages.size), got.messages, "kept rows arrive whole")
        assertTrue(got.messages.all { it.images.size == 1 && !it.imagesTruncated }, "no picture is shed for the soft cap")
    }

    @Test
    fun a_soft_trim_never_keeps_fewer_than_twenty_rows() {
        val rows = (1L..40L).map { row(it, text = text(30)) } // ≈ 1.2 MB: still over the soft cap at 20 rows
        val trims = mutableListOf<String>()
        val e = env(ConvoHistory("c", rows, lastSeq = 40, firstSeq = 1))
        val got = decode(FrameFitter.encodeWithin(e, WIRE_MAX_FRAME_BYTES, onSoftTrim = { trims += it })) as ConvoHistory
        assertEquals(rows.takeLast(floor), got.messages, "trimmed down to the floor and no further")
        assertEquals(21L, got.firstSeq)
        assertTrue(got.hasMore)
        assertEquals(1, trims.size)

        // a window already at the floor is left whole, however heavy
        val atFloor = env(ConvoHistory("c", rows.takeLast(floor), lastSeq = 40, firstSeq = 21, delta = true))
        assertContentEquals(plain(atFloor), FrameFitter.encodeWithin(atFloor, WIRE_MAX_FRAME_BYTES))
    }

    @Test
    fun a_window_without_cursors_is_never_soft_trimmed() {
        // a backend that does not page: nothing could bring a dropped row back
        val e = env(ConvoHistory("c", (1..60).map { HistoryMessage(ChatRole.ASSISTANT, text(8)) }))
        val trims = mutableListOf<String>()
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, WIRE_MAX_FRAME_BYTES, onSoftTrim = { trims += it }))
        assertTrue(trims.isEmpty())
    }

    /** 3.3 as written: when the first row the trim would keep carries no cursor (a compact summary on a paging
     *  backend), the window is left whole rather than anchored somewhere else. */
    @Test
    fun a_cut_that_would_start_on_a_row_without_a_cursor_leaves_the_window_whole() {
        val rows = (1L..60L).map { row(it, text = text(8)) }
        val kept = (decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows, lastSeq = 60, firstSeq = 1)), WIRE_MAX_FRAME_BYTES)) as ConvoHistory).messages.size
        val cut = rows.size - kept
        // the row at the cut becomes cursor-less, padded to the SAME encoded size so the cut cannot move
        val summary = rows[cut].copy(seq = null).let { it.copy(text = it.text + "s".repeat(rowJson(rows[cut]).length - rowJson(it).length)) }
        assertEquals(rowJson(rows[cut]).length, rowJson(summary).length)
        val e = env(ConvoHistory("c", rows.toMutableList().also { it[cut] = summary }, lastSeq = 60, firstSeq = 1))
        val trims = mutableListOf<String>()
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, WIRE_MAX_FRAME_BYTES, onSoftTrim = { trims += it }))
        assertTrue(trims.isEmpty())
    }

    @Test
    fun a_window_still_over_the_client_cap_after_the_soft_trim_goes_on_to_the_hard_cap() {
        val rows = (1L..40L).map { row(it, image = image(100)) } // 4 MB of screenshots for a 1 MiB client
        val trims = mutableListOf<String>()
        val oversize = mutableListOf<String>()
        val e = env(ConvoHistory("c", rows, lastSeq = 40, firstSeq = 1, delta = true))
        val out = FrameFitter.encodeWithin(e, legacy, onSoftTrim = { trims += it }) { oversize += it }
        assertTrue(sealed(out) <= legacy, "sealed ${sealed(out)} B")
        val got = decode(out) as ConvoHistory
        assertTrue(got.messages.size <= floor, "the soft trim took it to the floor first: ${got.messages.size}")
        assertEquals(40L, got.messages.last().seq)
        assertEquals(got.messages.first().seq, got.firstSeq)
        assertTrue(got.hasMore)
        assertFalse(got.delta)
        assertTrue("→ $floor rows" in trims.single(), trims.single())
        assertTrue("shrunk to" in oversize.single(), oversize.single())
    }
}
