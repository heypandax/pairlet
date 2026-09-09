package dev.ccpocket.daemon.zcode

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Reads ZCode's provider configuration without starting the desktop runtime, and projects it both onto the
 * cc-pocket model wire and onto the runtime's own `runtimeModel` payload (see [ZCodeProviderCatalog]).
 *
 * Two store generations are supported from one parser. 3.7.6 kept an explicit selection in
 * `model.main = {provider,model,…}` (with optional lite/available targets) plus `provider.<id>.models.<id>`;
 * a legacy `"provider/model"` string main is tolerated as a fallback. 3.11 dropped the whole `model` object —
 * its config has a `provider` map and nothing else — so the default there is derived from the first provider
 * cc-pocket can actually authenticate with. Parsing is defensive because desktop releases keep adding fields.
 * A mismatch returns an empty list, never guessed catalog entries or an eager app-server process.
 */
class ZCodeModelService(private val configOverride: Path? = null) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Resolved per call, never cached in a field: the daemon outlives ZCode installs and reconfigurations,
     * and a store that only appears after boot (a fresh ZCode setup, a first provider) must be picked up
     * without a daemon restart.
     */
    private val config: Path get() = configOverride ?: ZCodePaths.providerConfig()

    suspend fun fetch(): ModelsList = withContext(Dispatchers.IO) {
        val models = configuredModels(config)
        ModelsList(agent = AgentKind.ZCODE, models = models)
    }

    fun defaultModel(): String? = defaultModel(config)

    /**
     * The `runtimeModel` for [model] (or the configured default when null/blank), as `session/create` and
     * `session/resume` accept it. Null when the store cannot satisfy the selection — the caller then opens
     * without it and lets the runtime speak for itself rather than sending a half-built provider.
     */
    fun runtimeModel(model: String? = null): JsonObject? = runtimeModel(config, model)

    internal fun configuredModels(path: Path): List<String> = runCatching {
        val root = readRoot(path) ?: return emptyList()
        val out = linkedSetOf<String>()
        // 3.7.6's explicit targets come first so the user's own ordering survives; 3.11 has none of these.
        mainModel(root)?.let(out::add)
        val model = root["model"] as? JsonObject
        model?.get("lite")?.let(::targetModel)?.let(out::add)
        (model?.get("available") as? JsonArray).orEmpty().mapNotNull(::targetModel).forEach(out::add)
        out += ZCodeProviderCatalog.catalog(ZCodeProviderCatalog.parse(root))
        out.toList()
    }.getOrDefault(emptyList())

    internal fun defaultModel(path: Path): String? = runCatching {
        val root = readRoot(path) ?: return null
        mainModel(root)?.let { return it }
        // 3.11: no stored selection at all. Prefer a provider that can authenticate headlessly, so the very
        // first open of a fresh install picks something that works instead of a Coding Plan entry whose
        // credentials only the Electron host can supply.
        val providers = ZCodeProviderCatalog.parse(root)
        val pick = providers.firstOrNull { it.usable } ?: return null
        "${pick.providerId}/${pick.models.first().modelId}"
    }.getOrNull()

    internal fun runtimeModel(path: Path, model: String?): JsonObject? = runCatching {
        val root = readRoot(path) ?: return null
        val selection = model?.trim()?.takeIf { it.isNotEmpty() } ?: defaultModel(path) ?: return null
        val slash = selection.indexOf('/')
        if (slash <= 0 || slash == selection.lastIndex) return null
        val providerId = selection.substring(0, slash)
        val modelId = selection.substring(slash + 1)
        val provider = ZCodeProviderCatalog.parse(root).firstOrNull { it.providerId == providerId } ?: return null
        ZCodeProviderCatalog.runtimeModel(provider, modelId, revision(path), generatedAt(path))
    }.getOrNull()

    /**
     * A content-derived revision. The runtime dedupes provider updates by revision, so this must change when
     * the store changes and must NOT change on a mere reopen — a timestamp or random value would make every
     * open look like a new registry generation.
     */
    private fun revision(path: Path): String =
        "cc-pocket-" + runCatching { path.readText().hashCode() }.getOrDefault(0).toUInt().toString(16)

    private fun generatedAt(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()?.takeIf { it > 0 }
            ?: System.currentTimeMillis()

    private fun readRoot(path: Path): JsonObject? {
        if (!path.isRegularFile()) return null
        return json.parseToJsonElement(path.readText()) as? JsonObject
    }

    private fun mainModel(root: JsonObject): String? =
        (root["model"] as? JsonObject)?.get("main")?.let(::targetModel)

    private fun targetModel(value: kotlinx.serialization.json.JsonElement): String? {
        val legacy = (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (legacy != null) return legacy
        val target = value as? JsonObject ?: return null
        val provider = (target["provider"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: (target["providerId"] as? JsonPrimitive)?.contentOrNull?.trim()
        val model = (target["model"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: (target["modelId"] as? JsonPrimitive)?.contentOrNull?.trim()
        return if (provider.isNullOrEmpty() || model.isNullOrEmpty()) null else "$provider/$model"
    }
}
