package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelCatalogMeta
import dev.ccpocket.protocol.ModelsList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-device copy of one computer's model catalog (Codex catalog cache, design
 * `docs/design/CODEX-MODEL-CATALOG-CACHE.md`): what the picker can show the instant it opens after an app
 * restart, before the daemon has answered — or while the phone is offline.
 *
 * What is stored is deliberately NARROW: the rows, their capability facts and the daemon's provenance
 * ([ModelCatalogMeta]). Never the whole [ModelsList] — that frame also carries permission-mode presets,
 * backend-wide efforts and other per-daemon capabilities, and restoring an old frame would walk a previous
 * daemon's capabilities into a new connection. A restored record therefore comes back as a PREVIEW list
 * (see [PocketRepository.agentModelPreview]): rows to look at, not a catalog to reason from.
 *
 * Keyed by the binding identity (relay | account | daemon key | device id — the same identity the history
 * cache uses) and the agent; the daemon's own [ModelCatalogMeta.scope] travels inside the record. Only a
 * CONFIRMED account catalog ([MODEL_CATALOG_SOURCE_DYNAMIC], no error, scoped, final) is stored — the CLI's
 * built-ins, an unconfirmed account, a file read, a kept-under-failure answer and Pairlet's own fallback never
 * overwrite it. Identical content (same scope + content version) is not rewritten.
 *
 * One JSON record per (identity, agent), atomically replaced; bounded in rows and bytes on BOTH directions;
 * any record that does not parse or exceeds the bounds is dropped and the normal fetch path runs. No new
 * storage dependency — this rides the existing key/value store the rest of the settings use.
 */
internal class ModelCatalogStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val remove: (String) -> Unit,
) {
    @Serializable
    private data class Record(
        val schema: Int = SCHEMA,
        val agent: AgentKind,
        val models: List<String>,
        val capabilities: List<ModelCapabilities> = emptyList(),
        val catalog: ModelCatalogMeta,
    )

    /** The preview list for [identity]/[agent], or null when nothing usable is stored. */
    fun load(identity: String, agent: AgentKind): ModelsList? {
        val k = key(identity, agent)
        val raw = read(k) ?: return null
        val rec = if (raw.length > MAX_CHARS) null else runCatching { json.decodeFromString(Record.serializer(), raw) }.getOrNull()
        if (rec == null || rec.schema != SCHEMA || rec.agent != agent || rec.models.size > MAX_MODELS || rec.capabilities.size > MAX_MODELS) {
            remove(k) // unreadable, oversized or from another schema: drop it, the fetch path takes over
            return null
        }
        return ModelsList(
            agent = agent,
            models = rec.models,
            modelCapabilities = rec.capabilities,
            catalog = rec.catalog.copy(refreshing = false),
        )
    }

    /** Whether [list] is a catalog this store may hold at all (see the class KDoc). */
    fun persistable(list: ModelsList): Boolean {
        val meta = list.catalog ?: return false
        return !meta.refreshing && list.error == null && meta.source == MODEL_CATALOG_SOURCE_DYNAMIC &&
            !meta.scope.isNullOrBlank() && !meta.contentVersion.isNullOrBlank()
    }

    /** Persist [list] when it is a confirmed catalog whose content differs from the stored record; returns
     *  whether anything was written. */
    fun save(identity: String, agent: AgentKind, list: ModelsList): Boolean {
        if (!persistable(list)) return false
        val meta = list.catalog!!
        val k = key(identity, agent)
        read(k)?.let { raw ->
            val existing = runCatching { json.decodeFromString(Record.serializer(), raw) }.getOrNull()
            if (existing != null && existing.catalog.scope == meta.scope && existing.catalog.contentVersion == meta.contentVersion) return false
        }
        val rec = Record(
            agent = agent,
            models = list.models.take(MAX_MODELS),
            capabilities = list.modelCapabilities.take(MAX_MODELS),
            catalog = meta.copy(refreshing = false),
        )
        val encoded = json.encodeToString(Record.serializer(), rec)
        if (encoded.length > MAX_CHARS) return false
        write(k, encoded)
        return true
    }

    fun clear(identity: String, agent: AgentKind) = remove(key(identity, agent))

    companion object {
        const val SCHEMA = 1
        const val MAX_MODELS = 128
        const val MAX_CHARS = 96 * 1024
        const val KEY_PREFIX = "model_catalog|"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }

        fun key(identity: String, agent: AgentKind) = "$KEY_PREFIX${agent.name.lowercase()}|$identity"
    }
}

/**
 * Does a turn error say the MODEL was refused (not found / unsupported / retired / no access)? Substring
 * matching on the backend's English text, deliberately narrow: a false positive only costs one extra catalog
 * check, a false negative leaves the user re-picking from a stale list until the next scheduled check.
 */
internal fun looksLikeModelUnavailable(error: String): Boolean {
    val m = error.lowercase()
    if ("model" !in m) return false
    return MODEL_UNAVAILABLE_HINTS.any { it in m }
}

private val MODEL_UNAVAILABLE_HINTS = listOf(
    "not found", "does not exist", "unknown model", "invalid model", "unsupported model", "not supported",
    "no longer", "retired", "deprecated", "not available", "unavailable", "do not have access", "no access",
)

/**
 * When a FRESH-ENOUGH answer may be reused instead of asking the daemon again. Plain object, injected clock,
 * no coroutines — so the rule is unit-testable by advancing a `var`.
 *
 * One rule: an answer younger than [REUSE_MS] for the SAME context (binding identity + working directory —
 * the daemon caches per working directory, because Codex config and profiles can differ per project) is
 * reused; a different context, a manual refresh, an invalidation or a reset never reuses. In-flight merging
 * and the request timeout live with the request itself in the repository, keyed by its token.
 */
internal class ModelCatalogRefreshPolicy(private val now: () -> Long) {
    private class Reply(val context: String, val at: Long)

    private val lastReply = HashMap<AgentKind, Reply>()

    /** Is there an answer for [agent] in [context] young enough to serve instead of a new request? */
    fun reusable(agent: AgentKind, context: String): Boolean =
        lastReply[agent]?.let { it.context == context && now() - it.at < REUSE_MS } == true

    /** A FINAL, accepted answer landed for [context]. */
    fun replied(agent: AgentKind, context: String) { lastReply[agent] = Reply(context, now()) }

    /** Forget the previous answer's age so the next surface asks again (a model-unavailable error). */
    fun invalidate(agent: AgentKind) { lastReply.remove(agent) }

    /** Another computer: nothing here describes it. */
    fun reset() { lastReply.clear() }

    companion object {
        const val REUSE_MS = 10 * 60_000L

        /** How long one request may stay outstanding before its cue ends and a new one may go out. */
        const val IN_FLIGHT_MS = 30_000L
    }
}
