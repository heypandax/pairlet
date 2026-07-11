package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.claude.WorkflowProgressParser
import dev.ccpocket.protocol.WorkflowAgentSnap
import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowPhaseInfo
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Tracks a conversation's Workflow runs (issue #106) from the CLI's live stream — the
 * orchestration-level sibling of [BackgroundJobRegistry]'s per-job view.
 *
 * NOT thread-safe: the owning [Conversation] drives it from its single stdout pump.
 *
 * Wire lifecycle (probed on claude 2.1.206, see scripts/probe-claude-wire.py `workflow`):
 *  - `system/task_started {task_type:"local_workflow", workflow_name}` and/or the tool_result's
 *    root `tool_use_result {runId, taskId, workflowName}` open the run — either may arrive first.
 *  - `system/task_progress {workflow_progress:[…]}` re-sends the CUMULATIVE item array on every
 *    agent/phase transition ([onProgress] replaces state wholesale; items are keyed by index).
 *    The CLI seeds every `meta.phases` title up front, so the phase bar knows its segment count
 *    from the first snapshot; `phase()` calls may still add more mid-run.
 *  - `system/task_updated {patch.status}` / `task_notification {status}` settle the run.
 *
 * The CLI's per-agent `state` machine is start → progress → done|error; a `start` that never got
 * a `startedAt` is still queued (fan-out backlog) — mapped here to the protocol's QUEUED/RUNNING/
 * DONE/FAILED so clients never re-learn CLI internals.
 */
class WorkflowTracker {
    private class Run(
        var runId: String?,           // known immediately on replay; live, arrives with the launch ack
        var taskId: String?,
        var toolUseId: String?,
        var name: String,
        val startedAt: Long,
        var status: WorkflowRunStatus = WorkflowRunStatus.RUNNING,
        var durationMs: Long? = null,
        var error: String? = null,
        val phases: LinkedHashMap<Int, String> = LinkedHashMap(),
        val agents: LinkedHashMap<Int, WorkflowAgentSnap> = LinkedHashMap(),
    )

    private val runs = ArrayList<Run>()

    private fun byTask(taskId: String?): Run? = taskId?.let { t -> runs.lastOrNull { it.taskId == t } }

    /** The Workflow tool_result's launch ack — the only live carrier of the run id. Returns true when
     *  anything visible changed (caller re-emits). Creates the run when the ack outruns task_started. */
    fun onLaunched(toolUseId: String?, runId: String, taskId: String?, name: String?, now: Long): Boolean {
        val existing = byTask(taskId) ?: runs.lastOrNull { it.runId == runId }
        if (existing != null) {
            var changed = false
            if (existing.runId == null) { existing.runId = runId; changed = true }
            if (existing.toolUseId == null && toolUseId != null) { existing.toolUseId = toolUseId; changed = true }
            if (name != null && (existing.name.isBlank() || existing.name == PLACEHOLDER_NAME)) { existing.name = name; changed = true }
            return changed
        }
        runs += Run(runId = runId, taskId = taskId, toolUseId = toolUseId, name = name ?: PLACEHOLDER_NAME, startedAt = now)
        trim()
        return true
    }

    /** `system/task_started` with task_type=local_workflow. May arrive before OR after [onLaunched]. */
    fun onTaskStarted(taskId: String, toolUseId: String?, workflowName: String?, now: Long): Boolean {
        val existing = byTask(taskId)
            ?: toolUseId?.let { t -> runs.lastOrNull { it.toolUseId == t } }?.also { it.taskId = taskId }
        if (existing != null) {
            if (workflowName != null && (existing.name.isBlank() || existing.name == PLACEHOLDER_NAME)) existing.name = workflowName
            if (existing.toolUseId == null) existing.toolUseId = toolUseId
            return true
        }
        runs += Run(runId = null, taskId = taskId, toolUseId = toolUseId, name = workflowName ?: PLACEHOLDER_NAME, startedAt = now)
        trim()
        return true
    }

    /** A cumulative workflow_progress snapshot — replaces phase/agent state wholesale (items are the
     *  CLI's own merged array, keyed by index). Unknown item types are skipped, never fatal. */
    fun onProgress(taskId: String, toolUseId: String?, items: JsonArray, now: Long): Boolean {
        val run = byTask(taskId) ?: Run(runId = null, taskId = taskId, toolUseId = toolUseId, name = PLACEHOLDER_NAME, startedAt = now)
            .also { runs += it; trim() }
        if (run.toolUseId == null) run.toolUseId = toolUseId
        for (el in items) {
            val obj = el as? JsonObject ?: continue
            WorkflowProgressParser.phaseOrNull(obj)?.let { (idx, title) -> run.phases[idx] = title }
            WorkflowProgressParser.agentOrNull(obj)?.let { snap -> run.agents[snap.index] = snap }
            // workflow_log: free-form orchestration logs — not rendered, skipped
        }
        return true
    }

    /** `task_updated`/`task_notification` for a tracked run — settles it. Unknown task ids are not
     *  workflows (plain bg shells etc.) and return false. */
    fun onTaskSettled(taskId: String, status: String?, now: Long): Boolean {
        val run = byTask(taskId) ?: return false
        if (run.status != WorkflowRunStatus.RUNNING) return false
        val next = when (status?.lowercase()) {
            "completed", "complete", "done", "success" -> WorkflowRunStatus.COMPLETED
            "failed", "error" -> WorkflowRunStatus.FAILED
            "killed", "cancelled", "canceled", "interrupted" -> WorkflowRunStatus.KILLED
            else -> return false // an in-flight patch (e.g. paused) — keep RUNNING rather than invent states
        }
        run.status = next
        run.durationMs = now - run.startedAt
        // agents the CLI never settled (process killed mid-fanout) must not pulse forever
        if (next != WorkflowRunStatus.COMPLETED) {
            for ((idx, a) in run.agents) {
                if (a.state == WorkflowAgentState.RUNNING || a.state == WorkflowAgentState.QUEUED) {
                    run.agents[idx] = a.copy(state = WorkflowAgentState.FAILED, error = a.error ?: "run ${next.name.lowercase()}")
                }
            }
        }
        return true
    }

    /** Patch terminal facts read from the on-disk manifest (final return value, exact duration). */
    fun onManifest(taskId: String, runId: String?, finalResult: String?, durationMs: Long?, error: String?): Boolean {
        val run = byTask(taskId) ?: return false
        var changed = false
        if (runId != null && run.runId == null) { run.runId = runId; changed = true }
        if (durationMs != null && durationMs > 0) { run.durationMs = durationMs; changed = true }
        if (error != null && run.error == null) { run.error = error; changed = true }
        return changed || finalResult != null
    }

    fun snapshotFor(taskId: String): WorkflowRun? = byTask(taskId)?.toWire(null)

    /** Snapshot for re-emit on reattach; [finalResults] lets the caller splice manifest-read returns in. */
    fun snapshots(): List<WorkflowRun> = runs.map { it.toWire(null) }

    fun snapshotWithFinal(taskId: String, finalResult: String?): WorkflowRun? = byTask(taskId)?.toWire(finalResult)

    fun hasRunning(): Boolean = runs.any { it.status == WorkflowRunStatus.RUNNING }

    /** The agent process died/was stopped — every RUNNING run died with it (workflows run inside the
     *  CLI process). Settle them as KILLED so no card pulses forever; returns the task ids that
     *  flipped (caller emits each). */
    fun killRunning(now: Long): List<String> {
        val flipped = ArrayList<String>()
        for (run in runs) {
            if (run.status == WorkflowRunStatus.RUNNING) {
                run.taskId?.let { if (onTaskSettled(it, "killed", now)) flipped += it }
            }
        }
        return flipped
    }

    fun clear(): Boolean {
        val had = runs.isNotEmpty()
        runs.clear()
        return had
    }

    private fun Run.toWire(finalResult: String?): WorkflowRun = WorkflowRun(
        runId = runId ?: taskId.orEmpty(),  // pre-ack fallback: taskId still keys the client's map stably
        name = name,
        status = status,
        toolUseId = toolUseId,
        phases = phases.entries.sortedBy { it.key }.map { WorkflowPhaseInfo(it.key, it.value) },
        agents = agents.values.sortedBy { it.index },
        startedAt = startedAt,
        durationMs = durationMs,
        finalResult = finalResult,
        error = error,
    )

    private fun trim() {
        // long sessions can launch many workflows; keep the last few terminal ones + anything running
        while (runs.size > MAX_RUNS) {
            val victim = runs.firstOrNull { it.status != WorkflowRunStatus.RUNNING } ?: break
            runs.remove(victim)
        }
    }

    private companion object {
        const val MAX_RUNS = 8
        const val PLACEHOLDER_NAME = "workflow"  // until task_started/the launch ack names the run
    }
}
