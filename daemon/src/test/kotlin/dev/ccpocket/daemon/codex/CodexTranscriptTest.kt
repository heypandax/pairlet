package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
