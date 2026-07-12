package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.protocol.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure: one stdout line -> a list of [AgentEvent] (an `assistant` line can carry several content
 * blocks). Dispatches manually on `type`/`subtype` and tolerates unknown shapes — Anthropic adds
 * event types over time, so a strict deserializer would be fragile.
 */
object StreamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(line: String): List<AgentEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return listOf(AgentEvent.Unparseable(trimmed))
        return when (val type = root.str("type")) {
            "system" -> listOf(parseSystem(root))
            "assistant" -> parseAssistant(root)
            "user" -> parseUser(root)
            "result" -> listOf(parseResult(root))
            "control_request" -> listOf(parseControlRequest(root))
            "control_cancel_request" -> listOf(AgentEvent.ControlCancel(root.str("request_id") ?: ""))
            else -> listOf(AgentEvent.Ignored(type))
        }
    }

    private fun parseSystem(root: JsonObject): AgentEvent {
        // background-task lifecycle (backgrounded shells): claude emits these as system events, NOT in the
        // tool_result. They carry session_id too, so they must be matched on subtype BEFORE the init fallback.
        when (root.str("subtype")) {
            "task_started" -> root.str("task_id")?.let {
                return AgentEvent.BackgroundTaskStarted(
                    it, root.str("tool_use_id"), root.str("description"), root.str("task_type"),
                    workflowName = root.str("workflow_name"), // rides a Workflow launch (issue #106)
                )
            }
            "task_updated" -> root.str("task_id")?.let {
                return AgentEvent.BackgroundTaskUpdated(it, (root["patch"] as? JsonObject)?.let { p -> p.str("status") })
            }
            // task_notification also carries tool_use_id + summary — for a backgrounded sub-agent this
            // pair is the authoritative completion (its tool_result was only the launch ack) — issue #77
            "task_notification" -> root.str("task_id")?.let {
                return AgentEvent.BackgroundTaskUpdated(it, root.str("status"), toolUseId = root.str("tool_use_id"), summary = root.str("summary"))
            }
            // a Workflow run's progress snapshot: the CLI re-sends the CUMULATIVE workflow_progress
            // array on every agent/phase state transition; pure activity ticks omit the array and are
            // ignored here (they carry no per-agent state) — issue #106, probed on 2.1.206
            "task_progress" -> root.str("task_id")?.let { tid ->
                val items = root["workflow_progress"] as? JsonArray ?: return AgentEvent.Ignored("system/task_progress")
                return AgentEvent.WorkflowProgress(tid, root.str("tool_use_id"), items)
            }
        }
        // session_id appears on hook_started/hook_response as well as init — take the first one we see
        // so the conversation goes "live" immediately, not only once `init` lands.
        val sid = root.str("session_id")
        return if (sid != null) {
            AgentEvent.SessionInit(sessionId = sid, cwd = root.str("cwd"), model = root.str("model"))
        } else {
            AgentEvent.Ignored("system/${root.str("subtype")}")
        }
    }

    private fun parseAssistant(root: JsonObject): List<AgentEvent> {
        val message = root["message"] as? JsonObject
        val content = message?.get("content") as? JsonArray
            ?: return listOf(AgentEvent.Ignored("assistant"))
        // model == "<synthetic>" = the CLI's own API-failure placeholder ("No response requested."),
        // written after every call of the turn failed. Its usage is all zeros too — don't emit it as a
        // normal reply (it would render as a real answer) and don't let its zero usage poison the
        // statusline; surface it as one SyntheticReply the Conversation turns into an error (issue #65).
        if (message.str("model") == SYNTHETIC_MODEL) {
            val text = content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
                .joinToString("\n").ifBlank { "No response from the API" }
            return listOf(AgentEvent.SyntheticReply(text))
        }
        // sub-agent (Task/Agent) events stream in the SAME stdout with parent_tool_use_id set at the
        // root — carry it so the Conversation can fold them into the parent's card instead of the
        // main chat (issue #77)
        val parentId = root.str("parent_tool_use_id")
        val blocks = content.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            when (block.str("type")) {
                "text" -> block.str("text")?.let { AgentEvent.AssistantText(it, parentId) }
                "thinking" -> block.str("thinking")?.let { AgentEvent.AssistantThinking(it, parentId) }
                "tool_use" -> AgentEvent.AssistantToolUse(
                    id = block.str("id"),
                    name = block.str("name") ?: "tool",
                    input = block["input"] as? JsonObject,
                    parentId = parentId,
                )
                else -> null
            }
        }
        // per-call usage rides every assistant message — the turn's `result` only carries the SUM of all
        // calls (see [AgentEvent.AssistantUsage]). Skip subagent-originated events: their usage is the
        // SUBAGENT's own window (same rule as the transcript scanner's isSidechain guard).
        val usage = (message["usage"] as? JsonObject)?.takeIf { root.str("parent_tool_use_id") == null }
        return if (usage == null) blocks else blocks + AgentEvent.AssistantUsage(
            inputTokens = usage.long("input_tokens") ?: 0,
            cacheCreationInputTokens = usage.long("cache_creation_input_tokens"),
            cacheReadInputTokens = usage.long("cache_read_input_tokens"),
        )
    }

    /**
     * A `user` line is either a replayed user turn (--replay-user-messages) or claude feeding tool_results
     * back in. We surface tool_results (for background-job tracking) and treat everything else as a replay.
     */
    private fun parseUser(root: JsonObject): List<AgentEvent> {
        val parentId = root.str("parent_tool_use_id")
        val rawContent = (root["message"] as? JsonObject)?.get("content")
        val content = rawContent as? JsonArray
            // a plain-string content is the common replay shape for a text-only prompt — carry the text
            // so the Conversation's unconsumed-prompt ledger can settle the matching entry (issue #122)
            ?: return listOf(AgentEvent.UserReplay(text = (rawContent as? JsonPrimitive)?.contentOrNull, parentId = parentId))
        val results = content.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            if (block.str("type") != "tool_result") return@mapNotNull null
            AgentEvent.ToolResult(
                toolUseId = block.str("tool_use_id"),
                content = toolResultText(block["content"]),
                isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
                parentId = parentId, // set = a result INSIDE a sub-agent, not the main chain
            )
        }
        // a Workflow tool's async-launch ack: the run id ONLY reaches the live stream via this
        // root-level tool_use_result (`{taskType:"local_workflow", runId, taskId, workflowName}`,
        // probed 2.1.206) — surface it so the tracker can tie the chat card to the run (issue #106)
        val launch = (root["tool_use_result"] as? JsonObject)
            ?.takeIf { it.str("taskType") == "local_workflow" }
            ?.let { r ->
                r.str("runId")?.let { runId ->
                    AgentEvent.WorkflowLaunched(
                        toolUseId = (content.firstOrNull() as? JsonObject)?.str("tool_use_id"),
                        runId = runId,
                        taskId = r.str("taskId"),
                        workflowName = r.str("workflowName"),
                    )
                }
            }
        val all = if (launch != null) results + launch else results
        return all.ifEmpty {
            val text = content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
                .joinToString("\n")
            listOf(AgentEvent.UserReplay(text = text, parentId = parentId))
        }
    }

    /** tool_result `content` is either a raw string or an array of {type:text,text:…} blocks. */
    private fun toolResultText(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n").ifBlank { null }
        else -> null
    }

    private fun parseResult(root: JsonObject): AgentEvent {
        val usage = root["usage"] as? JsonObject
        val isError = (root["is_error"]?.jsonPrimitive?.contentOrNull == "true") ||
            (root.str("subtype")?.let { it != "success" } ?: false)
        return AgentEvent.TurnResult(
            finalText = root.str("result"),
            // interrupted/error results can omit usage — null then means "unknown", not "empty window"
            usage = usage?.let {
                TokenUsage(
                    inputTokens = it.long("input_tokens") ?: 0,
                    outputTokens = it.long("output_tokens") ?: 0,
                    cacheCreationInputTokens = it.long("cache_creation_input_tokens"),
                    cacheReadInputTokens = it.long("cache_read_input_tokens"),
                )
            },
            isError = isError,
        )
    }

    private fun parseControlRequest(root: JsonObject): AgentEvent {
        val requestId = root.str("request_id") ?: ""
        val request = root["request"] as? JsonObject
        return if (request?.str("subtype") == "can_use_tool") {
            AgentEvent.ControlRequest(
                requestId = requestId,
                toolName = request.str("tool_name") ?: "tool",
                input = request["input"] as? JsonObject,
            )
        } else AgentEvent.Ignored("control_request/${request?.str("subtype")}")
    }

    /** The CLI's placeholder model id on API-failure/notice records (same literal TranscriptScanner skips). */
    const val SYNTHETIC_MODEL = "<synthetic>"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.long(key: String): Long? {
        val el: JsonElement = this?.get(key) ?: return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.longOrNull ?: prim.contentOrNull?.toDoubleOrNull()?.toLong()
    }
}
