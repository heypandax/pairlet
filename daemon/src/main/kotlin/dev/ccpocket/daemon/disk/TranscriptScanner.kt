package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

/** Reads the `.jsonl` transcript headers under a project dir into [SessionSummary] — no claude launch. */
object TranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L // transcript touched within this window = a session running right now

    fun scan(dir: Path): List<SessionSummary> {
        if (!dir.isDirectory()) return emptyList()
        val files = Files.newDirectoryStream(dir, "*.jsonl").use { it.toList() }
        // read the rewind/fork ledger ONCE per scan, not once per file (issue #282) — a project dir can
        // hold hundreds of transcripts and the edges are daemon-global
        val lineage = runCatching { RewindLineage.byChild() }.getOrDefault(emptyMap())
        return files.mapNotNull { runCatching { summarize(it) }.getOrNull() }
            .map { s -> lineage[s.sessionId]?.let { stampLineage(s, it) } ?: s }
            .sortedByDescending { it.lastModified }
    }

    /** Land one ledger edge on the CHILD row. The original keeps a clean summary: clients derive "this
     *  one was superseded" by looking for a peer that names it, so nothing has to be written twice. */
    private fun stampLineage(s: SessionSummary, e: RewindLineage.Entry): SessionSummary = when (e.mode) {
        dev.ccpocket.protocol.RewindMode.FORK -> s.copy(forkedFrom = e.parentSid)
        dev.ccpocket.protocol.RewindMode.REWIND -> s.copy(rewindOf = e.parentSid)
        else -> s
    }

    fun summarize(file: Path): SessionSummary? {
        val sessionId = file.fileName.toString().removeSuffix(".jsonl")
        var firstPrompt: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        var version: String? = null
        var aiTitle: String? = null
        var customTitle: String? = null // the user's rename, persisted by Claude as a `custom-title` record (issue #14)
        // A session whose OPENING turn is a slash command (`/record-issue …`) never gets a real `type:"user"`
        // record written — Claude stores that text only in a `last-prompt` record. Without this fallback such a
        // session carries no prompt and no title, so it's dropped from the list entirely (issue #341). First
        // record wins = the opening prompt; only ever used when no real user turn exists.
        var lastPrompt: String? = null
        // The same sessions lose their workdir too: cwd/gitBranch/version are captured off the first real user
        // turn, so without one the summary says cwd="" — and opening such a row makes the app focus directory
        // "", a nameless synthetic project group in the sidebar (issue #341's second face). Every tool_result
        // and assistant record carries the same envelope, so the first record naming a cwd is the fallback.
        var fbCwd: String? = null
        var fbGitBranch: String? = null
        var fbVersion: String? = null
        var model: String? = null       // last assistant turn's model — same rules as [lastModel], captured in this pass
        var userCount = 0

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (fbCwd == null) obj.str("cwd")?.let {
                    fbCwd = it
                    fbGitBranch = obj.str("gitBranch")
                    fbVersion = obj.str("version")
                }
                when (obj.str("type")) {
                    "user" -> if (isRealUserTurn(obj)) {
                        userCount++
                        if (firstPrompt == null) {
                            firstPrompt = extractUserText(obj)
                            cwd = obj.str("cwd")
                            gitBranch = obj.str("gitBranch")
                            version = obj.str("version")
                        }
                    }
                    "assistant" -> assistantModel(obj)?.let { model = it }
                    "ai-title" -> aiTitle = obj.str("aiTitle")
                    // the user's explicit rename — rewritten through the session, last wins (issue #14)
                    "custom-title" -> customTitle = obj.str("customTitle")
                    "last-prompt" -> if (lastPrompt == null) lastPrompt = obj.str("lastPrompt")
                }
            }
        }

        if (firstPrompt == null && aiTitle == null && customTitle == null && lastPrompt == null) return null
        val mtime = file.getLastModifiedTime().toMillis()
        val fp = firstPrompt ?: lastPrompt ?: ""
        // the user's rename beats the AI's guess beats the first prompt (issue #14)
        val title = customTitle?.takeIf { it.isNotBlank() }
            ?: aiTitle?.takeIf { it.isNotBlank() }
            ?: fp.lineSequence().firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
            ?: sessionId
        return SessionSummary(
            sessionId = sessionId,
            title = title,
            firstPrompt = fp,
            messageCount = userCount,
            cwd = cwd ?: fbCwd ?: "",
            lastModified = mtime,
            gitBranch = gitBranch ?: fbGitBranch,
            version = version ?: fbVersion,
            live = System.currentTimeMillis() - mtime < LIVE_WINDOW_MS,
            model = model,
        )
    }

    /**
     * Everything a COLD RESUME needs from a transcript, in the shapes the three single-purpose readers
     * below produce: [title] as [summarize] computes it (null exactly when summarize returns null, i.e. the
     * file carries no prompt and no title record), [gitBranch] from the first real user turn (falling back to
     * the first cwd-bearing record, as summarize does — issue #341), [model] as [lastModel], [contextTokens]
     * as [lastContextTokens].
     */
    data class ResumeSeed(
        val title: String? = null,
        val gitBranch: String? = null,
        val model: String? = null,
        val contextTokens: Long? = null,
    )

    /**
     * All four resume fields in ONE pass, memoized by (path, mtime). Opening a Claude session used to parse
     * the same .jsonl three times over (summarize → title, [lastModel], [lastContextTokens]) — three full
     * reads of a file that reaches megabytes, back to back on the open path (issue #303).
     *
     * The single-purpose readers stay: they have many other callers, and they remain the SPEC this must
     * match — `ResumeSeedParityTest` pins the four fields against them line by line.
     *
     * Null only when [file] is absent or unreadable. Transcripts are append-only, so a moved mtime is the
     * only way a parse of one goes stale (the same reasoning CodexTranscriptScanner's memo uses).
     */
    fun resumeSeed(file: Path): ResumeSeed? {
        if (!file.exists()) return null
        val stamp = runCatching { file.getLastModifiedTime() }.getOrNull()
        seedCache.get(file, stamp)?.let { return it }
        val seed = runCatching { readResumeSeed(file) }.getOrNull() ?: return null
        seedCache.put(file, stamp, seed)
        return seed
    }

    private fun readResumeSeed(file: Path): ResumeSeed {
        val sessionId = file.fileName.toString().removeSuffix(".jsonl")
        var firstPrompt: String? = null
        var gitBranch: String? = null
        var aiTitle: String? = null
        var customTitle: String? = null
        var lastPrompt: String? = null // slash-command-opened sessions carry their prompt only here (issue #341)
        var fbCwd: String? = null // envelope fallback, same cwd-keyed rule as summarize (issue #341)
        var fbGitBranch: String? = null
        var model: String? = null
        var contextTokens: Long? = null

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (fbCwd == null) obj.str("cwd")?.let {
                    fbCwd = it
                    fbGitBranch = obj.str("gitBranch")
                }
                when (obj.str("type")) {
                    "user" -> if (isRealUserTurn(obj) && firstPrompt == null) {
                        firstPrompt = extractUserText(obj)
                        gitBranch = obj.str("gitBranch")
                    }
                    "assistant" -> {
                        assistantModel(obj)?.let { model = it } // skips sidechain + <synthetic>, last wins
                        contextOf(obj)?.let { contextTokens = it } // last MAIN-chain turn carrying usage wins
                    }
                    "ai-title" -> aiTitle = obj.str("aiTitle")
                    "custom-title" -> customTitle = obj.str("customTitle")
                    "last-prompt" -> if (lastPrompt == null) lastPrompt = obj.str("lastPrompt")
                }
            }
        }

        // summarize's guard: without any of the four, that reader answers null and there is no title to seed
        val title = if (firstPrompt == null && aiTitle == null && customTitle == null && lastPrompt == null) null else {
            customTitle?.takeIf { it.isNotBlank() }
                ?: aiTitle?.takeIf { it.isNotBlank() }
                ?: (firstPrompt ?: lastPrompt).orEmpty().lineSequence().firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
                ?: sessionId
        }
        // the fallback branch only exists where summarize produces a summary at all (title != null) —
        // ResumeSeedParityTest pins seed.gitBranch to summary?.gitBranch, which is null when summarize bails
        return ResumeSeed(
            title = title,
            gitBranch = gitBranch ?: fbGitBranch?.takeIf { title != null },
            model = model,
            contextTokens = contextTokens,
        )
    }

    private val seedCache = MtimeMemo<ResumeSeed>(SEED_MEMO_MAX)

    /** A bounded LRU keyed by file path and stamped with that file's mtime — the append-only-file memo
     *  CodexTranscriptScanner already uses, kept as a local twin rather than shared across two scanners that
     *  otherwise know nothing about each other. The raw FileTime (not millis) makes a same-millisecond
     *  rewrite visible instead of silently cached. */
    private class MtimeMemo<V : Any>(private val max: Int) {
        private val map = object : LinkedHashMap<Path, Pair<FileTime, V>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Pair<FileTime, V>>) = size > max
        }

        @Synchronized
        fun get(file: Path, stamp: FileTime?): V? =
            if (stamp == null) null else map[file]?.takeIf { it.first == stamp }?.second

        @Synchronized
        fun put(file: Path, stamp: FileTime?, value: V) {
            if (stamp != null) map[file] = stamp to value
        }

        @Synchronized
        fun clear() = map.clear()
    }

    /** Cross-test isolation: the memo is process-wide state on an object singleton. */
    internal fun clearSeedCacheForTest() = seedCache.clear()

    private const val SEED_MEMO_MAX = 800

    /**
     * Context tokens the LAST completed assistant turn left in the window — its `message.usage` summed
     * through [TokenUsage.contextTokens], i.e. `input + output + cache_read + cache_creation`. Mirrors
     * the live TurnDone sum so the phone's usage statusline reads the same on resume as mid-session.
     * Null when the file is absent or no turn carries usage yet.
     *
     * (This doc used to say "output isn't in-window yet" and list only three terms, contradicting both
     * the code below and [TokenUsage.contextTokens]'s own reasoning — the assistant's reply is in the
     * transcript the next turn replays. The code was right; corrected 2026-07-19 under issue #159
     * before someone "fixed" the code to match the comment.)
     */
    fun lastContextTokens(file: Path): Long? {
        if (!file.exists()) return null
        var last: Long? = null
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (obj.str("type") != "assistant") continue
                if (obj.bool("isSidechain") == true) continue // Task-subagent turns share the file; their usage is the SUBAGENT's window, not this session's
                val usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject ?: continue
                // build the wire's TokenUsage so occupancy comes from the one shared accessor, not a re-sum
                val total = TokenUsage(
                    inputTokens = usage.long("input_tokens") ?: 0,
                    outputTokens = usage.long("output_tokens") ?: 0,
                    cacheCreationInputTokens = usage.long("cache_creation_input_tokens"),
                    cacheReadInputTokens = usage.long("cache_read_input_tokens"),
                ).contextTokens
                if (total > 0) last = total // last assistant turn with usage wins
            }
        }
        return last
    }

    /** The model id of the LAST assistant turn in [file] (`message.model`), or null if none/absent. Lets a cold
     *  resume announce the session's real model (and derive its context window) before the first new turn's init
     *  lands — a headless claude is silent until then (issue #27). Skips Task-subagent turns (isSidechain — they
     *  share the file but ran the SUBAGENT's model) and `<synthetic>` records (API-error/notice placeholders). */
    fun lastModel(file: Path): String? {
        if (!file.exists()) return null
        var last: String? = null
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (obj.str("type") != "assistant") continue
                assistantModel(obj)?.let { last = it }
            }
        }
        return last
    }

    /**
     * How many MAIN-chain assistant records at the TAIL of [file] are `<synthetic>` API-failure
     * placeholders in a row (a non-synthetic assistant record resets the run). A dead session — every
     * API call failing, typically past its context window — ends in [user, synthetic]+ pairs, so a
     * streak ≥ 2 seeds [dev.ccpocket.protocol.SessionLive.degraded] on resume: the phone warns before
     * the user pours more prompts into a transcript that can only bloat (issue #65).
     */
    fun syntheticTailStreak(file: Path): Int {
        if (!file.exists()) return 0
        var streak = 0
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (obj.str("type") != "assistant") continue
                if (obj.bool("isSidechain") == true) continue // subagent turns share the file but aren't this session's replies
                val model = (obj["message"] as? JsonObject)?.str("model")
                streak = if (model == "<synthetic>") streak + 1 else 0
            }
        }
        return streak
    }

    /** Context occupancy an assistant line leaves in the window, or null when it doesn't count: a
     *  Task-subagent turn (isSidechain — that usage describes the SUBAGENT's window), a line with no
     *  `message.usage`, or a zero sum. Same rules as [lastContextTokens], which is deliberately left
     *  untouched (many callers); `ResumeSeedParityTest` pins the two against each other. */
    private fun contextOf(obj: JsonObject): Long? {
        if (obj.bool("isSidechain") == true) return null
        val usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject ?: return null
        val total = TokenUsage(
            inputTokens = usage.long("input_tokens") ?: 0,
            outputTokens = usage.long("output_tokens") ?: 0,
            cacheCreationInputTokens = usage.long("cache_creation_input_tokens"),
            cacheReadInputTokens = usage.long("cache_read_input_tokens"),
        ).contextTokens
        return total.takeIf { it > 0 }
    }

    /** `message.model` of a MAIN-chain assistant line — null for Task-subagent turns (isSidechain: they share
     *  the file but ran the SUBAGENT's model) and `<synthetic>` placeholders (API-error/notice records). */
    private fun assistantModel(obj: JsonObject): String? {
        if (obj.bool("isSidechain") == true) return null
        return (obj["message"] as? JsonObject)?.str("model")?.takeIf { it.isNotBlank() && it != "<synthetic>" }
    }

    /** A real user turn has no `toolUseResult` and content is not a `tool_result` array. (C5) */
    private fun isRealUserTurn(obj: JsonObject): Boolean {
        if (obj.containsKey("toolUseResult")) return false
        val content = (obj["message"] as? JsonObject)?.get("content")
        if (content is JsonArray && content.isNotEmpty()) {
            val allToolResult = content.all {
                (it as? JsonObject)?.let { b -> (b["type"] as? JsonPrimitive)?.contentOrNull } == "tool_result"
            }
            if (allToolResult) return false
        }
        return true
    }

    private fun extractUserText(obj: JsonObject): String {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> content.firstNotNullOfOrNull { el ->
                (el as? JsonObject)
                    ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                    ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
            } ?: ""
            else -> ""
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
