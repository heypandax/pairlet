package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.isWorkflowTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

/** Reads a session `.jsonl` into a flat list of [HistoryMessage]s for replaying a resumed chat. */
object TranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Bounded so one ConvoHistory frame stays well under the relay's 256 KiB cap. */
    fun read(file: Path, maxMessages: Int = 100, maxTextLen: Int = 2000): List<HistoryMessage> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<HistoryMessage>()
        val taskIdx = HashMap<String, Int>() // sub-agent tool_use id -> its card's index in `out` (issue #77)
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    // a sub-agent's inner records share the file with isSidechain:true — they fold into
                    // the Task card (live: parent-tagged tool events), never the main transcript (issue #77)
                    if ((obj["isSidechain"] as? JsonPrimitive)?.booleanOrNull == true) continue
                    when (obj.str("type")) {
                        // drop harness plumbing (standalone task-notifications, the resume nudge) so the
                        // phone replays the real conversation, not background-shell chatter
                        "user" -> {
                            attachSubagentResults(obj, out, taskIdx)
                            if (isRealUserTurn(obj)) userText(obj)
                                .takeIf { it.isNotBlank() && !TranscriptNoise.isNoiseUserText(it) }
                                ?.let { out += HistoryMessage(ChatRole.USER, it.take(maxTextLen)) }
                        }
                        "assistant" -> assistantBlocks(obj, maxTextLen).forEach { (msg, taskId) ->
                            taskId?.let { taskIdx[it] = out.size }
                            out += msg
                        }
                    }
                }
            }
        }
        return if (out.size > maxMessages) out.takeLast(maxMessages) else out
    }

    /** One history row + (for a sub-agent tool_use) its tool_use id, so the reader can key the card. */
    private fun assistantBlocks(obj: JsonObject, maxTextLen: Int): List<Pair<HistoryMessage, String?>> {
        val message = obj["message"] as? JsonObject
        val content = message?.get("content") as? JsonArray ?: return emptyList()
        // `<synthetic>` = the CLI's API-failure placeholder, not a real reply — flag it so the phone
        // replays it as an error row instead of a normal answer (issue #65; live turns get the same
        // treatment via StreamParser). Old clients ignore the flag and render the text as before.
        val synthetic = message.str("model") == "<synthetic>"
        val items = ArrayList<Pair<HistoryMessage, String?>>()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotBlank() }
                    ?.let { items += HistoryMessage(ChatRole.ASSISTANT, it.take(maxTextLen), error = synthetic) to null }
                "tool_use" -> {
                    val name = block.str("name") ?: "tool"
                    val input = block["input"] as? JsonObject
                    if (isSubagentTool(name)) {
                        // sub-agent card: same "<type>: <description>" label as the live ToolEvent (issue #77);
                        // its ok/output are patched in when the matching tool_result scrolls past
                        val label = listOfNotNull(input.str("subagent_type"), input.str("description"))
                            .joinToString(": ").ifBlank { "sub-agent" }
                        items += HistoryMessage(ChatRole.TOOL, label.take(maxTextLen), tool = name) to block.str("id")
                    } else if (isWorkflowTool(name)) {
                        // Workflow run card (issue #106): keyed like a sub-agent so the launch ack's
                        // tool_result can patch in the run id (the separately-replayed WorkflowUpdate
                        // then binds the full progress tree to this row)
                        val label = input.str("description")?.ifBlank { null } ?: "workflow"
                        items += HistoryMessage(ChatRole.TOOL, label.take(maxTextLen), tool = name) to block.str("id")
                    } else {
                        items += HistoryMessage(
                            ChatRole.TOOL,
                            text = input?.toString()?.take(1000) ?: "", // full-ish input; the app shows it on tap-to-expand
                            tool = name,
                        ) to null
                    }
                }
            }
        }
        return items
    }

    /** Patch a sub-agent/workflow card with its outcome when the main-chain tool_result shows up.
     *  A Workflow launch's record also carries a root-level `toolUseResult {taskType:"local_workflow",
     *  runId}` — that run id binds the card to its separately-replayed [WorkflowFiles] run (#106). */
    private fun attachSubagentResults(obj: JsonObject, out: ArrayList<HistoryMessage>, taskIdx: HashMap<String, Int>) {
        if (taskIdx.isEmpty()) return
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        val workflowRunId = (obj["toolUseResult"] as? JsonObject)
            ?.takeIf { it.str("taskType") == "local_workflow" }?.str("runId")
        for (el in content) {
            val block = el as? JsonObject ?: continue
            if (block.str("type") != "tool_result") continue
            val idx = block.str("tool_use_id")?.let(taskIdx::remove) ?: continue
            val prev = out.getOrNull(idx) ?: continue
            out[idx] = prev.copy(
                ok = (block["is_error"] as? JsonPrimitive)?.booleanOrNull != true,
                output = subagentReport(toolResultText(block["content"])),
                workflowRunId = workflowRunId ?: prev.workflowRunId,
            )
        }
    }

    /** tool_result `content` is either a raw string or an array of {type:text,text:…} blocks. */
    private fun toolResultText(el: Any?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n").ifBlank { null }
        else -> null
    }

    /** The report minus the CLI's trailing "agentId: …" continuation plumbing, capped like the live path. */
    private fun subagentReport(content: String?, maxLen: Int = 4000): String? {
        content ?: return null
        val cut = content.indexOf("\nagentId: ")
        val body = if (cut >= 0) content.substring(0, cut) else content
        return body.trim().take(maxLen).ifBlank { null }
    }

    private fun isRealUserTurn(obj: JsonObject): Boolean {
        if (obj.containsKey("toolUseResult")) return false
        val content = (obj["message"] as? JsonObject)?.get("content")
        if (content is JsonArray && content.isNotEmpty()) {
            val allToolResult = content.all { (it as? JsonObject)?.str("type") == "tool_result" }
            if (allToolResult) return false
        }
        return true
    }

    private fun userText(obj: JsonObject): String {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> content.firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
            } ?: ""
            else -> ""
        }
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
}
