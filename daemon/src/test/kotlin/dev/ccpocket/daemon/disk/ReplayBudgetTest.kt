package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun utf8_size_counts_multibyte_and_surrogates() {
        assertEquals(1, ReplayBudget.utf8Size("a"))
        assertEquals(3, ReplayBudget.utf8Size("备")) // CJK = 3 bytes
        assertEquals(4, ReplayBudget.utf8Size("😀")) // emoji surrogate pair = 4 bytes
        // matches the JDK encoder we would otherwise allocate
        assertEquals("ab备😀".toByteArray(Charsets.UTF_8).size.toLong(), ReplayBudget.utf8Size("ab备😀"))
    }
}
