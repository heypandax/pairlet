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
}
