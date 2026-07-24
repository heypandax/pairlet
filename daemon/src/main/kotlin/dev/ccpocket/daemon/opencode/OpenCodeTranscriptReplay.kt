package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection

/**
 * Reconstructs [HistoryMessage]s from an OpenCode session in the SQLite database.
 *
 * Reads the message/part tables and, like the Claude [dev.ccpocket.daemon.disk.TranscriptReplay] and
 * Codex [dev.ccpocket.daemon.codex.CodexTranscriptReplay], emits a tool part as its own structured
 * `ChatRole.TOOL` row (issue #177) — so the phone renders it as a foldable tool card instead of a `[bash]
 * …output…` line flattened into the assistant bubble. Text and tool parts stay in their recorded order
 * (the `part` table is time-ordered), so an "explain, run, explain" turn replays as text · card · text.
 *
 * The tool NAME is mapped through [ToolNameMapper] to its Claude-shaped form (bash → Bash …) so the row
 * reuses the exact card the live [OpenCodeStreamParser] path produces; the row's `text` is the tool's
 * `state.input` (the bash command etc.) as the compact input preview, matching how the live card and the
 * Claude/Codex replays fill a tool card's preview. A SETTLED call also carries its `output` and ok/failed
 * verdict (the live parser's rule), so the folded card expands to the same information live left behind.
 */
object OpenCodeTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Tool input preview cap — the compact "command"/input line shown on the card (display-on-tap, not a
    // reply body). Mirrors the Claude replay's MAX_TOOL_TEXT so all three backends cap tool previews alike.
    private const val MAX_TOOL_TEXT = 1000

    // Tool output cap — matches the Claude replay's sub-agent report cap (SUBAGENT_OUTPUT_MAX = 4000) so one
    // large tool result can't dominate the history frame; [ReplayBudget] stays the outer frame-level guard.
    // Reply-body text is deliberately NOT per-row capped (issue #81 parity with the Claude/Codex replays).
    private const val MAX_TOOL_OUTPUT = 4000

    fun read(sessionId: String, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return emptyList()
            conn.use { readFrom(it, sessionId, maxMessages, maxFrameTextBytes) }
        }.getOrDefault(emptyList())
    }

    /**
     * The parse over an already-open [conn] — the seam the unit test injects an in-memory opencode.db
     * through (the production [read] opens the busy-tolerant read-only connection for it). Rows are the
     * flat, chronological text/tool stream; `maxMessages` then keeps the newest ROWS (a turn that expanded
     * into text+card+text counts as its rows, exactly like the Claude/Codex slicers) and [ReplayBudget]
     * bounds the total frame bytes.
     */
    internal fun readFrom(conn: Connection, sessionId: String, maxMessages: Int, maxFrameTextBytes: Long): List<HistoryMessage> {
        val msgStmt = conn.prepareStatement(
            "SELECT id, data FROM message WHERE session_id = ? ORDER BY time_created ASC",
        )
        msgStmt.setString(1, sessionId)
        val msgRs = msgStmt.executeQuery()

        val out = mutableListOf<HistoryMessage>()
        while (msgRs.next()) {
            val messageId = msgRs.getString("id") ?: continue
            val dataStr = msgRs.getString("data") ?: continue
            val msgData = runCatching { json.parseToJsonElement(dataStr) }.getOrNull() as? JsonObject ?: continue
            val role = when (msgData["role"]?.jsonPrimitive?.contentOrNull) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            out += messageRows(conn, sessionId, messageId, role)
        }
        val capped = if (out.size > maxMessages) out.takeLast(maxMessages) else out
        return ReplayBudget.fit(capped, maxFrameTextBytes)
    }

    /**
     * One message's parts, in recorded order, as interleaved history rows: consecutive text parts fold
     * into a single [role] bubble, and each `tool` part is flushed as its own [ChatRole.TOOL] card row
     * between the surrounding text. A tool part in any state (running / completed / error) yields a card —
     * an in-flight or malformed part degrades to a bare preview card, it never throws. Text is folded
     * whole: a long reply replays intact ([ReplayBudget] bounds the frame total), matching Claude/Codex.
     */
    private fun messageRows(conn: Connection, sessionId: String, messageId: String, role: ChatRole): List<HistoryMessage> {
        val partStmt = conn.prepareStatement(
            "SELECT data FROM part WHERE session_id = ? AND message_id = ? ORDER BY time_created ASC",
        )
        partStmt.setString(1, sessionId)
        partStmt.setString(2, messageId)
        val partRs = partStmt.executeQuery()

        val rows = mutableListOf<HistoryMessage>()
        val textRun = StringBuilder()
        fun flushText() {
            if (textRun.isNotBlank()) rows.add(HistoryMessage(role, textRun.toString()))
            textRun.setLength(0)
        }
        while (partRs.next()) {
            val dataStr = partRs.getString("data") ?: continue
            val partData = runCatching { json.parseToJsonElement(dataStr) }.getOrNull() as? JsonObject ?: continue
            when (partData["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> partData["text"]?.jsonPrimitive?.contentOrNull?.let {
                    if (textRun.isNotEmpty()) textRun.append('\n')
                    textRun.append(it)
                }
                "tool" -> {
                    flushText() // preserve "text · card · text" ordering
                    rows.add(toolRow(partData))
                }
            }
        }
        flushText()
        return rows
    }

    /** One persisted `tool` part → a TOOL card row shaped like the live card: the name runs through
     *  [ToolNameMapper], the label is the collapsed [toolPreview], and — only once the call has SETTLED
     *  (`state.status == "completed"`) — its outcome mirrors [OpenCodeStreamParser]'s rule: an error is a
     *  completed call that produced NO output and had a non-zero (or absent) exit code; `ok` is its negation.
     *  An unsettled part (running / still-erroring / just-started) carries no verdict and no partial output,
     *  so a card never replays a stale outcome. */
    private fun toolRow(partData: JsonObject): HistoryMessage {
        val tool = ToolNameMapper.map(partData["tool"]?.jsonPrimitive?.contentOrNull ?: "tool")
        val state = partData["state"] as? JsonObject
        val output = state?.get("output")?.jsonPrimitive?.contentOrNull
        val completed = state?.get("status")?.jsonPrimitive?.contentOrNull == "completed"
        val ok: Boolean? = if (completed) {
            val exit = state?.get("metadata")?.jsonObject?.get("exit")?.jsonPrimitive?.longOrNull
            !(output == null && exit != 0L)
        } else null
        return HistoryMessage(
            role = ChatRole.TOOL,
            text = toolPreview(partData),
            tool = tool,
            ok = ok,
            output = if (completed) output?.take(MAX_TOOL_OUTPUT) else null,
        )
    }

    /** The tool card's input preview: the tool's `state.input` object (bash → `{"command":…}`, read →
     *  `{"file_path":…}` …) serialized and capped — the same compact preview the live card and the
     *  Claude/Codex replays show. Empty when a part carries no input yet (e.g. a just-started call). */
    private fun toolPreview(partData: JsonObject): String {
        val state = partData["state"] as? JsonObject ?: return ""
        val input = state["input"] as? JsonObject ?: return ""
        return input.toString().take(MAX_TOOL_TEXT)
    }
}
