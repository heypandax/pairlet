package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Translates Kimi Code CLI ACP (Agent Client Protocol v1) `session/update` notifications → provider-neutral
 * [AgentEvent] (issue #206). This decodes the STATELESS update kinds (text/thought chunks) for the live
 * backend.
 *
 * ACP `session/update` params: `{sessionId, update:{sessionUpdate:<kind>, …}}` (probe-verified on 0.34.0):
 *  - `agent_message_chunk` / `agent_thought_chunk`  → assistant text / thinking (ContentBlock `content`)
 *  - `user_message_chunk`                            → UserReplay receipt (prompt consumed)
 *  - `plan` / `available_commands_update` / `session_info_update` / `usage_update` → Ignored (P2)
 *  - `tool_call` / `tool_call_update`                → NOT HERE — they need per-call state (the input JSON
 *    streams as cumulative text; `tool_call` carries no `rawInput`), so [KimiBackend] accumulates and
 *    emits tool events itself.
 *
 * The on-disk transcript (`wire.jsonl`) is the CLI's INTERNAL wire format, NOT ACP (probe 0.34.0 — the
 * pre-auth design assumed otherwise and replay produced zero rows); disk parsing lives in
 * [KimiTranscriptReplay].
 *
 * Contract: [translate]/[parseLine] NEVER throw. Unknown update kinds degrade to [AgentEvent.Ignored].
 */
object KimiAcpParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One ACP `session/update` notification line → events. Accepts a full notification or a bare `update`
     *  object. (The DISK transcript is NOT ACP — see [KimiTranscriptReplay]; this is the live-path helper.) */
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

            "plan" -> listOf(AgentEvent.Ignored("plan")) // P2: render the plan
            else -> listOf(AgentEvent.Ignored(update.str("sessionUpdate")))
        }
    }
}
