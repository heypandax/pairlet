package dev.ccpocket.daemon.codex

import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import kotlin.test.*

class CodexIncrementalReplayTest {
    private fun message(text: String) = """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}}""" + "\n"

    @Test fun appendReadsOnlyNewBytesAndMatchesFullReplay() {
        val file = Files.createTempFile("codex-tail", ".jsonl")
        try {
            Files.writeString(file, (1..2000).joinToString("") { message("row $it") })
            val tail = CodexTranscriptReplay.Tail()
            val first = tail.slice(file, null)
            assertEquals(CodexTranscriptReplay.slice(file, null).messages, first.messages)
            assertTrue(first.hasMore)
            val bytes = tail.bytesRead
            val added = message("中文 new")
            Files.writeString(file, added, APPEND)
            val next = tail.slice(file, first.lastSeq)
            assertTrue(next.delta)
            assertEquals(listOf("中文 new"), next.messages.map { it.text })
            assertEquals(added.toByteArray().size.toLong(), tail.bytesRead - bytes)
            assertEquals(CodexTranscriptReplay.slice(file, first.lastSeq).messages, next.messages)
            tail.slice(file, next.lastSeq)
            assertEquals(bytes + added.toByteArray().size, tail.bytesRead)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun largeToolOutputsAreNotReadAgainOnAppend() {
        val file = Files.createTempFile("codex-large-tail", ".jsonl")
        try {
            val output = "x".repeat(512 * 1024)
            Files.newBufferedWriter(file).use { writer ->
                repeat(32) { index ->
                    writer.write("""{"type":"response_item","payload":{"type":"function_call","call_id":"$index","name":"exec_command","arguments":"{}"}}""" + "\n")
                    writer.write("""{"type":"response_item","payload":{"type":"function_call_output","call_id":"$index","output":"$output"}}""" + "\n")
                }
            }
            val tail = CodexTranscriptReplay.Tail()
            val first = tail.slice(file, null)
            assertEquals(32, first.messages.size)
            assertTrue(Files.size(file) > 16 * 1024 * 1024)
            val before = tail.bytesRead
            val added = message("next")
            Files.writeString(file, added, APPEND)
            assertEquals(listOf("next"), tail.slice(file, first.lastSeq).messages.map { it.text })
            assertEquals(added.toByteArray().size.toLong(), tail.bytesRead - before)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun partialUtf8WaitsForNewlineAndTruncationResetsGeneration() {
        val file = Files.createTempFile("codex-tail", ".jsonl")
        try {
            val raw = message("中文").toByteArray()
            val split = raw.indexOf(0xe4.toByte()) + 1
            Files.write(file, raw.copyOfRange(0, split))
            val tail = CodexTranscriptReplay.Tail()
            assertTrue(tail.slice(file, null).messages.isEmpty())
            Files.write(file, raw.copyOfRange(split, raw.size), APPEND)
            val complete = tail.slice(file, null)
            assertEquals(listOf("中文"), complete.messages.map { it.text })
            Files.writeString(file, message("x"))
            val reset = tail.slice(file, complete.lastSeq)
            assertFalse(reset.delta)
            assertEquals(listOf("x"), reset.messages.map { it.text })
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun completionPatchesOldRowAndRuntimeUsesSameRead() {
        val file = Files.createTempFile("codex-tail", ".jsonl")
        try {
            Files.writeString(file, """{"type":"response_item","payload":{"type":"function_call","call_id":"a","name":"exec_command","arguments":"{}"}}""" + "\n")
            val tail = CodexTranscriptReplay.Tail()
            val first = tail.slice(file, null)
            Files.writeString(file, """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"a","exit_code":1,"status":"completed"}}}""" + "\n" +
                """{"type":"turn_context","payload":{"model":"test-model"}}""" + "\n", APPEND)
            val next = tail.slice(file, first.lastSeq)
            assertFalse(next.delta)
            assertEquals(false, next.messages.single().ok)
            assertEquals("test-model", tail.runtime.model)
            assertEquals(CodexTranscriptReplay.slice(file, first.lastSeq).messages, next.messages)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun temporaryReadFailurePreservesHistoryAndRetriesUnchangedFile() {
        val file = Files.createTempFile("codex-retry", ".jsonl")
        try {
            Files.writeString(file, message("old"))
            var fail = false
            val tail = CodexTranscriptReplay.Tail { path ->
                if (fail) throw java.io.IOException("temporary test failure")
                java.io.RandomAccessFile(path.toFile(), "r")
            }
            val initial = tail.slice(file, null)
            Files.writeString(file, message("new"), APPEND)
            fail = true
            val unavailable = tail.slice(file, initial.lastSeq)
            assertTrue(tail.retryRead)
            assertTrue(unavailable.delta, "an empty FULL would erase the existing chat")
            assertNull(unavailable.lastSeq, "failed reads must not acknowledge a cursor")
            fail = false
            val recovered = tail.slice(file, initial.lastSeq)
            assertFalse(tail.retryRead)
            assertFalse(recovered.delta)
            assertEquals(listOf("old", "new"), recovered.messages.map { it.text })
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun reconnectCursorAndOlderPagesRemainUsable() {
        val file = Files.createTempFile("codex-reconnect", ".jsonl")
        try {
            Files.writeString(file, message("old") + message("new"))
            val tail = CodexTranscriptReplay.Tail()
            val delta = tail.slice(file, 1)
            assertTrue(delta.delta)
            assertEquals(listOf("new"), delta.messages.map { it.text })
            assertEquals(listOf("old"), CodexTranscriptReplay.page(file, 2).messages.map { it.text })
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun samePathReplacementDoesNotReuseOldCursor() {
        val file = Files.createTempFile("codex-tail", ".jsonl")
        try {
            Files.writeString(file, message("old"))
            val tail = CodexTranscriptReplay.Tail()
            val first = tail.slice(file, null)
            val replacement = Files.createTempFile(file.parent, "codex-new", ".jsonl")
            Files.writeString(replacement, message("replacement longer than old"))
            Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val next = tail.slice(file, first.lastSeq)
            assertFalse(next.delta)
            assertEquals(listOf("replacement longer than old"), next.messages.map { it.text })
        } finally { Files.deleteIfExists(file) }
    }
}
