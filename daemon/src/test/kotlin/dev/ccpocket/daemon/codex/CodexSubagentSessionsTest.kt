package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.session.ScanCompleteness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexSubagentSessionsTest {
    private val root = Files.createTempDirectory("ccp-codex-subagents")
    private val prompt = """{"execution_mode":"fresh","topic_id":"example-task"}"""
    private val spawned = """{"subagent":{"thread_spawn":{"parent_thread_id":"parent","depth":1}}}"""

    @BeforeTest
    fun reset() {
        CodexTranscriptScanner.clearForTest()
        CodexPaths.clearForTest()
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
        reset()
    }

    private fun rollout(
        id: String,
        source: String? = null,
        cwd: String = "/repo",
        mtime: Long = 1_700_000_000_000,
        text: String = prompt,
        extraMeta: Map<String, JsonElement> = emptyMap(),
    ): Path {
        val meta = buildJsonObject {
            put("type", "session_meta")
            putJsonObject("payload") {
                put("id", id)
                put("cwd", cwd)
                source?.let { put("source", Json.parseToJsonElement(it)) }
                extraMeta.forEach { (key, value) -> put(key, value) }
            }
        }
        val message = buildJsonObject {
            put("type", "response_item")
            putJsonObject("payload") {
                put("type", "message")
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", text) })
                })
            }
        }
        return root.resolve("rollout-$id.jsonl").also {
            it.writeText(listOf(
                meta.toString(),
                """{"type":"turn_context","payload":{"model":"test-model"}}""",
                """{"type":"event_msg","payload":{"type":"task_started","turn_id":"turn-1"}}""",
                message.toString(),
            ).joinToString("\n"))
            Files.setLastModifiedTime(it, FileTime.fromMillis(mtime))
        }
    }

    @Test
    fun subagent_sources_do_not_become_session_rows_even_with_index_titles() {
        val sources = listOf(
            spawned,
            """{"subagent":{"other":"guardian"}}""",
            """{"subagent":"review"}""",
            """{"subagent":"compact"}""",
            """{"subagent":{"future_task":{}}}""",
            """"subagent"""",
        )
        for ((index, source) in sources.withIndex()) {
            val id = "child-$index"
            val file = rollout(id, source, text = if (index == 0) prompt else "Review this code")
            assertNull(CodexTranscriptScanner.summarize(file, "/repo", mapOf(id to "Named child")), source)
        }
        val file = rollout("thread-source", extraMeta = mapOf("thread_source" to Json.parseToJsonElement("\"subagent\"")))
        assertNull(CodexTranscriptScanner.summarize(file, "/repo", emptyMap()))
    }

    @Test
    fun ordinary_sources_and_old_rollouts_keep_json_prompts_and_manual_forks() {
        val sources = listOf(null, "null", "\"cli\"", "\"exec\"", "\"vscode\"", "\"appServer\"", "\"unknown\"", """{"future_source":{}}""")
        for ((index, source) in sources.withIndex()) {
            val file = rollout("main-$index", source, extraMeta = mapOf("forked_from_id" to Json.parseToJsonElement("\"parent\"")))
            val summary = assertNotNull(CodexTranscriptScanner.summarize(file, "/repo", emptyMap()), source)
            assertEquals(prompt, summary.firstPrompt)
            assertEquals(1, summary.messageCount)
        }
    }

    @Test
    fun runtime_and_turn_reads_cannot_put_a_child_back_in_the_cached_list() {
        val file = rollout("child", spawned)
        assertNull(CodexTranscriptScanner.summarize(file, "/repo", emptyMap()))
        assertEquals("test-model", CodexTranscriptScanner.runtimeState(file).model)
        assertEquals("turn-1", CodexTranscriptScanner.turnEvidence(file)?.turnId)
        assertNull(CodexTranscriptScanner.summarize(file, "/repo", emptyMap()))
        assertNull(CodexTranscriptScanner.summarize(file, null, emptyMap()))
    }

    @Test
    fun detailed_discovery_skips_children_without_reporting_an_incomplete_scan() {
        rollout("parent", "\"vscode\"")
        rollout("child", spawned, mtime = 1_700_000_001_000)
        val scan = CodexTranscriptScanner.scanDetailed("/repo", root = root, titles = emptyMap())
        assertEquals(listOf("parent"), scan.items.map { it.sessionId })
        assertEquals(ScanCompleteness.COMPLETE, scan.completeness)
        assertEquals(0, scan.failedCount)
    }

    @Test
    fun newer_children_do_not_replace_the_parent_in_active_summaries() {
        val parent = rollout("parent", "\"vscode\"")
        val child = rollout("child", spawned, mtime = 1_700_000_001_000)
        // The runtime/observe path can have populated the shared parse before the project refresh.
        CodexTranscriptScanner.runtimeState(child)
        val active = CodexTranscriptScanner.activeSummaries(setOf("/repo"), listOf(child, parent))
        assertEquals("parent", active.getValue("/repo").sessionId)
        assertTrue(CodexTranscriptScanner.activeSummaries(setOf("/repo"), listOf(child)).isEmpty())
    }

    @Test
    fun children_neither_discover_projects_nor_bump_their_recency() {
        val parentTime = 1_700_000_000_000L
        val parent = rollout("parent", "\"vscode\"", mtime = parentTime)
        val child = rollout("child", spawned, mtime = parentTime + 1_000)
        val other = rollout("child-elsewhere", spawned, cwd = "/child-only", mtime = parentTime + 2_000)
        val files = listOf(other, child, parent)
        repeat(2) {
            assertEquals(mapOf("/repo" to parentTime), CodexTranscriptScanner.cwdsByNewest(files))
        }
    }
}
