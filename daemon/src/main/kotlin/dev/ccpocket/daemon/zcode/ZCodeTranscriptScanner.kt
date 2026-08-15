package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptNoise
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

    /** Text parts of [role]'s messages, oldest first. User rows are screened through the shared noise
     *  judgement so a harness injection (system-reminder / task-notification) never becomes the list
     *  preview — the same filter the replay applies (issue #253). */
    internal fun messageParts(conn: Connection, sid: String, role: String): List<String> {
        val out = mutableListOf<String>()
        val user = role == "user"
        conn.prepareStatement("SELECT m.id,m.data FROM message m WHERE m.session_id=? ORDER BY m.sequence,m.time_created").use { ms ->
            ms.setString(1, sid)
            ms.executeQuery().use { mr -> while (mr.next()) {
                if (parse(mr.getString("data"))?.str("role") != role) continue
                conn.prepareStatement("SELECT data FROM part WHERE session_id=? AND message_id=? ORDER BY sequence,time_created").use { ps ->
                    ps.setString(1, sid); ps.setString(2, mr.getString("id"))
                    ps.executeQuery().use { pr -> while (pr.next()) {
                        val p = parse(pr.getString(1)) ?: continue
                        if (p.str("type") == "text") p.str("text")
                            ?.takeUnless { user && TranscriptNoise.isNoiseUserText(it) }
                            ?.let(out::add)
                    } }
                }
            } }
        }
        return out
    }

    /** One ZCode model request's token spend, for usage aggregation (issue #258). [whenEpochMs] is the
     *  request's wall-clock moment; [model] is "provider/model"; the four token columns are DISJOINT
     *  (ZCode's own `computed_total_tokens` is exactly their sum), so cache read stays split out of input
     *  like Claude/OpenCode and the shared cache-hit formula holds. */
    data class UsageTurn(
        val id: String,
        val whenEpochMs: Long,
        val model: String,
        val input: Long,
        val output: Long,
        val cacheCreation: Long,
        val cacheRead: Long,
    )

    /**
     * Every ZCode model request at or after [sinceEpochMs], for [dev.ccpocket.daemon.disk.UsageService]
     * to bucket by day/model (issue #258).
     *
     * Source is `model_usage`, NOT `turn_usage`: only `model_usage` carries the model identity
     * (`provider_id`/`model_id`) and it covers EVERY request — probe-verified locally, a turn's
     * `session_title` side request lands in `model_usage` but is excluded from that turn's `turn_usage`
     * rollup, so `turn_usage` would under-count real spend. Rows are keyed by `model_usage.id` (its
     * primary key) for dedup; `error`/`cancelled` rows carry all-zero tokens and drop out on the
     * total > 0 guard rather than on a status filter (a cancelled turn that already burned tokens
     * should still count). Read-only + busy-tolerant like every other scan; any failure → empty list.
     */
    fun usageTurns(sinceEpochMs: Long, conn: Connection? = ZCodePaths.connectReadOnly()): List<UsageTurn> = runCatching {
        val c = conn ?: return emptyList()
        c.use { usageTurnsFrom(it, sinceEpochMs) }
    }.getOrDefault(emptyList())

    internal fun usageTurnsFrom(conn: Connection, sinceEpochMs: Long): List<UsageTurn> {
        val out = mutableListOf<UsageTurn>()
        conn.prepareStatement(
            "SELECT id,provider_id,model_id,started_at,completed_at,input_tokens,output_tokens," +
                "cache_creation_input_tokens,cache_read_input_tokens FROM model_usage " +
                "WHERE COALESCE(completed_at,started_at) >= ? LIMIT 50000",
        ).use { st ->
            st.setLong(1, sinceEpochMs)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val input = rs.getLong("input_tokens")
                    val output = rs.getLong("output_tokens")
                    val cacheCreation = rs.getLong("cache_creation_input_tokens")
                    val cacheRead = rs.getLong("cache_read_input_tokens")
                    if (input + output + cacheCreation + cacheRead <= 0L) continue
                    // prefer the request's completion moment; a still-running row only has started_at
                    val whenMs = rs.getLong("completed_at").takeIf { it > 0L } ?: rs.getLong("started_at")
                    if (whenMs < sinceEpochMs) continue
                    out += UsageTurn(
                        id = rs.getString("id") ?: "",
                        whenEpochMs = whenMs,
                        model = qualifiedModel(rs.getString("provider_id"), rs.getString("model_id")),
                        input = input, output = output, cacheCreation = cacheCreation, cacheRead = cacheRead,
                    )
                }
            }
        }
        return out
    }

    /** "provider/model" from the usage row's own columns, matching the session rows' model spelling. */
    private fun qualifiedModel(provider: String?, model: String?): String = when {
        model.isNullOrBlank() -> "zcode"
        '/' in model || provider.isNullOrBlank() -> model
        else -> "$provider/$model"
    }

    private fun parse(raw: String?): JsonObject? = runCatching { json.parseToJsonElement(raw ?: "") as? JsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
