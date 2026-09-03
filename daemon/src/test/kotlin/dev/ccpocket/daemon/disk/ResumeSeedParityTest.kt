package dev.ccpocket.daemon.disk

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #303: opening a Claude session parsed the same transcript THREE times (summarize → title,
 * [TranscriptScanner.lastModel], [TranscriptScanner.lastContextTokens]) — three full reads of a file that
 * reaches megabytes, back to back on the open path. [TranscriptScanner.resumeSeed] does it in one.
 *
 * The three readers keep their many other callers and stay the SPEC: every fixture here is asserted field
 * by field against them, so a rule that drifts in one place (the sidechain skip, the `<synthetic>` skip, the
 * title precedence, the first-user-turn capture) turns this red instead of quietly seeding a wrong resume.
 */
class ResumeSeedParityTest {

    @BeforeTest
    fun reset() = TranscriptScanner.clearSeedCacheForTest()

    @AfterTest
    fun cleanup() = TranscriptScanner.clearSeedCacheForTest()

    /** The load-bearing assertion of this file: one pass must equal the three it replaces. */
    private fun assertParity(name: String, lines: List<String>) {
        val f = Files.createTempDirectory("ccp-seed").resolve("$name.jsonl")
        f.writeText(lines.joinToString("\n"))
        val seed = assertNotNull(TranscriptScanner.resumeSeed(f), "$name: a readable transcript always seeds")
        val summary = TranscriptScanner.summarize(f)
        assertEquals(summary?.title, seed.title, "$name: title")
        assertEquals(summary?.gitBranch, seed.gitBranch, "$name: gitBranch")
        assertEquals(TranscriptScanner.lastModel(f), seed.model, "$name: model")
        assertEquals(TranscriptScanner.lastContextTokens(f), seed.contextTokens, "$name: contextTokens")
    }

    @Test
    fun a_full_transcript_seeds_exactly_what_the_three_readers_say() {
        assertParity(
            "sess-full",
            listOf(
                """{"type":"mode","mode":"normal"}""",
                """{"type":"user","message":{"role":"user","content":"first real prompt"},"cwd":"/repo","gitBranch":"main","version":"2.1.165"}""",
                """{"type":"assistant","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":100,"output_tokens":5},"content":[]}}""",
                """{"type":"user","toolUseResult":{"x":1},"message":{"role":"user","content":[{"type":"tool_result","content":"r"}]}}""",
                """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":1000,"output_tokens":10,"cache_read_input_tokens":500},"content":[]}}""",
                """{"type":"ai-title","aiTitle":"My Title"}""",
                """{"type":"custom-title","customTitle":"cc-renamed"}""",
            ),
        )
    }

    @Test
    fun sidechain_and_synthetic_records_are_skipped_the_same_way() {
        // a Task subagent's turn shares the file but ran the SUBAGENT's model against the SUBAGENT's window;
        // `<synthetic>` is an API-error placeholder. Neither may win the model, and the sidechain's usage
        // must not become this session's occupancy.
        assertParity(
            "sess-sc",
            listOf(
                """{"type":"user","message":{"role":"user","content":"hi"},"gitBranch":"feat/x"}""",
                """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":1000,"output_tokens":10,"cache_read_input_tokens":500},"content":[]}}""",
                """{"type":"assistant","isSidechain":true,"message":{"model":"claude-haiku-4-5","usage":{"input_tokens":99999,"output_tokens":10},"content":[]}}""",
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"api error"}]}}""",
            ),
        )
    }

    @Test
    fun title_falls_back_the_same_way_at_every_step() {
        assertParity(
            "sess-ai-title",
            listOf(
                """{"type":"user","message":{"role":"user","content":"prompt"},"gitBranch":"main"}""",
                """{"type":"ai-title","aiTitle":"guessed"}""",
            ),
        )
        assertParity(
            "sess-first-prompt", // no title record at all ⇒ first line of the first prompt, capped at 60
            listOf(
                """{"type":"user","message":{"role":"user","content":"${"x".repeat(80)}\nsecond line"}}""",
            ),
        )
        assertParity(
            "sess-slash-only", // opened by a slash command: no real user turn, prompt lives only in last-prompt (issue #341)
            listOf(
                """{"type":"attachment","attachment":{"type":"skill_listing","content":"…"}}""",
                """{"type":"last-prompt","lastPrompt":"/record-issue 打开会话偶发失败"}""",
                // the envelope fallback: with no real user turn, gitBranch comes off the first cwd-bearing record
                """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":9,"output_tokens":3},"content":[]},"cwd":"/repo/proj","gitBranch":"main"}""",
                """{"type":"user","toolUseResult":{"x":1},"message":{"role":"user","content":[{"type":"tool_result","content":"r"}]},"cwd":"/repo/other","gitBranch":"other"}""",
                // a second, later slash command must NOT override the opening one as the title
                """{"type":"last-prompt","lastPrompt":"/record-issue something else later"}""",
            ),
        )
        assertParity(
            "sess-blank", // blank prompt, no titles ⇒ the sessionId itself
            listOf("""{"type":"user","message":{"role":"user","content":""}}"""),
        )
        assertParity(
            "sess-renamed-only", // a rename with nothing else still surfaces
            listOf("""{"type":"custom-title","customTitle":"My Renamed Session"}"""),
        )
    }

    @Test
    fun the_first_user_turn_wins_the_branch_in_both_readers() {
        // a session that outlived a branch switch: the header is captured once, at the first REAL user turn
        assertParity(
            "sess-branch",
            listOf(
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"r"}]}}""",
                """{"type":"user","message":{"role":"user","content":"real"},"gitBranch":"first"}""",
                """{"type":"user","message":{"role":"user","content":"later"},"gitBranch":"second"}""",
            ),
        )
    }

    @Test
    fun a_transcript_with_no_title_at_all_still_seeds_model_and_tokens() {
        // summarize answers null here (no prompt, no title record) — the seed must mirror that in `title`
        // WITHOUT losing the two fields that come from assistant lines, or a resume would show no model.
        // The record deliberately carries cwd+gitBranch: the #341 envelope fallback must stay gated behind
        // "summarize produced a summary", or seed.gitBranch would drift from summary?.gitBranch (null) here.
        val f = Files.createTempDirectory("ccp-seed").resolve("sess-headless.jsonl")
        f.writeText("""{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":7,"output_tokens":3},"content":[]},"cwd":"/repo","gitBranch":"main"}""")
        assertNull(TranscriptScanner.summarize(f))
        val seed = assertNotNull(TranscriptScanner.resumeSeed(f))
        assertNull(seed.title)
        assertNull(seed.gitBranch)
        assertEquals("claude-opus-4-8", seed.model)
        assertEquals(10L, seed.contextTokens)
    }

    @Test
    fun an_absent_transcript_seeds_nothing() {
        val dir = Files.createTempDirectory("ccp-seed")
        assertNull(TranscriptScanner.resumeSeed(dir.resolve("nope.jsonl")))
    }

    @Test
    fun the_memo_is_keyed_by_mtime_not_by_path() {
        // Transcripts are append-only, so a moved mtime is the only way a parse of one goes stale. Proven
        // both ways: content rewritten UNDER the same stamp keeps the old seed (⇒ the file was not re-read),
        // and moving the stamp re-reads.
        val f = Files.createTempDirectory("ccp-seed").resolve("sess-memo.jsonl")
        f.writeText("""{"type":"assistant","message":{"model":"first-model","usage":{"input_tokens":10,"output_tokens":0},"content":[]}}""")
        val stamp = f.getLastModifiedTime()
        val first = assertNotNull(TranscriptScanner.resumeSeed(f))
        assertEquals("first-model", first.model)

        f.writeText("""{"type":"assistant","message":{"model":"second-model","usage":{"input_tokens":20,"output_tokens":0},"content":[]}}""")
        f.setLastModifiedTime(stamp)
        assertEquals(first, TranscriptScanner.resumeSeed(f), "same (path, mtime) ⇒ the memoized parse")

        f.setLastModifiedTime(FileTime.fromMillis(stamp.toMillis() + 2_000))
        val second = assertNotNull(TranscriptScanner.resumeSeed(f))
        assertEquals("second-model", second.model, "a moved mtime must re-read")
        assertEquals(20L, second.contextTokens)
    }
}
