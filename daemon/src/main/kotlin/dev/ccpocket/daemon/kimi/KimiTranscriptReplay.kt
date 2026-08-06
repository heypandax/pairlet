package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

/**
 * Flattens a Kimi session transcript into [HistoryMessage]s for replaying a resumed chat (issue #206). Runs
 * each line through the SAME [KimiAcpParser] the live backend uses so replay and live can't drift. Mirrors
 * the Claude/Codex/OpenCode replays: user + assistant text + tool cards, with a tool call's later
 * [AgentEvent.ToolResult] merged onto its card (outcome + output).
 *
 * ⚠ DISK FORMAT UNVERIFIED (probe blocked on device-code auth, 2026-08-06): no kimi session could be created
 * without login, so the exact on-disk transcript path/shape is unconfirmed. This parses the design's assumed
 * `agents/main/wire.jsonl` defensively — every line failing to parse as an ACP `session/update` simply yields
 * no row (fail-safe to empty), never a crash. Re-verify post-auth (design V2/V7) and adjust if the format
 * differs. Defensive line-length guard (#81 + kimi request-trace lines) caps each line before parse.
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
        val (rows, cursor) = parse(file)
        return ReplaySlicer.slice(rows, cursor, sinceSeq, maxMessages, maxFrameTextBytes)
    }

    fun page(
        file: Path,
        beforeSeq: Long,
        limit: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, _) = parse(file)
        return ReplaySlicer.page(rows, beforeSeq, limit, maxFrameTextBytes)
    }

    /** Parse wire.jsonl into replay rows tagged with source line (#147 seq) + total line count (cursor). */
    private fun parse(file: Path): Pair<List<ReplaySlicer.Row>, Long> {
        if (!file.exists()) return emptyList<ReplaySlicer.Row>() to 0L
        val out = ArrayList<ReplaySlicer.Row>()
        val toolRowIndex = HashMap<String, Int>() // tool_call_id → index in `out`, to merge its ToolResult
        var lineNo = 0L
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    lineNo += 1
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val clipped = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
                    for (ev in KimiAcpParser.parseLine(clipped)) {
                        when (ev) {
                            is AgentEvent.UserReplay ->
                                ev.text?.takeIf { it.isNotBlank() }?.let {
                                    out += ReplaySlicer.Row(HistoryMessage(ChatRole.USER, it), lineNo)
                                }
                            is AgentEvent.AssistantText ->
                                ev.text.takeIf { it.isNotBlank() }?.let {
                                    out += ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, it), lineNo)
                                }
                            is AgentEvent.AssistantToolUse -> {
                                val preview = ev.input?.toString()?.take(MAX_TOOL_TEXT) ?: ""
                                out += ReplaySlicer.Row(
                                    HistoryMessage(ChatRole.TOOL, preview, tool = ev.name), lineNo,
                                )
                                ev.id?.let { toolRowIndex[it] = out.size - 1 }
                            }
                            is AgentEvent.ToolResult -> {
                                val idx = ev.toolUseId?.let { toolRowIndex[it] }
                                if (idx != null) {
                                    val prev = out[idx]
                                    out[idx] = prev.copy(
                                        msg = prev.msg.copy(
                                            ok = !ev.isError,
                                            output = ev.content?.take(MAX_TOOL_OUTPUT),
                                        ),
                                        patchLine = lineNo, // #147: a late result mutates an earlier row
                                    )
                                }
                            }
                            else -> {} // thinking / usage / ignored / unparseable — not a chat row
                        }
                    }
                }
            }
        }
        return out to lineNo
    }
}
