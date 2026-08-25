package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime

/**
 * Reads `dsh` (DeepSeek Harness) token spend off each session transcript for the usage dashboard
 * (issue #279 — the usage page already offered a "DeepSeek" chip, but with no scanner behind it every
 * bar read 0).
 *
 * The bearing line is `assistant/message`, whose shape is probe-confirmed on a real local transcript:
 * ```
 * {"type":"assistant/message","seq":36,"time":1786805272848,
 *  "data":{"turn":1,"step":1,
 *          "message":{"role":"assistant","content":[…],
 *                     "source":{"kind":"model","provider":"deepseek-official","model":"deepseek-v4-flash"},
 *                     "id":"580798a2-…"},
 *          "usage":{"inputTokens":11173,"outputTokens":19,"cacheReadTokens":0,"reasoningTokens":0}}}
 * ```
 * ⚠️ `usage` sits under `data` as a SIBLING of `message`, not inside it — the one detail most likely to be
 * "fixed" into a bug later. The clock is the event's TOP-LEVEL `time`; the model and the dedup id live
 * one level deeper, on `data.message`.
 *
 * ENUMERATION deliberately does NOT reuse [DshTranscriptScanner.findAll]: that filters out
 * `origin:"subagent"` sessions because a sub-agent's internal chat is not something the user started and
 * so has no place in a session LIST. Usage is a different question — a sub-agent burns real tokens on the
 * user's account, so leaving those sessions out would under-report spend. We therefore walk the store
 * ourselves ([DshPaths.projectDirs] → [DshPaths.sessionDirs] → [DshPaths.transcriptFile]) and keep only
 * the `version != 0` guard.
 *
 * Defensiveness follows [dev.ccpocket.daemon.kimi.KimiUsageScanner]: every token category tries a list of
 * plausible key spellings and takes the FIRST PRESENT one (never a sum — two spellings of one number must
 * not double count), an absent key is 0, and a record that yields zero tokens is dropped. A shape drift
 * degrades to "dsh contributes nothing" rather than to a crash or to invented numbers.
 */
object DshUsageScanner {

    /** Below this an integer cannot be epoch-MILLIS (2001-09-09); a seconds-scale or fixture value would
     *  land in 1970, and dating the spend by the file's mtime beats filing it under the wrong day. */
    private const val MIN_PLAUSIBLE_EPOCH_MS = 1_000_000_000_000L

    /** Fallback model label when the event carries neither a model nor a provider. */
    private const val UNKNOWN_MODEL = "deepseek"

    /** One dsh model call's token spend. Token columns are treated as DISJOINT (cache read split out of
     *  input, like Claude/Codex/OpenCode) so the shared cache-hit formula stays correct. [cacheCreation]
     *  is always 0 — dsh records no cache-write counter — and is kept only so the row lines up with
     *  `UsageService.add()`'s four columns. */
    data class UsageRecord(
        val id: String,
        val whenEpochMs: Long,
        val model: String,
        val input: Long,
        val output: Long,
        val cacheCreation: Long,
        val cacheRead: Long,
    )

    /** Every dsh usage record at or after [sinceEpochMs]. [root] is a test seam; production reads the real
     *  store. Never throws: a usage page that renders one backend short beats one that fails to load. */
    fun usageRecords(sinceEpochMs: Long, root: Path = DshPaths.sessionsRoot()): List<UsageRecord> = runCatching {
        val out = mutableListOf<UsageRecord>()
        for (projectDir in DshPaths.projectDirs(root)) {
            for (dir in DshPaths.sessionDirs(projectDir)) {
                if (DshPaths.isSidecar(dir.fileName.toString())) continue
                val file = DshPaths.transcriptFile(dir) ?: continue
                runCatching { out += readTranscript(file, sinceEpochMs) }
            }
        }
        // No cap on sessions walked, unlike the listing path: dropping a session here would silently
        // UNDER-COUNT spend, and the mtime gate below already reduces an out-of-window session to a
        // single stat.
        out
    }.getOrDefault(emptyList())

    /** Scan ONE transcript. Decompression (concatenated zstd frames, 64MB ceiling, ragged live tail) is
     *  [DshTranscript.lines]'s job — never re-implemented here. */
    private fun readTranscript(file: Path, sinceEpochMs: Long): List<UsageRecord> {
        val mtime = runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L)
        if (mtime > 0L && mtime < sinceEpochMs) return emptyList() // whole session predates the window
        // Header first, off a bounded read: an unsupported version must not cost a full decompression.
        // Unknown format version → skip the session rather than guess at its fields. A `subagent` origin
        // is deliberately NOT filtered: its tokens are the user's tokens (see the class comment).
        val header = DshTranscript.header(file) ?: return emptyList()
        if (!header.isSupported) return emptyList()
        val lines = DshTranscript.lines(file)
        val out = mutableListOf<UsageRecord>()
        var lineNo = 0L
        for (raw in lines) {
            lineNo += 1
            if (DshTranscript.EVENT_ASSISTANT !in raw) continue // cheap prefilter before JSON parse
            val root = DshTranscript.parseLine(raw) ?: continue
            if (root.str("type") != DshTranscript.EVENT_ASSISTANT) continue
            val data = root.obj("data") ?: continue
            // `usage` is a sibling of `message` under `data`; the record object itself is the fallback so a
            // future writer that flattens the counters still lands (only numeric keys are ever read).
            val usage = data.obj("usage") ?: data
            val input = usage.firstLong("inputTokens", "input_tokens", "input")
            val output = usage.firstLong("outputTokens", "output_tokens", "output")
            val cacheRead = usage.firstLong("cacheReadTokens", "cache_read_input_tokens", "cacheRead", "cache_read")
            val reasoning = usage.firstLong("reasoningTokens", "reasoning_tokens", "reasoning")
            // DeepSeek's API counts reasoning INSIDE completion_tokens, so adding both would inflate output.
            // Standing in when output is absent still honours "a present token key is counted" (Kimi's rule).
            val effectiveOutput = if (output > 0L) output else reasoning
            // DeepSeek is OpenAI-lineage: cached tokens are a SUBSET of the prompt count, so subtract them
            // to reach Claude's disjoint columns — the same normalization the Codex path applies.
            // ⚠️ PRIOR, not evidence: every local sample has cacheReadTokens == 0, so the containment could
            // not be observed. If dsh is ever seen to pre-split the two, drop this subtraction.
            val netInput = (input - cacheRead).coerceAtLeast(0L)
            if (netInput + effectiveOutput + cacheRead <= 0L) continue
            val message = data.obj("message")
            val ts = root.long("time")?.takeIf { it >= MIN_PLAUSIBLE_EPOCH_MS } ?: mtime
            val source = message?.obj("source")
            val model = source?.str("model")?.takeIf { it.isNotBlank() }
                ?: source?.str("provider")?.takeIf { it.isNotBlank() }
                ?: UNKNOWN_MODEL
            out += UsageRecord(
                // The provider's own message id: a forked session copies the history verbatim, so the same
                // id in two transcripts is the same spend and dedups by itself. Without one, the file+line
                // keeps two identical-looking turns distinct instead of collapsing them.
                id = message?.str("id")?.takeIf { it.isNotBlank() } ?: "$file#$lineNo",
                whenEpochMs = ts,
                model = model,
                input = netInput,
                output = effectiveOutput,
                cacheCreation = 0L, // dsh records no cache-write counter
                cacheRead = cacheRead,
            )
        }
        return out
    }

    /** The FIRST present numeric key among [keys] — never a sum, so two spellings can't double count. */
    private fun JsonObject.firstLong(vararg keys: String): Long =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
}
