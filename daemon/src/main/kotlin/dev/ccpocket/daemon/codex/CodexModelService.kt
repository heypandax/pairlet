package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/** Reads the Codex CLI's local model cache. This mirrors the Mac app/CLI without network calls. */
class CodexModelService(
    private val cachePath: Path = CodexPaths.codexHome().resolve("models_cache.json"),
    private val configPath: Path = CodexPaths.codexHome().resolve("config.toml"),
) {
    /** Synchronous cache lookup used on a session's settings path. Missing/custom models return null:
     *  unknown capability must not be mistaken for known-unsupported. */
    internal fun capabilitiesFor(model: String?): ModelCapabilities? {
        val wanted = model ?: CodexDefaultModel.resolve(configPath) ?: return null
        return runCatching { readCache().firstOrNull { it.slug == wanted }?.capabilities }.getOrNull()
    }

    suspend fun fetch(): ModelsList = withContext(Dispatchers.IO) {
        runCatching {
            val configured = CodexDefaultModel.resolve(configPath)
            val cached = readCache()
            val models = (listOfNotNull(configured) + cached.map { it.slug } + CODEX_MODEL_IDS).distinct()
            ModelsList(
                agent = AgentKind.CODEX,
                models = models,
                modelCapabilities = cached.map { it.capabilities },
            )
        }.getOrElse { e ->
            val configured = CodexDefaultModel.resolve(configPath)
            ModelsList(
                agent = AgentKind.CODEX,
                models = (listOfNotNull(configured) + CODEX_MODEL_IDS).distinct(),
                error = "Failed to list Codex models: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun readCache(): List<CacheModel> {
        val file = cachePath.toFile()
        if (!file.isFile) return emptyList()
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val models = root["models"] as? JsonArray ?: return emptyList()
        return models.asSequence().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { usableWireString(it, MAX_MODEL_ID_LEN) }
                ?: return@mapNotNull null
            val visible = obj["visibility"]?.jsonPrimitive?.contentOrNull == "list"
            // Current caches encode `upgrade` as an object (or null), not a string. Treat every non-null
            // shape as superseded; calling jsonPrimitive on the object aborts the whole model listing.
            val upgrade = obj["upgrade"]?.takeUnless { it is JsonNull }
            if (!visible || upgrade != null) return@mapNotNull null
            val efforts = (obj["supported_reasoning_levels"] as? JsonArray).orEmpty().mapNotNull { level ->
                (level as? JsonObject)?.get("effort")?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { usableWireString(it, MAX_EFFORT_LEN) }
            }.distinct().take(MAX_EFFORTS)
            val tiers = (obj["service_tiers"] as? JsonArray).orEmpty().mapNotNull { tier ->
                val t = tier as? JsonObject ?: return@mapNotNull null
                val id = t["id"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { usableWireString(it, MAX_TIER_ID_LEN) }
                    ?: return@mapNotNull null
                ModelServiceTier(
                    id = id,
                    name = t["name"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.take(MAX_TIER_NAME_LEN)
                        ?: id,
                    description = t["description"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.take(MAX_TIER_DESCRIPTION_LEN),
                )
            }.distinctBy { it.id }.take(MAX_SERVICE_TIERS)
            CacheModel(
                slug = slug,
                priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE,
                capabilities = ModelCapabilities(
                    model = slug,
                    reasoningEfforts = efforts,
                    defaultReasoningEffort = obj["default_reasoning_level"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf { usableWireString(it, MAX_EFFORT_LEN) },
                    serviceTiers = tiers,
                ),
            )
        }.sortedWith(compareBy<CacheModel> { it.priority }.thenBy { it.slug })
            .take(MAX_CACHE_MODELS)
            .toList()
    }

    private data class CacheModel(
        val slug: String,
        val priority: Int,
        val capabilities: ModelCapabilities,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_CACHE_MODELS = 128
        private const val MAX_MODEL_ID_LEN = 128
        private const val MAX_EFFORTS = 16
        private const val MAX_EFFORT_LEN = 32
        private const val MAX_SERVICE_TIERS = 8
        private const val MAX_TIER_ID_LEN = 64
        private const val MAX_TIER_NAME_LEN = 64
        private const val MAX_TIER_DESCRIPTION_LEN = 160

        private fun usableWireString(value: String, maxLength: Int): Boolean =
            value.isNotEmpty() && value.length <= maxLength && value.none(Char::isISOControl)
    }
}
