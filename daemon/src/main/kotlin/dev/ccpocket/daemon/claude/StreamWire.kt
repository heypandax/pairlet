package dev.ccpocket.daemon.claude

import kotlinx.serialization.json.JsonObject

/**
 * Domain events parsed from claude's stdout. This (+ StreamParser + PermissionBridge) is the ONLY
 * place that understands Anthropic's stream-json schema — everything downstream is provider-agnostic.
 */
sealed interface ClaudeEvent {
    /** system / subtype=init — the authoritative session_id carrier. */
    data class SessionInit(val sessionId: String?, val cwd: String?, val model: String?) : ClaudeEvent

    data class AssistantText(val text: String) : ClaudeEvent
    data class AssistantThinking(val text: String) : ClaudeEvent
    data class AssistantToolUse(val id: String?, val name: String, val input: JsonObject?) : ClaudeEvent

    /** replayed user turn (from --replay-user-messages). */
    data object UserReplay : ClaudeEvent

    data class TurnResult(
        val finalText: String?,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheCreationInputTokens: Long?,
        val cacheReadInputTokens: Long?,
        val isError: Boolean,
    ) : ClaudeEvent

    data class ControlRequest(val requestId: String, val toolName: String, val input: JsonObject?) : ClaudeEvent
    data class ControlCancel(val requestId: String) : ClaudeEvent

    /** a known-but-uninteresting line (hook_*, rate_limit_event, ...). */
    data class Ignored(val type: String?) : ClaudeEvent

    /** a line we couldn't parse — never throw out of the parser. */
    data class Unparseable(val raw: String) : ClaudeEvent
}
