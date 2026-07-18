package dev.ccpocket.daemon.opencode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

/**
 * Best-effort resolve of the model OpenCode uses when a session starts with NO explicit `--model`.
 * Reads the project's `.opencode/opencode.json` or the global `~/.config/opencode/opencode.jsonc`
 * for the top-level `model` key. NEVER throws: a failed resolve degrades to null.
 */
object OpenCodeDefaultModel {
    // the global config is a .jsonc — comments/trailing commas are the NORM there, and isLenient
    // does NOT cover them: without these flags every commented config silently resolved to null
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; allowComments = true; allowTrailingComma = true }

    fun resolve(workdir: String? = null): String? = runCatching {
        // Try project-level config first
        val projectConfig = workdir?.let { Path.of(it, ".opencode", "opencode.json").toFile() }
        val globalConfig = Path.of(System.getProperty("user.home"), ".config", "opencode", "opencode.jsonc").toFile()
        val configFile = listOfNotNull(projectConfig?.takeIf { it.isFile }, globalConfig.takeIf { it.isFile }).firstOrNull()
            ?: return@runCatching null
        val root = json.parseToJsonElement(configFile.readText()).jsonObject
        root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && "/" in it }
    }.getOrNull()
}
