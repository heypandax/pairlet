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
        return files.mapNotNull { runCatching { summarize(it) }.getOrNull() }
            .sortedByDescending { it.lastModified }
    }

    fun summarize(file: Path): SessionSummary? {
        val sessionId = file.fileName.toString().removeSuffix(".jsonl")
        var firstPrompt: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        var version: String? = null
        var aiTitle: String? = null
        var customTitle: String? = null // the user's rename, persisted by Claude as a `custom-title` record (issue #14)
        var model: String? = null       // last assistant turn's model — same rules as [lastModel], captured in this pass
        var userCount = 0

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
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
                }
            }
        }

        if (firstPrompt == null && aiTitle == null && customTitle == null) return null
        val mtime = file.getLastModifiedTime().toMillis()
        val fp = firstPrompt ?: ""
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
            cwd = cwd ?: "",
            lastModified = mtime,
            gitBranch = gitBranch,
            version = version,
            live = System.currentTimeMillis() - mtime < LIVE_WINDOW_MS,
            model = model,
        )
    }

    /**
     * Context tokens the LAST completed assistant turn left in the window — `input + cache_read +
     * cache_creation` of its `message.usage` (output isn't in-window yet). Mirrors the live TurnDone
     * sum so the phone's usage statusline reads the same on resume as mid-session. Null when the file
     * is absent or no turn carries usage yet.
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
