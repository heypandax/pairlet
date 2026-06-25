package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    when (obj.str("type")) {
                        // drop harness plumbing (standalone task-notifications, the resume nudge) so the
                        // phone replays the real conversation, not background-shell chatter
                        "user" -> if (isRealUserTurn(obj)) userText(obj)
                            .takeIf { it.isNotBlank() && !TranscriptNoise.isNoiseUserText(it) }
                            ?.let { out += HistoryMessage(ChatRole.USER, it.take(maxTextLen)) }
                        "assistant" -> assistantBlocks(obj, maxTextLen).forEach { out += it }
                    }
                }
            }
        }
        return if (out.size > maxMessages) out.takeLast(maxMessages) else out
    }

    private fun assistantBlocks(obj: JsonObject, maxTextLen: Int): List<HistoryMessage> {
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
        val items = ArrayList<HistoryMessage>()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotBlank() }
                    ?.let { items += HistoryMessage(ChatRole.ASSISTANT, it.take(maxTextLen)) }
                "tool_use" -> items += HistoryMessage(
                    ChatRole.TOOL,
                    text = block["input"]?.toString()?.take(1000) ?: "", // full-ish input; the app shows it on tap-to-expand
                    tool = block.str("name") ?: "tool",
                )
            }
        }
        return items
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

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
