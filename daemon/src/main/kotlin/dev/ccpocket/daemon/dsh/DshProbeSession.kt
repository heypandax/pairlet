package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * ONE throwaway `dsh --profile acp` client: boot it, open one session in a scratch directory, read that
 * session's `configOptions`, then take the process, the scratch directory AND the session dsh persisted
 * for it back out of the world (issues #333, #387).
 *
 * It is a class rather than a function because all three of those are lifecycles that have to end even
 * when the read does not:
 *
 *  - **The process.** It is ours from the moment it starts. A timeout, a throw or a cancelled fetch all
 *    leave through [terminate], which closes stdin first (EOF is ACP's clean client-disconnect), then
 *    escalates, and always reports whether the child actually died.
 *  - **The scratch directory.** Created before the child, so a `ProcessBuilder.start` that throws — no
 *    executable, no exec bit, a fork failure — must still release it; that path is its own `catch`.
 *  - **The persisted session.** dsh materializes a header for an empty session during `session/new` and
 *    offers no way to opt out of it or delete it afterwards, so the probe has to sweep its own row out of
 *    `$DSH_HOME/sessions` ([DshProbeSessionCleanup] owns the proof that the row is in fact ours). It runs
 *    only after the child is CONFIRMED gone: a live dsh may still be holding a write-behind batch, and
 *    the whole point of the ownership check is that it reads a finished file.
 *
 * ORDER MATTERS on the way out: `session/close` (which flushes persistence before it resolves) → stdin
 * EOF → confirmed exit → file cleanup → scratch. Cleanup never gates the answer: a catalogue that was
 * read successfully is returned even when its litter could not be swept, because "dsh has no models" and
 * "cc-pocket left a row behind" are different problems with different fixes.
 *
 * `DSH_HOME` is pinned into the child's environment at the value this process resolved, so the store the
 * child writes to and the store cleanup reads cannot be two different directories.
 */
internal class DshProbeSession(
    /** Starts the ACP child in the given scratch directory. A seam: tests inject failures and fakes. */
    private val launch: (File) -> Process,
    private val newScratch: () -> File = { createTempDirectory(SCRATCH_PREFIX).toFile() },
    private val sessionsRoot: () -> Path = { DshPaths.sessionsRoot() },
    private val cleanup: (String, String, Path) -> DshProbeSessionCleanup.Outcome = DshProbeSessionCleanup::remove,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
    private val closeTimeoutMs: Long = CLOSE_TIMEOUT_MS,
    private val exitTimeoutMs: Long = EXIT_TIMEOUT_MS,
) {
    private val log = logger("DshProbeSession")

    /** What one probe achieved. [options] is the answer; the rest is hygiene the caller may assert on
     *  but must never trade the answer for. */
    data class Result(
        val options: DshConfigOptions?,
        val session: DshProbeSessionCleanup.Outcome,
        val processExited: Boolean,
        val scratchRemoved: Boolean,
    )

    fun run(): Result {
        val scratch = newScratch()
        val proc = try {
            launch(scratch)
        } catch (t: Throwable) {
            // Nothing was started, so nothing persisted — but the directory is still ours.
            val removed = removeScratch(scratch)
            if (!removed) log.warn("dsh model probe could not remove its scratch directory after a failed launch")
            throw t
        }
        // The canonical path is what goes on the wire: dsh stores the cwd it is GIVEN verbatim in the
        // session header, so handing it an unresolved one (macOS /var -> /private/var) would make our own
        // header check disagree with our own request.
        val cwd = runCatching { scratch.canonicalPath }.getOrDefault(scratch.absolutePath)
        var sessionId: String? = null
        var options: DshConfigOptions? = null
        var outcome: DshProbeSessionCleanup.Outcome = DshProbeSessionCleanup.Outcome.Absent
        var exited = false
        var scratchRemoved = false
        try {
            val answers = Answers()
            drainStderr(proc)
            readStdout(proc, answers)
            val writer = proc.outputStream.bufferedWriter()
            send(writer, handshake())
            // No need to wait for the initialize response: dsh answers requests in order, and a
            // session/new arriving behind an unfinished handshake is queued, not refused.
            send(writer, openSession(cwd))
            val answer = runCatching { answers.session.get(answerTimeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
            sessionId = answer?.str("sessionId")?.takeIf { it.isNotBlank() }
            options = answer?.arr("configOptions")?.let { DshConfigOptions.parse(it) }?.takeIf { !it.isEmpty }
            if (sessionId != null) {
                // session/close flushes this session's persistence before it resolves — waiting for the
                // ack is what makes the file on disk final before anything reads or deletes it.
                runCatching { send(writer, closeSession(sessionId)) }
                runCatching { answers.closed.get(closeTimeoutMs, TimeUnit.MILLISECONDS) }
            }
            runCatching { writer.close() }
        } finally {
            exited = terminate(proc)
            outcome = sweep(sessionId, cwd, exited)
            scratchRemoved = removeScratch(scratch)
            report(outcome, exited, scratchRemoved)
        }
        return Result(options, outcome, exited, scratchRemoved)
    }

    /** Stop the child, hardest last, and say whether it is really gone. */
    private fun terminate(proc: Process): Boolean {
        runCatching { proc.outputStream.close() } // ACP reads stdin EOF as a clean client disconnect
        if (awaitExit(proc)) return true
        runCatching { proc.destroy() }
        if (awaitExit(proc)) return true
        runCatching { proc.destroyForcibly() }
        return awaitExit(proc)
    }

    private fun awaitExit(proc: Process): Boolean = try {
        proc.waitFor(exitTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        // A cancelled fetch must still finish tearing down; keep the flag for whoever owns this thread.
        Thread.currentThread().interrupt()
        !proc.isAlive
    } catch (_: Throwable) {
        false
    }

    private fun sweep(sessionId: String?, cwd: String, exited: Boolean): DshProbeSessionCleanup.Outcome = when {
        sessionId == null ->
            DshProbeSessionCleanup.Outcome.Refused("no session/new answer to attribute a session to")
        // A dsh still running may hold an unflushed write-behind batch; deleting under it would be a
        // guess about a file somebody else still owns.
        !exited -> DshProbeSessionCleanup.Outcome.Refused("the probe process did not exit")
        else -> runCatching { cleanup(sessionId, cwd, sessionsRoot()) }
            .getOrElse { DshProbeSessionCleanup.Outcome.Failed("the cleanup pass did not complete") }
    }

    private fun removeScratch(scratch: File): Boolean =
        runCatching { scratch.deleteRecursively() }.getOrDefault(false)

    /** A fixed, enumerable vocabulary — never a transcript, a path or a credential. */
    private fun report(outcome: DshProbeSessionCleanup.Outcome, exited: Boolean, scratchRemoved: Boolean) {
        when (outcome) {
            is DshProbeSessionCleanup.Outcome.Removed,
            is DshProbeSessionCleanup.Outcome.Absent,
            -> log.debug("dsh model probe left no session behind")
            is DshProbeSessionCleanup.Outcome.Refused ->
                log.warn("dsh model probe left a session in dsh's list: ${outcome.reason}")
            is DshProbeSessionCleanup.Outcome.Failed ->
                log.warn("dsh model probe could not remove its session: ${outcome.reason}")
        }
        if (!exited) log.warn("dsh model probe could not confirm its process exited")
        if (!scratchRemoved) log.warn("dsh model probe could not remove its scratch directory")
    }

    // ── wire ────────────────────────────────────────────────────────────────────────────────────────

    private class Answers {
        /** The `session/new` result, or null when stdout ended / errored without one. */
        val session = CompletableFuture<JsonObject?>()
        val closed = CompletableFuture<Boolean>()
    }

    private fun send(writer: Writer, obj: JsonObject) {
        writer.write(obj.toString()); writer.write("\n"); writer.flush()
    }

    private fun handshake(): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", INITIALIZE_ID); put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", 1)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
            }
        }
    }

    private fun openSession(cwd: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", SESSION_NEW_ID); put("method", "session/new")
        putJsonObject("params") {
            put("cwd", cwd)
            putJsonArray("mcpServers") {}
        }
    }

    private fun closeSession(sessionId: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", SESSION_CLOSE_ID); put("method", "session/close")
        putJsonObject("params") { put("sessionId", sessionId) }
    }

    /** Drain stderr for the child's whole life: a full pipe buffer would wedge the process we are about
     *  to ask a question of. dsh logs there; none of it is ours to repeat. */
    private fun drainStderr(proc: Process) {
        Thread {
            runCatching { proc.errorStream.bufferedReader().forEachLine { } }
        }.apply { isDaemon = true; name = "dsh-models-stderr" }.start()
    }

    private fun readStdout(proc: Process, answers: Answers) {
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { reader: BufferedReader ->
                    reader.forEachLine { line ->
                        val root = runCatching { JSON.parseToJsonElement(line) }.getOrNull() as? JsonObject
                            ?: return@forEachLine
                        // Server→client REQUESTS (session/request_permission) carry an id from dsh's own
                        // counter, which can collide with ours — only a response is an answer to us.
                        if (root.str("method") != null) return@forEachLine
                        val id = root.str("id")
                        when {
                            id == SESSION_CLOSE_ID.toString() -> answers.closed.complete(true)
                            id == SESSION_NEW_ID.toString() -> answers.session.complete(root.obj("result"))
                            // Belt and braces for an id we did not expect: the session answer is the one
                            // carrying configOptions; initialize's has none.
                            root.obj("result")?.arr("configOptions") != null ->
                                answers.session.complete(root.obj("result"))
                        }
                    }
                }
            }
            // stdout closed with no answer — unblock the waiters rather than spend the whole timeout.
            answers.session.complete(null)
            answers.closed.complete(false)
        }.apply { isDaemon = true; name = "dsh-models-stdout" }.start()
    }

    companion object {
        /** Recognizable on sight: while a probe is in flight this name is what a user sees in dsh's own
         *  list, and a leftover after a crash says who made it. */
        const val SCRATCH_PREFIX = "cc-pocket-dsh-models"

        private const val INITIALIZE_ID = 1
        private const val SESSION_NEW_ID = 2
        private const val SESSION_CLOSE_ID = 3

        /** A cold Node start plus dsh's profile compose; generous because the alternative is a blank picker. */
        const val ANSWER_TIMEOUT_MS = 60_000L

        /** The flush of a session with nothing in it. Short: past this we kill and refuse to delete. */
        const val CLOSE_TIMEOUT_MS = 5_000L

        /** Per escalation step, not in total. */
        const val EXIT_TIMEOUT_MS = 5_000L

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * The child: `dsh --profile acp`, rooted in the scratch directory, with `DSH_HOME` pinned so the
         * store it writes to is the one [DshProbeSessionCleanup] will read.
         *
         * NO CREDENTIALS ARE PASSED, same as [DshLauncher.processBuilder] — dsh finds its own key.
         */
        fun processBuilder(exe: Path, scratch: File): ProcessBuilder =
            ProcessBuilder(exe.toString(), "--profile", DshLauncher.PROFILE)
                .directory(scratch)
                .redirectErrorStream(false) // dsh logs on stderr; stdout is exclusively ACP frames
                .also {
                    it.environment()["DSH_HOME"] = DshPaths.dshHome().toString()
                    it.environment().putIfAbsent("LANG", "C.UTF-8")
                }
    }
}
