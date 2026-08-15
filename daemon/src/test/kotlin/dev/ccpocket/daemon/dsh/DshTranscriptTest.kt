package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.Zstd
import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real physical format: CONCATENATED, independently-compressed zstd frames. The fixtures
 * are built here with the same library the daemon decodes with, so these tests fail loudly if the
 * multi-frame assumption is ever broken (a single-frame decoder passes the one-frame case and silently
 * loses every event in the others — which is exactly the bug this guards).
 */
class DshTranscriptTest {

    private fun tmp(): Path = Files.createTempDirectory("dsh-transcript-test").also { it.toFile().deleteOnExit() }

    /** One zstd frame per element — concatenated, exactly like dsh's durable batches. */
    private fun writeFrames(dir: Path, vararg batches: String): Path {
        dir.createDirectories()
        val file = dir.resolve("session.jsonl.zstd")
        val out = batches.fold(ByteArray(0)) { acc, batch -> acc + Zstd.compress(batch.toByteArray()) }
        Files.write(file, out)
        return file
    }

    private fun header(id: String = "session-abc", cwd: String = "/w", version: Int = 0, origin: String? = null) =
        buildString {
            append("""{"type":"session","version":$version,"id":"$id","cwd":"$cwd",""")
            append(""""createdAt":1700000000000,"delegationDepth":0""")
            if (origin != null) append(""","origin":"$origin"""")
            append("}\n")
        }

    private fun userMsg(text: String, seq: Int) =
        """{"type":"user/message","seq":$seq,"time":1700000000001,"data":{"id":"m$seq","role":"user","content":[{"type":"text","text":"$text"}],"source":"user"}}""" + "\n"

    /** NOTE the asymmetry with [userMsg]: the assistant event wraps its Message under `message`. */
    private fun assistantMsg(text: String, seq: Int) =
        """{"type":"assistant/message","seq":$seq,"time":1700000000002,"data":{"turn":1,"step":1,"message":{"id":"a$seq","role":"assistant","content":[{"type":"text","text":"$text"}],"source":"model"}}}""" + "\n"

    // ---- multi-frame decoding ----

    @Test
    fun reads_events_across_many_concatenated_frames() {
        val file = writeFrames(
            tmp(),
            header(),                       // frame 1: the header, as dsh writes it
            userMsg("hello", 1),            // frame 2: a durable batch
            assistantMsg("hi there", 2),    // frame 3
            userMsg("more", 3) + assistantMsg("ok", 4), // frame 4: two events in one batch
        )
        val lines = DshTranscript.lines(file)
        assertEquals(5, lines.size, "a single-frame decode would stop after the header: $lines")

        val msgs = DshTranscriptReplay.read(file)
        assertEquals(4, msgs.size)
        assertEquals(ChatRole.USER to "hello", msgs[0].role to msgs[0].text)
        assertEquals(ChatRole.ASSISTANT to "hi there", msgs[1].role to msgs[1].text)
        assertEquals(ChatRole.USER to "more", msgs[2].role to msgs[2].text)
        assertEquals(ChatRole.ASSISTANT to "ok", msgs[3].role to msgs[3].text)
    }

    /**
     * A torn trailing frame is the NORMAL state of a session dsh is writing right now. It must yield the
     * good prefix, never an error and never an empty read.
     */
    @Test
    fun a_truncated_final_frame_is_not_corruption() {
        val dir = tmp()
        val whole = Zstd.compress(header().toByteArray()) + Zstd.compress(userMsg("kept", 1).toByteArray())
        val torn = Zstd.compress(assistantMsg("half-written", 2).toByteArray())
        val file = dir.also { it.createDirectories() }.resolve("session.jsonl.zstd")
        // keep only the first few bytes of the last frame — a writer caught mid-flush
        Files.write(file, whole + torn.copyOfRange(0, torn.size / 2))

        val msgs = DshTranscriptReplay.read(file)
        assertEquals(1, msgs.size, "the intact prefix must survive a torn tail frame")
        assertEquals("kept", msgs[0].text)
        assertEquals("session-abc", DshTranscript.header(file)?.id)
    }

    @Test
    fun a_trailing_partial_line_inside_a_good_frame_is_dropped() {
        // no terminating newline on the last record: it is still being written
        val file = writeFrames(tmp(), header(), userMsg("complete", 1) + """{"type":"user/message","seq":2,"da""")
        val msgs = DshTranscriptReplay.read(file)
        assertEquals(1, msgs.size)
        assertEquals("complete", msgs[0].text)
    }

    @Test
    fun uncompressed_transcripts_are_read_too() {
        val dir = tmp().also { it.createDirectories() }
        val file = dir.resolve("session.jsonl")
        file.writeText(header() + userMsg("plain", 1))
        assertEquals(listOf("plain"), DshTranscriptReplay.read(file).map { it.text })
    }

    // ---- header contract ----

    @Test
    fun header_cwd_is_read_verbatim_never_derived_from_the_directory_name() {
        // the containing directory is deliberately named for a DIFFERENT path; the header must win
        val dir = tmp().resolve(DshPaths.projectKey("/somewhere/else")).resolve("session-abc")
        val file = writeFrames(dir, header(cwd = "/real/project/path"))
        assertEquals("/real/project/path", DshTranscript.header(file)?.cwd)
    }

    @Test
    fun an_unsupported_format_version_is_refused_rather_than_guessed_at() {
        val file = writeFrames(tmp(), header(version = 1), userMsg("x", 1))
        assertEquals(false, DshTranscript.header(file)?.isSupported)
    }

    @Test
    fun a_header_without_a_version_is_treated_as_unsupported_not_as_v0() {
        val file = writeFrames(tmp(), """{"type":"session","id":"session-x","cwd":"/w","createdAt":1}""" + "\n")
        assertEquals(false, DshTranscript.header(file)?.isSupported)
    }

    @Test
    fun subagent_sessions_are_flagged_so_the_scanner_can_hide_them() {
        val file = writeFrames(tmp(), header(origin = "subagent"))
        assertEquals(true, DshTranscript.header(file)?.isSubagent)
        assertEquals(false, DshTranscript.header(tmp().let { writeFrames(it, header()) })?.isSubagent)
    }

    @Test
    fun a_file_whose_first_line_is_not_a_session_record_has_no_header() {
        assertNull(DshTranscript.header(writeFrames(tmp(), userMsg("orphan", 1))))
    }

    // ---- titles ----

    @Test
    fun the_last_title_event_wins() {
        val file = writeFrames(
            tmp(),
            header(),
            userMsg("first prompt", 1),
            """{"type":"session/title","seq":2,"time":1,"data":{"title":"Draft title","messageSeqs":[1],"source":{"kind":"provider","provider":"llm"}}}""" + "\n",
            """{"type":"session/title","seq":3,"time":2,"data":{"title":"Final title","messageSeqs":[1],"source":{"kind":"user"}}}""" + "\n",
        )
        assertEquals("Final title", DshTranscript.title(DshTranscript.lines(file)))
    }

    @Test
    fun without_a_title_event_the_first_user_message_is_the_fallback() {
        val file = writeFrames(tmp(), header(), userMsg("summarize the repo", 1), assistantMsg("sure", 2))
        assertEquals("summarize the repo", DshTranscript.title(DshTranscript.lines(file)))
    }

    // ---- defensive parsing ----

    /**
     * The batched chunk rows are the STREAMING deltas of a reply that also lands whole as
     * `assistant/message`. Replaying both would print the reply twice, so the chunk rows must produce no
     * replay rows at all.
     */
    @Test
    fun packed_chunk_rows_never_duplicate_the_assembled_assistant_message() {
        val chunks = """{"type":"text-chunks","seq0":2,"time0":1700000000002,""" +
            """"data":{"turn":1,"step":1,"index":0,"dt":[5,5],"texts":["hi ","the","re"]}}""" + "\n"
        val file = writeFrames(tmp(), header(), userMsg("hello", 1), chunks, assistantMsg("hi there", 5))
        val msgs = DshTranscriptReplay.read(file)
        assertEquals(
            listOf("hello", "hi there"), msgs.map { it.text },
            "the streamed deltas must not be replayed alongside the assembled message",
        )
    }

    @Test
    fun a_malformed_line_yields_no_row_instead_of_throwing() {
        val file = writeFrames(
            tmp(),
            header(),
            userMsg("before", 1),
            "{not json at all\n" + """{"type":"user/message","seq":9}""" + "\n", // no data at all
            userMsg("after", 3),
        )
        assertEquals(listOf("before", "after"), DshTranscriptReplay.read(file).map { it.text })
    }

    @Test
    fun ignorable_events_are_skipped() {
        val ignorable = """{"type":"user/message","seq":2,"time":1,"ignorable":true,""" +
            """"data":{"id":"m2","role":"user","content":[{"type":"text","text":"internal"}],"source":"system"}}""" + "\n"
        val file = writeFrames(tmp(), header(), userMsg("real", 1), ignorable)
        assertEquals(listOf("real"), DshTranscriptReplay.read(file).map { it.text })
    }

    @Test
    fun reasoning_blocks_are_not_folded_into_chat_text() {
        val mixed = """{"type":"assistant/message","seq":2,"time":1,"data":{"turn":1,"step":1,"message":""" +
            """{"id":"a2","role":"assistant","content":[{"type":"reasoning","text":"let me think"},""" +
            """{"type":"text","text":"the answer"}],"source":"model"}}}""" + "\n"
        val file = writeFrames(tmp(), header(), mixed)
        assertEquals(listOf("the answer"), DshTranscriptReplay.read(file).map { it.text })
    }

    @Test
    fun a_missing_file_reads_as_empty() {
        assertTrue(DshTranscript.lines(tmp().resolve("nope.jsonl.zstd")).isEmpty())
        assertTrue(DshTranscriptReplay.read(tmp().resolve("nope.jsonl.zstd")).isEmpty())
    }
}
