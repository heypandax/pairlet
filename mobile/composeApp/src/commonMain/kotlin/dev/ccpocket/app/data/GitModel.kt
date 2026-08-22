package dev.ccpocket.app.data

import dev.ccpocket.protocol.GitDiff
import dev.ccpocket.protocol.GitFileEntry
import dev.ccpocket.protocol.GitStatus
import dev.ccpocket.protocol.WorktreeEntry

// ════════════════════════════════════════════════════════════════════
//  Client-side model behind the Git panel (issue #280) and the worktree
//  surface (issue #281). Everything here is pure so the grouping order,
//  the truncation rule and the enablement policy are testable without a
//  composition — the Composables in ui/git/ only paint what this says.
// ════════════════════════════════════════════════════════════════════

/** How long a pocket/git.* or pocket/worktree.* request waits before the app calls it a too-old daemon.
 *  Same 8s the changed-files pair uses — these are all local git reads, not network round trips. */
const val GIT_REPLY_DEADLINE_MS: Long = 8_000

/** Client-fabricated [dev.ccpocket.protocol.GitActionResult.error] for "the daemon never answered".
 *  Never produced by a daemon; [PocketRepository]'s action deadline is the only writer. */
const val GIT_ERROR_STALE_DAEMON = "stale_daemon"

/** True for the fabricated no-reply diff — the pane shows "update the computer", not "no diff". */
val GitDiff.staleDaemon: Boolean get() = !ok && error == DIFF_ERROR_STALE_DAEMON

/**
 * Middle-truncate a repository-relative path from the LEFT, keeping whole segments: the filename is
 * what identifies a row, so it must never move as the list scrolls (design A1). Mirrors the handoff's
 * own `mid()` helper exactly — longest whole-segment tail that fits, prefixed with an ellipsis.
 */
fun midTruncatePath(path: String, max: Int): String {
    if (path.length <= max) return path
    val parts = path.split('/')
    var tail = parts.last()
    var i = parts.size - 2
    while (i > 0 && tail.length + parts[i].length + 3 <= max) {
        tail = parts[i] + "/" + tail
        i--
    }
    return "…/$tail"
}

/** Which colour language a row's letter chip speaks. One glyph, one colour, one meaning (design §2):
 *  green staged, amber working change, red conflict, grey untracked. */
enum class GitChip { STAGED, UNSTAGED, CONFLICT, UNTRACKED }

/** The action pill at a row's right edge, or none at all — a conflict row is not stageable, so it
 *  carries a chevron to the diff instead (design A3). */
enum class GitRowAction { STAGE, UNSTAGE, NONE }

/** One group of the status list. [key] is stable for LazyColumn identity. */
data class GitSection(
    val key: String,
    val chip: GitChip,
    val action: GitRowAction,
    val rows: List<GitFileEntry>,
)

/**
 * The four groups in the ONE order the panel ever renders them, with the two rules the design fixes:
 *  · empty groups are REMOVED, not shown empty;
 *  · while the index is unmerged, Conflicts takes the top and Staged disappears entirely — nothing
 *    can be staged against an unmerged index, so an empty-but-present Staged group would be a lie.
 */
fun gitSections(status: GitStatus): List<GitSection> {
    val conflicted = status.conflicted
    return buildList {
        if (conflicted.isNotEmpty()) {
            add(GitSection("conflicts", GitChip.CONFLICT, GitRowAction.NONE, conflicted))
        } else if (status.staged.isNotEmpty()) {
            add(GitSection("staged", GitChip.STAGED, GitRowAction.UNSTAGE, status.staged))
        }
        if (status.unstaged.isNotEmpty()) {
            add(GitSection("changes", GitChip.UNSTAGED, GitRowAction.STAGE, status.unstaged))
        }
        if (status.untracked.isNotEmpty()) {
            add(GitSection("untracked", GitChip.UNTRACKED, GitRowAction.STAGE, status.untracked))
        }
    }
}

/** Commit is refused for two reasons and both are stated in place, never by a toast (design A3):
 *  an empty message, and an unmerged index. Returns null when the button is live. */
enum class GitCommitBlock { EMPTY_MESSAGE, CONFLICTS, DETACHED }

fun commitBlockedBy(status: GitStatus?, message: String): GitCommitBlock? = when {
    status == null -> GitCommitBlock.EMPTY_MESSAGE
    status.conflicted.isNotEmpty() -> GitCommitBlock.CONFLICTS
    status.detached -> GitCommitBlock.DETACHED
    message.isBlank() -> GitCommitBlock.EMPTY_MESSAGE
    else -> null
}

/** "↑2 ↓1" — the arrows only. Empty both when the status is unknown (so the header never flashes a
 *  wrong reading) and when the branch is level with its upstream: that case is a WORD, not arrows,
 *  and words are localized — [gitInSync] tells the header to render it. */
fun divergenceText(status: GitStatus?): String = when {
    status == null || status.detached || status.initial -> ""
    status.ahead > 0 && status.behind > 0 -> "↑${status.ahead} ↓${status.behind}"
    status.ahead > 0 -> "↑${status.ahead}"
    status.behind > 0 -> "↓${status.behind}"
    else -> ""
}

/** Level with a real upstream — the header's green "in sync". A branch with no upstream at all is
 *  neither in sync nor diverged, so it says nothing rather than claiming agreement with nothing. */
fun gitInSync(status: GitStatus?): Boolean =
    status != null && !status.detached && !status.initial &&
        status.upstream != null && status.ahead == 0 && status.behind == 0

/** The unmerged shape, named rather than left as git's two letters (design A3 sub-line). The words
 *  themselves are localized at the call site — this only classifies. Null when the daemon sent no
 *  [GitFileEntry.xy] (an older one) and the row simply carries no sub-line. */
enum class GitConflictShape { BOTH_MODIFIED, BOTH_ADDED, BOTH_DELETED, ADDED_BY_US, ADDED_BY_THEM, DELETED_BY_US, DELETED_BY_THEM }

fun conflictShape(entry: GitFileEntry): GitConflictShape? = when (entry.xy) {
    "DD" -> GitConflictShape.BOTH_DELETED
    "AU" -> GitConflictShape.ADDED_BY_US
    "UD" -> GitConflictShape.DELETED_BY_THEM
    "UA" -> GitConflictShape.ADDED_BY_THEM
    "DU" -> GitConflictShape.DELETED_BY_US
    "AA" -> GitConflictShape.BOTH_ADDED
    "UU" -> GitConflictShape.BOTH_MODIFIED
    else -> null
}

/** The last path segment of a worktree's main-repo anchor — what "part of cc-pocket" names (#281 D). */
fun repoBasename(path: String): String =
    path.trimEnd('/', '\\').split('/', '\\').lastOrNull { it.isNotBlank() } ?: path

/** The directory `git worktree add` would create for [branch], as the New-worktree sheet previews it
 *  live while typing: a SIBLING of the repository, slashes flattened to dashes so one branch is one
 *  directory. Display only — the daemon computes the real path and validates it (protocol Git.kt). */
fun worktreeLocationPreview(repoRoot: String?, branch: String): String {
    val repo = repoBasename(repoRoot ?: "").ifBlank { "repo" }
    val slug = branch.trim().trim('/').replace('/', '-').ifBlank { "…" }
    return "../$repo-worktrees/$slug"
}

/** Removal is refused outright — not merely confirmed — for a checkout with a live session, and the
 *  main worktree has no Remove item at all (its absence IS the answer, design A6). */
fun worktreeRemovable(entry: WorktreeEntry): Boolean = !entry.isMain
