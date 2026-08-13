package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.opencode.ToolNameMapper
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection

/** Replays official ZCode 3.7.6 SQLite message/part rows; never writes the desktop store. */
object ZCodeTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun read(sessionId: String, maxMessages: Int = 100): List<HistoryMessage> = runCatching {
        ZCodePaths.connectReadOnly()?.use { readFrom(it, sessionId, maxMessages) }.orEmpty()
    }.getOrDefault(emptyList())

    internal fun readFrom(conn: Connection, sid: String, maxMessages: Int = 100): List<HistoryMessage> {
        val out = mutableListOf<HistoryMessage>()
        conn.prepareStatement("SELECT id,data FROM message WHERE session_id=? ORDER BY sequence,time_created,id").use { ms ->
            ms.setString(1, sid)
            ms.executeQuery().use { mr -> while (mr.next()) {
                val msg = parse(mr.getString("data")) ?: continue
                val role = when (msg.str("role")) { "user" -> ChatRole.USER; "assistant" -> ChatRole.ASSISTANT; else -> continue }
                val text = StringBuilder()
                fun flush() { if (text.isNotBlank()) out += HistoryMessage(role, text.toString()); text.setLength(0) }
                conn.prepareStatement("SELECT data FROM part WHERE session_id=? AND message_id=? ORDER BY sequence,time_created,id").use { ps ->
                    ps.setString(1, sid); ps.setString(2, mr.getString("id"))
                    ps.executeQuery().use { pr -> while (pr.next()) {
                        val p = parse(pr.getString(1)) ?: continue
                        when (p.str("type")) {
                            "text" -> p.str("text")?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
                            "tool" -> { flush(); out += toolRow(p) }
                        }
                    } }
                }
                flush()
            } }
        }
        return ReplayBudget.fit(out.takeLast(maxMessages), ReplayBudget.MAX_FRAME_TEXT_BYTES)
    }

    private fun toolRow(p: JsonObject): HistoryMessage {
        val state = p["state"] as? JsonObject
        val status = state?.str("status")
        val error = state?.get("error")
        val output = state?.get("output")
        return HistoryMessage(
            ChatRole.TOOL,
            state?.get("input")?.toString()?.take(1000).orEmpty(),
            tool = ToolNameMapper.map(p.str("tool") ?: p.str("toolName") ?: "tool"),
            ok = when (status) { "completed" -> true; "error" -> false; else -> null },
            output = when { error != null -> scalar(error); output != null -> scalar(output); else -> null }?.take(4000),
        )
    }

    private fun scalar(e: kotlinx.serialization.json.JsonElement): String =
        (e as? JsonPrimitive)?.contentOrNull ?: e.toString()
    private fun parse(raw: String?): JsonObject? = runCatching { json.parseToJsonElement(raw ?: "") as? JsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
