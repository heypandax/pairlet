package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.protocol.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Parses OpenCode's `--format json` JSONL output into [AgentEvent]s. Each line is one JSON object
 * with a `type` field. Event types:
 *   - `step_start` → SessionInit (first time) or Ignored
 *   - `text` → AssistantText
 *   - `tool_use` → AssistantToolUse + ToolResult (emitted together when status=completed)
 *   - `step_finish` → TurnResult (when reason=stop) or Ignored (when reason=tool-calls)
 *   - `error` → AssistantText(error)
 */
object OpenCodeStreamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(line: String): List<AgentEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(trimmed) }
            .getOrNull() as? JsonObject ?: return listOf(AgentEvent.Unparseable(trimmed))
        return runCatching { dispatch(root) }
            .getOrElse { listOf(AgentEvent.Unparseable(trimmed)) }
    }

    private fun dispatch(root: JsonObject): List<AgentEvent> {
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return when (type) {
            "step_start" -> onStepStart(root)
            "text" -> onText(root)
            "tool_use" -> onToolUse(root)
            "step_finish" -> onStepFinish(root)
            "error" -> onError(root)
            else -> listOf(AgentEvent.Ignored(type))
        }
    }

    private fun onStepStart(root: JsonObject): List<AgentEvent> {
        val part = root["part"]?.jsonObject ?: return emptyList()
        val sessionId = part["sessionID"]?.jsonPrimitive?.contentOrNull
        return if (sessionId != null) {
            listOf(AgentEvent.SessionInit(sessionId = sessionId, cwd = null, model = null))
        } else {
            emptyList()
        }
    }

    private fun onText(root: JsonObject): List<AgentEvent> {
        val part = root["part"]?.jsonObject ?: return emptyList()
        val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return listOf(AgentEvent.AssistantText(text))
    }

    private fun onToolUse(root: JsonObject): List<AgentEvent> {
        val part = root["part"]?.jsonObject ?: return emptyList()
        val toolRaw = part["tool"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val toolName = ToolNameMapper.map(toolRaw)
        val callID = part["callID"]?.jsonPrimitive?.contentOrNull
        val state = part["state"]?.jsonObject
        val input = state?.get("input")?.jsonObject
        val output = state?.get("output")?.jsonPrimitive?.contentOrNull
        val status = state?.get("status")?.jsonPrimitive?.contentOrNull

        // Build Claude-shaped input for ToolMetadata compatibility
        val mappedInput = buildToolInput(toolRaw, input)

        val events = mutableListOf<AgentEvent>()
        // Emit tool use
        events.add(AgentEvent.AssistantToolUse(callID, toolName, mappedInput))
        // Emit tool result if completed
        if (status == "completed") {
            val isError = output == null && state?.get("metadata")?.jsonObject?.get("exit")?.jsonPrimitive?.longOrNull != 0L
            events.add(AgentEvent.ToolResult(callID, output, isError = isError))
        }
        return events
    }

    /** Map OpenCode tool input shapes to Claude-shaped input for ToolMetadata.
     *  Keys match OpenCode's lowercase field names; the mapped shape matches what Claude's
     *  ToolMetadata layer expects for each tool so the phone UI renders parameters correctly. */
    private fun buildToolInput(toolRaw: String, input: JsonObject?): JsonObject? {
        input ?: return null
        val normalized = toolRaw.lowercase()
        return when (normalized) {
            "bash" -> buildJsonObject {
                input["command"]?.jsonPrimitive?.contentOrNull?.let { put("command", it) }
                input["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it) }
            }
            "read" -> buildJsonObject {
                input["file_path"]?.jsonPrimitive?.contentOrNull?.let { put("file_path", it) }
            }
            "write" -> buildJsonObject {
                input["file_path"]?.jsonPrimitive?.contentOrNull?.let { put("file_path", it) }
            }
            "edit" -> buildJsonObject {
                input["file_path"]?.jsonPrimitive?.contentOrNull?.let { put("file_path", it) }
            }
            "glob" -> buildJsonObject {
                input["pattern"]?.jsonPrimitive?.contentOrNull?.let { put("pattern", it) }
                input["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it) }
            }
            "grep" -> buildJsonObject {
                input["pattern"]?.jsonPrimitive?.contentOrNull?.let { put("pattern", it) }
                input["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it) }
            }
            "webfetch" -> buildJsonObject {
                input["url"]?.jsonPrimitive?.contentOrNull?.let { put("url", it) }
            }
            "websearch" -> buildJsonObject {
                input["query"]?.jsonPrimitive?.contentOrNull?.let { put("query", it) }
                input["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it) }
            }
            "task" -> buildJsonObject {
                input["task"]?.jsonPrimitive?.contentOrNull?.let { put("task", it) }
                input["context"]?.jsonPrimitive?.contentOrNull?.let { put("context", it) }
            }
            else -> {
                // Unknown tool — pass through raw input with first-letter-uppercased keys
                // for consistency with the ToolMetadata layer
                buildJsonObject {
                    input.forEach { (k, v) ->
                        val key = k.replaceFirstChar { it.uppercase() }
                        put(key, v)
                    }
                }
            }
        }
    }

    private fun onStepFinish(root: JsonObject): List<AgentEvent> {
        val part = root["part"]?.jsonObject ?: return emptyList()
        val reason = part["reason"]?.jsonPrimitive?.contentOrNull
        if (reason == "tool-calls") return listOf(AgentEvent.Ignored("step_finish:tool-calls"))
        // reason == "stop" → turn finished
        val tokens = part["tokens"]?.jsonObject
        val usage = if (tokens != null) {
            val input = tokens["input"]?.jsonPrimitive?.longOrNull ?: 0L
            val output = tokens["output"]?.jsonPrimitive?.longOrNull ?: 0L
            val cacheRead = tokens["cache"]?.jsonObject?.get("read")?.jsonPrimitive?.longOrNull
            TokenUsage(inputTokens = input, outputTokens = output, cacheReadInputTokens = cacheRead)
        } else null
        return listOf(AgentEvent.TurnResult(finalText = null, usage = usage, isError = false))
    }

    private fun onError(root: JsonObject): List<AgentEvent> {
        val errorObj = root["error"]?.jsonObject ?: return emptyList()
        val name = errorObj["name"]?.jsonPrimitive?.contentOrNull ?: "error"
        val data = errorObj["data"]?.jsonObject
        val message = data?.get("message")?.jsonPrimitive?.contentOrNull ?: name
        return listOf(AgentEvent.AssistantText("\u26a0\ufe0f $message"))
    }
}
