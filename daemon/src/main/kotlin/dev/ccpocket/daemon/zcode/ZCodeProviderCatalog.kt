package dev.ccpocket.daemon.zcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Projects ZCode's on-disk provider store into the `runtimeModel` payload that `session/create` and
 * `session/resume` accept.
 *
 * WHY this exists. ZCode 3.9+ moved the desktop's provider store to `~/.zcode/v2/config.json`, but the
 * headless agent runtime it bundles (`Resources/glm/zcode.cjs`) resolves its own model config from
 * `~/.zcode/cli/config.json` and nothing else — a file the desktop no longer writes. Verified against the
 * official 3.11.2 bundle: the runtime contains three `.zcode/cli` references, zero `.zcode/v2` ones, and no
 * migration between them. The official shell bridges the gap over the wire instead: every open carries the
 * provider inline (`runtimeModel`) or pushes a `workspace/updateProviderRegistry`. cc-pocket sent neither, so
 * the runtime fell back to the missing file and answered `Model config is missing. Create
 * ~/.zcode/cli/config.json …` no matter how well ZCode itself was configured.
 *
 * The emitted shapes are transcribed from the 3.11.2 runtime's own zod schemas and every one of them is
 * `.strict()` — a single unknown key rejects the whole open, so nothing may be added speculatively:
 *
 *   runtimeModel = { revision:string, generatedAt:int>=0, model:{providerId,modelId,variant?}, provider, thoughtLevel? }
 *   provider     = { providerId, kind:"anthropic"|"openai"|"openai-compatible", apiFormat?, label?,
 *                    source?:"builtin"|"models-dev"|"custom"|"user"|"workspace"|"ephemeral", baseURL?,
 *                    apiKey?, apiKeyRequired?, headers?, providerOptions?, logoUrl?, modelsDevProviderId?,
 *                    models:[…] (min 1) }
 *   apiKey       = {source:"credential",key} | {source:"env",name} | {source:"server-config",key} | {source:"inline",value}
 *   model        = { modelId, label?, description?, contextWindow?:int>0, maxOutputTokens?:int>0,
 *                    reasoning?, supportsImages?, supportsPdf?, supportsVideo?, … }
 *   reasoning    = { enabled:boolean, levels:[{value,label,description?}], defaultLevel?, providerOptionsByLevel? }
 *
 * The on-disk store uses DIFFERENT names for several of these (`name`→label, `options.*` flattened,
 * `limit.context`→contextWindow, `modalities.input`→supports*, `reasoning.variants`→levels,
 * `reasoning.defaultVariant`→defaultLevel), which is why this translation layer exists rather than passing
 * the file through. Parsing stays defensive: an unreadable or unexpected entry drops that provider, never
 * the whole catalog.
 */
object ZCodeProviderCatalog {
    /** Runtime enums. A value outside these sets fails `.strict()` validation, so such providers are dropped. */
    private val KINDS = setOf("anthropic", "openai", "openai-compatible")
    private val SOURCES = setOf("builtin", "models-dev", "custom", "user", "workspace", "ephemeral")

    /** One model as the on-disk store describes it, already renamed to the runtime's vocabulary. */
    data class Model(
        val modelId: String,
        val label: String? = null,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsImages: Boolean? = null,
        val supportsPdf: Boolean? = null,
        val supportsVideo: Boolean? = null,
        val reasoningEnabled: Boolean? = null,
        val reasoningLevels: List<String> = emptyList(),
        val reasoningDefault: String? = null,
    )

    data class Provider(
        val providerId: String,
        val kind: String,
        val label: String? = null,
        val source: String? = null,
        val baseURL: String? = null,
        val apiKey: String? = null,
        val apiKeyRequired: Boolean? = null,
        val enabled: Boolean = true,
        val models: List<Model> = emptyList(),
    ) {
        /**
         * True when cc-pocket can actually open a session with this provider headlessly.
         *
         * A blank `apiKey` is the discriminator that matters here: ZCode's built-in Coding/Start Plan entries
         * ship with an empty key because the Electron host injects their OAuth credentials privately at
         * request time (`interaction/requestProviderRuntimeHeaders`), which cc-pocket has no access to. Listing
         * them would offer models that always fail on the first turn.
         */
        val usable: Boolean get() = enabled && kind in KINDS && models.isNotEmpty() && !apiKey.isNullOrBlank()

        /** Listable but not necessarily authenticated — the fallback when nothing at all is [usable]. */
        val listable: Boolean get() = enabled && kind in KINDS && models.isNotEmpty()
    }

    /** Reads the `provider` map of either store generation. Returns config order, which is what the user sees. */
    fun parse(root: JsonObject): List<Provider> {
        val providers = root["provider"] as? JsonObject ?: return emptyList()
        return providers.mapNotNull { (id, value) -> provider(id, value as? JsonObject ?: return@mapNotNull null) }
    }

    /** `provider/model` keys for the cc-pocket wire, preferring authenticated providers. */
    fun catalog(providers: List<Provider>): List<String> =
        providers.filter { it.usable }.ifEmpty { providers.filter { it.listable } }
            .flatMap { p -> p.models.map { "${p.providerId}/${it.modelId}" } }

    /**
     * Builds the `runtimeModel` for one `provider/model` selection, or null when the selection cannot be
     * satisfied from this store (unknown provider, unknown model, or a kind the runtime would reject).
     */
    fun runtimeModel(provider: Provider, modelId: String, revision: String, generatedAt: Long): JsonObject? {
        if (provider.models.none { it.modelId == modelId }) return null
        val providerJson = providerJson(provider) ?: return null
        return buildJsonObject {
            put("revision", revision)
            put("generatedAt", generatedAt.coerceAtLeast(0))
            putJsonObject("model") { put("providerId", provider.providerId); put("modelId", modelId) }
            put("provider", providerJson)
        }
    }

    internal fun providerJson(provider: Provider): JsonObject? {
        if (provider.kind !in KINDS || provider.models.isEmpty()) return null
        return buildJsonObject {
            put("providerId", provider.providerId)
            put("kind", provider.kind)
            provider.label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
            provider.source?.takeIf { it in SOURCES }?.let { put("source", it) }
            provider.baseURL?.takeIf { it.isNotBlank() }?.let { put("baseURL", it) }
            provider.apiKeyRequired?.let { put("apiKeyRequired", it) }
            putJsonArray("models") { provider.models.forEach { add(modelJson(it)) } }
            // Emitted LAST on purpose: the credential then sits deepest in the serialized frame, past the
            // truncation window of the only place a raw stdin line can reach a log (AgentProcess logs
            // `json.take(120)` when a write is dropped on a closed pipe).
            provider.apiKey?.takeIf { it.isNotBlank() }
                ?.let { putJsonObject("apiKey") { put("source", "inline"); put("value", it) } }
        }
    }

    private fun modelJson(model: Model): JsonObject = buildJsonObject {
        put("modelId", model.modelId)
        model.label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
        model.contextWindow?.takeIf { it > 0 }?.let { put("contextWindow", it) }
        model.maxOutputTokens?.takeIf { it > 0 }?.let { put("maxOutputTokens", it) }
        model.supportsImages?.let { put("supportsImages", it) }
        model.supportsPdf?.let { put("supportsPdf", it) }
        model.supportsVideo?.let { put("supportsVideo", it) }
        // `levels` is required inside `reasoning`, so a store entry that only says enabled=true without
        // variants is emitted as no reasoning at all rather than as an invalid object.
        if (model.reasoningEnabled != null && model.reasoningLevels.isNotEmpty()) {
            putJsonObject("reasoning") {
                put("enabled", model.reasoningEnabled)
                putJsonArray("levels") {
                    model.reasoningLevels.forEach { level -> add(buildJsonObject { put("value", level); put("label", level) }) }
                }
                model.reasoningDefault?.takeIf { it in model.reasoningLevels }?.let { put("defaultLevel", it) }
            }
        }
    }

    private fun provider(id: String, raw: JsonObject): Provider? {
        val kind = raw.str("kind") ?: return null
        // 3.11 nests credentials under `options`; the 3.7.6 generation kept them flat. Accept both.
        val options = raw["options"] as? JsonObject
        val models = (raw["models"] as? JsonObject)?.map { (modelId, value) -> model(modelId, value as? JsonObject) }.orEmpty()
        return Provider(
            providerId = id,
            kind = kind,
            label = raw.str("name") ?: raw.str("label"),
            source = raw.str("source"),
            baseURL = options?.str("baseURL") ?: raw.str("baseURL"),
            apiKey = options?.str("apiKey") ?: raw.str("apiKey"),
            apiKeyRequired = options?.bool("apiKeyRequired") ?: raw.bool("apiKeyRequired"),
            // `systemDisabledReason: "oauth_provider_inactive"` rides along with enabled=false; the flag is
            // the contract, the reason string is not.
            enabled = raw.bool("enabled") ?: true,
            models = models,
        )
    }

    private fun model(id: String, raw: JsonObject?): Model {
        val limit = raw?.get("limit") as? JsonObject
        val inputs = ((raw?.get("modalities") as? JsonObject)?.get("input") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val reasoning = raw?.get("reasoning") as? JsonObject
        val levels = (reasoning?.get("variants") as? JsonArray ?: reasoning?.get("levels") as? JsonArray)
            ?.mapNotNull(::levelValue).orEmpty()
        return Model(
            modelId = id,
            label = raw?.str("name") ?: raw?.str("label"),
            contextWindow = limit?.int("context") ?: raw?.int("contextWindow"),
            maxOutputTokens = limit?.int("output") ?: raw?.int("maxOutputTokens"),
            // Absent modalities mean "unknown", not "unsupported" — leave the field off rather than assert false.
            supportsImages = inputs.takeIf { it.isNotEmpty() }?.contains("image"),
            supportsPdf = inputs.takeIf { it.isNotEmpty() }?.contains("pdf"),
            supportsVideo = inputs.takeIf { it.isNotEmpty() }?.contains("video"),
            reasoningEnabled = reasoning?.bool("enabled"),
            reasoningLevels = levels,
            reasoningDefault = reasoning?.str("defaultVariant") ?: reasoning?.str("defaultLevel"),
        )
    }

    /** A level is a bare string in the store (`["low","high"]`); tolerate the runtime's object form too. */
    private fun levelValue(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonObject -> element.str("value")
        else -> null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
