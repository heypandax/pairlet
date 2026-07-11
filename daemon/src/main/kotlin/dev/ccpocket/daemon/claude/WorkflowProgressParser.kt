package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.WorkflowAgentSnap
import dev.ccpocket.protocol.WorkflowAgentState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The CLI's `workflow_progress` item schema → wire snaps (issue #106). ONE parser shared by the
 * live tracker (wire `task_progress` events) and the on-disk manifest reader
 * (`<sessionDir>/workflows/<runId>.json` — its `workflowProgress` array is the SAME structure,
 * persisted once at completion), so the two paths can't drift.
 *
 * Item shapes (probed on claude 2.1.206 — internal, may drift; scripts/probe-claude-wire.py
 * `workflow` regresses them):
 *  - workflow_phase {index, title, kind?}
 *  - workflow_agent {index, label, phaseIndex?, phaseTitle?, agentId?, model?, state, startedAt?,
 *    queuedAt?, attempt?, lastToolName?, lastToolSummary?, promptPreview?, resultPreview?, error?,
 *    blocked?, cached?, tokens?, toolCalls?, durationMs?, …}
 *  - workflow_log {message} — free-form, skipped
 *
 * Agent `state` machine: start → progress → done | error. A `start` WITHOUT startedAt is still in
 * the fan-out backlog — mapped to QUEUED; anything unrecognized degrades to UNKNOWN, never throws.
 */
internal object WorkflowProgressParser {

    fun phaseOrNull(obj: JsonObject): Pair<Int, String>? {
        if (obj.str("type") != "workflow_phase") return null
        val idx = obj.int("index") ?: return null
        return idx to (obj.str("title") ?: "Phase $idx")
    }

    fun agentOrNull(obj: JsonObject): WorkflowAgentSnap? {
        if (obj.str("type") != "workflow_agent") return null
        val index = obj.int("index") ?: return null
        val startedAt = obj.long("startedAt")
        val state = when (obj.str("state")) {
            "start" -> if (startedAt != null) WorkflowAgentState.RUNNING else WorkflowAgentState.QUEUED
            "progress" -> WorkflowAgentState.RUNNING
            "done" -> WorkflowAgentState.DONE
            "error" -> WorkflowAgentState.FAILED
            else -> WorkflowAgentState.UNKNOWN
        }
        return WorkflowAgentSnap(
            index = index,
            label = obj.str("label")?.take(LABEL_MAX) ?: "agent $index",
            state = state,
            phaseIndex = obj.int("phaseIndex"),
            queuedAt = obj.long("queuedAt"),
            startedAt = startedAt,
            durationMs = obj.long("durationMs"),
            error = obj.str("error")?.take(ERROR_MAX),
            resultPreview = obj.str("resultPreview")?.take(PREVIEW_MAX),
            promptPreview = obj.str("promptPreview")?.take(PREVIEW_MAX),
            lastToolName = obj.str("lastToolName")?.take(LABEL_MAX),
            agentId = obj.str("agentId"),
            model = obj.str("model"),
            cached = obj.bool("cached") == true,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    const val LABEL_MAX = 120
    const val PREVIEW_MAX = 240   // CLI already caps its previews; defensive re-cap
    const val ERROR_MAX = 500
}
