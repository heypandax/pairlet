package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.util.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Owns one OS agent process (claude / codex): a [stdout] channel of raw lines, a single serialized
 * [writeLine], and a [shutdown] that reaps the whole process tree (no orphaned MCP grandchildren).
 * Provider-agnostic — it speaks only newline-delimited text; the [AgentBackend] owns the schema.
 */
class AgentProcess private constructor(
    private val process: Process,
    private val scope: CoroutineScope,
) {
    private val log = logger("AgentProcess")
    private val descendantsAtStart: List<ProcessHandle> = process.toHandle().descendants().toList()

    val pid: Long get() = process.pid()

    // Bounded tail of the non-blank stderr lines — a dying agent's parting words. A single-slot
    // "last line" loses the diagnosis whenever the runtime prints a MULTI-LINE death (a Node crash
    // dump ends with a bare "Node.js v24.16.0" footer that overwrites the `Error: …` above it), so
    // the whole tail is kept and compressed at read time. Bounded twice — per line and in line count
    // — because stderr is attacker-adjacent unbounded output that must never grow the heap.
    // Guarded by its own monitor: the writer is the stderr pump coroutine, readers run on other
    // threads after awaitExit / stderrDrained.
    private val stderrTailBuf = ArrayDeque<String>()

    /** The last non-blank stderr line — a dying agent's parting words ("No conversation found with
     *  session ID …", context-overflow errors). Carried into the process_exited error so the phone
     *  sees WHY a resume died before its first init, not just "agent process ended". */
    val lastStderr: String? get() = synchronized(stderrTailBuf) { stderrTailBuf.lastOrNull() }

    /** Snapshot of the retained stderr tail, oldest first. Callers scanning for a marker
     *  (session-lock refusal, "Session not found") must use this rather than [lastStderr]: a marker
     *  printed mid-dump is otherwise invisible once the runtime prints its footer after it. */
    fun stderrTail(): List<String> = synchronized(stderrTailBuf) { stderrTailBuf.toList() }

    /**
     * The stderr tail compressed to the part worth showing a human, or null when nothing was printed.
     *
     * Deterministic, never a guess: strip the runtime's trailing footer lines (a Node crash dump's
     * bare `Node.js v24.16.0`, which is what a last-line-only reader used to surface — issue #328),
     * then start from the first line carrying a strong error signal so the real cause leads. With no
     * signal line the retained head is returned as-is; if stripping consumed everything, the raw last
     * line stands (the compressed view must never be WORSE than the single line it replaced).
     *
     * E2E-only: this rides the sealed [dev.ccpocket.protocol.PocketError], never a cleartext push.
     */
    fun stderrDiagnostic(maxChars: Int = MAX_DIAGNOSTIC_CHARS): String? {
        val lines = stderrTail()
        if (lines.isEmpty()) return null
        val body = lines.dropLastWhile { RUNTIME_FOOTER.matches(it.trim()) }
        if (body.isEmpty()) return lines.last()
        val from = body.indexOfFirst { ERROR_SIGNAL.containsMatchIn(it) }.let { if (it < 0) 0 else it }
        return body.subList(from, body.size).joinToString("\n").take(maxChars)
    }

    /** True once the process produced at least one stdout line. The startup watchdog's liveness
     *  signal: "alive but sawStdout=false after the window" = hung on launch; a long healthy turn
     *  keeps streaming and must never be confused with a hang. */
    @Volatile
    var sawStdout: Boolean = false
        private set

    /** Exit code once the process has terminated, else null (also null if it can't be read). */
    fun exitCode(): Int? = runCatching { process.exitValue() }.getOrNull()

    /** True while the OS process is still running — the "is the event source still alive?" gate for
     *  heuristics that would otherwise guess at outcomes the live agent will eventually report itself. */
    fun isAlive(): Boolean = process.isAlive

    /** Raw stdout lines; closed when the process exits. Bounded -> backpressure to the agent. */
    val stdout: Channel<String> = Channel(capacity = 256)

    /** Every write (prompt / allow / deny / rpc) funnels through this one writer -> no interleaving. */
    private val stdin: Channel<String> = Channel(capacity = 64)
    private val shutdownLock = Mutex()

    // completed when the stderr pump has read its pipe to EOF — the OS exit alone does NOT imply
    // lastStderr is populated yet (the reader coroutine may not have been scheduled), and a startup
    // refusal's parting words are healSessionLock's only evidence
    private val stderrDrained = CompletableDeferred<Unit>()

    private fun launchPumps() {
        scope.launch(Dispatchers.IO + CoroutineName("agent-stdout-$pid")) {
            try {
                process.inputStream.bufferedReader().use { r ->
                    while (isActive) {
                        val line = r.readLine() ?: break
                        sawStdout = true
                        stdout.send(line)
                    }
                }
            } catch (_: Throwable) {
                // reader interrupted during shutdown
            } finally {
                stdout.close()
            }
        }
        scope.launch(Dispatchers.IO + CoroutineName("agent-stderr-$pid")) {
            // must drain stderr or the child can block on a full pipe
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    if (it.isNotBlank()) {
                        synchronized(stderrTailBuf) {
                            stderrTailBuf.addLast(it.take(MAX_STDERR_LINE_CHARS))
                            while (stderrTailBuf.size > MAX_STDERR_TAIL_LINES) stderrTailBuf.removeFirst()
                        }
                        // per-line local log stays the forensic record: the tail is bounded, this isn't
                        log.warn("agent stderr: ${it.take(200)}")
                    }
                }
            }
            stderrDrained.complete(Unit)
        }
        scope.launch(Dispatchers.IO + CoroutineName("agent-stdin-$pid")) {
            val w = process.outputStream.bufferedWriter()
            try {
                for (msg in stdin) {
                    w.write(msg); w.write("\n"); w.flush()
                }
            } catch (t: Throwable) {
                // broken pipe: the process died under us — say so instead of dying silently (issue #122)
                log.warn("agent $pid stdin writer ended: ${t.message}")
            } finally {
                runCatching { w.close() }
                // close the channel too, so later writeLine calls FAIL FAST instead of buffering into a
                // dead pipe (or suspending forever once the 64-slot buffer fills) — issue #122: a write
                // that can't reach the agent must be observable, the Conversation's ledger re-injects it
                stdin.close()
            }
        }
    }

    /** Queue one line for the agent's stdin. A write after the process/pipe died is DROPPED, loudly —
     *  never silently buffered or suspended forever; the caller's unconsumed-prompt ledger (issue #122)
     *  is the recovery path, re-injecting the payload into the next spawn. */
    suspend fun writeLine(json: String) {
        runCatching { stdin.send(json) }
            .onFailure { log.warn("agent $pid stdin write dropped (pipe closed): ${json.take(120)}") }
    }

    /** Bounded wait for the OS process to fully exit — its transcript is only flushed then. Also waits
     *  (bounded) for the stderr pump to drain: callers read [lastStderr] right after this (the
     *  session-lock heal, the process_exited "why"), and the exit races the reader coroutine. Post-exit
     *  the pipe is at EOF so the drain settles in microseconds; the timeout only guards a wedged pump. */
    suspend fun awaitExit(seconds: Long = 5) {
        withContext(Dispatchers.IO) { runCatching { process.waitFor(seconds, TimeUnit.SECONDS) } }
        withTimeoutOrNull(2_000) { stderrDrained.await() }
    }

    /**
     * Stop the process and reap its tree, but give the CLI a real window to flush its transcript
     * FIRST. The ladder is EOF -> SIGTERM -> SIGKILL, each rung entered only if the previous didn't
     * take:
     *
     *  1. Close stdin. In `-p --input-format stream-json` mode an EOF on stdin IS the CLI's normal
     *     shutdown signal (the official Agent SDK ends a session this way), so this alone lets the
     *     child flush `~/.claude/projects/<key>/<sid>.jsonl` and exit 0 on its own.
     *  2. Wait [eofGraceMs] for that clean exit. This window is the ONLY graceful stop on Windows,
     *     where `Process.destroy()` is `TerminateProcess` (== `destroyForcibly()`, uncatchable, no
     *     flush) — without the wait the grace collapses to ~0 ms and the transcript tail is lost
     *     (issue #101). In the common idle / between-turns case the child exits well under budget, so
     *     `waitFor` returns early and we never signal it at all.
     *  3. Still alive -> [Process.destroy]: SIGTERM on Unix (a second, catchable chance that preserves
     *     the pre-#101 2 s grace), already-forcible on Windows. Wait [termGraceMs].
     *  4. Still alive -> force-kill: SIGKILL on Unix, plus reaping the live descendant tree (queried
     *     WHILE the parent is alive — the only moment the links are walkable, so an already-exited
     *     child's now-reparented grandchildren can't be enumerated) so no MCP grandchild is orphaned;
     *     on Windows `taskkill /T /F` is the tree-reaper. Kept in THIS branch (it used to run
     *     unconditionally) so a child that already exited cleanly is never chased with `taskkill /F`
     *     on a possibly-recycled pid — issue #101 rec. #3; this also matches what Unix always did (its
     *     live-descendant reap only ever ran on the force branch).
     *
     * Total is bounded by eofGrace + termGrace + forceGrace even for a wedged child, so a stuck agent
     * can't wedge daemon shutdown; [dev.ccpocket.daemon.session.SessionRegistry.closeAll] fans the
     * per-session shutdowns out in parallel so N sessions cost ~one budget, not N.
     */
    suspend fun shutdown(
        eofGraceMs: Long = EOF_GRACE_MS,
        termGraceMs: Long = TERM_GRACE_MS,
        forceGraceMs: Long = FORCE_GRACE_MS,
    ) = shutdownLock.withLock {
        stdin.close()
        runCatching { process.outputStream.close() } // EOF — the CLI's stream-json shutdown signal
        var exited = withContext(Dispatchers.IO) { process.waitFor(eofGraceMs, TimeUnit.MILLISECONDS) }
        if (!exited) {
            process.destroy() // Unix: SIGTERM (catchable, flushes). Windows: TerminateProcess.
            exited = withContext(Dispatchers.IO) { process.waitFor(termGraceMs, TimeUnit.MILLISECONDS) }
        }
        if (!exited) {
            (descendantsAtStart + process.toHandle().descendants().toList())
                .forEach { runCatching { it.destroyForcibly() } }
            process.destroyForcibly() // Unix: SIGKILL
            withContext(Dispatchers.IO) { runCatching { process.waitFor(forceGraceMs, TimeUnit.MILLISECONDS) } }
            if (isWindows()) windowsTaskkill() // tree-reaper of last resort — only on the force path
        }
        stdout.close()
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
    private fun windowsTaskkill() = runCatching {
        ProcessBuilder("taskkill", "/T", "/F", "/PID", pid.toString()).inheritIO().start().waitFor()
    }

    companion object {
        // EOF (stdin close) is the stream-json CLI's graceful stop; give it time to flush the
        // transcript tail and exit before escalating. Idle sessions exit in well under this — it's
        // only fully spent when stopping mid-turn. 3 s covers a slow flush + MCP teardown while
        // keeping escalation prompt. On Windows this is the ONLY graceful window (destroy() ==
        // TerminateProcess), so it must not be ~0 (issue #101).
        private const val EOF_GRACE_MS = 3_000L
        // Unix SIGTERM grace — preserved from the pre-#101 code (was waitFor(2, SECONDS)).
        private const val TERM_GRACE_MS = 2_000L
        // settle time after SIGKILL / taskkill — preserved from the pre-#101 code.
        private const val FORCE_GRACE_MS = 2_000L

        // stderr tail bounds. 50 lines × 400 chars caps the retained tail at ~20 KB per process,
        // enough for a whole Node crash dump (message + a dozen stack frames + footer) while staying
        // far below anything that could pressure the heap when an agent spews output.
        private const val MAX_STDERR_TAIL_LINES = 50
        private const val MAX_STDERR_LINE_CHARS = 400
        // what a phone can actually read in an error card; the caller no longer truncates.
        private const val MAX_DIAGNOSTIC_CHARS = 700

        // A runtime's parting footer carries no diagnosis — Node prints a bare `Node.js v24.16.0` as
        // the LAST line of a fatal dump, which is exactly the line a last-line-only reader surfaced.
        private val RUNTIME_FOOTER = Regex("""^Node\.js v\d[\d.]*$""")

        // Word-boundary signals that a line states the failure rather than framing it (stack frames,
        // "at …" lines, blank separators). Kept literal and case-insensitive: no inference, a line
        // either says one of these or it doesn't.
        private val ERROR_SIGNAL = Regex(
            """\b(error|exception|unhandled|fatal|panic|throw|cannot|not found|ENOENT|EACCES|EADDRINUSE|ECONNREFUSED|ETIMEDOUT)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun start(pb: ProcessBuilder, scope: CoroutineScope): AgentProcess =
            AgentProcess(pb.start(), scope).also { it.launchPumps() }
    }
}
