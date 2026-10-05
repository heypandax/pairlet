package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.disk.ReplayRead
import dev.ccpocket.observability.*

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
import dev.ccpocket.daemon.disk.sizeOrNull
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

    /** A late tool completion patches its original row. The shared slicer falls back to a full window
     *  when that row was already delivered, so a reconnect cannot lose the outcome in an empty delta. */
    fun slice(
        file: Path,
        sinceSeq: Long?,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val parsed = parse(file)
        return ReplaySlicer.slice(parsed.first, parsed.second, sinceSeq, maxMessages, maxFrameTextBytes)
            .copy(quality = parsed.quality, sourceRows = parsed.second, failedRows = parsed.failedRows, sourceBytes = file.sizeOrNull())
    }

    /** One page of history OLDER than [beforeSeq] — the scroll-to-top lazy load (issue #147). */
    fun page(
        file: Path,
        beforeSeq: Long,
        limit: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val parsed = parse(file)
        return ReplaySlicer.page(parsed.first, beforeSeq, limit, maxFrameTextBytes)
            .copy(quality = parsed.quality, sourceRows = parsed.second, failedRows = parsed.failedRows)
    }

    /** Parse the rollout into rows tagged with their source line (the #147 seq) + the total line count
     *  (the cursor). Every raw line advances the cursor — stable under append-only growth. */
    private fun parse(file: Path): ReplayRead<ReplaySlicer.Row> {
        if (!file.exists()) return ReplayRead(emptyList(), 0L, "unavailable")
        val out = ArrayList<ReplaySlicer.Row>()
        val toolIndices = HashMap<String, Int>()
        val completions = HashMap<String, Pair<Boolean, Long>>()
        fun patch(id: String, ok: Boolean, line: Long) {
            val index = toolIndices[id] ?: return
            val row = out[index]
            if (row.msg.ok != ok) out[index] = row.copy(msg = row.msg.copy(ok = ok), patchLine = line)
        }
        var lineNo = 0L
        var malformed = 0L
        var lastMalformed = -1L
        var firstError: Throwable? = null
        var readFailed = false
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    lineNo += 1
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.onFailure {
                        malformed++; lastMalformed = lineNo
                        if (firstError == null) firstError = it
                    }.getOrNull() as? JsonObject ?: continue
                    val p = obj.obj("payload") ?: continue
                    if (obj.str("type") == "event_msg" && p.str("type") == "item_completed") {
                        val item = p.obj("item") ?: continue
                        val id = item.str("id") ?: continue
                        val ok = codexCompletedToolOutcome(item) ?: continue
                        completions[id] = ok to lineNo
                        patch(id, ok, lineNo)
                        continue
                    }
                    if (obj.str("type") != "response_item") continue
                    when (p.str("type")) {
                        "message" -> {
                            val text = codexMessageText(p)?.takeIf { it.isNotBlank() } ?: continue
                            when (p.str("role")) {
                                "user" -> if (!isSyntheticUserText(text)) out += ReplaySlicer.Row(HistoryMessage(ChatRole.USER, text), lineNo)
                                "assistant" -> out += ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, text), lineNo)
                                else -> {}
                            }
                        }
                        "function_call", "custom_tool_call" -> {
                            val id = p.str("call_id")
                            if (id != null) toolIndices[id] = out.size
                            val completion = completions[id]
                            val input = if (p.str("type") == "function_call") p.str("arguments") else p.str("input")
                            out += ReplaySlicer.Row(
                                HistoryMessage(ChatRole.TOOL, input.orEmpty().take(1000), tool = p.str("name") ?: "tool", ok = completion?.first),
                                lineNo, completion?.second ?: 0L,
                            )
                        }
                        "web_search_call" -> out += ReplaySlicer.Row(
                            HistoryMessage(ChatRole.TOOL, "", tool = "WebSearch", ok = codexToolStatus(p.str("status"))), lineNo,
                        )
                        "function_call_output", "custom_tool_call_output" -> {
                            val id = p.str("call_id") ?: continue
                            if (id in completions) continue // typed completion is authoritative
                            val index = toolIndices[id] ?: continue
                            val ok = codexRolloutToolOutcome(out[index].msg.tool.orEmpty(), p) ?: continue
                            patch(id, ok, lineNo)
                        }
                    }
                }
            }
        }
        .onFailure { readFailed = true; Diagnostics.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, it,
            SafeMetrics(totalCount = lineNo, returnedCount = out.size.toLong(), resultQuality = ResultQuality.PARTIAL)) }
        if (malformed > 0) {
            val corrupt = malformed > 1 || lastMalformed != lineNo
            Diagnostics.report(ErrorPath.HISTORY_READ, Stage.PARSE,
                if (corrupt) ErrorCode.DECODE_FAILED else ErrorCode.INCOMPLETE,
                if (corrupt) firstError else null,
                SafeMetrics(totalCount = lineNo, failedCount = malformed, returnedCount = out.size.toLong(), resultQuality = ResultQuality.PARTIAL))
        }
        return ReplayRead(out, lineNo, if (readFailed || malformed > 0) "partial" else "complete", malformed)
    }
}
