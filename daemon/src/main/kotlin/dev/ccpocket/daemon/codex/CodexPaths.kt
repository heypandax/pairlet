package dev.ccpocket.daemon.codex

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Codex stores rollout transcripts under `$CODEX_HOME/sessions` (default `~/.codex/sessions`), nested by
 * date as `YYYY/MM/DD/rollout-<timestamp>-<threadId>.jsonl` (verified against codex 0.124). The threadId in
 * the filename is the resume handle. Unlike Claude's per-project folders, rollouts are global — callers
 * filter by the `cwd` recorded in each file's session_meta line.
 *
 * Both listing entry points are on hot paths (the 10-second project refresh lists the whole tree; opening
 * one Codex session looked one id up four times), so both are memoized — see [entriesOf] and [findSession].
 * The memos key on the DIRECTORY's own mtime, never on wall-clock TTLs: a rollout appearing or vanishing
 * is exactly what bumps its parent's mtime.
 */
object CodexPaths {
    fun codexHome(): Path =
        System.getenv("CODEX_HOME")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".codex")

    fun sessionsRoot(): Path = codexHome().resolve("sessions")

    /** Codex's session index (`$CODEX_HOME/session_index.jsonl`): one `{id, thread_name, updated_at}` line
     *  per thread, carrying the human/AI title Codex Desktop shows. Rollouts themselves store no title (#64). */
    fun sessionIndex(): Path = codexHome().resolve("session_index.jsonl")

    /** Every rollout file under sessions/ (recursively), newest-mtime first, capped to bound a global scan.
     *  File mtimes are stat'd fresh on every call (the sort needs them, and a stat is cheap); only the
     *  directory LISTINGS are reused. A path whose stat fails is dropped — it was deleted since the listing
     *  was taken, which is also how a stale cached entry gets filtered out. */
    fun sessionFiles(limit: Int = 800, root: Path = sessionsRoot()): List<Path> {
        if (!root.isDirectory()) return emptyList()
        val files = ArrayList<Path>()
        collectRollouts(root, 0, files)
        return files
            .mapNotNull { p -> runCatching { p.getLastModifiedTime().toMillis() }.getOrNull()?.let { p to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * The rollout file for a thread id (filename ends with `-<threadId>.jsonl`).
     *
     * Answered from a lazy id→path index: opening ONE Codex session asks for the same id four times
     * (resumeContextTokens / resumeModel / resumeTitle plus SessionRegistry's own lookup), and each of those
     * used to walk the entire tree (PR #296 review). Rollouts are never renamed, so a cached hit only has to
     * still exist; when it doesn't, the entry is dropped and the full walk backfills it.
     */
    fun findSession(sessionId: String, root: Path = sessionsRoot()): Path? {
        idIndex[sessionId]?.let {
            if (it.isRegularFile()) return it
            idIndex.remove(sessionId, it) // deleted/archived since — fall through to a fresh walk
        }
        if (!root.isDirectory()) return null
        val suffix = "-$sessionId.jsonl"
        val files = ArrayList<Path>()
        collectRollouts(root, 0, files)
        // No mtime sort here: this lookup only needs the first name match, not newest-first ordering.
        val hit = files.firstOrNull { it.fileName.toString().endsWith(suffix) && it.isRegularFile() }
        if (hit != null) idIndex[sessionId] = hit
        return hit
    }

    /** One directory's children plus whether each is itself a directory. `isDir` rides along with the
     *  listing because swapping a name between file and directory means unlink+create IN the parent, which
     *  bumps the parent's mtime and drops this entry anyway. */
    private class DirEntry(val path: Path, val isDir: Boolean)

    private val dirCache = ConcurrentHashMap<Path, Pair<FileTime, List<DirEntry>>>()

    /** id → rollout path, backfilled by [findSession]; validated by existence, never by time. */
    private val idIndex = ConcurrentHashMap<String, Path>()

    /**
     * [dir]'s children, re-listed only when its mtime moved. The rollout tree is date-nested, so every
     * directory except today's is immutable — re-reading all of them on each 10-second refresh was pure
     * waste.
     *
     * The stamp is the raw [FileTime] (nanosecond precision where the filesystem has it), and a directory
     * touched within [DIR_SETTLE_MS] is deliberately NOT cached: on a coarse-granularity filesystem a file
     * created in the same tick as our listing would leave a stale listing that nothing ever invalidates,
     * which would hide a brand-new session forever. Only today's leaf directory is ever that young, so the
     * guard costs one small readdir and keeps the rest of the tree cached.
     */
    private fun entriesOf(dir: Path): List<DirEntry> {
        val stamp = runCatching { dir.getLastModifiedTime() }.getOrNull() ?: return emptyList()
        val settled = System.currentTimeMillis() - stamp.toMillis() >= DIR_SETTLE_MS
        if (settled) dirCache[dir]?.let { if (it.first == stamp) return it.second }
        val listed = runCatching {
            Files.list(dir).use { s -> s.map { DirEntry(it, Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) }.toList() }
        }.getOrNull() ?: return emptyList() // unreadable dir: don't cache the failure as "empty"
        if (settled) dirCache[dir] = stamp to listed
        return listed
    }

    /** Depth-first collect of rollout files over the cached listings. Depth is bounded rather than pinned to
     *  the YYYY/MM/DD layout so a future Codex nesting still resolves; symlinked directories are not
     *  descended, matching the `Files.walk` this replaced (and ruling out link cycles). */
    private fun collectRollouts(dir: Path, depth: Int, out: MutableList<Path>) {
        if (depth > MAX_DEPTH) return
        for (e in entriesOf(dir)) {
            if (e.isDir) collectRollouts(e.path, depth + 1, out)
            else if (isRolloutName(e.path)) out.add(e.path)
        }
    }

    private fun isRolloutName(p: Path): Boolean =
        p.fileName.toString().let { it.startsWith("rollout-") && it.endsWith(".jsonl") }

    /** Cross-test isolation: these memos are process-wide state on an object singleton. */
    internal fun clearForTest() {
        dirCache.clear()
        idIndex.clear()
    }

    private const val MAX_DEPTH = 8
    private const val DIR_SETTLE_MS = 2_000L
}
