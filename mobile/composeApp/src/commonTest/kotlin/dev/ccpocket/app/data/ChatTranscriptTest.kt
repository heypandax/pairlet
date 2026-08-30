package dev.ccpocket.app.data

import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stream-assembly rules, now that they live in [ChatTranscript] rather than inside the repository
 * (issue #311). The extraction has to be behaviour-preserving — the repository delegates to this and the
 * split panes each own one, so a rule that quietly changed here would change the main chat too.
 */
class ChatTranscriptTest {

    private fun text(s: String) = AssistantChunk("c", 1, StreamPiece.Text(s))
    private fun thinking(s: String) = AssistantChunk("c", 1, StreamPiece.Thinking(s))

    @Test
    fun consecutiveTextChunksGrowOneBubble() {
        val t = ChatTranscript()
        t.appendChunk(text("Hel"))
        t.appendChunk(text("lo"))
        assertEquals(listOf("Hello"), t.messages.filterIsInstance<ChatItem.Assistant>().map { it.text })
        assertTrue(t.streaming.value) // a chunk means a turn is running
    }

    @Test
    fun thinkingCollectsSeparatelyAndIsStampedWhenProseStarts() {
        val t = ChatTranscript()
        t.appendChunk(thinking("weighing "))
        t.appendChunk(thinking("options"))
        val open = t.messages.filterIsInstance<ChatItem.Thinking>().single()
        assertEquals("weighing options", open.text)
        assertNull(open.seconds) // still open

        t.appendChunk(text("Here is the plan"))
        val stamped = t.messages.filterIsInstance<ChatItem.Thinking>().single()
        assertNotNull(stamped.seconds) // prose starting closes the block and stamps its duration
        assertTrue(stamped.seconds!! >= 1) // never "Thought for 0s"
    }

    @Test
    fun aToolResultPatchesTheCardItsStartCreated() {
        val t = ChatTranscript()
        t.onToolEvent(ToolEvent("c", 1, ToolPhase.START, "Read", "src/App.kt", toolUseId = "t1"))
        t.onToolEvent(ToolEvent("c", 2, ToolPhase.RESULT, "Read", toolUseId = "t1", ok = false, output = "no such file"))
        val card = t.messages.filterIsInstance<ChatItem.Tool>().single()
        assertEquals("Read", card.tool)
        assertEquals(false, card.ok)
        assertEquals("no such file", card.output)
    }

    @Test
    fun aChildToolEventCountsUpItsParentCardInsteadOfAddingARow() {
        val t = ChatTranscript()
        t.onToolEvent(ToolEvent("c", 1, ToolPhase.START, "Task", "explore", toolUseId = "parent"))
        t.onToolEvent(ToolEvent("c", 2, ToolPhase.START, "Grep", "needle", toolUseId = "k1", parentToolUseId = "parent"))
        t.onToolEvent(ToolEvent("c", 3, ToolPhase.START, "Read", "file", toolUseId = "k2", parentToolUseId = "parent"))
        val card = t.messages.filterIsInstance<ChatItem.Tool>().single()
        assertEquals(2, card.childCount)
        assertEquals("Read", card.lastChild)
    }

    @Test
    fun theReplayEchoDedupeDropsExactlyOneRepeatedTail() {
        val t = ChatTranscript()
        t.appendChunk(text("the replayed answer"))
        t.replayEcho = true // what a merged ConvoHistory arms
        t.appendChunk(text("the replayed answer")) // the live stream re-sending the block the replay carried
        assertEquals(listOf("the replayed answer"), t.messages.filterIsInstance<ChatItem.Assistant>().map { it.text })

        t.appendChunk(text(" and more")) // the one-shot is spent — ordinary streaming resumes
        assertEquals(listOf("the replayed answer and more"), t.messages.filterIsInstance<ChatItem.Assistant>().map { it.text })
    }

    @Test
    fun resetDropsEverythingIncludingTheOneShotArm() {
        val t = ChatTranscript()
        t.appendChunk(text("gone"))
        t.replayEcho = true
        t.reset()
        assertTrue(t.messages.isEmpty())
        assertTrue(!t.replayEcho)
        assertTrue(!t.streaming.value)
    }

    /** A conversation boundary must also disarm the THINKING clock, or the next session's first tool call
     *  stamps a row that belongs to a conversation the user already left. */
    @Test
    fun resetDisarmsTheThinkingClockToo() {
        val t = ChatTranscript()
        t.appendChunk(thinking("mid-thought when the user switched away"))
        t.reset()
        t.appendChunk(thinking("a brand-new block"))
        t.onToolEvent(ToolEvent("c", 1, ToolPhase.START, "Read", "src/App.kt", toolUseId = "t1"))
        // exactly one block, stamped by ITS OWN start — nothing carried over the boundary
        assertEquals(1, t.messages.count { it is ChatItem.Thinking })
        assertNotNull(t.messages.filterIsInstance<ChatItem.Thinking>().single().seconds)
    }

    /** A tool starting closes the thinking block — the transcript's own rule now, not a convention each
     *  caller had to remember to hand-pair with [ChatTranscript.onToolEvent]. */
    @Test
    fun aToolStartingStampsTheOpenThinkingBlock() {
        val t = ChatTranscript()
        t.appendChunk(thinking("which file?"))
        t.onToolEvent(ToolEvent("c", 1, ToolPhase.START, "Read", "src/App.kt", toolUseId = "t1"))
        assertNotNull(t.messages.filterIsInstance<ChatItem.Thinking>().single().seconds)
    }

    @Test
    fun endTurnReportsWhetherATurnWasWatchedAndShowsAnErrorWhereTheReplyWouldBe() {
        val t = ChatTranscript()
        t.appendChunk(thinking("about to fail"))
        t.replayEcho = true
        assertTrue(t.endTurn("API request failed"), "a turn that was streaming counts as watched")
        assertTrue(!t.streaming.value)
        assertTrue(!t.replayEcho, "a turn boundary disarms the echo — the next block starts a new turn")
        assertNotNull(t.messages.filterIsInstance<ChatItem.Thinking>().single().seconds)
        assertEquals("API request failed", t.messages.filterIsInstance<ChatItem.Sys>().single().text)

        // a second boundary with nothing running is NOT a watched turn (no completion marker for it)
        assertTrue(!t.endTurn(null))
    }

    @Test
    fun mergeHistoryReturnsTheCursorAndArmsTheEchoDedupe() {
        val t = ChatTranscript()
        val full = ConvoHistory(
            "c",
            listOf(HistoryMessage(ChatRole.USER, "what changed?"), HistoryMessage(ChatRole.ASSISTANT, "three files")),
            lastSeq = 42,
        )
        assertEquals(42L, t.mergeHistory(full))
        assertEquals(2, t.messages.size)
        assertTrue(t.replayEcho)

        // an EMPTY delta merges nothing and arms nothing — but still carries the cursor forward
        t.replayEcho = false
        assertEquals(77L, t.mergeHistory(ConvoHistory("c", emptyList(), delta = true, lastSeq = 77)))
        assertEquals(2, t.messages.size)
        assertTrue(!t.replayEcho)
    }

    /** The receipt hook the focused path layers on top sees the lists as they were and as they became. */
    @Test
    fun mergeHistoryHandsTheCallerTheBeforeAndAfterRows() {
        val t = ChatTranscript()
        t.appendChunk(text("already on screen"))
        var before: List<ChatItem>? = null
        var after: List<ChatItem>? = null
        t.mergeHistory(ConvoHistory("c", listOf(HistoryMessage(ChatRole.USER, "typed at the computer")))) { b, a ->
            before = b
            after = a
        }
        assertEquals(1, before?.size)
        assertEquals(t.messages.toList(), after)
    }
}
