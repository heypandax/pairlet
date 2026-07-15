package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Reads OpenCode sessions from the SQLite database (~/.local/share/opencode/opencode.db)
 * into [SessionSummary] for the phone's session list. Filters by the recorded directory.
 */
object OpenCodeTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L

    /** All OpenCode sessions whose directory matches [workdir], newest-first. */
    fun scan(workdir: String): List<SessionSummary> {
        val dbPath = OpenCodePaths.database()
        if (!dbPath.toFile().exists()) return emptyList()
        return runCatching {
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toFile().absolutePath}")
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
                    val model = rs.getString("model")
                    val timeUpdated = rs.getLong("time_updated")
                    val timeCreated = rs.getLong("time_created")
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
                    ))
                }
                out
            }
        }.getOrElse { emptyList() }
    }

    /** Every directory with OpenCode sessions → its newest session mtime. */
    fun cwdsByNewest(): Map<String, Long> {
        val dbPath = OpenCodePaths.database()
        if (!dbPath.toFile().exists()) return emptyMap()
        return runCatching {
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toFile().absolutePath}")
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
}
