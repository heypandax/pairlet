package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection

/** Read-only view of the official 3.7.6 `~/.zcode/cli/db/db.sqlite` session store. */
object ZCodeTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val LIVE_WINDOW_MS = 20_000L

    fun scan(workdir: String, conn: Connection? = ZCodePaths.connectReadOnly()): List<SessionSummary> = runCatching {
        val c = conn ?: return emptyList()
        c.use { scanFrom(it, workdir) }
    }.getOrDefault(emptyList())

    internal fun scanFrom(conn: Connection, workdir: String): List<SessionSummary> {
        val target = ProjectPaths.canonicalKey(workdir)
        val out = mutableListOf<SessionSummary>()
        conn.prepareStatement(
            "SELECT s.id,s.directory,s.title,s.version,s.time_updated,s.parent_id,s.task_type," +
                "COUNT(m.id) msg_count FROM session s LEFT JOIN message m ON m.session_id=s.id " +
                "WHERE s.time_archived IS NULL GROUP BY s.id ORDER BY s.time_updated DESC LIMIT 200",
        ).executeQuery().use { rs ->
            while (rs.next()) {
                if (!rs.getString("parent_id").isNullOrBlank() || rs.getString("task_type") != "interactive") continue
                val cwd = rs.getString("directory") ?: continue
                if (ProjectPaths.canonicalKey(cwd) != target) continue
                val sid = rs.getString("id") ?: continue
                val updated = rs.getLong("time_updated")
                out += SessionSummary(
                    sessionId = sid, title = rs.getString("title")?.ifBlank { sid } ?: sid,
                    firstPrompt = firstPrompt(conn, sid), messageCount = rs.getInt("msg_count"), cwd = cwd,
                    lastModified = updated, version = rs.getString("version"),
                    live = System.currentTimeMillis() - updated < LIVE_WINDOW_MS, agent = AgentKind.ZCODE,
                    model = latestModel(conn, sid),
                )
            }
        }
        return out
    }

    fun cwdsByNewest(conn: Connection? = ZCodePaths.connectReadOnly()): Map<String, Long> = runCatching {
        val c = conn ?: return emptyMap()
        c.use {
            val out = hashMapOf<String, Long>()
            it.prepareStatement("SELECT directory,time_updated,parent_id,task_type FROM session WHERE time_archived IS NULL")
                .executeQuery().use { rs -> while (rs.next()) {
                    if (!rs.getString("parent_id").isNullOrBlank() || rs.getString("task_type") != "interactive") continue
                    val cwd = rs.getString("directory")?.takeIf(String::isNotBlank) ?: continue
                    out.merge(cwd, rs.getLong("time_updated"), ::maxOf)
                } }
            out
        }
    }.getOrDefault(emptyMap())

    fun resumeModel(sessionId: String): String? = runCatching {
        ZCodePaths.connectReadOnly()?.use { latestModel(it, sessionId) }
    }.getOrNull()

    private fun firstPrompt(conn: Connection, sid: String): String = messageParts(conn, sid, "user")
        .firstOrNull().orEmpty().take(200)

    private fun latestModel(conn: Connection, sid: String): String? {
        conn.prepareStatement("SELECT data FROM message WHERE session_id=? ORDER BY sequence DESC,time_created DESC LIMIT 50").use { st ->
            st.setString(1, sid)
            st.executeQuery().use { rs -> while (rs.next()) {
                val o = parse(rs.getString(1)) ?: continue
                val provider = o.str("providerID") ?: (o["model"] as? JsonObject)?.str("providerID")
                val model = o.str("modelID") ?: (o["model"] as? JsonObject)?.str("modelID")
                if (!model.isNullOrBlank()) return if (provider.isNullOrBlank() || '/' in model) model else "$provider/$model"
            } }
        }
        return null
    }

    internal fun messageParts(conn: Connection, sid: String, role: String): List<String> {
        val out = mutableListOf<String>()
        conn.prepareStatement("SELECT m.id,m.data FROM message m WHERE m.session_id=? ORDER BY m.sequence,m.time_created").use { ms ->
            ms.setString(1, sid)
            ms.executeQuery().use { mr -> while (mr.next()) {
                if (parse(mr.getString("data"))?.str("role") != role) continue
                conn.prepareStatement("SELECT data FROM part WHERE session_id=? AND message_id=? ORDER BY sequence,time_created").use { ps ->
                    ps.setString(1, sid); ps.setString(2, mr.getString("id"))
                    ps.executeQuery().use { pr -> while (pr.next()) {
                        val p = parse(pr.getString(1)) ?: continue
                        if (p.str("type") == "text") p.str("text")?.let(out::add)
                    } }
                }
            } }
        }
        return out
    }

    private fun parse(raw: String?): JsonObject? = runCatching { json.parseToJsonElement(raw ?: "") as? JsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
