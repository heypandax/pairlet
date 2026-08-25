package dev.ccpocket.daemon.codex

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Newer codex CLIs write a SECOND rollout file on resume — `rollout-<ts>-<origId>_<resumeId>.jsonl` —
 * whose session_meta carries the ORIGINAL session id. One session, two files. Listing both rows with the
 * same id crashed the phone's session list outright (LazyColumn duplicate key, found via a symbolicated
 * .ips on 2026-08-25; repro data was a real ~/Desktop/Brain codex resume from 08-23).
 *
 * Pins two things:
 *  1. the underscore-named resume rollout still parses and reports the ORIGINAL id (schema tripwire —
 *     if codex changes what the meta carries, this goes red before a phone crashes);
 *  2. the scan pipeline's contract: newest-first + distinctBy(sessionId) collapses the pair to ONE row,
 *     and the row is the RESUME file's (the live continuation).
 */
class CodexResumeRolloutDedupeTest {

    private fun rollout(firstPrompt: String) = """
        {"timestamp":"t0","type":"session_meta","payload":{"id":"01a031bc-9b7d-7070-9537-ba635e85b709","cwd":"/repo","cli_version":"0.124.0"}}
        {"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$firstPrompt"}]}}
    """.trimIndent()

    @Test
    fun a_resume_rollout_pair_collapses_to_one_row_and_the_resume_file_wins() {
        val original = Files.createTempFile("rollout-2026-08-23T20-07-19-01a031bc-9b7d-7070-9537-ba635e85b709", ".jsonl")
            .also { it.writeText(rollout("original turn")) }
        val resume = Files.createTempFile("rollout-2026-08-23T20-19-08-01a031bc-9b7d-7070-9537-ba635e85b709_01a031c7-6c2f-7873-9da8-6ccac6182d77", ".jsonl")
            .also { it.writeText(rollout("resumed turn")) }
        // deterministic recency without sleeping: stamp the resume file strictly newer
        Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(resume, java.nio.file.attribute.FileTime.fromMillis(2_000_000))

        val a = CodexTranscriptScanner.summarize(original, "/repo", emptyMap())
        val b = CodexTranscriptScanner.summarize(resume, "/repo", emptyMap())
        assertNotNull(a); assertNotNull(b)
        assertEquals(a.sessionId, b.sessionId, "resume rollout must report the ORIGINAL session id")

        // the exact pipeline scan() applies after summarize
        val rows = listOf(a, b).sortedByDescending { it.lastModified }.distinctBy { it.sessionId }
        assertEquals(1, rows.size, "one session id must yield ONE row")
        assertEquals("resumed turn", rows.single().firstPrompt, "the newest (resume) file must win")
    }
}
