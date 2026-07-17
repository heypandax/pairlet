package dev.ccpocket.daemon.opencode

import dev.ccpocket.protocol.ModelsList
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
                result.timedOut -> ModelsList(error = "opencode models timed out after ${timeoutMs / 1000}s")
                result.exitCode != 0 -> ModelsList(error = "opencode models exited ${result.exitCode}: ${result.stderr}")
                else -> ModelsList(models = sortModels(result.stdout.lines().filter { it.isNotBlank() }))
            }
        }.getOrElse { e ->
            ModelsList(error = "Failed to list models: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val FALLBACK_MODEL = "opencode/deepseek-v4-flash-free"

        fun defaultModel(opencodeBin: String? = null, timeoutMs: Long = 3_000L): String =
            runCatching {
                val exe = OpenCodeLauncher.resolveExecutable(opencodeBin)
                val result = runModels(exe, timeoutMs)
                if (result.exitCode == 0 && !result.timedOut) {
                    sortModels(result.stdout.lines().filter { it.isNotBlank() }).firstOrNull()
                } else null
            }.getOrNull() ?: FALLBACK_MODEL

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
