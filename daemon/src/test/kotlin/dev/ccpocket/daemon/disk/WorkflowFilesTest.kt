package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowRunStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures mirror a REAL probe run's on-disk layout (claude 2.1.206, wf_03737500-658):
 * manifest written once at completion; journal/agent transcripts appended live.
 */
class WorkflowFilesTest {

    private fun sessionDir(): Path = Files.createTempDirectory("wf-files").resolve("sess-1").createDirectories()

    private fun writeManifest(dir: Path, runId: String = "wf_03737500-658", status: String? = "completed") {
        dir.resolve("workflows").createDirectories()
        val statusField = status?.let { "\"status\":\"$it\"," } ?: ""
        dir.resolve("workflows/$runId.json").writeText(
            """{
              "runId":"$runId","timestamp":"2026-07-11T11:30:07.079Z","taskId":"wvw3rra3y",
              "script":"export const meta = …","scriptPath":"/x/probe-mini-$runId.js",
              "result":{"fruits":["apple","banana"],"last":"cherry"},
              "agentCount":3,"logs":[],"durationMs":6095,"summary":"probe minimal workflow",
              "workflowName":"probe-mini",$statusField"startTime":1783769400984,
              "phases":[{"title":"Alpha","detail":"two parallel agents"},{"title":"Beta","detail":"one agent"}],
              "defaultModel":"claude-sonnet-5",
              "workflowProgress":[
                {"type":"workflow_phase","index":1,"title":"Alpha"},
                {"type":"workflow_phase","index":2,"title":"Beta"},
                {"type":"workflow_agent","index":1,"label":"say-apple","phaseIndex":1,"phaseTitle":"Alpha","agentId":"aA","model":"m","state":"done","startedAt":1,"queuedAt":0,"attempt":1,"promptPreview":"Reply apple","tokens":22831,"toolCalls":0,"durationMs":3266,"resultPreview":"apple"},
                {"type":"workflow_agent","index":2,"label":"say-banana","phaseIndex":1,"agentId":"aB","state":"error","error":"boom","queuedAt":0},
                {"type":"workflow_agent","index":3,"label":"say-cherry","phaseIndex":2,"agentId":"aC","state":"done","startedAt":5,"queuedAt":5,"durationMs":1847,"resultPreview":"cherry"}
              ],
              "totalTokens":68494,"totalToolCalls":0
            }""",
        )
    }

    @Test
    fun listRuns_parses_the_manifest_into_a_terminal_run() {
        val dir = sessionDir()
        writeManifest(dir)
        val run = WorkflowFiles.listRuns(dir).single()
        assertEquals("wf_03737500-658", run.runId)
        assertEquals("probe-mini", run.name)
        assertEquals(WorkflowRunStatus.COMPLETED, run.status)
        assertEquals(listOf("Alpha", "Beta"), run.phases.map { it.title })
        assertEquals(3, run.agents.size)
        assertEquals(WorkflowAgentState.DONE, run.agents[0].state)
        assertEquals(WorkflowAgentState.FAILED, run.agents[1].state)
        assertEquals("boom", run.agents[1].error)
        assertEquals(1783769400984, run.startedAt)
        assertEquals(6095, run.durationMs)
        // structured result pretty-prints
        assertTrue(run.finalResult!!.contains("\"fruits\""), run.finalResult!!)
        assertNull(run.toolUseId) // replay binds via HistoryMessage.workflowRunId
        assertNull(run.error)
    }

    @Test
    fun manifest_without_status_infers_from_error_and_unknown_status_degrades() {
        val dir = sessionDir()
        writeManifest(dir, status = null) // CLI omits status on some paths: error → failed, else completed
        assertEquals(WorkflowRunStatus.COMPLETED, WorkflowFiles.listRuns(dir).single().status)

        val dir2 = sessionDir()
        writeManifest(dir2, runId = "wf_z", status = "paused")
        assertEquals(WorkflowRunStatus.UNKNOWN, WorkflowFiles.readRun(dir2, "wf_z")!!.status)
    }

    @Test
    fun readRun_misses_are_null_and_broken_json_never_throws() {
        val dir = sessionDir()
        assertNull(WorkflowFiles.readRun(dir, "wf_missing"))
        dir.resolve("workflows").createDirectories()
        dir.resolve("workflows/wf_bad.json").writeText("{not json")
        assertNull(WorkflowFiles.readRun(dir, "wf_bad"))
        assertEquals(emptyList(), WorkflowFiles.listRuns(dir))
    }

    @Test
    fun readAgentDetail_prompt_from_transcript_result_from_journal() {
        val dir = sessionDir()
        val runDir = dir.resolve("subagents/workflows/wf_1").createDirectories()
        // journal: started + result records (key is a cache HASH — the prompt text is NOT here)
        runDir.resolve("journal.jsonl").writeText(
            """{"type":"started","key":"v2:e0bf","agentId":"aA"}
              |{"type":"result","key":"v2:e0bf","agentId":"aA","result":"apple"}
              |{"type":"result","key":"v2:ffff","agentId":"aB","result":{"structured":true}}
              |""".trimMargin(),
        )
        runDir.resolve("agent-aA.jsonl").writeText(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Reply with exactly the single word: apple."}]}}
              |{"type":"assistant","message":{"content":[{"type":"text","text":"apple"}]}}
              |""".trimMargin(),
        )
        val detail = WorkflowFiles.readAgentDetail(dir, "wf_1", "aA")
        assertEquals("Reply with exactly the single word: apple.", detail.prompt)
        assertEquals("apple", detail.result)

        // structured journal result pretty-prints; missing transcript → prompt null, never a throw
        val b = WorkflowFiles.readAgentDetail(dir, "wf_1", "aB")
        assertNull(b.prompt)
        assertTrue(b.result!!.contains("\"structured\""))
    }

    @Test
    fun readAgentDetail_falls_back_to_transcripts_last_assistant_text_when_journal_has_no_result() {
        val dir = sessionDir()
        val runDir = dir.resolve("subagents/workflows/wf_1").createDirectories()
        runDir.resolve("journal.jsonl").writeText("""{"type":"started","key":"v2:x","agentId":"aC"}""" + "\n")
        runDir.resolve("agent-aC.jsonl").writeText(
            """{"type":"user","message":{"role":"user","content":"do the thing"}}
              |{"type":"assistant","message":{"content":[{"type":"text","text":"first"}]}}
              |{"type":"assistant","message":{"content":[{"type":"text","text":"final answer"}]}}
              |""".trimMargin(),
        )
        val detail = WorkflowFiles.readAgentDetail(dir, "wf_1", "aC")
        assertEquals("do the thing", detail.prompt) // string-form content works too
        assertEquals("final answer", detail.result)
    }

    @Test
    fun missing_run_dir_yields_all_null_detail() {
        val detail = WorkflowFiles.readAgentDetail(sessionDir(), "wf_none", "aZ")
        assertNull(detail.prompt)
        assertNull(detail.result)
    }
}
