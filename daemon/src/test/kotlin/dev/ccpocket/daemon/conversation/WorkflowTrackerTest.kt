package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowRunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Item payloads mirror real claude 2.1.206 workflow_progress frames (probe-claude-wire.py workflow). */
class WorkflowTrackerTest {

    private fun items(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private val seed = """[
        {"type":"workflow_phase","index":1,"title":"Alpha"},
        {"type":"workflow_phase","index":2,"title":"Beta"},
        {"type":"workflow_agent","index":1,"label":"say-apple","phaseIndex":1,"phaseTitle":"Alpha","agentId":"aA","model":"m","state":"start","startedAt":100,"queuedAt":99,"promptPreview":"Reply apple"},
        {"type":"workflow_agent","index":2,"label":"say-banana","phaseIndex":1,"agentId":"aB","state":"start","queuedAt":99}
    ]"""

    @Test
    fun full_lifecycle_launch_progress_settle() {
        val t = WorkflowTracker()
        // launch ack first (the usual order: tool_result precedes task_started on the wire)
        assertTrue(t.onLaunched("toolu_1", "wf_1", "task1", "probe-mini", now = 50))
        assertTrue(t.onTaskStarted("task1", "toolu_1", "probe-mini", now = 51))
        assertTrue(t.onProgress("task1", "toolu_1", items(seed), now = 60))

        val run = assertNotNull(t.snapshotFor("task1"))
        assertEquals("wf_1", run.runId)
        assertEquals("probe-mini", run.name)
        assertEquals("toolu_1", run.toolUseId)
        assertEquals(WorkflowRunStatus.RUNNING, run.status)
        assertEquals(listOf("Alpha", "Beta"), run.phases.map { it.title })
        // start WITH startedAt → RUNNING; start WITHOUT → still queued in the fan-out backlog
        assertEquals(WorkflowAgentState.RUNNING, run.agents[0].state)
        assertEquals(WorkflowAgentState.QUEUED, run.agents[1].state)

        // agent 1 finishes; agent 3 appears mid-run (parallel() fan-out grows the total)
        assertTrue(
            t.onProgress(
                "task1", "toolu_1",
                items(
                    """[
                    {"type":"workflow_agent","index":1,"label":"say-apple","phaseIndex":1,"agentId":"aA","state":"done","startedAt":100,"queuedAt":99,"durationMs":3266,"resultPreview":"apple"},
                    {"type":"workflow_agent","index":3,"label":"say-cherry","phaseIndex":2,"agentId":"aC","state":"progress","startedAt":200,"queuedAt":150,"lastToolName":"Bash"}
                ]""",
                ),
                now = 70,
            ),
        )
        val mid = assertNotNull(t.snapshotFor("task1"))
        assertEquals(3, mid.agents.size)
        assertEquals(WorkflowAgentState.DONE, mid.agents.first { it.index == 1 }.state)
        assertEquals("apple", mid.agents.first { it.index == 1 }.resultPreview)
        assertEquals(WorkflowAgentState.RUNNING, mid.agents.first { it.index == 3 }.state)

        assertTrue(t.onTaskSettled("task1", "completed", now = 5000))
        val done = assertNotNull(t.snapshotFor("task1"))
        assertEquals(WorkflowRunStatus.COMPLETED, done.status)
        assertEquals(4950, done.durationMs) // now - startedAt(50)
        assertFalse(t.hasRunning())
    }

    @Test
    fun progress_before_any_launch_creates_a_stub_run_then_ack_names_it() {
        val t = WorkflowTracker()
        assertTrue(t.onProgress("taskX", "toolu_9", items(seed), now = 10))
        val stub = assertNotNull(t.snapshotFor("taskX"))
        assertEquals("taskX", stub.runId) // pre-ack fallback keys the client map stably
        assertEquals(WorkflowRunStatus.RUNNING, stub.status)

        assertTrue(t.onLaunched("toolu_9", "wf_9", "taskX", "late-name", now = 11))
        val named = assertNotNull(t.snapshotFor("taskX"))
        assertEquals("wf_9", named.runId)
        assertEquals("late-name", named.name)
    }

    @Test
    fun settle_flavors_map_and_unknown_patch_keeps_running() {
        val t = WorkflowTracker()
        t.onLaunched(null, "wf_a", "ta", "a", 0)
        assertFalse(t.onTaskSettled("ta", "paused", 1)) // no invented states
        assertTrue(t.onTaskSettled("ta", "failed", 2))
        assertEquals(WorkflowRunStatus.FAILED, t.snapshotFor("ta")!!.status)

        t.onLaunched(null, "wf_b", "tb", "b", 0)
        assertTrue(t.onTaskSettled("tb", "killed", 2))
        assertEquals(WorkflowRunStatus.KILLED, t.snapshotFor("tb")!!.status)

        // settle for a task we never tracked = not a workflow
        assertFalse(t.onTaskSettled("not-ours", "completed", 3))
    }

    @Test
    fun non_completed_settle_fails_unfinished_agents_so_nothing_pulses_forever() {
        val t = WorkflowTracker()
        t.onLaunched(null, "wf_1", "t1", "n", 0)
        t.onProgress("t1", null, items(seed), 5)
        t.onTaskSettled("t1", "killed", 10)
        val run = t.snapshotFor("t1")!!
        assertTrue(run.agents.all { it.state == WorkflowAgentState.FAILED })
        assertEquals("run killed", run.agents[0].error)
    }

    @Test
    fun killRunning_settles_only_running_runs() {
        val t = WorkflowTracker()
        t.onLaunched(null, "wf_1", "t1", "a", 0)
        t.onLaunched(null, "wf_2", "t2", "b", 0)
        t.onTaskSettled("t1", "completed", 5)
        val flipped = t.killRunning(now = 9)
        assertEquals(listOf("t2"), flipped)
        assertEquals(WorkflowRunStatus.COMPLETED, t.snapshotFor("t1")!!.status)
        assertEquals(WorkflowRunStatus.KILLED, t.snapshotFor("t2")!!.status)
    }

    @Test
    fun unknown_agent_state_degrades_to_unknown_and_bad_items_are_skipped() {
        val t = WorkflowTracker()
        t.onLaunched(null, "wf_1", "t1", "n", 0)
        t.onProgress(
            "t1", null,
            items(
                """[
                {"type":"workflow_agent","index":1,"label":"x","state":"someday-new"},
                {"type":"workflow_agent","label":"no-index"},
                {"type":"workflow_log","message":"noise"},
                {"type":"future_thing","index":9}
            ]""",
            ),
            5,
        )
        val run = t.snapshotFor("t1")!!
        assertEquals(1, run.agents.size)
        assertEquals(WorkflowAgentState.UNKNOWN, run.agents[0].state)
    }

    @Test
    fun manifest_patch_fills_runId_and_duration() {
        val t = WorkflowTracker()
        t.onTaskStarted("t1", null, "n", 0) // started WITHOUT a launch ack (no runId yet)
        assertEquals("t1", t.snapshotFor("t1")!!.runId)
        t.onTaskSettled("t1", "completed", 100)
        assertTrue(t.onManifest("t1", "wf_real", finalResult = "{\"ok\":true}", durationMs = 88, error = null))
        val run = t.snapshotWithFinal("t1", "{\"ok\":true}")!!
        assertEquals("wf_real", run.runId)
        assertEquals(88, run.durationMs)
        assertEquals("{\"ok\":true}", run.finalResult)
        // the plain snapshot never carries the final return (it is fetched/pushed explicitly)
        assertNull(t.snapshotFor("t1")!!.finalResult)
    }
}
