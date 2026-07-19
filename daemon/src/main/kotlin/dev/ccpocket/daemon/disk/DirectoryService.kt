package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PathEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable

/** Enumerate candidate working directories + validate a chosen cwd. M0 = in-memory recents. */
class DirectoryService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val recents = LinkedHashSet<String>()

    private companion object { const val ACTIVE_WINDOW_MS = 30_000L } // wrote within 30s = actively executing

    fun noteRecent(workdir: String) {
        recents.remove(workdir)
        recents.add(workdir)
        while (recents.size > 10) {
            val it = recents.iterator()
            it.next()
            it.remove()
        }
    }

    /**
     * List directories that have agent history: Claude's (from ~/.claude/projects, the authoritative
     * `cwd` read from each project's newest .jsonl rather than the lossy dir-key) merged with Codex's
     * (cwds recorded in ~/.codex/sessions rollouts). A dir the user only ever ran Codex in has no
     * Claude project folder — without the merge it never appears at all, so its Codex sessions are
     * unreachable from the app.
     */
    fun listDirectories(
        root: String?,
        busyCwds: Set<String> = emptySet(),
        // the daemon's OWN live conversations per cwd (exact turn state, any backend) — see SessionRegistry.liveByCwd
        liveByCwd: Map<String, List<ActiveSession>> = emptyMap(),
    ): List<DirectoryEntry> {
        // normCwd-keyed so a tilde/absolute mismatch between OpenSession's workdir and a transcript's cwd still matches
        val liveNorm = liveByCwd.entries.groupBy({ ProjectPaths.normCwd(it.key) }, { it.value }).mapValues { (_, v) -> v.flatten() }
        val claude = claudeDirectories(busyCwds, liveNorm)
        val codex = runCatching { dev.ccpocket.daemon.codex.CodexTranscriptScanner.cwdsByNewest() }.getOrDefault(emptyMap())
        val opencode = runCatching { dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.cwdsByNewest() }.getOrDefault(emptyMap())
        // Merge all three sources
        val allExternal = codex + opencode.mapValues { (k, v) -> maxOf(v, codex[k] ?: 0L) }
        if (allExternal.isEmpty()) return claude
        val known = claude.mapTo(HashSet()) { ProjectPaths.normCwd(it.path) }
        val externalByNorm = allExternal.entries.groupBy({ ProjectPaths.normCwd(it.key) }, { it.value })
        val externalOnly = allExternal.entries
            .filter { (cwd, _) -> ProjectPaths.normCwd(cwd) !in known }
            .map { (cwd, mtime) ->
                val live = liveNorm[ProjectPaths.normCwd(cwd)].orEmpty().sortedByDescending { it.executing }
                DirectoryEntry(
                    path = cwd,
                    name = Path.of(cwd).fileName?.toString() ?: cwd,
                    isDir = true,
                    hasSessions = true,
                    recent = cwd in recents,
                    lastModified = mtime,
                    open = live.isNotEmpty(),
                    executing = live.any { it.executing },
                    busy = cwd in busyCwds,
                    activeSessionId = live.firstOrNull()?.sessionId,
                    activeSessionTitle = live.firstOrNull()?.title,
                    activeSessions = live,
                )
            }
            .distinctBy { ProjectPaths.normCwd(it.path) }
        // a dir with both histories sorts by whichever agent wrote last
        val merged = claude.map { e ->
            val extM = externalByNorm[ProjectPaths.normCwd(e.path)]?.max() ?: 0L
            if (extM > e.lastModified) e.copy(lastModified = extM) else e
        }
        return (merged + externalOnly).sortedByDescending { it.lastModified }
    }

    /** Directories with Claude history, newest-first, deduped per cwd. [liveNorm] = daemon conversations
     *  keyed by normCwd (from [listDirectories]). */
    private fun claudeDirectories(busyCwds: Set<String>, liveNorm: Map<String, List<ActiveSession>>): List<DirectoryEntry> {
        val projects = ProjectPaths.projectsRoot()
        if (!projects.isDirectory()) return emptyList()
        val dirs = Files.newDirectoryStream(projects).use { it.toList() }.filter { it.isDirectory() }
        val now = System.currentTimeMillis()
        // open = a claude process is alive here (idle or active); executing = a session here is mid-turn;
        // busy = a daemon conversation here has running background work (keep it "active" even when idle).
        val liveCwds = LiveProcesses.claudeCwds()
        return dirs.mapNotNull { dir -> scanProject(dir) }
            .map { (cwd, mtime, newest) ->
                val osOpen = cwd in liveCwds
                // the daemon's own conversations here carry EXACT turn state (isExecuting) — the mtime window
                // below can't see turn boundaries, which kept "running" on for ~30s after a turn finished.
                // Claude ones get their title/branch from their own transcript; Codex rollouts live outside
                // ~/.claude/projects, so those rows fall back to the client's generic label.
                val daemonLive = liveNorm[ProjectPaths.normCwd(cwd)].orEmpty().map { s ->
                    if (s.agent == AgentKind.CLAUDE) {
                        val sum = runCatching { TranscriptScanner.summarize(newest.resolveSibling("${s.sessionId}.jsonl")) }.getOrNull()
                        s.copy(title = sum?.title, gitBranch = sum?.gitBranch)
                    } else s
                }
                // a claude OUTSIDE the daemon (terminal): only the newest transcript can identify it — the
                // legacy single-active heuristic, kept as a fallback when the daemon doesn't own that session.
                // The filename-stem check skips the summarize entirely in the common case (the newest
                // transcript IS a daemon session), which would otherwise re-parse a growing file per call.
                val newestSid = newest.fileName?.toString()?.removeSuffix(".jsonl")
                val external = if (osOpen && daemonLive.none { it.sessionId == newestSid }) {
                    runCatching { TranscriptScanner.summarize(newest) }.getOrNull()
                        ?.takeIf { s -> daemonLive.none { it.sessionId == s.sessionId } }
                        ?.let { ActiveSession(it.sessionId, it.title, executing = now - mtime < ACTIVE_WINDOW_MS, gitBranch = it.gitBranch) }
                } else null
                val active = (daemonLive + listOfNotNull(external)).sortedByDescending { it.executing }
                val first = active.firstOrNull()
                DirectoryEntry(
                    path = cwd,
                    name = Path.of(cwd).fileName?.toString() ?: cwd,
                    isDir = true,
                    hasSessions = true,
                    recent = cwd in recents,
                    lastModified = mtime,
                    open = osOpen || daemonLive.isNotEmpty(),
                    executing = active.any { it.executing },
                    busy = cwd in busyCwds,
                    activeSessionId = first?.sessionId,
                    activeSessionTitle = first?.title,
                    gitBranch = first?.gitBranch,
                    activeSessions = active,
                )
            }
            .sortedByDescending { it.lastModified }
            .distinctBy { it.path } // keep the newest entry per cwd
    }

    /** The dir's `cwd` (from its newest transcript), that transcript's mtime, and the transcript file. */
    private fun scanProject(projectDir: Path): Triple<String, Long, Path>? {
        val newest = Files.newDirectoryStream(projectDir, "*.jsonl").use { it.toList() }
            .maxByOrNull { it.getLastModifiedTime().toMillis() } ?: return null
        val mtime = newest.getLastModifiedTime().toMillis()
        newest.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val obj = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() as? JsonObject ?: continue
                (obj["cwd"] as? JsonPrimitive)?.contentOrNull?.let { return Triple(it, mtime, newest) }
            }
        }
        return null
    }

    /** `~` / `~/...` → the daemon user's home. Clients accept `~` paths in their "new session" inputs and may
     *  send them raw; the daemon owns the expansion because only it knows the remote machine's home. */
    private fun expandTilde(path: String): String = when {
        path == "~" -> System.getProperty("user.home")
        path.startsWith("~/") || path.startsWith("~\\") -> System.getProperty("user.home") + path.drop(1)
        else -> path
    }

    fun validateWorkdir(path: String): Path? {
        val p = runCatching { Path.of(expandTilde(path)).toRealPath() }.getOrNull() ?: return null
        return if (p.isDirectory() && p.isReadable()) p else null
    }

    /**
     * List the immediate children of [subPath] (relative to [workdir]) for the composer's `@`-file
     * completion (issue #75). Directories first, then case-insensitive by name; names only, capped at
     * [limit] with a truncated flag. The target is `toRealPath()`-canonicalized and MUST stay inside the
     * (also canonical) workdir — a `..` or symlink escape returns null — so the feature can reference the
     * session's own project files and nothing wider. Returns null when the workdir isn't a readable dir
     * or the resolved child dir escapes / doesn't exist / isn't readable.
     */
    fun listPathEntries(workdir: String, subPath: String, limit: Int): Pair<List<PathEntry>, Boolean>? {
        val root = validateWorkdir(workdir) ?: return null
        // resolve against the canonical root, then re-canonicalize: toRealPath() collapses `..` and follows
        // symlinks, and startsWith(root) rejects anything that lands outside the project subtree.
        val target = runCatching { root.resolve(subPath).normalize().toRealPath() }.getOrNull() ?: return null
        if (!target.startsWith(root)) return null
        if (!target.isDirectory() || !target.isReadable()) return null
        val children = runCatching { Files.newDirectoryStream(target).use { it.toList() } }.getOrNull() ?: return null
        val sorted = children
            .mapNotNull { p -> p.fileName?.toString()?.let { name -> PathEntry(name, p.isDirectory()) } }
            .sortedWith(compareByDescending<PathEntry> { it.isDir }.thenBy { it.name.lowercase() })
        val cap = limit.coerceIn(1, 2_000)
        return sorted.take(cap) to (sorted.size > cap)
    }

    /**
     * Like [validateWorkdir], but for STARTING A NEW PROJECT (issue #7 follow-up): if [path] doesn't exist yet,
     * create it as a single new leaf directory under an already-existing, writable parent, then return its real
     * path. Only one level is created (no `mkdir -p` of a deep tree) and the parent must already be a writable
     * directory — so a typo'd path fails fast instead of materialising a stray tree. An existing readable
     * directory behaves exactly like [validateWorkdir]; returns null when the path is unusable (it exists but
     * isn't a readable dir, the parent is missing or not writable, or creation failed).
     *
     * A paired phone can already create folders through the approval-gated terminal (issue #3), so creating the
     * one named project directory here adds no new trust boundary.
     */
    fun validateOrCreateWorkdir(path: String): Path? {
        validateWorkdir(path)?.let { return it }                       // already a readable directory → done
        val raw = runCatching { Path.of(expandTilde(path)).normalize() }.getOrNull() ?: return null
        if (raw.exists()) return null                                  // exists but not a readable dir (e.g. a file)
        val leaf = raw.fileName ?: return null                         // need a leaf name to create
        val parent = raw.parent?.let { p -> runCatching { p.toRealPath() }.getOrNull() } ?: return null
        if (!parent.isDirectory() || !parent.isWritable()) return null // parent must already exist & be writable
        return runCatching { Files.createDirectory(parent.resolve(leaf)).toRealPath() }.getOrNull()
    }
}
