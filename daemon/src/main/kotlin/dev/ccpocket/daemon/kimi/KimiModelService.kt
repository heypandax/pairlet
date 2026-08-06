package dev.ccpocket.daemon.kimi

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Lists the Kimi model aliases the user has CONFIGURED, via `kimi provider list --json` (issue #206). That
 * command emits `{"providers":{…},"models":{<alias>:{…}}}` — the `models` keys are exactly the aliases a
 * session can pass to `--model` / ACP. Never blocks the router loop (runs on Dispatchers.IO); degrades to
 * the single `config.toml` default (or an explanatory error) when the CLI can't answer. P2 could add the
 * public catalog (`kimi provider catalog list`) as an import surface.
 */
class KimiModelService(
    private val kimiBin: String? = null,
    private val runner: (Path, Long) -> CommandResult = Companion::runProviderList,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(timeoutMs: Long = DEFAULT_TIMEOUT_MS): ModelsList = withContext(Dispatchers.IO) {
        runCatching {
            val exe = KimiLauncher.resolveExecutable(kimiBin)
            val result = runner(exe, timeoutMs)
            when {
                result.timedOut -> fallback("kimi provider list timed out after ${timeoutMs / 1000}s")
                result.exitCode != 0 -> fallback("kimi provider list exited ${result.exitCode}: ${result.stderr}")
                else -> {
                    val models = parseModels(result.stdout)
                    if (models.isNotEmpty()) ModelsList(agent = AgentKind.KIMI, models = models)
                    else fallback(null)
                }
            }
        }.getOrElse { e -> fallback("Failed to list models: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** No configured models → surface the config default alone (or an error note), so the picker isn't empty. */
    private fun fallback(error: String?): ModelsList {
        val def = KimiDefaultModel.resolve()
        return when {
            def != null -> ModelsList(agent = AgentKind.KIMI, models = listOf(def))
            error != null -> ModelsList(agent = AgentKind.KIMI, error = error)
            else -> ModelsList(agent = AgentKind.KIMI, models = emptyList())
        }
    }

    internal fun parseModels(stdout: String): List<String> = runCatching {
        val root = json.parseToJsonElement(stdout.trim()) as? JsonObject ?: return emptyList()
        (root["models"] as? JsonObject)?.keys?.sorted().orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L

        internal fun runProviderList(exe: Path, timeoutMs: Long): CommandResult {
            val proc = ProcessBuilder(exe.toString(), "provider", "list", "--json")
                .redirectErrorStream(false).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread { proc.inputStream.bufferedReader().use { out.append(it.readText()) } }
            val errThread = Thread { proc.errorStream.bufferedReader().use { err.append(it.readText()) } }
            outThread.start(); errThread.start()
            val exited = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) { proc.destroyForcibly(); proc.waitFor(1, TimeUnit.SECONDS) }
            outThread.join(500); errThread.join(500)
            return CommandResult(
                exitCode = if (exited) proc.exitValue() else -1,
                stdout = out.toString().trim(),
                stderr = err.toString().trim(),
                timedOut = !exited,
            )
        }
    }

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)
}
