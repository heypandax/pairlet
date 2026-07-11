package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

/**
 * Flattens a Codex rollout `.jsonl` into [HistoryMessage]s for replaying a resumed chat. Mirrors the Claude
 * [dev.ccpocket.daemon.disk.TranscriptReplay]: user + assistant text + tool calls, skipping the Codex-injected
 * context blocks (env/permission wrappers, AGENTS.md dump, @-file expansion — see [isSyntheticUserText]). Schema: `response_item` payloads —
 * `message` (role user `input_text` / assistant `output_text`), `function_call`, `web_search_call`, `custom_tool_call`.
 */
object CodexTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(file: Path, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<HistoryMessage>()
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    if (obj.str("type") != "response_item") continue
                    val p = obj.obj("payload") ?: continue
                    when (p.str("type")) {
                        "message" -> {
                            val text = codexMessageText(p)?.takeIf { it.isNotBlank() } ?: continue
                            when (p.str("role")) {
                                "user" -> if (!isSyntheticUserText(text)) out += HistoryMessage(ChatRole.USER, text)
                                "assistant" -> out += HistoryMessage(ChatRole.ASSISTANT, text)
                                else -> {}
                            }
                        }
                        "function_call" ->
                            out += HistoryMessage(ChatRole.TOOL, (p.str("arguments") ?: "").take(1000), tool = p.str("name") ?: "tool")
                        "web_search_call" -> out += HistoryMessage(ChatRole.TOOL, "", tool = "WebSearch")
                        "custom_tool_call" ->
                            out += HistoryMessage(ChatRole.TOOL, (p.str("input") ?: "").take(1000), tool = p.str("name") ?: "tool")
                    }
                }
            }
        }
        val capped = if (out.size > maxMessages) out.takeLast(maxMessages) else out
        return ReplayBudget.fit(capped, maxFrameTextBytes)
    }

}
