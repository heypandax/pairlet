package dev.ccpocket.app.data

import dev.ccpocket.protocol.AssistantChunk
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
}
