package dev.ccpocket.daemon.zcode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Projects ZCode's persisted user rows onto the human conversation timeline.
 *
 * ZCode 3.7.6 deliberately stores compact summaries and post-compact reminders as `role=user` because
 * they remain provider context. They are not user input: the official store marks them synthetic and/or
 * `semantics.origin=agent_runtime`, `kind=compact_summary`, `transcriptVisibility=hidden`. Older summary
 * rows may have only the top-level `summary` marker. Prefer those structural facts over inspecting the
 * text — a real user is still free to paste XML or discuss summaries verbatim (#313).
 */
internal object ZCodeTranscriptProjection {
    fun isVisibleUserRow(message: JsonObject): Boolean {
        if (message["summary"] != null && message["summary"] !is JsonNull) return false
        if (message.bool("synthetic") || message.str("visibility") == "model-only") return false

        val semantics = message["semantics"] as? JsonObject ?: return true // legacy real-user row
        if (semantics.str("kind") == "compact_summary") return false
        if (semantics.str("uiVisibility") == "hidden" || semantics.str("transcriptVisibility") == "hidden") {
            return false
        }
        return semantics.str("origin") == "real_user"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = str(key)?.toBooleanStrictOrNull() == true
}
