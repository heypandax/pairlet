package dev.ccpocket.daemon.kimi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * Reads Kimi's global `session_index.jsonl` (issue #206). Each line indexes ONE session with its id, its
 * on-disk directory, and the workDir it ran in. The EXACT field spellings weren't fully documented at
 * design time (probe V2), so every getter tries the plausible variants and the reader tolerates any
 * unknown extra keys — a schema drift downgrades a field to null, never a crash.
 *
 * Memoized by the index file's mtime: a directory list summarizes many sessions but reads/parses the
 * index once (mirrors CodexTranscriptScanner's title cache).
 */
object KimiSessionIndex {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Entry(val sessionId: String, val sessionDir: String?, val workDir: String?)

    // keyed by (index path, mtime) so a re-read only reparses on change; the path in the key keeps distinct
    // index files (tests) from colliding when they share a millisecond mtime.
    private val cache = java.util.concurrent.atomic.AtomicReference<Triple<String, Long, List<Entry>>?>(null)

    fun entries(index: Path = KimiPaths.sessionIndex()): List<Entry> {
        val key = index.toString()
        val mtime = runCatching { index.getLastModifiedTime().toMillis() }.getOrDefault(-1L)
        cache.get()?.let { if (it.first == key && it.second == mtime) return it.third }
        val list = runCatching { read(index) }.getOrDefault(emptyList())
        cache.set(Triple(key, mtime, list))
        return list
    }

    private fun read(index: Path): List<Entry> {
        if (!index.exists()) return emptyList()
        val out = ArrayList<Entry>()
        index.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                val id = obj.firstStr("sessionId", "session_id", "id") ?: continue
                out += Entry(
                    sessionId = id,
                    sessionDir = obj.firstStr("sessionDir", "session_dir", "dir", "path"),
                    workDir = obj.firstStr("workDir", "work_dir", "workdir", "cwd"),
                )
            }
        }
        return out
    }

    private fun JsonObject.firstStr(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it)?.takeIf { s -> s.isNotBlank() } }
}
