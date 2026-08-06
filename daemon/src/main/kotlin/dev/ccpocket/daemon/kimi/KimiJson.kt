package dev.ccpocket.daemon.kimi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Lenient JSON accessors for the Kimi Code CLI ACP (Agent Client Protocol v1) stdio schema (issue #206).
// One home shared by the backend, ACP parser, scanner and replay (mirrors codex/CodexJson.kt).
//
// SELECTION NOTE (probe 2026-08-06): the design assumed a `kimi --wire` mode, but the shipped CLI (0.33.0)
// has NO `--wire` flag. Its machine interfaces are `kimi acp` (a complete ACP v1 stdio server — confirmed
// by the initialize handshake: loadSession/resume/fork/close/delete/list + permission requests) and
// `kimi -p --output-format stream-json` (one-shot). ACP is the full-fidelity interface, so the backend
// speaks ACP. See docs/design/kimi-backend-design.md §9 probe results.
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Text of an ACP ContentBlock (`{type:"text",text:…}`) or a list of them joined. Other block kinds
 *  (image/audio/resource) yield null/skip in P1. */
internal fun contentBlockText(el: JsonElement?): String? = when (el) {
    is JsonPrimitive -> el.contentOrNull
    is JsonObject -> if (el.str("type") == "text") el.str("text") else null
    is JsonArray -> el.mapNotNull { contentBlockText(it) }.joinToString("").takeIf { it.isNotEmpty() }
    else -> null
}
