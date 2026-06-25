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
}
