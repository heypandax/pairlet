package dev.ccpocket.daemon.zcode

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Reads ZCode 3.7.6's probe-observed `~/.zcode/cli/config.json` model configuration without starting the desktop
 * runtime. ZCode 0.16.3 stores `model.main = {provider,model,...}` (and may carry lite/available targets)
 * plus `provider.<id>.models.<id>`; a legacy `"provider/model"` main is tolerated only as fallback.
 * Parsing is defensive because desktop releases may add fields. A mismatch returns an empty list, never
 * guessed catalog entries or an eager app-server process.
 */
class ZCodeModelService(private val config: Path = ZCodePaths.cliConfig()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(): ModelsList = withContext(Dispatchers.IO) {
        val models = configuredModels(config)
        ModelsList(agent = AgentKind.ZCODE, models = models)
    }

    fun defaultModel(): String? = defaultModel(config)

    internal fun configuredModels(path: Path): List<String> = runCatching {
        val root = readRoot(path) ?: return emptyList()
        val providers = root["provider"] as? JsonObject ?: JsonObject(emptyMap())
        val out = linkedSetOf<String>()
        mainModel(root)?.let(out::add)
        val model = root["model"] as? JsonObject
        model?.get("lite")?.let(::targetModel)?.let(out::add)
        (model?.get("available") as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull(::targetModel).forEach(out::add)
        for ((providerId, value) in providers) {
            val models = (value as? JsonObject)?.get("models") as? JsonObject ?: continue
            models.keys.sorted().forEach { modelId -> out += "$providerId/$modelId" }
        }
        out.toList()
    }.getOrDefault(emptyList())

    internal fun defaultModel(path: Path): String? = runCatching {
        readRoot(path)?.let(::mainModel)
    }.getOrNull()

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
