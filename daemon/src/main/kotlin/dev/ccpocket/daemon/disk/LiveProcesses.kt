package dev.ccpocket.daemon.disk

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Working directories of currently-running `claude` CLI processes. A session idle between turns
 * doesn't touch its transcript, so mtime alone misses it — but its process is still alive with the
 * project as its cwd. This catches those (terminal sessions included), so a project shows as "live"
 * whenever a claude is actually running in it, not only while it streams.
 */
object LiveProcesses {
    /**
     * Memoized for [CWD_TTL_MS] like [codexCwds], and for the same reason: the ~10s project refresh paid a
     * full process enumeration plus an lsof fork every tick.
     *
     * Unlike Codex's, this set does NOT exclude the daemon's own claude children — a daemon-owned session's
     * project is live too, and [SessionRegistry.liveByCwd] is merged on top rather than substituted here.
     * That is pre-existing behavior, kept deliberately (see [externalPids]'s `excludeDaemonDescendants`).
     *
     * Its second consumer, GitService's worktree-in-use guard, tolerates the TTL: the worktree list the user
     * confirms against is itself a refresh old, and a daemon-owned agent still arrives fresh via liveByCwd.
     */
    fun claudeCwds(): Set<String> = claudeCwdMemo.get(System.currentTimeMillis(), ::claudePids, ::claudeCwdsOf)

    /** [claudeCwds]'s memo, with its clock and both probes injected so the TTL is testable. */
    internal fun claudeCwds(nowMs: Long, pids: () -> Set<Long>, cwdsOf: (Set<Long>) -> Set<String>): Set<String> =
        claudeCwdMemo.get(nowMs, pids, cwdsOf)

    /** Every `claude` pid, daemon-owned ones INCLUDED — see [claudeCwds]. The substring match (rather than
     *  [isClaudeCommand]'s separator-agnostic form) is the historical one and stays: on Windows there is no
     *  lsof, so a wider pid match could not change the answer, only the work done to reach the same empty set. */
    private fun claudePids(): Set<Long> =
        externalPids(excludeDaemonDescendants = false) { it.contains("/claude") } // .../bin/claude or .../share/claude/versions/<v>
            ?.toSet().orEmpty()

    private fun claudeCwdsOf(pids: Set<Long>): Set<String> = cwdsOf(pids).orEmpty()

    /**
     * Working directories of `codex` CLI processes that are NOT children of this daemon. Codex keeps its
     * rollout files under one global tree, so the project list cannot map a cwd to one particular rollout.
     * This coarse project-level view therefore uses process cwd; [externalCodexAt] separately uses the
     * exact rollout fd when deciding whether one session may be resumed safely.
     *
     * Only the exact `codex` executable counts: helpers such as `codex-code-mode-host` inherit the same cwd
     * and would otherwise duplicate/extend liveness after their owning CLI disappeared. Daemon-owned Codex
     * children are excluded because [SessionRegistry.liveByCwd] already reports them with authoritative
     * turn state.
     *
     * Memoized for [CWD_TTL_MS] because the project list refreshes every ~10 seconds and this is a
     * full OS process enumeration plus an lsof fork (PR #296 review). This is a DISPLAY signal — a project
     * row lighting up a few seconds late is invisible; see the never-cache rule on [externalAgentAt] for
     * the probes where the same staleness would be a correctness bug.
     */
    fun codexCwds(): Set<String> {
        // Windows has no lsof; read each codex pid's cwd via the PEB instead (issue #302). This is the
        // DISPLAY probe — best-effort is fine, an unreadable pid just doesn't light its row up.
        val readCwds: (Set<Long>) -> Set<String> =
            if (isWindows()) { pids -> pids.mapNotNull(ProcessCwd::of).toSet() } else ::codexCwdsOf
        return codexCwdMemo.get(System.currentTimeMillis(), ::codexPids, readCwds)
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** [codexCwds]'s memo, with its clock and both probes injected so the TTL is testable. */
    internal fun codexCwds(nowMs: Long, pids: () -> Set<Long>, cwdsOf: (Set<Long>) -> Set<String>): Set<String> =
        codexCwdMemo.get(nowMs, pids, cwdsOf)

    /**
     * One agent's "which cwds are live" answer, remembered for [CWD_TTL_MS]. Shared by the Claude and Codex
     * project-list signals — each holds its OWN instance, so one agent's churn never invalidates the other's.
     *
     * Legal ONLY for display signals; never wrap [externalAgentAt] in one (iron rule there).
     */
    private class CwdMemo {
        private class Snapshot(val atMs: Long, val pids: Set<Long>, val cwds: Set<String>)

        private val ref = java.util.concurrent.atomic.AtomicReference<Snapshot?>(null)

        fun get(nowMs: Long, pids: () -> Set<Long>, cwdsOf: (Set<Long>) -> Set<String>): Set<String> {
            val cached = ref.get()
            if (cached != null && nowMs - cached.atMs < CWD_TTL_MS) return cached.cwds
            val live = pids()
            if (live.isEmpty()) {
                ref.set(Snapshot(nowMs, emptySet(), emptySet()))
                return emptySet()
            }
            // Same processes as last time ⇒ same cwds: a running CLI does not chdir. Re-enumerating pids is
            // cheap and in-process; the lsof fork is what this skips.
            if (cached != null && cached.pids == live) {
                ref.set(Snapshot(nowMs, live, cached.cwds))
                return cached.cwds
            }
            val cwds = cwdsOf(live)
            ref.set(Snapshot(nowMs, live, cwds))
            return cwds
        }

        fun clear() = ref.set(null)
    }

    private val codexCwdMemo = CwdMemo()
    private val claudeCwdMemo = CwdMemo()

    /** External (non-daemon-descendant) `codex` pids; empty when the walk fails. */
    private fun codexPids(): Set<Long> =
        externalPids(excludeDaemonDescendants = true, matches = ::isCodexExecutable)?.toSet().orEmpty()

    private fun codexCwdsOf(pids: Set<Long>): Set<String> = cwdsOf(pids).orEmpty()

    /**
     * pids whose executable [matches], in enumeration order, or null when the process walk itself failed
     * (callers distinguish "none running" from "couldn't look").
     *
     * [excludeDaemonDescendants] drops this daemon's own children by pid lineage. It is a SAFETY filter for
     * the take-over probes — a daemon-owned agent must never be mistaken for a foreign writer — and true
     * everywhere except [claudeCwds], whose project-level liveness has always counted them.
     */
    private fun externalPids(excludeDaemonDescendants: Boolean, matches: (String) -> Boolean): List<Long>? {
        val selfPid = ProcessHandle.current().pid()
        return runCatching {
            ProcessHandle.allProcesses()
                .filter { matches(it.info().command().orElse("")) }
                .filter { !excludeDaemonDescendants || !hasAncestor(it, selfPid) }
                .map { it.pid() }
                .toList()
        }.getOrNull()
    }

    /** Working directories of [pids] via one lsof, or null when lsof fails/times out (Windows included:
     *  there is no such binary, so the start throws and every caller degrades to its own "unknown"). */
    private fun cwdsOf(pids: Collection<Long>): Set<String>? {
        if (pids.isEmpty()) return emptySet()
        return lsofLines(listOf("lsof", "-a", "-d", "cwd", "-p", pids.joinToString(","), "-Fn"))
            ?.filter { it.startsWith("n") }
            ?.map { it.substring(1) }
            ?.toSet()
    }

    /** Cross-test isolation: the memos are process-wide state on an object singleton. */
    internal fun clearForTest() {
        codexCwdMemo.clear()
        claudeCwdMemo.clear()
    }

    /** Exact basename match, separator-agnostic so the process probe behaves on Unix and Windows paths. */
    internal fun isCodexExecutable(command: String): Boolean {
        val name = command.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name == "codex" || name == "codex.exe"
    }

    /** Verdict of [externalClaudeAt]. UNKNOWN = "couldn't tell" — the CALLER picks the safe fallback
     *  (SessionRegistry keeps the mtime verdict: a spurious fork beats a two-writer clobber). */
    enum class ExternalClaude { PRESENT, ABSENT, UNKNOWN }

    /**
     * Is a `claude` CLI OUTSIDE this daemon's own process tree attached to this session — i.e. running
     * with [workdir] as its cwd, or holding [transcript] open? cwd is the PRIMARY signal: claude appends
     * to the .jsonl and closes it between writes, so the fd is rarely held (holding it is sufficient but
     * not necessary — checked as a strengthener). The daemon's own claude children share the same cwd;
     * they're excluded by pid lineage (every one is a descendant of this process — see AgentProcess).
     * Windows (no lsof), enumeration failure, or an lsof failure/timeout all return UNKNOWN.
     */
    fun externalClaudeAt(workdir: String, transcript: Path): ExternalClaude {
        // fdFirst = false: claude's fd is rarely held, so the cwd probe is the one that usually decides.
        return externalAgentAt(workdir, transcript, matchesCommand = ::isClaudeCommand, fdFirst = false)
    }

    /** Separator-agnostic like [isCodexExecutable]: a slash-only match can never see `C:\...\claude.exe`,
     *  which turned every Windows verdict into ABSENT ("no external claude") and waved a second writer
     *  through the take-over gate — the exact clobber UNKNOWN existed to prevent (PR #296 re-review). */
    internal fun isClaudeCommand(command: String): Boolean =
        command.replace('\\', '/').contains("/claude")

    /**
     * Codex counterpart of [externalClaudeAt], used by read-only observe / safe take-over.
     *
     * NEVER CACHED — see the rule on [externalAgentAt].
     *
     * Unlike Claude, current Codex keeps its active rollout open even while idle. That exact fd is the
     * authoritative signal for an old transcript. cwd remains useful for a RECENT rollout (there is a
     * short interval before/around the fd becoming visible), but must not make every old Codex session in
     * the same project look live merely because one unrelated Codex process has that cwd.
     */
    fun externalCodexAt(workdir: String, transcript: Path): ExternalClaude {
        val recent = runCatching {
            // the CODEX scanner's window: this freshness feeds the write-gate below, and borrowing
            // Claude's constant let an unrelated backend's display tuning move a safety input
            System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(transcript).toMillis() <
                dev.ccpocket.daemon.codex.CodexTranscriptScanner.LIVE_WINDOW_MS
        }.getOrDefault(false)
        // fdFirst = true: an idle Codex holds its rollout open, so the fd probe is the one that usually decides.
        return externalAgentAt(workdir, transcript, ::isCodexExecutable, allowCwdMatch = recent, fdFirst = true)
    }

    /**
     * IRON RULE — this probe and its two callers ([externalClaudeAt] / [externalCodexAt]) must NEVER be
     * cached or given a TTL, however hot they get. Their verdict is what decides whether we may take over
     * or write into a transcript another process owns; answering it from a world that is even a few seconds
     * old is exactly how you manufacture a silent two-writer clobber.
     *
     * The nearby [codexCwds] / [claudeCwds] memos are safe because neither answers that question: they are
     * coarse project-level LIVENESS, and the worst a stale one does is show a badge (or refuse/allow one
     * user-confirmed worktree removal) a beat late, against a list that was already a refresh old. Nothing
     * downstream of them writes a transcript on their word.
     *
     * If this needs to be faster, make the probe itself CHEAPER — do not remember its answer. The `fdFirst`
     * ordering below is what that looks like: one lsof instead of two on the common hit, same verdict on
     * every input (issue #303).
     */
    private fun externalAgentAt(
        workdir: String,
        transcript: Path,
        matchesCommand: (String) -> Boolean,
        allowCwdMatch: Boolean = true,
        fdFirst: Boolean = true,
    ): ExternalClaude {
        // Process ENUMERATION is cross-platform; only the fd/cwd probes below need lsof. Answering ABSENT
        // when no matching process exists at all keeps Windows verdicts sharp instead of a blanket UNKNOWN —
        // which matters since the Codex caller treats UNKNOWN as "assume the rollout is still held".
        val noLsof = System.getProperty("os.name").lowercase().contains("win")
        val external = externalPids(excludeDaemonDescendants = true, matches = matchesCommand)
        val pids = external.orEmpty() // only read once [agentVerdict] has ruled out the null/empty cases
        return agentVerdict(
            external = external,
            noLsof = noLsof,
            allowCwdMatch = allowCwdMatch,
            fdFirst = fdFirst,
            // Exact transcript ownership: an idle agent can hold a rollout for hours without touching its
            // mtime, and that writer must never be resumed by a second process.
            holdsTranscript = {
                val owners = pids.toSet()
                lsofLines(listOf("lsof", "-t", "--", transcript.toString()))
                    ?.mapNotNull { it.trim().toLongOrNull() }
                    ?.any { it in owners }
            },
            cwdMatches = { cwdMatchesWorkdir(pids, workdir) },
        )
    }

    /**
     * Does any of [pids] have [workdir] as its cwd? Three-valued (issue #302/#303): true = a match,
     * false = every pid read AND none matched, null = at least one pid's cwd could not be read.
     *
     * The null case is the safety invariant on Windows: an unreadable pid could be the very process
     * sitting in [workdir], so a single read miss forces "unknown" (→ the caller's assume-held), NEVER
     * ABSENT. macOS/Linux resolve all cwds in one lsof — that failing is already null.
     */
    private fun cwdMatchesWorkdir(pids: Collection<Long>, workdir: String): Boolean? {
        if (pids.isEmpty()) return false
        // match raw AND canonical forms: lsof reports resolved real paths, the workdir may arrive symlinked
        val targets = pathForms(workdir)
        if (isWindows()) {
            fun norm(p: String) = p.replace('\\', '/').trimEnd('/').lowercase() // Windows paths: case-insensitive
            val winTargets = targets.map(::norm).toSet()
            var anyUnread = false
            for (pid in pids) {
                val cwd = ProcessCwd.of(pid)
                if (cwd == null) { anyUnread = true; continue }
                if (norm(cwd) in winTargets) return true
            }
            return if (anyUnread) null else false
        }
        return cwdsOf(pids)?.any { it in targets }
    }

    /**
     * The pure decision behind [externalAgentAt], with both lsof probes as lazy suppliers (null = the probe
     * failed / timed out) so the ORDER they run in is itself testable.
     *
     * [fdFirst] only changes which probe is asked first, never the verdict: the fd hit and the cwd hit are
     * both sufficient for PRESENT, so with `allowCwdMatch` the answer is a symmetric
     * "PRESENT if either hits; UNKNOWN if the ones that could still have said PRESENT failed; else ABSENT".
     * Claude's fd is rarely held while its cwd almost always matches, so asking cwd first spares that agent
     * one lsof fork per probe on the common PRESENT path (issue #303). Codex is the mirror image and keeps
     * fd first — and when `allowCwdMatch` is false, only the fd probe may speak at all.
     */
    internal fun agentVerdict(
        external: List<Long>?,
        noLsof: Boolean,
        allowCwdMatch: Boolean,
        fdFirst: Boolean,
        holdsTranscript: () -> Boolean?,
        cwdMatches: () -> Boolean?,
    ): ExternalClaude {
        if (external == null) return ExternalClaude.UNKNOWN // the walk failed: we know nothing
        if (external.isEmpty()) return ExternalClaude.ABSENT // no matching agent outside the daemon at all
        if (noLsof) {
            // Windows: the lsof fd-ownership probe can't run, but ProcessCwd can read cwd (issue #302).
            // An fd-ONLY decision (allowCwdMatch=false — an OLD codex rollout judged purely by who holds
            // the file) therefore stays UNKNOWN → the safe "assume held". Otherwise defer to the cwd
            // matcher, whose three-valued answer keeps a read miss as UNKNOWN, never ABSENT.
            if (!allowCwdMatch) return ExternalClaude.UNKNOWN
            return when (cwdMatches()) {
                true -> ExternalClaude.PRESENT
                false -> ExternalClaude.ABSENT
                null -> ExternalClaude.UNKNOWN
            }
        }

        if (fdFirst || !allowCwdMatch) {
            val holders = holdsTranscript()
            if (holders == true) return ExternalClaude.PRESENT
            if (!allowCwdMatch) return if (holders == null) ExternalClaude.UNKNOWN else ExternalClaude.ABSENT
            return when (cwdMatches()) {
                true -> ExternalClaude.PRESENT
                false -> ExternalClaude.ABSENT
                null -> ExternalClaude.UNKNOWN
            }
        }

        val cwd = cwdMatches()
        if (cwd == true) return ExternalClaude.PRESENT // one fork was enough — the fd probe could only agree
        // cwd said no (or couldn't look): the fd is still sufficient on its own, so it gets the last word.
        if (holdsTranscript() == true) return ExternalClaude.PRESENT
        return if (cwd == null) ExternalClaude.UNKNOWN else ExternalClaude.ABSENT
    }

    /** True when [pid] appears in [p]'s ancestor chain (bounded walk — no cycles, but be paranoid). */
    private fun hasAncestor(p: ProcessHandle, pid: Long): Boolean {
        var cur: ProcessHandle? = p
        var hops = 0
        while (cur != null && hops++ < 64) {
            if (cur.pid() == pid) return true
            cur = cur.parent().orElse(null)
        }
        return false
    }

    private fun pathForms(p: String): Set<String> =
        setOf(p, runCatching { Path.of(p).toRealPath().toString() }.getOrDefault(p))

    /** Run [cmd], returning its output lines, or null on start failure or timeout. waitFor BEFORE reading
     *  gives a hard cap; safe because these listings are tiny (well under the pipe buffer), so lsof never
     *  blocks on write. lsof exits 1 for "no results" — only the timeout is a failure, the code isn't. */
    private fun lsofLines(cmd: List<String>): List<String>? = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        if (proc.waitFor(LSOF_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            proc.inputStream.bufferedReader().readLines()
        } else {
            proc.destroyForcibly()
            null
        }
    }.getOrNull()

    private const val LSOF_TIMEOUT_MS = 1_500L

    /** Shorter than the ~10s project refresh it serves, so a normal tick still sees a fresh-enough world
     *  while bursts (several refresh triggers landing together) collapse into one probe. */
    private const val CWD_TTL_MS = 5_000L
}
