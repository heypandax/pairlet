package dev.ccpocket.app.data

import dev.ccpocket.protocol.*
import kotlin.test.*

class CompactTranscriptTest {
    @Test fun summaryDoesNotStartTurnAndReplayDoesNotDuplicateIt() {
        val transcript = ChatTranscript()
        transcript.appendCompactSummary("summary")
        assertFalse(transcript.streaming.value)
        assertTrue((transcript.messages.single() as ChatItem.User).compactSummary)
        transcript.appendCompactSummary("summary")
        assertEquals(1, transcript.messages.size)
        transcript.mergeHistory(ConvoHistory("c", listOf(
            HistoryMessage(ChatRole.USER, "summary", compactSummary = true),
        )))
        assertEquals(1, transcript.messages.size)
        transcript.appendChunk(AssistantChunk("c", 2, StreamPiece.Text("actual answer")))
        assertIs<ChatItem.Assistant>(transcript.messages.last())
    }

    @Test fun quotedSummaryIsNotPairedWithGeneratedSummary() {
        val items = TranscriptMerge.merge(listOf(ChatItem.User("summary", pending = true)), listOf(
            historyItem(HistoryMessage(ChatRole.USER, "summary", compactSummary = true)),
        ))
        assertTrue(items.filterIsInstance<ChatItem.User>().any { it.pending && !it.compactSummary })
        assertTrue(items.filterIsInstance<ChatItem.User>().any { it.compactSummary })
        val summary = historyItem(HistoryMessage(ChatRole.USER, "summary", seq = 1, uuid = "u", compactSummary = true)) as ChatItem.User
        assertNull(summary.seq)
        assertNull(summary.uuid)
    }
}
