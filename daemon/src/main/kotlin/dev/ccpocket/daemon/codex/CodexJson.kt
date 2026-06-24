package dev.ccpocket.daemon.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Lenient JSON accessors for the codex app-server / rollout schemas — one home, shared by the backend,
// scanner and replay rather than re-declared in each.
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** First text from a Codex message `content[]` (role-specific `input_text` / `output_text`). */
internal fun codexMessageText(message: JsonObject): String? {
    val content = message["content"] as? JsonArray ?: return null
    return content.firstNotNullOfOrNull { el ->
        (el as? JsonObject)?.takeIf { it.str("type")?.endsWith("_text") == true }?.str("text")
    }
}
