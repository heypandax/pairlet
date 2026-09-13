package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.Zstd
import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic payloads checked against deepseek-harness dsh-v0.1.5-rc.1 (183f08e9):
 * packages/session/session/src/types.ts and session-format-v{0-to-v1,1-to-v2,2-to-v3}.
 * V0/V1 retain packed top-level deltas; V2/V3 embed stream[] in assistant/message. The conversation
 * payloads below are shared, and V3 replaces start/end surface endpoints with startSeq/endSeq.
 * These are invented conversations, never copies of a local session.
 */
class DshGenerationReplayTest {
    private val root = Files.createTempDirectory("dsh-generation-replay")
    private val cwd = "/work/alpha"
    private val id = "session-fixture"

    @AfterTest
    fun cleanup() { root.toFile().deleteRecursively() }

    private fun header(version: Int): String =
        """{"type":"session","version":$version,"id":"$id","cwd":"$cwd","createdAt":1700000000000,"delegationDepth":0,""" +
            (if (version < 2) "\"seedLength\":0" else "\"isSeeded\":false") + "}\n"

    private fun event(type: String, seq: Int, data: String, placement: String? = null, sources: String? = null): String =
        """{"type":"$type","seq":$seq,"time":1700000000001,"data":$data""" +
            (placement?.let { ",\"surfaceOp\":$it" } ?: "") +
            (sources?.let { ",\"sourceEventSeqs\":$it" } ?: "") + "}\n"

    private fun user(seq: Int, text: String, placement: String = "\"append\"", sources: String? = null): String = event(
        "user/message", seq,
        """{"id":"u$seq","role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}""",
        placement, sources,
    )

    private fun assistant(seq: Int, text: String, stream: String = "[]", placement: String = "\"append\""): String = event(
        "assistant/message", seq,
        """{"turn":1,"step":1,"message":{"id":"a$seq","role":"assistant","source":{"kind":"model","provider":"fixture","model":"fixture-model"},"content":[{"type":"text","text":"$text"}]},"stream":$stream,"usage":{"inputTokens":100,"outputTokens":10,"cacheReadTokens":20}}""",
        placement,
    )

    private fun call(seq: Int, callId: String = "call-1"): String = event("tool/call", seq,
        """{"turn":1,"step":1,"callId":"$callId","name":"read_file","arguments":"{\"path\":\"README.md\"}"}""")

    private fun result(seq: Int, text: String, callId: String = "call-1", error: Boolean = false,
                       placement: String = "\"append\"", sources: String? = null, messageId: String = "r$seq"): String = event("tool/result", seq,
        """{"turn":1,"step":1,"message":{"id":"$messageId","role":"user","source":{"kind":"tool","callId":"$callId"},"content":[{"type":"tool-result","toolCallId":"$callId","isError":$error,"content":[{"type":"text","text":"$text"}]}]}}""",
        placement, sources)

    private fun file(version: Int, compressed: Boolean, rows: String, store: Path = root): Path {
        val dir = store.resolve(DshPaths.projectKey(cwd)).resolve(id)
        Files.createDirectories(dir)
        val basename = if (version == 0) "session.jsonl" else "session.v$version.jsonl"
        val file = dir.resolve(basename + if (compressed) ".zstd" else "")
        if (compressed) {
            Files.write(file, Zstd.compress(header(version).toByteArray()) + Zstd.compress(rows.toByteArray()))
        } else Files.writeString(file, header(version) + rows)
        return file
    }

    private fun append(file: Path, rows: String) {
        val bytes = rows.toByteArray().let { if (file.toString().endsWith(".zstd")) Zstd.compress(it) else it }
        Files.write(file, bytes, StandardOpenOption.APPEND)
    }

    @Test
    fun all_released_generations_are_discovered_and_replayed_in_both_encodings() {
        for (version in 0..3) for (compressed in listOf(false, true)) {
            val store = root.resolve("v$version-$compressed")
            val transcript = file(version, compressed,
                user(0, "First prompt") + assistant(1, "First reply") + call(2) + result(3, "File contents") +
                    user(4, "Follow-up") + assistant(5, "Final reply"), store)
            val found = assertNotNull(DshTranscriptScanner.find(id, cwd, store))
            assertEquals(transcript, found.file)
            assertTrue(found.header.isSupported)
            assertEquals(setOf(cwd), DshTranscriptScanner.cwdsByNewest(store).keys)
            val summary = DshTranscriptScanner.scan(cwd, store).single()
            assertEquals("First prompt", summary.title)
            assertEquals(2, summary.messageCount)
            val replay = DshTranscriptReplay.slice(found.file, null)
            assertEquals("complete", replay.quality, "v$version compressed=$compressed")
            assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.USER, ChatRole.ASSISTANT),
                replay.messages.map { it.role })
            assertEquals("Final reply", replay.messages.last().text)
            assertEquals("File contents", replay.messages[2].output)
            assertEquals(true, replay.messages[2].ok)
            assertEquals("fixture-model", DshTranscript.resumeMeta(transcript).model)
        }
    }

    @Test
    fun migrated_v3_overrides_the_immutable_legacy_copy_for_listing_replay_and_usage() {
        val legacy = file(0, true, user(0, "Old opening") + assistant(1, "Old reply"))
        val current = file(3, false, user(0, "Current opening") + assistant(1, "Current reply") + user(2, "Later prompt"))
        Files.setLastModifiedTime(current, java.nio.file.attribute.FileTime.fromMillis(1))
        val beforeLegacy = Files.readAllBytes(legacy)
        val beforeCurrent = Files.readAllBytes(current)
        val found = assertNotNull(DshTranscriptScanner.find(id, cwd, root))
        assertEquals(current, found.file)
        assertEquals("Current opening", DshTranscriptScanner.scan(cwd, root).single().title)
        assertEquals(listOf("Current opening", "Current reply", "Later prompt"), DshTranscriptReplay.read(found.file).map { it.text })
        assertEquals(1, DshUsageScanner.usageRecords(0, root).size, "legacy and current must not double-count spend")
        assertTrue(beforeLegacy.contentEquals(Files.readAllBytes(legacy)))
        assertTrue(beforeCurrent.contentEquals(Files.readAllBytes(current)))
    }

    @Test
    fun reconnect_replays_both_turns_and_patches_a_tool_result_written_while_disconnected() {
        val transcript = file(3, true, user(0, "Inspect file") + assistant(1, "Reading") + call(2))
        val initial = DshTranscriptReplay.slice(transcript, null)
        assertNull(initial.messages.last().ok)
        append(transcript, result(3, "File contents") + assistant(4, "First answer") +
            user(5, "Read another") + call(6, "call-2") + result(7, "Missing file", "call-2", error = true) +
            assistant(8, "Second answer"))
        val reopened = DshTranscriptReplay.slice(transcript, initial.lastSeq)
        assertFalse(reopened.delta, "a late result patches a card already on the client")
        assertEquals(listOf("Inspect file", "Reading", "First answer", "Read another", "Second answer"),
            reopened.messages.filter { it.role != ChatRole.TOOL }.map { it.text })
        val tools = reopened.messages.filter { it.role == ChatRole.TOOL }
        assertEquals(listOf("File contents", "Missing file"), tools.map { it.output })
        assertEquals(listOf(true, false), tools.map { it.ok })
        assertEquals("complete", reopened.quality)
    }

    @Test
    fun a_cursor_from_an_older_generation_forces_a_full_replay() {
        val old = file(0, false, user(0, "Opening") + assistant(1, "Reply"))
        val oldCursor = DshTranscriptReplay.slice(old, null).lastSeq
        val current = file(3, false, user(0, "Opening") + assistant(1, "Reply") + user(2, "New prompt") + assistant(3, "New reply"))
        val replay = DshTranscriptReplay.slice(current, oldCursor)
        assertFalse(replay.delta)
        assertEquals(listOf("Opening", "Reply", "New prompt", "New reply"), replay.messages.map { it.text })
        assertEquals(5L, replay.sourceRows, "source row metrics remain physical counts")
        assertTrue(replay.lastSeq!! > oldCursor!!)
        val stalePage = DshTranscriptReplay.page(current, oldCursor)
        assertEquals("unavailable", stalePage.quality)
        assertTrue(stalePage.messages.isEmpty())
        assertTrue(stalePage.readError!!.contains("changed format"))
    }

    @Test
    fun same_generation_cursors_still_support_incremental_replay_and_older_pages() {
        val transcript = file(3, true, user(0, "Opening"))
        val initial = DshTranscriptReplay.slice(transcript, null)
        append(transcript, assistant(1, "Reply") + user(2, "Follow-up"))
        val replay = DshTranscriptReplay.slice(transcript, initial.lastSeq)
        assertTrue(replay.delta)
        assertEquals(listOf("Reply", "Follow-up"), replay.messages.map { it.text })
        val older = DshTranscriptReplay.page(transcript, replay.firstSeq!!)
        assertEquals(listOf("Opening"), older.messages.map { it.text })
        assertEquals("complete", older.quality)
    }

    @Test
    fun unknown_current_generation_is_visible_and_never_replays_an_older_copy() {
        file(0, false, user(0, "Stale content"))
        file(3, true, user(0, "Also stale"))
        val current = file(4, true, user(0, "Future content"))
        val found = assertNotNull(DshTranscriptScanner.find(id, cwd, root))
        assertEquals(current, found.file)
        assertFalse(found.header.isSupported)
        val summary = DshTranscriptScanner.scan(cwd, root).single()
        assertTrue(summary.firstPrompt.contains("unsupported session format v4"))
        val replay = DshTranscriptReplay.slice(found.file, 2L)
        assertEquals("unavailable", replay.quality)
        assertTrue(replay.delta, "an unavailable read must never be an explicit client clear")
        assertTrue(replay.messages.isEmpty())
        assertNull(replay.lastSeq, "the last successful cursor must not be advanced")
        assertTrue(replay.readError!!.contains("unsupported session format v4"))
        assertNull(DshTranscript.resumeMeta(current).model)
        assertTrue(DshUsageScanner.usageRecords(0, root).isEmpty())
    }

    @Test
    fun filename_header_disagreement_and_unreadable_new_generation_never_fall_back() {
        file(0, false, user(0, "Stale content"))
        val selected = file(3, false, user(0, "Current content"))
        Files.writeString(selected, header(0) + user(0, "Wrong generation"))
        val found = assertNotNull(DshTranscriptScanner.find(id, cwd, root))
        assertFalse(found.header.isSupported)
        val mismatch = DshTranscriptReplay.slice(found.file, null)
        assertTrue(mismatch.messages.isEmpty())
        assertTrue(mismatch.readError!!.contains("filename declares format v3"))
        Files.writeString(selected, "")
        assertEquals(selected, DshPaths.transcriptFile(selected.parent))
        assertNull(DshTranscriptScanner.find(id, cwd, root))
        val unreadable = DshTranscriptReplay.slice(selected, null)
        assertTrue(unreadable.messages.isEmpty())
        assertEquals("unavailable", unreadable.quality)
        assertNotNull(unreadable.readError)
    }

    @Test
    fun known_session_header_failure_is_visible_through_backend_and_recovers_without_fake_discovery() {
        val backend = DshBackend(null, sessionsRoot = { root })
        val transcript = file(3, false, user(0, "Existing history") + assistant(1, "Existing reply"))
        val good = Files.readString(transcript)
        val initial = backend.replaySlice(cwd, id, null)
        assertEquals(2, initial.messages.size)
        for (bad in listOf("", "not a session header\n")) {
            Files.writeString(transcript, bad)
            assertTrue(DshTranscriptScanner.scan(cwd, root).isEmpty())
            assertTrue(DshTranscriptScanner.cwdsByNewest(root).isEmpty(), "never infer cwd from the lossy directory")
            for (failed in listOf(backend.replaySlice(cwd, id, initial.lastSeq),
                backend.replayPage(cwd, id, initial.lastSeq!!, 50))) {
                assertEquals("unavailable", failed.quality)
                assertNotNull(failed.readError)
                assertTrue(failed.messages.isEmpty())
                assertTrue(failed.delta)
                assertNull(failed.lastSeq)
            }
        }
        Files.writeString(transcript, good)
        assertEquals(initial.messages, backend.replaySlice(cwd, id, null).messages)
        assertNull(backend.replayPage(cwd, id, initial.lastSeq!!, 50).readError)
        Files.delete(transcript)
        assertNotNull(backend.replaySlice(cwd, id, null).readError, "a missing known transcript is not an empty history")
    }

    @Test
    fun corrupt_body_after_readable_header_is_visible_and_retry_restores_history() {
        val transcript = file(3, true, user(0, "Existing history") + assistant(1, "Existing reply"))
        // Get beyond the header reader's 256 KiB bound before the bad frame. A supported/readable
        // header must not mask failure later in the full-body decompression pass.
        append(transcript, event("text-chunks", 2, "{}") .repeat(8_000))
        val intact = Files.readAllBytes(transcript)
        val initial = DshTranscriptReplay.slice(transcript, null)
        Files.write(transcript, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), StandardOpenOption.APPEND)
        assertNotNull(DshTranscript.header(transcript))
        for (failed in listOf(DshTranscriptReplay.slice(transcript, initial.lastSeq),
            DshTranscriptReplay.page(transcript, initial.lastSeq!!))) {
            assertEquals("unavailable", failed.quality)
            assertTrue(assertNotNull(failed.readError).contains("read or decompressed"))
            assertTrue(failed.messages.isEmpty())
            assertNull(failed.lastSeq)
        }
        Files.write(transcript, intact)
        assertEquals(initial.messages, DshTranscriptReplay.slice(transcript, null).messages)
        assertNull(DshTranscriptReplay.page(transcript, initial.lastSeq!!).readError)
    }

    @Test
    fun v3_embedded_stream_larger_than_the_old_row_limit_keeps_the_assembled_reply() {
        val stream = """[{"type":"chunk","time":1,"chunk":{"type":"reasoning-delta","index":0,"text":"${"x".repeat(230_000)}"}}]"""
        val transcript = file(3, true, user(0, "Question") + assistant(1, "Visible answer", stream))
        val replay = DshTranscriptReplay.slice(transcript, null)
        assertEquals("complete", replay.quality)
        assertEquals(listOf("Question", "Visible answer"), replay.messages.map { it.text })
        assertEquals("fixture-model", DshTranscript.resumeMeta(transcript).model)
        assertEquals(1, DshUsageScanner.usageRecords(0, root).size)
    }

    @Test
    fun model_only_compaction_copies_do_not_duplicate_messages_or_overwrite_tool_output() {
        val replace = """{"op":"replace","startSeq":3,"endSeq":3}"""
        val transcript = file(3, false, user(0, "Original question") + assistant(1, "Original reply") + call(2) +
            result(3, "Original result") + result(4, "Pruned result", placement = replace, sources = "[3]", messageId = "r3") +
            user(5, "Model-only summary", """{"op":"replace","startSeq":0,"endSeq":1}""", sources = "[0,1]") +
            user(6, "Follow-up") + assistant(7, "Next reply"))
        val replay = DshTranscriptReplay.read(transcript)
        assertEquals(listOf("Original question", "Original reply", "Follow-up", "Next reply"),
            replay.filter { it.role != ChatRole.TOOL }.map { it.text })
        assertEquals("Original result", replay.single { it.role == ChatRole.TOOL }.output)
        assertEquals(2, DshTranscriptScanner.scan(cwd, root).single().messageCount)
    }

    @Test
    fun v3_missing_surface_marker_reports_partial_instead_of_claiming_complete() {
        val malformed = event("user/message", 1,
            """{"id":"bad","role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"Malformed"}]}""")
        val transcript = file(3, false, user(0, "Valid") + malformed)
        val replay = DshTranscriptReplay.slice(transcript, null)
        assertEquals(listOf("Valid"), replay.messages.map { it.text })
        assertEquals("partial", replay.quality)
        assertEquals(1L, replay.failedRows)
    }

    @Test
    fun v3_concatenated_frames_keep_the_complete_prefix_when_the_final_frame_is_torn() {
        val transcript = file(3, true, user(0, "Complete"))
        val tail = Zstd.compress(assistant(1, "Still writing").toByteArray())
        Files.write(transcript, tail.copyOfRange(0, tail.size / 2), StandardOpenOption.APPEND)
        assertEquals(listOf("Complete"), DshTranscriptReplay.read(transcript).map { it.text })
    }
}
