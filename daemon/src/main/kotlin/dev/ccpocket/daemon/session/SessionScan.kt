package dev.ccpocket.daemon.session

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ManagedScanDiagnostics
import dev.ccpocket.protocol.SessionSummary

/**
 * How trustworthy one backend's session scan is (issue #360). The legacy [SessionRegistry.listSessions] turns a
 * failed scan into an empty list, which is fine for display but can never prove "this project has no sessions".
 * Anything that makes a durable decision from a scan — the first migration to the managed list, an import's
 * existence check, marking a member "record unavailable" — must require [COMPLETE].
 */
enum class ScanCompleteness(val wire: String) {
    /** Every native record under the project was listed; an empty result really means none exist. */
    COMPLETE(ManagedScanDiagnostics.COMPLETE),

    /** The listing itself worked but some records failed to read. */
    PARTIAL(ManagedScanDiagnostics.PARTIAL),

    /** The backend walked only a capped number of records and the cap was reached. */
    TRUNCATED(ManagedScanDiagnostics.TRUNCATED),

    /** The history directory (or part of it) was not readable for lack of permission. */
    PERMISSION_DENIED(ManagedScanDiagnostics.PERMISSION_DENIED),

    /** The scan failed outright, or the backend is not registered. */
    ERROR(ManagedScanDiagnostics.ERROR),

    /** The backend does not report completeness; its rows are display-only. */
    UNVERIFIED(ManagedScanDiagnostics.ERROR),
}

/**
 * Claude's completeness-aware scan of one project transcript [dir] (issue #360). A dir that PROVABLY does not
 * exist is a complete empty scan (a project with no Claude history); one whose existence cannot be determined is
 * PERMISSION_DENIED; a non-directory or a failed listing is ERROR; unreadable transcripts make it PARTIAL.
 */
fun claudeProjectScan(dir: java.nio.file.Path, workdir: String, agent: AgentKind = AgentKind.CLAUDE): SessionScan = try {
    when {
        java.nio.file.Files.isDirectory(dir) -> {
            val d = dev.ccpocket.daemon.disk.TranscriptScanner.scanDetailed(dir)
            // Claude's project-dir name is a LOSSY encoding of the path (`/x/a_b` and `/x/a.b` share one dir), so a
            // row belongs to [workdir] only when the cwd it recorded is the same project. A row with no recorded cwd
            // cannot be attributed: it stays listed, but the scan can no longer prove it is exactly this project's.
            val target = dev.ccpocket.daemon.disk.ProjectPaths.canonicalKey(workdir)
            val mine = d.items.filter { it.cwd.isBlank() || dev.ccpocket.daemon.disk.ProjectPaths.canonicalKey(it.cwd) == target }
            val unattributed = mine.any { it.cwd.isBlank() }
            SessionScan(
                agent, workdir, mine.map { it.copy(agent = agent) },
                if (d.failed > 0 || unattributed) ScanCompleteness.PARTIAL else ScanCompleteness.COMPLETE, failedCount = d.failed,
                detail = if (unattributed) "rows without a recorded cwd" else null,
            )
        }
        java.nio.file.Files.notExists(dir) -> SessionScan(agent, workdir, emptyList(), ScanCompleteness.COMPLETE)
        java.nio.file.Files.exists(dir) -> SessionScan(agent, workdir, emptyList(), ScanCompleteness.ERROR, detail = "not a directory")
        else -> SessionScan(agent, workdir, emptyList(), ScanCompleteness.PERMISSION_DENIED, detail = "existence undeterminable")
    }
} catch (e: Exception) {
    SessionScan.failed(agent, workdir, e)
}

/** One backend's scan of [workdir]: [items] (newest first, all of [agent]) plus how complete they are. */
data class SessionScan(
    val agent: AgentKind,
    val workdir: String,
    val items: List<SessionSummary>,
    val completeness: ScanCompleteness,
    /** Records that were found but could not be read (PARTIAL), 0 otherwise. */
    val failedCount: Int = 0,
    /** Short, path-free reason for logs/diagnostics. */
    val detail: String? = null,
) {
    val isComplete: Boolean get() = completeness == ScanCompleteness.COMPLETE

    companion object {
        fun failed(agent: AgentKind, workdir: String, error: Throwable): SessionScan = SessionScan(
            agent, workdir, emptyList(),
            if (error is java.nio.file.AccessDeniedException || error is SecurityException) ScanCompleteness.PERMISSION_DENIED
            else ScanCompleteness.ERROR,
            detail = error::class.simpleName,
        )
    }
}
