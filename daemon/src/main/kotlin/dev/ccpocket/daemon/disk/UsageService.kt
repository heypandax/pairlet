package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.UsageDay
import dev.ccpocket.protocol.UsageModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.io.path.bufferedReader
import kotlin.io.path.isDirectory

/**
 * Aggregates token usage from EVERY backend's on-disk records (issue #26, extended by #217 and #258):
 *  - Claude transcripts under ~/.claude/projects — the same files ccusage reads. Per assistant turn it sums
 *    `message.usage` (input + output + cache) and reads the transcript's OWN `costUSD` (no fragile price
 *    table). Turns are deduped by `message.id` + `requestId` so a turn duplicated across resumed/forked
 *    `.jsonl` isn't double counted.
 *  - Codex rollouts under ~/.codex/sessions — `event_msg`/`token_count` records carry the turn's delta
 *    (`last_token_usage`) with a timestamp, and `turn_context` carries the model. Codex stamps no cost, so
 *    `costUsdToday`/`costUsdWindow` stay Claude-only.
 *  - OpenCode turns from its SQLite store (issue #217).
 *  - ZCode requests from `~/.zcode/cli/db/db.sqlite`'s `model_usage` table and Kimi Code `usage.record`
 *    lines from each session's `agents/main/wire.jsonl` (issue #258). Neither backend records a cost.
 *  - dsh (DeepSeek Harness) turns from each `~/.dsh/sessions/**/session.jsonl[.zstd]`'s `assistant/message`
 *    events (issue #279). No cost either — dsh stamps none, and an estimate from a price table would be
 *    worse than the blank the App already renders.
 *
 * [aggregate] can be scoped to ONE backend ([AgentKind] filter, issue #258); the default null keeps the
 * all-backends total. A filtered run doesn't even touch the other backends' disks.
 */
object UsageService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val zone: ZoneId = ZoneId.systemDefault()

    /** [projectsRoot]/[codexFiles] default to the real on-disk roots; tests inject temp ones.
     *  [openCodeTurns]/[zcodeTurns]/[kimiRecords]/[dshRecords] read token spend from each backend's own
     *  store since a cutoff (issues #217, #258, #279) — function seams so tests can feed fixtures without
     *  a real db/home. [agent] non-null narrows the whole aggregation to that backend. */
    fun aggregate(
        days: Int,
        projectsRoot: Path = ProjectPaths.projectsRoot(),
        codexFiles: List<Path> = runCatching { dev.ccpocket.daemon.codex.CodexPaths.sessionFiles() }.getOrDefault(emptyList()),
        openCodeTurns: (sinceEpochMs: Long) -> List<dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.UsageTurn> =
            { since -> runCatching { dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.usageTurns(since) }.getOrDefault(emptyList()) },
        zcodeTurns: (sinceEpochMs: Long) -> List<dev.ccpocket.daemon.zcode.ZCodeTranscriptScanner.UsageTurn> =
            { since -> runCatching { dev.ccpocket.daemon.zcode.ZCodeTranscriptScanner.usageTurns(since) }.getOrDefault(emptyList()) },
        kimiRecords: (sinceEpochMs: Long) -> List<dev.ccpocket.daemon.kimi.KimiUsageScanner.UsageRecord> =
            { since -> runCatching { dev.ccpocket.daemon.kimi.KimiUsageScanner.usageRecords(since) }.getOrDefault(emptyList()) },
        dshRecords: (sinceEpochMs: Long) -> List<dev.ccpocket.daemon.dsh.DshUsageScanner.UsageRecord> =
            { since -> runCatching { dev.ccpocket.daemon.dsh.DshUsageScanner.usageRecords(since) }.getOrDefault(emptyList()) },
        agent: AgentKind? = null,
    ): Usage {
        val span = days.coerceIn(1, 90)
        val today = LocalDate.now(zone)
        val start = today.minusDays((span - 1).toLong())
        // The PREVIOUS equal-width window [prevStart, start) rides along in the same single pass (issue #128):
        // the scan horizon doubles (30d view reads 60 days) but each file is still walked exactly once —
        // turns older than `start` only feed the one prev-window total, never the trend/models/today stats.
        val prevStart = start.minusDays(span.toLong())
        var prevWindowTokens = 0L
        val seen = HashSet<String>()
        val perDay = HashMap<LocalDate, Long>()
        val perHour = LongArray(24) // today's tokens by local hour — only surfaced for the Today range
        val perModel = HashMap<String, Long>()
        // issue #217: which backend contributed each model key, so the by-model bars get the right
        // AgentKind badge instead of re-guessing from the model string (an OpenCode "openai/gpt-…"
        // must NOT fall into the codex/gpt heuristic). Recorded at accumulation, read at classify.
        val modelAgent = HashMap<String, AgentKind>()
        var tokensToday = 0L
        var requestsToday = 0L
        var inputToday = 0L
        var cacheReadToday = 0L
        var costToday = 0.0
        var costSeen = false
        // Full-WINDOW counterparts of the today sub-metrics (issue #174): every in-window turn feeds these,
        // today or not, so the 7d/30d ranges get real requests/cache-hit/cost instead of today's repeated.
        // Same semantics as the today set: cost stays null unless a transcript costUSD was actually seen.
        var requestsWindow = 0L
        var inputWindow = 0L
        var cacheReadWindow = 0L
        var costWindow = 0.0
        var costWindowSeen = false

        // issue #258: one shared accumulator for the "turn already parsed into columns" backends, so a new
        // one costs a call, not another copy of the bucketing rules. [dedupKey] is skipped when blank.
        fun add(
            dedupKey: String, whenEpochMs: Long, model: String, kind: AgentKind,
            input: Long, output: Long, cacheCreation: Long, cacheRead: Long,
        ) {
            val total = input + output + cacheCreation + cacheRead
            if (total <= 0L) return
            if (dedupKey.isNotBlank() && !seen.add("${kind.name}:$dedupKey")) return
            val when_ = Instant.ofEpochMilli(whenEpochMs).atZone(zone)
            val date = when_.toLocalDate()
            if (date.isBefore(prevStart)) return
            if (date.isBefore(start)) { prevWindowTokens += total; return } // prev-window turn: baseline only
            perDay[date] = (perDay[date] ?: 0) + total
            perModel[model] = (perModel[model] ?: 0) + total
            modelAgent[model] = kind
            requestsWindow++
            inputWindow += input
            cacheReadWindow += cacheRead
            if (date == today) {
                perHour[when_.hour] += total
                tokensToday += total
                requestsToday++
                inputToday += input
                cacheReadToday += cacheRead
            }
        }

        if (projectsRoot.isDirectory() && (agent == null || agent == AgentKind.CLAUDE)) Files.newDirectoryStream(projectsRoot).use { dirs ->
            for (dir in dirs) {
                if (!dir.isDirectory()) continue
                val files = runCatching { Files.newDirectoryStream(dir, "*.jsonl").use { it.toList() } }.getOrNull() ?: continue
                for (file in files) runCatching {
                    file.bufferedReader().useLines { lines ->
                        for (raw in lines) {
                            val line = raw.trim()
                            if (line.isEmpty() || "\"assistant\"" !in line) continue // cheap prefilter before JSON parse
                            val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                            if (obj.str("type") != "assistant") continue
                            val msg = obj["message"] as? JsonObject ?: continue
                            val when_ = obj.str("timestamp")?.let(::parseWhen) ?: continue
                            val date = when_.toLocalDate()
                            if (date.isBefore(prevStart)) continue
                            // dedupe: the same turn reappears in multiple .jsonl after a resume/fork
                            val key = (msg.str("id") ?: "") + ":" + (obj.str("requestId") ?: "")
                            if (key != ":" && !seen.add(key)) continue
                            val usage = msg["usage"] as? JsonObject
                            val input = usage.long("input_tokens")
                            val cacheRead = usage.long("cache_read_input_tokens")
                            val total = input + usage.long("output_tokens") + usage.long("cache_creation_input_tokens") + cacheRead
                            if (date.isBefore(start)) { prevWindowTokens += total; continue } // prev-window turn: baseline only
                            perDay[date] = (perDay[date] ?: 0) + total
                            val model = msg.str("model") ?: "unknown"
                            perModel[model] = (perModel[model] ?: 0) + total
                            modelAgent[model] = AgentKind.CLAUDE
                            requestsWindow++
                            inputWindow += input
                            cacheReadWindow += cacheRead
                            (obj["costUSD"] as? JsonPrimitive)?.doubleOrNull?.let { costWindow += it; costWindowSeen = true }
                            if (date == today) {
                                perHour[when_.hour] += total
                                tokensToday += total
                                requestsToday++
                                inputToday += input
                                cacheReadToday += cacheRead
                                (obj["costUSD"] as? JsonPrimitive)?.doubleOrNull?.let { costToday += it; costSeen = true }
                            }
                        }
                    }
                }
            }
        }

        // ── Codex rollouts: per-turn deltas from token_count events (the Codex by-model bars were a
        // dead path before this). Dedup by timestamp+total — a resumed thread's rollout can carry
        // copied history. OpenAI counts cached ⊂ input, Claude splits them; normalize to Claude's
        // split so the shared cache-hit formula (cacheRead / (input + cacheRead)) stays correct.
        for (file in if (agent == null || agent == AgentKind.CODEX) codexFiles else emptyList()) runCatching {
            val mtime = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return@runCatching
            if (Instant.ofEpochMilli(mtime).atZone(zone).toLocalDate().isBefore(prevStart)) return@runCatching // whole file predates both windows
            var model = "codex"
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if ("\"turn_context\"" in line) {
                        ((runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject)?.get("payload") as? JsonObject)
                            ?.str("model")?.takeIf { it.isNotBlank() }?.let { model = it }
                        continue
                    }
                    if ("\"token_count\"" !in line) continue // cheap prefilter before JSON parse
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    val payload = obj["payload"] as? JsonObject ?: continue
                    if (payload.str("type") != "token_count") continue
                    val info = payload["info"] as? JsonObject ?: continue // null info = rate-limit-only event
                    val last = info["last_token_usage"] as? JsonObject ?: continue
                    val total = last.long("total_tokens").takeIf { it > 0 }
                        ?: (last.long("input_tokens") + last.long("output_tokens")).takeIf { it > 0 } ?: continue
                    val ts = obj.str("timestamp") ?: continue
                    val when_ = parseWhen(ts) ?: continue
                    val date = when_.toLocalDate()
                    if (date.isBefore(prevStart)) continue
                    if (!seen.add("cx:$ts:$total")) continue
                    if (date.isBefore(start)) { prevWindowTokens += total; continue } // prev-window turn: baseline only
                    perDay[date] = (perDay[date] ?: 0) + total
                    perModel[model] = (perModel[model] ?: 0) + total
                    modelAgent[model] = AgentKind.CODEX
                    requestsWindow++
                    val cachedWin = last.long("cached_input_tokens")
                    inputWindow += (last.long("input_tokens") - cachedWin).coerceAtLeast(0)
                    cacheReadWindow += cachedWin
                    if (date == today) {
                        perHour[when_.hour] += total
                        tokensToday += total
                        requestsToday++
                        val cached = last.long("cached_input_tokens")
                        inputToday += (last.long("input_tokens") - cached).coerceAtLeast(0)
                        cacheReadToday += cached
                    }
                }
            }
        }

        // ── OpenCode turns: per-turn token spend from its SQLite store (issue #217). Same day/model
        // bucketing as the other two backends; OpenCode's tokens already split cache-read out of input
        // (like Claude), so the shared cache-hit formula stays correct. OpenCode stamps cost per model
        // in its own tables, but we don't price here — token counts only, per the issue's scope.
        val prevStartEpochMs = prevStart.atStartOfDay(zone).toInstant().toEpochMilli()
        for (turn in if (agent == null || agent == AgentKind.OPENCODE) openCodeTurns(prevStartEpochMs) else emptyList()) {
            val total = turn.input + turn.output + turn.cacheRead
            if (total <= 0L) continue
            if (turn.id.isNotBlank() && !seen.add("oc:${turn.id}")) continue
            val when_ = Instant.ofEpochMilli(turn.whenEpochMs).atZone(zone)
            val date = when_.toLocalDate()
            if (date.isBefore(prevStart)) continue
            if (date.isBefore(start)) { prevWindowTokens += total; continue } // prev-window turn: baseline only
            perDay[date] = (perDay[date] ?: 0) + total
            perModel[turn.model] = (perModel[turn.model] ?: 0) + total
            modelAgent[turn.model] = AgentKind.OPENCODE
            requestsWindow++
            inputWindow += turn.input
            cacheReadWindow += turn.cacheRead
            if (date == today) {
                perHour[when_.hour] += total
                tokensToday += total
                requestsToday++
                inputToday += turn.input
                cacheReadToday += turn.cacheRead
            }
        }

        // ── ZCode requests: one row per model call from `model_usage` (issue #258). Its four token columns
        // are disjoint (ZCode's own computed_total is their sum), so cache read stays split out of input
        // exactly like Claude/OpenCode. Dedup rides `model_usage.id`, the table's primary key.
        if (agent == null || agent == AgentKind.ZCODE) for (t in zcodeTurns(prevStartEpochMs)) {
            add(t.id, t.whenEpochMs, t.model, AgentKind.ZCODE, t.input, t.output, t.cacheCreation, t.cacheRead)
        }

        // ── Kimi Code records: `usage.record` lines off each session's wire.jsonl (issue #258). Only the
        // `usage.output` spelling is probe-confirmed, so KimiUsageScanner parses defensively and a shape
        // mismatch simply yields no records — Kimi then contributes nothing rather than wrong numbers.
        if (agent == null || agent == AgentKind.KIMI) for (r in kimiRecords(prevStartEpochMs)) {
            add(r.id, r.whenEpochMs, r.model, AgentKind.KIMI, r.input, r.output, r.cacheCreation, r.cacheRead)
        }

        // ── dsh turns: one record per `assistant/message` event in each session transcript (issue #279).
        // The usage page has been offering a "DeepSeek" chip since #255 with nothing behind it, so every
        // dsh bar read 0 while the spend sat on disk. DshUsageScanner already normalizes DeepSeek's
        // OpenAI-style cached ⊂ input into the disjoint columns this aggregation assumes, and folds
        // reasoning into output — so the columns arrive on the same footing as every other backend's.
        if (agent == null || agent == AgentKind.DSH) for (r in dshRecords(prevStartEpochMs)) {
            add(r.id, r.whenEpochMs, r.model, AgentKind.DSH, r.input, r.output, r.cacheCreation, r.cacheRead)
        }

        val trend = (0 until span).map { i ->
            val d = start.plusDays(i.toLong())
            UsageDay(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), perDay[d] ?: 0, date = d.toString())
        }
        // 24 hourly buckets only when the window IS today (span == 1); larger windows leave hours null.
        // Hour buckets carry no date — "today" is their definition and no client reads it.
        val hours = if (span == 1) (0..23).map { UsageDay(label = "%02d:00".format(it), tokens = perHour[it]) } else null
        // drop zero-token models so a `<synthetic> 0` placeholder turn never shows a bar
        val models = perModel.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(6).map {
            // prefer the backend recorded at accumulation (issue #217); fall back to the legacy
            // string heuristic only for a key no loop tagged (defensive — shouldn't happen).
            val agent = modelAgent[it.key] ?: run {
                if ("codex" in it.key.lowercase() || "gpt" in it.key.lowercase()) AgentKind.CODEX else AgentKind.CLAUDE
            }
            UsageModel(it.key, it.value, agent)
        }
        val cacheHit = (inputToday + cacheReadToday).takeIf { it > 0 }?.let { ((cacheReadToday * 100) / it).toInt() }
        // window cache-hit: the exact today formula over the window accumulators (cacheRead / (input + cacheRead))
        val cacheHitWindow = (inputWindow + cacheReadWindow).takeIf { it > 0 }?.let { ((cacheReadWindow * 100) / it).toInt() }

        return Usage(
            days = trend,
            models = models,
            tokensToday = tokensToday,
            requestsToday = requestsToday,
            cacheHitPct = cacheHit,
            costUsdToday = if (costSeen) costToday else null,
            hours = hours,
            prevWindowTokens = prevWindowTokens,
            requestsWindow = requestsWindow,
            cacheHitPctWindow = cacheHitWindow,
            costUsdWindow = if (costWindowSeen) costWindow else null,
        )
    }

    private fun parseWhen(ts: String): ZonedDateTime? = runCatching { Instant.parse(ts).atZone(zone) }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.long(key: String): Long = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
}
