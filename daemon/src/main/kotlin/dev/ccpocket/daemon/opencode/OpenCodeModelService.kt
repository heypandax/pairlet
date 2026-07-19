package dev.ccpocket.daemon.opencode

import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Lists OpenCode models without blocking the relay/router inbound loop. */
class OpenCodeModelService(
    private val opencodeBin: String? = null,
    private val runner: (Path, Long) -> CommandResult = Companion::runModels,
) {
    suspend fun fetch(timeoutMs: Long = DEFAULT_TIMEOUT_MS): ModelsList = withContext(Dispatchers.IO) {
        runCatching {
            val exe = OpenCodeLauncher.resolveExecutable(opencodeBin)
            val result = runner(exe, timeoutMs)
            when {
                result.timedOut -> ModelsList(agent = AgentKind.OPENCODE, error = "opencode models timed out after ${timeoutMs / 1000}s")
                result.exitCode != 0 -> ModelsList(agent = AgentKind.OPENCODE, error = "opencode models exited ${result.exitCode}: ${result.stderr}")
                else -> ModelsList(agent = AgentKind.OPENCODE, models = sortModels(result.stdout.lines().filter { it.isNotBlank() }))
            }
        }.getOrElse { e ->
            ModelsList(agent = AgentKind.OPENCODE, error = "Failed to list models: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L

        /** Best-effort probe of `opencode models` for a default. NULL when it can't answer — the
         *  launcher then passes NO --model and opencode uses its OWN configured default. Never a
         *  hardcoded fallback: any id pinned here names a provider the user may not have configured
         *  (free-tier catalogs also rotate), and a wrong --model hangs the run silently. */
        fun defaultModel(opencodeBin: String? = null, timeoutMs: Long = 3_000L): String? =
            runCatching {
                val exe = OpenCodeLauncher.resolveExecutable(opencodeBin)
                val result = runModels(exe, timeoutMs)
                if (result.exitCode == 0 && !result.timedOut) {
                    sortModels(result.stdout.lines().filter { it.isNotBlank() }).firstOrNull()
                } else null
            }.getOrNull()

        internal fun sortModels(models: List<String>): List<String> =
            models.distinct().sortedWith(compareBy<String> { if (it.startsWith("opencode/")) 0 else 1 }.thenBy { it })

        internal fun runModels(exe: Path, timeoutMs: Long): CommandResult {
            val proc = ProcessBuilder(exe.toString(), "models").start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread { proc.inputStream.bufferedReader().use { out.append(it.readText()) } }
            val errThread = Thread { proc.errorStream.bufferedReader().use { err.append(it.readText()) } }
            outThread.start()
            errThread.start()
            val exited = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                proc.destroyForcibly()
                proc.waitFor(1, TimeUnit.SECONDS)
            }
            outThread.join(500)
            errThread.join(500)
            return CommandResult(
                exitCode = if (exited) proc.exitValue() else -1,
                stdout = out.toString().trim(),
                stderr = err.toString().trim(),
                timedOut = !exited,
            )
        }

    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )
}
