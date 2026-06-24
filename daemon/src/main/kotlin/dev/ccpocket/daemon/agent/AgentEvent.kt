package dev.ccpocket.daemon.agent

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

    data class AssistantText(val text: String) : AgentEvent
    data class AssistantThinking(val text: String) : AgentEvent
    data class AssistantToolUse(val id: String?, val name: String, val input: JsonObject?) : AgentEvent

    /** a tool/command result — carries the originating tool_use id + (text) content. */
    data class ToolResult(val toolUseId: String?, val content: String?, val isError: Boolean) : AgentEvent

    /** a background task (e.g. a backgrounded shell) began; links task_id to its tool_use. */
    data class BackgroundTaskStarted(val taskId: String, val toolUseId: String?, val description: String?, val taskType: String?) : AgentEvent

    /** a background task changed state (status: completed/failed/…). */
    data class BackgroundTaskUpdated(val taskId: String, val status: String?) : AgentEvent

    /** replayed user turn (Claude --replay-user-messages). */
    data object UserReplay : AgentEvent

    data class TurnResult(
        val finalText: String?,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheCreationInputTokens: Long?,
        val cacheReadInputTokens: Long?,
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
