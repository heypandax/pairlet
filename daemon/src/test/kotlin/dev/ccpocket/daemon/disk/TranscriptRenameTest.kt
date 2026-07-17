package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** issue #158: the idle-transcript rename append — the record must be exactly the CLI's own
 *  `custom-title` shape so claude (and every scanner) adopts it through the #14 fallback chain. */
class TranscriptRenameTest {

    @Test
    fun appends_the_cli_record_shape_and_the_scanner_adopts_it() {
        val dir = Files.createTempDirectory("ccp-rename")
        val f = dir.resolve("sess-1.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"first real prompt"},"cwd":"/repo"}""",
                """{"type":"ai-title","aiTitle":"ai guessed this"}""",
            ).joinToString("\n") + "\n",
        )

        assertTrue(TranscriptRename.append(f, "sess-1", "Auth refactor"))

        val record = f.readLines().last()
        // key order + fields mirror claude 2.1.210's own session-store renameSession append
        assertTrue(record.startsWith("""{"type":"custom-title","customTitle":"Auth refactor","sessionId":"sess-1","uuid":""""), record)
        assertTrue(Regex("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\"") in record, record)

        // the read half (#14) picks it up: custom-title beats ai-title beats firstPrompt
        val s = assertNotNull(TranscriptScanner.summarize(f))
        assertEquals("Auth refactor", s.title)
    }

    @Test
    fun a_second_rename_wins_last_record_rule() {
        val dir = Files.createTempDirectory("ccp-rename")
        val f = dir.resolve("sess-2.jsonl")
        f.writeText("""{"type":"user","message":{"role":"user","content":"hi"},"cwd":"/repo"}""" + "\n")

        assertTrue(TranscriptRename.append(f, "sess-2", "first name"))
        assertTrue(TranscriptRename.append(f, "sess-2", "second name"))

        assertEquals("second name", assertNotNull(TranscriptScanner.summarize(f)).title)
    }

    @Test
    fun rescues_a_tail_line_that_lost_its_newline() {
        // a crashed writer can leave the file without a trailing \n — the record must land on its OWN
        // line, never merged into the partial tail
        val dir = Files.createTempDirectory("ccp-rename")
        val f = dir.resolve("sess-3.jsonl")
        f.writeText("""{"type":"user","message":{"role":"user","content":"hi"},"cwd":"/repo"}""") // no trailing \n

        assertTrue(TranscriptRename.append(f, "sess-3", "titled"))

        val lines = f.readText().trimEnd('\n').lines()
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines[1].startsWith("""{"type":"custom-title""""), lines[1])
        assertEquals("titled", assertNotNull(TranscriptScanner.summarize(f)).title)
    }

    @Test
    fun missing_transcript_is_a_clean_false_never_a_create() {
        val dir = Files.createTempDirectory("ccp-rename")
        val f = dir.resolve("no-such.jsonl")
        assertFalse(TranscriptRename.append(f, "no-such", "x"))
        assertFalse(Files.exists(f), "a rename must never mint a transcript")
    }
}
