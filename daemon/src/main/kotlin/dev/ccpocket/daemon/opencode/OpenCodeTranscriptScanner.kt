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
                    "SELECT id, title, directory, model, cost, tokens_input, tokens_output, time_created, time_updated " +
                    "FROM session WHERE time_archived IS NULL ORDER BY time_updated DESC LIMIT 200"
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
                    if (workdir.isNotBlank() && directory.isNotBlank()) {
                        if (ProjectPaths.normCwd(directory) != ProjectPaths.normCwd(workdir)) continue
                    }
                    out.add(SessionSummary(
                        sessionId = sid,
                        title = title.takeIf { it.isNotBlank() } ?: sid,
                        firstPrompt = title,
                        messageCount = 0, // not easily countable without N+1 query
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
