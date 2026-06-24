package dev.ccpocket.daemon.shell

import dev.ccpocket.daemon.agent.ToolMetadata
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ShellResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * are flagged, and time out to "denied"). Approval routing is by [PermissionAsk.askId] (the "sh-" prefix keeps
 * it distinct from agent asks, but [onVerdict] matches purely by pending-map membership).
 */
class ShellService(private val scope: CoroutineScope) {
    private val log = logger("Shell")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private class Pending(val gate: CompletableDeferred<Boolean>, val convoId: String, val rule: String)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val allowRules = ConcurrentHashMap<String, MutableSet<String>>() // convoId -> remembered command scopes

    /** Run [cmd] in its (already-validated) workdir, gated by the conversation's [mode]; emit one [ShellResult]. */
    suspend fun run(cmd: RunShellCommand, mode: PermissionMode?, emit: suspend (Frame) -> Unit) {
        val meta = ToolMetadata.of("Bash", buildJsonObject { put("command", cmd.command) })
        val approved = when {
            mode == PermissionMode.BYPASS_PERMISSIONS -> true
            allowRules[cmd.convoId]?.contains(meta.rule) == true -> true
            else -> askApproval(cmd, meta.preview, meta.title, meta.rule, meta.danger, meta.dangerNote, mode, emit)
        }
        if (!approved) {
            emit(ShellResult(cmd.convoId, cmd.command, exitCode = -1, denied = true))
            return
        }
        emit(execute(cmd))
    }

    @Suppress("LongParameterList")
    private suspend fun askApproval(
        cmd: RunShellCommand, preview: String, title: String, rule: String, danger: Boolean, dangerNote: String?,
        mode: PermissionMode?, emit: suspend (Frame) -> Unit,
    ): Boolean {
        val askId = "sh-" + UUID.randomUUID()
        val gate = CompletableDeferred<Boolean>()
        pending[askId] = Pending(gate, cmd.convoId, rule)
        emit(PermissionAsk(cmd.convoId, askId, "Bash", preview, mode, title, rule, danger, dangerNote, null))
        val timeout = scope.launch {
            delay(VERDICT_TIMEOUT_MS)
            if (pending.remove(askId) != null) gate.complete(false) // no answer -> deny, never run
        }
        return try { gate.await() } finally { timeout.cancel() }
    }

    /** Route a [PermissionVerdict]; returns true iff it resolved a pending SHELL ask (so the caller stops here). */
    fun onVerdict(v: PermissionVerdict): Boolean {
        val p = pending.remove(v.askId) ?: return false
        val allow = v.decision == Decision.ALLOW
        if (allow && v.remember) allowRules.getOrPut(p.convoId) { ConcurrentHashMap.newKeySet() }.add(p.rule)
        p.gate.complete(allow)
        return true
    }

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
            val out = async { proc.inputStream.bufferedReader().readText() }
            val err = async { proc.errorStream.bufferedReader().readText() }
            val finished = proc.waitFor(cmd.timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                ShellResult(cmd.convoId, cmd.command, exitCode = -1, stdout = out.await().take(MAX_OUT), stderr = err.await().take(MAX_OUT), timedOut = true)
            } else {
                ShellResult(cmd.convoId, cmd.command, exitCode = proc.exitValue(), stdout = out.await().take(MAX_OUT), stderr = err.await().take(MAX_OUT))
            }
        } catch (e: Exception) {
            log.warn("shell command failed: ${cmd.command}", e)
            ShellResult(cmd.convoId, cmd.command, exitCode = -1, error = e.message ?: "failed to run command")
        }
    }

    private companion object {
        const val VERDICT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_OUT = 16_000 // cap each stream so a chatty command can't blow the relay frame
    }
}
