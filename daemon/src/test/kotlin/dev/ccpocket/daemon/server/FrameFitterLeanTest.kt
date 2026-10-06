package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.media.ImagePreviews
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import kotlinx.serialization.encodeToString
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lean history at the per-connection sealer (SLOW-LINK-RESILIENCE §6): a connection that declared it gets
 * picture previews and a byte-bounded window; one that did not gets byte for byte what it got before.
 */
class FrameFitterLeanTest {

    @BeforeTest fun reset() = ImagePreviews.clearForTest()

    private val cap = WIRE_MAX_FRAME_BYTES
    private val short = FrameFitter.Lean(shortHistoryWindow = true)
    private val previews = FrameFitter.Lean(imagePreviews = true)
    private val both = FrameFitter.Lean(imagePreviews = true, shortHistoryWindow = true)

    private fun env(body: Frame) = Envelope("1", 0L, body = body)
    private fun decode(bytes: ByteArray): Frame = PocketJson.decodeFromString<Envelope>(bytes.decodeToString()).body
    private fun plain(e: Envelope) = PocketJson.encodeToString(e).encodeToByteArray()

    /** One ~0.7 KB row, the measured median of a real session. */
    private fun row(seq: Long?, role: ChatRole = ChatRole.ASSISTANT, text: String = "row $seq " + "x".repeat(640), images: List<ImageData> = emptyList()) =
        HistoryMessage(role, text, tool = if (role == ChatRole.TOOL) "Read" else null, images = images, seq = seq)

    private fun window(n: Int, from: Long = 1) = (from until from + n).map { row(it) }

    private fun screenshot(seed: Int = 3): ImageData {
        val img = BufferedImage(1024, 633, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 633) for (x in 0 until 1024) {
            val block = if ((x / 40 + y / 24 + seed) % 7 == 0) 0x202830 else 0
            img.setRGB(x, y, (((x * 255 / 1023) shl 16) or ((y * 255 / 632) shl 8) or (((x + y + seed) / 4) % 256)) xor block)
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", bos)
        return ImageData("image/jpeg", Base64.getEncoder().encodeToString(bos.toByteArray()))
    }

    // ---- nothing declared: nothing changes ----

    @Test
    fun a_connection_that_declared_nothing_gets_the_frame_it_always_got() {
        val e = env(ConvoHistory("c", window(100).mapIndexed { i, r -> if (i == 40) r.copy(images = listOf(screenshot())) else r },
            lastSeq = 100, firstSeq = 1, hasMore = true))
        val notes = mutableListOf<String>()
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, cap, onSoftTrim = { notes += it }) { notes += it })
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, cap, FrameFitter.Lean.OFF))
        assertTrue(notes.isEmpty())
    }

    // ---- the short first window ----

    @Test
    fun a_first_window_is_cut_to_the_newest_rows_that_fit_the_budget_and_anchored_for_paging() {
        val rows = window(100)
        val e = env(ConvoHistory("c", rows, lastSeq = 100, firstSeq = 1, hasMore = false))
        val notes = mutableListOf<String>()
        val out = FrameFitter.encodeWithin(e, cap, short, onSoftTrim = { notes += it })
        assertTrue(out.size <= FrameFitter.LEAN_FIRST_WINDOW_BYTES, "${out.size} B")
        val got = decode(out) as ConvoHistory
        assertTrue(got.messages.size in FrameFitter.LEAN_MIN_ROWS until 100, "${got.messages.size} rows")
        assertEquals(rows.takeLast(got.messages.size), got.messages) // the NEWEST rows, whole and in order
        assertEquals(got.messages.first().seq, got.firstSeq)
        assertTrue(got.hasMore)
        assertEquals(100L, got.lastSeq) // the delta cursor is the transcript's, not the window's
        assertFalse(got.delta)
        // as many rows as the budget holds: one more would not have fit
        val oneMore = env(ConvoHistory("c", rows.takeLast(got.messages.size + 1), lastSeq = 100, firstSeq = rows[rows.size - got.messages.size - 1].seq, hasMore = true))
        assertTrue(plain(oneMore).size > FrameFitter.LEAN_FIRST_WINDOW_BYTES)
        assertEquals(1, notes.size)
        assertTrue("lean history budget" in notes[0] && "left to paging" in notes[0], notes[0])
    }

    @Test
    fun a_window_already_inside_the_budget_is_the_plain_encoding() {
        val e = env(ConvoHistory("c", window(20), lastSeq = 20, firstSeq = 1))
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, cap, both))
    }

    @Test
    fun the_floor_keeps_a_first_screen_even_when_those_rows_alone_are_over_the_budget() {
        val heavy = (1L..40L).map { row(it, text = "y".repeat(8_000)) } // 8 KB a row: 4 rows would fill the budget
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", heavy, lastSeq = 40, firstSeq = 1)), cap, short)) as ConvoHistory
        assertEquals(FrameFitter.LEAN_MIN_ROWS, got.messages.size)
        assertEquals(33L, got.firstSeq)
        assertTrue(got.hasMore)
    }

    @Test
    fun the_cut_never_falls_inside_one_source_line() {
        // rows 1..60, but the reply at line 30 produced four rows (text + three tool calls) sharing its cursor
        val rows = (1L..29L).map { row(it) } + List(4) { row(30, text = "part $it " + "z".repeat(640)) } + (31L..60L).map { row(it) }
        for (extra in 0..3) {
            // nudge the sizes so the byte cut lands on each position inside the group in turn
            val padded = rows.dropLast(1) + row(60, text = "q".repeat(600 + extra * 700))
            val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", padded, lastSeq = 60, firstSeq = 1)), cap, short)) as ConvoHistory
            val first = got.messages.first().seq!!
            val before = padded[padded.size - got.messages.size - 1].seq
            assertTrue(first != before, "window starts at $first, the row before it is $before")
            assertEquals(first, got.firstSeq)
        }
    }

    @Test
    fun a_leading_row_without_a_cursor_is_left_to_the_older_page() {
        // a compact summary carries no cursor: a window must not start on it, and it is on the older page by its
        // line. The heavy row right before it makes the byte cut land exactly on the summary.
        val rows = window(29) + row(30, text = "h".repeat(20_000)) + row(null, text = "summary " + "s".repeat(640)) + window(30, from = 32)
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows, lastSeq = 61, firstSeq = 1)), cap, short)) as ConvoHistory
        assertEquals(32L, got.messages.first().seq)
        assertEquals(32L, got.firstSeq)
        assertEquals(30, got.messages.size)
        assertTrue(got.hasMore)
    }

    @Test
    fun a_backend_that_does_not_page_keeps_its_whole_window() {
        val rows = (1..100).map { row(null, text = "n$it " + "x".repeat(640)) }
        val e = env(ConvoHistory("c", rows))
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, cap, short))
    }

    // ---- deltas and pages ----

    @Test
    fun a_modest_delta_goes_out_whole_and_stays_a_delta() {
        val e = env(ConvoHistory("c", window(60, from = 41), lastSeq = 100, firstSeq = 41, delta = true)) // ~45 KB: over the first-window budget, under the delta one
        assertTrue(plain(e).size in (FrameFitter.LEAN_FIRST_WINDOW_BYTES + 1) until FrameFitter.LEAN_DELTA_BYTES)
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, cap, short))
    }

    @Test
    fun a_long_absence_delta_becomes_a_first_window() {
        val e = env(ConvoHistory("c", window(200, from = 101), lastSeq = 300, firstSeq = 101, delta = true))
        val got = decode(FrameFitter.encodeWithin(e, cap, short)) as ConvoHistory
        assertFalse(got.delta, "a continuation missing its oldest rows would leave a hole")
        assertTrue(got.hasMore)
        assertEquals(got.messages.first().seq, got.firstSeq)
        assertEquals(300L, got.lastSeq)
        assertEquals(300L, got.messages.last().seq)
    }

    @Test
    fun an_older_page_keeps_the_rows_nearest_the_window_and_stays_pageable() {
        val e = env(ConvoHistoryPage("c", window(100), firstSeq = 1, hasMore = false))
        val out = FrameFitter.encodeWithin(e, cap, short)
        assertTrue(out.size <= FrameFitter.LEAN_PAGE_BYTES)
        val got = decode(out) as ConvoHistoryPage
        assertEquals(100L, got.messages.last().seq) // adjacent to the window that asked
        assertEquals(got.messages.first().seq, got.firstSeq)
        assertTrue(got.hasMore)
        assertTrue(got.messages.size > FrameFitter.LEAN_MIN_ROWS)
    }

    // ---- previews ----

    @Test
    fun pictures_travel_as_previews_on_history_rows_pages_and_live_tool_results() {
        val shot = screenshot()
        val history = env(ConvoHistory("c", listOf(row(1, ChatRole.TOOL, images = listOf(shot)), row(2, ChatRole.USER, images = listOf(shot))), lastSeq = 2, firstSeq = 1))
        val h = decode(FrameFitter.encodeWithin(history, cap, previews)) as ConvoHistory
        val tool = h.messages[0].images.single(); val user = h.messages[1].images.single()
        assertNotNull(tool.ref); assertEquals(tool.ref, user.ref)
        assertTrue(tool.base64.length * 3 < shot.base64.length)
        assertTrue(user.base64.length > tool.base64.length, "a prompt attachment is shown larger")
        assertEquals(shot, ImagePreviews.full("c", tool.ref!!)) // the full version is one request away

        val page = decode(FrameFitter.encodeWithin(env(ConvoHistoryPage("c", listOf(row(1, ChatRole.TOOL, images = listOf(shot))), firstSeq = 1)), cap, previews)) as ConvoHistoryPage
        assertEquals(tool, page.messages.single().images.single())

        val live = ToolEvent("c2", 7, ToolPhase.RESULT, "Read", ok = true, toolUseId = "t1", images = listOf(shot))
        val l = decode(FrameFitter.encodeWithin(env(live), cap, previews)) as ToolEvent
        assertEquals(tool.ref, l.images.single().ref)
        assertEquals(shot, ImagePreviews.full("c2", tool.ref!!))
    }

    @Test
    fun a_frame_without_pictures_is_untouched_by_the_preview_stage() {
        val e = env(ConvoHistory("c", window(10), lastSeq = 10, firstSeq = 1))
        assertContentEquals(plain(e), FrameFitter.encodeWithin(e, cap, previews))
        val t = env(ToolEvent("c", 1, ToolPhase.START, "Bash", inputPreview = "ls"))
        assertContentEquals(plain(t), FrameFitter.encodeWithin(t, cap, previews))
    }

    @Test
    fun the_budget_measures_the_frame_with_its_previews_not_with_the_full_pictures() {
        // a screenshot fifteen rows back: its full rendition alone is more than the whole budget
        val rows = window(40).mapIndexed { i, r -> if (i == 25) r.copy(role = ChatRole.TOOL, tool = "Read", images = listOf(screenshot())) else r }
        val e = env(ConvoHistory("c", rows, lastSeq = 40, firstSeq = 1))
        assertTrue(plain(e).size > 2 * FrameFitter.LEAN_FIRST_WINDOW_BYTES, "full frame ${plain(e).size} B")
        // without previews the window has to stop right after the picture's row; with them that row fits, and more behind it
        val withoutPreviews = decode(FrameFitter.encodeWithin(e, cap, short)) as ConvoHistory
        assertEquals(14, withoutPreviews.messages.size)
        assertTrue(withoutPreviews.messages.none { it.images.isNotEmpty() }, "kept a full picture")
        val withPreviews = decode(FrameFitter.encodeWithin(e, cap, both)) as ConvoHistory
        assertTrue(withPreviews.messages.size > 15, "${withPreviews.messages.size} rows")
        val picture = withPreviews.messages.single { it.images.isNotEmpty() }.images.single()
        assertNotNull(picture.ref)
        assertTrue(FrameFitter.encodeWithin(e, cap, both).size <= FrameFitter.LEAN_FIRST_WINDOW_BYTES, "lean frame ${FrameFitter.encodeWithin(e, cap, both).size} B, preview ${picture.base64.length} B")
    }

    @Test
    fun a_full_picture_too_big_for_the_connection_becomes_an_unavailable_answer_not_a_dropped_link() {
        val small = 256L * 1024 // a connection that declared a small frame cap
        val huge = dev.ccpocket.protocol.ImageContent("c", "r".repeat(22), image = ImageData("image/png", "A".repeat(400_000)), requestId = "q1")
        val notes = mutableListOf<String>()
        val out = FrameFitter.encodeWithin(env(huge), small, both) { notes += it }
        assertTrue(FrameFitter.fits(out.size, small))
        val got = decode(out) as dev.ccpocket.protocol.ImageContent
        assertNull(got.image)
        assertEquals(dev.ccpocket.protocol.ImageContent.ERROR_UNAVAILABLE, got.error)
        assertEquals("q1", got.requestId) // still the answer to that request
        assertEquals(1, notes.size)
        // one that fits is untouched
        val ok = dev.ccpocket.protocol.ImageContent("c", "r".repeat(22), image = ImageData("image/png", "A".repeat(100_000)))
        assertContentEquals(plain(env(ok)), FrameFitter.encodeWithin(env(ok), small, both))
    }

    @Test
    fun the_soft_cap_still_guards_a_connection_that_only_asked_for_previews() {
        val rows = (1L..60L).map { row(it, text = "w".repeat(9_000)) } // 540 KB of text, no pictures
        val notes = mutableListOf<String>()
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows, lastSeq = 60, firstSeq = 1)), cap, previews, onSoftTrim = { notes += it })) as ConvoHistory
        assertTrue(got.messages.size in FrameFitter.HISTORY_SOFT_MIN_ROWS until 60)
        assertTrue("history soft cap" in notes.single(), notes.toString())
    }

    @Test
    fun the_soft_cap_does_not_split_a_source_line_either() {
        val rows = (1L..40L).map { row(it, text = "w".repeat(9_000)) }.toMutableList()
        // lines 11..14 collapse into one four-row line right where the 256 KB cut will land
        for (i in 10..13) rows[i] = rows[i].copy(seq = 11)
        val got = decode(FrameFitter.encodeWithin(env(ConvoHistory("c", rows, lastSeq = 40, firstSeq = 1)), cap)) as ConvoHistory
        val keptFrom = rows.size - got.messages.size
        assertTrue(keptFrom == 0 || rows[keptFrom].seq != rows[keptFrom - 1].seq, "cut at $keptFrom splits line ${rows[keptFrom].seq}")
        assertNull(got.messages.firstOrNull { it.seq == null })
    }
}
