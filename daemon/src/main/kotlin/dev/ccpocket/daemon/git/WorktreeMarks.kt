package dev.ccpocket.daemon.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Decides whether a directory is a LINKED git worktree, and of which repository — the fact behind the
 * "⎇ part of cc-pocket" caption on a directory row (issue #281 §2).
 *
 * Done WITHOUT starting git. The project list is rebuilt on every refresh and can hold dozens of rows;
 * spawning a `git rev-parse` per row would turn a list refresh into dozens of processes. Git's on-disk
 * layout already answers the question for free:
 *
 *  - a main worktree has a `.git` DIRECTORY;
 *  - a linked worktree has a `.git` FILE whose single line reads
 *    `gitdir: /path/to/main-repo/.git/worktrees/<name>`.
 *
 * So the main worktree's path is everything before `/.git/worktrees/`. If the file says anything else
 * (a submodule's `gitdir:` points at `…/.git/modules/…`, not `worktrees`), we return null and the row
 * renders exactly as it always did — this is a caption, and being silent is the correct failure.
 */
internal object WorktreeMarks {

    private const val MARKER = "/.git/worktrees/"
    private const val MAX_GITFILE_BYTES = 4_096L

    /** The main worktree's path for a linked checkout at [dir]; null for anything else. */
    fun mainWorktreeOf(dir: Path): String? = runCatching {
        val dotGit = dir.resolve(".git")
        if (!dotGit.isRegularFile()) return null
        if (Files.size(dotGit) > MAX_GITFILE_BYTES) return null
        val line = Files.readAllLines(dotGit).firstOrNull { it.startsWith("gitdir:") } ?: return null
        // git writes native separators on Windows; normalise so the one marker match works everywhere
        val gitDir = line.removePrefix("gitdir:").trim().replace('\\', '/')
        val idx = gitDir.indexOf(MARKER)
        if (idx <= 0) return null
        gitDir.substring(0, idx).ifBlank { null }
    }.getOrNull()

    /** Stamp [dev.ccpocket.protocol.DirectoryEntry.worktreeOf] on every row that is a linked checkout. */
    fun stamp(entries: List<dev.ccpocket.protocol.DirectoryEntry>): List<dev.ccpocket.protocol.DirectoryEntry> =
        entries.map { e ->
            val main = runCatching { mainWorktreeOf(Path.of(e.path)) }.getOrNull()
            if (main == null) e else e.copy(worktreeOf = main)
        }
}
