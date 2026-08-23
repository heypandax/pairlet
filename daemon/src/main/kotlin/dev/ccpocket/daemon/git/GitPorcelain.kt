package dev.ccpocket.daemon.git

import dev.ccpocket.protocol.GIT_STATUS_MAX_ENTRIES
import dev.ccpocket.protocol.GitBranchInfo
import dev.ccpocket.protocol.GitFileEntry
import dev.ccpocket.protocol.WorktreeEntry

/**
 * Pure parsers for git's machine-readable output (issue #280 / #281). Deliberately separated from
 * [GitService]: everything here is a String -> data transformation with no process, no filesystem and no
 * clock, so the whole parse matrix (rename, conflict, detached HEAD, empty repo, ahead/behind, bare,
 * prunable, locked) is unit-testable without a git installation.
 *
 * Two invariants hold throughout:
 *  - **Paths are repository-root relative with `/` separators, on every platform.** That is git's
 *    porcelain contract, and we do NOT translate it on Windows — the strings go straight back to git,
 *    which speaks `/` everywhere, and the client only ever displays them. (Learned the hard way: the
 *    daemon's own dirKey rewriting is what broke Windows before.)
 *  - **We read the `-z`/NUL-delimited variants, never the human ones.** Without `-z`, git C-quotes any
 *    path containing a space, quote, backslash or non-ASCII byte, and un-quoting that correctly is a
 *    parser we would get subtly wrong. NUL-delimited output has no quoting at all, so a filename can
 *    never be mistaken for a field boundary.
 */
internal object GitPorcelain {

    /** The parsed shape of `git status --porcelain=v2 --branch -z`. */
    data class Status(
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
    ) {
        /** Anything that would be lost or carried by a checkout — the dirty test the confirm sheet uses. */
        val dirtyCount: Int get() = staged.size + unstaged.size + untracked.size + conflicted.size
        val dirty: Boolean get() = dirtyCount > 0
    }

    /**
     * Parse `git status --porcelain=v2 --branch -z`.
     *
     * Record shapes (all NUL-separated, header lines first):
     * ```
     * # branch.oid <commit> | (initial)
     * # branch.head <branch> | (detached)
     * # branch.upstream <upstream>
     * # branch.ab +<ahead> -<behind>
     * 1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>                      ordinary change
     * 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <Xscore> <path>\0<origPath> rename/copy — origPath is its OWN record
     * u <XY> <sub> <m1> <m2> <m3> <mW> <h1> <h2> <h3> <path>            unmerged
     * ? <path>                                                          untracked
     * ! <path>                                                          ignored (we never ask for these)
     * ```
     * `XY` is (index status, worktree status) with `.` meaning unchanged. A path with BOTH set lands in
     * [Status.staged] and [Status.unstaged] — that is the partially staged file whose two truths the
     * Working/Staged toggle exists to show. An unmerged path lands ONLY in [Status.conflicted]: nothing
     * can be staged while the index is unmerged, which is also why the panel hides the Staged section
     * and disables Commit in that state.
     *
     * Each group is capped at [cap]; anything dropped sets [Status.truncated] rather than silently
     * shortening the list (a repo with a build directory outside .gitignore can report tens of thousands
     * of untracked paths, which would blow the relay frame).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun parseStatusV2(raw: String, cap: Int = GIT_STATUS_MAX_ENTRIES): Status {
        val recs = raw.split('\u0000')
        var branch: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        var detached = false
        var initial = false
        var oid: String? = null
        val staged = ArrayList<GitFileEntry>()
        val unstaged = ArrayList<GitFileEntry>()
        val untracked = ArrayList<GitFileEntry>()
        val conflicted = ArrayList<GitFileEntry>()

        var i = 0
        while (i < recs.size) {
            val rec = recs[i]
            i++
            if (rec.isEmpty()) continue
            when {
                rec.startsWith("# branch.oid ") -> {
                    val v = rec.removePrefix("# branch.oid ").trim()
                    if (v == "(initial)") initial = true else oid = v
                }
                rec.startsWith("# branch.head ") -> {
                    val v = rec.removePrefix("# branch.head ").trim()
                    if (v == "(detached)") detached = true else branch = v
                }
                rec.startsWith("# branch.upstream ") -> upstream = rec.removePrefix("# branch.upstream ").trim()
                rec.startsWith("# branch.ab ") -> {
                    // "+2 -1" — the two counters are always both present when the header line is emitted
                    for (tok in rec.removePrefix("# branch.ab ").trim().split(' ')) {
                        when (tok.firstOrNull()) {
                            '+' -> ahead = tok.drop(1).toIntOrNull() ?: 0
                            '-' -> behind = tok.drop(1).toIntOrNull() ?: 0
                        }
                    }
                }
                rec.startsWith("# ") -> Unit // an unknown header from a newer git: ignore, never fail the parse
                rec.startsWith("1 ") -> {
                    val f = rec.split(' ', limit = 9)
                    if (f.size < 9) continue
                    val (x, y) = xy(f[1]) ?: continue
                    val path = f[8]
                    if (x != '.') staged += GitFileEntry(path, x.toString())
                    if (y != '.') unstaged += GitFileEntry(path, y.toString())
                }
                rec.startsWith("2 ") -> {
                    val f = rec.split(' ', limit = 10)
                    if (f.size < 10) continue
                    val (x, y) = xy(f[1]) ?: continue
                    val path = f[9]
                    // the rename/copy SOURCE is the next NUL record, not a tab-separated tail of this one
                    val orig = recs.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    i++
                    if (x != '.') staged += GitFileEntry(path, x.toString(), origPath = orig)
                    if (y != '.') unstaged += GitFileEntry(path, y.toString(), origPath = orig)
                }
                rec.startsWith("u ") -> {
                    val f = rec.split(' ', limit = 11)
                    if (f.size < 11) continue
                    // the raw XY pair rides along so the app can say "both modified" / "deleted by them"
                    // in the user's own language — the daemon never composes that sentence.
                    conflicted += GitFileEntry(f[10], "U", xy = f[1])
                }
                rec.startsWith("? ") -> untracked += GitFileEntry(rec.substring(2), "?")
                rec.startsWith("! ") -> Unit // ignored files — we never pass --ignored, but be defensive
            }
        }

        // A detached HEAD has no branch name; show the short oid instead, and every branch-shaped verb
        // refuses upstream of here (GitService.requireBranch).
        if (detached && branch == null) branch = oid?.take(7)

        val truncated = staged.size > cap || unstaged.size > cap || untracked.size > cap || conflicted.size > cap
        return Status(
            branch = branch, upstream = upstream, ahead = ahead, behind = behind,
            detached = detached, initial = initial,
            staged = staged.take(cap), unstaged = unstaged.take(cap),
            untracked = untracked.take(cap), conflicted = conflicted.take(cap),
            truncated = truncated,
        )
    }

    private fun xy(field: String): Pair<Char, Char>? =
        if (field.length < 2) null else field[0] to field[1]

    /**
     * Merge `git diff --numstat -z` counts onto already-parsed entries, matching on path.
     *
     * The `-z` numstat record is `<adds>\t<dels>\t<path>` — EXCEPT for a rename, where the path field is
     * empty and the source and destination follow as two separate NUL records. A binary file reports `-`
     * for both counters, which becomes null (no counts shown) rather than a misleading zero.
     */
    fun parseNumstat(raw: String): Map<String, Pair<Int?, Int?>> {
        val recs = raw.split('\u0000')
        val out = LinkedHashMap<String, Pair<Int?, Int?>>()
        var i = 0
        while (i < recs.size) {
            val rec = recs[i]
            if (rec.isEmpty()) { i++; continue }
            val parts = rec.split('\t')
            if (parts.size < 3) { i++; continue }
            val adds = parts[0].toIntOrNull()
            val dels = parts[1].toIntOrNull()
            if (parts[2].isEmpty()) {
                // rename: <adds>\t<dels>\t \0 <from> \0 <to> — the counts belong to the destination
                recs.getOrNull(i + 2)?.takeIf { it.isNotEmpty() }?.let { out[it] = adds to dels }
                i += 3
            } else {
                out[parts[2]] = adds to dels
                i += 1
            }
        }
        return out
    }

    /** Apply [counts] to [entries] by path, leaving adds/dels null where numstat had nothing to say. */
    fun withCounts(entries: List<GitFileEntry>, counts: Map<String, Pair<Int?, Int?>>): List<GitFileEntry> =
        entries.map { e -> counts[e.path]?.let { (a, d) -> e.copy(adds = a, dels = d) } ?: e }

    /**
     * Parse `git for-each-ref --format=%(refname:short)%00%(HEAD)%00%(committerdate:unix)%00%(upstream:short) refs/heads`.
     * Records are newline-separated (a ref name can contain neither a newline nor a NUL), fields NUL-separated.
     * `%(HEAD)` is `*` for the checked-out branch. Ordered newest-commit-first, which is the order the
     * branch sheet renders and the order people actually think in.
     */
    fun parseBranches(raw: String): List<GitBranchInfo> =
        raw.split('\n')
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val f = line.split('\u0000')
                if (f.size < 3 || f[0].isEmpty()) return@mapNotNull null
                GitBranchInfo(
                    name = f[0],
                    current = f[1].trim() == "*",
                    lastCommitAt = f[2].trim().toLongOrNull() ?: 0L,
                    upstream = f.getOrNull(3)?.takeIf { it.isNotBlank() },
                )
            }
            .sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenByDescending { it.lastCommitAt })

    /**
     * Parse `git worktree list --porcelain`. Blocks are separated by a blank line; the FIRST block is the
     * primary worktree by definition (git lists it first), which is the one that can never be removed.
     *
     * Attribute lines are transcribed, not interpreted: `HEAD`, `branch refs/heads/x` (shortened),
     * `bare`, `detached`, `locked [reason]`, `prunable [reason]`. An unknown attribute from a newer git
     * is ignored rather than failing the whole list.
     */
    fun parseWorktrees(raw: String): List<WorktreeEntry> {
        val out = ArrayList<WorktreeEntry>()
        var path: String? = null
        var head: String? = null
        var branch: String? = null
        var bare = false
        var detached = false
        var locked = false
        var lockReason: String? = null
        var prunable = false
        var prunableReason: String? = null

        fun flush() {
            val p = path ?: return
            out += WorktreeEntry(
                path = p, branch = branch, head = head, isMain = out.isEmpty(),
                detached = detached, bare = bare,
                locked = locked, lockReason = lockReason,
                prunable = prunable, prunableReason = prunableReason,
            )
            path = null; head = null; branch = null
            bare = false; detached = false
            locked = false; lockReason = null
            prunable = false; prunableReason = null
        }

        for (line in raw.split('\n')) {
            val l = line.trimEnd('\r')
            when {
                l.isBlank() -> flush()
                l.startsWith("worktree ") -> { flush(); path = l.removePrefix("worktree ") }
                l.startsWith("HEAD ") -> head = l.removePrefix("HEAD ").trim()
                l.startsWith("branch ") -> branch = l.removePrefix("branch ").trim().removePrefix("refs/heads/")
                l == "bare" -> bare = true
                l == "detached" -> detached = true
                l == "locked" -> locked = true
                l.startsWith("locked ") -> { locked = true; lockReason = l.removePrefix("locked ").trim().ifBlank { null } }
                l == "prunable" -> prunable = true
                l.startsWith("prunable ") -> { prunable = true; prunableReason = l.removePrefix("prunable ").trim().ifBlank { null } }
            }
        }
        flush()
        return out
    }

    /**
     * git's own ref-name rules, the subset that matters for a branch (`git check-ref-format --branch`).
     * Applied BEFORE the name reaches an argv slot, so `-b` can never be handed something that reads as a
     * flag, and applied again by git itself — this is a fast, quotable refusal, not the only defence.
     */
    @Suppress("ReturnCount")
    fun isValidBranchName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.startsWith("-") || name.startsWith("/") || name.startsWith(".")) return false
        if (name.endsWith("/") || name.endsWith(".") || name.endsWith(".lock")) return false
        if (name.contains("..") || name.contains("//") || name.contains("@{")) return false
        if (name == "@") return false
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7F) return false
            if (c in " ~^:?*[\\") return false
        }
        // no path component may start with a dot or end with .lock
        return name.split('/').none { it.isEmpty() || it.startsWith(".") || it.endsWith(".lock") }
    }

    /**
     * The directory name a branch gets under `<repo>-worktrees/`: slashes flattened to dashes, which is
     * the policy the design board states in words ("with slashes flattened to dashes"). Nothing else is
     * rewritten — the name has already passed [isValidBranchName], so it holds no separator, no `..` and
     * no control character.
     */
    fun branchSlug(branch: String): String = branch.replace('/', '-')

    /** A single, safe directory leaf: what a caller-supplied worktree path's last segment must look like. */
    fun isSafeLeaf(name: String): Boolean =
        name.isNotEmpty() && name.length <= 120 && name != "." && name != ".." &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } && !name.startsWith(".")

    /** +/− line counts of a unified diff, counted from the text the client will actually render. */
    fun countDiffLines(diff: String): Pair<Int, Int> {
        var adds = 0
        var dels = 0
        for (line in diff.lineSequence()) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> adds++
                line.startsWith("-") -> dels++
            }
        }
        return adds to dels
    }
}
