package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The autonomy ladder — the four permission modes claude's `--permission-mode` actually accepts,
 * ordered most→least cautious. Serialized names match the CLI flag values exactly.
 * (The earlier `auto`/`dontAsk` were app-invented and not valid CLI values, so they were dropped.)
 */
@Serializable
enum class PermissionMode {
    @SerialName("default") DEFAULT,
    @SerialName("acceptEdits") ACCEPT_EDITS,
    @SerialName("plan") PLAN,
    @SerialName("bypassPermissions") BYPASS_PERMISSIONS,
}

/** Outcome of a remote permission prompt. Maps to control_response behavior allow|deny. */
@Serializable
enum class Decision {
    @SerialName("allow") ALLOW,
    @SerialName("deny") DENY,
}

/**
 * Which agent CLI backs a session. Serialized in [OpenSession]/[SessionLive]/[SessionSummary];
 * the default CLAUDE is what an older peer (App or daemon) implies when it omits the field, so adding
 * this stays wire-backward-compatible.
 */
@Serializable
enum class AgentKind {
    @SerialName("claude") CLAUDE,
    @SerialName("codex") CODEX,
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
) {
    /**
     * Tokens occupying the model's context window after this turn: the fresh prompt plus the cached
     * prefix still in the window (output isn't in-window yet). The single definition of "context
     * occupancy" — the daemon seeds it on resume, the phone shows it live, both read it from here.
     * Computed (no backing field) so it never crosses the wire.
     */
    val contextTokens: Long get() = inputTokens + (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0)
}

/**
 * Built by the daemon's TranscriptScanner from `~/.claude/projects/<key>/<sid>.jsonl`.
 * `title` prefers the user's `custom-title` rename, else the `ai-title` record, else a truncated
 * [firstPrompt]; `messageCount` counts real user turns only (excludes tool-result turns).
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
    val live: Boolean = false, // transcript written very recently — a session running right now
    val busy: Boolean = false, // has running background work (bg bash / subagent / monitor) — keep it "active" even when idle
    val agent: AgentKind? = null, // which backend owns this transcript (null = older daemon → phone assumes Claude)
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
    /** newest transcript mtime under this dir — used to sort projects newest-first. */
    val lastModified: Long = 0,
    /** a claude process is alive in this dir (open session — may be idle, waiting for input). */
    val open: Boolean = false,
    /** actively executing right now: a process here that wrote output very recently. */
    val executing: Boolean = false,
    /** the live session here has running background work — keep it "active" even when the turn is idle. */
    val busy: Boolean = false,
    /** for open/executing dirs: the live session to jump straight into (tap resumes it directly). */
    val activeSessionId: String? = null,
    val activeSessionTitle: String? = null,
    /** git branch of the active session, shown inline on the live row. */
    val gitBranch: String? = null,
)

/** One day in the usage trend (issue #26): a short [label] (e.g. "Mon") and its total tokens. */
@Serializable
data class UsageDay(val label: String, val tokens: Long)

/** One model's slice of usage: the [model] id, its [tokens], and which [agent] it belongs to (for the color). */
@Serializable
data class UsageModel(val model: String, val tokens: Long, val agent: AgentKind = AgentKind.CLAUDE)

/** What kind of background work a [BackgroundJob] is. */
@Serializable
enum class JobKind {
    @SerialName("bash") BASH_BACKGROUND, // a Bash tool call with run_in_background=true
    @SerialName("subagent") SUBAGENT,    // a Task tool call (sub-agent)
    @SerialName("monitor") MONITOR,      // a Monitor tool call (polls until a condition)
}

/** Lifecycle of a [BackgroundJob]. RUNNING jobs keep their session "active" (see [DirectoryEntry.busy]). */
@Serializable
enum class JobStatus {
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    @SerialName("killed") KILLED,
}

/**
 * One background job tracked by the daemon for a conversation: a backgrounded shell, a sub-agent,
 * or a monitor. [id] is the originating tool_use id; [label] is a human summary (command / desc).
 */
@Serializable
data class BackgroundJob(
    val id: String,
    val kind: JobKind,
    val label: String,
    val status: JobStatus,
    val startedAt: Long,
    val lastUpdate: Long,
)
