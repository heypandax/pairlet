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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.io.path.bufferedReader
import kotlin.io.path.isDirectory

/**
 * Aggregates token usage from BOTH agents' on-disk records (issue #26):
 *  - Claude transcripts under ~/.claude/projects — the same files ccusage reads. Per assistant turn it sums
 *    `message.usage` (input + output + cache) and reads the transcript's OWN `costUSD` (no fragile price
 *    table). Turns are deduped by `message.id` + `requestId` so a turn duplicated across resumed/forked
 *    `.jsonl` isn't double counted.
 *  - Codex rollouts under ~/.codex/sessions — `event_msg`/`token_count` records carry the turn's delta
 *    (`last_token_usage`) with a timestamp, and `turn_context` carries the model. Codex stamps no cost, so
 *    `costUsdToday` remains Claude-only.
 */
object UsageService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val zone: ZoneId = ZoneId.systemDefault()

    fun aggregate(days: Int): Usage {
        val span = days.coerceIn(1, 90)
        val today = LocalDate.now(zone)
        val start = today.minusDays((span - 1).toLong())
        val seen = HashSet<String>()
        val perDay = HashMap<LocalDate, Long>()
        val perModel = HashMap<String, Long>()
        var tokensToday = 0L
        var requestsToday = 0L
        var inputToday = 0L
        var cacheReadToday = 0L
        var costToday = 0.0
        var costSeen = false

        val root = ProjectPaths.projectsRoot()
        if (root.isDirectory()) Files.newDirectoryStream(root).use { dirs ->
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
                            val date = obj.str("timestamp")?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() } ?: continue
                            if (date.isBefore(start)) continue
                            // dedupe: the same turn reappears in multiple .jsonl after a resume/fork
                            val key = (msg.str("id") ?: "") + ":" + (obj.str("requestId") ?: "")
                            if (key != ":" && !seen.add(key)) continue
                            val usage = msg["usage"] as? JsonObject
                            val input = usage.long("input_tokens")
                            val cacheRead = usage.long("cache_read_input_tokens")
                            val total = input + usage.long("output_tokens") + usage.long("cache_creation_input_tokens") + cacheRead
                            perDay[date] = (perDay[date] ?: 0) + total
                            perModel[msg.str("model") ?: "unknown"] = (perModel[msg.str("model") ?: "unknown"] ?: 0) + total
                            if (date == today) {
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
        for (file in runCatching { dev.ccpocket.daemon.codex.CodexPaths.sessionFiles() }.getOrDefault(emptyList())) runCatching {
            val mtime = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return@runCatching
            if (Instant.ofEpochMilli(mtime).atZone(zone).toLocalDate().isBefore(start)) return@runCatching // whole file predates the window
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
                    val date = runCatching { Instant.parse(ts).atZone(zone).toLocalDate() }.getOrNull() ?: continue
                    if (date.isBefore(start)) continue
                    if (!seen.add("cx:$ts:$total")) continue
                    perDay[date] = (perDay[date] ?: 0) + total
                    perModel[model] = (perModel[model] ?: 0) + total
                    if (date == today) {
                        tokensToday += total
                        requestsToday++
                        val cached = last.long("cached_input_tokens")
                        inputToday += (last.long("input_tokens") - cached).coerceAtLeast(0)
                        cacheReadToday += cached
                    }
                }
            }
        }

        val trend = (0 until span).map { i ->
            val d = start.plusDays(i.toLong())
            UsageDay(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), perDay[d] ?: 0)
        }
        val models = perModel.entries.sortedByDescending { it.value }.take(6).map {
            val codex = "codex" in it.key.lowercase() || "gpt" in it.key.lowercase()
            UsageModel(it.key, it.value, if (codex) AgentKind.CODEX else AgentKind.CLAUDE)
        }
        val cacheHit = (inputToday + cacheReadToday).takeIf { it > 0 }?.let { ((cacheReadToday * 100) / it).toInt() }

        return Usage(
            days = trend,
            models = models,
            tokensToday = tokensToday,
            requestsToday = requestsToday,
            cacheHitPct = cacheHit,
            costUsdToday = if (costSeen) costToday else null,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.long(key: String): Long = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
}
