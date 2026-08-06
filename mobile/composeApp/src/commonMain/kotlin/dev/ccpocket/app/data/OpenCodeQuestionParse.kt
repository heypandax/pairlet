package dev.ccpocket.app.data

import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.isOpenCodeQuestionTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Parses an OpenCode `question` tool's preview JSON into the shared [AskQuestion]/[AskOption] model so
 * the client can render a read-only question card instead of a raw JSON tool row (issue #210, phase 1).
 *
 * The wire shape is OpenCode's own: `{"questions":[{question, header, multiple, options:[{label,
 * description}]}]}`. Parsing is deliberately tolerant — key case is ignored (a pre-#210 daemon
 * PascalCases the top key), a few option-key synonyms are accepted, and ANY malformed / truncated JSON
 * yields null so the caller falls back to the existing plain tool row (never worse than today). This
 * keeps mixed versions safe: a new client renders cards off both new and old daemons, and where the old
 * daemon truncated the preview past parseability, it simply degrades to the raw row it always showed.
 */
object OpenCodeQuestionParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(tool: String, preview: String?): List<AskQuestion>? {
        if (!isOpenCodeQuestionTool(tool)) return null
        val text = preview?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val arr = root.getIgnoreCase("questions") as? JsonArray ?: return null
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseOne) }.takeIf { it.isNotEmpty() }
    }

    private fun parseOne(o: JsonObject): AskQuestion? {
        val question = (o.getIgnoreCase("question") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        val header = (o.getIgnoreCase("header") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val multi = ((o.getIgnoreCase("multiple") ?: o.getIgnoreCase("multiSelect")) as? JsonPrimitive)
            ?.booleanOrNull ?: false
        val options = (o.getIgnoreCase("options") as? JsonArray).orEmpty().mapNotNull(::parseOption)
        return AskQuestion(question = question, header = header, multiSelect = multi, options = options)
    }

    private fun parseOption(el: JsonElement): AskOption? {
        // a bare string option ("Redis") or the object form ({label, description})
        (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return AskOption(it) }
        val o = el as? JsonObject ?: return null
        val label = (o.getIgnoreCase("label") as? JsonPrimitive)?.contentOrNull
            ?: (o.getIgnoreCase("value") as? JsonPrimitive)?.contentOrNull
            ?: (o.getIgnoreCase("text") as? JsonPrimitive)?.contentOrNull
        if (label.isNullOrBlank()) return null
        val desc = (o.getIgnoreCase("description") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return AskOption(label, desc)
    }

    private fun JsonObject.getIgnoreCase(key: String): JsonElement? =
        this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}
