package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Reconstructs [HistoryMessage]s from an OpenCode session in the SQLite database.
 * Reads message/part tables and flattens them for replay to the phone.
 */
object OpenCodeTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Per-message char cap applied before ReplayBudget's frame-level limit (4000 chars ≈ 8KB in
    // multi-byte UTF-8). Keeps a single large tool output from dominating the history frame.
    private const val MAX_TEXT_PER_MESSAGE = 4000

    fun read(sessionId: String, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return emptyList()
            conn.use {
                // Read messages for this session
                val msgStmt = it.prepareStatement(
                    "SELECT id, data, time_created FROM message WHERE session_id = ? ORDER BY time_created ASC"
                )
                msgStmt.setString(1, sessionId)
                val msgRs = msgStmt.executeQuery()

                val out = mutableListOf<HistoryMessage>()
                while (msgRs.next()) {
                    val dataStr = msgRs.getString("data") ?: continue
                    val msgData = runCatching { json.parseToJsonElement(dataStr) }.getOrNull() as? JsonObject ?: continue
                    val role = msgData["role"]?.jsonPrimitive?.contentOrNull ?: continue

                    when (role) {
                        "user" -> {
                            // Get text from parts
                            val text = getTextParts(it, sessionId, msgRs.getString("id"))
                            if (text.isNotBlank()) out.add(HistoryMessage(ChatRole.USER, text))
                        }
                        "assistant" -> {
                            val text = getTextParts(it, sessionId, msgRs.getString("id"))
                            if (text.isNotBlank()) out.add(HistoryMessage(ChatRole.ASSISTANT, text))
                        }
                    }
                }
                val capped = if (out.size > maxMessages) out.takeLast(maxMessages) else out
                ReplayBudget.fit(capped, maxFrameTextBytes)
            }
        }.getOrDefault(emptyList())
    }

    private fun getTextParts(conn: java.sql.Connection, sessionId: String, messageId: String): String {
        val partStmt = conn.prepareStatement(
            "SELECT data FROM part WHERE session_id = ? AND message_id = ? ORDER BY time_created ASC"
        )
        partStmt.setString(1, sessionId)
        partStmt.setString(2, messageId)
        val partRs = partStmt.executeQuery()
        val parts = mutableListOf<String>()
        while (partRs.next()) {
            val dataStr = partRs.getString("data") ?: continue
            val partData = runCatching { json.parseToJsonElement(dataStr) }.getOrNull() as? JsonObject ?: continue
            val type = partData["type"]?.jsonPrimitive?.contentOrNull ?: continue
            when (type) {
                "text" -> partData["text"]?.jsonPrimitive?.contentOrNull?.let { parts.add(it) }
                "tool" -> {
                    val tool = partData["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val state = partData["state"]?.jsonObject
                    val output = state?.get("output")?.jsonPrimitive?.contentOrNull
                    if (output != null) parts.add("[$tool] $output")
                }
            }
        }
        return parts.joinToString("\n").take(MAX_TEXT_PER_MESSAGE) // cap per-message
    }
}
