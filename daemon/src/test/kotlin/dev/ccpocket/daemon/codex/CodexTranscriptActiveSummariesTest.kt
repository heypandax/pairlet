package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR #296 review: [CodexTranscriptScanner.activeSummaries] walked a cwd's rollouts newest-first but SKIPPED
 * any file without a real user turn yet, so a terminal sitting at a fresh `codex` prompt — or a session the
 * phone had only just opened — made it keep walking back to yesterday's finished rollout and report that as
 * the project's active session (a ghost second row on the card, its id differing from the daemon's). The same
 * skip never cleared the cwd from `remaining` either, so this 10-second refresh reparsed all 800 rollouts on
 * every tick, forever.
 *
 * The rule pinned here: the NEWEST rollout of a cwd answers for that cwd, prompt or no prompt.
 */
class CodexTranscriptActiveSummariesTest {
    private val tmp = Files.createTempDirectory("ccp-codex-active")
    private val work = Files.createTempDirectory("ccp-codex-work").toString()
    private val now = System.currentTimeMillis()

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
        java.io.File(work).deleteRecursively()
    }

    /** A rollout for [cwd]; [prompt] = null reproduces what Codex has on disk the instant a session starts —
     *  session_meta plus its injected `<environment_context>` block, and nothing the user actually typed. */
    private fun rollout(id: String, cwd: String, prompt: String?, mtime: Long): Path {
        val lines = buildList {
            add("""{"timestamp":"t0","type":"session_meta","payload":{"id":"$id","cwd":"$cwd","cli_version":"0.124.0"}}""")
            add("""{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context> ..."}]}}""")
            if (prompt != null) {
                add("""{"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$prompt"}]}}""")
            }
        }
        return Files.createTempFile(tmp, "rollout-2026-08-25T00-00-00-$id", ".jsonl").also {
            it.writeText(lines.joinToString("\n"))
            Files.setLastModifiedTime(it, FileTime.fromMillis(mtime))
        }
    }

    @Test
    fun newest_promptless_rollout_answers_for_its_cwd_instead_of_an_older_finished_session() {
        // production hands this newest-first (CodexPaths.sessionFiles sorts by mtime), so the fixtures do too
        val files = listOf(
            rollout("thr-fresh", work, prompt = null, mtime = now),
            rollout("thr-yesterday", work, prompt = "ship the release", mtime = now - 86_400_000),
        )

        val summary = CodexTranscriptScanner.activeSummaries(setOf(work), files).getValue(work)

        assertEquals("thr-fresh", summary.sessionId, "the live rollout answers, not yesterday's dead one")
        assertEquals("", summary.title, "nothing to title it with yet — blank, never the session UUID")
        assertEquals(0, summary.messageCount)
        assertEquals(AgentKind.CODEX, summary.agent)
        assertTrue(summary.live, "the live window still follows the rollout's mtime")
    }

    @Test
    fun a_promptless_hit_settles_its_cwd_so_older_rollouts_are_never_consulted() {
        // Convergence, asserted through the only thing it changes on the outside: a cwd left in `remaining`
        // would keep matching the older files below and OVERWRITE the entry with each of them in turn.
        val files = listOf(
            rollout("thr-fresh", work, prompt = null, mtime = now),
            rollout("thr-older", work, prompt = "older work", mtime = now - 3_600_000),
            rollout("thr-oldest", work, prompt = "oldest work", mtime = now - 7_200_000),
        )

        val out = CodexTranscriptScanner.activeSummaries(setOf(work), files)

        assertEquals(listOf("thr-fresh"), out.values.map { it.sessionId }, "one cwd, one answer: the newest")
    }

    @Test
    fun rollouts_for_other_cwds_are_still_skipped_and_the_requested_spelling_is_kept() {
        val other = Files.createTempDirectory("ccp-codex-other").toString()
        try {
            val files = listOf(
                rollout("thr-other", other, prompt = "elsewhere", mtime = now),
                rollout("thr-wanted", "$work/", prompt = null, mtime = now - 1_000), // trailing-separator variant
            )

            val out = CodexTranscriptScanner.activeSummaries(setOf(work), files)

            assertEquals(setOf(work), out.keys, "keys keep the caller's spelling; matching is canonical")
            assertEquals("thr-wanted", out.getValue(work).sessionId)
        } finally {
            java.io.File(other).deleteRecursively()
        }
    }

    @Test
    fun a_rollout_whose_cwd_is_already_known_to_be_unwanted_is_never_opened() {
        // issue #300: a live project's newest rollout sits behind every other project's newer ones, and each
        // of those used to be OPENED (just to be discarded on its first line) on every 10-second tick. They
        // are prefiltered on the cwd the same tick's cwdsByNewest already memoized by (path, mtime).
        val other = Files.createTempDirectory("ccp-codex-decoy").toString()
        try {
            val decoy = rollout("thr-decoy", other, prompt = "elsewhere", mtime = now)
            val wanted = rollout("thr-wanted", work, prompt = "the live one", mtime = now - 1_000)
            val files = listOf(decoy, wanted) // decoy first, exactly where the other-project files sit

            // what the refresh does first in production — this is what fills the cwd memo
            CodexTranscriptScanner.cwdsByNewest(files)

            // Now make the decoy CLAIM the wanted cwd, with its mtime pinned. Only an actual open of its
            // first line can see that: if activeSummaries opened it, the decoy — being first in the list —
            // would answer for `work` and thr-wanted would never be reached.
            Files.writeString(
                decoy,
                """{"timestamp":"t0","type":"session_meta","payload":{"id":"thr-decoy","cwd":"$work","cli_version":"0.124.0"}}""" + "\n" +
                    """{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"decoy"}]}}""",
            )
            Files.setLastModifiedTime(decoy, FileTime.fromMillis(now))

            assertEquals(
                "thr-wanted",
                CodexTranscriptScanner.activeSummaries(setOf(work), files).getValue(work).sessionId,
                "an unwanted cwd costs a stat, not an open",
            )

            // …and the flip side of that memo, so this test can't pass for the wrong reason (e.g. the decoy
            // being skipped by something other than the cache): with a cold cache the file IS opened and its
            // new cwd is seen.
            CodexTranscriptScanner.clearForTest()
            assertEquals(
                "thr-decoy",
                CodexTranscriptScanner.activeSummaries(setOf(work), files).getValue(work).sessionId,
                "cold cache → the first line is read, and it now says this file is the wanted cwd's newest",
            )
        } finally {
            java.io.File(other).deleteRecursively()
            CodexTranscriptScanner.clearForTest()
        }
    }

    @Test
    fun a_newest_rollout_that_does_have_a_prompt_still_titles_itself_from_it() {
        val files = listOf(rollout("thr-titled", work, prompt = "build the thing", mtime = now))

        val summary = CodexTranscriptScanner.activeSummaries(setOf(work), files).getValue(work)

        assertEquals("build the thing", summary.title)
        assertEquals("build the thing", summary.firstPrompt)
        assertEquals(1, summary.messageCount)
    }
}
