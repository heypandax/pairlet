package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.DirectoryEntry
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
    fun listDirectories(root: String?, busyCwds: Set<String> = emptySet()): List<DirectoryEntry> {
        val claude = claudeDirectories(busyCwds)
        val codex = runCatching { dev.ccpocket.daemon.codex.CodexTranscriptScanner.cwdsByNewest() }.getOrDefault(emptyMap())
        if (codex.isEmpty()) return claude
        val known = claude.mapTo(HashSet()) { ProjectPaths.normCwd(it.path) }
        val codexByNorm = codex.entries.groupBy({ ProjectPaths.normCwd(it.key) }, { it.value })
        val codexOnly = codex.entries
            .filter { (cwd, _) -> ProjectPaths.normCwd(cwd) !in known }
            .map { (cwd, mtime) ->
                DirectoryEntry(
                    path = cwd,
                    name = Path.of(cwd).fileName?.toString() ?: cwd,
                    isDir = true,
                    hasSessions = true,
                    recent = cwd in recents,
                    lastModified = mtime,
                    busy = cwd in busyCwds,
                )
            }
            .distinctBy { ProjectPaths.normCwd(it.path) }
        // a dir with both histories sorts by whichever agent wrote last
        val merged = claude.map { e ->
            val codexM = codexByNorm[ProjectPaths.normCwd(e.path)]?.max() ?: 0L
            if (codexM > e.lastModified) e.copy(lastModified = codexM) else e
        }
        return (merged + codexOnly).sortedByDescending { it.lastModified }
    }

    /** Directories with Claude history, newest-first, deduped per cwd. */
    private fun claudeDirectories(busyCwds: Set<String>): List<DirectoryEntry> {
        val projects = ProjectPaths.projectsRoot()
        if (!projects.isDirectory()) return emptyList()
        val dirs = Files.newDirectoryStream(projects).use { it.toList() }.filter { it.isDirectory() }
        val now = System.currentTimeMillis()
        // open = a claude process is alive here (idle or active); executing = open AND wrote recently;
        // busy = a daemon conversation here has running background work (keep it "active" even when idle).
        val liveCwds = LiveProcesses.claudeCwds()
        return dirs.mapNotNull { dir -> scanProject(dir) }
            .map { (cwd, mtime, newest) ->
                val open = cwd in liveCwds
                // for live dirs, surface the active session (the newest transcript) so the row links straight into it
                val active = if (open) runCatching { TranscriptScanner.summarize(newest) }.getOrNull() else null
                DirectoryEntry(
                    path = cwd,
                    name = Path.of(cwd).fileName?.toString() ?: cwd,
                    isDir = true,
                    hasSessions = true,
                    recent = cwd in recents,
                    lastModified = mtime,
                    open = open,
                    executing = open && now - mtime < ACTIVE_WINDOW_MS,
                    busy = cwd in busyCwds,
                    activeSessionId = active?.sessionId,
                    activeSessionTitle = active?.title,
                    gitBranch = active?.gitBranch,
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
