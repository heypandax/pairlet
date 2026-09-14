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

    /**
     * The occupancy a REOPENED session shows before its first new turn (issue #320) — the last completed
     * request's context footprint, never a session spend sum.
     *
     * Source is `model_usage` rather than the assistant message rows. Both carry the number and they agree
     * term for term (probed on the local 3.7.6 store), but only this table can say what KIND of request it
     * was: `query_source` separates the conversation's own `main_turn` from the `session_title` side
     * request ZCode fires against a smaller model. Reading the message rows means inferring that
     * distinction; reading this column means being told it.
     *
     * ⚠️ The arithmetic is `input + output` and deliberately NOT the four columns summed. ZCode is
     * OpenAI-lineage: `cache_read_input_tokens` is a SUBSET of `input_tokens`, and the store's own
     * `computed_total_tokens` proves it on real rows (`17531 + 13 + 7680` would be 25224, ZCode wrote
     * 17544). Adding the cached prefix back would overstate a warm session by up to ~45%. Contrast
     * [dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.resumeContextTokensFrom], whose store IS
     * disjoint and therefore DOES add all four — the convention is per-backend and never portable.
     *
     * Status is not the gate: a cancelled turn that already burned tokens really did fill the window. A
     * measurement of zero is the gate, because that is what an errored request writes, and it is an absence
     * of measurement rather than an empty window. Null ⇒ the phone shows no readout, which beats a
     * confident 0%.
     */
    fun resumeContextTokens(sessionId: String, conn: Connection? = ZCodePaths.connectReadOnly()): Long? =
        runCatching { conn?.use { resumeContextTokensFrom(it, sessionId) } }.getOrNull()

    internal fun resumeContextTokensFrom(conn: Connection, sessionId: String): Long? {
        conn.prepareStatement(
            // The newest MEASURED main turn. Unmeasured rows are skipped rather than allowed to win and
            // return null: a request still in flight, and an errored one, both write all zeros, and
            // neither is evidence that the window the previous turn filled has since emptied.
            "SELECT input_tokens,output_tokens FROM model_usage WHERE session_id=? AND query_source='main_turn' " +
                "AND input_tokens + output_tokens > 0 ORDER BY COALESCE(completed_at,started_at) DESC LIMIT 1",
        ).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                val input = rs.getLong("input_tokens")
                val output = rs.getLong("output_tokens")
                if (input < 0L || output < 0L) return null
                return (input + output).takeIf { it > 0L }
            }
        }
    }

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

    /** Text parts of [role]'s messages, oldest first. ZCode's own visibility semantics remove compact
     *  summaries/model-only rows first (#313); the shared noise judgement then catches legacy harness
     *  injections so neither can become the list preview (#253). The replay applies the same two gates. */
    internal fun messageParts(conn: Connection, sid: String, role: String): List<String> {
        val out = mutableListOf<String>()
        val user = role == "user"
        conn.prepareStatement("SELECT m.id,m.data FROM message m WHERE m.session_id=? ORDER BY m.sequence,m.time_created").use { ms ->
            ms.setString(1, sid)
            ms.executeQuery().use { mr -> while (mr.next()) {
                val message = parse(mr.getString("data")) ?: continue
                if (message.str("role") != role) continue
                if (user && !ZCodeTranscriptProjection.isVisibleUserRow(message)) continue
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
     *  request's wall-clock moment; [model] is "provider/model"; the four token columns are DISJOINT, so
     *  the day/model total is their sum and the shared cache-hit formula holds. Reaching that shape takes
     *  a NORMALIZATION on this backend — see [usageTurnsFrom]; the stored columns are not disjoint. */
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
     *
     * ## ⚠️ The cache columns are SUBSETS of `input_tokens` and must be subtracted out
     *
     * ZCode is OpenAI-lineage, so `input_tokens` is the WHOLE prompt — cached prefix included — where
     * [UsageTurn]'s four columns are disjoint. Passing the stored values through therefore billed the
     * cached prefix twice. The store's own `computed_total_tokens` is the oracle and it equals
     * `input + output` on every one of the 29 local rows, never `input + output + cache`:
     * ```
     * input 17531, output  13, cacheRead  7680 → computed_total 17544   (raw sum would be 25224, +44%)
     * input 44125, output 293, cacheRead 43008 → computed_total 44418   (raw sum would be 87426, +97%)
     * ```
     * Subtracting restores the invariant `input + output + cacheCreation + cacheRead == computed_total`
     * BY CONSTRUCTION, and keeps the split intact so the usage page can still show a cache-hit rate. This
     * is the same normalization [dev.ccpocket.daemon.dsh.DshUsageScanner] documents for DeepSeek and the
     * Codex path applies for its own store; Claude and OpenCode need none, their inputs are already net.
     *
     * ⚠️ `cache_creation_input_tokens` is subtracted on the SAME PRIOR, not on evidence: every local row
     * has it at 0, so its containment could not be observed. Subtracting is what keeps the sum equal to
     * ZCode's own total, which is the invariant worth preserving. If a store is ever seen where
     * `computed_total_tokens == input + output + cache_creation`, drop it from the subtraction.
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
                    val storedInput = rs.getLong("input_tokens")
                    val output = rs.getLong("output_tokens")
                    val cacheCreation = rs.getLong("cache_creation_input_tokens")
                    val cacheRead = rs.getLong("cache_read_input_tokens")
                    // The cached prefix is already inside `input_tokens` (see the KDoc). Clamp rather than
                    // let a corrupt row go negative: a negative column would CANCEL real spend out of the
                    // same day/model bucket, which is far worse than one row reading slightly high.
                    val input = (storedInput - cacheRead - cacheCreation).coerceAtLeast(0L)
                    // `storedInput` on the guard, not `input`: a turn that was ENTIRELY a cache hit nets to
                    // zero fresh tokens and is still real spend that must appear on the usage page.
                    if (storedInput + output <= 0L) continue
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
