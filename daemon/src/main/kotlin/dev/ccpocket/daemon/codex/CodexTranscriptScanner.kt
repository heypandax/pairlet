package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * Reads Codex rollout `.jsonl` headers into [SessionSummary] (no codex launch). Each line is
 * `{timestamp, type, payload}` (verified against codex 0.124): the first line is `session_meta`
 * (carrying the thread id + cwd), and real user turns are `response_item` messages with role `user`
 * whose text isn't a synthetic `<environment_context>` / `<permissions …>` block.
 */
object CodexTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L

    /** All Codex sessions whose recorded cwd is [workdir], newest-first. */
    fun scan(workdir: String): List<SessionSummary> {
        val titles = threadNames() // one index read per listing, shared across every rollout summarized below
        return CodexPaths.sessionFiles().mapNotNull { runCatching { summarize(it, workdir, titles) }.getOrNull() }
            .sortedByDescending { it.lastModified }
    }

    // Codex's session_index.jsonl (id → thread title) — memoized by the index's mtime so a directory list
    // that summarizes dozens of rollouts reads and parses it once, not per file. Append-style, last wins.
    private val titleCache = java.util.concurrent.atomic.AtomicReference<Pair<Long, Map<String, String>>?>(null)

    /** id → Codex thread title, from `$CODEX_HOME/session_index.jsonl`. Empty when the index is absent. */
    fun threadNames(): Map<String, String> {
        val file = CodexPaths.sessionIndex()
        val mtime = runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(-1L)
        titleCache.get()?.let { if (it.first == mtime) return it.second }
        val map = runCatching { readThreadNames(file) }.getOrDefault(emptyMap())
        titleCache.set(mtime to map)
        return map
    }

    /** Parse an index file into id → thread_name (last non-blank name wins); blank/absent → empty map. */
    fun readThreadNames(index: Path): Map<String, String> {
        if (!index.exists()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        index.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                val id = obj.str("id") ?: continue
                val name = obj.str("thread_name")?.takeIf { it.isNotBlank() } ?: continue
                out[id] = name // a rename rewrites the index; keep the latest
            }
        }
        return out
    }

    // rollout files are append-only, so a first-line cwd read is stable once cached (keyed by mtime
    // anyway in case a file is replaced)
    private val cwdCache = java.util.concurrent.ConcurrentHashMap<Path, Pair<Long, String?>>()

    /** Every cwd with Codex history → its newest rollout mtime. First-line reads only, memoized —
     *  cheap enough for the directory list, which must surface dirs that have no Claude history at all. */
    fun cwdsByNewest(files: List<Path> = CodexPaths.sessionFiles()): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (file in files) {
            val mtime = runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L)
            val cached = cwdCache[file]
            val cwd = if (cached != null && cached.first == mtime) cached.second else {
                runCatching { readCwd(file) }.getOrNull().also { cwdCache[file] = mtime to it }
            }
            if (cwd != null) out.merge(cwd, mtime, ::maxOf)
        }
        return out
    }

    private fun readCwd(file: Path): String? = file.bufferedReader().use { metaPayload(it)?.str("cwd") }

    /** The rollout's first-line `session_meta` payload (id/cwd/cli_version live here), or null. Advances
     *  [r] past that line so [summarize] can keep scanning turns from the same reader. */
    private fun metaPayload(r: java.io.BufferedReader): JsonObject? {
        val first = r.readLine() ?: return null
        return (runCatching { json.parseToJsonElement(first.trim()) }.getOrNull() as? JsonObject)
            ?.takeIf { it.str("type") == "session_meta" }?.obj("payload")
    }

    /** Returns null if [file] isn't a rollout for [workdir] (cheap first-line cwd filter) or has no real turn.
     *  [titles] (id → Codex thread title) supplies the session name; a listing passes one shared map. */
    fun summarize(file: Path, workdir: String?, titles: Map<String, String> = threadNames()): SessionSummary? {
        var id: String? = null
        var cwd: String? = null
        var version: String? = null
        var firstPrompt: String? = null
        var userCount = 0
        file.bufferedReader().use { r ->
            val meta = metaPayload(r) ?: return null
            id = meta.str("id"); cwd = meta.str("cwd"); version = meta.str("cli_version")
            // OS-normalized compare (slashes / trailing sep / Windows case): codex records the cwd its own
            // way, and an exact string compare silently dropped sessions on Windows (issue #19's sibling)
            val recorded = cwd
            if (workdir != null && (recorded == null || ProjectPaths.normCwd(recorded) != ProjectPaths.normCwd(workdir))) return null
            var line = r.readLine()
            while (line != null) {
                val obj = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject
                val p = obj?.takeIf { it.str("type") == "response_item" }?.obj("payload")
                if (p != null && p.str("type") == "message" && p.str("role") == "user") {
                    val t = codexMessageText(p)
                    if (t != null && !t.startsWith("<")) { // skip synthetic environment/permission blocks
                        userCount++
                        if (firstPrompt == null) firstPrompt = t
                    }
                }
                line = r.readLine()
            }
        }
        val sid = id ?: return null
        val fp = firstPrompt ?: return null
        val mtime = file.getLastModifiedTime().toMillis()
        return SessionSummary(
            sessionId = sid,
            // Codex's own thread title (session_index.jsonl) beats the first-prompt fallback — the same
            // precedence Claude's custom-title/ai-title gets. Untitled/older sessions have no index entry,
            // so they land on the first line of the first prompt exactly as before (#64).
            title = titles[sid]?.takeIf { it.isNotBlank() }
                ?: fp.lineSequence().firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
                ?: sid,
            firstPrompt = fp,
            messageCount = userCount,
            cwd = cwd ?: "",
            lastModified = mtime,
            version = version,
            live = System.currentTimeMillis() - mtime < LIVE_WINDOW_MS,
            agent = AgentKind.CODEX,
        )
    }

}
