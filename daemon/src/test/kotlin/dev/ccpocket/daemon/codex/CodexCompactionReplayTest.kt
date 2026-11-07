package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import kotlin.test.*

class CodexCompactionReplayTest {
    private val before = """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"before"}]}}""" + "\n"
    // Remote compaction stores no plaintext summary. Replacement history is model input, not chat.
    private val compacted = """{"type":"compacted","payload":{"message":"","replacement_history":[{"type":"message","role":"developer","content":[{"type":"input_text","text":"private setup"}]},{"type":"compaction","encrypted_content":"opaque"}]}}""" + "\n"

    @Test fun emptyRemoteCompactionMessageStillReplaysAsACard() {
        val file = Files.createTempFile("codex-compact", ".jsonl")
        try {
            Files.writeString(file, before + compacted)
            val rows = CodexTranscriptReplay.read(file)
            assertEquals(2, rows.size)
            assertEquals("before", rows.first().text)
            val card = rows.last()
            assertEquals(ChatRole.USER, card.role)
            assertEquals("Context compacted.", card.text)
            assertTrue(card.compactSummary)
            assertNull(card.seq, "a compaction record must never become a rewind anchor")
            assertEquals(listOf(card), CodexTranscriptReplay.slice(file, sinceSeq = 1).messages)
            assertEquals(listOf(card), CodexTranscriptReplay.page(file, beforeSeq = 3, limit = 1).messages)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun incrementalReplayRetainsEachCompactionAndDoesNotDuplicateOnReattach() {
        val file = Files.createTempFile("codex-compact-tail", ".jsonl")
        try {
            Files.writeString(file, before)
            val tail = CodexTranscriptReplay.Tail()
            var cursor = tail.slice(file, null).lastSeq
            repeat(2) {
                Files.writeString(file, compacted, APPEND)
                val delta = tail.slice(file, cursor)
                assertTrue(delta.delta)
                assertEquals(CodexTranscriptReplay.slice(file, cursor).messages, delta.messages)
                assertTrue(delta.messages.single().compactSummary)
                cursor = delta.lastSeq
                assertTrue(tail.slice(file, cursor).messages.isEmpty())
            }
            assertEquals(2, CodexTranscriptReplay.read(file).count { it.compactSummary })
        } finally { Files.deleteIfExists(file) }
    }
}
