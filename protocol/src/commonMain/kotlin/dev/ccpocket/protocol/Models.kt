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
     * Tokens occupying the model's context window after this turn: the prompt the model saw (fresh
     * input + the cached prefix) PLUS the reply it just wrote — that reply is in the conversation
     * and is prompt-side from the next turn on, so omitting it under-reports right after a long
     * response. The single definition of "context occupancy" — the daemon seeds it on resume, the
     * phone shows it live, both read it from here. Computed (no backing field) so it never crosses
     * the wire.
     */
    val contextTokens: Long get() = inputTokens + outputTokens + (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0)
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
    val model: String? = null, // the LAST assistant turn's model id (null = older daemon / no turn yet) — list rows show its alias
)

/**
 * One file a session created/edited, as recorded in its transcript (see ListSessionFiles).
 * [op] is the LAST operation seen: "write" | "edit" | "delete" | "notebook"; [edits] counts how
 * many tool calls touched the path. Ordered newest-touched first by the daemon.
 * [adds]/[dels] total the +/− lines across the session's ops on this path (Claude structuredPatch /
 * Codex patch envelopes); null when the transcript carries no line-level data for it — the client
 * shows the counts only when present, so frames from an older daemon still decode as "no stats".
 */
@Serializable
data class ChangedFile(
    val path: String,
    val op: String = "edit",
    val edits: Int = 1,
    val adds: Int? = null,
    val dels: Int? = null,
)

/** Extensions both peers treat as images: the daemon serves them as base64 [FileContent] (no text
 *  diff exists), and the clients disable the Diff tab / skip the [ReadFileDiff] request for them.
 *  One set so the two sides can't drift. */
val IMAGE_FILE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

fun isImageFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_FILE_EXTENSIONS

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

/** One option of an [AskQuestion]: a short label (what gets sent back as the answer) + a one-line description. */
@Serializable
data class AskOption(val label: String, val description: String? = null)

/**
 * One question of an AskUserQuestion call (Claude asking the user, tool schema: 1–4 questions,
 * 2–4 options each). [header] is a short chip label (≤12 chars). The phone renders a question card
 * and answers with [PermissionVerdict.answers] keyed by the exact [question] text.
 */
@Serializable
data class AskQuestion(
    val question: String,
    val header: String? = null,
    val multiSelect: Boolean = false,
    val options: List<AskOption> = emptyList(),
)
