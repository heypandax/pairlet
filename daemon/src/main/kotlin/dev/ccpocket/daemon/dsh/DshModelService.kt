package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The DeepSeek Harness model + reasoning-effort catalogue (issue #333, re-sourced by the dsh 0.1.2 ACP switch), read from dsh's OWN ACP
 * answer rather than guessed from a config file.
 *
 * On the ACP surface the catalogue is a property of a SESSION: `session/new` and `session/resume` return
 * `configOptions` describing the selectable models and effort levels, and there is no host-level
 * "what do you have" method to ask instead (the `llm.models` RPC this class used to call lived on the web
 * profile's local API, which dsh 0.1.2-rc.1 removed — see [DshBackend]). So:
 *
 *  1. **A live session's read-back.** If the daemon is already driving a dsh conversation, its own
 *     `configOptions` are free, current, and describe the very session the user is about to change
 *     ([DshCatalog]).
 *  2. **A throwaway ACP client.** Otherwise boot `dsh --profile acp`, handshake, open ONE session in a
 *     scratch directory, read its options and kill the process. This costs a Node start-up, which is why
 *     the answer is cached for [CACHE_TTL_MS].
 *
 * The scratch directory is deliberate: `session/new` needs a cwd and dsh persists every session it
 * creates, so pointing this at a real project would litter that project's session list with empty rows.
 * A temp dir keys the throwaway session under a project nobody browses (see [DshPaths.projectKey]).
 *
 * ## What "no answer" means
 *
 * Every failure path returns `ModelsList(agent = DSH, error = …)` — an empty list with no reason is how a
 * picker ends up silently blank. Only SUCCESS is cached: a dsh that was mid-upgrade for one fetch must
 * not be remembered as broken for ten minutes.
 *
 * ## Model ids on the wire
 *
 * The wire id is the bare dsh model name (`deepseek-v4-pro`), never `provider/model` and never dsh's
 * opaque `["provider","model"]` selection value — [DshConfigOptions] owns that join, on both sides, so
 * a daemon-internal encoding can never leak into the model chip or the session header.
 *
 * ## Agent presets are gone
 *
 * The ACP surface exposes no agent-preset axis (dsh: "modes, commands, plans, terminals and elicitation
 * remain outside this automation surface"), so no `agentPresets` are advertised any more. An empty list
 * means the pickers show no preset row — which is honest, where offering one we cannot select was not.
 */
class DshModelService(
    private val dshBin: String? = null,
    /** A session we are already driving; null = none open, boot a throwaway client. */
    private val liveOptions: () -> DshConfigOptions? = { DshCatalog.current() },
    /** Seam for tests: reads a catalogue from a throwaway dsh, or null when one cannot be started. */
    private val transientRead: suspend () -> DshConfigOptions? = { bootTransient(dshBin) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val log = logger("DshModelService")

    private data class Cached(val at: Long, val list: ModelsList)

    @Volatile private var cache: Cached? = null

    /**
     * Serializes [fetch]. Without it two pickers opening at once (the phone's and the desktop's, or one
     * user tapping twice) each miss the empty cache and each BOOT A dsh — two Node processes against the
     * same `$DSH_HOME`, both then thrown away. The fast path below still answers a warm cache with no
     * locking at all; only an actual catalogue read queues, and the loser re-reads the cache the winner
     * just filled rather than repeating the work.
     */
    private val gate = Mutex()

    suspend fun fetch(): ModelsList {
        cache?.takeIf(::fresh)?.let { return it.list }
        return gate.withLock {
            cache?.takeIf(::fresh)?.let { return@withLock it.list }
            readCatalogue()
        }
    }

    private fun fresh(c: Cached): Boolean = nowMs() - c.at < CACHE_TTL_MS

    private suspend fun readCatalogue(): ModelsList {
        // A live session's own answer wins: it is the user's real dsh, and booting a second one to
        // contradict it would report a catalogue the open session is not actually using.
        runCatching { liveOptions() }.getOrNull()?.let { return remember(toList(it)) }
        val options = runCatching { transientRead() }.getOrElse {
            return failure("could not start DeepSeek Harness to read its models: ${it.message}")
        } ?: return failure(
            "could not start DeepSeek Harness to read its models — cc-pocket needs dsh " +
                "${DshLauncher.MIN_VERSION} or newer (npm i -g @deepseek-ai/dsh@latest).",
        )
        return remember(toList(options))
    }

    private fun remember(list: ModelsList): ModelsList {
        if (list.error == null) cache = Cached(nowMs(), list)
        return list
    }

    private fun failure(why: String): ModelsList {
        log.warn("dsh catalogue unavailable: $why")
        return ModelsList(agent = AgentKind.DSH, error = why)
    }

    /** One session's advertised options → the picker's rows. */
    private fun toList(options: DshConfigOptions): ModelsList {
        if (options.models.isEmpty()) {
            return failure("DeepSeek Harness listed no models — check its provider configuration.")
        }
        val efforts = options.efforts.map { it.id }
        return ModelsList(
            agent = AgentKind.DSH,
            models = options.models.map { it.id },
            // dsh advertises ONE effort ladder per session, not per model — but the current model is the
            // only one it was advertised for, so it rides as that model's capability AND as the
            // backend-wide list, which is what a picker with no selection yet can use.
            supportedEfforts = efforts,
            modelCapabilities = options.currentModel
                ?.takeIf { efforts.isNotEmpty() }
                ?.let {
                    listOf(
                        ModelCapabilities(
                            model = it,
                            reasoningEfforts = efforts,
                            defaultReasoningEffort = options.currentEffort,
                        ),
                    )
                }
                .orEmpty(),
        )
    }

    private companion object {
        /** Long enough that opening the picker twice does not boot dsh twice; short enough that a model
         *  a user just configured shows up without restarting the daemon. */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** A cold Node start plus dsh's profile compose; generous because the alternative is a blank picker. */
        const val BOOT_TIMEOUT_MS = 60_000L

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Boot `dsh --profile acp`, handshake, open one scratch session, read its `configOptions`, kill it.
         *
         * The child is OURS to kill from the moment it starts: every exit — a timeout, a throw, a
         * cancelled fetch — must take the process with it, or a Node host outlives the request holding
         * `$DSH_HOME` open with nobody left who knows how to close it.
         */
        suspend fun bootTransient(dshBin: String?): DshConfigOptions? = withContext(Dispatchers.IO) {
            val exe = DshLauncher.resolveExecutable(dshBin)
            val scratch = kotlin.io.path.createTempDirectory("cc-pocket-dsh-models").toFile()
            val proc = ProcessBuilder(exe.toString(), "--profile", DshLauncher.PROFILE)
                .directory(scratch)
                .redirectErrorStream(false) // dsh logs on stderr; stdout is exclusively ACP frames
                .also { it.environment().putIfAbsent("LANG", "C.UTF-8") }
                .start()
            val answers = CompletableFuture<JsonObject>()
            Thread {
                // Drain stderr for the child's whole life: a full pipe buffer would wedge the process we
                // are about to ask a question of.
                runCatching { proc.errorStream.bufferedReader().forEachLine { } }
            }.apply { isDaemon = true; name = "dsh-models-stderr" }.start()
            Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { reader: BufferedReader ->
                        reader.forEachLine { line ->
                            val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
                            val result = root?.obj("result") ?: return@forEachLine
                            // The session answer is the one carrying configOptions; initialize's has none.
                            if (result.arr("configOptions") != null && !answers.isDone) answers.complete(result)
                        }
                    }
                }
                answers.complete(JsonObject(emptyMap())) // stdout closed with no answer — unblock the waiter
            }.apply { isDaemon = true; name = "dsh-models-stdout" }.start()
            try {
                val writer = proc.outputStream.bufferedWriter()
                fun send(obj: JsonObject) {
                    writer.write(obj.toString()); writer.write("\n"); writer.flush()
                }
                send(
                    buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize")
                        putJsonObject("params") {
                            put("protocolVersion", 1)
                            putJsonObject("clientCapabilities") {
                                putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
                            }
                        }
                    },
                )
                // No need to wait for the initialize response: dsh answers requests in order, and a
                // session/new arriving behind an unfinished handshake is queued, not refused.
                send(
                    buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", 2); put("method", "session/new")
                        putJsonObject("params") {
                            put("cwd", scratch.absolutePath)
                            putJsonArray("mcpServers") {}
                        }
                    },
                )
                val result = runCatching { answers.get(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
                val options = result?.arr("configOptions")?.let { DshConfigOptions.parse(it) }
                options?.takeIf { !it.isEmpty }
            } finally {
                runCatching { proc.destroyForcibly() }
                runCatching { scratch.deleteRecursively() }
            }
        }
    }
}
