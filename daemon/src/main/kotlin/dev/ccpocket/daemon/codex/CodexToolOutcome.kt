package dev.ccpocket.daemon.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Only positive completion evidence makes a tool foldable. Unknown/running stays null. */
internal fun codexToolStatus(status: String?): Boolean? = when (status) {
    "completed" -> true
    "failed", "declined", "cancelled", "canceled", "interrupted", "aborted" -> false
    else -> null
}

private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

/** App-server uses camelCase; rollout item_completed records use PascalCase + snake_case. */
internal fun codexCompletedToolOutcome(item: JsonObject): Boolean? {
    val status = item.str("status")
    if (status == "inProgress" || status == "in_progress") return null
    return when (item.str("type")) {
        "commandExecution", "CommandExecution" -> {
            val exit = item.long("exitCode") ?: item.long("exit_code")
            when {
                codexToolStatus(status) == false || (exit != null && exit != 0L) -> false
                exit == 0L -> true
                else -> codexToolStatus(status)
            }
        }
        "fileChange", "FileChange" -> codexToolStatus(status)
        "mcpToolCall", "McpToolCall" -> when {
            item["error"]?.let { it != JsonNull } == true || item.obj("result")?.bool("isError") == true -> false
            else -> codexToolStatus(status)
        }
        "dynamicToolCall", "DynamicToolCall" -> when {
            item.bool("success") == false || codexToolStatus(status) == false -> false
            item.bool("success") == true -> true
            else -> codexToolStatus(status)
        }
        // The live webSearch item has no status: item/completed itself is the completion evidence.
        "webSearch", "WebSearch" -> true
        else -> null
    }
}

internal fun codexToolOutputText(output: JsonElement?): String? = when (output) {
    is JsonPrimitive -> output.contentOrNull
    is JsonArray -> output.mapNotNull { part ->
        (part as? JsonObject)?.takeIf { it.str("type") in setOf("text", "input_text", "output_text") }?.str("text")
    }.joinToString("\n").takeIf { it.isNotEmpty() }
    else -> null
}

private val shellTools = setOf("shell", "shell_command", "exec_command", "write_stdin")
private val shellExit = Regex("""(?:Chunk ID: [^\n]*\n)?Wall time: [^\n]*\nProcess exited with code (-?\d+)\n(?:Final output:|Output:)""")

/** Older rollouts have only response_item outputs. Read their envelopes, never arbitrary output prose.
 * Newer item_completed records take precedence in the replay. A receipt alone is not proof of success. */
internal fun codexRolloutToolOutcome(tool: String, payload: JsonObject): Boolean? {
    val output = payload["output"]
    val text = codexToolOutputText(output)
    val obj = output as? JsonObject ?: text?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }
    val flags = listOfNotNull(payload.bool("is_error"), payload.bool("isError"), obj?.bool("isError"), obj?.bool("is_error"))
    if (true in flags) return false
    val name = tool.substringAfterLast('.')
    if (name in shellTools) {
        val exit = obj?.obj("metadata")?.long("exit_code") ?: obj?.long("exit_code")
        if (exit != null) return exit == 0L
        shellExit.find(text.orEmpty())?.takeIf { it.range.first == 0 }?.let {
            return it.groupValues[1].toLongOrNull()?.let { code -> code == 0L }
        }
    }
    if (name == "exec" || name == "wait") {
        when (text?.lineSequence()?.firstOrNull()) {
            "Script completed" -> return true
            "Script failed", "Script error:" -> return false
        }
    }
    if (name == "apply_patch" && text?.startsWith("Success. Updated the following files:\n") == true) return true
    // Explicit flags are meaningful; missing flags in arbitrary JSON/text are not.
    if (flags.isNotEmpty()) return true
    return null
}
