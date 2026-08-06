package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.opencode.ToolNameMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Translates Kimi Code CLI ACP (Agent Client Protocol v1) `session/update` notifications → provider-neutral
 * [AgentEvent] (issue #206). This is the ONE place the ACP update schema is decoded; it is shared by the live
 * backend and — best-effort — by the offline transcript replay (see [KimiTranscriptReplay]).
 *
 * ACP `session/update` params: `{sessionId, update:{sessionUpdate:<kind>, …}}`. The `sessionUpdate`
 * discriminator drives the mapping (per the ACP spec + the confirmed kimi 0.33.0 handshake):
 *  - `agent_message_chunk` / `agent_thought_chunk`  → assistant text / thinking (ContentBlock `content`)
 *  - `user_message_chunk`                            → UserReplay receipt (prompt consumed)
 *  - `tool_call`                                     → AssistantToolUse (title/kind/rawInput)
 *  - `tool_call_update`                              → ToolResult when it carries content/output (status settled)
 *  - `plan`                                          → Ignored (P2: render plan)
 *
 * Contract: [translate]/[parseLine] NEVER throw. Unknown update kinds degrade to [AgentEvent.Ignored].
 */
object KimiAcpParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One persisted transcript line → events (offline replay path — disk format unverified pre-auth,
     *  fails safe to empty/Ignored). Accepts a full `session/update` notification or a bare `update` object. */
    fun parseLine(line: String): List<AgentEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
            ?: return listOf(AgentEvent.Unparseable(t))
        return runCatching {
            val update = when {
                root.str("method") == "session/update" -> root.obj("params")?.obj("update")
                root.containsKey("update") -> root.obj("update")
                root.containsKey("sessionUpdate") -> root // bare update object
                else -> null
            }
            update?.let { translate(it) } ?: emptyList()
        }.getOrElse { listOf(AgentEvent.Unparseable(t)) }
    }

    /** One ACP `update` object → domain events. */
    fun translate(update: JsonObject): List<AgentEvent> {
        return when (update.str("sessionUpdate")) {
            "agent_message_chunk" ->
                contentBlockText(update["content"])?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(AgentEvent.AssistantText(it)) } ?: emptyList()
            "agent_thought_chunk" ->
                contentBlockText(update["content"])?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(AgentEvent.AssistantThinking(it)) } ?: emptyList()
            "user_message_chunk" ->
                listOf(AgentEvent.UserReplay(contentBlockText(update["content"])))

            "tool_call" -> {
                val id = update.str("toolCallId")
                // ACP tool_call carries a human `title` + a `kind` (read/edit/execute/…) + `rawInput`.
                // Prefer the kind for a Claude-shaped tool name (execute→Bash via the mapper), else the title.
                val name = ToolNameMapper.map(update.str("kind") ?: update.str("title") ?: "tool")
                val input = update.obj("rawInput")
                listOf(AgentEvent.AssistantToolUse(id, name, input))
            }
            "tool_call_update" -> {
                val id = update.str("toolCallId")
                val status = update.str("status")
                // content is an array of ToolCallContent; rawOutput is the structured result. Surface a
                // ToolResult only when the call has produced output (settled), mirroring the other backends.
                val text = contentBlockText(update.obj("content")?.get("content"))
                    ?: contentBlockText(update["content"])
                    ?: update.obj("rawOutput")?.toString()
                if (text != null || status == "completed" || status == "failed") {
                    listOf(AgentEvent.ToolResult(id, text, isError = status == "failed"))
                } else emptyList()
            }

            "plan" -> listOf(AgentEvent.Ignored("plan")) // P2: render the plan
            else -> listOf(AgentEvent.Ignored(update.str("sessionUpdate")))
        }
    }
}
