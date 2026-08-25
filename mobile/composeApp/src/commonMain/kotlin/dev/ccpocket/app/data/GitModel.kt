package dev.ccpocket.app.data

import dev.ccpocket.protocol.GIT_OP_PUSH
import dev.ccpocket.protocol.GitActionResult
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

/**
 * What a SUCCESSFUL fetch has to say for itself (issue #280 真机反馈 1).
 *
 * Fetch changes nothing local on purpose, so the panel looked identical before and after — the ↓1 the
 * user was staring at is still ↓1, correctly, and with no word from us that reads as "it worked". This
 * is that word, computed from the snapshot the daemon sends back with the result.
 */
enum class GitFetchOutcome {
    /** Level with the upstream — the fetch found nothing new. */
    UP_TO_DATE,

    /** The upstream is ahead and we are not: a fast-forward merge is available. */
    BEHIND,

    /** Both sides moved. `pull --ff-only` will refuse this — the note says so before the tap. */
    DIVERGED,

    /** Fetch succeeded but the daemon sent no snapshot (older daemon): "done", nothing more. */
    DONE,
}

/** The counted outcome of the last successful fetch, as the composer band reports it. */
data class GitFetchReport(val outcome: GitFetchOutcome, val ahead: Int = 0, val behind: Int = 0)

fun gitFetchReport(status: GitStatus?): GitFetchReport = when {
    status == null || !status.ok || status.detached || status.initial || status.upstream == null ->
        GitFetchReport(GitFetchOutcome.DONE)
    status.behind > 0 && status.ahead > 0 -> GitFetchReport(GitFetchOutcome.DIVERGED, status.ahead, status.behind)
    status.behind > 0 -> GitFetchReport(GitFetchOutcome.BEHIND, status.ahead, status.behind)
    else -> GitFetchReport(GitFetchOutcome.UP_TO_DATE, status.ahead, status.behind)
}

/**
 * True when a push was refused because the REMOTE moved on — the one push failure the phone can give
 * real advice about (issue #280 真机反馈 5). Everything else (no upstream, auth, a hook) gets git's own
 * line and no advice from us, because guessing at it would be worse than silence.
 */
fun gitPushBlockedByRemote(result: GitActionResult): Boolean {
    if (result.ok || result.op != GIT_OP_PUSH) return false
    val text = (result.stderr + "\n" + result.error.orEmpty()).lowercase()
    return PUSH_REJECTED_MARKERS.any { it in text }
}

private val PUSH_REJECTED_MARKERS = listOf("[rejected]", "(fetch first)", "non-fast-forward", "fetch first")

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

/** The post-create receipt (#281 功能范围, restored by #294 真机反馈): a successful `worktree add`
 *  raises this, and the sheet it drives offers to open a session in [path]. [path] is null when an
 *  older daemon answered without it — the fact still shows, the open-here verb does not. */
data class WorktreeCreated(val path: String?, val branch: String?)
