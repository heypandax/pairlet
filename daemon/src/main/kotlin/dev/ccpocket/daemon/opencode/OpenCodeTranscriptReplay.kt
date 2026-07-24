package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
 * Claude/Codex replays fill a tool card's preview.
 */
object OpenCodeTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Per-text-row char cap applied before ReplayBudget's frame-level limit (4000 chars ≈ 8KB in
    // multi-byte UTF-8). Keeps a single huge assistant text part from dominating the history frame.
    private const val MAX_TEXT_PER_MESSAGE = 4000

    // Tool input preview cap — the compact "command"/input line shown on the card (display-on-tap, not a
    // reply body). Mirrors the Claude replay's MAX_TOOL_TEXT so all three backends cap tool previews alike.
    private const val MAX_TOOL_TEXT = 1000

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
     * only its `state.input` preview is read, never its output, so an in-flight or failed call never throws.
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
            if (textRun.isNotBlank()) rows.add(HistoryMessage(role, textRun.toString().take(MAX_TEXT_PER_MESSAGE)))
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
                    val tool = ToolNameMapper.map(partData["tool"]?.jsonPrimitive?.contentOrNull ?: "tool")
                    rows.add(HistoryMessage(ChatRole.TOOL, toolPreview(partData), tool = tool))
                }
            }
        }
        flushText()
        return rows
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
