package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads OpenCode sessions from the SQLite database (~/.local/share/opencode/opencode.db)
 * into [SessionSummary] for the phone's session list. Filters by the recorded directory.
 */
object OpenCodeTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L

    /** All OpenCode sessions whose directory matches [workdir], newest-first. */
    fun scan(workdir: String): List<SessionSummary> {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return emptyList()
            conn.use {
                val stmt = it.prepareStatement(
                    "SELECT s.id, s.title, s.directory, s.model, s.cost, " +
                    "s.tokens_input, s.tokens_output, s.time_created, s.time_updated, " +
                    "COUNT(m.id) AS msg_count " +
                    "FROM session s LEFT JOIN message m ON m.session_id = s.id " +
                    "WHERE s.time_archived IS NULL GROUP BY s.id ORDER BY s.time_updated DESC LIMIT 200"
                )
                val rs = stmt.executeQuery()
                val out = mutableListOf<SessionSummary>()
                while (rs.next()) {
                    val sid = rs.getString("id") ?: continue
                    val title = rs.getString("title") ?: sid
                    val directory = rs.getString("directory") ?: ""
                    val model = parseModel(rs.getString("model"))
                    val timeUpdated = rs.getLong("time_updated")
                    val msgCount = rs.getInt("msg_count")
                    if (workdir.isNotBlank() && directory.isNotBlank()) {
                        if (ProjectPaths.normCwd(directory) != ProjectPaths.normCwd(workdir)) continue
                    }
                    out.add(SessionSummary(
                        sessionId = sid,
                        title = title.takeIf { it.isNotBlank() } ?: sid,
                        firstPrompt = title,
                        messageCount = msgCount,
                        cwd = directory,
                        lastModified = timeUpdated,
                        version = null,
                        live = System.currentTimeMillis() - timeUpdated < LIVE_WINDOW_MS,
                        agent = AgentKind.OPENCODE,
                        model = model,
                    ))
                }
                out
            }
        }.getOrElse { emptyList() }
    }

    fun resumeModel(sessionId: String): String? {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return null
            conn.use {
                val stmt = it.prepareStatement("SELECT model FROM session WHERE id = ? LIMIT 1")
                stmt.setString(1, sessionId)
                val rs = stmt.executeQuery()
                if (rs.next()) parseModel(rs.getString("model")) else null
            }
        }.getOrNull()
    }

    /** Every directory with OpenCode sessions → its newest session mtime. */
    fun cwdsByNewest(): Map<String, Long> {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return emptyMap()
            conn.use {
                val stmt = it.prepareStatement(
                    "SELECT directory, MAX(time_updated) as mtime FROM session WHERE time_archived IS NULL AND directory IS NOT NULL GROUP BY directory"
                )
                val rs = stmt.executeQuery()
                val out = HashMap<String, Long>()
                while (rs.next()) {
                    val dir = rs.getString("directory") ?: continue
                    val mtime = rs.getLong("mtime")
                    if (dir.isNotBlank()) out[dir] = mtime
                }
                out
            }
        }.getOrDefault(emptyMap())
    }

    internal fun parseModel(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!text.startsWith("{")) return text.takeIf { "/" in it }
        return runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            val provider = obj["providerID"]?.jsonPrimitive?.contentOrNull
                ?: obj["provider"]?.jsonPrimitive?.contentOrNull
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["modelID"]?.jsonPrimitive?.contentOrNull
                ?: obj["model"]?.jsonPrimitive?.contentOrNull
            when {
                provider.isNullOrBlank() || id.isNullOrBlank() -> null
                "/" in id -> id
                else -> "$provider/$id"
            }
        }.getOrNull()
    }
}
