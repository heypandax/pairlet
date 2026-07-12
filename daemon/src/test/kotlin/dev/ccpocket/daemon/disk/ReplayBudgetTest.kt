package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.HistoryMessage
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
        // 20 rows, tiny text but a 100-byte report each: only the newest rows whose FULL payload
        // (text + output) fits may survive — before #33 all 20 rode through on their 1-byte texts
        val rows = List(20) { HistoryMessage(ChatRole.ASSISTANT, "t", tool = "Task", ok = true, output = "r".repeat(100)) }
        val out = ReplayBudget.fit(rows, maxBytes = 350)
        assertTrue(out.size < rows.size, "output-heavy rows must be shed, kept ${out.size}")
        assertTrue(out.sumOf { ReplayBudget.payloadSize(it) } <= 350)
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

    @Test
    fun utf8_size_counts_multibyte_and_surrogates() {
        assertEquals(1, ReplayBudget.utf8Size("a"))
        assertEquals(3, ReplayBudget.utf8Size("备")) // CJK = 3 bytes
        assertEquals(4, ReplayBudget.utf8Size("😀")) // emoji surrogate pair = 4 bytes
        // matches the JDK encoder we would otherwise allocate
        assertEquals("ab备😀".toByteArray(Charsets.UTF_8).size.toLong(), ReplayBudget.utf8Size("ab备😀"))
    }
}
