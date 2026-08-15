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
 * Two whole classes of session are filtered out:
 *  - `version != 0` — a format this build has never seen; guessing at it would surface garbage rows.
 *  - `origin == "subagent"` — dsh records each sub-agent's own session; those are internal machinery,
 *    not chats the user started (the same rule ZCode's scanner applies).
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
        if (!header.isSupported) return null
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
        // unknown format version, or a sub-agent's internal session — neither belongs in a user's list
        if (!header.isSupported || header.isSubagent) return null
        return Found(header, dir, file, mtimeOf(file))
    }

    private fun mtimeOf(file: Path): Long =
        runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L)

    private fun summarize(found: Found): SessionSummary {
        // Bounded read: the header, the title event and the opening user turn all live near the top, so
        // the list never pays for decompressing a long chat.
        val lines = runCatching { DshTranscript.summaryLines(found.file) }.getOrDefault(emptyList())
        val title = runCatching { DshTranscript.title(lines) }.getOrNull()
        val firstPrompt = lines.asSequence()
            .mapNotNull { DshTranscript.parseLine(it) }
            .firstOrNull { it.str("type") == DshTranscript.EVENT_USER }
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
        )
    }
}
