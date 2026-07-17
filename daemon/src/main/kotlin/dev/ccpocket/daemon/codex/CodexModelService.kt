package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    suspend fun fetch(): ModelsList = withContext(Dispatchers.IO) {
        runCatching {
            val configured = CodexDefaultModel.resolve(configPath)
            val cached = readCache()
            val models = (listOfNotNull(configured) + cached + CODEX_MODEL_IDS).distinct()
            ModelsList(agent = AgentKind.CODEX, models = models)
        }.getOrElse { e ->
            val configured = CodexDefaultModel.resolve(configPath)
            ModelsList(
                agent = AgentKind.CODEX,
                models = (listOfNotNull(configured) + CODEX_MODEL_IDS).distinct(),
                error = "Failed to list Codex models: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun readCache(): List<String> {
        val file = cachePath.toFile()
        if (!file.isFile) return emptyList()
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val models = root["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val visible = obj["visibility"]?.jsonPrimitive?.contentOrNull == "list"
            val upgrade = obj["upgrade"]?.jsonPrimitive?.contentOrNull
            if (!visible || upgrade != null) return@mapNotNull null
            CacheModel(slug, obj["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE)
        }.sortedWith(compareBy<CacheModel> { it.priority }.thenBy { it.slug }).map { it.slug }
    }

    private data class CacheModel(val slug: String, val priority: Int)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
