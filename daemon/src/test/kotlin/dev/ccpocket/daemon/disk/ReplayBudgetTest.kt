package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.QuestionAnswer
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The frame-safety byte budget shared by the Claude and Codex transcript replays (issue #81). */
class ReplayBudgetTest {

    private fun msg(text: String) = HistoryMessage(ChatRole.ASSISTANT, text)

    @Test
    fun keeps_everything_when_under_budget() {
        val msgs = listOf(msg("a"), msg("bb"), msg("ccc"))
        assertEquals(msgs, ReplayBudget.fit(msgs, maxBytes = 1000))
    }

    @Test
    fun drops_oldest_and_truncates_the_straddling_row_preserving_order() {
        // three 10-byte rows, budget 25: newest two stay whole (20 B), the oldest is truncated to 5 B
        val msgs = listOf(msg("0".repeat(10)), msg("1".repeat(10)), msg("2".repeat(10)))
        val out = ReplayBudget.fit(msgs, maxBytes = 25)
        assertEquals(3, out.size)
        assertEquals(5, out[0].text.length) // oldest — the straddling row, truncated
        assertEquals("1".repeat(10), out[1].text) // whole
        assertEquals("2".repeat(10), out[2].text) // newest, whole
        assertTrue(out.sumOf { ReplayBudget.utf8Size(it.text) } <= 25)
    }

    @Test
    fun never_splits_a_surrogate_pair() {
        // an emoji is a surrogate pair = 4 UTF-8 bytes; a 3-byte budget must cut before it, not mid-pair
        val emoji = "😀" // 😀
        val out = ReplayBudget.fit(listOf(msg("ab$emoji")), maxBytes = 3)
        assertEquals("ab", out[0].text) // the 2-byte "ab" fits; the 4-byte emoji does not
        assertTrue(out.all { ReplayBudget.utf8Size(it.text) <= 3 })
    }

    // ---- issue #33: sub-agent output / answers bytes must not ride outside the budget ----

    @Test
    fun subagent_output_counts_against_the_budget() {
        // 20 rows, tiny text but a 100-byte report each: before #33 all 20 rode through on their
        // 1-byte texts, blowing the frame with 2 KB of uncounted reports.
        // Since #254's strip-then-remeasure the budget takes the REPORTS rather than the ROWS: only
        // the newest few keep their expandable detail, the rest keep their (1-byte) conversation text
        // with output shed. That is the intended trade — text is the last thing sacrificed — and the
        // guarantee #33 exists for is unchanged: no output byte rides outside the budget.
        val rows = List(20) { HistoryMessage(ChatRole.ASSISTANT, "t", tool = "Task", ok = true, output = "r".repeat(100)) }
        val out = ReplayBudget.fit(rows, maxBytes = 350)
        assertTrue(out.sumOf { ReplayBudget.payloadSize(it) } <= 350)
        // 3 rows' worth of (text + report) fits in 350 B; the other 17 survive stripped, not deleted
        assertEquals(3, out.count { it.output != null }, "reports must be shed, never smuggled past the budget")
        assertTrue(out.all { it.text == "t" }, "conversation text is the last thing to go")
    }

    @Test
    fun answers_count_against_the_budget() {
        val row = HistoryMessage(
            ChatRole.ASSISTANT, "q?", tool = "AskUserQuestion",
            answers = listOf(QuestionAnswer("q".repeat(30), "备".repeat(10))), // 30 + 30 B
        )
        assertEquals(2L + 30L + 30L, ReplayBudget.payloadSize(row))
        // a budget that covers the text but not the answers must not keep the row whole
        val out = ReplayBudget.fit(listOf(row), maxBytes = 10)
        assertTrue(out.sumOf { ReplayBudget.payloadSize(it) } <= 10)
    }

    @Test
    fun straddling_row_sheds_output_and_answers_and_clips_text() {
        val straddler = HistoryMessage(
            ChatRole.ASSISTANT, "0".repeat(10), tool = "Task", ok = true,
            output = "r".repeat(50), answers = listOf(QuestionAnswer("q", "a")),
        )
        val newest = msg("1".repeat(10))
        val out = ReplayBudget.fit(listOf(straddler, newest), maxBytes = 15)
        assertEquals(2, out.size)
        assertEquals("1".repeat(10), out[1].text) // newest whole
        assertEquals("0".repeat(5), out[0].text) // straddler: text clipped to the 5 B left
        assertNull(out[0].output) // heavy optionals shed, not smuggled past the budget
        assertNull(out[0].answers)
        assertTrue(out.sumOf { ReplayBudget.payloadSize(it) } <= 15)
    }

    @Test
    fun worst_case_output_heavy_history_stays_under_the_4MiB_frame() {
        // the #33 scenario (#77 × #81 first co-occurrence): 100 rows × (2000-CJK text + 4000-CJK
        // sub-agent report) ≈ 1.8 MB payload. After fit(), the REAL wire encoding (PocketJson
        // Envelope, + 25 B E2E sealing overhead) must stay under the relay's 4 MiB frame cap.
        val rows = List(100) {
            HistoryMessage(ChatRole.ASSISTANT, "备".repeat(2000), tool = "Task", ok = true, output = "备".repeat(4000))
        }
        val fitted = ReplayBudget.fit(rows)
        assertTrue(fitted.sumOf { ReplayBudget.payloadSize(it) } <= ReplayBudget.MAX_FRAME_TEXT_BYTES)
        val wire = PocketJson.encodeToString(Envelope("id", 0L, body = ConvoHistory("c", fitted)))
        val sealedBytes = wire.toByteArray(Charsets.UTF_8).size + 25
        assertTrue(sealedBytes < 4 * 1024 * 1024, "sealed frame $sealedBytes B must be < 4 MiB")
    }

    // ---- issue #254: replayed prompt images are the heaviest thing on a row ----

    private fun img(bytes: Int) = ImageData("image/jpeg", "A".repeat(bytes))

    @Test
    fun image_base64_counts_against_the_budget() {
        val row = HistoryMessage(ChatRole.USER, "look", images = listOf(img(100), img(50)))
        assertEquals(4L + 150L, ReplayBudget.payloadSize(row))
    }

    @Test
    fun oversized_single_image_is_capped_out_and_flagged() {
        val row = HistoryMessage(
            ChatRole.USER, "look",
            images = listOf(img((ReplayBudget.MAX_IMAGE_BASE64_BYTES + 1).toInt()), img(10)),
        )
        val out = ReplayBudget.capImages(row)
        assertEquals(1, out.images.size) // the small one survives — capping is per image, not per row
        assertEquals(10, out.images[0].base64.length)
        assertTrue(out.imagesTruncated)
        assertEquals("look", out.text) // text is never the thing sacrificed for an image
    }

    @Test
    fun image_count_per_message_is_capped_and_flagged() {
        val row = HistoryMessage(ChatRole.USER, "6 shots", images = List(6) { img(10) })
        val out = ReplayBudget.capImages(row)
        assertEquals(ReplayBudget.MAX_IMAGES_PER_MESSAGE, out.images.size)
        assertTrue(out.imagesTruncated)
    }

    @Test
    fun a_row_within_both_caps_is_returned_untouched() {
        val row = HistoryMessage(ChatRole.USER, "fine", images = listOf(img(10), img(20)))
        assertEquals(row, ReplayBudget.capImages(row)) // identical, and imagesTruncated stays false
        assertEquals(row, ReplayBudget.fit(listOf(row), maxBytes = 1000).single())
    }

    @Test
    fun fit_applies_the_image_caps_so_one_huge_attachment_cannot_evict_the_conversation() {
        // without capImages the 700 KB blob would spend the whole budget and shed every older row
        val huge = HistoryMessage(ChatRole.USER, "hi", images = listOf(img(700_000)))
        val older = List(5) { msg("row$it") }
        val out = ReplayBudget.fit(older + huge, maxBytes = 100_000)
        assertEquals(6, out.size, "the conversation must survive an oversized attachment")
        assertTrue(out.last().images.isEmpty())
        assertTrue(out.last().imagesTruncated)
    }

    @Test
    fun a_multi_image_turn_under_the_per_image_cap_still_cannot_evict_older_rows() {
        // the gap capImages alone does NOT close: three 550 KB images are each UNDER the per-image
        // ceiling and within the count cap, so they ride through untouched — but 1.65 MB is past the
        // whole 1.5 MB frame budget. The newest row therefore straddles on its PICTURES while its
        // words fit trivially. Shedding them and re-measuring is what keeps the five older rows alive;
        // zeroing the budget at the straddle (the pre-fix behavior) left a one-row transcript.
        val newest = HistoryMessage(ChatRole.USER, "here are the three shots", images = List(3) { img(550_000) })
        val older = List(5) { msg("older-$it") }
        val out = ReplayBudget.fit(older + newest)

        assertEquals(6, out.size, "an image-heavy newest row must not evict the conversation behind it")
        assertEquals(older.map { it.text }, out.dropLast(1).map { it.text }) // older rows whole, in order
        assertEquals("here are the three shots", out.last().text) // text survives — images are shed first
        assertTrue(out.last().images.isEmpty())
        assertTrue(out.last().imagesTruncated) // and the loss is announced, never silent
        assertTrue(out.sumOf { ReplayBudget.payloadSize(it) } <= ReplayBudget.MAX_FRAME_TEXT_BYTES)
    }

    @Test
    fun straddling_row_sheds_its_images_and_announces_it() {
        val straddler = HistoryMessage(ChatRole.USER, "0".repeat(10), images = listOf(img(20)))
        val newest = msg("1".repeat(10))
        val out = ReplayBudget.fit(listOf(straddler, newest), maxBytes = 15)
        assertEquals(2, out.size)
        assertEquals("0".repeat(5), out[0].text)
        assertTrue(out[0].images.isEmpty())
        assertTrue(out[0].imagesTruncated) // shed images are ALWAYS announced, unlike output/answers
        assertTrue(out.sumOf { ReplayBudget.payloadSize(it) } <= 15)
    }

    @Test
    fun an_image_heavy_history_still_fits_the_4MiB_frame() {
        // 100 turns each carrying the per-message maximum of near-ceiling images ≈ 240 MB raw —
        // fit() must land under the budget, and the REAL wire encoding under the relay's frame cap
        val rows = List(100) {
            HistoryMessage(
                ChatRole.USER, "备".repeat(500),
                images = List(ReplayBudget.MAX_IMAGES_PER_MESSAGE) { img((ReplayBudget.MAX_IMAGE_BASE64_BYTES - 1).toInt()) },
            )
        }
        val fitted = ReplayBudget.fit(rows)
        assertTrue(fitted.sumOf { ReplayBudget.payloadSize(it) } <= ReplayBudget.MAX_FRAME_TEXT_BYTES)
        val wire = PocketJson.encodeToString(Envelope("id", 0L, body = ConvoHistory("c", fitted)))
        val sealedBytes = wire.toByteArray(Charsets.UTF_8).size + 25
        assertTrue(sealedBytes < 4 * 1024 * 1024, "sealed frame $sealedBytes B must be < 4 MiB")
    }

    @Test
    fun utf8_size_counts_multibyte_and_surrogates() {
        assertEquals(1, ReplayBudget.utf8Size("a"))
        assertEquals(3, ReplayBudget.utf8Size("备")) // CJK = 3 bytes
        assertEquals(4, ReplayBudget.utf8Size("😀")) // emoji surrogate pair = 4 bytes
        // matches the JDK encoder we would otherwise allocate
        assertEquals("ab备😀".toByteArray(Charsets.UTF_8).size.toLong(), ReplayBudget.utf8Size("ab备😀"))
    }
}
