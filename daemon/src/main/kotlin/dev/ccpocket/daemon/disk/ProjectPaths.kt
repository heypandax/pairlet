package dev.ccpocket.daemon.disk

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

/** Maps a working directory to its `~/.claude/projects/<dir-key>` transcript folder. */
object ProjectPaths {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun projectsRoot(): Path = Path.of(System.getProperty("user.home"), ".claude", "projects")

    /**
     * claude 2.1.x encodes the cwd into its `~/.claude/projects/<key>` folder name by replacing
     * every character that is NOT `[A-Za-z0-9-]` with '-'. That means '/', '.', '_', spaces, etc.
     * all become '-', while existing hyphens are kept and runs are NOT collapsed (so `…/-x`/`._`
     * → `--`). Verified against on-disk dirs, e.g. `…/j_c3x2gb/work` → `…-j-c3x2gb-work` and
     * `…/skdbg.IYBb` → `…-skdbg-IYBb`. An earlier version replaced only '/', which broke session
     * fetch/open for any cwd containing '_' or '.' (the computed dir didn't exist on disk).
     *
     * This forward mapping is LOSSY and was only verified against Unix paths. On Windows a cwd like
     * `C:\Users\x\proj` may NOT encode to claude's actual on-disk dir name, so [dirFor] must not rely
     * on it alone — it falls back to the authoritative recorded `cwd` (see below).
     */
    fun dirKey(absPath: String): String = absPath.replace(Regex("[^A-Za-z0-9-]"), "-")

    /**
     * The project dir for [workdir]. Tries the fast [dirKey] path first; when that dir does not exist
     * — the Windows / lossy-encoding case — falls back to locating the dir by the authoritative `cwd`
     * recorded inside each project's newest transcript (the same source [DirectoryService] lists from),
     * which is correct on any OS regardless of how claude encoded the folder name. Returns the dirKey
     * path when nothing matches (e.g. a brand-new session whose dir doesn't exist yet), so new-session
     * behavior is unchanged. Unix keeps the fast path (no scan); only a dirKey miss pays for the scan.
     */
    fun dirFor(workdir: String): Path = dirForUnder(projectsRoot(), workdir)

    /** [dirFor] against an explicit projects [root] — same logic, testable without touching `$HOME`. */
    fun dirForUnder(root: Path, workdir: String): Path {
        val byKey = root.resolve(dirKey(workdir))
        if (byKey.exists()) return byKey
        return findByRecordedCwd(root, workdir) ?: byKey
    }

    private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * Normalize a cwd for cross-OS equality before matching: unify back/forward slashes and drop a trailing
     * separator; on Windows also lowercase (its filesystem is case-insensitive, and the resume path hands us a
     * `toRealPath()`-canonicalized workdir that can differ from Claude's recorded cwd in case / slash direction
     * / trailing separator — notably for UNC `\\host\share` paths). A no-op on ordinary Unix paths, so the fast
     * path and Unix behavior are unchanged. Shared: the Codex scanner compares its rollouts' recorded cwd
     * against the phone's workdir with the same rules (issue #19's sibling — exact compare lost sessions).
     */
    internal fun normCwd(p: String): String {
        var s = p.replace('\\', '/')
        if (s.length > 1 && s.endsWith('/')) s = s.dropLast(1)
        return if (onWindows) s.lowercase() else s
    }

    /** `~` / `~/...` → the daemon user's home. Clients accept `~` paths in their "new session" inputs and may
     *  send them raw; the daemon owns the expansion because only it knows this machine's home. */
    internal fun expandTilde(path: String): String = when {
        path == "~" -> System.getProperty("user.home")
        path.startsWith("~/") || path.startsWith("~\\") -> System.getProperty("user.home") + path.drop(1)
        else -> path
    }

    // canonicalKey memo — toRealPath() is an IO syscall and the directory list recomputes keys for every row
    // on every refresh. An EXISTING dir's real path is stable, so successes cache forever (cardinality here is
    // "projects the user has", i.e. tiny). Misses are deliberately NOT cached: a dir created later must pick
    // up its real path, or a pre-creation fallback key would split it from its own real spelling forever.
    private val canonicalCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The cross-backend identity key for a working directory (issue #184). Each backend records the SAME dir
     * in its own spelling — tilde vs absolute, trailing/doubled separators, symlinked forms like macOS
     * `/var` ↔ `/private/var` — and key-equality is what merges their project rows AND matches a row to each
     * backend's sessions, so anything weaker than the filesystem's own verdict shows one project twice (or,
     * worse, hides a backend's sessions from the merged row). Both sides of every dir↔session compare must
     * use THIS key: mixing it with [normCwd] would deduplicate rows while losing their sessions.
     *
     * Existing paths resolve through [java.nio.file.Path.toRealPath] (symlinks followed; case canonicalized
     * by case-insensitive filesystems). Paths that no longer exist (deleted projects still in history)
     * degrade to pure string normalization: tilde-expanded, `.`/`..`/doubled separators collapsed by
     * [java.nio.file.Path.normalize], then [normCwd]'s slash / trailing-separator / Windows-lowercase rules —
     * deliberately conservative, no case folding beyond what the real filesystem answered.
     */
    internal fun canonicalKey(p: String): String {
        val expanded = expandTilde(p)
        if (expanded.isBlank()) return normCwd(expanded) // never realpath "" — it would resolve to the daemon's own cwd
        canonicalCache[expanded]?.let { return it }
        val parsed = runCatching { Path.of(expanded) }.getOrNull() ?: return normCwd(expanded)
        val real = runCatching { parsed.toRealPath() }.getOrNull()
            ?: return normCwd(parsed.normalize().toString())
        return normCwd(real.toString()).also { canonicalCache[expanded] = it }
    }

    /** The project dir whose newest transcript records [workdir] as its `cwd` (canonical-key match — claude
     *  may have recorded a symlinked/variant spelling of the resume path's realpath'd workdir), or null. */
    private fun findByRecordedCwd(root: Path, workdir: String): Path? {
        if (!root.isDirectory()) return null
        val target = canonicalKey(workdir)
        return Files.newDirectoryStream(root).use { stream ->
            stream.firstOrNull { dir -> dir.isDirectory() && recordedCwd(dir)?.let(::canonicalKey) == target }
        }
    }

    /** The `cwd` recorded in [projectDir]'s newest `.jsonl`, or null if none/unreadable. */
    private fun recordedCwd(projectDir: Path): String? {
        val newest = runCatching {
            Files.newDirectoryStream(projectDir, "*.jsonl").use { it.toList() }
        }.getOrNull()?.maxByOrNull { it.getLastModifiedTime().toMillis() } ?: return null
        return runCatching {
            newest.bufferedReader().useLines { lines ->
                lines.firstNotNullOfOrNull { raw ->
                    val obj = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() as? JsonObject
                    (obj?.get("cwd") as? JsonPrimitive)?.contentOrNull
                }
            }
        }.getOrNull()
    }
}
