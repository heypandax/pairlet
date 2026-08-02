package dev.ccpocket.daemon.shell

import dev.ccpocket.daemon.agent.ApprovalTimeout
import dev.ccpocket.daemon.agent.ToolMetadata
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.approval.ApprovalOutcome
import dev.ccpocket.daemon.approval.ApprovalSource
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ShellResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The phone's "quick terminal" (issue #3): runs a one-off shell command in a session's workdir and returns
 * a single [ShellResult]. A paired phone can already run commands through the agent's Bash tool, so this adds
 * no new trust boundary — but it still reuses the SAME approval firewall as that tool: a command auto-runs
 * only in bypass mode or when its rule was remembered; otherwise the phone must approve it (dangerous commands
 * are flagged, and time out to "denied"). The SHELL source adapter of [ApprovalCoordinator] (approval design
 * M1): the coordinator owns pending/timeout/verdict routing (the "sh-" askId prefix stays for log legibility);
 * remembered command scopes stay here.
 */
class ShellService(
    private val scope: CoroutineScope,
    private val coordinator: ApprovalCoordinator = ApprovalCoordinator(scope),
    private val verdictTimeoutMs: Long = ApprovalTimeout.ms, // issue #100: injectable so a test drives a short timeout
) {
    private val log = logger("Shell")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private val allowRules = ConcurrentHashMap<String, MutableSet<String>>() // convoId -> remembered command scopes
    private val inFlight = ConcurrentHashMap.newKeySet<String>() // convoIds with a command in progress (one at a time)

    /** Run [cmd] in its (already-validated) workdir, gated by the conversation's [mode]; emit one [ShellResult]. */
    suspend fun run(cmd: RunShellCommand, mode: PermissionMode?, emit: suspend (Frame) -> Unit) {
        // server-side backpressure: one command per conversation at a time (the phone enforces this too, but a
        // buggy/hostile client must not be able to spawn unbounded processes / pending approvals)
        if (!inFlight.add(cmd.convoId)) {
            emit(ShellResult(cmd.convoId, cmd.command, exitCode = -1, error = "a command is already running in this session"))
            return
        }
        try {
            val meta = ToolMetadata.of("Bash", buildJsonObject { put("command", cmd.command) })
            val approved = when {
                mode == PermissionMode.BYPASS_PERMISSIONS -> {
                    coordinator.recordAuto(ApprovalSource.SHELL, cmd.convoId, "Bash", meta.rule, "bypass-permissions")
                    true
                }
                allowRules[cmd.convoId]?.contains(meta.rule) == true -> {
                    coordinator.recordAuto(ApprovalSource.SHELL, cmd.convoId, "Bash", meta.rule, "remembered-rule")
                    true
                }
                else -> askApproval(cmd, meta.preview, meta.title, meta.rule, meta.danger, meta.dangerNote, mode, emit)
            }
            if (!approved) {
                emit(ShellResult(cmd.convoId, cmd.command, exitCode = -1, denied = true))
                return
            }
            emit(execute(cmd))
        } finally {
            inFlight.remove(cmd.convoId)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun askApproval(
        cmd: RunShellCommand, preview: String, title: String, rule: String, danger: Boolean, dangerNote: String?,
        mode: PermissionMode?, emit: suspend (Frame) -> Unit,
    ): Boolean {
        val askId = "sh-" + UUID.randomUUID()
        val gate = CompletableDeferred<Boolean>()
        val ask = PermissionAsk(cmd.convoId, askId, "Bash", preview, mode, title, rule, danger, dangerNote, null, timeoutSec = (verdictTimeoutMs / 1000).toInt())
        // the coordinator owns timeout + card retirement (issue #100: the ShellResult(denied) below dismisses
        // the terminal spinner but not the separate PermissionAsk card — AskWithdrawn(TIMED_OUT) retires it)
        coordinator.submit(ask, ApprovalSource.SHELL, owner = this, timeoutMs = verdictTimeoutMs, emit = emit) { outcome ->
            when (outcome) {
                is ApprovalOutcome.Answered -> {
                    val v = outcome.verdict
                    val allow = v.decision == Decision.ALLOW
                    if (allow && v.remember) allowRules.getOrPut(cmd.convoId) { ConcurrentHashMap.newKeySet() }.add(rule)
                    gate.complete(allow)
                }
                ApprovalOutcome.TimedOut, ApprovalOutcome.Withdrawn -> gate.complete(false) // no answer -> deny, never run
            }
        }
        return gate.await()
    }

    /** Owner-facing snapshot rows for quick-terminal approvals. */
    fun pendingApprovals(): List<PendingApproval> = coordinator.rowsFor(this)

    /** Drop remembered command scopes for a closed conversation. */
    fun forget(convoId: String) { allowRules.remove(convoId) }

    private suspend fun execute(cmd: RunShellCommand): ShellResult = withContext(Dispatchers.IO) {
        try {
            val argv =
                if (isWindows) listOf(System.getenv("ComSpec") ?: "cmd.exe", "/c", cmd.command)
                else listOf("/bin/sh", "-c", cmd.command)
            val proc = ProcessBuilder(argv)
                .directory(File(cmd.workdir))
                .redirectErrorStream(false)
                .start()
            proc.outputStream.close() // no stdin for a one-off command
            val out = async { drainCapped(proc.inputStream.bufferedReader()) }
            val err = async { drainCapped(proc.errorStream.bufferedReader()) }
            val finished = proc.waitFor(cmd.timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            if (!finished) proc.destroyForcibly()
            // bound the read: a grandchild that inherited the pipe can keep it open after the child is killed, so
            // never block forever waiting for EOF — emit what we have.
            val stdout = withTimeoutOrNull(READ_DRAIN_MS) { out.await() } ?: ""
            val stderr = withTimeoutOrNull(READ_DRAIN_MS) { err.await() } ?: ""
            if (!finished) ShellResult(cmd.convoId, cmd.command, exitCode = -1, stdout = stdout, stderr = stderr, timedOut = true)
            else ShellResult(cmd.convoId, cmd.command, exitCode = proc.exitValue(), stdout = stdout, stderr = stderr)
        } catch (e: Exception) {
            log.warn("shell command failed: ${cmd.command}", e)
            ShellResult(cmd.convoId, cmd.command, exitCode = -1, error = e.message ?: "failed to run command")
        }
    }

    /** Read a stream keeping at most [MAX_OUT] chars but draining the rest, so a chatty command neither blows the
     *  relay frame / daemon heap nor blocks on a full pipe. */
    private fun drainCapped(reader: java.io.Reader): String = reader.use { r ->
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var total = 0
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            if (total < MAX_OUT) sb.append(buf, 0, minOf(n, MAX_OUT - total))
            total += n
        }
        sb.toString()
    }

    private companion object {
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_OUT = 16_000 // cap each stream so a chatty command can't blow the relay frame
        const val READ_DRAIN_MS = 3_000L // max wait for output readers after the process ends/was killed
    }
}
