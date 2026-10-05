package dev.ccpocket.daemon.acp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The frames the daemon feeds into its OWN parse pump through [dev.ccpocket.daemon.agent.AgentIo.inject] —
 * the only lines on the pump the agent did not write. Each type is namespaced per backend ([tag]) so it can
 * never collide with a real agent frame.
 *
 *  - [refusal]: settles the prompt reserved as `id` with an error turn — a prompt refused before it was
 *    written, or one stranded by a startup failure. Only the pump may return events, so callers off the
 *    pump inject this instead.
 *  - [error]: a failure with no prompt to settle (an error text plus an error TurnResult).
 *  - [notice]: a message with no verdict about the turn — the turn itself is untouched.
 */
class AcpSynthetic(tag: String) {
    val refusalType = "cc-pocket/$tag-prompt-refused"
    val errorType = "cc-pocket/$tag-error"
    val noticeType = "cc-pocket/$tag-notice"

    fun refusal(id: Long, message: String): String =
        buildJsonObject { put("type", refusalType); put("id", id); put("message", message) }.toString()

    fun error(message: String): String =
        buildJsonObject { put("type", errorType); put("message", message) }.toString()

    fun notice(message: String): String =
        buildJsonObject { put("type", noticeType); put("message", message) }.toString()
}
