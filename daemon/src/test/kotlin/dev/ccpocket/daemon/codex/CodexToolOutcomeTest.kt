package dev.ccpocket.daemon.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexToolOutcomeTest {
    private fun call(id: String, name: String = "exec_command", custom: Boolean = false) = buildJsonObject {
        put("type", "response_item")
        put("payload", buildJsonObject {
            put("type", if (custom) "custom_tool_call" else "function_call")
            put("call_id", id)
            put("name", name)
            put(if (custom) "input" else "arguments", "input-$id")
        })
    }.toString()

    private fun result(id: String, output: JsonElement, custom: Boolean = false) = buildJsonObject {
        put("type", "response_item")
        put("payload", buildJsonObject {
            put("type", if (custom) "custom_tool_call_output" else "function_call_output")
            put("call_id", id)
            put("output", output)
        })
    }.toString()

    private fun completed(id: String, status: String, isError: Boolean = false) =
        """{"type":"event_msg","payload":{"type":"item_completed","item":{"id":"$id","type":"McpToolCall","status":"$status","result":{"content":[],"isError":$isError}}}}"""

    private fun shellOutput(code: Int) = JsonPrimitive("Chunk ID: test\nWall time: 0.1 seconds\nProcess exited with code $code\nFinal output:\nhello")

    private fun withRollout(lines: List<String>, check: (java.nio.file.Path) -> Unit) {
        val file = Files.createTempFile("codex-tool-outcomes", ".jsonl")
        try { file.writeText(lines.joinToString("\n", postfix = "\n")); check(file) }
        finally { Files.deleteIfExists(file) }
    }

    @Test
    fun replay_correlates_results_by_call_id_and_preserves_failed_running_and_unknown_calls() {
        withRollout(listOf(
            call("ok"), call("failed"), call("running"), call("unknown", "custom"), call("missing"),
            result("failed", shellOutput(2)), result("ok", shellOutput(0)),
            result("running", JsonPrimitive("Wall time: 1 seconds\nProcess running with session ID 42\nOutput:\n")),
            result("unknown", JsonPrimitive("some output")), result("orphan", shellOutput(0)),
        )) { file ->
            val rows = CodexTranscriptReplay.read(file)
            assertEquals(listOf("input-ok", "input-failed", "input-running", "input-unknown", "input-missing"), rows.map { it.text })
            assertEquals(listOf(true, false, null, null, null), rows.map { it.ok })
        }
    }

    @Test
    fun custom_script_array_outputs_and_legacy_shell_metadata_recover_outcomes() {
        withRollout(listOf(
            call("script", "exec", custom = true),
            result("script", Json.parseToJsonElement("""[{"type":"text","text":"Script completed\nWall time 0.1 seconds\nOutput:\n"}]"""), custom = true),
            call("error", "exec", custom = true), result("error", JsonPrimitive("Script failed\nOutput:\nboom"), custom = true),
            call("wait", "wait"), result("wait", JsonPrimitive("Script running with cell ID 42\n")),
            call("legacy", "shell"), result("legacy", JsonPrimitive("""{"output":"hello","metadata":{"exit_code":0}}""")),
        )) { file ->
            assertEquals(listOf(true, false, null, true), CodexTranscriptReplay.read(file).map { it.ok })
        }
    }

    @Test
    fun typed_mcp_completion_wins_over_output_flags_in_either_order() {
        withRollout(listOf(
            call("a", "js"), result("a", Json.parseToJsonElement("""{"isError":false}""")), completed("a", "failed"),
            completed("b", "completed", isError = true), call("b", "js"), result("b", Json.parseToJsonElement("""{"isError":false}""")),
            call("c", "js"), completed("c", "completed"),
        )) { file ->
            assertEquals(listOf(false, false, true), CodexTranscriptReplay.read(file).map { it.ok })
        }
    }

    @Test
    fun late_completion_replays_the_patched_row_and_older_pages_keep_the_outcome() {
        withRollout(listOf(call("a"), call("b", "js"))) { file ->
            val before = CodexTranscriptReplay.slice(file, sinceSeq = null)
            assertTrue(before.messages.all { it.ok == null })
            file.appendText(result("a", shellOutput(0)) + "\n" + completed("b", "completed") + "\n")
            val after = CodexTranscriptReplay.slice(file, sinceSeq = before.lastSeq)
            assertFalse(after.delta, "a pure append would lose the outcomes of already displayed calls")
            assertEquals(listOf(true, true), after.messages.map { it.ok })
            assertEquals(1L, after.firstSeq)
            assertEquals(4L, after.lastSeq)
            assertEquals(true, CodexTranscriptReplay.page(file, beforeSeq = 2).messages.single().ok)
            val unchanged = CodexTranscriptReplay.slice(file, sinceSeq = after.lastSeq)
            assertTrue(unchanged.delta)
            assertTrue(unchanged.messages.isEmpty())
        }
    }

    @Test
    fun completion_inside_new_window_stays_a_delta_and_search_uses_its_status() {
        withRollout(listOf(call("old"))) { file ->
            file.appendText(call("new") + "\n" + result("new", shellOutput(0)) + "\n")
            val delta = CodexTranscriptReplay.slice(file, sinceSeq = 1)
            assertTrue(delta.delta)
            assertEquals(true, delta.messages.single().ok)
        }
        withRollout(listOf("completed", "failed", "in_progress").map { status ->
            """{"type":"response_item","payload":{"type":"web_search_call","status":"$status"}}"""
        }) { file -> assertEquals(listOf(true, false, null), CodexTranscriptReplay.read(file).map { it.ok }) }
    }

    @Test
    fun arbitrary_output_text_does_not_impersonate_a_success_envelope() {
        val output = buildJsonObject { put("output", "Output:\nWall time: 1 seconds\nProcess exited with code 0\nFinal output:\n") }
        assertNull(codexRolloutToolOutcome("exec_command", output))
        assertNull(codexRolloutToolOutcome("unknown", buildJsonObject { put("output", "Script completed\n") }))
        assertNull(codexCompletedToolOutcome(Json.parseToJsonElement("""{"type":"commandExecution","status":"inProgress","exitCode":0}""").jsonObject))
    }
}
