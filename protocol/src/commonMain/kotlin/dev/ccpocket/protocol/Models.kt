package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The six real permission modes from `claude --help`. Serialized names match the CLI flag
 * values exactly so the daemon can pass them straight to `--permission-mode <name>`.
 */
@Serializable
enum class PermissionMode {
    @SerialName("acceptEdits") ACCEPT_EDITS,
    @SerialName("auto") AUTO,
    @SerialName("bypassPermissions") BYPASS_PERMISSIONS,
    @SerialName("default") DEFAULT,
    @SerialName("dontAsk") DONT_ASK,
    @SerialName("plan") PLAN,
}

/** Outcome of a remote permission prompt. Maps to control_response behavior allow|deny. */
@Serializable
enum class Decision {
    @SerialName("allow") ALLOW,
    @SerialName("deny") DENY,
}

/** One assistant content piece (closed set for M0: text | thinking). tool_use is a [ToolEvent]. */
@Serializable
sealed interface StreamPiece {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : StreamPiece

    @Serializable
    @SerialName("thinking")
    data class Thinking(val text: String) : StreamPiece
}

/** Lifecycle phase of a surfaced tool invocation. */
@Serializable
enum class ToolPhase {
    @SerialName("start") START,
    @SerialName("result") RESULT,
}

/**
 * From `result.usage`. Field names are ours (camelCase); the daemon's StreamParser maps the
 * snake_case Anthropic keys. Cache fields are present on many real results, optional here.
 */
@Serializable
data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
)

/**
 * Built by the daemon's TranscriptScanner from `~/.claude/projects/<key>/<sid>.jsonl`.
 * `title` prefers the `ai-title` record, else a truncated [firstPrompt]; `messageCount`
 * counts real user turns only (excludes tool-result turns).
 */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val firstPrompt: String,
    val messageCount: Int,
    val cwd: String,
    val lastModified: Long,
    val gitBranch: String? = null,
    val version: String? = null,
)

/** One filesystem entry returned by the daemon's DirectoryService. */
@Serializable
data class DirectoryEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    /** true if resumable Claude history exists for this dir. */
    val hasSessions: Boolean = false,
    /** true if in the recents list. */
    val recent: Boolean = false,
)
