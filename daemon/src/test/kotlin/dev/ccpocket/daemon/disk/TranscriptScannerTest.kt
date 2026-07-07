package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TranscriptScannerTest {

    @Test
    fun counts_only_real_user_turns_and_reads_header() {
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-1.jsonl")
        f.writeText(
            listOf(
                """{"type":"mode","mode":"normal"}""",
                """{"type":"permission-mode","permissionMode":"default"}""",
                """{"type":"file-history-snapshot","messageId":"m"}""",
                """{"type":"user","message":{"role":"user","content":"first real prompt"},"cwd":"/repo","gitBranch":"main","version":"2.1.165"}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]}}""",
                """{"type":"user","toolUseResult":{"x":1},"message":{"role":"user","content":[{"type":"tool_result","content":"r"}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"r2"}]}}""",
                """{"type":"ai-title","aiTitle":"My Title"}""",
            ).joinToString("\n"),
        )

        val s = assertNotNull(TranscriptScanner.summarize(f))
        assertEquals("sess-1", s.sessionId)
        assertEquals(1, s.messageCount) // C5: tool-result user turns excluded
        assertEquals("My Title", s.title) // ai-title preferred over firstPrompt
        assertEquals("first real prompt", s.firstPrompt)
        assertEquals("/repo", s.cwd)
        assertEquals("main", s.gitBranch)
        assertEquals("2.1.165", s.version)
    }

    @Test
    fun custom_title_overrides_ai_title_last_write_wins() {
        // Claude Code persists the user's session rename as a `custom-title` record (issue #14); it must win
        // over the AI-generated `ai-title`, and a later rename overrides an earlier one.
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-2.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"hi"},"cwd":"/repo"}""",
                """{"type":"ai-title","aiTitle":"claude-session-browser-dashboard"}""",
                """{"type":"custom-title","customTitle":"cc"}""",
                """{"type":"custom-title","customTitle":"cc-renamed"}""",
            ).joinToString("\n"),
        )
        val s = assertNotNull(TranscriptScanner.summarize(f))
        assertEquals("cc-renamed", s.title)
    }

    @Test
    fun custom_title_alone_surfaces_a_renamed_session() {
        // a renamed session with no captured first prompt must still surface (the guard includes customTitle)
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-3.jsonl")
        f.writeText("""{"type":"custom-title","customTitle":"My Renamed Session"}""")
        val s = assertNotNull(TranscriptScanner.summarize(f))
        assertEquals("My Renamed Session", s.title)
    }

    @Test
    fun last_model_returns_the_newest_assistant_model() {
        // a cold resume reads the session's real model from the last assistant turn (issue #27)
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-m.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"hi"}}""",
                """{"type":"assistant","message":{"model":"claude-sonnet-4-5","content":[{"type":"text","text":"a"}]}}""",
                """{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"b"}]}}""",
            ).joinToString("\n"),
        )
        assertEquals("claude-opus-4-8", TranscriptScanner.lastModel(f))
    }

    @Test
    fun last_model_is_null_without_an_assistant_turn() {
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-n.jsonl")
        f.writeText("""{"type":"user","message":{"role":"user","content":"hi"}}""")
        assertNull(TranscriptScanner.lastModel(f))
    }

    @Test
    fun last_model_skips_sidechain_and_synthetic_records() {
        // Task-subagent turns land in the same .jsonl with isSidechain:true and may run a different model;
        // API-error placeholders carry the literal "<synthetic>". Neither must win the backfill.
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-sc.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"main"}]}}""",
                """{"type":"assistant","isSidechain":true,"message":{"model":"claude-haiku-4-5","content":[{"type":"text","text":"subagent"}]}}""",
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"api error"}]}}""",
            ).joinToString("\n"),
        )
        assertEquals("claude-opus-4-8", TranscriptScanner.lastModel(f))
    }

    @Test
    fun last_context_tokens_skips_sidechain_records() {
        // the subagent's usage describes the SUBAGENT's context window — seeding the main thread's
        // occupancy from it would show a bogus statusline on resume
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-su.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"model":"m","usage":{"input_tokens":1000,"output_tokens":10,"cache_read_input_tokens":500},"content":[]}}""",
                """{"type":"assistant","isSidechain":true,"message":{"model":"m","usage":{"input_tokens":99999,"output_tokens":10},"content":[]}}""",
            ).joinToString("\n"),
        )
        assertEquals(1510L, TranscriptScanner.lastContextTokens(f)) // input 1000 + output 10 + cache_read 500
    }

    @Test
    fun syntheticTailStreak_counts_trailing_placeholders_only() {
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-dead.jsonl")
        f.writeText(
            listOf(
                // an EARLY placeholder followed by a real reply — the real reply resets the run
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""",
                """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"real"}]}}""",
                // a subagent line never counts either way
                """{"type":"assistant","isSidechain":true,"message":{"model":"<synthetic>","content":[]}}""",
                // the dead tail: two consecutive placeholders
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""",
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""",
            ).joinToString("\n"),
        )
        assertEquals(2, TranscriptScanner.syntheticTailStreak(f))

        val healthy = dir.resolve("sess-ok.jsonl")
        healthy.writeText("""{"type":"assistant","message":{"model":"m","content":[{"type":"text","text":"hi"}]}}""")
        assertEquals(0, TranscriptScanner.syntheticTailStreak(healthy))
        assertEquals(0, TranscriptScanner.syntheticTailStreak(dir.resolve("absent.jsonl")))
    }
}
