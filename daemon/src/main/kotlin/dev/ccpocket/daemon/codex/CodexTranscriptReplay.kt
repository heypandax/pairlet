package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
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

    fun read(file: Path, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> =
        slice(file, sinceSeq = null, maxMessages = maxMessages, maxFrameTextBytes = maxFrameTextBytes).messages

    /** The (re)open replay with cursor metadata (issue #147) — a DELTA past [sinceSeq] when it can be
     *  honored, else the full tail window. Codex rows are never patched after the fact, so the shared
     *  slicer's cross-patch fallback simply never triggers here. */
    fun slice(
        file: Path,
        sinceSeq: Long?,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, cursor) = parse(file)
        return ReplaySlicer.slice(rows, cursor, sinceSeq, maxMessages, maxFrameTextBytes)
    }

    /** One page of history OLDER than [beforeSeq] — the scroll-to-top lazy load (issue #147). */
    fun page(
        file: Path,
        beforeSeq: Long,
        limit: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, _) = parse(file)
        return ReplaySlicer.page(rows, beforeSeq, limit, maxFrameTextBytes)
    }

    /** Parse the rollout into rows tagged with their source line (the #147 seq) + the total line count
     *  (the cursor). Every raw line advances the cursor — stable under append-only growth. */
    private fun parse(file: Path): Pair<List<ReplaySlicer.Row>, Long> {
        if (!file.exists()) return emptyList<ReplaySlicer.Row>() to 0L
        val out = ArrayList<ReplaySlicer.Row>()
        var lineNo = 0L
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    lineNo += 1
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    if (obj.str("type") != "response_item") continue
                    val p = obj.obj("payload") ?: continue
                    when (p.str("type")) {
                        "message" -> {
                            val text = codexMessageText(p)?.takeIf { it.isNotBlank() } ?: continue
                            when (p.str("role")) {
                                "user" -> if (!isSyntheticUserText(text)) out += ReplaySlicer.Row(HistoryMessage(ChatRole.USER, text), lineNo)
                                "assistant" -> out += ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, text), lineNo)
                                else -> {}
                            }
                        }
                        "function_call" ->
                            out += ReplaySlicer.Row(HistoryMessage(ChatRole.TOOL, (p.str("arguments") ?: "").take(1000), tool = p.str("name") ?: "tool"), lineNo)
                        "web_search_call" -> out += ReplaySlicer.Row(HistoryMessage(ChatRole.TOOL, "", tool = "WebSearch"), lineNo)
                        "custom_tool_call" ->
                            out += ReplaySlicer.Row(HistoryMessage(ChatRole.TOOL, (p.str("input") ?: "").take(1000), tool = p.str("name") ?: "tool"), lineNo)
                    }
                }
            }
        }
        return out to lineNo
    }
}
