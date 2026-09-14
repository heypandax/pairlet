package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime

/**
 * Discovers resumable `dsh` sessions (issue #255) by walking `~/.dsh/sessions` and reading each
 * transcript's HEADER — never by inverting the directory name.
 *
 * The `--<normalized-cwd>--` directory name is a lossy, colliding key ([DshPaths.projectKey] documents
 * why: `/a/b` and `/a-b` land in the same directory). It is used here purely to pick which directory to
 * look in FIRST; membership is always decided by the header's verbatim `cwd`, compared with
 * [ProjectPaths.canonicalKey] like every other backend's cross-source match.
 *
 * Subagent sessions remain hidden. A readable header in an unsupported generation stays discoverable
 * with an explicit diagnostic; the selected generation must never silently disappear or fall back to
 * an immutable predecessor.
 *
 * The event body is read only for the two facts a row cannot be honest without and that only the WHOLE
 * file settles — the renamed title (issue #289) and the model that last answered (issue #320) — through
 * a single mtime-cached stream ([tail]). Everything else comes off the header or the summary budget.
 */
object DshTranscriptScanner {
    /** A transcript written within this window counts as a live session for the list's dot. */
    const val LIVE_WINDOW_MS = 20_000L

    /** Safety bound: never let one listing walk an unbounded store. */
    private const val MAX_SESSIONS_SCANNED = 2_000

    /** One discovered session: its header plus where it lives. */
    data class Found(val header: DshTranscript.Header, val dir: Path, val file: Path, val mtime: Long)

    /** Sessions whose recorded cwd matches [workdir], newest first. [root] is a test seam. */
    fun scan(workdir: String, root: Path = DshPaths.sessionsRoot()): List<SessionSummary> {
        val target = workdir.takeIf { it.isNotBlank() }?.let(ProjectPaths::canonicalKey) ?: return emptyList()
        return findAll(cwdHint = workdir, root = root)
            .filter { it.header.cwd != null && ProjectPaths.canonicalKey(it.header.cwd) == target }
            .sortedByDescending { it.mtime }
            .mapNotNull { runCatching { summarize(it) }.getOrNull() }
    }

    /** Every cwd with dsh history → its newest session mtime (feeds the cross-backend directory list). */
    fun cwdsByNewest(root: Path = DshPaths.sessionsRoot()): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (found in findAll(root = root)) {
            val cwd = found.header.cwd ?: continue // the `_no-cwd` bucket has no project to list
            out.merge(cwd, found.mtime, ::maxOf)
        }
        return out
    }

    /** Locate one session by id (for replay/resume), or null. */
    fun find(sessionId: String, cwdHint: String? = null, root: Path = DshPaths.sessionsRoot()): Found? {
        val dir = DshPaths.findSessionDir(sessionId, cwdHint, root) ?: return null
        val file = DshPaths.transcriptFile(dir) ?: return null
        val header = DshTranscript.header(file) ?: return null
        return Found(header, dir, file, mtimeOf(file))
    }

    /**
     * Walk the store and return every usable session header. [cwdHint] only reorders the walk so the
     * likely project directory is visited first — it never restricts the result, because the project key
     * collides and a session's real cwd can only be read from its header.
     */
    fun findAll(cwdHint: String? = null, root: Path = DshPaths.sessionsRoot()): List<Found> {
        val hinted = cwdHint?.takeIf { it.isNotBlank() }?.let { root.resolve(DshPaths.projectKey(it)) }
        val projectDirs = DshPaths.projectDirs(root)
        val ordered = if (hinted != null && projectDirs.any { it == hinted }) {
            listOf(hinted) + projectDirs.filter { it != hinted }
        } else {
            projectDirs
        }
        val out = ArrayList<Found>()
        var scanned = 0
        for (projectDir in ordered) {
            for (dir in DshPaths.sessionDirs(projectDir)) {
                if (scanned >= MAX_SESSIONS_SCANNED) return out
                if (DshPaths.isSidecar(dir.fileName.toString())) continue
                scanned += 1
                val found = runCatching { read(dir) }.getOrNull() ?: continue
                out += found
            }
        }
        return out
    }

    private fun read(dir: Path): Found? {
        val file = DshPaths.transcriptFile(dir) ?: return null
        val header = DshTranscript.header(file) ?: return null
        if (header.isSubagent) return null
        return Found(header, dir, file, mtimeOf(file))
    }

    private fun mtimeOf(file: Path): Long =
        runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L)

    /**
     * issue #289: a rename APPENDS a `session/title` event, so the summary-budget read misses it once
     * the chat outgrows the budget. Issue #320 adds the model, which is last-wins for the same reason.
     * The full-transcript streaming scan is cached by (path, mtime) — a settled session is scanned once
     * per daemon lifetime; only a live writer (mtime moving) re-scans.
     */
    private data class CachedTail(val mtime: Long, val tail: DshTranscript.Tail)
    private val tailCache = java.util.concurrent.ConcurrentHashMap<String, CachedTail>()
    private const val TAIL_CACHE_MAX = 4_096 // ~2× the scan bound; blunt reset beats an LRU here

    /**
     * The title AND the model, from ONE cached stream ([DshTranscript.tail]).
     *
     * Both are last-wins over the whole file, so both cost the same walk — reading them separately would
     * decompress every session twice per listing for no new information.
     */
    private fun tail(found: Found): DshTranscript.Tail {
        val key = found.file.toString()
        tailCache[key]?.takeIf { it.mtime == found.mtime }?.let { return it.tail }
        val fresh = runCatching { DshTranscript.tail(found.file) }.getOrDefault(DshTranscript.Tail.EMPTY)
        if (tailCache.size >= TAIL_CACHE_MAX) tailCache.clear()
        tailCache[key] = CachedTail(found.mtime, fresh)
        return fresh
    }

    private fun summarize(found: Found): SessionSummary {
        DshTranscript.formatProblem(found.file, found.header)?.let { problem ->
            return SessionSummary(
                sessionId = found.header.id,
                title = "DSH history unavailable (format v${found.header.version})",
                firstPrompt = problem,
                messageCount = 0,
                cwd = found.header.cwd.orEmpty(),
                lastModified = found.mtime,
                agent = AgentKind.DSH,
            )
        }
        // Bounded read for everything BUT the title and the model: header, opening user turn and the
        // message count all live near the top, so the list never materializes a long chat. Those two must
        // consider the whole file (renames append, and the model can switch mid-chat) — done as ONE cached
        // O(1)-memory stream; see [tail].
        val lines = runCatching { DshTranscript.summaryLines(found.file) }.getOrDefault(emptyList())
        val tail = tail(found)
        val title = tail.title ?: runCatching { DshTranscript.title(lines) }.getOrNull()
        val firstPrompt = lines.asSequence()
            .mapNotNull { DshTranscript.parseRecord(it, found.header.version) }
            .firstOrNull { it.str("type") == DshTranscript.EVENT_USER && it["ignorable"]?.toString() != "true" &&
                DshTranscript.messagePlacement(it, found.header.version) == DshTranscript.MessagePlacement.APPEND }
            ?.let { DshTranscript.messageText(it.obj("data")) }
            .orEmpty()
        return SessionSummary(
            sessionId = found.header.id,
            title = title?.takeIf { it.isNotBlank() } ?: found.header.id,
            firstPrompt = firstPrompt,
            // Approximate, and deliberately so: it counts only the turns inside the summary budget, which
            // the App tolerates (it renders 0 fine). An exact count would mean full decompression per row.
            messageCount = runCatching { DshTranscript.countUserMessages(lines) }.getOrDefault(0),
            cwd = found.header.cwd.orEmpty(),
            lastModified = found.mtime,
            live = System.currentTimeMillis() - found.mtime < LIVE_WINDOW_MS,
            agent = AgentKind.DSH,
            // issue #320: the model the session's own records name — last-wins, so a row says what the
            // session IS rather than what it was opened as. Null when the transcript named none; the local
            // default is NOT a substitute, because it describes this machine today, not that conversation.
            model = tail.meta.model,
        )
    }
}
