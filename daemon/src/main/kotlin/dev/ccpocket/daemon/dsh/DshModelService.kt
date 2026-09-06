package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentPresetInfo
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.TimeUnit

/**
 * The DeepSeek Harness model + agent-preset catalogue (issue #333), read from dsh's OWN host RPCs
 * rather than guessed from a config file.
 *
 * ```
 *   llm.models       → {groups:[{id:<provider>, name, models:[{id, name, reasoning:{efforts:[{id,name}],
 *                                                                                  defaultEffort}}]}], failures}
 *   agentPreset.list → {presets:[{id, trust:"system"|"user", isDefault, name, description}],
 *                       authorable, hasDocument}
 * ```
 * Both are HOST-level and need NO session (probe-verified against dsh 0.1.0-rc.6 on 2026-09-07), which is
 * what makes a catalogue read possible before the user has opened anything. `llm.discoverModels` is NOT
 * used: it demands a `settingsNs` argument and re-probes a provider's remote endpoint, i.e. it is the
 * "go ask the network" call, not the "what do I have" one.
 *
 * ## Two sources, in this order
 *
 *  1. **A live host.** If the daemon is already driving a dsh session, its [DshApiClient] answers these
 *     RPCs directly ([DshHosts]). Free, instant, and it avoids a second dsh process transiently touching
 *     the same `$DSH_HOME` store.
 *  2. **A throwaway host.** Otherwise boot `dsh --profile web --port 0`, ask, and kill it. This costs a
 *     Node start-up, which is exactly why the answer is cached for [CACHE_TTL_MS].
 *
 * The transient host deliberately does NOT open the mux WebSocket — [DshApiClient.start] is never called.
 * RPC is plain HTTP; a catalogue read has no events to receive, and opening a downlink we would
 * immediately abandon is the kind of thing that leaves a host holding a subscription for a client that
 * is already gone.
 *
 * ## What "no answer" means
 *
 * Every failure path returns `ModelsList(agent = DSH, error = …)` — an empty list with no reason is how a
 * picker ends up silently blank, which is the state issue #255 shipped and #333 replaces. Only SUCCESS is
 * cached: a dsh that was mid-upgrade for one fetch must not be remembered as broken for ten minutes.
 *
 * ## Model ids on the wire
 *
 * The wire id is dsh's OWN model name (`deepseek-v4-pro`), never `provider/model`. dsh's
 * `session.selectModel` takes provider and model as SEPARATE arguments, and [DshBackend] re-joins the
 * provider from the SESSION's own `session.models` listing at selection time — which is also the listing
 * that decides, so the join can never go stale against this cache. Encoding the provider into the id
 * would leak a daemon-internal join into [dev.ccpocket.protocol.SessionLive.model], the model chip and
 * the session header — all of which show the raw string to the user.
 */
class DshModelService(
    private val dshBin: String? = null,
    /** A host we are already driving; null = none open, boot a throwaway one. */
    private val liveRpc: () -> DshRpc? = { DshHosts.first() },
    /** Seam for tests: opens a throwaway host, or null when one cannot be started. */
    private val transientHost: suspend () -> TransientHost? = { bootTransient(dshBin) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val log = logger("DshModelService")

    /** A throwaway dsh host: something to ask, and a way to stop it. */
    class TransientHost(val rpc: DshRpc, val close: () -> Unit)

    private data class Cached(val at: Long, val list: ModelsList)

    @Volatile private var cache: Cached? = null

    suspend fun fetch(): ModelsList {
        cache?.takeIf { nowMs() - it.at < CACHE_TTL_MS }?.let { return it.list }
        val live = runCatching { liveRpc() }.getOrNull()
        if (live != null) {
            // A live host that refuses is reported as-is: it is the user's real dsh, and booting a second
            // one to contradict it would report a catalogue the open session is not actually using.
            // ONE attempt: a host we are already driving is known-good, so a null answer is a real
            // carrier failure, not the boot window the retry loop below exists for.
            return remember(read(live, attempts = 1))
        }
        val host = runCatching { transientHost() }.getOrElse {
            return failure("could not start DeepSeek Harness to read its models: ${it.message}")
        } ?: return failure("could not start DeepSeek Harness to read its models")
        return try {
            remember(read(host.rpc, attempts = RPC_ATTEMPTS))
        } finally {
            runCatching { host.close() }
        }
    }

    private fun remember(list: ModelsList): ModelsList {
        if (list.error == null) cache = Cached(nowMs(), list)
        return list
    }

    private fun failure(why: String): ModelsList {
        log.warn("dsh catalogue unavailable: $why")
        return ModelsList(agent = AgentKind.DSH, error = why)
    }

    /** Both catalogue RPCs, folded into one [ModelsList]. */
    private suspend fun read(rpc: DshRpc, attempts: Int): ModelsList {
        val models = rpc.call("llm.models", attempts)
            ?: return failure("DeepSeek Harness did not answer llm.models")
        val groups = models.arr("groups").orEmpty()
        val ids = LinkedHashSet<String>()
        val caps = mutableListOf<ModelCapabilities>()
        for (group in groups) {
            val g = group as? JsonObject ?: continue
            // The group id IS the provider. It is not carried onto the wire — [DshBackend] re-joins it from
            // the session's own listing at selection time — but a model routed by two providers must still
            // appear ONCE, or the picker shows the user a duplicate row.
            g.str("id") ?: continue
            for (entry in g.arr("models").orEmpty()) {
                val m = entry as? JsonObject ?: continue
                val id = m.str("id")?.takeIf { it.isNotBlank() } ?: continue
                if (!ids.add(id)) continue
                val reasoning = m.obj("reasoning")
                val efforts = reasoning?.arr("efforts").orEmpty()
                    .mapNotNull { (it as? JsonObject)?.str("id")?.takeIf(String::isNotBlank) }
                val default = reasoning?.str("defaultEffort")?.takeIf { it.isNotBlank() }
                if (efforts.isNotEmpty() || default != null) {
                    caps += ModelCapabilities(
                        model = id,
                        reasoningEfforts = efforts,
                        defaultReasoningEffort = default,
                    )
                }
            }
        }
        if (ids.isEmpty()) {
            // dsh reports per-provider failures rather than throwing, and an empty catalogue with no
            // reason is indistinguishable from "the picker is broken". Quote its own words.
            val why = models.arr("failures").orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("message") ?: (it as? JsonObject)?.str("error") }
                .firstOrNull()
            return failure(
                why?.let { "DeepSeek Harness listed no usable models: $it" }
                    ?: "DeepSeek Harness listed no models — check its provider configuration.",
            )
        }
        // Presets degrade INDEPENDENTLY: a dsh build without agentPreset.list still has a model picker,
        // and failing the whole fetch over the newer of the two RPCs would take the older one down with it.
        // One attempt: llm.models just answered, so the host is up — a null here means this dsh has no
        // such method, not that it is still booting.
        val presets = runCatching { rpc.call("agentPreset.list", attempts = 1) }.getOrNull()
            ?.let(::presetRows).orEmpty()
        return ModelsList(
            agent = AgentKind.DSH,
            models = ids.toList(),
            modelCapabilities = caps,
            agentPresets = presets,
        )
    }

    private fun presetRows(value: JsonObject): List<AgentPresetInfo> =
        value.arr("presets").orEmpty().mapNotNull { entry ->
            val p = entry as? JsonObject ?: return@mapNotNull null
            val id = p.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentPresetInfo(
                id = id,
                // dsh calls them name/description; the wire calls them label/detail. Verbatim either way —
                // dsh ships localized copy ("标准模式") and translating it here would be inventing text.
                label = p.str("name")?.takeIf { it.isNotBlank() } ?: id,
                detail = p.str("description")?.takeIf { it.isNotBlank() },
                // `trust` is dsh's own provenance marker: "system" = shipped with the CLI, "user" = authored
                // by the human in `$DSH_HOME/.agent-presets/`. Both spellings are probe-verified in the
                // rc.6 bundle; anything else is treated as custom, because "not one of ours" is the safer
                // way to be wrong (it labels a row, it does not gate anything).
                custom = p.str("trust")?.let { it != "system" } ?: false,
                recommended = p.bool("isDefault") ?: false,
            )
        }

    private companion object {
        /** Long enough that opening the picker twice does not boot dsh twice; short enough that a preset
         *  the user just authored in `$DSH_HOME/.agent-presets/` shows up without restarting the daemon. */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** dsh prints its bound port once the LISTENER is up, a moment before its routes finish mounting —
         *  the same window [DshApiClient] retries across. */
        const val RPC_ATTEMPTS = 20
        const val RPC_RETRY_MS = 250L
        const val BOOT_TIMEOUT_MS = 60_000L

        /** Unwrap one RPC to its `value`, retrying the boot window, or null on a carrier/business failure. */
        suspend fun DshRpc.call(method: String, attempts: Int): JsonObject? {
            repeat(attempts) {
                val result = rpc(method, buildJsonObject { })
                if (result != null) {
                    // A business error is dsh's verdict and will not improve by asking again.
                    return if (result["ok"]?.toString() == "true") result.obj("value") else null
                }
                delay(RPC_RETRY_MS)
            }
            return null
        }

        /**
         * Boot `dsh --profile web --port 0`, wait for its banner, and hand back an RPC seam plus a killer.
         *
         * The child's stdout is drained on a daemon thread for its whole life: dsh keeps logging, and a
         * full pipe buffer would wedge the process we are about to ask a question of.
         */
        suspend fun bootTransient(dshBin: String?): TransientHost? = withContext(Dispatchers.IO) {
            val exe = DshLauncher.resolveExecutable(dshBin)
            val proc = ProcessBuilder(exe.toString(), "--profile", "web", "--port", "0")
                .redirectErrorStream(true)
                .redirectInput(
                    ProcessBuilder.Redirect.from(
                        java.io.File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null"),
                    ),
                )
                .also { it.environment().putIfAbsent("LANG", "C.UTF-8") }
                .start()
            val portFuture = java.util.concurrent.CompletableFuture<Int>()
            Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (!portFuture.isDone) DshLauncher.parseBootPort(line)?.let(portFuture::complete)
                    }
                }
                portFuture.complete(0) // stdout closed without a banner — unblock the waiter
            }.apply { isDaemon = true; name = "dsh-catalogue-stdout" }.start()
            val port = runCatching {
                portFuture.get(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(0)
            if (port <= 0) {
                proc.destroyForcibly()
                return@withContext null
            }
            // No mux: start() is deliberately never called (see the class KDoc). The scope is only the
            // client's constructor contract and owns nothing here.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = DshApiClient(port, scope, onFrame = {}, onFatal = {})
            TransientHost(
                rpc = { m, p -> client.rpc(m, p) },
                close = {
                    runCatching { client.close() }
                    runCatching { scope.cancel() }
                    proc.destroyForcibly()
                },
            )
        }
    }
}
