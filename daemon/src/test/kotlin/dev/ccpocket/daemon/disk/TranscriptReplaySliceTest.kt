package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #147: the incremental-reattach seq semantics — delta continuation, every fallback-to-full
 *  path, and the older-history paging window. */
class TranscriptReplaySliceTest {

    private fun tmpFile(name: String): Path = Files.createTempDirectory("ccp-slice").resolve(name)

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":"$text"}}"""
    private fun assistant(text: String) = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun full_read_carries_cursor_and_window_anchors() {
        val f = tmpFile("s.jsonl")
        f.writeText(listOf(user("q1"), assistant("a1"), user("q2"), assistant("a2")).joinToString("\n") + "\n")

        val slice = TranscriptReplay.slice(f, sinceSeq = null)

        assertEquals(4, slice.messages.size)
        assertFalse(slice.delta)
        assertEquals(4L, slice.lastSeq)  // cursor = the file's line count
        assertEquals(1L, slice.firstSeq) // first kept row's source line
        assertFalse(slice.hasMore)
    }

    @Test
    fun delta_exactly_continues_after_the_cursor() {
        val f = tmpFile("s.jsonl")
        f.writeText(listOf(user("q1"), assistant("a1")).joinToString("\n") + "\n")
        val first = TranscriptReplay.slice(f, sinceSeq = null)
        assertEquals(2L, first.lastSeq)

        // two more turns land while the client is away
        f.appendText(user("q2") + "\n" + assistant("a2") + "\n")
        val delta = TranscriptReplay.slice(f, sinceSeq = first.lastSeq)

        assertTrue(delta.delta)
        assertEquals(listOf("q2", "a2"), delta.messages.map { it.text })
        assertEquals(4L, delta.lastSeq)  // the new cursor — chains onto the next reconnect
        assertEquals(3L, delta.firstSeq)
        assertFalse(delta.hasMore)
    }

    @Test
    fun delta_of_nothing_is_an_empty_delta_not_a_wipe() {
        val f = tmpFile("s.jsonl")
        f.writeText(user("q1") + "\n")
        val cursor = TranscriptReplay.slice(f, sinceSeq = null).lastSeq

        val delta = TranscriptReplay.slice(f, sinceSeq = cursor)

        assertTrue(delta.delta) // callers skip emitting an empty delta — never an empty FULL (= /clear)
        assertTrue(delta.messages.isEmpty())
        assertEquals(cursor, delta.lastSeq)
    }

    @Test
    fun stale_cursor_past_the_file_falls_back_to_full() {
        // the file shrank (rewritten/cleared) or the cursor belongs to another transcript
        val f = tmpFile("s.jsonl")
        f.writeText(listOf(user("q1"), assistant("a1")).joinToString("\n") + "\n")

        val slice = TranscriptReplay.slice(f, sinceSeq = 99)

        assertFalse(slice.delta)
        assertEquals(2, slice.messages.size) // the full window, replacing the client's stale view
    }

    @Test
    fun nonpositive_cursor_is_a_full_replay_capability_flag() {
        // a new client with no transcript sends 0 — full replay, but delta-capable (observe ticks)
        val f = tmpFile("s.jsonl")
        f.writeText(user("q1") + "\n")
        val slice = TranscriptReplay.slice(f, sinceSeq = 0)
        assertFalse(slice.delta)
        assertEquals(1, slice.messages.size)
    }

    @Test
    fun late_patch_on_an_already_delivered_row_falls_back_to_full() {
        // a sub-agent card (line 2) is delivered, the client disconnects, then its tool_result lands
        // (line 3) — a pure-append delta could not carry the mutation, so the daemon replays in full
        val f = tmpFile("s.jsonl")
        f.writeText(
            listOf(
                user("run the agent"),
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Task","input":{"subagent_type":"Explore","description":"scan"}}]}}""",
            ).joinToString("\n") + "\n",
        )
        val cursor = TranscriptReplay.slice(f, sinceSeq = null).lastSeq
        f.appendText(
            """{"type":"user","toolUseResult":{},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"the report"}]}}""" + "\n" +
                assistant("done") + "\n",
        )

        val slice = TranscriptReplay.slice(f, sinceSeq = cursor)

        assertFalse(slice.delta) // full fallback
        val card = slice.messages.first { it.role == ChatRole.TOOL }
        assertEquals(true, card.ok) // and the full window carries the patched card
        assertEquals("the report", card.output)
    }

    @Test
    fun patch_entirely_inside_the_delta_window_stays_a_delta() {
        val f = tmpFile("s.jsonl")
        f.writeText(user("q1") + "\n")
        val cursor = TranscriptReplay.slice(f, sinceSeq = null).lastSeq
        // the whole sub-agent run (tool_use + its tool_result) happens after the cursor
        f.appendText(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Task","input":{"subagent_type":"Explore","description":"scan"}}]}}""" + "\n" +
                """{"type":"user","toolUseResult":{},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"the report"}]}}""" + "\n",
        )

        val slice = TranscriptReplay.slice(f, sinceSeq = cursor)

        assertTrue(slice.delta)
        assertEquals(1, slice.messages.size)
        assertEquals("the report", slice.messages[0].output) // patched within the delta
    }

    @Test
    fun oversized_delta_falls_back_to_full() {
        val f = tmpFile("s.jsonl")
        f.writeText(user("q0") + "\n")
        val cursor = TranscriptReplay.slice(f, sinceSeq = null).lastSeq
        f.appendText((1..5).joinToString("\n") { user("m$it") } + "\n")

        // a delta larger than the count cap demotes to the (equally capped) full window
        val slice = TranscriptReplay.slice(f, sinceSeq = cursor, maxMessages = 3)

        assertFalse(slice.delta)
        assertEquals(3, slice.messages.size)
        assertEquals(listOf("m3", "m4", "m5"), slice.messages.map { it.text })
        assertTrue(slice.hasMore)
    }

    @Test
    fun first_screen_truncation_reports_hasMore_and_page_loads_older_rows() {
        val f = tmpFile("s.jsonl")
        f.writeText((1..10).joinToString("\n") { user("m$it") } + "\n")

        val window = TranscriptReplay.slice(f, sinceSeq = null, maxMessages = 4)
        assertEquals(listOf("m7", "m8", "m9", "m10"), window.messages.map { it.text })
        assertTrue(window.hasMore)
        assertEquals(7L, window.firstSeq)

        val page = TranscriptReplay.page(f, beforeSeq = window.firstSeq!!, limit = 3)
        assertEquals(listOf("m4", "m5", "m6"), page.messages.map { it.text })
        assertTrue(page.hasMore)
        assertEquals(4L, page.firstSeq)
        assertNull(page.lastSeq) // a page is never a reattach cursor

        val last = TranscriptReplay.page(f, beforeSeq = page.firstSeq!!, limit = 100)
        assertEquals(listOf("m1", "m2", "m3"), last.messages.map { it.text })
        assertFalse(last.hasMore)
    }

    @Test
    fun delta_still_respects_the_frame_byte_budget() {
        // a delta whose rows blow the byte budget must NOT ride through clipped — it demotes to the
        // full path, which applies the same #81 budget it always did
        val f = tmpFile("s.jsonl")
        f.writeText(user("q0") + "\n")
        val cursor = TranscriptReplay.slice(f, sinceSeq = null).lastSeq
        val big = "x".repeat(600)
        f.appendText(user(big) + "\n" + user(big) + "\n" + user(big) + "\n")

        val slice = TranscriptReplay.slice(f, sinceSeq = cursor, maxFrameTextBytes = 1000)

        assertFalse(slice.delta) // budget-trimmed continuation → full window instead
        // and the full window itself stays within the budget (newest kept whole, oldest clipped/shed)
        assertTrue(slice.messages.sumOf { ReplayBudget.payloadSize(it) } <= 1000)
    }

    @Test
    fun codex_rollout_delta_and_page_share_the_same_semantics() {
        fun codexUser(text: String) =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}}"""
        val f = tmpFile("rollout.jsonl")
        f.writeText(listOf(codexUser("q1"), codexUser("q2")).joinToString("\n") + "\n")
        val first = dev.ccpocket.daemon.codex.CodexTranscriptReplay.slice(f, sinceSeq = null)
        assertEquals(2L, first.lastSeq)

        f.appendText(codexUser("q3") + "\n")
        val delta = dev.ccpocket.daemon.codex.CodexTranscriptReplay.slice(f, sinceSeq = first.lastSeq)
        assertTrue(delta.delta)
        assertEquals(listOf("q3"), delta.messages.map { it.text })

        val page = dev.ccpocket.daemon.codex.CodexTranscriptReplay.page(f, beforeSeq = 2, limit = 10)
        assertEquals(listOf("q1"), page.messages.map { it.text })
        assertFalse(page.hasMore)
    }
}
