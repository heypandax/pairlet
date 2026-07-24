package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection

/**
 * Reads OpenCode sessions from the SQLite database (~/.local/share/opencode/opencode.db)
 * into [SessionSummary] for the phone's session list. Filters by the recorded directory.
 */
object OpenCodeTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L
    private const val PREVIEW_CAP = 200 // 副标题预览截断，够一行显示且不给列表帧塞满

    /** All OpenCode sessions whose directory matches [workdir], newest-first. */
    fun scan(workdir: String): List<SessionSummary> {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return emptyList()
            conn.use { scanConn(it, workdir) }
        }.getOrElse { emptyList() }
    }

    /**
     * 用 [conn] 查 [workdir] 下的会话；拆出来让测试能喂 fixture db。
     *
     * OpenCode 的 task 工具 spawn 的子 agent 是同一 session 表里的 child 行（父列指向发起会话），顶层
     * 列表不该显示它们（issue #172）。父列拼写在不同 OpenCode 版本间不一（parentID / parent_id），故
     * 运行时探测：探到就加「父列 IS NULL」，探不到就不过滤——绝不误伤正常会话（fail-open）。
     */
    internal fun scanConn(conn: Connection, workdir: String): List<SessionSummary> {
        val parentCol = sessionParentColumn(conn) // 按连接探一次即可
        val parentFilter = parentCol?.let { "AND s.\"$it\" IS NULL " } ?: ""
        val stmt = conn.prepareStatement(
            "SELECT s.id, s.title, s.directory, s.model, s.cost, " +
            "s.tokens_input, s.tokens_output, s.time_created, s.time_updated, " +
            "COUNT(m.id) AS msg_count " +
            "FROM session s LEFT JOIN message m ON m.session_id = s.id " +
            "WHERE s.time_archived IS NULL " + parentFilter +
            "GROUP BY s.id ORDER BY s.time_updated DESC LIMIT 200"
        )
        val rs = stmt.executeQuery()
        val staged = mutableListOf<SessionSummary>()
        while (rs.next()) {
            val sid = rs.getString("id") ?: continue
            val directory = rs.getString("directory") ?: ""
            if (workdir.isNotBlank() && directory.isNotBlank()) {
                if (ProjectPaths.normCwd(directory) != ProjectPaths.normCwd(workdir)) continue
            }
            val title = rs.getString("title") ?: sid
            val timeUpdated = rs.getLong("time_updated")
            staged.add(SessionSummary(
                sessionId = sid,
                title = title.takeIf { it.isNotBlank() } ?: sid,
                firstPrompt = "",
                messageCount = rs.getInt("msg_count"),
                cwd = directory,
                lastModified = timeUpdated,
                version = null,
                live = System.currentTimeMillis() - timeUpdated < LIVE_WINDOW_MS,
                agent = AgentKind.OPENCODE,
                model = parseModel(rs.getString("model")),
            ))
        }
        rs.close()
        // 命中 workdir 的会话收集完再补首条用户消息作副标题——只对留下的会话跑，不给 200 行都做子查询
        return staged.map { it.copy(firstPrompt = firstUserPrompt(conn, it.sessionId)) }
    }

    /** session 表的父列名（parentID / parent_id），两者都没有则返回 null（不过滤子会话）。 */
    private fun sessionParentColumn(conn: Connection): String? {
        val cols = HashSet<String>()
        conn.prepareStatement("PRAGMA table_info(session)").executeQuery().use { rs ->
            while (rs.next()) rs.getString("name")?.let { cols.add(it) }
        }
        return when {
            "parentID" in cols -> "parentID"
            "parent_id" in cols -> "parent_id"
            else -> null
        }
    }

    /** 该会话第一条用户消息的文本预览（issue #173）；取不到返回空串（副标题行 isNotBlank 守卫自然隐藏）。 */
    private fun firstUserPrompt(conn: Connection, sessionId: String): String {
        return runCatching {
            val stmt = conn.prepareStatement(
                "SELECT id, data FROM message WHERE session_id = ? ORDER BY time_created ASC LIMIT 20"
            )
            stmt.setString(1, sessionId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val data = rs.getString("data") ?: continue
                    val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: continue
                    if (obj["role"]?.jsonPrimitive?.contentOrNull != "user") continue
                    val text = firstTextPart(conn, sessionId, rs.getString("id"))
                    if (text.isNotBlank()) return@runCatching text.trim().take(PREVIEW_CAP)
                }
            }
            ""
        }.getOrDefault("")
    }

    /** 一条消息里第一个 text part 的文本（issue #173）。 */
    private fun firstTextPart(conn: Connection, sessionId: String, messageId: String?): String {
        if (messageId == null) return ""
        val stmt = conn.prepareStatement(
            "SELECT data FROM part WHERE session_id = ? AND message_id = ? ORDER BY time_created ASC"
        )
        stmt.setString(1, sessionId)
        stmt.setString(2, messageId)
        stmt.executeQuery().use { rs ->
            while (rs.next()) {
                val data = rs.getString("data") ?: continue
                val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: continue
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    val t = obj["text"]?.jsonPrimitive?.contentOrNull
                    if (!t.isNullOrBlank()) return t
                }
            }
        }
        return ""
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
