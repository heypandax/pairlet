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
     * the 10-second project-list refresh, so it walks the rollout tree ONCE, skips every file whose
     * (memoized) cwd isn't wanted without opening it, and reads at most one file per requested cwd — a read
     * the shared [scanCache] then hands to the observe/resume paths for free. A full session list still uses
     * [scan]; the active row only needs id/title/mtime/agent.
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
            // Prefilter on the memoized first-line cwd (issue #300): the same 10-second refresh has just
            // called [cwdsByNewest] over this very list, so every unchanged file's cwd is already in
            // [cwdCache] — a stat, no open. Opening each of the N newer other-project rollouts that sit
            // ahead of a live project's newest one, only to discard them on their first line, is what this
            // costs otherwise, every tick, forever.
            val key = cwdOf(file)?.let(ProjectPaths::canonicalKey) ?: continue
            if (key !in remaining) continue
            val summary = runCatching { summarizeActive(file, titles) }.getOrNull() ?: continue
            val requestedCwd = requested[key] ?: continue
            out[requestedCwd] = summary
            remaining.remove(key)
        }
        return out
    }

    /** Lightweight summary of a rollout already known (by [activeSummaries]' cwd prefilter) to belong to a
     *  requested live cwd, or null when it has no usable `session_meta`. A matching rollout with no real user
     *  turn yet still answers for its cwd, with a blank title (the index title when Codex already named the
     *  thread) — see [activeSummaries]. The read itself goes through the shared [scanCache], so the observe
     *  tick and this refresh pay for at most one parse of the file per mtime between them. */
    private fun summarizeActive(file: Path, titles: Map<String, String>): SessionSummary? {
        val stamp = stampOf(file)
        val parsed = scanned(file, stamp, workdir = null)?.parsed ?: return null
        val sid = parsed.id ?: return null
        val recorded = parsed.cwd ?: return null
        val fp = parsed.firstPrompt
        val version = parsed.version
        val mtime = stamp?.toMillis() ?: file.getLastModifiedTime().toMillis()
        return SessionSummary(
            sessionId = sid,
            // Blank rather than the session UUID when there is no prompt AND no index title: the phone
            // renders its generic session label for a blank title, while a raw UUID would be shown as-is.
            title = titles[sid]?.takeIf { it.isNotBlank() }
                ?: fp?.let { it.lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim()?.take(60) ?: sid }
                ?: "",
            firstPrompt = fp ?: "",
            messageCount = parsed.userCount,
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
            val cwd = cwdOf(file, mtime)
            if (cwd != null) out.merge(cwd, mtime, ::maxOf)
        }
        return out
    }

    /** The rollout's recorded cwd, memoized by (path, mtime) — a first-line read at most once per version of
     *  the file. Shared by [cwdsByNewest] and [activeSummaries]' prefilter, which run back-to-back on the
     *  same file list every 10 seconds, so the second of them pays a stat and nothing more. */
    private fun cwdOf(file: Path, mtime: Long = runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L)): String? {
        cwdCache[file]?.let { if (it.first == mtime) return it.second }
        return runCatching { readCwd(file) }.getOrNull().also { cwdCache[file] = mtime to it }
    }

    private fun readCwd(file: Path): String? = file.bufferedReader().use { metaPayload(it)?.str("cwd") }

    /**
     * A bounded LRU keyed by file path and stamped with that file's mtime. Rollouts are append-only, so a
     * moved mtime is the ONLY way a parse of one can go stale — the same reasoning [cwdCache] already used,
     * generalized here for the full-file parse below.
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

    /** Read the newest Codex model and token metadata from a rollout. Model settings are written in
     * `turn_context`; token counts are `event_msg/token_count`. Older rollouts simply return null fields.
     *
     * Served by the same (path, mtime) memo as [summarize] — one read answers both (issue #300). A Codex
     * session open asks three accessors (title, model, contextTokens) about the same megabyte-sized file,
     * and every observe tick asks again; before the merge, the summary pass computed this state line by
     * line and then threw everything but the model away, so each open cost two full parses. */
    fun runtimeState(file: Path): RuntimeState {
        val stamp = stampOf(file)
        // No workdir filter and no session_meta requirement: a header-less rollout has no summary, but its
        // turn_context/token_count lines are still the truth about the model and context in use.
        return scanned(file, stamp, workdir = null, requireMeta = false)?.runtime ?: RuntimeState()
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
    )

    /** Everything ONE read of a rollout yields: the summary material ([parsed], null when the file has no
     *  `session_meta` header) and the runtime settings that were being merged line by line anyway. Keeping
     *  them together is the whole of issue #300: the summary path and the model/context path used to be two
     *  separate full parses of the same bytes, always requested within the same session open or tick. */
    private data class Scanned(val parsed: Parsed?, val runtime: RuntimeState)

    private val scanCache = MtimeMemo<Scanned>(MEMO_MAX)

    /** Cached [scanRollout]. Null (and nothing cached) only for the early exits that never read the body. */
    private fun scanned(
        file: Path,
        stamp: java.nio.file.attribute.FileTime?,
        workdir: String?,
        requireMeta: Boolean = true,
    ): Scanned? = scanCache.get(file, stamp)
        ?: scanRollout(file, workdir, requireMeta)?.also { scanCache.put(file, stamp, it) }

    /** The single full read of a rollout, keeping the cheap first-line filters it always had: a rollout for
     *  another project (or, when [requireMeta], one with no session_meta header) must still cost one line,
     *  not a whole read — a session listing runs this over ~800 files. Returns null for those, uncached:
     *  what a one-line read decided must not be remembered as if the body had been seen. */
    private fun scanRollout(file: Path, workdir: String?, requireMeta: Boolean): Scanned? {
        var id: String? = null
        var cwd: String? = null
        var version: String? = null
        var firstPrompt: String? = null
        var userCount = 0
        var hasMeta = false
        var runtime = RuntimeState()
        file.bufferedReader().use { r ->
            val first = r.readLine()
            val firstObj = first?.let { runCatching { json.parseToJsonElement(it.trim()) }.getOrNull() as? JsonObject }
            val meta = firstObj?.takeIf { it.str("type") == "session_meta" }?.obj("payload")
            if (meta == null) {
                // No header: nothing to summarize and no cwd to match, so a caller that needs either stops
                // here. [runtimeState] needs neither and reads on — including this very first line.
                if (requireMeta || workdir != null) return null
            } else {
                hasMeta = true
                id = meta.str("id"); cwd = meta.str("cwd"); version = meta.str("cli_version")
                // Canonical-key compare (slashes / trailing sep / Windows case / symlinks / tilde): codex records
                // the cwd its own way, and an exact string compare silently dropped sessions on Windows (issue
                // #19's sibling). Since #184 merges spelling variants into ONE project row, the row's realpath'd
                // workdir must still match a variant-spelled rollout — same key DirectoryService merges by.
                val recorded = cwd
                if (workdir != null && (recorded == null || ProjectPaths.canonicalKey(recorded) != ProjectPaths.canonicalKey(workdir))) return null
            }
            // The first line is merged like any other — the runtime pass this replaced merged every line,
            // and only a line's own `type` decides whether it carries settings. Header or not, same rule.
            if (firstObj != null) runtime = mergeRuntimeState(runtime, firstObj)
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
        return Scanned(
            parsed = if (hasMeta) Parsed(id, cwd, version, firstPrompt, userCount) else null,
            runtime = runtime,
        )
    }

    /** Returns null if [file] isn't a rollout for [workdir] (cheap first-line cwd filter) or has no real turn.
     *  [titles] (id → Codex thread title) supplies the session name; a listing passes one shared map. */
    fun summarize(file: Path, workdir: String?, titles: Map<String, String> = threadNames()): SessionSummary? {
        val stamp = stampOf(file)
        val scan = scanned(file, stamp, workdir) ?: return null
        val parsed = scan.parsed ?: return null
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
            model = scan.runtime.model,
        )
    }

    /** Cross-test isolation: every memo here is process-wide state on an object singleton. */
    internal fun clearForTest() {
        scanCache.clear()
        cwdCache.clear()
        titleCache.set(null)
    }

    /** Sized to one full listing ([CodexPaths.sessionFiles]'s own cap), so a session list never evicts
     *  entries it is still walking. */
    private const val MEMO_MAX = 800
}
