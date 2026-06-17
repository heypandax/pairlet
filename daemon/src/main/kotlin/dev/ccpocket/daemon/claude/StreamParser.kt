package dev.ccpocket.daemon.claude

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
 * Pure: one stdout line -> a list of [ClaudeEvent] (an `assistant` line can carry several content
 * blocks). Dispatches manually on `type`/`subtype` and tolerates unknown shapes — Anthropic adds
 * event types over time, so a strict deserializer would be fragile.
 */
object StreamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return listOf(ClaudeEvent.Unparseable(trimmed))
        return when (val type = root.str("type")) {
            "system" -> listOf(parseSystem(root))
            "assistant" -> parseAssistant(root)
            "user" -> parseUser(root)
            "result" -> listOf(parseResult(root))
            "control_request" -> listOf(parseControlRequest(root))
            "control_cancel_request" -> listOf(ClaudeEvent.ControlCancel(root.str("request_id") ?: ""))
            else -> listOf(ClaudeEvent.Ignored(type))
        }
    }

    private fun parseSystem(root: JsonObject): ClaudeEvent {
        // background-task lifecycle (backgrounded shells): claude emits these as system events, NOT in the
        // tool_result. They carry session_id too, so they must be matched on subtype BEFORE the init fallback.
        when (root.str("subtype")) {
            "task_started" -> root.str("task_id")?.let {
                return ClaudeEvent.BackgroundTaskStarted(it, root.str("tool_use_id"), root.str("description"), root.str("task_type"))
            }
            "task_updated" -> root.str("task_id")?.let {
                return ClaudeEvent.BackgroundTaskUpdated(it, (root["patch"] as? JsonObject)?.let { p -> p.str("status") })
            }
            "task_notification" -> root.str("task_id")?.let {
                return ClaudeEvent.BackgroundTaskUpdated(it, root.str("status"))
            }
        }
        // session_id appears on hook_started/hook_response as well as init — take the first one we see
        // so the conversation goes "live" immediately, not only once `init` lands.
        val sid = root.str("session_id")
        return if (sid != null) {
            ClaudeEvent.SessionInit(sessionId = sid, cwd = root.str("cwd"), model = root.str("model"))
        } else {
            ClaudeEvent.Ignored("system/${root.str("subtype")}")
        }
    }

    private fun parseAssistant(root: JsonObject): List<ClaudeEvent> {
        val content = (root["message"] as? JsonObject)?.get("content") as? JsonArray
            ?: return listOf(ClaudeEvent.Ignored("assistant"))
        return content.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            when (block.str("type")) {
                "text" -> block.str("text")?.let { ClaudeEvent.AssistantText(it) }
                "thinking" -> block.str("thinking")?.let { ClaudeEvent.AssistantThinking(it) }
                "tool_use" -> ClaudeEvent.AssistantToolUse(
                    id = block.str("id"),
                    name = block.str("name") ?: "tool",
                    input = block["input"] as? JsonObject,
                )
                else -> null
            }
        }
    }

    /**
     * A `user` line is either a replayed user turn (--replay-user-messages) or claude feeding tool_results
     * back in. We surface tool_results (for background-job tracking) and treat everything else as a replay.
     */
    private fun parseUser(root: JsonObject): List<ClaudeEvent> {
        val content = (root["message"] as? JsonObject)?.get("content") as? JsonArray
            ?: return listOf(ClaudeEvent.UserReplay)
        val results = content.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            if (block.str("type") != "tool_result") return@mapNotNull null
            ClaudeEvent.ToolResult(
                toolUseId = block.str("tool_use_id"),
                content = toolResultText(block["content"]),
                isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        return results.ifEmpty { listOf(ClaudeEvent.UserReplay) }
    }

    /** tool_result `content` is either a raw string or an array of {type:text,text:…} blocks. */
    private fun toolResultText(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n").ifBlank { null }
        else -> null
    }

    private fun parseResult(root: JsonObject): ClaudeEvent {
        val usage = root["usage"] as? JsonObject
        val isError = (root["is_error"]?.jsonPrimitive?.contentOrNull == "true") ||
            (root.str("subtype")?.let { it != "success" } ?: false)
        return ClaudeEvent.TurnResult(
            finalText = root.str("result"),
            inputTokens = usage.long("input_tokens") ?: 0,
            outputTokens = usage.long("output_tokens") ?: 0,
            cacheCreationInputTokens = usage.long("cache_creation_input_tokens"),
            cacheReadInputTokens = usage.long("cache_read_input_tokens"),
            isError = isError,
        )
    }

    private fun parseControlRequest(root: JsonObject): ClaudeEvent {
        val requestId = root.str("request_id") ?: ""
        val request = root["request"] as? JsonObject
        return if (request?.str("subtype") == "can_use_tool") {
            ClaudeEvent.ControlRequest(
                requestId = requestId,
                toolName = request.str("tool_name") ?: "tool",
                input = request["input"] as? JsonObject,
            )
        } else ClaudeEvent.Ignored("control_request/${request?.str("subtype")}")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.long(key: String): Long? {
        val el: JsonElement = this?.get(key) ?: return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.longOrNull ?: prim.contentOrNull?.toDoubleOrNull()?.toLong()
    }
}
