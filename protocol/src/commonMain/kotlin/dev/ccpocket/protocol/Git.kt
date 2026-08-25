package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ===========================================================================
//  Git panel (issue #280, docs/design/GIT-PANEL.md) + Worktree management
//  (issue #281, docs/design/WORKTREE-MANAGEMENT.md).
//
//  What these frames are NOT: a remote shell. [RunShellCommand] already lets the
//  owner type `git status` from the phone; its price is an approval card per call
//  and unstructured text back. This family buys structure and zero-approval READS
//  by giving up the free-text command surface entirely — the daemon assembles the
//  argv itself from a CLOSED nine-verb allow-list (see [GIT_OPS]) and never passes
//  a client string to a shell. That closed set IS the security model, so the wire
//  must not grow an escape hatch: no "args", no "extraFlags", no raw command field.
//
//  Wire red lines kept here (same as Handoff.kt/Review.kt):
//   - [GitAction.op] is a TOLERANT String, not an enum. `coerceInputValues` would
//     silently rewrite an unknown enum to the DEFAULT verb, and a default verb that
//     runs SOMETHING is exactly the wrong failure for a mutating allow-list. A
//     String lets the daemon reject the unknown token by name instead.
//   - every field added later is a trailing optional with a default;
//   - [GitActionResult.statusAfter] nests a [GitStatus] as a CONCRETE type (no "t"
//     inside), the same shape PendingApproval.ask has used since the approval plane
//     shipped. Its declared type must NEVER be widened to ToPhone/Frame: a
//     polymorphic decoder REQUIRES the discriminator, so every snapshot an older
//     daemon already sends would fail to decode — a one-way break no
//     `ignoreUnknownKeys` can absorb. Widening is safe only in the other direction.
//   - [WorktreeEntry.dirty] must keep its `null` default. With explicitNulls=false an
//     unknown dirty state travels as an ABSENT key; give the field a non-null default
//     and absence silently decodes as "clean" rather than "we have not looked" — the
//     one reading that must never be guessed, since the UI would stop warning before
//     a delete.
//   - all pocket/git.* and pocket/worktree.* frames are NEW types: an old daemon
//     silently drops them (unknown "t"), so the client arms a reply deadline and
//     falls back to the same "update the computer" state [ReadFileDiff] uses; an
//     old app never sends them.
//   - OWNER-ONLY on the daemon side: none of these types appear in GuestCaps /
//     BridgeCaps / CollaboratorCaps allow-lists, and RequestRouter re-checks the
//     three credential classes at dispatch. Reads are not opened up either — a
//     guest's files/diff surface is "what this session changed", while git status
//     is "what the whole repository looks like", a strictly wider face.
// ===========================================================================

/** The closed set of mutating verbs the phone may ask for — the allow-list contract on the design board
 *  (git-panel-280 §E: nine verbs; worktrees-281 §E adds two reachable through [GitAction]'s siblings).
 *  ONE constant so the daemon's dispatch and the app's buttons cannot drift apart. Anything absent from
 *  this set is unreachable from the phone by construction: force push, amend, stash, merge, rebase,
 *  reset --hard, clean, branch delete/rename, hunk-level staging.
 *
 *  Note the two-word split: [GIT_OP_PULL] is ALWAYS `--ff-only` (a non-fast-forward is reported, never
 *  merged), [GIT_OP_PUSH] never carries `--force`/`--force-with-lease` and never creates an upstream. */
const val GIT_OP_STAGE = "stage"
const val GIT_OP_UNSTAGE = "unstage"
const val GIT_OP_COMMIT = "commit"
const val GIT_OP_FETCH = "fetch"
const val GIT_OP_PULL = "pull"
const val GIT_OP_PUSH = "push"
const val GIT_OP_CHECKOUT = "checkout"
const val GIT_OP_BRANCH = "branch"
const val GIT_OP_REVERT = "revert"

/** issue #281: the worktree verbs ride the SAME two-step confirmation machinery (one token semantics,
 *  never a second one) and therefore the same [GitActionPreview] / [GitActionResult] reply family. They
 *  are requested with their own frames ([AddWorktree] / [RemoveWorktree]) because their arguments have
 *  nothing in common with a file list, but the `op` they echo back belongs to this vocabulary. */
const val GIT_OP_WORKTREE_ADD = "worktree.add"
const val GIT_OP_WORKTREE_REMOVE = "worktree.remove"

/** Every verb the daemon will execute. The daemon rejects an `op` outside this set by name — it does not
 *  fall back to a default. Shared so a new app talking to a new daemon cannot invent a verb the daemon
 *  silently ignores, and so tests can assert the set has not quietly grown. */
val GIT_OPS: Set<String> = setOf(
    GIT_OP_STAGE, GIT_OP_UNSTAGE, GIT_OP_COMMIT, GIT_OP_FETCH, GIT_OP_PULL, GIT_OP_PUSH,
    GIT_OP_CHECKOUT, GIT_OP_BRANCH, GIT_OP_REVERT, GIT_OP_WORKTREE_ADD, GIT_OP_WORKTREE_REMOVE,
)

/** The verbs that MUST arrive twice: the first [GitAction] returns a [GitActionPreview] carrying a
 *  [GitActionPreview.confirmToken], and only a second [GitAction] echoing that token executes. Reserved
 *  for the two operations that destroy work — reverting a file and checking out over a dirty tree — plus
 *  #281's worktree removal. Everything else is one tap because it is recoverable in one tap; a
 *  confirmation there would only teach people to dismiss confirmations. */
val GIT_TWO_STEP_OPS: Set<String> = setOf(GIT_OP_REVERT, GIT_OP_CHECKOUT, GIT_OP_WORKTREE_REMOVE)

/** How long a [GitActionPreview.confirmToken] stays redeemable (§5 of GIT-PANEL.md). Single-use as well
 *  as short-lived: the daemon drops the token the moment it is consumed, so a replayed confirm frame
 *  cannot discard a second round of work the user made in between. */
const val GIT_CONFIRM_TTL_MS: Long = 60_000L

/** Cap on how many entries one [GitStatus] carries per group. A repository mid-rebase, or one where a
 *  build directory escaped .gitignore, can report tens of thousands of paths — enough to blow the relay
 *  frame. The daemon truncates each group at this count and sets [GitStatus.truncated]; the app says so
 *  instead of pretending the list is complete. A second, lower-level cap sits behind it: the daemon
 *  reads at most GitService.DIFF_CAP (200 KB) of any git output, and a read cut short also sets
 *  [GitStatus.truncated]. */
const val GIT_STATUS_MAX_ENTRIES: Int = 2000

// ---- models ----

/**
 * One path in a [GitStatus] group. [code] is the SINGLE porcelain letter git already uses, so the panel
 * teaches nothing new: `M` modified, `A` added, `D` deleted, `R` renamed, `C` copied, `U` unmerged,
 * `?` untracked.
 *
 * [adds]/[dels] come from `git diff --numstat` for the matching side (index or worktree) and are null
 * when git reported the file as binary or when the numstat pass was skipped — the app then shows no
 * counts rather than a wrong zero, exactly like [ChangedFile].
 *
 * [origPath] is the rename/copy SOURCE (porcelain v2 `2 ` records only); [xy] is the raw two-letter
 * porcelain field, carried only for unmerged rows so the app can name the conflict shape ("both
 * modified" for `UU`, "deleted by them" for `UD`, …) in the user's own language rather than the
 * daemon's. Both are trailing optionals: an older daemon omits them, an older app ignores them.
 */
@Serializable
data class GitFileEntry(
    val path: String,
    val code: String = "M",
    val adds: Int? = null,
    val dels: Int? = null,
    val origPath: String? = null,
    val xy: String? = null,
)

/**
 * One LOCAL branch, for the branch sheet. Remote-only branches are deliberately absent: checking one out
 * would create a detached HEAD or a tracking branch, neither of which is on the allow-list.
 *
 * [lastCommitAt] is the committer date in epoch SECONDS (0 when unreadable) — the sheet orders by it,
 * because "last touched" is the order people actually think in. [current] marks HEAD's branch.
 */
@Serializable
data class GitBranchInfo(
    val name: String,
    val current: Boolean = false,
    val lastCommitAt: Long = 0,
    val upstream: String? = null,
    /** #281: non-null = this branch is already checked out in ANOTHER worktree at this path, so
     *  `git worktree add` for it would fail. The new-worktree sheet dims the row and names the holder
     *  instead of letting the tap fail. A trailing optional — omitted by a daemon without worktree
     *  support, ignored by an app without the sheet. */
    val checkedOutAt: String? = null,
)

/**
 * One entry of `git worktree list --porcelain` (issue #281). Fields map 1:1 onto porcelain lines so the
 * parser stays a transcription rather than an interpretation: `worktree`→[path], `HEAD`→[head],
 * `branch`→[branch] (refs/heads/… shortened), `bare`→[bare], `detached`→[detached], `locked`→[locked]
 * (+[lockReason] when git supplied one), `prunable`→[prunable] (+[prunableReason]).
 *
 * [isMain] is the FIRST entry git lists, which is the primary worktree by definition — it is never
 * removable, and the app omits the Remove item from its menu rather than disabling it.
 *
 * [dirty] is deliberately THREE-valued: true/false when a status pass ran for that checkout, and null =
 * "we have not looked yet". Collecting status for every checkout costs one git process each, so the list
 * pass runs them in parallel with a short budget and degrades to null on timeout (§3 of the design). The
 * card shows a grey "status unknown" chip and blocks nothing; removal re-reads it for real before acting.
 * [dirtyCount] accompanies a true.
 *
 * [activeSessionId]/[activeSessionTitle] come from the daemon's own SessionRegistry/LiveProcesses truth —
 * the same source as [DirectoryEntry.open] — and are what makes removal refuse outright. Note the third
 * reading of [activeSessionId]: null = nothing running here; a non-empty id = a daemon-owned session; the
 * EMPTY STRING = a `claude` process someone started in their own terminal, which holds the directory just
 * as firmly but has no session for the app to jump to. Clients test `!= null` for "in use" and only offer
 * a jump when the id is non-blank.
 */
@Serializable
data class WorktreeEntry(
    val path: String,
    val branch: String? = null,
    val head: String? = null,
    val isMain: Boolean = false,
    val detached: Boolean = false,
    val bare: Boolean = false,
    val locked: Boolean = false,
    val lockReason: String? = null,
    val prunable: Boolean = false,
    val prunableReason: String? = null,
    val dirty: Boolean? = null,
    val dirtyCount: Int? = null,
    val activeSessionId: String? = null,
    val activeSessionTitle: String? = null,
)

// ---- phone -> daemon ----

/**
 * phone -> daemon: read the whole repository's state for the Git tab (issue #280). [workdir] is the
 * active session's cwd, validated daemon-side against the same directory rules the files surface uses —
 * an arbitrary path is refused, never scanned. [convoId] scopes the reply to the live conversation and
 * matches the one-command-at-a-time backpressure key, exactly like [RunShellCommand].
 *
 * Reply is one [GitStatus]. Unlike [RunShellCommand] this raises NO approval card: it is a read, the argv
 * is assembled by the daemon, and the surface is owner-only. A daemon that predates this drops the frame
 * and the client times out to its "update the computer" state.
 *
 * [withBranches] asks the daemon to include the local branch list in the same reply (the branch sheet's
 * data) — one round trip instead of two. A daemon reading it false simply returns no branches.
 */
@Serializable
@SerialName("pocket/git.status")
data class FetchGitStatus(
    val convoId: String,
    val workdir: String,
    val withBranches: Boolean = false,
) : ToDaemon

/**
 * phone -> daemon: the unified diff of ONE path against the index ([staged] = true, `git diff --cached`)
 * or against the working tree ([staged] = false, `git diff`) — the two truths a partially staged file
 * has, which is the question the Git panel creates and the Working/Staged control answers.
 *
 * Distinct from [ReadFileDiff], which rebuilds a diff from the SESSION TRANSCRIPT and therefore only ever
 * shows what this agent did. This one asks git, so it also covers edits made in the terminal, and it is
 * the only one that can show the staged side at all. Both render through the same client DiffView.
 *
 * [path] is repository-relative with `/` separators as porcelain emits them — NOT translated on Windows
 * (the daemon feeds it straight back to git, which speaks `/` on every platform). It is passed to git
 * after a `--` end-of-options marker, so a file literally named `--cached` is a file, not a flag.
 * Reply is one [GitDiff].
 */
@Serializable
@SerialName("pocket/git.diff")
data class ReadGitDiff(
    val convoId: String,
    val workdir: String,
    val path: String,
    val staged: Boolean = false,
) : ToDaemon

/**
 * phone -> daemon: run ONE verb from [GIT_OPS] (issue #280). The daemon builds the argv array itself —
 * `ProcessBuilder(listOf(git, verb, …))`, never a shell string — and every path in [paths] is appended
 * after a `--` marker. Nothing in this frame reaches a command line as text.
 *
 * Which fields a verb reads:
 *  - [GIT_OP_STAGE] / [GIT_OP_UNSTAGE] / [GIT_OP_REVERT] → [paths] (file-level; hunks are out of scope);
 *  - [GIT_OP_COMMIT] → [message] (rejected when blank, and when the index is unmerged);
 *  - [GIT_OP_CHECKOUT] → [branch], which MUST name an existing LOCAL branch — the daemon checks it
 *    against its own `for-each-ref` listing, so the value is a selection, not free text;
 *  - [GIT_OP_BRANCH] → [branch] as the NEW name, validated against git's own ref-name rules;
 *  - [GIT_OP_FETCH] / [GIT_OP_PULL] / [GIT_OP_PUSH] → nothing (current branch, no remote choice).
 *
 * [confirmToken] is empty on the FIRST send of a [GIT_TWO_STEP_OPS] verb: the daemon answers with a
 * [GitActionPreview] naming exactly what would be lost, and only a second frame carrying that token
 * executes. Sending a token for a one-step verb is ignored; sending a stale/unknown/reused one fails
 * with [GitActionResult.ok] = false rather than executing.
 *
 * Reply is one [GitActionPreview] (first step of a two-step verb) or one [GitActionResult].
 */
@Serializable
@SerialName("pocket/git.action")
data class GitAction(
    val convoId: String,
    val workdir: String,
    val op: String,
    val paths: List<String> = emptyList(),
    val message: String? = null,
    val branch: String? = null,
    val confirmToken: String? = null,
) : ToDaemon

/**
 * phone -> daemon (issue #281): list every checkout of the repository [workdir] belongs to. Running
 * `git worktree list --porcelain` from ANY family member returns the whole family, so the session's own
 * cwd is enough of an anchor and no new path surface is introduced.
 *
 * [withStatus] asks the daemon to also decide dirty/clean per checkout — one extra git process each, run
 * in parallel with a short budget and degraded to [WorktreeEntry.dirty] = null on timeout. Reply is one
 * [WorktreeList].
 */
@Serializable
@SerialName("pocket/worktree.list")
data class ListWorktrees(
    val convoId: String,
    val workdir: String,
    val withStatus: Boolean = true,
) : ToDaemon

/**
 * phone -> daemon (issue #281): `git worktree add`. [branch] is either an existing local branch (
 * [createBranch] = false — the daemon verifies it exists and is not already checked out elsewhere) or a
 * NEW branch name to create off the repository's default branch ([createBranch] = true, `-b`).
 *
 * [path] is normally null: the daemon computes `<repoRoot>-worktrees/<branch-with-slashes-as-dashes>`,
 * a SIBLING of the repository. A supplied path is validated to sit directly inside that same
 * `<repoRoot>-worktrees` directory and is refused otherwise — `..`, absolute escapes and paths inside
 * the repository itself never reach `git worktree add`. This is L1 (one tap): it writes a new directory
 * and touches no existing data. Reply is one [GitActionResult] with op = [GIT_OP_WORKTREE_ADD].
 */
@Serializable
@SerialName("pocket/worktree.add")
data class AddWorktree(
    val convoId: String,
    val workdir: String,
    val branch: String,
    val createBranch: Boolean = false,
    val path: String? = null,
) : ToDaemon

/**
 * phone -> daemon (issue #281): `git worktree remove` for the linked checkout at [path]. Two-step, on the
 * SAME token machinery as [GIT_OP_REVERT] — the first send returns a [GitActionPreview] listing the
 * uncommitted work that would die and whether a session is running there.
 *
 * Two refusals are absolute and survive the confirm token: the MAIN worktree is never removable (git
 * would refuse anyway, but the daemon says so first and the app omits the menu item entirely), and a
 * checkout with a LIVE session is refused even with a valid token — pulling the directory out from under
 * a running agent is not something a confirmation should be able to buy. A dirty-but-idle checkout is
 * removed with `--force` once confirmed. Reply is [GitActionPreview] then [GitActionResult].
 */
@Serializable
@SerialName("pocket/worktree.remove")
data class RemoveWorktree(
    val convoId: String,
    val workdir: String,
    val path: String,
    val confirmToken: String? = null,
) : ToDaemon

// ---- daemon -> phone ----

/**
 * daemon -> phone: reply to [FetchGitStatus], parsed from `git status --porcelain=v2 --branch -z`.
 * Matched client-side on (convoId, workdir).
 *
 * [notARepo] is the ordinary, non-error outcome for a session whose cwd is not inside a git repository —
 * the app hides the Git tab rather than showing a failure. [ok] = false with [error] is the exceptional
 * one (git missing, timeout, unreadable directory).
 *
 * The four groups are disjoint and ordered the way the panel renders them: a path with both an index and
 * a worktree change appears in BOTH [staged] and [unstaged] (that is the partially-staged file whose two
 * truths the Working/Staged toggle exists for), a path in [conflicted] appears nowhere else — nothing can
 * be staged while the index is unmerged. Each group is capped at [GIT_STATUS_MAX_ENTRIES] with
 * [truncated] set when anything was dropped.
 *
 * [detached] means HEAD points at a commit, not a branch: [branch] then carries the short oid for display
 * and every verb that needs a branch is refused. [initial] means the repository has no commits yet
 * (`# branch.oid (initial)`), where HEAD does not resolve and unstaging goes through `rm --cached`.
 * [branches] is populated only when [FetchGitStatus.withBranches] asked for it.
 */
@Serializable
@SerialName("pocket/git.statusResult")
data class GitStatus(
    val convoId: String,
    val workdir: String,
    val ok: Boolean = true,
    val notARepo: Boolean = false,
    val error: String? = null,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val detached: Boolean = false,
    val initial: Boolean = false,
    val staged: List<GitFileEntry> = emptyList(),
    val unstaged: List<GitFileEntry> = emptyList(),
    val untracked: List<GitFileEntry> = emptyList(),
    val conflicted: List<GitFileEntry> = emptyList(),
    val truncated: Boolean = false,
    val branches: List<GitBranchInfo> = emptyList(),
    /** #281 composition: the repository has more than one checkout, so the Git panel header grows its
     *  worktree line and the Worktrees surface is worth offering. A trailing optional — a daemon without
     *  worktree support omits it and the app simply never offers the entrance. */
    val worktreeCount: Int = 0,
) : ToPhone

/**
 * daemon -> phone: reply to [ReadGitDiff]. [diff] is unified-diff text in git's own format, rendered by
 * the same client parser [FileDiff] feeds. [adds]/[dels] are counted from that text, so they always agree
 * with what is on screen. Capped server-side; [truncated] says the tail was dropped.
 *
 * [ok] = false with [error] covers "no diff for this path on this side" (a staged-only file has nothing
 * on the working side, and vice versa) as well as real failures — the app shows its existing empty state
 * either way. [staged] echoes the request so a late reply for the other side cannot overwrite the
 * current one.
 */
@Serializable
@SerialName("pocket/git.diffResult")
data class GitDiff(
    val convoId: String,
    val workdir: String,
    val path: String,
    val staged: Boolean = false,
    val ok: Boolean = true,
    val error: String? = null,
    val diff: String? = null,
    val adds: Int = 0,
    val dels: Int = 0,
    val truncated: Boolean = false,
) : ToPhone

/**
 * daemon -> phone: step one of a [GIT_TWO_STEP_OPS] verb — what would be lost, and the [confirmToken]
 * that buys the loss. The token is a UUID, valid for [GIT_CONFIRM_TTL_MS] from [expiresAtMs] minus that
 * window, single-use, and bound to (convoId, op, target): it cannot be replayed, cannot be moved to
 * another conversation, and cannot be aimed at a different file or worktree than the one previewed.
 *
 * [files] itemises the paths whose working-tree changes die, with their +/− counts, so the sheet can
 * name them in mono instead of summarising them into a number the user has to trust. [summary] is a
 * short machine-readable hint ("dirty-checkout", "revert", "worktree-dirty", "worktree-clean") — the
 * user-facing sentence is composed by the app in the user's language, never here.
 *
 * [blocked] = true means the confirm can NEVER succeed and the app renders an information panel with an
 * inert Remove button instead of a destructive one: today that is #281's "a session is running in this
 * worktree". A token is still issued (so the flow is uniform) but redeeming it fails.
 */
@Serializable
@SerialName("pocket/git.preview")
data class GitActionPreview(
    val convoId: String,
    val op: String,
    val confirmToken: String,
    val expiresAtMs: Long = 0,
    val files: List<GitFileEntry> = emptyList(),
    val summary: String = "",
    val path: String? = null,
    val branch: String? = null,
    val blocked: Boolean = false,
    val blockedReason: String? = null,
    /** The workdir of the request this answers, echoed verbatim. Matching on [convoId] alone is not
     *  enough: a session can switch directory mid-conversation, and a preview still in flight would then
     *  land on the new directory's panel offering to discard files it never looked at. A trailing
     *  optional — an older daemon omits it and a client falls back to the convoId-only match. */
    val workdir: String? = null,
) : ToPhone

/**
 * daemon -> phone: the outcome of a [GitAction] / [AddWorktree] / [RemoveWorktree]. [op] echoes the verb
 * so a client with several requests in flight can route it.
 *
 * [stderr] is git's own first lines, capped and VERBATIM: a developer recognises `(fetch first)` or
 * `! [rejected]` instantly, and translating git's words into ours would only make them unsearchable. Our
 * own product-level sentence, when there is one, is [error] — the two are shown together, ours above.
 *
 * [notFastForward] singles out the one refusal that is a feature rather than a fault: `pull --ff-only`
 * declining because the upstream diverged. The app answers it in amber with "finish this on the
 * computer", not in red, because nothing broke and nothing was lost.
 *
 * [statusAfter] is a full [GitStatus] snapshot taken right after a successful mutation, so the panel
 * refreshes in place with no second round trip and no polling. Nested as a concrete type (no polymorphic
 * "t" key inside): null whenever the daemon could not re-read, and for verbs that change nothing local.
 */
@Serializable
@SerialName("pocket/git.result")
data class GitActionResult(
    val convoId: String,
    val op: String,
    val ok: Boolean = false,
    val error: String? = null,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val notFastForward: Boolean = false,
    val statusAfter: GitStatus? = null,
    /** The workdir of the request this answers — see [GitActionPreview.workdir] for why convoId alone is
     *  not a sufficient identity. Trailing optional, same two-way degradation. */
    val workdir: String? = null,
    /**
     * For a successful `worktree.add` only: the checkout directory the daemon created — the fact the
     * post-create "open a session here?" layer needs (#281 功能范围, restored by #294 真机反馈). The
     * daemon computed this path itself (policy dir + slug), so echoing it is not client input coming
     * back. Trailing optional: an older daemon omits it and the client shows the receipt without the
     * open-here verb rather than guessing at a path.
     */
    val path: String? = null,
) : ToPhone

/**
 * The ONE line of a failed git command's stderr that is worth showing a phone (issue #280 真机反馈 5).
 *
 * git writes the useful sentence in the middle of its output, never at the top: a rejected push opens
 * with `To https://…`, which names the remote and says nothing about what went wrong. Taking the first
 * line — which is what a naive strip does — therefore shows the user the least informative line git
 * printed. This picks by MEANING instead, in four ranks:
 *
 *  0. `! [rejected]` / `! [remote rejected]` — git's own verdict line, and it carries the reason in
 *     parentheses (`(fetch first)`, `(non-fast-forward)`);
 *  1. `fatal:` — how a refused `pull --ff-only` speaks;
 *  2. `error:` — the summary line of a failed push;
 *  3. a line carrying the reason in parentheses even without a marker prefix;
 *  4. anything else non-blank that is not the `To <remote>` banner.
 *
 * Ties inside a rank go to the earliest line. Returns "" when stderr holds nothing but `To` banners and
 * blanks — the caller then falls back to our own sentence, which beats echoing a URL at the user. The
 * chosen line is git's own words VERBATIM (trimmed): a developer searches for `(fetch first)`, and a
 * paraphrase of it would be unsearchable.
 */
fun gitStderrHighlight(stderr: String): String {
    var best = ""
    var bestRank = Int.MAX_VALUE
    for (raw in stderr.lineSequence()) {
        val line = raw.trim()
        val rank = gitStderrLineRank(line)
        if (rank != null && rank < bestRank) {
            bestRank = rank
            best = line
            if (rank == 0) break
        }
    }
    return best
}

/** null = this line can never be the one we show (blank, or the `To <remote>` banner). */
private fun gitStderrLineRank(line: String): Int? {
    if (line.isEmpty()) return null
    val lower = line.lowercase()
    return when {
        line.startsWith("!") && line.contains('[') -> 0
        lower.startsWith("fatal:") -> 1
        lower.startsWith("error:") -> 2
        REASON_MARKERS.any { it in lower } -> 3
        // `To https://…` / `To ../bare.git` is the banner naming the remote, never the reason
        line.startsWith("To ") || line == "To" -> null
        else -> 4
    }
}

private val REASON_MARKERS = listOf(
    "(fetch first)", "(non-fast-forward)", "(unpacker error)", "(pre-receive hook declined)",
    "(permission denied)", "(cannot lock ref)",
)

/**
 * daemon -> phone (issue #281): reply to [ListWorktrees]. [repoRoot] is the main worktree's path — the
 * anchor the app shows as the surface's subtitle and the base of the `<repoRoot>-worktrees` policy
 * directory. [worktrees] is in git's own order, which puts the main worktree first.
 *
 * [notARepo] / [ok] / [error] carry the same three-way meaning as [GitStatus]: not-a-repository is a
 * normal answer that hides the surface, ok=false is a failure worth reporting.
 */
@Serializable
@SerialName("pocket/worktree.listResult")
data class WorktreeList(
    val convoId: String,
    val workdir: String,
    val ok: Boolean = true,
    val notARepo: Boolean = false,
    val error: String? = null,
    val repoRoot: String? = null,
    val worktrees: List<WorktreeEntry> = emptyList(),
) : ToPhone
