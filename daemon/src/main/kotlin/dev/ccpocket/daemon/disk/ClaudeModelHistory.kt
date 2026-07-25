package dev.ccpocket.daemon.disk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.bufferedReader
import kotlin.io.path.isDirectory

/**
 * Claude model ids this machine has actually used, newest first (issue #167, direction ③).
 *
 * This is deliberately separate from [UsageService]. The usage dashboard has the opposite contract:
 * a caller-selected 1–90 day window, token-descending order, and six bars at most. A model-picker
 * fallback needs the full on-disk lifetime, every distinct id, and recent-use order.
 *
 * The scan is only invoked after a configured gateway fails to answer `/v1/models`. It is cached
 * briefly because that failure is negatively cached too, and repeatedly opening the picker must not
 * repeatedly walk every transcript. The result is bounded before it can ride a protocol frame.
 */
object ClaudeModelHistory {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Entry(val models: List<String>, val atMs: Long)

    private val cache = ConcurrentHashMap<Path, Entry>()

    internal fun recent(
        projectsRoot: Path = ProjectPaths.projectsRoot(),
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val key = projectsRoot.toAbsolutePath().normalize()
        cache[key]?.takeIf { nowMs - it.atMs < TTL_MS }?.let { return it.models }
        val models = scan(projectsRoot)
        cache[key] = Entry(models, nowMs)
        return models
    }

    private fun scan(projectsRoot: Path): List<String> {
        if (!projectsRoot.isDirectory()) return emptyList()
        val lastUsed = HashMap<String, Long>()
        runCatching {
            Files.newDirectoryStream(projectsRoot).use { projectDirs ->
                for (projectDir in projectDirs) {
                    if (!projectDir.isDirectory()) continue
                    val files = runCatching {
                        Files.newDirectoryStream(projectDir, "*.jsonl").use { it.toList() }
                    }.getOrDefault(emptyList())
                    for (file in files) runCatching {
                        val fileTime = Files.getLastModifiedTime(file).toMillis()
                        file.bufferedReader().useLines { lines ->
                            for (raw in lines) {
                                if ("\"assistant\"" !in raw || "\"model\"" !in raw) continue
                                val obj = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() as? JsonObject ?: continue
                                if (obj.str("type") != "assistant") continue
                                val message = obj["message"] as? JsonObject ?: continue
                                val model = message.str("model")?.trim()?.takeIf(::usableId) ?: continue
                                val usedAt = obj.str("timestamp")?.let(::epochMillis) ?: fileTime
                                lastUsed.merge(model, usedAt, ::maxOf)
                            }
                        }
                    }
                }
            }
        }
        return lastUsed.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(MAX_IDS)
            .map { it.key }
    }

    private fun usableId(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= MAX_ID_LEN &&
            id.none(Char::isISOControl) &&
            !(id.startsWith("<") && id.endsWith(">"))

    private fun epochMillis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    internal fun clearCache() = cache.clear()

    private const val TTL_MS = 60_000L
    private const val MAX_IDS = 200
    private const val MAX_ID_LEN = 128
}
