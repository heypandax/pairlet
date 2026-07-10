package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.BackgroundJob
import dev.ccpocket.protocol.JobKind
import dev.ccpocket.protocol.JobStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Tracks a conversation's background work — backgrounded shells (`Bash` with `run_in_background`),
 * sub-agents (`Task`), and monitors (`Monitor`) — by watching claude's stream.
 *
 * NOT thread-safe: the owning [Conversation] drives it from its single stdout pump (no locks needed).
 *
 * Lifecycle signals (verified against claude 2.1.x stream-json):
 *  - A job is born on its `tool_use` (Bash+run_in_background / Task / Monitor), keyed by the tool_use id.
 *  - Task / Monitor are synchronous-from-the-turn: their `tool_result` IS the completion (ok -> DONE, error -> FAILED).
 *  - A backgrounded Bash's `tool_result` only marks that it STARTED; it finishes later via dedicated
 *    `system` events: `task_started {task_id, tool_use_id}` then `task_updated`/`task_notification {status}`.
 *    Those are routed here through [onTaskStarted] / [onTaskUpdated] and keyed back via [taskToKey].
 */
class BackgroundJobRegistry {
    private data class Job(
        val key: String,
        var kind: JobKind,
        var label: String,
        var status: JobStatus,
        val startedAt: Long,
        var lastUpdate: Long,
        // launched with run_in_background (bg Bash always; a sub-agent can be too): its tool_result is
        // only the launch ack — completion arrives via the system task_* events instead
        val background: Boolean = false,
    )

    private val jobs = LinkedHashMap<String, Job>()    // keyed by tool_use id (insertion-ordered)
    private val taskToKey = HashMap<String, String>()  // system task_id -> job key

    /** A tool_use crossed the stream. Returns true if the visible job set/status changed (caller re-emits). */
    fun onToolUse(id: String?, name: String, input: JsonObject?, now: Long): Boolean {
        when (name) {
            "Bash" -> if (input.flag("run_in_background") && id != null) {
                putNew(id, JobKind.BASH_BACKGROUND, input.str("command")?.summary() ?: "background shell", now, background = true); return true
            }
            // "Task" through CLI 2.1.x; renamed "Agent" on current CLIs (probed 07-08). A sub-agent can
            // itself be backgrounded (run_in_background) — then its tool_result is only the launch ack.
            "Task", "Agent" -> if (id != null) {
                val label = listOfNotNull(input.str("subagent_type"), input.str("description")).joinToString(": ").ifBlank { "sub-agent" }
                putNew(id, JobKind.SUBAGENT, label.summary(), now, background = input.flag("run_in_background")); return true
            }
            "Monitor" -> if (id != null) {
                putNew(id, JobKind.MONITOR, (input.str("description") ?: input.str("command") ?: "monitor").summary(), now); return true
            }
            "KillShell", "KillBash" -> {
                val sid = input.str("shell_id") ?: input.str("bash_id") ?: input.str("task_id") ?: return false
                return markKilled(taskToKey[sid] ?: sid, now)
            }
        }
        return false
    }

    /** A tool_result crossed the stream. Completes a synchronous Task/Monitor; a backgrounded job's
     *  result only marks "started" (bg Bash always, a run_in_background sub-agent too). */
    fun onToolResult(toolUseId: String?, content: String?, isError: Boolean, now: Long): Boolean {
        val job = jobs[toolUseId] ?: return false
        job.lastUpdate = now
        if (job.background) {
            // a backgrounded job reports "started" here and finishes later via a system task_* event — so a
            // success result is NOT terminal. But an ERROR result means the launch never backgrounded: that
            // later task_* event will never come, so settle it now instead of leaking a forever-RUNNING job.
            if (isError && job.status == JobStatus.RUNNING) { job.status = JobStatus.FAILED; return true }
            return false
        }
        if (job.status != JobStatus.RUNNING) return false
        job.status = if (isError) JobStatus.FAILED else JobStatus.DONE
        return true
    }

    /** `system/task_started` — links the background task_id to its job (created earlier by the tool_use). */
    fun onTaskStarted(taskId: String, toolUseId: String?, description: String?, taskType: String?, now: Long): Boolean {
        val key = toolUseId ?: taskId
        taskToKey[taskId] = key
        val existing = jobs[key]
        if (existing != null) { existing.lastUpdate = now; return false } // the tool_use already created it
        // a task_started-born job by definition completes via later task_* events, never a tool_result
        putNew(key, kindOf(taskType), description?.summary() ?: "background task", now, background = true)
        return true
    }

    /** `system/task_updated` / `task_notification` — the authoritative completion of a backgrounded shell. */
    fun onTaskUpdated(taskId: String, status: String?, now: Long): Boolean {
        val job = jobs[taskToKey[taskId] ?: return false] ?: return false
        job.lastUpdate = now
        val next = when (status?.lowercase()) {
            "completed", "complete", "done", "success" -> JobStatus.DONE
            "failed", "error" -> JobStatus.FAILED
            "killed", "cancelled", "canceled", "interrupted" -> JobStatus.KILLED
            else -> JobStatus.RUNNING
        }
        if (job.status == next || next == JobStatus.RUNNING) return false // never resurrect a finished job
        job.status = next
        return true
    }

    /**
     * Backstop for the case onToolResult can't see: a backgrounded job whose completion `task_*` event
     * never arrives (turn ended, the phone was away, the event was dropped). A backgrounded job (bg Bash /
     * bg sub-agent) that has been RUNNING with NO update for [staleMs] is almost certainly long dead —
     * settle it as KILLED so the count clears and the session becomes idle-reapable. Foreground sub-agents /
     * monitors complete synchronously from the turn and are never reaped here. Returns true if anything
     * changed (caller re-emits the snapshot).
     */
    fun reapStale(now: Long, staleMs: Long): Boolean {
        var changed = false
        for (job in jobs.values) {
            if (job.background && job.status == JobStatus.RUNNING && now - job.lastUpdate > staleMs) {
                job.status = JobStatus.KILLED
                job.lastUpdate = now
                changed = true
            }
        }
        return changed
    }

    /**
     * Force one RUNNING job to KILLED — the phone's panel "stop" (issue #80), keyed by its snapshot id
     * ([BackgroundJob.id] == the originating tool_use key). Same terminal state the model's own KillShell
     * lands here; returns true when it actually flipped (RUNNING → KILLED) so the caller re-emits. No-op
     * for an unknown id or an already-settled job.
     */
    fun markKilled(jobId: String, now: Long): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status != JobStatus.RUNNING) return false
        job.status = JobStatus.KILLED
        job.lastUpdate = now
        return true
    }

    fun hasRunning(): Boolean = jobs.values.any { it.status == JobStatus.RUNNING }

    fun snapshot(): List<BackgroundJob> =
        jobs.values.map { BackgroundJob(it.key, it.kind, it.label, it.status, it.startedAt, it.lastUpdate) }

    /** Drop everything (a relaunch kills the process tree, taking its background shells with it). */
    fun clear(): Boolean {
        val had = jobs.isNotEmpty()
        jobs.clear()
        taskToKey.clear()
        return had
    }

    private fun putNew(key: String, kind: JobKind, label: String, now: Long, background: Boolean = false) {
        jobs[key] = Job(key, kind, label, JobStatus.RUNNING, now, now, background = background)
        // keep memory bounded on long sessions: evict the oldest terminal job once over the cap
        while (jobs.size > MAX_JOBS) {
            val victim = jobs.values.firstOrNull { it.status != JobStatus.RUNNING } ?: break
            jobs.remove(victim.key)
            taskToKey.values.remove(victim.key)
        }
    }

    private fun kindOf(taskType: String?): JobKind {
        val t = taskType?.lowercase() ?: return JobKind.BASH_BACKGROUND
        return when {
            "agent" in t || "task" in t -> JobKind.SUBAGENT
            "monitor" in t -> JobKind.MONITOR
            else -> JobKind.BASH_BACKGROUND
        }
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.flag(key: String): Boolean {
        val p = this?.get(key) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.contentOrNull == "true")
    }

    private fun String.summary(maxLen: Int = 80): String =
        trim().replace(WHITESPACE, " ").let { if (it.length > maxLen) it.take(maxLen - 1) + "…" else it }

    private companion object {
        const val MAX_JOBS = 12
        val WHITESPACE = Regex("\\s+")
    }
}
