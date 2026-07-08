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

/**
 * True when a `user`-role rollout message wasn't typed by the human but injected by Codex as context, so it
 * must never seed a session's title/preview or show as a chat bubble. Covers three injected shapes verified
 * against real rollouts (codex 0.124):
 *  - the `<environment_context>` / `<permissions …>` / `<turn_aborted>` wrappers,
 *  - the auto-prepended `# AGENTS.md instructions for <path>` repo guidelines (a ~10KB dump), and
 *  - the `# Files mentioned by the user:` @-mention expansion (which leads with a newline).
 * Only `<…>` was filtered before, so the AGENTS.md block surfaced as `# AGENTS.md instructions…` titles and
 * — when a Files-mentioned/empty block was the first user turn, leaving a blank first line — the title
 * collapsed to the raw session UUID. Prefixes are matched after [trimStart] so the newline-led block counts.
 */
internal fun isSyntheticUserText(text: String): Boolean {
    val t = text.trimStart()
    return t.isEmpty() ||
        t.startsWith("<") ||
        t.startsWith("# AGENTS.md instructions") ||
        t.startsWith("# Files mentioned by the user")
}
