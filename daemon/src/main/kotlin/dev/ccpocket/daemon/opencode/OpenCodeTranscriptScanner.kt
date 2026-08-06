package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection

/**
 * Reads OpenCode sessions from the SQLite database (~/.local/share/opencode/opencode.db)
 * into [SessionSummary] for the phone's session list. Filters by the recorded directory.
 */
object OpenCodeTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L
    private const val PREVIEW_CAP = 200 // 副标题预览截断，够一行显示且不给列表帧塞满

    /**
     * True when this OpenCode session is a task-tool sub-agent run rather than a resumable top-level
     * conversation (issue #172). OpenCode's `task` tool spawns children via `Session.create({ parentID })`,
     * so the row's `parent_id` column points at the enclosing session (OpenCode even indexes it as
     * `session_parent_idx`). Identity is taken from that column ONLY — never from title text, which is
     * brittle and language-dependent. A null/blank parent = top-level, kept in the list.
     */
    internal fun isSubAgentSession(parentId: String?): Boolean = !parentId.isNullOrBlank()

    /** All OpenCode top-level sessions whose directory matches [workdir], newest-first.
     *  Task sub-agent runs (parent_id set) are excluded — see [isSubAgentSession].
     *  [conn] is a test seam; production uses the default. */
    fun scan(workdir: String, conn: Connection? = OpenCodePaths.connectReadOnly()): List<SessionSummary> {
        return runCatching {
            val c = conn ?: return emptyList()
            c.use { scanConn(it, workdir) }
        }.getOrElse { emptyList() }
    }

    /**
     * 用 [conn] 查 [workdir] 下的会话；拆出来让测试能喂 fixture db。
     *
     * OpenCode 的 task 工具 spawn 的子 agent 是同一 session 表里的 child 行——`parent_id` 列指向发起
     * 会话（sst/opencode `session/sql.ts` 的既有列，还带 `session_parent_idx` 索引），顶层列表不该
     * 显示它们（issue #172）。判定走 [isSubAgentSession] 单一事实源，绝不用标题文本启发式。
     *
     * 目录匹配用 [ProjectPaths.canonicalKey]（issue #184）：opencode 记的是自己拼写的目录（波浪号/
     * 尾分隔符/符号链接变体），合并后的项目行发来的是 realpath 过的 workdir——必须用目录列表合并行
     * 所用的同一把键比对，弱字符串比对会「行并上了、会话丢了」。
     */
    internal fun scanConn(conn: Connection, workdir: String): List<SessionSummary> {
        val target = workdir.takeIf { it.isNotBlank() }?.let(ProjectPaths::canonicalKey)
        val stmt = conn.prepareStatement(
            "SELECT s.id, s.parent_id, s.title, s.directory, s.model, s.cost, " +
            "s.tokens_input, s.tokens_output, s.time_created, s.time_updated, " +
            "COUNT(m.id) AS msg_count " +
            "FROM session s LEFT JOIN message m ON m.session_id = s.id " +
            "WHERE s.time_archived IS NULL " +
            "GROUP BY s.id ORDER BY s.time_updated DESC LIMIT 200"
        )
        val rs = stmt.executeQuery()
        val staged = mutableListOf<SessionSummary>()
        while (rs.next()) {
            val sid = rs.getString("id") ?: continue
            if (isSubAgentSession(rs.getString("parent_id"))) continue
            val directory = rs.getString("directory") ?: ""
            if (target != null && directory.isNotBlank()) {
                if (ProjectPaths.canonicalKey(directory) != target) continue
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

    /** Every directory with a TOP-LEVEL OpenCode session → its newest session mtime.
     *  Rows are read un-grouped so the same [isSubAgentSession] rule as [scan] drops task sub-agent
     *  runs (issue #172) before aggregating — a directory that only ever hosted a sub-run must not
     *  masquerade as its own project, nor bump a real project's newest mtime. */
    fun cwdsByNewest(): Map<String, Long> {
        return runCatching {
            val conn = OpenCodePaths.connectReadOnly() ?: return emptyMap()
            conn.use {
                val stmt = it.prepareStatement(
                    "SELECT directory, parent_id, time_updated FROM session WHERE time_archived IS NULL AND directory IS NOT NULL"
                )
                val rs = stmt.executeQuery()
                val out = HashMap<String, Long>()
                while (rs.next()) {
                    if (isSubAgentSession(rs.getString("parent_id"))) continue
                    val dir = rs.getString("directory") ?: continue
                    if (dir.isBlank()) continue
                    val mtime = rs.getLong("time_updated")
                    val prev = out[dir]
                    if (prev == null || mtime > prev) out[dir] = mtime
                }
                out
            }
        }.getOrDefault(emptyMap())
    }

    /** One assistant turn's token spend, pulled from the OpenCode DB for usage aggregation (issue #217).
     *  [whenEpochMs] is the turn's local wall-clock moment; [model] is "provider/model" (or the session
     *  fallback); tokens follow OpenCode's own split (cache read is separate from input). */
    data class UsageTurn(
        val id: String,
        val whenEpochMs: Long,
        val model: String,
        val input: Long,
        val output: Long,
        val cacheRead: Long,
    )

    /**
     * Every assistant turn (all sessions, INCLUDING task sub-agent runs — their tokens are real spend)
     * whose completion time is at or after [sinceEpochMs], for [UsageService] to bucket by day/model
     * (issue #217). Tokens live on the assistant MESSAGE's serialized `data` (`tokens.input/output`,
     * `tokens.cache.read`) — the same shape [OpenCodeStreamParser] reads live from step_finish. Model
     * is taken from the message's own `providerID`/`modelID`, falling back to the session's model.
     * Read-only + busy-tolerant like every other scan; any failure degrades to an empty list.
     */
    fun usageTurns(sinceEpochMs: Long, conn: Connection? = OpenCodePaths.connectReadOnly()): List<UsageTurn> {
        return runCatching {
            val c = conn ?: return emptyList()
            c.use { usageTurnsConn(it, sinceEpochMs) }
        }.getOrElse { emptyList() }
    }

    internal fun usageTurnsConn(conn: Connection, sinceEpochMs: Long): List<UsageTurn> {
        val out = mutableListOf<UsageTurn>()
        val stmt = conn.prepareStatement(
            "SELECT m.id AS mid, m.data AS data, m.time_created AS tc, s.model AS session_model " +
            "FROM message m JOIN session s ON s.id = m.session_id " +
            "WHERE s.time_archived IS NULL AND m.time_created >= ? LIMIT 50000"
        )
        stmt.setLong(1, sinceEpochMs)
        stmt.executeQuery().use { rs ->
            while (rs.next()) {
                val data = rs.getString("data") ?: continue
                val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: continue
                if (obj["role"]?.jsonPrimitive?.contentOrNull != "assistant") continue
                val tokens = obj["tokens"]?.jsonObject ?: continue
                val input = tokens.longAt("input")
                val output = tokens.longAt("output")
                val cacheRead = (tokens["cache"] as? JsonObject)?.longAt("read") ?: 0L
                if (input + output + cacheRead <= 0L) continue
                // prefer the turn's own completion time; fall back to the row's created column
                val completed = (obj["time"] as? JsonObject)?.longAt("completed")
                val whenMs = completed?.takeIf { it > 0L } ?: rs.getLong("tc")
                if (whenMs < sinceEpochMs) continue
                val model = assistantModel(obj) ?: parseModel(rs.getString("session_model")) ?: "opencode"
                out.add(UsageTurn(rs.getString("mid") ?: "", whenMs, model, input, output, cacheRead))
            }
        }
        return out
    }

    /** "provider/model" from an assistant message's own model fields, or null when unqualified. */
    private fun assistantModel(msg: JsonObject): String? {
        val provider = msg["providerID"]?.jsonPrimitive?.contentOrNull
            ?: msg["provider"]?.jsonPrimitive?.contentOrNull
        val id = msg["modelID"]?.jsonPrimitive?.contentOrNull
            ?: msg["model"]?.jsonPrimitive?.contentOrNull
        return when {
            id.isNullOrBlank() -> null
            "/" in id -> id
            provider.isNullOrBlank() -> id
            else -> "$provider/$id"
        }
    }

    private fun JsonObject.longAt(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

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
