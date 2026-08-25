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
 * whose text isn't a Codex-injected context block (see [isSyntheticUserText]).
 */
object CodexTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L

    /** Last runtime settings persisted by Codex in a rollout. These are the source of truth when the
     * desktop owns a session and cc-pocket is only observing it. */
    data class RuntimeState(
        val model: String? = null,
        val contextWindow: Long? = null,
        val contextUsed: Long? = null,
    )

    /** All Codex sessions whose recorded cwd is [workdir], newest-first. */
    fun scan(workdir: String): List<SessionSummary> {
        val titles = threadNames() // one index read per listing, shared across every rollout summarized below
        return CodexPaths.sessionFiles().mapNotNull { runCatching { summarize(it, workdir, titles) }.getOrNull() }
            .sortedByDescending { it.lastModified }
            // Newer codex CLIs write a SECOND rollout on resume (`rollout-<ts>-<origId>_<resumeId>.jsonl`)
            // whose meta carries the ORIGINAL session id — one session, two files. Listing both rows with
            // one id crashed the phone's session list outright (LazyColumn duplicate key, 2026-08-25).
            // Newest file wins: it is the live continuation, and resume must target it anyway.
            .distinctBy { it.sessionId }
    }

    /**
     * The newest resumable Codex session for each externally-live cwd. Unlike [scan], this is called by
     * the 10-second project-list refresh, so it walks the rollout tree ONCE and stops reading each matching
     * file after the first real user prompt (messageCount is intentionally only a lower bound here). A
     * full session list still uses [scan]; the active row only needs id/title/mtime/agent.
     *
     * Returned keys preserve the caller's cwd spelling; matching itself uses [ProjectPaths.canonicalKey].
     *
     * A cwd is answered by its NEWEST rollout, prompt or no prompt (PR #296 review). Falling back to an
     * older rollout when the newest one had no real user turn yet — a terminal sitting at a fresh `codex`
     * prompt, or a session the phone just opened — resurrected yesterday's finished session as this
     * project's "active" row (a ghost second row downstream, whose id differs from the daemon's), and left
     * that cwd in [remaining] forever, so this 10-second refresh reopened and parsed all 800 rollouts on
     * every tick instead of stopping at the first hit.
     */
    fun activeSummaries(
        workdirs: Set<String>,
        files: List<Path> = CodexPaths.sessionFiles(),
    ): Map<String, SessionSummary> {
        if (workdirs.isEmpty()) return emptyMap()
        val requested = workdirs.associateBy(ProjectPaths::canonicalKey)
        val remaining = requested.keys.toMutableSet()
        val out = LinkedHashMap<String, SessionSummary>()
        val titles = threadNames()
        for (file in files) {
            if (remaining.isEmpty()) break
            val hit = runCatching { summarizeActive(file, remaining, titles) }.getOrNull() ?: continue
            val requestedCwd = requested[hit.first] ?: continue
            out[requestedCwd] = hit.second
            remaining.remove(hit.first)
        }
        return out
    }

    /** Canonical cwd key + lightweight summary, or null when this rollout is not a requested live cwd.
     *  Only the cwd decides that: a matching rollout with no real user turn yet still answers for its cwd,
     *  with a blank title (the index title when Codex already named the thread) — see [activeSummaries]. */
    private fun summarizeActive(
        file: Path,
        wantedKeys: Set<String>,
        titles: Map<String, String>,
    ): Pair<String, SessionSummary>? {
        var id: String? = null
        var cwd: String? = null
        var version: String? = null
        var firstPrompt: String? = null
        file.bufferedReader().use { r ->
            val meta = metaPayload(r) ?: return null
            id = meta.str("id"); cwd = meta.str("cwd"); version = meta.str("cli_version")
            val recorded = cwd ?: return null
            val key = ProjectPaths.canonicalKey(recorded)
            if (key !in wantedKeys) return null
            var line = r.readLine()
            while (line != null && firstPrompt == null) {
                val obj = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject
                val p = obj?.takeIf { it.str("type") == "response_item" }?.obj("payload")
                if (p != null && p.str("type") == "message" && p.str("role") == "user") {
                    codexMessageText(p)?.takeIf { !isSyntheticUserText(it) }?.let { firstPrompt = it }
                }
                line = r.readLine()
            }
        }
        val sid = id ?: return null
        val recorded = cwd ?: return null
        val fp = firstPrompt
        val mtime = file.getLastModifiedTime().toMillis()
        return ProjectPaths.canonicalKey(recorded) to SessionSummary(
            sessionId = sid,
            // Blank rather than the session UUID when there is no prompt AND no index title: the phone
            // renders its generic session label for a blank title, while a raw UUID would be shown as-is.
            title = titles[sid]?.takeIf { it.isNotBlank() }
                ?: fp?.let { it.lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim()?.take(60) ?: sid }
                ?: "",
            firstPrompt = fp ?: "",
            messageCount = if (fp != null) 1 else 0,
            cwd = recorded,
            lastModified = mtime,
            version = version,
            live = System.currentTimeMillis() - mtime < LIVE_WINDOW_MS,
            agent = AgentKind.CODEX,
        )
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

    /**
     * A bounded LRU keyed by file path and stamped with that file's mtime. Rollouts are append-only, so a
     * moved mtime is the ONLY way a parse of one can go stale — the same reasoning [cwdCache] already used,
     * generalized here for the two full-file parses below.
     *
     * The stamp is the raw [java.nio.file.attribute.FileTime] rather than millis: nanosecond precision where
     * the filesystem has it makes a same-millisecond rewrite visible instead of silently cached.
     */
    private class MtimeMemo<V : Any>(private val max: Int) {
        private val map = object : LinkedHashMap<Path, Pair<java.nio.file.attribute.FileTime, V>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Pair<java.nio.file.attribute.FileTime, V>>) = size > max
        }

        @Synchronized
        fun get(file: Path, stamp: java.nio.file.attribute.FileTime?): V? =
            if (stamp == null) null else map[file]?.takeIf { it.first == stamp }?.second

        @Synchronized
        fun put(file: Path, stamp: java.nio.file.attribute.FileTime?, value: V) {
            if (stamp != null) map[file] = stamp to value
        }

        @Synchronized
        fun clear() = map.clear()
    }

    private fun stampOf(file: Path): java.nio.file.attribute.FileTime? =
        runCatching { file.getLastModifiedTime() }.getOrNull()

    private val runtimeCache = MtimeMemo<RuntimeState>(MEMO_MAX)

    /** Read the newest Codex model and token metadata from a rollout. Model settings are written in
     * `turn_context`; token counts are `event_msg/token_count`. Older rollouts simply return null fields.
     *
     * Memoized by (path, mtime): a Codex session open asks for this twice (resumeContextTokens +
     * resumeModel) and an observe tick asks again, each time re-parsing a file that can reach megabytes. */
    fun runtimeState(file: Path): RuntimeState {
        val stamp = stampOf(file)
        runtimeCache.get(file, stamp)?.let { return it }
        var state = RuntimeState()
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val obj = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() as? JsonObject ?: continue
                state = mergeRuntimeState(state, obj)
            }
        }
        runtimeCache.put(file, stamp, state)
        return state
    }

    private fun mergeRuntimeState(current: RuntimeState, obj: JsonObject): RuntimeState {
        val payload = obj.obj("payload") ?: return current
        val model = when (obj.str("type")) {
            "turn_context" -> payload.str("model")
                ?: payload.obj("collaboration_mode")?.obj("settings")?.str("model")
            "world_state" -> payload.obj("state")?.str("model")
            "event_msg" -> payload.obj("thread_settings")?.str("model")
            else -> null
        }
        val info = payload.obj("info")
        val window = payload.long("model_context_window") ?: info?.long("model_context_window")
        val used = info?.obj("last_token_usage")?.long("total_tokens")
        return RuntimeState(
            model = model ?: current.model,
            contextWindow = window ?: current.contextWindow,
            contextUsed = used ?: current.contextUsed,
        )
    }

    /** The rollout's first-line `session_meta` payload (id/cwd/cli_version live here), or null. Advances
     *  [r] past that line so [summarize] can keep scanning turns from the same reader. */
    private fun metaPayload(r: java.io.BufferedReader): JsonObject? {
        val first = r.readLine() ?: return null
        return (runCatching { json.parseToJsonElement(first.trim()) }.getOrNull() as? JsonObject)
            ?.takeIf { it.str("type") == "session_meta" }?.obj("payload")
    }

    /** Everything a rollout FILE alone decides. The finished [SessionSummary] can't be cached as-is: it also
     *  depends on the caller's workdir, on the shared title map, and on `live`, which is a function of now —
     *  so only the parse (the expensive part) is memoized. */
    private data class Parsed(
        val id: String?,
        val cwd: String?,
        val version: String?,
        val firstPrompt: String?,
        val userCount: Int,
        val model: String?,
    )

    private val parseCache = MtimeMemo<Parsed>(MEMO_MAX)

    /** Full-file parse behind [summarize], keeping its cheap first-line cwd filter: a rollout for another
     *  project must still cost one line, not a whole read (a session listing runs this over ~800 files).
     *  Returns null for those, and for a file without a session_meta header — neither is worth caching. */
    private fun parseRollout(file: Path, workdir: String?): Parsed? {
        var id: String? = null
        var cwd: String? = null
        var version: String? = null
        var firstPrompt: String? = null
        var userCount = 0
        var runtime = RuntimeState()
        file.bufferedReader().use { r ->
            val meta = metaPayload(r) ?: return null
            id = meta.str("id"); cwd = meta.str("cwd"); version = meta.str("cli_version")
            // Canonical-key compare (slashes / trailing sep / Windows case / symlinks / tilde): codex records
            // the cwd its own way, and an exact string compare silently dropped sessions on Windows (issue
            // #19's sibling). Since #184 merges spelling variants into ONE project row, the row's realpath'd
            // workdir must still match a variant-spelled rollout — same key DirectoryService merges by.
            val recorded = cwd
            if (workdir != null && (recorded == null || ProjectPaths.canonicalKey(recorded) != ProjectPaths.canonicalKey(workdir))) return null
            var line = r.readLine()
            while (line != null) {
                val obj = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject
                if (obj != null) runtime = mergeRuntimeState(runtime, obj)
                val p = obj?.takeIf { it.str("type") == "response_item" }?.obj("payload")
                if (p != null && p.str("type") == "message" && p.str("role") == "user") {
                    val t = codexMessageText(p)
                    // skip Codex-injected context turns (env/permission wrappers, AGENTS.md dump, @-file
                    // expansion) — they aren't real user turns and were poisoning the title/preview
                    if (t != null && !isSyntheticUserText(t)) {
                        userCount++
                        if (firstPrompt == null) firstPrompt = t
                    }
                }
                line = r.readLine()
            }
        }
        return Parsed(id, cwd, version, firstPrompt, userCount, runtime.model)
    }

    /** Returns null if [file] isn't a rollout for [workdir] (cheap first-line cwd filter) or has no real turn.
     *  [titles] (id → Codex thread title) supplies the session name; a listing passes one shared map. */
    fun summarize(file: Path, workdir: String?, titles: Map<String, String> = threadNames()): SessionSummary? {
        val stamp = stampOf(file)
        val parsed = parseCache.get(file, stamp)
            ?: parseRollout(file, workdir)?.also { parseCache.put(file, stamp, it) }
            ?: return null
        // A cached parse still re-checks the caller's workdir: the memo is keyed by the FILE, and the same
        // rollout is summarized by both a project-scoped listing and the observe/resume paths.
        val recorded = parsed.cwd
        if (workdir != null && (recorded == null || ProjectPaths.canonicalKey(recorded) != ProjectPaths.canonicalKey(workdir))) return null
        val sid = parsed.id ?: return null
        val fp = parsed.firstPrompt ?: return null
        val version = parsed.version
        val userCount = parsed.userCount
        val cwd = recorded
        val mtime = stamp?.toMillis() ?: file.getLastModifiedTime().toMillis()
        return SessionSummary(
            sessionId = sid,
            // Codex's own thread title (session_index.jsonl) beats the first-prompt fallback — the same
            // precedence Claude's custom-title/ai-title gets. Untitled/older sessions have no index entry,
            // so they land on the first line of the first prompt exactly as before (#64).
            title = titles[sid]?.takeIf { it.isNotBlank() }
                ?: fp.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                ?: sid,
            firstPrompt = fp,
            messageCount = userCount,
            cwd = cwd ?: "",
            lastModified = mtime,
            version = version,
            live = System.currentTimeMillis() - mtime < LIVE_WINDOW_MS,
            agent = AgentKind.CODEX,
            model = parsed.model,
        )
    }

    /** Cross-test isolation: every memo here is process-wide state on an object singleton. */
    internal fun clearForTest() {
        parseCache.clear()
        runtimeCache.clear()
        cwdCache.clear()
        titleCache.set(null)
    }

    /** Sized to one full listing ([CodexPaths.sessionFiles]'s own cap), so a session list never evicts
     *  entries it is still walking. */
    private const val MEMO_MAX = 800
}
