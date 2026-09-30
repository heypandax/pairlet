package dev.ccpocket.daemon.memo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * One child process for the memo pipeline. [argv] is exec'd directly — never through a shell — and user
 * text only ever travels over [stdin] (argv is visible to `ps`). [deadlineMs] is the budget LEFT for this
 * process, not a per-step allowance: callers pass their job deadline's remainder.
 */
class MemoProcessSpec(
    val argv: List<String>,
    val cwd: Path? = null,
    /** Edits the child's environment (e.g. [dev.ccpocket.daemon.claude.ClaudeRuntime.applyTo]); never the daemon's. */
    val env: (MutableMap<String, String>) -> Unit = {},
    val stdin: ByteArray? = null,
    val stdoutLimit: Int,
    val stderrLimit: Int,
    val deadlineMs: Long,
    /** Polled while the child runs; true = stop it now (e.g. a converter whose output outgrew any legal
     *  recording) and report [MemoProcessResult.Aborted]. */
    val abortWhen: (() -> Boolean)? = null,
)

sealed interface MemoProcessResult {
    /** The child exited on its own. Output past a limit was drained and dropped; [stdoutTruncated] also
     *  covers output that may be incomplete because a leftover descendant still held the pipe. */
    class Exited(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
    ) : MemoProcessResult

    data object TimedOut : MemoProcessResult
    data object StartFailed : MemoProcessResult
    data object Aborted : MemoProcessResult
}

/** Injection seam so the transcriber / summarizer can be tested without real binaries. */
fun interface MemoProcessExecutor {
    suspend fun run(spec: MemoProcessSpec): MemoProcessResult
}

/**
 * Cancellable, bounded child-process execution for the memo jobs (code design §6.3).
 *
 * - stdout and stderr are drained on their own threads at the same time, each kept up to its limit and
 *   discarded past it, so a chatty child can never block on a full pipe.
 * - Timeout and coroutine cancellation both end in the same teardown: SIGTERM the child and every
 *   descendant seen while it ran ([ProcessHandle.descendants]), a short grace, then SIGKILL, and a bounded
 *   wait for the exit — all under [NonCancellable] so a cancelled job still leaves nothing behind.
 * - Only processes this runner started (and their descendants) are touched; nothing is killed by name.
 * - [CancellationException] propagates untouched: a cancelled job must not turn into "the tool failed".
 */
class MemoProcessRunner(
    private val pollMs: Long = 100,
    private val termGraceMs: Long = 500,
    private val killConfirmMs: Long = 2_000,
    /** After the child exits, how long its output pipes may stay open (held by a descendant we never saw). */
    private val postExitDrainMs: Long = 2_000,
) : MemoProcessExecutor {

    override suspend fun run(spec: MemoProcessSpec): MemoProcessResult = withContext(Dispatchers.IO) {
        if (spec.argv.isEmpty()) return@withContext MemoProcessResult.StartFailed
        if (spec.deadlineMs <= 0) return@withContext MemoProcessResult.TimedOut
        val pb = ProcessBuilder(spec.argv).redirectErrorStream(false)
        spec.cwd?.let { pb.directory(it.toFile()) }
        try {
            spec.env(pb.environment())
        } catch (e: Exception) {
            return@withContext MemoProcessResult.StartFailed
        }
        // a job cancelled before launch must not start a process at all
        ensureActive()
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext MemoProcessResult.StartFailed
        }
        val seen: MutableSet<ProcessHandle> = ConcurrentHashMap.newKeySet()
        val out = drain(proc.inputStream, spec.stdoutLimit, "memo-proc-out")
        val err = drain(proc.errorStream, spec.stderrLimit, "memo-proc-err")
        feed(proc, spec.stdin)
        try {
            var aborted = false
            val result = withTimeoutOrNull(spec.deadlineMs) {
                val code = awaitExit(proc, seen, spec.abortWhen) ?: run { aborted = true; return@withTimeoutOrNull null }
                // anything still alive from this tree would hold the pipes open (and outlive the job)
                terminate(seen.filter { it.isAlive })
                val o = withTimeoutOrNull(postExitDrainMs) { out.done.await() }
                val e = withTimeoutOrNull(postExitDrainMs) { err.done.await() }
                MemoProcessResult.Exited(
                    exitCode = code,
                    stdout = o?.bytes ?: out.snapshot(),
                    stderr = e?.bytes ?: err.snapshot(),
                    stdoutTruncated = o?.truncated ?: true,
                    stderrTruncated = e?.truncated ?: true,
                )
            }
            when {
                aborted -> MemoProcessResult.Aborted
                result == null -> MemoProcessResult.TimedOut
                else -> result
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { seen.addAll(proc.toHandle().descendants().toList()) }
                terminate(listOf(proc.toHandle()) + seen)
            }
        }
    }

    /** Exit code, or null when [abortWhen] fired. Records descendants on every poll so an orphaned
     *  grandchild can still be reaped after its parent is gone. */
    private suspend fun awaitExit(proc: Process, seen: MutableSet<ProcessHandle>, abortWhen: (() -> Boolean)?): Int? {
        while (true) {
            runCatching { seen.addAll(proc.toHandle().descendants().toList()) }
            val exited = runInterruptible { proc.waitFor(pollMs, TimeUnit.MILLISECONDS) }
            if (exited) return proc.exitValue()
            if (abortWhen != null && runCatching { abortWhen() }.getOrDefault(false)) return null
        }
    }

    /** SIGTERM → grace → SIGKILL → bounded confirmation. Safe to call on already-dead handles. */
    private suspend fun terminate(handles: Collection<ProcessHandle>) {
        val alive = handles.filter { it.isAlive }
        if (alive.isEmpty()) return
        alive.forEach { runCatching { it.destroy() } }
        if (waitDead(alive, termGraceMs)) return
        alive.forEach { runCatching { it.destroyForcibly() } }
        waitDead(alive, killConfirmMs)
    }

    private suspend fun waitDead(handles: Collection<ProcessHandle>, budgetMs: Long): Boolean {
        val end = System.nanoTime() + budgetMs * 1_000_000
        while (handles.any { it.isAlive }) {
            if (System.nanoTime() >= end) return false
            delay(20)
        }
        return true
    }

    /** Writes [bytes] then closes stdin on its own thread — a child that never reads can't block the job. */
    private fun feed(proc: Process, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            runCatching { proc.outputStream.close() }
            return
        }
        Thread({
            runCatching { proc.outputStream.use { it.write(bytes) } }
        }, "memo-proc-in").apply { isDaemon = true }.start()
    }

    private class Captured(val bytes: ByteArray, val truncated: Boolean)

    private class Drain(private val sink: ByteArrayOutputStream, val done: CompletableDeferred<Captured>) {
        fun snapshot(): ByteArray = synchronized(sink) { sink.toByteArray() }
    }

    private fun drain(stream: InputStream, limit: Int, name: String): Drain {
        val sink = ByteArrayOutputStream()
        val done = CompletableDeferred<Captured>()
        Thread({
            val buf = ByteArray(8 * 1024)
            var truncated = false
            try {
                stream.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        synchronized(sink) {
                            val keep = minOf(n, limit - sink.size()).coerceAtLeast(0)
                            if (keep > 0) sink.write(buf, 0, keep)
                            if (keep < n) truncated = true
                        }
                    }
                }
            } catch (_: Exception) {
                // pipe torn down by the teardown — what was read so far stands
            }
            done.complete(Captured(synchronized(sink) { sink.toByteArray() }, truncated))
        }, name).apply { isDaemon = true }.start()
        return Drain(sink, done)
    }
}
