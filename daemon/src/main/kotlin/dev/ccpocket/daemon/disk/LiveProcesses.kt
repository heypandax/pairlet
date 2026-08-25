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
    fun claudeCwds(): Set<String> {
        val pids = runCatching {
            ProcessHandle.allProcesses()
                .filter { it.info().command().orElse("").contains("/claude") } // .../bin/claude or .../share/claude/versions/<v>
                .map { it.pid().toString() }
                .toList()
        }.getOrDefault(emptyList())
        if (pids.isEmpty()) return emptySet()
        return runCatching {
            val proc = ProcessBuilder("lsof", "-a", "-d", "cwd", "-p", pids.joinToString(","), "-Fn")
                .redirectErrorStream(true).start()
            val cwds = proc.inputStream.bufferedReader().readLines()
                .filter { it.startsWith("n") }.map { it.substring(1) }.toSet()
            proc.waitFor(3, TimeUnit.SECONDS)
            cwds
        }.getOrDefault(emptySet())
    }

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
     * Memoized for [CODEX_CWD_TTL_MS] because the project list refreshes every ~10 seconds and this is a
     * full OS process enumeration plus an lsof fork (PR #296 review). This is a DISPLAY signal — a project
     * row lighting up a few seconds late is invisible; see the never-cache rule on [externalAgentAt] for
     * the probes where the same staleness would be a correctness bug.
     */
    fun codexCwds(): Set<String> {
        if (System.getProperty("os.name").lowercase().contains("win")) return emptySet() // no lsof
        return codexCwds(System.currentTimeMillis(), ::codexPids, ::codexCwdsOf)
    }

    /** [codexCwds]'s memo, with its clock and both probes injected so the TTL is testable. */
    internal fun codexCwds(nowMs: Long, pids: () -> Set<Long>, cwdsOf: (Set<Long>) -> Set<String>): Set<String> {
        val cached = codexCwdMemo.get()
        if (cached != null && nowMs - cached.atMs < CODEX_CWD_TTL_MS) return cached.cwds
        val live = pids()
        if (live.isEmpty()) {
            codexCwdMemo.set(CodexCwdMemo(nowMs, emptySet(), emptySet()))
            return emptySet()
        }
        // Same processes as last time ⇒ same cwds: a running CLI does not chdir. Re-enumerating pids is
        // cheap and in-process; the lsof fork is what this skips.
        if (cached != null && cached.pids == live) {
            codexCwdMemo.set(CodexCwdMemo(nowMs, live, cached.cwds))
            return cached.cwds
        }
        val cwds = cwdsOf(live)
        codexCwdMemo.set(CodexCwdMemo(nowMs, live, cwds))
        return cwds
    }

    private class CodexCwdMemo(val atMs: Long, val pids: Set<Long>, val cwds: Set<String>)

    private val codexCwdMemo = java.util.concurrent.atomic.AtomicReference<CodexCwdMemo?>(null)

    /** External (non-daemon-descendant) `codex` pids, in enumeration order; empty when the walk fails. */
    private fun codexPids(): Set<Long> {
        val selfPid = ProcessHandle.current().pid()
        return runCatching {
            ProcessHandle.allProcesses()
                .filter { isCodexExecutable(it.info().command().orElse("")) }
                .filter { !hasAncestor(it, selfPid) }
                .map { it.pid() }
                .toList()
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun codexCwdsOf(pids: Set<Long>): Set<String> =
        lsofLines(listOf("lsof", "-a", "-d", "cwd", "-p", pids.joinToString(","), "-Fn"))
            ?.filter { it.startsWith("n") }
            ?.map { it.substring(1) }
            ?.toSet()
            .orEmpty()

    /** Cross-test isolation: the memo is process-wide state on an object singleton. */
    internal fun clearForTest() = codexCwdMemo.set(null)

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
        return externalAgentAt(workdir, transcript, matchesCommand = { it.contains("/claude") })
    }

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
            System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(transcript).toMillis() <
                TranscriptScanner.LIVE_WINDOW_MS
        }.getOrDefault(false)
        return externalAgentAt(workdir, transcript, ::isCodexExecutable, allowCwdMatch = recent)
    }

    /**
     * IRON RULE — this probe and its two callers ([externalClaudeAt] / [externalCodexAt]) must NEVER be
     * cached or given a TTL, however hot they get. Their verdict is what decides whether we may take over
     * or write into a transcript another process owns; answering it from a world that is even a few seconds
     * old is exactly how you manufacture a silent two-writer clobber. The nearby [codexCwds] memo is safe
     * only because it feeds a project-list badge, not a write gate. If this needs to be faster, make the
     * probe itself cheaper — do not remember its answer.
     */
    private fun externalAgentAt(
        workdir: String,
        transcript: Path,
        matchesCommand: (String) -> Boolean,
        allowCwdMatch: Boolean = true,
    ): ExternalClaude {
        // Process ENUMERATION is cross-platform; only the fd/cwd probes below need lsof. Answering ABSENT
        // when no matching process exists at all keeps Windows verdicts sharp instead of a blanket UNKNOWN —
        // which matters since the Codex caller treats UNKNOWN as "assume the rollout is still held".
        val noLsof = System.getProperty("os.name").lowercase().contains("win")
        val selfPid = ProcessHandle.current().pid()
        val external = runCatching {
            ProcessHandle.allProcesses()
                .filter { matchesCommand(it.info().command().orElse("")) }
                .filter { !hasAncestor(it, selfPid) }
                .map { it.pid() }
                .toList()
        }.getOrNull() ?: return ExternalClaude.UNKNOWN
        if (external.isEmpty()) return ExternalClaude.ABSENT // no matching agent outside the daemon at all
        if (noLsof) return ExternalClaude.UNKNOWN // processes exist but ownership can't be probed
        // Exact transcript ownership outranks age and cwd: an idle agent can hold a rollout for hours
        // without touching its mtime, and that writer must never be resumed by a second process.
        val holders = lsofLines(listOf("lsof", "-t", "--", transcript.toString()))
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()
        val externalPids = external.toSet()
        if (holders?.any { it in externalPids } == true) return ExternalClaude.PRESENT
        if (!allowCwdMatch) return if (holders == null) ExternalClaude.UNKNOWN else ExternalClaude.ABSENT

        val cwdLines = lsofLines(listOf("lsof", "-a", "-d", "cwd", "-p", external.joinToString(","), "-Fn"))
            ?: return ExternalClaude.UNKNOWN
        // match raw AND canonical forms: lsof reports resolved real paths, the workdir may arrive symlinked
        val targets = pathForms(workdir)
        if (cwdLines.filter { it.startsWith("n") }.any { it.substring(1) in targets }) return ExternalClaude.PRESENT
        return ExternalClaude.ABSENT
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
    private const val CODEX_CWD_TTL_MS = 5_000L
}
