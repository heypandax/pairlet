package dev.ccpocket.daemon.kimi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/**
 * Reads Kimi Code's token spend off `agents/main/wire.jsonl` for the usage dashboard (issue #258).
 *
 * The bearing line is `usage.record`, which [KimiTranscriptReplay] deliberately skips (it is not a chat
 * row). Sessions are enumerated through [KimiSessionIndex] — never by re-deriving the `workDirKey` slug.
 *
 * ⚠️ SHAPE CONFIDENCE. Only ONE spelling is probe-confirmed (0.34.0 fixture):
 * `{"type":"usage.record","model":"kimi-code/k3","usage":{"output":76}}` — i.e. the record is a TOP-LEVEL
 * line, the model sits beside `usage`, and `usage.output` is a plain token count. Everything else here is
 * deliberately DEFENSIVE rather than assumed:
 *  - the record is accepted both top-level and nested under `context.append_loop_event.event`;
 *  - each token category tries a list of plausible key spellings and takes the FIRST PRESENT one
 *    (never a sum — two spellings of the same number must not double count); an absent key is 0;
 *  - unknown keys inside `usage` are ignored, and a missing `usage` wrapper falls back to the record
 *    object itself (only numeric keys are ever read, so a stray string field is harmless);
 *  - `reasoning` counts ONLY when no output key was found. Every provider reports reasoning tokens as a
 *    SUBSET of output, so adding both would inflate; adding it when output is absent still honours
 *    "a present token key is counted".
 * A record that yields zero tokens is dropped, so a shape mismatch degrades to "Kimi contributes nothing"
 * — never to a crash or to invented numbers.
 *
 * TIMESTAMP. The confirmed fixture carries NO time field on the usage line. We take the record's own time
 * when it has a plausible epoch-ms one, else the most recent plausible timestamp seen EARLIER in the same
 * wire log (chat lines carry `time`), else the file's mtime. Day bucketing therefore degrades gracefully
 * to "the session's last write day" instead of dropping the spend.
 */
object KimiUsageScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Same guard as the replay: one request-trace line can be enormous, cap before parse (#81). */
    private const val MAX_LINE_CHARS = 200_000

    /** Below this an integer cannot be epoch-MILLIS (2001-09-09); fixtures use `"time":1`, and a
     *  seconds-scale value would land in 1970 — either way we prefer the mtime fallback over a wrong day. */
    private const val MIN_PLAUSIBLE_EPOCH_MS = 1_000_000_000_000L

    /** One Kimi model call's token spend. Token columns are treated as DISJOINT (cache read split out of
     *  input, like Claude/OpenCode) so the shared cache-hit formula stays correct. */
    data class UsageRecord(
        val id: String,
        val whenEpochMs: Long,
        val model: String,
        val input: Long,
        val output: Long,
        val cacheCreation: Long,
        val cacheRead: Long,
    )

    /** Every Kimi usage record at or after [sinceEpochMs], newest sessions included. Never throws. */
    fun usageRecords(
        sinceEpochMs: Long,
        entries: List<KimiSessionIndex.Entry> = KimiSessionIndex.entries(),
    ): List<UsageRecord> = runCatching {
        val out = mutableListOf<UsageRecord>()
        for (entry in entries) {
            val dir = entry.sessionDir?.let { Path.of(it) } ?: KimiPaths.sessionDir(entry.sessionId) ?: continue
            runCatching { out += readWire(KimiPaths.mainWireLog(dir), sinceEpochMs) }
        }
        out
    }.getOrDefault(emptyList())

    /** Scan ONE wire log. Public for tests: a fixture file is the only way to exercise the parse until a
     *  machine with real Kimi sessions can confirm the field spellings. */
    fun readWire(wire: Path, sinceEpochMs: Long): List<UsageRecord> {
        if (!wire.isRegularFile()) return emptyList()
        val mtime = runCatching { wire.getLastModifiedTime().toMillis() }.getOrDefault(0L)
        if (mtime > 0L && mtime < sinceEpochMs) return emptyList() // whole session predates the window
        val out = mutableListOf<UsageRecord>()
        var lastSeenTs = 0L // newest plausible timestamp from ANY earlier line in this file
        var lineNo = 0L
        runCatching {
            wire.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    lineNo += 1
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val isUsage = "usage.record" in line
                    // Chat lines are only read for their clock; skip the parse unless they can help.
                    if (!isUsage && "\"time\"" !in line && "\"timestamp\"" !in line) continue
                    val clipped = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
                    val root = runCatching { json.parseToJsonElement(clipped) }.getOrNull() as? JsonObject ?: continue
                    timestampOf(root)?.let { if (it > lastSeenTs) lastSeenTs = it }
                    if (!isUsage) continue
                    // the record is either the line itself or the loop event it wraps
                    val record = when {
                        root.str("type") == "usage.record" -> root
                        (root.obj("event")?.str("type")) == "usage.record" -> root.obj("event")!!
                        else -> null
                    } ?: continue
                    val usage = record.obj("usage") ?: record
                    val output = usage.firstLong("output", "output_tokens", "outputTokens", "completion_tokens", "completionTokens")
                    val reasoning = usage.firstLong("reasoning", "reasoning_tokens", "reasoningTokens", "thinking_tokens", "thinkingTokens")
                    val cache = usage["cache"]
                    val input = usage.firstLong("input", "input_tokens", "inputTokens", "prompt_tokens", "promptTokens")
                    val cacheRead = usage.firstLong(
                        "cache_read", "cacheRead", "cache_read_input_tokens", "cacheReadInputTokens",
                        "cached", "cached_tokens", "cachedInputTokens", "cache_read_tokens",
                    ).takeIf { it > 0 }
                        ?: (cache as? JsonObject)?.firstLong("read", "cache_read", "cacheRead")
                        ?: (cache as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: 0L
                    val cacheCreation = usage.firstLong(
                        "cache_creation", "cacheCreation", "cache_creation_input_tokens", "cacheCreationInputTokens",
                        "cache_write", "cacheWrite", "cache_creation_tokens",
                    ).takeIf { it > 0 }
                        ?: (cache as? JsonObject)?.firstLong("write", "creation", "cache_creation", "cacheCreation")
                        ?: 0L
                    // reasoning is a subset of output everywhere it exists — only stand in for a missing output
                    val effectiveOutput = if (output > 0L) output else reasoning
                    if (input + effectiveOutput + cacheCreation + cacheRead <= 0L) continue
                    val ts = timestampOf(record) ?: timestampOf(root) ?: lastSeenTs.takeIf { it > 0L } ?: mtime
                    val model = record.firstStr("model", "modelId", "model_id", "modelName")
                        ?: usage.firstStr("model", "modelId", "model_id") ?: "kimi"
                    out += UsageRecord(
                        // A forked session copies wire history, so the dedup key is CONTENT-based when the
                        // record has a real clock (like the Codex path); with only the mtime fallback the
                        // line number keeps two same-size turns in one file distinct.
                        id = if (ts >= MIN_PLAUSIBLE_EPOCH_MS) "$ts:$model:${input + effectiveOutput + cacheCreation + cacheRead}"
                        else "$wire#$lineNo",
                        whenEpochMs = ts, model = model,
                        input = input, output = effectiveOutput, cacheCreation = cacheCreation, cacheRead = cacheRead,
                    )
                }
            }
        }
        return out
    }

    /** A plausible epoch-ms clock off any of the spellings a wire line might use, else null. */
    private fun timestampOf(obj: JsonObject): Long? =
        obj.firstLong("time", "timestamp", "ts", "at", "createdAt", "created_at")
            .takeIf { it >= MIN_PLAUSIBLE_EPOCH_MS }

    /** The FIRST present numeric key among [keys] — never a sum, so two spellings can't double count. */
    private fun JsonObject.firstLong(vararg keys: String): Long =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L

    private fun JsonObject.firstStr(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
}
