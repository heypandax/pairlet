package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.TokenUsage
import kotlinx.serialization.json.JsonObject

/**
 * Provider-neutral domain events. Each [AgentBackend] (Claude stream-json, Codex app-server JSON-RPC)
 * translates its own wire schema INTO these — everything downstream (Conversation, the phone protocol)
 * is agent-agnostic. A permission ask is a [ControlRequest] regardless of backend: Claude's
 * `can_use_tool` and Codex's `item/.../requestApproval` both land here with an opaque `requestId`.
 */
sealed interface AgentEvent {
    /** The authoritative session_id carrier (Claude system/init; Codex thread/started → thread.id). */
    data class SessionInit(val sessionId: String?, val cwd: String?, val model: String?) : AgentEvent

    /** [parentId] on the assistant/tool events = the enclosing sub-agent's Task/Agent tool_use id
     *  (Claude stream-json `parent_tool_use_id`); null for main-chain events and for Codex. */
    data class AssistantText(val text: String, val parentId: String? = null) : AgentEvent
    data class AssistantThinking(val text: String, val parentId: String? = null) : AgentEvent
    data class AssistantToolUse(val id: String?, val name: String, val input: JsonObject?, val parentId: String? = null) : AgentEvent

    /** An assistant record whose model is the `<synthetic>` placeholder: the CLI wrote it AFTER every
     *  API call of the turn failed ("No response requested." etc). It is not a real reply — the
     *  Conversation surfaces it as a turn error instead of a normal chunk (issue #65). */
    data class SyntheticReply(val text: String) : AgentEvent

    /** The context-bearing usage of ONE assistant API call (`message.usage`). The turn's `result` event
     *  SUMS these across every call in the turn — a 2-tool-batch turn reported ~2× the real window
     *  footprint and the phone statusline read 88% on a 44% session — so occupancy must come from the
     *  LAST call, never the result total (the same last-vs-total rule the Codex backend applies). */
    data class AssistantUsage(
        val inputTokens: Long,
        val cacheCreationInputTokens: Long?,
        val cacheReadInputTokens: Long?,
    ) : AgentEvent

    /** a tool/command result — carries the originating tool_use id + (text) content.
     *  [parentId] set = this result belongs to a tool INSIDE a sub-agent, not the main chain. */
    data class ToolResult(val toolUseId: String?, val content: String?, val isError: Boolean, val parentId: String? = null) : AgentEvent

    /** a background task (e.g. a backgrounded shell) began; links task_id to its tool_use. */
    data class BackgroundTaskStarted(val taskId: String, val toolUseId: String?, val description: String?, val taskType: String?) : AgentEvent

    /** a background task changed state (status: completed/failed/…). `task_notification` also carries
     *  [toolUseId] + [summary] — for a backgrounded sub-agent that pair is the authoritative completion
     *  (its tool_result was only the launch ack), so the Task card's outcome comes from here. */
    data class BackgroundTaskUpdated(val taskId: String, val status: String?, val toolUseId: String? = null, val summary: String? = null) : AgentEvent

    /** replayed user turn (Claude --replay-user-messages). */
    data object UserReplay : AgentEvent

    /** Turn finished. [usage] carries the backend result's own numbers — null when the result had NO
     *  usage (interrupted turn, some error exits): absence is a null, never placeholder zeros a consumer
     *  could mistake for an empty window. NOTE: Claude's result SUMS input/cache across the turn's API
     *  calls — Conversation prefers the last [AssistantUsage] for occupancy (last-vs-total). */
    data class TurnResult(
        val finalText: String?,
        val usage: TokenUsage?,
        val isError: Boolean,
    ) : AgentEvent

    /** A permission ask the daemon must resolve. `requestId` is the opaque token the backend's
     *  [AgentBackend.respondPermission] writes the decision back against (Claude request_id / Codex JSON-RPC id).
     *  `diff` is a unified-diff to show for a file-change approval (Codex patches), when the backend has one. */
    data class ControlRequest(val requestId: String, val toolName: String, val input: JsonObject?, val diff: String? = null) : AgentEvent
    data class ControlCancel(val requestId: String) : AgentEvent

    /** a known-but-uninteresting line (hook_*, rate_limit_event, serverRequest/resolved, ...). */
    data class Ignored(val type: String?) : AgentEvent

    /** a line we couldn't parse — never throw out of the parser. */
    data class Unparseable(val raw: String) : AgentEvent
}
