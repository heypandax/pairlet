package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.JobKind
import dev.ccpocket.protocol.JobStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundJobRegistryTest {

    private fun bgBash(cmd: String) = buildJsonObject { put("run_in_background", true); put("command", cmd) }

    @Test
    fun bash_run_in_background_starts_a_running_job() {
        val r = BackgroundJobRegistry()
        assertTrue(r.onToolUse("t1", "Bash", bgBash("sleep 100"), now = 1000))
        assertTrue(r.hasRunning())
        val job = r.snapshot().single()
        assertEquals(JobKind.BASH_BACKGROUND, job.kind)
        assertEquals(JobStatus.RUNNING, job.status)
        assertTrue(job.label.contains("sleep"))
    }

    @Test
    fun foreground_bash_is_not_tracked() {
        val r = BackgroundJobRegistry()
        assertFalse(r.onToolUse("t1", "Bash", buildJsonObject { put("command", "ls") }, now = 1))
        assertTrue(r.snapshot().isEmpty())
    }

    @Test
    fun task_subagent_runs_then_completes_on_result() {
        val r = BackgroundJobRegistry()
        val input = buildJsonObject { put("subagent_type", "Explore"); put("description", "find X") }
        assertTrue(r.onToolUse("a1", "Task", input, now = 1))
        assertEquals(JobKind.SUBAGENT, r.snapshot().single().kind)
        assertTrue(r.hasRunning())
        assertTrue(r.onToolResult("a1", "ok", isError = false, now = 2))
        assertFalse(r.hasRunning())
        assertEquals(JobStatus.DONE, r.snapshot().single().status)
    }

    @Test
    fun task_error_result_marks_failed() {
        val r = BackgroundJobRegistry()
        r.onToolUse("a1", "Task", buildJsonObject { put("description", "x") }, now = 1)
        assertTrue(r.onToolResult("a1", "boom", isError = true, now = 2))
        assertEquals(JobStatus.FAILED, r.snapshot().single().status)
    }

    @Test
    fun background_bash_completes_via_system_task_events() {
        val r = BackgroundJobRegistry()
        r.onToolUse("toolu_1", "Bash", bgBash("server"), now = 1)
        // the bg-bash tool_result only confirms the shell STARTED — still running
        assertFalse(r.onToolResult("toolu_1", "Command running in background with ID: bztg", isError = false, now = 2))
        assertTrue(r.hasRunning())
        // system/task_started links the task_id to the existing job
        assertFalse(r.onTaskStarted(taskId = "bztg", toolUseId = "toolu_1", description = "Sleep", taskType = "local_bash", now = 3))
        assertTrue(r.hasRunning())
        // system/task_notification(completed) is the authoritative completion
        assertTrue(r.onTaskUpdated(taskId = "bztg", status = "completed", now = 4))
        assertFalse(r.hasRunning())
        assertEquals(JobStatus.DONE, r.snapshot().single().status)
    }

    @Test
    fun kill_shell_marks_killed_via_task_id() {
        val r = BackgroundJobRegistry()
        r.onToolUse("toolu_1", "Bash", bgBash("server"), now = 1)
        r.onTaskStarted(taskId = "bztg", toolUseId = "toolu_1", description = "x", taskType = "local_bash", now = 2)
        assertTrue(r.onToolUse(null, "KillShell", buildJsonObject { put("shell_id", "bztg") }, now = 3))
        assertEquals(JobStatus.KILLED, r.snapshot().single().status)
        assertFalse(r.hasRunning())
    }

    @Test
    fun task_started_without_prior_tool_use_creates_job() {
        val r = BackgroundJobRegistry()
        assertTrue(r.onTaskStarted(taskId = "T", toolUseId = "tool_x", description = "orphan", taskType = "local_bash", now = 1))
        assertTrue(r.hasRunning())
        assertEquals(JobKind.BASH_BACKGROUND, r.snapshot().single().kind)
    }

    @Test
    fun foreground_bash_task_events_do_not_mint_a_phantom_job() {
        // 2.1.206+ runs FOREGROUND Bash through the same task machinery: task_started + task_notification
        // (task_type local_bash, the fg tool_use's id) fire at command completion. Registering them made a
        // phantom "background job" flash through the phone's panel for EVERY foreground command (#105 residual).
        val r = BackgroundJobRegistry()
        assertFalse(r.onToolUse("toolu_fg", "Bash", buildJsonObject { put("command", "echo hi"); put("run_in_background", false) }, now = 1))
        assertFalse(r.onTaskStarted(taskId = "bfg1", toolUseId = "toolu_fg", description = "echo hi", taskType = "local_bash", now = 2))
        assertFalse(r.onTaskUpdated(taskId = "bfg1", status = "completed", now = 3))
        assertTrue(r.snapshot().isEmpty())
        assertFalse(r.hasRunning())
    }

    @Test
    fun foreground_bash_with_omitted_flag_is_suppressed_too() {
        // the model usually OMITS run_in_background entirely on foreground runs
        val r = BackgroundJobRegistry()
        assertFalse(r.onToolUse("toolu_fg", "Bash", buildJsonObject { put("command", "ls") }, now = 1))
        assertFalse(r.onTaskStarted(taskId = "t1", toolUseId = "toolu_fg", description = "ls", taskType = "local_bash", now = 2))
        assertTrue(r.snapshot().isEmpty())
    }

    @Test
    fun foreground_memory_is_bounded_and_keeps_recent_ids() {
        val r = BackgroundJobRegistry()
        for (i in 0 until 100) r.onToolUse("fg$i", "Bash", buildJsonObject { put("command", "x") }, now = i.toLong())
        // evicted oldest id falls back to the old create-on-task_started behavior (harmless, bounded)…
        assertTrue(r.onTaskStarted(taskId = "T", toolUseId = "fg0", description = "evicted", taskType = "local_bash", now = 200))
        // …while a recent foreground id is still recognized and suppressed
        assertFalse(r.onTaskStarted(taskId = "T2", toolUseId = "fg99", description = "fresh", taskType = "local_bash", now = 201))
        assertEquals(1, r.snapshot().size)
    }

    @Test
    fun background_bash_error_result_settles_as_failed() {
        // a bg-bash whose LAUNCH errors gets no later system task_* event, so the error result must settle it
        val r = BackgroundJobRegistry()
        r.onToolUse("toolu_1", "Bash", bgBash("nope"), now = 1)
        assertTrue(r.onToolResult("toolu_1", "command timed out", isError = true, now = 2))
        assertFalse(r.hasRunning())
        assertEquals(JobStatus.FAILED, r.snapshot().single().status)
    }

    @Test
    fun reap_stale_settles_a_silent_background_bash() {
        val r = BackgroundJobRegistry()
        r.onToolUse("toolu_1", "Bash", bgBash("server"), now = 1)
        r.onToolResult("toolu_1", "started", isError = false, now = 2) // still RUNNING, lastUpdate = 2
        assertFalse(r.reapStale(now = 100, staleMs = 1000)) // not stale yet
        assertTrue(r.hasRunning())
        assertTrue(r.reapStale(now = 2_000, staleMs = 1000)) // silent past the window -> reaped
        assertFalse(r.hasRunning())
        assertEquals(JobStatus.KILLED, r.snapshot().single().status)
        assertFalse(r.reapStale(now = 9_999, staleMs = 1000)) // idempotent — never resurrects/re-reaps
    }

    @Test
    fun reap_stale_leaves_subagents_alone() {
        val r = BackgroundJobRegistry()
        r.onToolUse("a1", "Task", buildJsonObject { put("description", "x") }, now = 1)
        assertFalse(r.reapStale(now = 10_000_000, staleMs = 1000)) // sub-agents complete from the turn, never reaped
        assertTrue(r.hasRunning())
    }

    @Test
    fun agent_tool_name_is_tracked_like_task() {
        // current CLIs renamed the sub-agent tool "Task" -> "Agent" (probed 07-08, issue #77)
        val r = BackgroundJobRegistry()
        val input = buildJsonObject { put("subagent_type", "general-purpose"); put("description", "add two numbers") }
        assertTrue(r.onToolUse("a1", "Agent", input, now = 1))
        val job = r.snapshot().single()
        assertEquals(JobKind.SUBAGENT, job.kind)
        assertTrue(job.label.startsWith("general-purpose"))
        assertTrue(r.onToolResult("a1", "5", isError = false, now = 2))
        assertEquals(JobStatus.DONE, r.snapshot().single().status)
    }

    @Test
    fun backgrounded_subagent_completes_via_task_events_not_its_result() {
        // Agent with run_in_background: the tool_result is only the launch ack — completion is task_notification
        val r = BackgroundJobRegistry()
        val input = buildJsonObject { put("subagent_type", "worker"); put("description", "long job"); put("run_in_background", true) }
        assertTrue(r.onToolUse("a1", "Agent", input, now = 1))
        assertFalse(r.onToolResult("a1", "Async agent launched", isError = false, now = 2))
        assertTrue(r.hasRunning())
        r.onTaskStarted(taskId = "T9", toolUseId = "a1", description = "long job", taskType = "local_agent", now = 3)
        assertTrue(r.onTaskUpdated(taskId = "T9", status = "completed", now = 4))
        assertFalse(r.hasRunning())
        assertEquals(JobStatus.DONE, r.snapshot().single().status)
    }

    @Test
    fun reap_stale_settles_a_silent_backgrounded_subagent() {
        val r = BackgroundJobRegistry()
        val input = buildJsonObject { put("description", "x"); put("run_in_background", true) }
        r.onToolUse("a1", "Agent", input, now = 1)
        assertTrue(r.reapStale(now = 10_000, staleMs = 1000))
        assertEquals(JobStatus.KILLED, r.snapshot().single().status)
    }

    @Test
    fun mark_killed_settles_a_running_job_by_id() {
        // issue #80: the phone's panel "stop" force-settles a RUNNING job by its snapshot id
        val r = BackgroundJobRegistry()
        r.onToolUse("toolu_1", "Bash", bgBash("gcloud auth login"), now = 1)
        assertTrue(r.hasRunning())
        assertTrue(r.markKilled("toolu_1", now = 5))
        assertEquals(JobStatus.KILLED, r.snapshot().single().status)
        assertFalse(r.hasRunning())
        // idempotent: an already-settled job (and an unknown id) never re-flips
        assertFalse(r.markKilled("toolu_1", now = 6))
        assertFalse(r.markKilled("nope", now = 7))
    }

    @Test
    fun clear_empties_and_reports_change() {
        val r = BackgroundJobRegistry()
        r.onToolUse("t1", "Bash", bgBash("x"), now = 1)
        assertTrue(r.clear())
        assertTrue(r.snapshot().isEmpty())
        assertFalse(r.clear())
    }
}
