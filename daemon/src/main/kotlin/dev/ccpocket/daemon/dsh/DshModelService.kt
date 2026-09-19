package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * ## The probe cleans up after itself (issue #387)
 *
 * A temp cwd hides the litter from the user's projects; it does not stop dsh from making it. dsh
 * materializes a header for an empty session DURING `session/new`, offers no ephemeral/no-persist
 * option, and registers no `session/delete` — so opening the model picker used to leave a permanent
 * `cc-pocket-dsh-models…` row in dsh's own web list every ten minutes. [DshProbeSession] now owns the
 * whole lifecycle and sweeps that one row, under [DshProbeSessionCleanup]'s ownership proof, after its
 * process is confirmed gone. A sweep that cannot prove ownership REFUSES and says so in the log; it
 * never costs the caller a catalogue that was read successfully.
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

        /**
         * Boot `dsh --profile acp`, handshake, open one scratch session, read its `configOptions`, and
         * take the process, the scratch directory and the session dsh persisted for it back out again.
         *
         * The whole lifecycle lives in [DshProbeSession] so it can be driven by a test with injected
         * process and filesystem failures; the only thing that belongs here is which executable to run.
         * Note what this returns: THE CATALOGUE, and nothing about the sweep. A probe whose litter could
         * not be cleaned still answers the picker — see [DshProbeSession.Result].
         */
        suspend fun bootTransient(dshBin: String?): DshConfigOptions? = withContext(Dispatchers.IO) {
            val exe = DshLauncher.resolveExecutable(dshBin)
            DshProbeSession(launch = { scratch -> DshProbeSession.processBuilder(exe, scratch).start() })
                .run().options
        }
    }
}
