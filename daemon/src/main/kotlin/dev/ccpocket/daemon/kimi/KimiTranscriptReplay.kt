package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.disk.ReplayRead
import dev.ccpocket.observability.*

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

/**
 * Flattens a Kimi session transcript into [HistoryMessage]s for replaying a resumed chat (issue #206).
 * Mirrors the Claude/Codex/OpenCode replays: user + assistant text + tool cards, with a tool call's later
 * result merged onto its card (outcome + output).
 *
 * DISK FORMAT (probe-verified on 0.34.0, 2026-08-08): `agents/main/wire.jsonl` is the CLI's INTERNAL wire
 * log — NOT ACP `session/update` notifications as the pre-auth design assumed (that assumption produced
 * zero replay rows in the field). The chat-bearing line types are:
 *  - `{"type":"turn.prompt", input:[{type:"text",text:…}]}`        → user row (real prompts only;
 *    `context.append_message` also carries role:user but folds in `<system-reminder>` wrappers — skipped)
 *  - `{"type":"context.append_loop_event", event:{type:"content.part", part:{type:"text"|"think", …}}}`
 *      → assistant text rows (`think` parts are thinking, not chat rows)
 *  - `… event:{type:"tool.call", toolCallId, name, args}`          → tool card (full input in `args`)
 *  - `… event:{type:"tool.result", toolCallId, result:{output, isError?}}` → merged onto the card
 * Everything else (metadata, llm.request, usage.record, step.*, turn.ended, interaction.*) is skipped.
 * Defensive line-length guard (#81 + kimi request-trace lines) caps each line before parse; unparseable
 * lines simply yield no row (fail-safe to empty), never a crash.
 */
object KimiTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Kimi's wire.jsonl carries request-trace lines (full tool schema / MCP tool lists) that can be huge.
    // A single chat line never legitimately needs this much; cap before parse so one trace line can't
    // dominate memory or the frame (#81). Tool previews/outputs are further capped below.
    private const val MAX_LINE_CHARS = 200_000
    private const val MAX_TOOL_TEXT = 1000
    private const val MAX_TOOL_OUTPUT = 4000

    fun read(file: Path, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> =
        slice(file, sinceSeq = null, maxMessages = maxMessages, maxFrameTextBytes = maxFrameTextBytes).messages

    fun slice(
        file: Path,
        sinceSeq: Long?,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val parsed = parse(file)
        return ReplaySlicer.slice(parsed.first, parsed.second, sinceSeq, maxMessages, maxFrameTextBytes)
            .copy(quality = parsed.quality, sourceRows = parsed.second, failedRows = parsed.failedRows)
    }

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

    /** Parse wire.jsonl into replay rows tagged with source line (#147 seq) + total line count (cursor). */
    private fun parse(file: Path): ReplayRead<ReplaySlicer.Row> {
        if (!file.exists()) return ReplayRead(emptyList(), 0L, "unavailable")
        val out = ArrayList<ReplaySlicer.Row>()
        val toolRowIndex = HashMap<String, Int>() // toolCallId → index in `out`, to merge its tool.result
        var lineNo = 0L
        var malformed = 0L
        var limited = 0L
        var lastMalformed = -1L
        var firstError: Throwable? = null
        var readFailed = false
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    lineNo += 1
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if (line.length > MAX_LINE_CHARS) limited++
                    val clipped = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
                    val root = runCatching { json.parseToJsonElement(clipped) }.onFailure {
                        if (line.length <= MAX_LINE_CHARS) {
                            malformed++; lastMalformed = lineNo
                            if (firstError == null) firstError = it
                        }
                    }.getOrNull() as? JsonObject
                        ?: continue // unparseable — no row, never a crash
                    when (root.str("type")) {
                        // a real user prompt entering a turn (context.append_message also logs role:user
                        // lines, but those fold in <system-reminder> wrappers — turn.prompt stays clean)
                        "turn.prompt" ->
                            contentBlockText(root["input"])?.takeIf { it.isNotBlank() }?.let {
                                out += ReplaySlicer.Row(HistoryMessage(ChatRole.USER, it), lineNo)
                            }
                        "context.append_loop_event" -> {
                            val ev = root.obj("event") ?: continue
                            when (ev.str("type")) {
                                "content.part" -> {
                                    val part = ev.obj("part") ?: continue
                                    // text parts are assistant replies; think parts are thinking (not chat rows)
                                    if (part.str("type") == "text") {
                                        part.str("text")?.takeIf { it.isNotBlank() }?.let {
                                            out += ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, it), lineNo)
                                        }
                                    }
                                }
                                "tool.call" -> {
                                    val preview = ev.obj("args")?.toString()?.take(MAX_TOOL_TEXT) ?: ""
                                    out += ReplaySlicer.Row(
                                        HistoryMessage(ChatRole.TOOL, preview, tool = ev.str("name") ?: "tool"), lineNo,
                                    )
                                    ev.str("toolCallId")?.let { toolRowIndex[it] = out.size - 1 }
                                }
                                "tool.result" -> {
                                    val idx = ev.str("toolCallId")?.let { toolRowIndex[it] }
                                    if (idx != null) {
                                        val result = ev.obj("result")
                                        val output = (result?.get("output") as? JsonPrimitive)?.contentOrNull
                                            ?: result?.toString()
                                        val isError = (result?.get("isError") as? JsonPrimitive)?.booleanOrNull == true
                                        val prev = out[idx]
                                        out[idx] = prev.copy(
                                            msg = prev.msg.copy(
                                                ok = !isError,
                                                output = output?.take(MAX_TOOL_OUTPUT),
                                            ),
                                            patchLine = lineNo, // #147: a late result mutates an earlier row
                                        )
                                    }
                                }
                                else -> {} // step.begin/end, llm.request, usage.record, … — not a chat row
                            }
                        }
                        else -> {} // metadata, profile.bind, interaction.*, turn.ended, …
                    }
                }
            }
        }
        .onFailure { readFailed = true; Diagnostics.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, it,
            SafeMetrics(totalCount = lineNo, returnedCount = out.size.toLong(), resultQuality = ResultQuality.PARTIAL)) }
        if (malformed > 0 || limited > 0) {
            val corrupt = malformed > 1 || malformed > 0 && lastMalformed != lineNo
            Diagnostics.report(ErrorPath.HISTORY_READ, Stage.PARSE,
                if (corrupt) ErrorCode.DECODE_FAILED else if (limited > 0) ErrorCode.SIZE_LIMIT else ErrorCode.INCOMPLETE,
                if (corrupt) firstError else null,
                SafeMetrics(totalCount = lineNo, failedCount = malformed + limited, returnedCount = out.size.toLong(), resultQuality = ResultQuality.PARTIAL))
        }
        return ReplayRead(out, lineNo, if (readFailed || malformed > 0 || limited > 0) "partial" else "complete", malformed + limited)
    }
}
