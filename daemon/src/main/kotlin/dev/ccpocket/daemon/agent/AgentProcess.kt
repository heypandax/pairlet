package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.util.logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** The last non-blank stderr line — a dying agent's parting words ("No conversation found with
     *  session ID …", context-overflow errors). Carried into the process_exited error so the phone
     *  sees WHY a resume died before its first init, not just "agent process ended". */
    @Volatile
    var lastStderr: String? = null
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

    private fun launchPumps() {
        scope.launch(Dispatchers.IO + CoroutineName("agent-stdout-$pid")) {
            try {
                process.inputStream.bufferedReader().use { r ->
                    while (isActive) {
                        val line = r.readLine() ?: break
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
                        lastStderr = it
                        log.warn("agent stderr: ${it.take(200)}")
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO + CoroutineName("agent-stdin-$pid")) {
            val w = process.outputStream.bufferedWriter()
            try {
                for (msg in stdin) {
                    w.write(msg); w.write("\n"); w.flush()
                }
            } catch (_: Throwable) {
                // pipe closed on shutdown
            } finally {
                runCatching { w.close() }
            }
        }
    }

    suspend fun writeLine(json: String) = stdin.send(json)

    /** Bounded wait for the OS process to fully exit — its transcript is only flushed then. */
    suspend fun awaitExit(seconds: Long = 5) {
        withContext(Dispatchers.IO) { runCatching { process.waitFor(seconds, TimeUnit.SECONDS) } }
    }

    suspend fun shutdown() {
        stdin.close()
        runCatching { process.outputStream.close() }
        process.destroy()
        val exited = withContext(Dispatchers.IO) { process.waitFor(2, TimeUnit.SECONDS) }
        if (!exited) {
            (descendantsAtStart + process.toHandle().descendants().toList())
                .forEach { runCatching { it.destroyForcibly() } }
            process.destroyForcibly()
            withContext(Dispatchers.IO) { runCatching { process.waitFor(2, TimeUnit.SECONDS) } }
        }
        descendantsAtStart.forEach { if (it.isAlive) runCatching { it.destroyForcibly() } }
        if (isWindows()) windowsTaskkill()
        stdout.close()
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
    private fun windowsTaskkill() = runCatching {
        ProcessBuilder("taskkill", "/T", "/F", "/PID", pid.toString()).inheritIO().start().waitFor()
    }

    companion object {
        fun start(pb: ProcessBuilder, scope: CoroutineScope): AgentProcess =
            AgentProcess(pb.start(), scope).also { it.launchPumps() }
    }
}
