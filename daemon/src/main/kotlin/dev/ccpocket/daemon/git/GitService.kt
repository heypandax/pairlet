package dev.ccpocket.daemon.git

import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AddWorktree
import dev.ccpocket.protocol.FetchGitStatus
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GIT_CONFIRM_TTL_MS
import dev.ccpocket.protocol.GIT_OPS
import dev.ccpocket.protocol.GIT_OP_BRANCH
import dev.ccpocket.protocol.GIT_OP_CHECKOUT
import dev.ccpocket.protocol.GIT_OP_COMMIT
import dev.ccpocket.protocol.GIT_OP_FETCH
import dev.ccpocket.protocol.GIT_OP_PULL
import dev.ccpocket.protocol.GIT_OP_PUSH
import dev.ccpocket.protocol.GIT_OP_REVERT
import dev.ccpocket.protocol.GIT_OP_STAGE
import dev.ccpocket.protocol.GIT_OP_UNSTAGE
import dev.ccpocket.protocol.GIT_OP_WORKTREE_ADD
import dev.ccpocket.protocol.GIT_OP_WORKTREE_REMOVE
import dev.ccpocket.protocol.GIT_STATUS_MAX_ENTRIES
import dev.ccpocket.protocol.GIT_TWO_STEP_OPS
import dev.ccpocket.protocol.GitAction
import dev.ccpocket.protocol.GitActionPreview
import dev.ccpocket.protocol.GitActionResult
import dev.ccpocket.protocol.GitBranchInfo
import dev.ccpocket.protocol.GitDiff
import dev.ccpocket.protocol.GitFileEntry
import dev.ccpocket.protocol.GitStatus
import dev.ccpocket.protocol.ListWorktrees
import dev.ccpocket.protocol.ReadGitDiff
import dev.ccpocket.protocol.RemoveWorktree
import dev.ccpocket.protocol.WorktreeEntry
import dev.ccpocket.protocol.WorktreeList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * The Git panel's execution engine (issue #280) and, on the same foundation, worktree management
 * (issue #281). See `docs/design/GIT-PANEL.md` §3 and `docs/design/WORKTREE-MANAGEMENT.md` §5.
 *
 * ## Why this is not [dev.ccpocket.daemon.shell.ShellService] with a git prefix
 *
 * The quick terminal takes a free-text command, and it pays for that with an approval card per call. The
 * Git panel takes NO text: every process this class starts is assembled here from a closed vocabulary
 * ([GIT_OPS]) plus arguments that are either daemon-computed or checked against a list git itself
 * produced. That is what buys zero-approval reads and one-tap writes, and it only holds while three
 * rules hold:
 *
 *  1. **argv, never a shell.** `ProcessBuilder(listOf(git, "add", "--", path))`. There is no `/bin/sh -c`
 *     anywhere in this file, so no metacharacter in a filename means anything. (ShellService's
 *     `/bin/sh -c` is correct THERE — the user typed a command line and expects shell semantics.)
 *  2. **`--` before every path.** Git's option parser stops there, so a file literally named `--cached`
 *     or `-rf` is a file. Every verb that names paths passes them after the marker.
 *  3. **Nothing free-text reaches an argv slot that git reads as a ref.** A checkout target must appear
 *     in our own `for-each-ref` listing; a new branch name must pass [GitPorcelain.isValidBranchName]
 *     first; a remote must appear in `git remote`; a worktree path is computed by us and must land
 *     inside `<repo>-worktrees/`. The one genuinely free string, a commit message, is the value of `-m`
 *     and can never be read as an option.
 *
 * ## Where the processes run
 *
 * Always at the repository TOP LEVEL, not the session's cwd. Porcelain paths are repository-root
 * relative, so anchoring the process there is what makes the paths we hand back and the paths we later
 * hand in the same strings. The session's workdir is only ever the ANCHOR used to find that top level —
 * it is validated by the caller (DirectoryService.validateWorkdir) exactly like the files surface.
 *
 * ## Two-step confirmation
 *
 * [GIT_TWO_STEP_OPS] answer their first request with a [GitActionPreview] carrying a single-use UUID
 * valid for [GIT_CONFIRM_TTL_MS]. The token is bound to (convoId, op, repo root, target) so it cannot be
 * replayed, moved to another conversation, or re-aimed at a different file/worktree. #281 rides this
 * same table — there is deliberately no second token semantics in the codebase.
 *
 * Owner-only is enforced OUTSIDE this class (GuestCaps/BridgeCaps/CollaboratorCaps default-deny plus an
 * explicit three-credential check in RequestRouter): this class assumes it is already talking to the
 * owner and concerns itself with the git-level guards.
 */
class GitService(
    /** Live daemon conversations grouped by cwd — SessionRegistry.liveByCwd(), the same truth the project
     *  list's "open/executing" dot uses. #281 refuses to remove a worktree that appears here. */
    private val liveByCwd: suspend () -> Map<String, List<ActiveSession>> = { emptyMap() },
    /** cwds of `claude` processes started OUTSIDE the daemon (LiveProcesses.claudeCwds()). A worktree held
     *  by someone's terminal is just as much "in use" as one the daemon owns. */
    private val externalCwds: suspend () -> Set<String> = { emptySet() },
    private val explicitGitBin: String? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** How long the whole per-checkout dirty scan may take before every unfinished one degrades to
     *  "unknown". Injectable purely so a test on a loaded machine measures the LOGIC and not the box:
     *  the production value is a UX budget, and a wall clock in an assertion is a flake waiting to fire. */
    private val worktreeStatusBudgetMs: Long = WORKTREE_STATUS_BUDGET_MS,
) {
    private val log = logger("Git")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** convoIds with a MUTATING verb in flight. Reads are never gated: the panel refreshes freely, and a
     *  status scan cannot corrupt anything. Mirrors ShellService's one-in-flight rule — a buggy or hostile
     *  client must not be able to fan out unbounded git processes. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val confirms = ConcurrentHashMap<String, Confirm>()

    private data class Confirm(
        val op: String,
        val convoId: String,
        val root: String,
        val paths: List<String>,
        val branch: String?,
        val target: String?,
        val expiresAt: Long,
        /** true = the preview already knows the confirm must fail (#281: a session runs in this worktree).
         *  A token is still minted so the client flow is uniform; redeeming it refuses. */
        val blocked: Boolean = false,
    )

    // ------------------------------------------------------------------ reads

    /** `git status --porcelain=v2 --branch -z` for the whole repository [workdir] sits in. */
    suspend fun status(f: FetchGitStatus, workdir: Path): GitStatus = statusAt(f.convoId, f.workdir, workdir, f.withBranches)

    /**
     * [echo] is the workdir string the CLIENT sent, replayed verbatim; [anchor] is the canonical directory
     * the router validated. They differ whenever the session sits in a SUBDIRECTORY of the repository (git
     * runs at the top level) or the path was tilde-expanded / symlink-resolved on the way in. The client
     * matches a reply on the string it sent — the same contract [SessionFiles] follows — so echoing the
     * canonical form instead would silently drop every reply for a subdirectory session.
     */
    private suspend fun statusAt(convoId: String, echo: String, anchor: Path, withBranches: Boolean): GitStatus {
        val repo = repoOf(anchor)
            ?: return GitStatus(convoId, echo, ok = true, notARepo = true)
        val exe = gitBin()
            ?: return GitStatus(convoId, echo, ok = false, error = GIT_NOT_FOUND)
        val st = readStatus(exe, repo.root)
            ?: return GitStatus(convoId, echo, ok = false, error = "git status failed")
        val branches = if (withBranches) readBranches(exe, repo) else emptyList()
        return GitStatus(
            convoId = convoId,
            workdir = echo,
            branch = st.branch, upstream = st.upstream, ahead = st.ahead, behind = st.behind,
            detached = st.detached, initial = st.initial,
            staged = st.staged, unstaged = st.unstaged, untracked = st.untracked, conflicted = st.conflicted,
            truncated = st.truncated,
            branches = branches,
            worktreeCount = repo.worktreeCount,
        )
    }

    /** One file's unified diff, index side ([ReadGitDiff.staged]) or working side. */
    suspend fun diff(f: ReadGitDiff, workdir: Path): GitDiff {
        val fail = { why: String -> GitDiff(f.convoId, f.workdir, f.path, f.staged, ok = false, error = why) }
        val repo = repoOf(workdir) ?: return fail("not a git repository")
        val exe = gitBin() ?: return fail(GIT_NOT_FOUND)
        // `--` terminates option parsing: a file named "--cached" is a file.
        val base = listOf("diff", "--no-color") + (if (f.staged) listOf("--cached") else emptyList()) + listOf("--", f.path)
        var out = git(exe, repo.root, base, readOnly = true).out
        if (out.isBlank() && !f.staged) {
            // untracked: git has no recorded side for it, so diff it against the null device. --no-index
            // exits 1 when the files differ, which is the normal success path here.
            val nul = if (isWindows) "NUL" else "/dev/null"
            out = git(exe, repo.root, listOf("diff", "--no-color", "--no-index", "--", nul, f.path), readOnly = true).out
        }
        if (out.isBlank()) return fail("no changes on this side")
        val truncated = out.length > DIFF_CAP
        val text = if (truncated) out.take(DIFF_CAP) else out
        val (adds, dels) = GitPorcelain.countDiffLines(text)
        return GitDiff(f.convoId, f.workdir, f.path, f.staged, ok = true, diff = text, adds = adds, dels = dels, truncated = truncated)
    }

    /** `git worktree list --porcelain`, enriched with per-checkout dirty state and live-session info. */
    suspend fun listWorktrees(f: ListWorktrees, workdir: Path): WorktreeList {
        val repo = repoOf(workdir)
            ?: return WorktreeList(f.convoId, f.workdir, ok = true, notARepo = true)
        val exe = gitBin()
            ?: return WorktreeList(f.convoId, f.workdir, ok = false, error = GIT_NOT_FOUND)
        val raw = git(exe, repo.root, listOf("worktree", "list", "--porcelain"), readOnly = true)
        if (raw.code != 0) {
            return WorktreeList(f.convoId, f.workdir, ok = false, error = raw.err.lineSequence().firstOrNull() ?: "git worktree list failed")
        }
        val parsed = GitPorcelain.parseWorktrees(raw.out)
        val live = liveIndex()
        val withStatus = if (f.withStatus) enrichDirty(exe, parsed) else parsed
        val entries = withStatus.map { w ->
            val hit = live[ProjectPaths.canonicalKey(w.path)]
            if (hit == null) w else w.copy(activeSessionId = hit.sessionId, activeSessionTitle = hit.title)
        }
        return WorktreeList(
            f.convoId, f.workdir,
            repoRoot = entries.firstOrNull()?.path ?: repo.root.toString(),
            worktrees = entries,
        )
    }

    // ----------------------------------------------------------------- writes

    /**
     * Run one [GIT_OPS] verb. Returns a [GitActionPreview] for the first step of a two-step verb, a
     * [GitActionResult] otherwise. Never throws: a failure is a frame the user can read.
     */
    @Suppress("ReturnCount")
    suspend fun act(f: GitAction, workdir: Path): Frame {
        if (f.op !in GIT_OPS) {
            // by NAME, not by falling back to a default verb — that is why `op` is a String on the wire
            return err(f.convoId, f.op, "unsupported git operation: ${f.op.take(40)}")
        }
        if (!inFlight.add(f.convoId)) return err(f.convoId, f.op, "a git operation is already running in this session")
        try {
            val repo = repoOf(workdir) ?: return err(f.convoId, f.op, "not a git repository").stamp(f.workdir)
            val exe = gitBin() ?: return err(f.convoId, f.op, GIT_NOT_FOUND).stamp(f.workdir)
            return runVerb(f, exe, repo).stamp(f.workdir)
        } catch (e: Exception) {
            log.warn("git ${f.op} failed", e)
            return err(f.convoId, f.op, e.message ?: "git operation failed").stamp(f.workdir)
        } finally {
            inFlight.remove(f.convoId)
        }
    }

    /**
     * Echo the request's own workdir onto a preview/result. Stamped at the PUBLIC boundary rather than at
     * each construction site: there are two dozen refusal paths, and a client that matched a reply on
     * convoId alone would show a stale preview on whatever directory the session switched to meanwhile.
     */
    private fun Frame.stamp(workdir: String): Frame = when (this) {
        is GitActionPreview -> copy(workdir = workdir)
        is GitActionResult -> copy(workdir = workdir)
        else -> this
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private suspend fun runVerb(f: GitAction, exe: Path, repo: Repo): Frame {
        val st = readStatus(exe, repo.root) ?: return err(f.convoId, f.op, "git status failed")
        return when (f.op) {
            GIT_OP_STAGE -> {
                val paths = f.paths.ifEmpty { return err(f.convoId, f.op, "no files given") }
                done(f, exe, repo, git(exe, repo.root, listOf("add", "--") + paths))
            }

            GIT_OP_UNSTAGE -> {
                val paths = f.paths.ifEmpty { return err(f.convoId, f.op, "no files given") }
                // With no HEAD yet there is nothing to reset a path back TO, so the un-stage of an initial
                // commit is `rm --cached` — it drops the index entry and leaves the file on disk.
                val argv = if (st.initial) listOf("rm", "--cached", "-r", "--") + paths
                else listOf("reset", "-q", "HEAD", "--") + paths
                done(f, exe, repo, git(exe, repo.root, argv))
            }

            GIT_OP_COMMIT -> {
                val msg = f.message?.trim().orEmpty()
                if (msg.isEmpty()) return err(f.convoId, f.op, "a commit needs a message")
                if (st.conflicted.isNotEmpty()) {
                    return err(f.convoId, f.op, "resolve the ${st.conflicted.size} conflicts before committing")
                }
                // -m's VALUE: free text can never be read as an option here. No --amend, no --no-verify.
                done(f, exe, repo, git(exe, repo.root, listOf("commit", "-m", msg), timeoutMs = LOCAL_TIMEOUT_MS))
            }

            GIT_OP_FETCH -> done(f, exe, repo, git(exe, repo.root, listOf("fetch", "--no-tags"), timeoutMs = NET_TIMEOUT_MS))

            GIT_OP_PULL -> {
                // --ff-only is the whole point: a diverged upstream is REPORTED, never merged or rebased.
                val r = git(exe, repo.root, listOf("pull", "--ff-only"), timeoutMs = NET_TIMEOUT_MS)
                val nonFf = r.code != 0 && looksNonFastForward(r.err + r.out)
                done(f, exe, repo, r, notFastForward = nonFf, error = if (nonFf) "the upstream diverged — finish this on the computer" else null)
            }

            GIT_OP_PUSH -> {
                val branch = st.branch?.takeIf { !st.detached } ?: return err(f.convoId, f.op, "detached HEAD — check out a branch first")
                val push = pushArgv(exe, repo, branch) ?: return err(f.convoId, f.op, "no upstream for $branch — set one on the computer")
                done(f, exe, repo, git(exe, repo.root, push, timeoutMs = NET_TIMEOUT_MS))
            }

            GIT_OP_BRANCH -> {
                val name = f.branch?.trim().orEmpty()
                if (!GitPorcelain.isValidBranchName(name)) return err(f.convoId, f.op, "not a valid branch name")
                if (readBranches(exe, repo).any { it.name == name }) return err(f.convoId, f.op, "branch $name already exists")
                // trailing `--` = "this is a REF, there is no pathspec": without it, a file named like the
                // branch makes `git checkout` ambiguous, and the wrong reading writes over a file.
                done(f, exe, repo, git(exe, repo.root, listOf("checkout", "-b", name, "--")))
            }

            GIT_OP_CHECKOUT -> checkout(f, exe, repo, st)
            GIT_OP_REVERT -> revert(f, exe, repo, st)

            else -> err(f.convoId, f.op, "unsupported git operation: ${f.op.take(40)}")
        }
    }

    /** Switch branches. A dirty tree makes this destructive, so it goes two-step and then uses --force. */
    private suspend fun checkout(f: GitAction, exe: Path, repo: Repo, st: GitPorcelain.Status): Frame {
        val target = f.branch?.trim().orEmpty()
        // a SELECTION, not free text: the name must be one git itself just listed for us
        if (readBranches(exe, repo).none { it.name == target }) return err(f.convoId, f.op, "no local branch named $target")
        if (target == st.branch && !st.detached) return err(f.convoId, f.op, "already on $target")
        // What the confirmed path actually runs is `checkout --force`, and that discards the INDEX as well
        // as the working tree. So the preview must name both sides — a sheet that listed only the working
        // changes would be promising something the command does not honour. (Reverting ONE FILE is the
        // other case, and there `checkout -- <path>` really does keep staged content; see revert().)
        val losable = (st.unstaged + st.staged + st.conflicted).distinctBy { it.path }
        if (losable.isEmpty()) {
            return done(f, exe, repo, git(exe, repo.root, listOf("checkout", target, "--")))
        }
        val token = f.confirmToken
        if (token.isNullOrEmpty()) {
            return preview(f.convoId, f.op, repo, withCounts(exe, repo, losable, staged = false), "dirty-checkout", branch = target)
        }
        consume(token, f.op, f.convoId, repo, target)
            ?: return err(f.convoId, f.op, "that confirmation expired — check the changes again")
        return done(f, exe, repo, git(exe, repo.root, listOf("checkout", "--force", target, "--")))
    }

    /** Throw away the working-tree changes of specific files. Always two-step; the index is left alone. */
    private suspend fun revert(f: GitAction, exe: Path, repo: Repo, st: GitPorcelain.Status): Frame {
        val paths = f.paths.ifEmpty { return err(f.convoId, f.op, "no files given") }
        val token = f.confirmToken
        if (token.isNullOrEmpty()) {
            val touched = (st.unstaged + st.conflicted).filter { it.path in paths.toSet() }
                .ifEmpty { paths.map { GitFileEntry(it, "M") } }
            return preview(f.convoId, f.op, repo, withCounts(exe, repo, touched, staged = false), "revert", target = paths.joinToString("\n"))
        }
        consume(token, f.op, f.convoId, repo, paths.joinToString("\n"))
            ?: return err(f.convoId, f.op, "that confirmation expired — check the changes again")
        // `checkout -- <paths>` restores the working tree from the INDEX: staged content survives, which
        // is exactly the sentence the confirm sheet promises.
        return done(f, exe, repo, git(exe, repo.root, listOf("checkout", "--") + paths))
    }

    // ------------------------------------------------------------- worktrees

    /** `git worktree add` — L1, one tap: it writes a NEW directory and touches no existing data. */
    @Suppress("ReturnCount")
    suspend fun addWorktree(f: AddWorktree, workdir: Path): GitActionResult =
        addWorktreeInner(f, workdir).copy(workdir = f.workdir)

    private suspend fun addWorktreeInner(f: AddWorktree, workdir: Path): GitActionResult {
        val op = GIT_OP_WORKTREE_ADD
        if (!inFlight.add(f.convoId)) return err(f.convoId, op, "a git operation is already running in this session")
        try {
            val repo = repoOf(workdir) ?: return err(f.convoId, op, "not a git repository")
            val exe = gitBin() ?: return err(f.convoId, op, GIT_NOT_FOUND)
            val branch = f.branch.trim()
            if (!GitPorcelain.isValidBranchName(branch)) return err(f.convoId, op, "not a valid branch name")
            val known = readBranches(exe, repo)
            if (f.createBranch && known.any { it.name == branch }) return err(f.convoId, op, "branch $branch already exists")
            if (!f.createBranch && known.none { it.name == branch }) return err(f.convoId, op, "no local branch named $branch")
            if (!f.createBranch) {
                val holder = GitPorcelain.parseWorktrees(git(exe, repo.root, listOf("worktree", "list", "--porcelain"), readOnly = true).out)
                    .firstOrNull { it.branch == branch }
                if (holder != null) return err(f.convoId, op, "$branch is already checked out at ${holder.path}")
            }
            val target = worktreePath(repo, branch, f.path)
                ?: return err(f.convoId, op, "a worktree must live directly in ${policyDir(repo)}")
            if (Files.exists(target)) return err(f.convoId, op, "${target.fileName} already exists")
            runCatching { Files.createDirectories(target.parent) }
            // argv order is git's: [-b <new>] <path> [<commit-ish>]. Both the path (computed by us, leaf
            // validated) and the base branch (from our own listing) are closed values, never client text.
            val argv = buildList {
                add("worktree"); add("add")
                if (f.createBranch) { add("-b"); add(branch) }
                add(target.toString())
                add(if (f.createBranch) defaultBranch(exe, repo, known) else branch)
            }
            val r = git(exe, repo.root, argv, timeoutMs = LOCAL_TIMEOUT_MS)
            return GitActionResult(
                f.convoId, op, ok = r.code == 0, exitCode = r.code,
                stdout = r.out.take(MAX_OUT), stderr = r.err.take(MAX_OUT),
                error = if (r.code == 0) null else "git worktree add failed",
            )
        } catch (e: Exception) {
            log.warn("git worktree add failed", e)
            return err(f.convoId, op, e.message ?: "git worktree add failed")
        } finally {
            inFlight.remove(f.convoId)
        }
    }

    /**
     * `git worktree remove` — two-step, and with two refusals a confirmation cannot buy: the MAIN
     * worktree is never removable, and neither is one with a live session (stop the agent first; the
     * phone does not pull the directory out from under running work).
     */
    @Suppress("ReturnCount")
    suspend fun removeWorktree(f: RemoveWorktree, workdir: Path): Frame =
        removeWorktreeInner(f, workdir).stamp(f.workdir)

    private suspend fun removeWorktreeInner(f: RemoveWorktree, workdir: Path): Frame {
        val op = GIT_OP_WORKTREE_REMOVE
        if (!inFlight.add(f.convoId)) return err(f.convoId, op, "a git operation is already running in this session")
        try {
            val repo = repoOf(workdir) ?: return err(f.convoId, op, "not a git repository")
            val exe = gitBin() ?: return err(f.convoId, op, GIT_NOT_FOUND)
            val all = GitPorcelain.parseWorktrees(git(exe, repo.root, listOf("worktree", "list", "--porcelain"), readOnly = true).out)
            val key = ProjectPaths.canonicalKey(f.path)
            val entry = all.firstOrNull { ProjectPaths.canonicalKey(it.path) == key }
                ?: return err(f.convoId, op, "not a worktree of this repository")
            if (entry.isMain) return err(f.convoId, op, "the main worktree cannot be removed")

            val live = liveIndex()[key]
            val target = entry.path
            val dirtyStatus = runCatching { readStatus(exe, Path.of(entry.path)) }.getOrNull()
            val losable = dirtyStatus?.let { it.unstaged + it.staged + it.untracked + it.conflicted }.orEmpty()

            val token = f.confirmToken
            if (token.isNullOrEmpty()) {
                return GitActionPreview(
                    convoId = f.convoId, op = op,
                    confirmToken = mint(op, f.convoId, repo, emptyList(), null, target, blocked = live != null),
                    expiresAtMs = nowMs() + GIT_CONFIRM_TTL_MS,
                    files = losable.take(GIT_STATUS_MAX_ENTRIES),
                    summary = if (losable.isEmpty()) "worktree-clean" else "worktree-dirty",
                    path = target, branch = entry.branch,
                    blocked = live != null,
                    blockedReason = live?.let { "a session is running in this worktree" },
                )
            }
            val confirm = consume(token, op, f.convoId, repo, target)
                ?: return err(f.convoId, op, "that confirmation expired — check the worktree again")
            // Re-checked at the side effect, not just at preview time: a session can START during the
            // 60-second window, and the refusal must win over an already-minted token (P1-5 in spirit).
            if (confirm.blocked || liveIndex()[key] != null) {
                return err(f.convoId, op, "a session is running in this worktree — stop it first")
            }
            val argv = buildList {
                add("worktree"); add("remove")
                if (losable.isNotEmpty()) add("--force")
                add(target)
            }
            val r = git(exe, repo.root, argv, timeoutMs = LOCAL_TIMEOUT_MS)
            return GitActionResult(
                f.convoId, op, ok = r.code == 0, exitCode = r.code,
                stdout = r.out.take(MAX_OUT), stderr = r.err.take(MAX_OUT),
                error = if (r.code == 0) null else "git worktree remove failed",
            )
        } catch (e: Exception) {
            log.warn("git worktree remove failed", e)
            return err(f.convoId, op, e.message ?: "git worktree remove failed")
        } finally {
            inFlight.remove(f.convoId)
        }
    }

    /**
     * Where a new worktree for [branch] may land: directly inside `<repoRoot>-worktrees/`, a SIBLING of
     * the repository. A caller-supplied [requested] path is accepted only if it names a single safe leaf
     * in that same directory — an absolute path elsewhere, a `..` escape, a nested path and anything
     * inside the repository itself all return null. The parent is resolved through the real filesystem
     * first, so a symlinked repo root cannot be used to aim the policy directory somewhere else.
     */
    internal fun worktreePath(repo: Repo, branch: String, requested: String?): Path? {
        val dir = policyDir(repo) ?: return null
        val leaf = when {
            requested.isNullOrBlank() -> GitPorcelain.branchSlug(branch)
            else -> {
                val p = runCatching { Path.of(requested).normalize() }.getOrNull() ?: return null
                // must be exactly one level inside the policy dir — compare the normalized PARENT, and
                // reject a relative name that carries any separator at all
                val name = p.fileName?.toString() ?: return null
                val parentOk = p.parent == null || p.parent.normalize() == dir
                if (!parentOk) return null
                name
            }
        }
        if (!GitPorcelain.isSafeLeaf(leaf)) return null
        val target = dir.resolve(leaf).normalize()
        if (target.parent != dir) return null
        if (target.startsWith(repo.root)) return null // never inside the repository
        return target
    }

    internal fun policyDir(repo: Repo): Path? {
        val name = repo.root.fileName?.toString() ?: return null
        val parent = repo.root.parent ?: return null
        return parent.resolve("$name-worktrees").normalize()
    }

    // ------------------------------------------------------------- internals

    /** The repository a workdir belongs to: top level, common git dir, and how many checkouts exist. */
    internal data class Repo(val root: Path, val commonDir: Path?, val worktreeCount: Int)

    private suspend fun repoOf(workdir: Path): Repo? {
        val exe = gitBin() ?: return null
        // one process for both answers; --show-toplevel fails inside a bare repo, which we treat as "not
        // a repository we can drive" rather than half-supporting it (bare layouts are out of scope, §E).
        val r = git(exe, workdir, listOf("rev-parse", "--show-toplevel", "--git-common-dir"), readOnly = true)
        if (r.code != 0) return null
        val lines = r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val top = lines.getOrNull(0)?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() } ?: return null
        // --git-common-dir may be relative to the top level ("./.git" when run from there)
        val common = lines.getOrNull(1)?.let { runCatching { top.resolve(it).normalize() }.getOrNull() }
        val linked = common?.resolve("worktrees")?.let { d ->
            runCatching { if (d.isDirectory()) Files.newDirectoryStream(d).use { s -> s.count { it.isDirectory() } } else 0 }.getOrDefault(0)
        } ?: 0
        return Repo(top, common, worktreeCount = 1 + linked)
    }

    private suspend fun readStatus(exe: Path, root: Path): GitPorcelain.Status? {
        // --untracked-files=all, NOT git's default "normal". "normal" collapses a wholly-new directory
        // into one `dir/` row, and the panel's whole vocabulary is per-file: a Stage pill, a tap to the
        // diff, a path in the confirm sheet. A directory row can do none of those. The cost — a repo whose
        // build output escaped .gitignore reporting thousands of paths — is what GIT_STATUS_MAX_ENTRIES
        // and the read cap below exist for.
        val r = git(exe, root, listOf("status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all"), readOnly = true)
        if (r.code != 0) return null
        // the drain cap can cut the output mid-record; the tail records are simply not parsed, so say
        // "truncated" rather than presenting a short list as complete.
        val capped = r.out.length >= DIFF_CAP
        val st = GitPorcelain.parseStatusV2(r.out).let { if (capped) it.copy(truncated = true) else it }
        // one numstat pass per side, merged onto the already-grouped entries by path
        val work = GitPorcelain.parseNumstat(git(exe, root, listOf("diff", "--numstat", "-z"), readOnly = true).out)
        val index = GitPorcelain.parseNumstat(git(exe, root, listOf("diff", "--cached", "--numstat", "-z"), readOnly = true).out)
        return st.copy(
            staged = GitPorcelain.withCounts(st.staged, index),
            unstaged = GitPorcelain.withCounts(st.unstaged, work),
        )
    }

    private suspend fun withCounts(exe: Path, repo: Repo, entries: List<GitFileEntry>, staged: Boolean): List<GitFileEntry> {
        val argv = if (staged) listOf("diff", "--cached", "--numstat", "-z") else listOf("diff", "--numstat", "-z")
        return GitPorcelain.withCounts(entries, GitPorcelain.parseNumstat(git(exe, repo.root, argv, readOnly = true).out))
    }

    private suspend fun readBranches(exe: Path, repo: Repo): List<GitBranchInfo> {
        val fmt = "%(refname:short)%00%(HEAD)%00%(committerdate:unix)%00%(upstream:short)"
        val r = git(exe, repo.root, listOf("for-each-ref", "--format=$fmt", "refs/heads"), readOnly = true)
        if (r.code != 0) return emptyList()
        val branches = GitPorcelain.parseBranches(r.out)
        // #281: say WHICH worktree already holds a branch, so the new-worktree sheet can dim the row
        // instead of letting `git worktree add` fail with its one genuinely confusing message.
        if (repo.worktreeCount <= 1) return branches
        val held = GitPorcelain.parseWorktrees(git(exe, repo.root, listOf("worktree", "list", "--porcelain"), readOnly = true).out)
            .mapNotNull { w -> w.branch?.let { it to w.path } }.toMap()
        return branches.map { b -> held[b.name]?.let { b.copy(checkedOutAt = it) } ?: b }
    }

    /** Push the CURRENT branch to its configured upstream. No -u, no --force, no remote chosen by the phone. */
    private suspend fun pushArgv(exe: Path, repo: Repo, branch: String): List<String>? {
        val remote = git(exe, repo.root, listOf("config", "--get", "branch.$branch.remote"), readOnly = true)
            .out.trim().ifEmpty { return null }
        val merge = git(exe, repo.root, listOf("config", "--get", "branch.$branch.merge"), readOnly = true)
            .out.trim().ifEmpty { return null }
        if (!merge.startsWith("refs/heads/")) return null
        // the remote NAME comes from repository config, not the client — but check it against the real
        // remote list anyway, so a hand-edited config cannot smuggle something option-shaped into argv
        val known = git(exe, repo.root, listOf("remote"), readOnly = true).out.lineSequence().map { it.trim() }.toSet()
        if (remote !in known || !remote.all { it.isLetterOrDigit() || it in "._-" }) return null
        return listOf("push", remote, "HEAD:$merge")
    }

    /** The branch a new worktree's branch is cut from: origin/HEAD if known, else main/master, else HEAD. */
    private suspend fun defaultBranch(exe: Path, repo: Repo, known: List<GitBranchInfo>): String {
        val head = git(exe, repo.root, listOf("symbolic-ref", "--short", "refs/remotes/origin/HEAD"), readOnly = true)
            .out.trim().removePrefix("origin/")
        if (head.isNotEmpty() && known.any { it.name == head }) return head
        for (candidate in listOf("main", "master")) if (known.any { it.name == candidate }) return candidate
        return "HEAD"
    }

    /** Per-worktree dirty state, collected in parallel under one budget — a timeout degrades to null
     *  ("we have not looked yet"), which the card shows as a grey chip and which blocks nothing. */
    private suspend fun enrichDirty(exe: Path, trees: List<WorktreeEntry>): List<WorktreeEntry> = coroutineScope {
        val jobs = trees.map { w ->
            async {
                if (w.bare || w.prunable) return@async w
                val dir = runCatching { Path.of(w.path).toRealPath() }.getOrNull() ?: return@async w
                val st = readStatus(exe, dir) ?: return@async w
                w.copy(dirty = st.dirty, dirtyCount = st.dirtyCount)
            }
        }
        withTimeoutOrNull(worktreeStatusBudgetMs) { jobs.map { it.await() } }
            ?: trees.also { jobs.forEach { j -> j.cancel() } }
    }

    /** cwd (canonical) -> the live session there, from the daemon's own registry plus external claudes. */
    private suspend fun liveIndex(): Map<String, ActiveSession> {
        val out = HashMap<String, ActiveSession>()
        runCatching { liveByCwd() }.getOrDefault(emptyMap()).forEach { (cwd, sessions) ->
            sessions.firstOrNull()?.let { out[ProjectPaths.canonicalKey(cwd)] = it }
        }
        runCatching { externalCwds() }.getOrDefault(emptySet()).forEach { cwd ->
            out.getOrPut(ProjectPaths.canonicalKey(cwd)) { ActiveSession(sessionId = "", title = null) }
        }
        return out
    }

    // ------------------------------------------------------- two-step tokens

    private fun mint(op: String, convoId: String, repo: Repo, paths: List<String>, branch: String?, target: String?, blocked: Boolean = false): String {
        val now = nowMs()
        confirms.entries.removeIf { it.value.expiresAt <= now } // opportunistic prune; the table stays tiny
        val token = UUID.randomUUID().toString()
        confirms[token] = Confirm(op, convoId, repo.root.toString(), paths, branch, target, now + GIT_CONFIRM_TTL_MS, blocked)
        return token
    }

    /** Redeem a token ONCE. Null when unknown, expired, or aimed at a different conversation/verb/target. */
    private fun consume(token: String, op: String, convoId: String, repo: Repo, target: String?): Confirm? {
        val c = confirms.remove(token) ?: return null
        if (c.expiresAt <= nowMs()) return null
        if (c.op != op || c.convoId != convoId || c.root != repo.root.toString()) return null
        if (c.target != target) return null
        return c
    }

    private fun preview(convoId: String, op: String, repo: Repo, files: List<GitFileEntry>, summary: String, branch: String? = null, target: String? = null) =
        GitActionPreview(
            convoId = convoId, op = op,
            confirmToken = mint(op, convoId, repo, files.map { it.path }, branch, target ?: branch),
            expiresAtMs = nowMs() + GIT_CONFIRM_TTL_MS,
            files = files.take(GIT_STATUS_MAX_ENTRIES),
            summary = summary,
            branch = branch,
        )

    // -------------------------------------------------------------- plumbing

    private suspend fun done(
        f: GitAction,
        exe: Path,
        repo: Repo,
        r: Exec,
        notFastForward: Boolean = false,
        error: String? = null,
    ): GitActionResult {
        val ok = r.code == 0
        // one extra status read on success so the panel refreshes in place — no second round trip from
        // the phone, and no polling anywhere.
        val after = if (ok) runCatching { statusAt(f.convoId, f.workdir, repo.root, withBranches = false) }.getOrNull() else null
        return GitActionResult(
            convoId = f.convoId, op = f.op, ok = ok, exitCode = r.code,
            stdout = r.out.take(MAX_OUT), stderr = r.err.take(MAX_OUT),
            notFastForward = notFastForward,
            error = error ?: if (ok) null else (r.failure ?: "git ${f.op} failed"),
            statusAfter = after,
        )
    }

    private fun err(convoId: String, op: String, why: String) =
        GitActionResult(convoId, op, ok = false, error = why, exitCode = -1)

    internal data class Exec(val code: Int, val out: String, val err: String, val timedOut: Boolean = false, val failure: String? = null)

    @Volatile private var cachedBin: Path? = null

    private fun gitBin(): Path? = cachedBin ?: runCatching {
        ExecutableResolver.resolve(
            explicitGitBin, System.getenv("CC_POCKET_GIT_BIN"),
            if (isWindows) listOf("git.exe", "git.cmd", "git") else listOf("git"),
            fallbackDirs, "git executable not found",
        )
    }.getOrNull()?.also { cachedBin = it }

    private val fallbackDirs: List<String> = buildList {
        if (isWindows) {
            add("C:\\Program Files\\Git\\cmd")
            add("C:\\Program Files\\Git\\bin")
            add("C:\\Program Files (x86)\\Git\\cmd")
        } else {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin"); add("/bin")
            add("/Library/Developer/CommandLineTools/usr/bin")
        }
    }

    /**
     * Start one git process with an explicit argv — the ONLY place in this file that spawns anything.
     * Output is capped while still being drained (a chatty command must neither blow the relay frame nor
     * block on a full pipe), the wait is bounded, and a grandchild holding the pipe open cannot make us
     * wait forever for EOF. Same three-part shape as ShellService.execute, minus the shell.
     */
    private suspend fun git(exe: Path, dir: Path, args: List<String>, timeoutMs: Long = LOCAL_TIMEOUT_MS, readOnly: Boolean = false): Exec =
        withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(listOf(exe.toString()) + args).directory(dir.toFile()).redirectErrorStream(false)
                pb.environment().apply {
                    // never stop for a credential/passphrase prompt: a fetch that would have blocked a
                    // human at a TTY must fail fast with a readable error instead of hanging to timeout
                    put("GIT_TERMINAL_PROMPT", "0")
                    remove("GIT_ASKPASS")
                    remove("SSH_ASKPASS")
                    // git's own words, in the language a developer can search for — and the language the
                    // fast-forward refusal is detected in
                    put("LC_ALL", "C")
                    put("LANG", "C")
                    // reads must not fight the agent for index.lock
                    if (readOnly) put("GIT_OPTIONAL_LOCKS", "0")
                }
                val proc = pb.start()
                proc.outputStream.close() // no stdin for any verb we run
                val out = async { drainCapped(proc.inputStream.bufferedReader()) }
                val err = async { drainCapped(proc.errorStream.bufferedReader()) }
                val finished = proc.waitFor(timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                if (!finished) proc.destroyForcibly()
                val stdout = withTimeoutOrNull(READ_DRAIN_MS) { out.await() } ?: ""
                val stderr = withTimeoutOrNull(READ_DRAIN_MS) { err.await() } ?: ""
                if (!finished) Exec(-1, stdout, stderr, timedOut = true, failure = "git took too long and was stopped")
                else Exec(proc.exitValue(), stdout, stderr)
            } catch (e: Exception) {
                log.warn("git ${args.firstOrNull()} failed to start", e)
                Exec(-1, "", "", failure = e.message ?: "could not run git")
            }
        }

    private fun drainCapped(reader: java.io.Reader): String = reader.use { r ->
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var total = 0
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            if (total < DIFF_CAP) sb.append(buf, 0, minOf(n, DIFF_CAP - total))
            total += n
        }
        sb.toString()
    }

    internal companion object {
        const val GIT_NOT_FOUND = "git is not installed on the computer"
        const val MAX_OUT = 4_000 // stdout/stderr echoed back on an action frame
        const val DIFF_CAP = 200_000 // a single diff body; also the hard read cap on any git output
        const val LOCAL_TIMEOUT_MS = 30_000L
        const val NET_TIMEOUT_MS = 120_000L
        const val MAX_TIMEOUT_MS = 180_000L
        const val READ_DRAIN_MS = 3_000L
        const val WORKTREE_STATUS_BUDGET_MS = 4_000L

        /** git's own phrasings for "this pull would not be a fast-forward" (LC_ALL=C, so these are stable). */
        private val NON_FF_MARKERS = listOf(
            "not possible to fast-forward",
            "fatal: not possible to fast-forward, aborting",
            "divergent branches",
            "need to specify how to reconciliate",
        )

        internal fun looksNonFastForward(text: String): Boolean {
            val t = text.lowercase()
            return NON_FF_MARKERS.any { it in t }
        }
    }
}
