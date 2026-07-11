package dev.ccpocket.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

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
    /** ALL live sessions here, executing-first (a dir can host several). The single activeSessionId/Title/
     *  gitBranch above stay populated with the first one so an older app keeps working. */
    val activeSessions: List<ActiveSession> = emptyList(),
    // ---- folder-share (issue #115): set ONLY on a GUEST's project rows (the daemon stamps them when it
    //      returns the shared root to a scoped guest). All trailing optionals — an old daemon omits them
    //      (a guest never sees a shared row), an old app ignores them (renders a plain local row). ----
    /** owner label for a guest's shared folder — the "shared by panda" caption; null = an ordinary local dir. */
    val sharedBy: String? = null,
    /** when this share expires (epoch ms) — drives the "6d left" caption on the guest's shared row. */
    val shareExpiresAt: Long? = null,
    /** the access tier the owner granted this share (Review / Collaborate / Autonomous) — the guest's badge. */
    val shareTier: AccessTier? = null,
)

/** One filesystem child under a session's cwd, for the composer's `@`-file completion ([PathEntries],
 *  issue #75). Name + a directory flag only — the client composes the relative path itself using the
 *  daemon host's separator, so this carries no path and no contents. */
@Serializable
data class PathEntry(val name: String, val isDir: Boolean)

/** One live session in a project dir — [DirectoryEntry.activeSessions]. The daemon knows its own
 *  conversations' turn state exactly; [executing] for a terminal-launched claude falls back to the
 *  wrote-recently heuristic. */
@Serializable
data class ActiveSession(
    val sessionId: String,
    val title: String? = null,
    /** mid-turn right now (authoritative for daemon-driven sessions). */
    val executing: Boolean = false,
    /** has running background work — stays "active" even when the turn is idle. */
    val busy: Boolean = false,
    val gitBranch: String? = null,
    /** which backend owns it — a tap must resume with the right CLI. */
    val agent: AgentKind = AgentKind.CLAUDE,
    /** the external bridge credential that opened it (issue #91), e.g. "feishu-bot" — clients show
     *  "via feishu-bot" on the live row. Null = interactive/local session or an older daemon. */
    val origin: String? = null,
)

/**
 * One bucket in the usage trend (issue #26): a short [label] (e.g. "Mon", or "14:00" for an hourly
 * bucket) and its total tokens. [date] is the bucket's ISO yyyy-MM-dd — a newer daemon fills it so the
 * 30d heatmap can place the day on a weekday grid; null from an older daemon, the client falls back to
 * parsing [label]. A trailing optional field, so old↔new frames still round-trip.
 */
@Serializable
data class UsageDay(val label: String, val tokens: Long, val date: String? = null)

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

/** Why an [AuthBlocker] conversation refuses a credential swap. Decoded tolerantly: a value only a
 *  NEWER daemon knows degrades to [UNKNOWN] instead of failing the whole AuthState decode (which the
 *  runCatching at every decode site would silently drop — a login button that just goes dead). */
@Serializable(with = AuthBlockReasonSerializer::class)
enum class AuthBlockReason(internal val wire: String) {
    /** A user turn is streaming right now. */
    EXECUTING("executing"),

    /** Idle between turns, but background work (bg shells / sub-agents / monitors) is still running —
     *  the agent process holding the old credential must stay alive for it. */
    BACKGROUND_JOBS("background_jobs"),

    /** Decode fallback for a newer peer's value — render a generic "still working" row. Never encoded. */
    UNKNOWN("unknown"),
}

private object AuthBlockReasonSerializer : KSerializer<AuthBlockReason> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AuthBlockReason", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AuthBlockReason) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): AuthBlockReason {
        val s = decoder.decodeString()
        return AuthBlockReason.entries.firstOrNull { it.wire == s } ?: AuthBlockReason.UNKNOWN
    }
}

/** Why a pending ask was retired ([AskWithdrawn.reason], issue #100). Decoded tolerantly like
 *  [AuthBlockReason]: a value only a newer daemon knows degrades to [UNKNOWN] (the phone then just
 *  dismisses the card, the pre-#100 behavior) instead of the runCatching at every decode site dropping
 *  the whole frame and stranding the card. */
@Serializable(with = AskWithdrawnReasonSerializer::class)
enum class AskWithdrawnReason(internal val wire: String) {
    /** The agent moved on / interrupted (claude's control_cancel_request) or the session closed — dismiss
     *  the card with no drama. The DEFAULT, so a pre-#100 daemon's frame (no reason key) reads as this and
     *  every phone keeps today's "just vanish" behavior. */
    WITHDRAWN("withdrawn"),

    /** The approval window elapsed before the user answered — the phone flips the card to its terminal
     *  "timed out / auto-denied" state instead of silently vanishing, so a returning user sees what happened
     *  rather than a card that looks like it went through. */
    TIMED_OUT("timed_out"),

    /** Decode fallback for a newer peer's value — treat like [WITHDRAWN] (dismiss). Never encoded. */
    UNKNOWN("unknown"),
}

private object AskWithdrawnReasonSerializer : KSerializer<AskWithdrawnReason> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AskWithdrawnReason", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AskWithdrawnReason) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): AskWithdrawnReason {
        val s = decoder.decodeString()
        return AskWithdrawnReason.entries.firstOrNull { it.wire == s } ?: AskWithdrawnReason.UNKNOWN
    }
}

/** One conversation blocking an account switch/logout (AuthState.blockers) — enough for the client
 *  to name it, explain why, and offer to stop it (CloseSession force / AuthLogin force). */
@Serializable
data class AuthBlocker(
    val convoId: String,
    val sessionId: String? = null,      // null pre-first-turn (no transcript yet)
    val cwd: String,
    val reason: AuthBlockReason,
    val jobLabels: List<String> = emptyList(), // running background-job labels (BACKGROUND_JOBS only)
)

// ── API presets (issue #113) ──────────────────────────────────────────────

/** The env vars an API preset may set on a session launch — the single source of truth for both the
 *  daemon (injection + scrubbing) and the clients (field labels / the AUTH_TOKEN|API_KEY toggle). */
object PresetEnv {
    const val BASE_URL = "ANTHROPIC_BASE_URL"
    const val AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN"
    const val API_KEY = "ANTHROPIC_API_KEY"
    const val MODEL = "ANTHROPIC_MODEL"
    const val SMALL_FAST_MODEL = "ANTHROPIC_SMALL_FAST_MODEL"
    // scrub-only (a preset never SETS it): a proxy user's daemon service env often carries
    // ANTHROPIC_CUSTOM_HEADERS="Authorization: Bearer <real cred>". Left in place, an active preset
    // would ship that credential to its OWN third-party base URL — a cross-endpoint secret leak.
    const val CUSTOM_HEADERS = "ANTHROPIC_CUSTOM_HEADERS"

    /** Valid [PresetSummary.tokenVar] / token-var choices (a preset's token is one or the other). */
    val TOKEN_VARS: List<String> = listOf(AUTH_TOKEN, API_KEY)

    /** Every var an active-preset launch SCRUBS before applying its own — superset of what a preset
     *  sets, so no stale ambient auth (a leftover key, or [CUSTOM_HEADERS] bound to a different
     *  endpoint) survives into the preset's child process. Single source of truth for the scrub. */
    val SCRUBBED: List<String> = listOf(BASE_URL, AUTH_TOKEN, API_KEY, MODEL, SMALL_FAST_MODEL, CUSTOM_HEADERS)
}

/** A write-only secret riding client → daemon (a preset's API token). Serializes as a plain JSON
 *  string; [toString] redacts so an accidentally logged frame can't spill it. Never sent daemon → client. */
@Serializable
@JvmInline
value class Secret(val value: String) {
    override fun toString(): String = "«redacted»"
}

/**
 * One saved API endpoint preset as the CLIENT sees it — the daemon-side plaintext token is reduced to
 * [tokenMask] before it ever reaches a frame (secrets red line: tokens are write-only, stored on the
 * computer, never sent back). [tokenVar] is one of [PresetEnv.TOKEN_VARS]; kept a plain string (like
 * [AuthState.apiKeySource]) so a future var name degrades to display text instead of failing the decode.
 */
@Serializable
data class PresetSummary(
    val id: String,
    val name: String,
    val baseUrl: String,
    val tokenVar: String = PresetEnv.AUTH_TOKEN,
    /** Short prefix + last 4, middle elided (e.g. `sk-…••••3f9a`) — derived on the daemon, never reversible. */
    val tokenMask: String = "",
    val model: String? = null,          // ANTHROPIC_MODEL override (optional model routing)
    val smallFastModel: String? = null, // ANTHROPIC_SMALL_FAST_MODEL override
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

// ── Workflow orchestration (issue #106) ─────────────────────────────────────────────────────────
// A Workflow tool call fans out to DOZENS of agents grouped into phases — an orchestration-level
// run, not one more sub-agent. The daemon tracks it from the CLI's `system/task_progress` events
// (whose `workflow_progress` array snapshots every agent) and, for finished historical runs, from
// the on-disk manifest `<sessionDir>/workflows/<runId>.json` (written once, at completion —
// probed on CLI 2.1.206).

/**
 * Lifecycle of ONE agent inside a Workflow run. Mapped daemon-side from the CLI's wire states
 * (`start`/`progress`/`done`/`error`): a `start` with no startedAt is QUEUED, a started/progressing
 * agent is RUNNING. Decoded tolerantly — a value only a newer daemon knows degrades to [UNKNOWN]
 * instead of failing the whole [WorkflowUpdate] decode (which the runCatching at every decode site
 * would silently drop — a card that just stops updating).
 */
@Serializable(with = WorkflowAgentStateSerializer::class)
enum class WorkflowAgentState(internal val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),

    /** Decode fallback for a newer peer's value — render as a muted/indeterminate row. Never encoded. */
    UNKNOWN("unknown"),
}

private object WorkflowAgentStateSerializer : KSerializer<WorkflowAgentState> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WorkflowAgentState", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: WorkflowAgentState) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): WorkflowAgentState {
        val s = decoder.decodeString()
        return WorkflowAgentState.entries.firstOrNull { it.wire == s } ?: WorkflowAgentState.UNKNOWN
    }
}

/** Terminal/live status of a whole Workflow run (the CLI task's own states + UNKNOWN fallback). */
@Serializable(with = WorkflowRunStatusSerializer::class)
enum class WorkflowRunStatus(internal val wire: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),

    /** Aborted mid-run (user kill / process death). */
    KILLED("killed"),

    /** Decode fallback for a newer peer's value. Never encoded. */
    UNKNOWN("unknown"),
}

private object WorkflowRunStatusSerializer : KSerializer<WorkflowRunStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WorkflowRunStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: WorkflowRunStatus) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): WorkflowRunStatus {
        val s = decoder.decodeString()
        return WorkflowRunStatus.entries.firstOrNull { it.wire == s } ?: WorkflowRunStatus.UNKNOWN
    }
}

/** One declared phase of a Workflow run (the script's `meta.phases`, seeded by the CLI at launch;
 *  `phase()` calls can add more mid-run). Phase STATUS is derived client-side from its agents. */
@Serializable
data class WorkflowPhaseInfo(val index: Int, val title: String)

/**
 * One agent of a Workflow run — a snapshot row, sized for the progress tree (previews are CLI-capped;
 * the FULL prompt/result are fetched on demand via GetWorkflowAgentDetail when a row is opened).
 */
@Serializable
data class WorkflowAgentSnap(
    val index: Int,                    // 1-based agent() call index — stable identity within the run
    val label: String,
    val state: WorkflowAgentState,
    val phaseIndex: Int? = null,       // groups the row under a phase; null = unphased (client groups as "Phase 0")
    val queuedAt: Long? = null,
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null,         // FAILED: first line renders as the row's danger caption
    val resultPreview: String? = null, // one-line return preview (journal row)
    val promptPreview: String? = null, // collapsed Prompt block seed (detail sheet fetches the full text)
    val lastToolName: String? = null,  // RUNNING: current activity, e.g. the tool it's inside
    val agentId: String? = null,       // key for on-demand detail reads (journal/agent-<id>.jsonl)
    val model: String? = null,
    val cached: Boolean = false,       // resume replayed this result from the journal cache — no new run
)

/**
 * One Workflow run — the orchestration container the phone/desktop render as a chat card +
 * progress tree. Pushed whole on every state transition ([WorkflowUpdate]); agent identity is
 * (runId, index) so clients reconcile in place.
 */
@Serializable
data class WorkflowRun(
    val runId: String,                 // "wf_…" — also the on-disk directory name
    val name: String,                  // meta.name, e.g. "release-pipeline"
    val status: WorkflowRunStatus,
    val toolUseId: String? = null,     // originating Workflow tool_use — correlates the chat card (live)
    val phases: List<WorkflowPhaseInfo> = emptyList(),
    val agents: List<WorkflowAgentSnap> = emptyList(),
    val startedAt: Long = 0,
    val durationMs: Long? = null,      // terminal only
    val finalResult: String? = null,   // terminal: the script's return value (daemon-capped preview)
    val error: String? = null,         // FAILED: the script/run error
)

// ===========================================================================
//  folder-share (issue #115): owner grants a scoped, expiring folder credential to a guest
// ===========================================================================

/**
 * The autonomy tier an owner grants a folder share — the OWNER-facing name for the daemon-enforced
 * permission-mode CEILING a guest session runs under (never [PermissionMode.BYPASS_PERMISSIONS]; a
 * scoped guest can't put the daemon into "approve nothing"). Ordered least→most autonomous.
 *
 * Decoded TOLERANTLY (like [AuthBlockReason]): a tier only a NEWER peer knows degrades to the SAFEST
 * ([REVIEW]) instead of failing the whole frame — a scoped credential must never fall OPEN on a
 * version skew, and the runCatching at every decode site would otherwise silently drop the frame.
 */
@Serializable(with = AccessTierSerializer::class)
enum class AccessTier(internal val wire: String) {
    /** Most cautious: every tool action on the guest's session prompts the guest (mode DEFAULT). */
    REVIEW("review"),

    /** Recommended middle: edits inside the shared folder auto-apply; shell / network / dangerous
     *  actions still prompt the guest (mode ACCEPT_EDITS). */
    COLLABORATE("collaborate"),

    /** Least cautious (owner sees a warning): same ACCEPT_EDITS ceiling — shell is STILL never silent,
     *  because a scoped credential can never reach bypassPermissions. Kept distinct for the owner's
     *  labelling; the mode clamp is identical to [COLLABORATE] in v1. */
    AUTONOMOUS("autonomous"),

    /** Decode fallback for a newer peer's tier — clamped to the safest behaviour. Never encoded. */
    UNKNOWN("unknown");

    companion object {
        /** The permission-mode ceiling this tier clamps a guest session to. Never BYPASS_PERMISSIONS. */
        fun ceiling(tier: AccessTier): PermissionMode = when (tier) {
            REVIEW, UNKNOWN -> PermissionMode.DEFAULT
            COLLABORATE, AUTONOMOUS -> PermissionMode.ACCEPT_EDITS
        }
    }
}

private object AccessTierSerializer : KSerializer<AccessTier> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AccessTier", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AccessTier) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): AccessTier {
        val s = decoder.decodeString()
        return AccessTier.entries.firstOrNull { it.wire == s } ?: AccessTier.UNKNOWN
    }
}

/**
 * A self-contained folder-share invite (issue #115). The owner's daemon mints it and returns it to the
 * owner's app inside a [dev.ccpocket.protocol.ShareCreated]; the app renders it as a QR / copyable blob
 * the owner hands to the guest OUT OF BAND (like today's pairing QR — the relay never sees it, so the
 * shared path never leaks server-side). The guest app decodes it, shows the accept-preview, then redeems
 * [ticket] through the ordinary `/v1/pair/redeem`.
 *
 * Carries only what the guest needs to redeem + preview — [folderName] is the BASENAME for display, never
 * the absolute path (which stays on the owner's machine); the daemon enforces the real root.
 */
@Serializable
data class ShareInvite(
    val relay: String,
    val accountId: String,
    val daemonPub: String,     // owner daemon's X25519 static pub (base64url) — the guest's E2E handshake identity
    val ticket: String,        // single-use pairing ticket; also the first-handshake PSK the daemon binds the scope to
    val folderName: String,    // basename of the shared folder, for the guest's preview + shared row
    val tier: AccessTier,
    val expiresAt: Long,       // share expiry, epoch ms — the guest sees "6d left" and is cut at this instant
    val ttlSec: Int,           // how long the REDEEM ticket itself is valid (short; distinct from the share expiry)
    val ownerLabel: String? = null, // owner's computer name for "shared by <label>"; null = fall back to accountId
)

/**
 * One folder the owner has shared, for the owner's management page ([dev.ccpocket.protocol.ShareListing]).
 * The owner sees WHO is using it and can revoke; approvals still go to the GUEST (this is an activity view,
 * not an approval inbox). [online]/[activeSessions] give the "active now" pulse; [lastActiveAt] the "idle" state.
 */
@Serializable
data class ShareInfo(
    val deviceId: String,       // the guest credential id — the revoke handle
    val path: String,           // the shared folder (owner sees the real path — it is theirs)
    val tier: AccessTier,
    val createdAt: Long,
    val expiresAt: Long,
    val guestLabel: String? = null,   // a nickname the owner set for this guest; null = "someone"
    val lastActiveAt: Long? = null,    // newest activity under this share; null = never used yet
    val online: Boolean = false,       // a guest session is live right now (the "active now" pulse dot)
    val activeSessions: Int = 0,        // how many live guest sessions under this share
    val revoked: Boolean = false,       // history rows (issue #115 "Share Endings" — Revoked/Expired)
    val expired: Boolean = false,
)
