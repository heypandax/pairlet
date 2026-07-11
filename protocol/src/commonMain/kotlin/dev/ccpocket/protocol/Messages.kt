package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ===========================================================================
//  phone  ->  daemon   (ToDaemon)
// ===========================================================================

/** List browsable directories: recents + children of an optional [root] (null => recents + defaults). */
@Serializable
@SerialName("pocket/dirs.list")
data class ListDirectories(val root: String? = null) : ToDaemon

/** List resumable sessions for a working directory (reads .jsonl headers; no claude launch). */
@Serializable
@SerialName("pocket/sessions.list")
data class ListSessions(val workdir: String) : ToDaemon

/** Fetch aggregated token usage over the last [days] local days (reads transcripts; no launch). Issue #26. */
@Serializable
@SerialName("pocket/usage.fetch")
data class FetchUsage(val days: Int = 7) : ToDaemon

/** Open a session: resume (resumeId != null) or start new (resumeId == null). */
@Serializable
@SerialName("pocket/session.open")
data class OpenSession(
    val workdir: String,
    val resumeId: String? = null,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val effort: String? = null, // reasoning effort to relaunch under; restores the session's last setting on reopen
    val takeOver: Boolean = false, // true = resume/control even a session live in a terminal (vs observe)
    val agent: AgentKind = AgentKind.CLAUDE, // which backend to drive; default keeps older Apps (no field) on Claude
) : ToDaemon

/** Restart the live conversation's claude process under a new cwd. */
@Serializable
@SerialName("pocket/session.switchDir")
data class SwitchDirectory(val convoId: String, val workdir: String) : ToDaemon

/** Send a user turn into a live conversation. [promptId] is a client-minted id the daemon echoes back
 *  as a [PromptAck] once the turn is handed to the agent — the delivery receipt (issue #66). It also
 *  dedupes retries: a resend with the same id is acked again but not double-delivered. Null from older
 *  clients → no ack (an old daemon ignores the unknown field the same way). */
@Serializable
@SerialName("pocket/prompt")
data class SendPrompt(
    val convoId: String,
    val text: String,
    val images: List<ImageData> = emptyList(),
    val promptId: String? = null,
) : ToDaemon

/** A base64 image attached to a prompt — downscaled on the phone to fit the relay frame cap. */
@Serializable
data class ImageData(val mediaType: String, val base64: String)

/** Resolve a pending permission prompt. askId == the Anthropic request_id (1:1). */
@Serializable
@SerialName("pocket/verdict")
data class PermissionVerdict(
    val convoId: String,
    val askId: String,
    val decision: Decision,
    val updatedInput: String? = null,
    val message: String? = null,
    val remember: Boolean = false, // ALLOW + remember => add an allow-rule so future matches auto-allow this session
    // AskUserQuestion only (the ask carried questions): the user's picks, keyed by the EXACT question text;
    // value = the chosen option label, comma-joined labels for multiSelect, or free "Other…" text.
    val answers: Map<String, String>? = null,
    // AskUserQuestion only: a freeform reply INSTEAD of answering the structured questions
    // (claude renders it as "The user responded: …").
    val response: String? = null,
) : ToDaemon

/** Switch the live conversation's permission mode (relaunches claude with --resume + the new mode). */
@Serializable
@SerialName("pocket/mode.switch")
data class SwitchMode(val convoId: String, val mode: PermissionMode) : ToDaemon

/** Drop a session allow-rule (rule == null clears them all) so it prompts again next time. */
@Serializable
@SerialName("pocket/rule.clear")
data class ClearAllowRule(val convoId: String, val rule: String? = null) : ToDaemon

/** Interrupt the current turn. */
@Serializable
@SerialName("pocket/turn.cancel")
data class CancelTurn(val convoId: String) : ToDaemon

/** Stop one background job (a backgrounded shell / sub-agent / monitor) from the phone's task panel
 *  (issue #80). [jobId] is the job's originating tool_use id — the [BackgroundJob.id] the daemon put on
 *  the wire. The daemon interrupts the agent's in-flight work for this conversation and marks that job
 *  killed. A brand-new message type: an old daemon can't decode it and drops the frame (the same
 *  runCatching-at-decode path every other newer message relies on), so the stop just no-ops there. */
@Serializable
@SerialName("pocket/job.stop")
data class StopBackgroundJob(val convoId: String, val jobId: String) : ToDaemon

/** Tear down a live conversation (clean kill of the process group). Without [force] a busy
 *  conversation (turn in flight / background jobs / unanswered ask) survives — the sender only
 *  detaches its own view (issue #55). [force] = the user explicitly chose to stop it (e.g. an
 *  account-switch blocker row): kill it busy or not. An old daemon ignores the flag — detach-only. */
@Serializable
@SerialName("pocket/session.close")
data class CloseSession(val convoId: String, val force: Boolean = false) : ToDaemon

/** One chunk of a voice capture. Chunks of a recording share [captureId]; daemon reassembles by [idx]. */
@Serializable
@SerialName("pocket/audio.chunk")
data class AudioChunk(
    val convoId: String,
    val captureId: String,   // phone-generated, one per recording (fresh per retry)
    val idx: Int,            // 0-based
    val last: Boolean,       // true on the final chunk -> transcription starts once 0..idx are all present
    val mediaType: String,   // "audio/mp4" (AAC m4a) | "audio/wav" (desktop PCM)
    val base64: String,
) : ToDaemon

/** Abandon a capture (user cancelled mid-upload); the daemon drops buffered chunks + any running job. */
@Serializable
@SerialName("pocket/audio.cancel")
data class AudioCancel(val convoId: String, val captureId: String) : ToDaemon

/**
 * One chunk of a regular file upload (issue #90). Unlike an inline [ImageData] (base64 folded into the
 * model message, never written to disk) a picked file — PDF/CSV/code/Office/… — is chunk-streamed to the
 * paired computer and LANDED in the session's workspace so the agent can Read it BY PATH. Chunks of one
 * file share [captureId]; the daemon reassembles by [idx] and writes the bytes to
 * `<cwd>/.ccpocket/inbox/<captureId>/<name>`.
 *
 * Shape mirrors [AudioChunk] and adds two fields: [name] rides on EVERY chunk so reassembly stays
 * stateless, and [totalBytes] (set on idx 0, 0 = unknown) lets the daemon reject an over-cap upload
 * before it buffers a single byte. A daemon that predates this frame can't decode the unknown "t" and
 * drops it (the runCatching-at-decode path every newer message relies on) — so a new phone MUST arm an
 * upload timeout and surface an "update the computer's cc-pocket" state rather than hang forever.
 */
@Serializable
@SerialName("pocket/file.chunk")
data class FileChunk(
    val convoId: String,
    val captureId: String,   // phone-generated, one per file (fresh per retry)
    val idx: Int,            // 0-based
    val last: Boolean,       // true on the final chunk -> landing happens once 0..idx are all present
    val name: String,        // the picked file's display name; the daemon sanitizes it to a safe basename
    val mediaType: String,   // best-effort MIME ("application/pdf" | "text/csv" | "application/octet-stream" | …)
    val base64: String,
    val totalBytes: Long = 0, // full file size, carried on idx 0 (0 = unknown) -> early over-cap rejection
) : ToDaemon

/** Abandon a file upload (user cancelled / retrying); the daemon drops buffered chunks + the partial
 *  file on disk. Mirrors [AudioCancel]. A daemon that predates this drops the frame — the stale partial
 *  is reaped by the inbox's own expiry sweep instead. */
@Serializable
@SerialName("pocket/file.cancel")
data class FileUploadCancel(val convoId: String, val captureId: String) : ToDaemon

/**
 * phone -> daemon: run a one-off shell command in [workdir] (the active session's cwd) to check the
 * environment from afar (e.g. `git status`, `node -v`). The daemon gates it through the same approval
 * UI as the Bash tool — auto-run only in bypass mode or for a remembered rule; dangerous commands always
 * prompt. The reply is a single [ShellResult] (not streamed). A daemon that predates this drops it.
 */
@Serializable
@SerialName("pocket/shell.run")
data class RunShellCommand(
    val convoId: String,
    val command: String,
    val workdir: String,
    val timeoutMs: Long = 30_000,
) : ToDaemon

/**
 * phone -> daemon: list the files this session created/edited, extracted from the session's own
 * transcript (Claude tool_use inputs / Codex patch envelopes) — so it works for historical sessions
 * too, not just the live one. Keyed on the persistent (workdir, sessionId) identity like
 * [ListSessions]. The reply is one [SessionFiles]. A daemon that predates this drops it.
 */
@Serializable
@SerialName("pocket/files.list")
data class ListSessionFiles(
    val workdir: String,
    val sessionId: String,
    val agent: AgentKind = AgentKind.CLAUDE,
) : ToDaemon

/**
 * phone -> daemon: read one file the session touched. The daemon re-derives the session's
 * changed-file set from the transcript and ONLY serves paths in it — the phone can already see
 * these files' contents through the transcript/diffs, so this adds no new read surface (unlike an
 * arbitrary-path read, which would bypass the approval firewall). Reply is one [FileContent].
 */
@Serializable
@SerialName("pocket/file.read")
data class ReadFile(
    val workdir: String,
    val sessionId: String,
    val path: String,
    val agent: AgentKind = AgentKind.CLAUDE,
) : ToDaemon

/**
 * phone -> daemon: export ONE file the session did NOT change out to the phone (issue #67 v2 / #79 — the
 * Bash/script-generated documents and read-only files [ReadFile]'s changed-set gate leaves unreachable).
 * This deliberately widens past [ReadFile], so it is gated to keep the "no arbitrary-path read" red line:
 *   - if [path] IS in the session's changed set, it is served straight away (already reachable via [ReadFile]);
 *   - otherwise the daemon requires the file to sit canonically INSIDE [workdir] (a `..`/symlink escape is
 *     refused with a readable reason, never served) AND raises an owner [PermissionAsk] ("Export {path} to
 *     phone?") — the SAME approval firewall the Bash quick-terminal uses. The owner can "Allow once" or
 *     remember the workspace; an unanswered ask times out to deny. The reply is one [FileContent] (served,
 *     or ok=false carrying why it was refused/denied). [convoId] scopes the approval + routes the ask to the
 *     live conversation, exactly like [RunShellCommand]. A daemon that predates this drops the frame — the
 *     client times out to its "update the computer" state.
 */
@Serializable
@SerialName("pocket/file.export")
data class ExportFile(
    val convoId: String,
    val workdir: String,
    val sessionId: String,
    val path: String,
    val agent: AgentKind = AgentKind.CLAUDE,
) : ToDaemon

/**
 * phone -> daemon: list the immediate children (files + subdirs) of [subPath] under [workdir], for the
 * composer's `@`-file completion (issue #75). [subPath] is a path RELATIVE to the session's cwd
 * ([workdir]) using the daemon host's separator (empty = the cwd itself); the daemon resolves it inside
 * [workdir] and refuses anything that escapes the tree. Names only — no contents — so it is a strictly
 * smaller read surface than [ReadFile], scoped to the session's own project directory. The reply is one
 * [PathEntries], capped at [limit] entries. A daemon that predates this drops it (the completer just
 * shows nothing).
 */
@Serializable
@SerialName("pocket/path.list")
data class ListPathEntries(
    val workdir: String,
    val subPath: String = "",
    val limit: Int = 500,
) : ToDaemon

/**
 * phone -> daemon: the line-level diff of one file the session touched, rebuilt from the same
 * transcript that backs [ListSessionFiles] (Claude structuredPatch hunks / Codex patch envelopes)
 * — NOT from disk, so it needs no read surface beyond [ReadFile]'s. Reply is one [FileDiff].
 * A daemon that predates this drops it; the client times out to its "update the daemon" state.
 */
@Serializable
@SerialName("pocket/diff.read")
data class ReadFileDiff(
    val workdir: String,
    val sessionId: String,
    val path: String,
    val agent: AgentKind = AgentKind.CLAUDE,
) : ToDaemon

/** phone -> daemon: query the daemon-host CLI's login state (`claude auth status`). Reply is one
 *  [AuthState]. A daemon that predates this drops it — the client shows no account info then. */
@Serializable
@SerialName("pocket/auth.fetch")
data object FetchAuthStatus : ToDaemon

/**
 * phone -> daemon: start a login / account switch (`claude auth login`). If already logged in the
 * daemon logs out first — this is the "switch account" gesture, and `auth login` over a live
 * credential is not a verified path. Refused (AuthState.error + [AuthState.blockers]) while any
 * conversation is mid-task (executing turn / background jobs): swapping credentials under an agent
 * actively talking to the API breaks it. Idle-but-open conversations are closed automatically
 * instead (they resume from disk under the new account). The daemon replies with [AuthState]
 * (loginPending + loginUrl), the browser opens on the daemon host, and the user pastes the code
 * back via [AuthLoginCode].
 *
 * [force] = the user saw the blocker list and chose "stop them & switch": the daemon also closes
 * the mid-task conversations (their process trees — background shells included — die; transcripts
 * persist and resume like any cold session) before starting the login. An old daemon ignores the
 * unknown flag and just refuses again — never destructive on downgrade.
 */
@Serializable
@SerialName("pocket/auth.login")
data class AuthLogin(val console: Boolean = false, val force: Boolean = false) : ToDaemon

/** phone -> daemon: the OAuth authorization code the user copied from the browser. */
@Serializable
@SerialName("pocket/auth.code")
data class AuthLoginCode(val code: String) : ToDaemon

/** phone -> daemon: abandon a pending login (kills the CLI child; logged-out state stands). */
@Serializable
@SerialName("pocket/auth.login.cancel")
data object AuthLoginCancel : ToDaemon

/** phone -> daemon: `claude auth logout`. Same mid-task guard + idle auto-close as [AuthLogin]. */
@Serializable
@SerialName("pocket/auth.logout")
data object AuthLogout : ToDaemon

/**
 * client -> daemon: set (or, with [enabled] null, just query) whether the daemon asks the relay to
 * push "turn complete" alerts to phones — lets someone working AT the computer silence their phone
 * without unpairing. Reply is one [PushPrefs]. A daemon that predates this drops it — the client
 * hides the toggle then.
 */
@Serializable
@SerialName("pocket/push.prefs.set")
data class SetPushPrefs(val enabled: Boolean? = null) : ToDaemon

// ── API presets (issue #113): named env overrides for third-party API users ──
// Every pocket/presets.* request is answered by one [PresetsState]. A daemon that predates these
// silently drops them (undecodable frame) — the client shows an "update the daemon" line instead,
// and MUST NOT offer the token-bearing create/edit form until a [PresetsState] reply proves the
// daemon understands presets (so a plaintext token is never fired at a peer that can't store it).

/** client -> daemon: list the saved presets (masked) + which one is active. */
@Serializable
@SerialName("pocket/presets.fetch")
data object FetchPresets : ToDaemon

/**
 * client -> daemon: create ([id] null) or update ([id] set) one preset. [token] is WRITE-ONLY: the
 * plaintext rides this frame over the E2E channel, is stored on the daemon, and only ever comes back
 * as [PresetSummary.tokenMask]. On update, a null [token] keeps the stored one ("leave blank to keep").
 * [tokenVar] picks which env var carries it ([PresetEnv.TOKEN_VARS]). Validation failures come back
 * as [PresetsState.error] with [PresetsState.fieldError] naming the offending field.
 */
@Serializable
@SerialName("pocket/presets.save")
data class SavePreset(
    val id: String? = null,
    val name: String,
    val baseUrl: String,
    val tokenVar: String = PresetEnv.AUTH_TOKEN,
    val token: Secret? = null,
    val model: String? = null,
    val smallFastModel: String? = null,
) : ToDaemon

/** client -> daemon: delete a preset. Deleting the ACTIVE one deactivates first — same switch
 *  semantics as [ActivatePreset] (mid-task sessions refuse via blockers, idle ones are closed). */
@Serializable
@SerialName("pocket/presets.delete")
data class DeletePreset(val id: String, val force: Boolean = false) : ToDaemon

/**
 * client -> daemon: make [id] the active preset (null = deactivate, back to the computer's own env /
 * login). New sessions launch with the preset's env injected; sessions already open keep the endpoint
 * they started with. Switch semantics mirror [AuthLogin]: refused with [PresetsState.blockers] while
 * any conversation is mid-task (its running process holds the OLD env for its next turn), idle-but-open
 * conversations are closed automatically (they cold-resume under the new env). [force] = the user saw
 * the blocker list and chose "stop all & switch".
 */
@Serializable
@SerialName("pocket/presets.activate")
data class ActivatePreset(val id: String? = null, val force: Boolean = false) : ToDaemon

// ===========================================================================
//  daemon  ->  phone   (ToPhone)
// ===========================================================================

/** daemon -> client: the current push preference — the single reply to every [SetPushPrefs]. */
@Serializable
@SerialName("pocket/push.prefs")
data class PushPrefs(val enabled: Boolean) : ToPhone

/**
 * daemon -> phone: the CLI's auth state — the single reply to every pocket/auth.* request and pushed
 * again when a pending login resolves. The client renders the LATEST one; it never builds its own
 * login state machine. [loginPending]+[loginUrl] mean a login child is waiting for the browser
 * dance / pasted code. [error] is a user-facing refusal (live sessions, spawn failure); the other
 * fields still carry the actual current state alongside it. When the refusal is the mid-task guard,
 * [blockers] itemizes the offending conversations so the client can render them actionably (stop /
 * force-switch) instead of a dead-end string; an old daemon just leaves it empty.
 */
@Serializable
@SerialName("pocket/auth.state")
data class AuthState(
    val loggedIn: Boolean = false,
    val email: String? = null,
    val orgName: String? = null,
    val subscriptionType: String? = null, // e.g. "max" / "pro" — shown as the plan badge
    val authMethod: String? = null,       // e.g. "claude.ai" / "console"
    // non-null when a key/env var authenticates (e.g. "ANTHROPIC_API_KEY"): the CLI still reports
    // authMethod "claude.ai" + loggedIn, but email/plan are null and `claude auth login/logout` can't
    // override the key, so the client explains that instead of offering a dead account switch (#73).
    val apiKeySource: String? = null,
    val loginPending: Boolean = false,
    val loginUrl: String? = null,
    val error: String? = null,
    val blockers: List<AuthBlocker> = emptyList(),
) : ToPhone

/**
 * daemon -> client: the presets truth — the single reply to every pocket/presets.* request. Tokens
 * appear ONLY as masks ([PresetSummary.tokenMask]); there is no frame that carries one back out.
 * [error] is a user-facing refusal (validation, mid-task guard); the list still reflects the actual
 * stored state alongside it. [fieldError] names the offending [SavePreset] field ("name" | "baseUrl" |
 * "token") so the form can mark it inline. When the refusal is the mid-task guard, [blockers] itemizes
 * the working conversations — same rendering as the OAuth account-switch refusal card.
 */
@Serializable
@SerialName("pocket/presets.state")
data class PresetsState(
    val presets: List<PresetSummary> = emptyList(),
    val activeId: String? = null,
    val error: String? = null,
    val fieldError: String? = null,
    val blockers: List<AuthBlocker> = emptyList(),
) : ToPhone

@Serializable
@SerialName("pocket/dirs")
data class Directories(val entries: List<DirectoryEntry>, val root: String? = null) : ToPhone

@Serializable
@SerialName("pocket/sessions")
data class Sessions(val workdir: String, val items: List<SessionSummary>) : ToPhone

/**
 * Aggregated token usage (issue #26). [tokensToday]/[requestsToday]/[cacheHitPct]/[costUsdToday] are for the
 * current local day; [days] is the per-day trend (oldest→newest, last element = today); [models] is the by-model
 * breakdown (desc by tokens). Cost comes from the transcript's own costUSD (null when none is recorded).
 * [hours] is the 24 hourly buckets (00:00→23:00) of TODAY, filled only for the Today range by a newer daemon;
 * null from an older daemon (the phone then hides the Today trend area) and null for the 7d/30d ranges.
 */
@Serializable
@SerialName("pocket/usage")
data class Usage(
    val days: List<UsageDay> = emptyList(),
    val models: List<UsageModel> = emptyList(),
    val tokensToday: Long = 0,
    val requestsToday: Long = 0,
    val cacheHitPct: Int? = null,
    val costUsdToday: Double? = null,
    val hours: List<UsageDay>? = null,
) : ToPhone

/**
 * The conversation is live. sessionId is backfilled once claude reports system.init.
 * [mode] is the daemon's ACTUAL permission mode — the phone reconciles its badge from it.
 * Null when observing: the terminal owns that session, the daemon doesn't know its mode.
 * [executing] is whether a turn is in flight RIGHT NOW — the phone resets its streaming/■
 * state from it on (re)attach. Null = sender predates the field (or observing): keep local state.
 * [model]/[effort] are the daemon's actual model + reasoning-effort for this session (for the
 * header + session-info sheet). [contextWindow] is the token capacity; null => the phone derives
 * it from [model]. [contextUsed] seeds the usage statusline on resume (tokens the last completed
 * turn left in the window) so it shows before the first new turn; null => unknown, the phone waits
 * for the next [TurnDone]. All are re-announced on every relaunch (mode/model/effort switch).
 */
@Serializable
@SerialName("pocket/session.live")
data class SessionLive(
    val convoId: String,
    val workdir: String,
    val sessionId: String? = null,
    val observing: Boolean = false,
    val mode: PermissionMode? = null,
    val executing: Boolean? = null,
    val model: String? = null,
    val effort: String? = null,
    val contextWindow: Long? = null,
    val contextUsed: Long? = null, // resume-time seed for the usage statusline (null = older daemon / no prior turn)
    val agent: AgentKind? = null, // which backend drives this session (null = older daemon → phone assumes Claude)
    // ≥2 consecutive turns produced only failures/`<synthetic>` placeholders (live-observed or read from
    // the resumed transcript's tail) — the session is likely past its context window and every send just
    // bloats the transcript (issue #65). Clients warn + gate the next send; old clients ignore the field.
    val degraded: Boolean = false,
) : ToPhone

/** A streamed assistant content piece. seq is monotonic per convo for ordering. */
@Serializable
@SerialName("pocket/chunk")
data class AssistantChunk(val convoId: String, val seq: Long, val piece: StreamPiece) : ToPhone

/** A tool invocation surfaced to the UI (original; no Anthropic schema crosses the wire).
 *
 *  Sub-agent visibility (issue #77) rides three OPTIONAL fields — an old daemon omits them (the
 *  client renders today's flat card) and an old client ignores them:
 *  [toolUseId] is the originating tool_use id, so a client can correlate a Task/Agent card's START
 *  with its later RESULT. [parentToolUseId] is set when this tool ran INSIDE a sub-agent — clients
 *  fold such events into the parent's card as progress instead of a top-level row. [output] rides
 *  the RESULT phase only: the tool's final text (for a sub-agent, its report), capped daemon-side.
 *  The daemon emits RESULT only for sub-agent (Task/Agent) calls today — every other tool stays
 *  START-only, exactly the pre-#77 stream. */
@Serializable
@SerialName("pocket/tool")
data class ToolEvent(
    val convoId: String,
    val seq: Long,
    val phase: ToolPhase,
    val tool: String,
    val inputPreview: String? = null,
    val ok: Boolean? = null,
    val toolUseId: String? = null,
    val parentToolUseId: String? = null,
    val output: String? = null,
) : ToPhone

/** The tool names the Claude CLI uses for a sub-agent call — "Task" through 2.1.x, "Agent" on
 *  current CLIs. One predicate shared by daemon (tracking) and clients (card rendering) so the
 *  two sides can't drift on the alias list. */
fun isSubagentTool(tool: String): Boolean = tool == "Task" || tool == "Agent"

/** The CLI's Workflow orchestration tool (one call fans out to dozens of agents — issue #106).
 *  Shared daemon+clients so the card dispatch and the tracker can't drift. */
fun isWorkflowTool(tool: String): Boolean = tool == "Workflow"

/** A permission prompt the phone must resolve. askId == Anthropic request_id. */
@Serializable
@SerialName("pocket/ask")
data class PermissionAsk(
    val convoId: String,
    val askId: String,
    val tool: String,
    val inputPreview: String,
    val mode: PermissionMode? = null,
    val title: String = "",            // human verb, e.g. "Run command" / "Write file"
    val rule: String? = null,          // the scope "Always allow" would remember, e.g. "git status" / "Edit"
    val danger: Boolean = false,       // destructive tool (rm, force-push…): nudge to "Allow once"
    val dangerNote: String? = null,    // e.g. "delete files"
    val diff: String? = null,          // unified-diff text for a file-change approval (Codex patch) — phone renders it as +/- lines
    // AskUserQuestion: the parsed questions — a new phone renders a question card and answers via
    // PermissionVerdict.answers/response; an old phone ignores this field and shows the generic
    // allow/deny card (a bare allow makes claude synthesize "the user did not answer").
    val questions: List<AskQuestion>? = null,
    // One-off human decision (plan approval, questions): "always allow / remember" must not be offered.
    // Mirrors the daemon's ToolMeta.neverRemember, which stays enforced server-side; this flag only
    // drives client UI. Old daemons omit it — clients fall back through [oneOff].
    val neverRemember: Boolean = false,
    // How many seconds the daemon will wait for the verdict before it auto-denies and withdraws this card
    // (issue #100). The phone counts its local "no response" fallback against THIS, so a long daemon-side
    // window (the product's whole premise is "you're away from the computer") no longer collides with a
    // hardcoded 30s client countdown. Null from a pre-#100 daemon → the phone keeps its legacy 30s; old
    // phones ignore the field.
    val timeoutSec: Int? = null,
) : ToPhone

/** Whether this ask is a one-off decision the UI must not offer to remember. The daemon's flag is the
 *  source of truth; the tool-name check covers daemons that predate [PermissionAsk.neverRemember] —
 *  kept HERE so neither client re-encodes the legacy literal. */
val PermissionAsk.oneOff: Boolean
    get() = neverRemember || tool == "ExitPlanMode" || tool == "exit_plan_mode"

/** Whether this ask is an AskUserQuestion (the model asking the user to choose), not a permission gate — the
 *  UI renders it as a question card instead of an allow/deny card. Kept HERE so every call site reads one
 *  predicate instead of re-testing `questions != null` across both clients. */
val PermissionAsk.isQuestion: Boolean get() = questions != null

/** The daemon retired a pending ask — dismiss / finalize the card. Sent when the agent withdrew it
 *  (claude's control_cancel_request), when the approval window timed out (issue #100 — the ONE path the
 *  phone otherwise can't observe, since from the CLI's view a timed-out request is already answered and no
 *  cancel ever comes), or when the session closes. [reason] tells the phone which terminal state to render;
 *  it is a trailing optional so a pre-#100 daemon omits it (decodes to [AskWithdrawnReason.WITHDRAWN]) and
 *  an old phone ignores it (ignoreUnknownKeys) and keeps just dismissing the card. */
@Serializable
@SerialName("pocket/ask.withdrawn")
data class AskWithdrawn(
    val convoId: String,
    val askId: String,
    val reason: AskWithdrawnReason = AskWithdrawnReason.WITHDRAWN,
) : ToPhone

/** Turn finished. finalText is the result text (if any); usage is token accounting (if present).
 *  [error] non-null = the turn FAILED and finalText (if any) is not a real answer: the CLI reported
 *  is_error, or every API call failed and it wrote a `<synthetic>` placeholder reply (issue #65 —
 *  previously swallowed, rendering as a normal-looking bubble). Clients show it as an error row;
 *  old clients ignore the field and keep today's behavior. */
@Serializable
@SerialName("pocket/turn.done")
data class TurnDone(
    val convoId: String,
    val finalText: String? = null,
    val usage: TokenUsage? = null,
    val error: String? = null,
) : ToPhone

/** daemon -> phone: delivery receipt for a [SendPrompt] that carried a promptId — emitted the moment
 *  the turn is handed to the agent (or handled as a daemon slash command). The client flips its
 *  "sending…" marker to delivered. Old phones drop the unknown frame; old daemons never send it —
 *  the client then falls back to first-stream-evidence, exactly today's behavior (issue #66). */
@Serializable
@SerialName("pocket/prompt.ack")
data class PromptAck(val convoId: String, val promptId: String) : ToPhone

/** An error surfaced to the phone. convoId null = connection-level. */
@Serializable
@SerialName("pocket/error")
data class PocketError(
    val code: String,
    val message: String,
    val convoId: String? = null,
) : ToPhone

/**
 * daemon -> phone: a [SendPrompt] referenced a conversation the daemon no longer holds (idle-reaped
 * while the link was down, or lost to a daemon restart). The phone auto-reopens the session and
 * resends the prompt instead of spinning forever. Older daemons silently dropped these; older phones
 * ignore this frame (unknown type) and behave as before — both directions stay wire-compatible.
 */
@Serializable
@SerialName("pocket/session.gone")
data class SessionGone(val convoId: String) : ToPhone

/**
 * device -> daemon, direct-LAN transport only: the opening claim naming the paired device, sent as
 * the first TEXT frame on the socket. The daemon looks up the device's paired static key and runs
 * the same Noise handshake as the relay data plane (unknown deviceIds are dropped). Consumed by the
 * transport layer — never routed to request handling.
 */
@Serializable
@SerialName("pocket/lan.hello")
data class LanHello(val deviceId: String) : ToDaemon

/**
 * daemon -> phone, sent once after each E2E session establishes (relay or LAN): where this daemon
 * can also be reached directly. [lanUrl] is a `ws://` URL on the daemon's LAN interface (loopback
 * when bound conservatively), null when the direct listener is disabled — the phone then clears any
 * stored address. The phone persists it per binding and tries it before the relay on later connects.
 * [hostname] is the daemon host's OS computer name — the client adopts it as the binding's default
 * display name until the user sets a nickname (so a computer reads as "Pandas-MacBook-Pro", not a
 * truncated account-id hash — issue #62). Null when unresolved or from a daemon that predates it.
 */
@Serializable
@SerialName("pocket/daemon.info")
data class DaemonInfo(val lanUrl: String? = null, val hostname: String? = null) : ToPhone

@Serializable
enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
}

/** One (question → answer) an AskUserQuestion resolved, reconstructed from a replayed transcript so a
 *  resumed/observed session shows the compact "answered" row instead of the raw tool JSON (issue #110).
 *  [question] is blank for a freeform reply. */
@Serializable
data class QuestionAnswer(val question: String, val answer: String)

/** One past message in a resumed session's transcript. [error] marks an assistant record that was a
 *  `<synthetic>` API-failure placeholder, not a real reply (issue #65) — clients render it as an error
 *  row; old clients ignore the flag and show the placeholder text as before.
 *  [ok]/[output] land on a sub-agent (Task/Agent) TOOL row only (issue #77): the completed run's
 *  outcome + capped final report, so a replayed transcript keeps the expandable card. Optional both
 *  ways — an old daemon omits them, an old client ignores them.
 *  [answers] lands on an AskUserQuestion TOOL row only (issue #110): the (question → answer) pairs the
 *  user picked, so a replayed transcript shows the same compact answered row the live path leaves
 *  behind instead of the raw questions JSON. Optional both ways — an old daemon omits it (the row falls
 *  back to a plain tool card), an old client ignores it. */
@Serializable
data class HistoryMessage(
    val role: ChatRole,
    val text: String,
    val tool: String? = null,
    val error: Boolean = false,
    val ok: Boolean? = null,
    val output: String? = null,
    val answers: List<QuestionAnswer>? = null,
    /** A Workflow TOOL row's run id (issue #106) — lets the replayed card bind to the
     *  [WorkflowRun] pushed separately via [WorkflowUpdate]. Trailing optional both ways:
     *  old daemons omit it (the card renders as a plain tool row), old clients ignore it. */
    val workflowRunId: String? = null,
)

/** daemon -> phone: the prior transcript of a resumed session, sent once after [SessionLive]. */
@Serializable
@SerialName("pocket/history")
data class ConvoHistory(val convoId: String, val messages: List<HistoryMessage>) : ToPhone

/** One slash command the composer can offer. [name] has no leading "/". */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String? = null,  // e.g. "<name>" / "[instructions]"; null = takes no arguments
    val source: CommandSource = CommandSource.BUILTIN,
)

/** Where a slash command was discovered — shown as a small tag in the composer menu. */
@Serializable
enum class CommandSource {
    @SerialName("builtin") BUILTIN,
    @SerialName("user") USER,       // ~/.claude/commands/*.md
    @SerialName("project") PROJECT, // <workdir>/.claude/commands/*.md
    @SerialName("skill") SKILL,     // ~/.claude/skills/<name>/ or <workdir>/.claude/skills/<name>/
}

/** daemon -> phone: the slash commands available to this conversation, sent after [SessionLive]. */
@Serializable
@SerialName("pocket/commands")
data class CommandList(val convoId: String, val commands: List<SlashCommand>) : ToPhone

/**
 * daemon -> phone: the conversation's background jobs (backgrounded shells, sub-agents, monitors),
 * pushed whenever the set or any status changes. An empty list clears the in-chat indicator.
 */
@Serializable
@SerialName("pocket/jobs")
data class BackgroundJobs(val convoId: String, val jobs: List<BackgroundJob>) : ToPhone

/**
 * daemon -> phone: one Workflow run's full snapshot (issue #106), pushed on every state transition
 * (agent started/finished/failed, phase reached, run settled) and replayed once per finished run
 * when a session is resumed/reattached. Clients key runs by [WorkflowRun.runId] and reconcile
 * agents by index. Old phones drop the unknown frame — the chat keeps its plain Workflow tool row.
 */
@Serializable
@SerialName("pocket/workflow")
data class WorkflowUpdate(val convoId: String, val run: WorkflowRun) : ToPhone

/**
 * phone -> daemon: fetch ONE workflow agent's full prompt + return for the detail sheet — snapshots
 * only carry CLI-capped previews. The daemon reads the run's on-disk journal/transcript
 * (`subagents/workflows/<runId>/`) and answers with [WorkflowAgentDetail]. Old daemons drop the
 * unknown frame; the client's sheet then keeps showing the previews it already has.
 */
@Serializable
@SerialName("pocket/workflow.agent.fetch")
data class GetWorkflowAgentDetail(
    val convoId: String,
    val runId: String,
    val agentIndex: Int,
    val agentId: String? = null,
) : ToDaemon

/** daemon -> phone: the full prompt/result text for one workflow agent ([GetWorkflowAgentDetail]).
 *  Either side may be null when the on-disk record is gone (run dir cleaned) — the sheet keeps its
 *  previews. [result] is the agent's return value (pretty-printed when it was structured JSON). */
@Serializable
@SerialName("pocket/workflow.agent")
data class WorkflowAgentDetail(
    val convoId: String,
    val runId: String,
    val agentIndex: Int,
    val prompt: String? = null,
    val result: String? = null,
) : ToPhone

/** daemon -> phone: result of transcribing a voice capture. ok=false carries a user-facing [error]. */
@Serializable
@SerialName("pocket/transcript")
data class Transcript(
    val convoId: String,
    val captureId: String,
    val text: String = "",
    val ok: Boolean = true,
    val error: String? = null, // e.g. "whisper-cli not found — brew install whisper-cpp"
) : ToPhone

/**
 * daemon -> phone: a file upload settled — the single reply to a completed (failed, or refused)
 * [FileChunk] stream, mirroring [Transcript]. On success [path] is the landing path RELATIVE to the
 * session's cwd (`.ccpocket/inbox/<captureId>/<name>`, daemon-host separators) — the client references
 * it with the composer's `@`-token (the #75 mechanism) so the agent Reads the file by path; no new
 * daemon read surface is involved. [name] is the sanitized basename actually written (may differ from
 * [FileChunk.name] — clients display/reference THIS one). ok=false carries a user-facing [error] and
 * nothing was landed. A phone that predates this drops the unknown frame; a NEW phone that never gets
 * one (old daemon dropped the chunks) times out to its "update the computer's cc-pocket" state.
 */
@Serializable
@SerialName("pocket/file.uploaded")
data class FileUploaded(
    val convoId: String,
    val captureId: String,
    val ok: Boolean = true,
    val path: String? = null,
    val name: String? = null,
    val size: Long = 0,
    val error: String? = null,
) : ToPhone

/** Hard per-file cap for [FileChunk] uploads, enforced daemon-side (early, via [FileChunk.totalBytes],
 *  and again on actual bytes written) and pre-checked client-side at pick time. One shared constant so
 *  the attach sheet's "up to 200 MB" caption and the daemon's refusal can't drift. */
const val MAX_UPLOAD_BYTES: Long = 200L * 1024 * 1024

/** daemon -> phone: the result of a [RunShellCommand]. stdout/stderr are capped server-side. */
@Serializable
@SerialName("pocket/shell.result")
data class ShellResult(
    val convoId: String,
    val command: String,
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val denied: Boolean = false,   // approval denied (or timed out) → the command was not run
    val error: String? = null,     // a spawn/system error (e.g. bad workdir), distinct from a non-zero exit
) : ToPhone

/** daemon -> phone: reply to [ListSessionFiles]. Matched client-side on (workdir, sessionId). */
@Serializable
@SerialName("pocket/files")
data class SessionFiles(
    val workdir: String,
    val sessionId: String,
    val files: List<ChangedFile> = emptyList(),
    val error: String? = null, // transcript not found / unreadable — files is empty then
) : ToPhone

/** daemon -> phone: reply to [ListPathEntries] — the children under (workdir, subPath). Matched
 *  client-side on (workdir, subPath). [truncated] = more children than [ListPathEntries.limit] existed
 *  and the tail was dropped. On failure (subPath escaped the workdir / not a readable dir) [ok] is
 *  false, [error] carries why, and [entries] is empty. */
@Serializable
@SerialName("pocket/path.entries")
data class PathEntries(
    val workdir: String,
    val subPath: String,
    val entries: List<PathEntry> = emptyList(),
    val truncated: Boolean = false,
    val ok: Boolean = true,
    val error: String? = null,
) : ToPhone

/**
 * daemon -> phone: reply to [ReadFile]. Exactly one of [text]/[base64] is set on success: text for
 * anything decodable as UTF-8, base64 for images and other binaries (office documents ride this
 * channel to the client's native viewer / share sheet — issues #67/#79, [mediaType] says what it
 * is). Text is capped server-side ([truncated] + [totalBytes] tell the phone it's looking at a
 * prefix); base64 payloads arrive whole or fail with [error] — a truncated docx is corrupt — so
 * one file still can't blow the 4 MiB relay frame.
 */
@Serializable
@SerialName("pocket/file.content")
data class FileContent(
    val workdir: String,
    val sessionId: String,
    val path: String,
    val ok: Boolean = true,
    val error: String? = null,     // not in the session's changed set / gone from disk / too large
    val text: String? = null,
    val base64: String? = null,
    val mediaType: String? = null, // e.g. "image/png" when base64 is set
    val truncated: Boolean = false,
    val totalBytes: Long = 0,
) : ToPhone

/**
 * daemon -> phone: reply to [ReadFileDiff]. [diff] is unified-diff text: `@@ -a,b +c,d @@` hunk
 * headers followed by ` `/`+`/`-`-prefixed lines, one hunk group per tool call in transcript
 * order (line numbers are as-of-that-edit; hunks are NOT merged across calls). Codex hunks may
 * carry `@@ -0,0 +0,0 @@` when the patch envelope had no line numbers. Capped server-side like
 * [FileContent] ([truncated] set when the tail was dropped).
 */
@Serializable
@SerialName("pocket/diff.content")
data class FileDiff(
    val workdir: String,
    val sessionId: String,
    val path: String,
    val ok: Boolean = true,
    val error: String? = null,  // not in the session's changed set / no line-level data in transcript
    val diff: String? = null,
    val adds: Int = 0,
    val dels: Int = 0,
    val truncated: Boolean = false,
) : ToPhone

// ===========================================================================
//  control plane  <->  relay   (ToRelay; carried in Envelope{to=RELAY} TEXT frames)
//
//  The relay parses ONLY these. App content travels as opaque, end-to-end
//  encrypted BINARY frames the relay forwards without decoding (zero-knowledge).
//  All byte-valued fields below are base64url(no-pad) of raw bytes.
// ===========================================================================

@Serializable
enum class Role {
    @SerialName("daemon") DAEMON,
    @SerialName("device") DEVICE,
}

// ---- daemon login: signed-challenge over its Ed25519 static key ----

/** daemon -> relay: opening claim. accountId MUST equal base32(sha256(ed25519Pub)). */
@Serializable
@SerialName("pocket/daemon.hello")
data class DaemonHello(val accountId: String, val ed25519Pub: String, val protoV: Int = 1) : ToRelay

/** relay -> daemon: a single-use nonce to sign (bound to this socket, short TTL). */
@Serializable
@SerialName("pocket/challenge")
data class Challenge(val nonce: String, val serverTimeMs: Long) : ToRelay

/** daemon -> relay: Ed25519 signature over "ccpocket/daemon-auth/v1"|0x00|accountId|nonce. */
@Serializable
@SerialName("pocket/daemon.auth")
data class DaemonAuth(val sig: String) : ToRelay

// ---- device login: bearer credential issued at pairing ----

/** device -> relay: opening claim. secret is checked against stored sha256(secret). */
@Serializable
@SerialName("pocket/device.hello")
data class DeviceHello(val deviceId: String, val secret: String, val protoV: Int = 1) : ToRelay

// ---- handshake result ----

/** relay -> peer: authenticated and bound to accountId; binary data plane is now live. */
@Serializable
@SerialName("pocket/attached")
data class Attached(val role: Role, val accountId: String) : ToRelay

/** relay -> peer: auth/handshake failed; the relay closes the socket after this. */
@Serializable
@SerialName("pocket/auth.error")
data class AuthError(val code: String, val message: String? = null) : ToRelay

// ---- pairing (only an authenticated daemon may mint) ----

/** daemon -> relay: mint a short-lived, single-use pairing ticket. Carries the daemon's E2E public
 *  key so the relay can serve it to a phone that pairs by short code (the QR path keeps it out-of-band). */
@Serializable
@SerialName("pocket/pair.begin")
data class PairBegin(val e2ePub: String) : ToRelay

/** relay -> daemon: the raw ticket (for the QR) plus a short 6-digit code to type on the phone. */
@Serializable
@SerialName("pocket/pair.ticket")
data class PairTicket(val ticket: String, val expiresInSec: Int, val code: String) : ToRelay

/** relay -> daemon: a device redeemed a ticket. devicePubKey is an advisory hint; the
 *  daemon allow-lists it only after the first ticket-PSK Noise handshake succeeds. */
@Serializable
@SerialName("pocket/device.paired")
data class DevicePaired(val deviceId: String, val devicePubKey: String) : ToRelay

/** daemon -> relay: revoke a device; the relay marks it revoked and force-closes its socket. */
@Serializable
@SerialName("pocket/device.revoke")
data class RevokeDevice(val deviceId: String) : ToRelay

/** relay -> daemon: a device was just revoked — prune it from the local paired allow-list NOW. Without
 *  this the direct-LAN gate (which trusts that list) would keep honoring a revoked device's key until
 *  the next attach-replay reconcile. Older daemons ignore the unknown frame (replay covers them). */
@Serializable
@SerialName("pocket/device.revoked")
data class DeviceRevoked(val deviceId: String) : ToRelay

/** relay -> peer: the other end's online/offline transition. */
@Serializable
@SerialName("pocket/peer.presence")
data class PeerPresence(val online: Boolean) : ToRelay

/** peer -> relay: application-level liveness probe. The relay echoes [Pong] with the same [ts].
 *  Getting the echo proves the relay *application* (not merely the TCP socket) is alive — this
 *  catches half-open/zombie links that the transport's TCP ping cannot. */
@Serializable
@SerialName("pocket/ping")
data class Ping(val ts: Long) : ToRelay

/** relay -> peer: echo of a [Ping]. */
@Serializable
@SerialName("pocket/pong")
data class Pong(val ts: Long) : ToRelay

// ---- push notifications (wake an offline phone via APNs/FCM) ----

/**
 * device -> relay: register (or refresh) this device's push token so the relay can wake it while its
 * socket is offline. [platform] selects the relay-side sender — "apns"/"apns_sandbox" (iOS, by build
 * env) or "fcm" (Android via Firebase); future domestic-vendor channels ("xiaomi"/"huawei"/…) slot in
 * here. An empty [token] de-registers (the user turned notifications off). Re-sent on every reconnect.
 */
@Serializable
@SerialName("pocket/push.register")
data class RegisterPush(val platform: String, val token: String) : ToRelay

/**
 * daemon -> relay: a notify-worthy event happened (a turn finished). The relay pushes [title]/[body]
 * to the account's registered tokens ONLY if no device socket is live. Unlike the opaque data plane,
 * this label is cleartext to the relay by design — it becomes the lock-screen alert text.
 *
 * [workdir]/[sessionId] (nullable for wire-compat with older daemons) ride along as routing data so a
 * tapped notification can deep-link straight into that session — they travel as APNs custom keys / FCM
 * data, not in the visible alert text.
 */
@Serializable
@SerialName("pocket/push.notify")
data class NotifyPush(
    val title: String,
    val body: String,
    val workdir: String? = null,
    val sessionId: String? = null,
) : ToRelay

// ---- pairing redeem (REST DTOs over POST /v1/pair/redeem; not Frames) ----

/** device -> relay (HTTP body): redeem a scanned ticket, registering its X25519 static pubkey. */
@Serializable
data class PairRedeem(val ticket: String, val devicePubKey: String)

/** relay -> device (HTTP body): the issued device credential + the account it is bound to. */
@Serializable
data class PairCredential(val deviceId: String, val credential: String, val accountId: String)

/** device -> relay (HTTP body): resolve a short pairing code typed by the user. */
@Serializable
data class PairCodeResolve(val code: String)

/** relay -> device (HTTP body): the pairing payload behind a code (relay is the one being asked). */
@Serializable
data class PairCodePayload(val accountId: String, val daemonPub: String, val ticket: String)
