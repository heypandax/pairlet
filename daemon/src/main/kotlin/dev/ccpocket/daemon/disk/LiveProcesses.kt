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
        if (System.getProperty("os.name").lowercase().contains("win")) return ExternalClaude.UNKNOWN
        val selfPid = ProcessHandle.current().pid()
        val external = runCatching {
            ProcessHandle.allProcesses()
                .filter { it.info().command().orElse("").contains("/claude") }
                .filter { !hasAncestor(it, selfPid) }
                .map { it.pid() }
                .toList()
        }.getOrNull() ?: return ExternalClaude.UNKNOWN
        if (external.isEmpty()) return ExternalClaude.ABSENT // no claude outside the daemon at all
        val cwdLines = lsofLines(listOf("lsof", "-a", "-d", "cwd", "-p", external.joinToString(","), "-Fn"))
            ?: return ExternalClaude.UNKNOWN
        // match raw AND canonical forms: lsof reports resolved real paths, the workdir may arrive symlinked
        val targets = pathForms(workdir)
        if (cwdLines.filter { it.startsWith("n") }.any { it.substring(1) in targets }) return ExternalClaude.PRESENT
        // strengthener only: a claude launched from elsewhere but holding this transcript's fd is also a live
        // writer. Its failure never demotes to UNKNOWN — the cwd check (the main judgement) already ran clean.
        val holders = lsofLines(listOf("lsof", "-t", "--", transcript.toString()))
            ?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()
        val externalPids = external.toSet()
        return if (holders.any { it in externalPids }) ExternalClaude.PRESENT else ExternalClaude.ABSENT
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
}
