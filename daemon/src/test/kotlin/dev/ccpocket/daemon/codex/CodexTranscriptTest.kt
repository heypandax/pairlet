package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies the Codex rollout scanner/replay against the real 0.124 `{timestamp,type,payload}` schema:
 *  session_meta carries id+cwd; the first real user turn skips the synthetic <environment_context>/<permissions> blocks. */
class CodexTranscriptTest {
    private val rollout = """
        {"timestamp":"t0","type":"session_meta","payload":{"id":"thr-xyz","cwd":"/repo","cli_version":"0.124.0"}}
        {"timestamp":"t1","type":"event_msg","payload":{"type":"task_started","turn_id":"u1"}}
        {"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"<permissions instructions> ..."}]}}
        {"timestamp":"t3","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context> ..."}]}}
        {"timestamp":"t4","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"build the thing"}]}}
        {"timestamp":"t5","type":"response_item","payload":{"type":"reasoning","content":[{"type":"reasoning_text","text":"hmm"}]}}
        {"timestamp":"t6","type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\"command\":\"ls\"}"}}
        {"timestamp":"t7","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}}
    """.trimIndent()

    private fun tempRollout() = Files.createTempFile("rollout-2026-06-24T00-00-00-thr-xyz", ".jsonl").also { it.writeText(rollout) }

    @Test
    fun summarize_extracts_meta_and_skips_synthetic_user_blocks() {
        val s = CodexTranscriptScanner.summarize(tempRollout(), "/repo")!!
        assertEquals("thr-xyz", s.sessionId)
        assertEquals("/repo", s.cwd)
        assertEquals("build the thing", s.firstPrompt) // the <environment_context> user block is skipped
        assertEquals(1, s.messageCount)
        assertEquals(AgentKind.CODEX, s.agent)
        assertEquals("0.124.0", s.version)
    }

    @Test
    fun summarize_filters_by_cwd() {
        assertNull(CodexTranscriptScanner.summarize(tempRollout(), "/some/other/dir"))
    }

    @Test
    fun summarize_prefers_codex_thread_title_over_first_prompt() {
        // Codex records the session's title in session_index.jsonl (not the rollout) — the scanner must
        // surface it instead of the prompt's first line (issue #64)
        val titled = CodexTranscriptScanner.summarize(tempRollout(), "/repo", mapOf("thr-xyz" to "Build the widget"))!!
        assertEquals("Build the widget", titled.title)
        // no index entry (older/untitled session) → unchanged first-prompt fallback, never worse than before
        val fallback = CodexTranscriptScanner.summarize(tempRollout(), "/repo", emptyMap())!!
        assertEquals("build the thing", fallback.title)
        // a blank thread_name is ignored, not shown as an empty title
        val blank = CodexTranscriptScanner.summarize(tempRollout(), "/repo", mapOf("thr-xyz" to "  "))!!
        assertEquals("build the thing", blank.title)
    }

    @Test
    fun summarize_skips_injected_agents_md_block() {
        // Codex auto-prepends the repo's AGENTS.md as a `user` turn (`# AGENTS.md instructions for <path>`);
        // it must not seed the title/preview the way `<environment_context>` already didn't (real bug: rows
        // titled "# AGENTS.md instructions for /Users/…").
        val f = Files.createTempFile("rollout-2026-06-24T00-00-00-thr-am", ".jsonl").also {
            it.writeText(
                """{"timestamp":"t0","type":"session_meta","payload":{"id":"thr-am","cwd":"/repo","cli_version":"0.124.0"}}""" + "\n" +
                    """{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions for /repo\n\n<INSTRUCTIONS>\nuse chinese\n"}]}}""" + "\n" +
                    """{"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"生成设计稿"}]}}""",
            )
        }
        val s = CodexTranscriptScanner.summarize(f, "/repo", emptyMap())!!
        assertEquals("生成设计稿", s.firstPrompt)
        assertEquals("生成设计稿", s.title)
        assertEquals(1, s.messageCount) // the AGENTS.md turn isn't a real user turn
    }

    @Test
    fun summarize_skips_files_mentioned_block_and_never_titles_with_uuid() {
        // The `# Files mentioned by the user:` @-mention expansion leads with a newline, so its first line is
        // blank — untreated it was skipping the title straight to the raw session UUID (the reported bug).
        val f = Files.createTempFile("rollout-2026-06-24T00-00-00-thr-fm", ".jsonl").also {
            it.writeText(
                """{"timestamp":"t0","type":"session_meta","payload":{"id":"thr-fm","cwd":"/repo","cli_version":"0.124.0"}}""" + "\n" +
                    """{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"\n# Files mentioned by the user:\n\n## foo.png"}]}}""" + "\n" +
                    """{"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"研究这个项目"}]}}""",
            )
        }
        val s = CodexTranscriptScanner.summarize(f, "/repo", emptyMap())!!
        assertEquals("研究这个项目", s.firstPrompt)
        assertEquals("研究这个项目", s.title)
        assertNotEquals("thr-fm", s.title) // must never fall back to the session UUID
    }

    @Test
    fun summarize_title_uses_first_non_blank_line() {
        // a real prompt that opens with a blank line must title on its first non-blank line, not collapse to UUID
        val f = Files.createTempFile("rollout-2026-06-24T00-00-00-thr-bl", ".jsonl").also {
            it.writeText(
                """{"timestamp":"t0","type":"session_meta","payload":{"id":"thr-bl","cwd":"/repo","cli_version":"0.124.0"}}""" + "\n" +
                    """{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"\n\n  hello there  \nmore"}]}}""",
            )
        }
        assertEquals("hello there", CodexTranscriptScanner.summarize(f, "/repo", emptyMap())!!.title)
    }

    @Test
    fun replay_skips_injected_agents_md_block() {
        val f = Files.createTempFile("rollout-2026-06-24T00-00-00-thr-ar", ".jsonl").also {
            it.writeText(
                """{"timestamp":"t0","type":"session_meta","payload":{"id":"thr-ar","cwd":"/repo","cli_version":"0.124.0"}}""" + "\n" +
                    """{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions for /repo\n\nblah"}]}}""" + "\n" +
                    """{"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"real question"}]}}""" + "\n" +
                    """{"timestamp":"t3","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"answer"}]}}""",
            )
        }
        val msgs = CodexTranscriptReplay.read(f)
        assertEquals(2, msgs.size) // AGENTS.md user block dropped; real user + assistant remain
        assertEquals("real question", msgs[0].text)
        assertEquals("answer", msgs[1].text)
    }

    @Test
    fun readThreadNames_parses_index_last_wins_and_skips_blanks() {
        val index = Files.createTempFile("session_index", ".jsonl").also {
            it.writeText(
                """{"id":"thr-a","thread_name":"First title","updated_at":"t0"}""" + "\n" +
                    """{"id":"thr-b","thread_name":"","updated_at":"t0"}""" + "\n" +   // blank → skipped
                    """not json""" + "\n" +                                              // junk line → skipped
                    """{"id":"thr-a","thread_name":"Renamed title","updated_at":"t1"}""", // rename → last wins
            )
        }
        val names = CodexTranscriptScanner.readThreadNames(index)
        assertEquals("Renamed title", names["thr-a"])
        assertNull(names["thr-b"])
        assertEquals(1, names.size)
    }

    @Test
    fun readThreadNames_absent_index_is_empty() {
        assertTrue(CodexTranscriptScanner.readThreadNames(Files.createTempDirectory("x").resolve("nope.jsonl")).isEmpty())
    }

    @Test
    fun summarize_matches_cwd_os_normalized() {
        // slash direction / trailing separator must not hide a session (issue #19's Codex sibling —
        // an exact compare silently dropped Windows sessions whose recorded cwd differed in form)
        val f = Files.createTempFile("rollout-2026-06-24T00-00-00-thr-win", ".jsonl").also {
            it.writeText("""{"timestamp":"t0","type":"session_meta","payload":{"id":"thr-win","cwd":"C:\\Users\\X\\proj","cli_version":"0.124.0"}}""" + "\n" +
                """{"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}}""")
        }
        assertEquals("thr-win", CodexTranscriptScanner.summarize(f, "C:/Users/X/proj")?.sessionId)
        assertEquals("thr-win", CodexTranscriptScanner.summarize(f, "C:\\Users\\X\\proj\\")?.sessionId)
    }

    @Test
    fun replay_flattens_user_assistant_and_tool() {
        val msgs = CodexTranscriptReplay.read(tempRollout())
        // synthetic <permissions>/<environment_context> + reasoning are skipped; user + tool + assistant remain
        assertEquals(3, msgs.size)
        assertEquals(ChatRole.USER, msgs[0].role)
        assertEquals("build the thing", msgs[0].text)
        assertEquals(ChatRole.TOOL, msgs[1].role)
        assertEquals("shell", msgs[1].tool)
        assertEquals(ChatRole.ASSISTANT, msgs[2].role)
        assertEquals("done", msgs[2].text)
    }

    @Test
    fun replay_handles_missing_file() {
        assertTrue(CodexTranscriptReplay.read(Files.createTempDirectory("x").resolve("nope.jsonl")).isEmpty())
    }

    @Test
    fun cwdsByNewest_aggregates_per_cwd_and_skips_metaless_files() {
        // a Codex-only dir must surface in the directory list even with zero Claude history
        fun rolloutFor(cwd: String, id: String) = Files.createTempFile("rollout-x-$id", ".jsonl").also {
            it.writeText("""{"timestamp":"t0","type":"session_meta","payload":{"id":"$id","cwd":"$cwd","cli_version":"0.124.0"}}""")
        }
        val a1 = rolloutFor("/only-codex", "thr-a1")
        val a2 = rolloutFor("/only-codex", "thr-a2")
        val b = rolloutFor("/other", "thr-b")
        val junk = Files.createTempFile("rollout-x-junk", ".jsonl").also { it.writeText("not json") }
        Files.setLastModifiedTime(a1, java.nio.file.attribute.FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(a2, java.nio.file.attribute.FileTime.fromMillis(2_000))
        Files.setLastModifiedTime(b, java.nio.file.attribute.FileTime.fromMillis(3_000))
        val cwds = CodexTranscriptScanner.cwdsByNewest(listOf(a1, a2, b, junk))
        assertEquals(mapOf("/only-codex" to 2_000L, "/other" to 3_000L), cwds)
    }
}
