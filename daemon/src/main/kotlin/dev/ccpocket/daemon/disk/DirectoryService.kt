package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PathEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable

/** Enumerate candidate working directories + validate a chosen cwd. M0 = in-memory recents.
 *  The constructor params are test seams only (issue #184: the cross-backend merge/dedup needs
 *  controllable sources); production always uses the defaults. */
class DirectoryService(
    private val projectsRoot: () -> Path = ProjectPaths::projectsRoot,
    private val codexCwds: () -> Map<String, Long> = { dev.ccpocket.daemon.codex.CodexTranscriptScanner.cwdsByNewest() },
    private val opencodeCwds: () -> Map<String, Long> = { dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.cwdsByNewest() },
) {
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
     * (cwds recorded in ~/.codex/sessions rollouts) and OpenCode's (directories in its SQLite session
     * table). A dir the user only ever ran Codex in has no Claude project folder — without the merge it
     * never appears at all, so its Codex sessions are unreachable from the app.
     *
     * Cross-source identity is [ProjectPaths.canonicalKey] (realpath-based): each source records the SAME
     * dir in its own spelling (tilde, trailing/doubled separators, macOS /var↔/private/var symlinks), and
     * the weaker string compare let every variant through as its own row — issue #184's duplicate "~"
     * project, whose second row then listed nothing because the session match used the same weak key.
     *
     * [includeOpencode]=false — a client that never declared AgentKind.OPENCODE support — skips the
     * OpenCode source entirely: that client's session list strips opencode sessions anyway (emitSessions),
     * so a row ONLY opencode sustains would open onto a bare "New session" screen (#184 mechanism ②). A dir
     * that also has claude/codex history keeps its row through those sources.
     */
    fun listDirectories(
        root: String?,
        busyCwds: Set<String> = emptySet(),
        // the daemon's OWN live conversations per cwd (exact turn state, any backend) — see SessionRegistry.liveByCwd
        liveByCwd: Map<String, List<ActiveSession>> = emptyMap(),
        includeOpencode: Boolean = true,
    ): List<DirectoryEntry> {
        // canonical-keyed so ANY spelling mismatch (tilde, symlink, separators) between OpenSession's
        // workdir and a transcript's recorded cwd still matches
        val liveNorm = liveByCwd.entries.groupBy({ ProjectPaths.canonicalKey(it.key) }, { it.value }).mapValues { (_, v) -> v.flatten() }
        val claude = claudeDirectories(busyCwds, liveNorm)
        val codex = runCatching(codexCwds).getOrDefault(emptyMap())
        val opencode = if (includeOpencode) runCatching(opencodeCwds).getOrDefault(emptyMap()) else emptyMap()
        // issue #188: the App's agent filter needs PROJECT-level provenance, not just the backend of any
        // currently-live session. Keep it additive on DirectoryEntry so each client can apply its own
        // persisted filter without turning that preference into daemon-global state.
        val externalAgentsByKey = HashMap<String, MutableSet<AgentKind>>()
        codex.keys.forEach { cwd ->
            externalAgentsByKey.getOrPut(ProjectPaths.canonicalKey(cwd)) { linkedSetOf() }.add(AgentKind.CODEX)
        }
        opencode.keys.forEach { cwd ->
            externalAgentsByKey.getOrPut(ProjectPaths.canonicalKey(cwd)) { linkedSetOf() }.add(AgentKind.OPENCODE)
        }
        // merge both external sources; keys keep each source's raw spelling here — grouped canonically below
        val allExternal = HashMap<String, Long>()
        codex.forEach { (cwd, mtime) -> allExternal.merge(cwd, mtime, ::maxOf) }
        opencode.forEach { (cwd, mtime) -> allExternal.merge(cwd, mtime, ::maxOf) }
        if (allExternal.isEmpty()) return claude
        val known = claude.mapTo(HashSet()) { ProjectPaths.canonicalKey(it.path) }
        // spelling variants of one dir collapse into a group; the group's mtime is its max
        val externalByKey = allExternal.entries.groupBy({ ProjectPaths.canonicalKey(it.key) }, { it.value })
        val externalOnly = allExternal.entries
            .sortedByDescending { it.value } // the newest variant's spelling becomes the row identity
            .distinctBy { ProjectPaths.canonicalKey(it.key) }
            .filter { (cwd, _) -> ProjectPaths.canonicalKey(cwd) !in known }
            .map { (cwd, _) ->
                val key = ProjectPaths.canonicalKey(cwd)
                val live = liveNorm[key].orEmpty().sortedByDescending { it.executing }
                DirectoryEntry(
                    path = cwd,
                    name = Path.of(cwd).fileName?.toString() ?: cwd,
                    isDir = true,
                    hasSessions = true,
                    recent = cwd in recents,
                    lastModified = externalByKey[key]?.max() ?: 0L,
                    open = live.isNotEmpty(),
                    executing = live.any { it.executing },
                    busy = cwd in busyCwds,
                    activeSessionId = live.firstOrNull()?.sessionId,
                    activeSessionTitle = live.firstOrNull()?.title,
                    activeSessions = live,
                    sessionAgents = (externalAgentsByKey[key].orEmpty() + live.map { it.agent })
                        .distinct().sortedBy { it.ordinal },
                )
            }
        // a dir with both histories sorts by whichever agent wrote last
        val merged = claude.map { e ->
            val key = ProjectPaths.canonicalKey(e.path)
            val extM = externalByKey[key]?.max() ?: 0L
            e.copy(
                lastModified = maxOf(e.lastModified, extM),
                sessionAgents = (e.sessionAgents + externalAgentsByKey[key].orEmpty() + e.activeSessions.map { it.agent })
                    .distinct().sortedBy { it.ordinal },
            )
        }
        return (merged + externalOnly).sortedByDescending { it.lastModified }
    }

    /** Directories with Claude history, newest-first, deduped per cwd. [liveNorm] = daemon conversations
     *  keyed by [ProjectPaths.canonicalKey] (from [listDirectories]). */
    private fun claudeDirectories(busyCwds: Set<String>, liveNorm: Map<String, List<ActiveSession>>): List<DirectoryEntry> {
        val projects = projectsRoot()
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
                val daemonLive = liveNorm[ProjectPaths.canonicalKey(cwd)].orEmpty().map { s ->
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
                    sessionAgents = (listOf(AgentKind.CLAUDE) + active.map { it.agent })
                        .distinct().sortedBy { it.ordinal },
                )
            }
            .sortedByDescending { it.lastModified }
            // keep the newest entry per cwd — canonical, since two project dirs can record variant
            // spellings of one real dir (e.g. a claude launched via a symlinked path)
            .distinctBy { ProjectPaths.canonicalKey(it.path) }
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

    fun validateWorkdir(path: String): Path? {
        val p = runCatching { Path.of(ProjectPaths.expandTilde(path)).toRealPath() }.getOrNull() ?: return null
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
     * The machine's filesystem roots in native form ("/" on Unix; "C:\", "D:\" … on Windows) — the #176
     * root switcher's source, riding as [dev.ccpocket.protocol.PathEntries.roots] on the owner's "~"
     * home-anchor reply. Enumeration only, no readability probe: touching an unready removable drive can
     * block for seconds on Windows, and a root that turns out unreadable simply answers ok=false when the
     * picker lists it (the same error row any unreadable folder gets).
     */
    fun listFsRoots(): List<String> =
        runCatching { FileSystems.getDefault().rootDirectories.map { it.toString() } }.getOrDefault(emptyList())

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
        val raw = runCatching { Path.of(ProjectPaths.expandTilde(path)).normalize() }.getOrNull() ?: return null
        if (raw.exists()) return null                                  // exists but not a readable dir (e.g. a file)
        val leaf = raw.fileName ?: return null                         // need a leaf name to create
        val parent = raw.parent?.let { p -> runCatching { p.toRealPath() }.getOrNull() } ?: return null
        if (!parent.isDirectory() || !parent.isWritable()) return null // parent must already exist & be writable
        return runCatching { Files.createDirectory(parent.resolve(leaf)).toRealPath() }.getOrNull()
    }
}
