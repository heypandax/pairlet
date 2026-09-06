package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Lenient JSON accessors for the DeepSeek Harness wire + session-file schemas (issue #255). One home
// shared by the backend, API client, transcript reader and scanner (mirrors kimi/KimiJson.kt).
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { if (it.isString) null else it.content.toBooleanStrictOrNull() }
