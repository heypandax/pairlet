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

// ── session groups (issue #119): optional one-level `project → group → session` organization. Group
//    metadata lives on the daemon (consistent across clients); each mutation is answered by re-pushing the
//    workdir's [Sessions] list (with the updated [Sessions.groups] + per-row [SessionSummary.group]) so the
//    change reflects immediately. A daemon that predates these drops the unknown frame (the group op just
//    no-ops there — the client keeps its flat list). ──

/** phone -> daemon: create a new group named [name] under [workdir]. Reply: the re-pushed [Sessions]. */
@Serializable
@SerialName("pocket/group.create")
data class GroupCreate(val workdir: String, val name: String) : ToDaemon

/** phone -> daemon: rename group [groupId] under [workdir] to [name]. Reply: the re-pushed [Sessions]. */
@Serializable
@SerialName("pocket/group.rename")
data class GroupRename(val workdir: String, val groupId: String, val name: String) : ToDaemon

/** phone -> daemon: delete group [groupId] under [workdir]; its sessions fall back to ungrouped (the sessions
 *  are NOT deleted). Reply: the re-pushed [Sessions]. */
@Serializable
@SerialName("pocket/group.delete")
data class GroupDelete(val workdir: String, val groupId: String) : ToDaemon

/** phone -> daemon: file [sessionId] under [groupId] ([groupId] null = move it out of any group). Reply: the
 *  re-pushed [Sessions]. */
@Serializable
@SerialName("pocket/group.assign")
data class GroupAssign(val workdir: String, val sessionId: String, val groupId: String? = null) : ToDaemon

/**
 * phone -> daemon: rename session [sessionId] under [workdir] to [title] (issue #158). The daemon lands it
 * as Claude's OWN `custom-title` transcript record — appended by the live CLI itself (a `rename_session`
 * control_request) when the daemon is driving that session, or appended to the idle `.jsonl` directly in
 * the CLI's exact record shape — so the CLI and every client adopt it through the existing
 * custom-title → ai-title → firstPrompt fallback (#14). Claude sessions only (a Codex id has no transcript
 * under the project dir and fails cleanly). Reply: the re-pushed [Sessions] on success (the same refresh
 * contract as the group mutations), a [PocketError] (code `rename_failed`) on failure — including a session
 * live in ANOTHER client (terminal), which is refused rather than raced. A brand-new message type: an old
 * daemon can't decode it and drops the frame (clients hide the entry unless [Sessions.renameSupported]).
 */
@Serializable
@SerialName("pocket/session.rename")
data class RenameSession(val workdir: String, val sessionId: String, val title: String) : ToDaemon

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
    /**
     * Incremental reattach (issue #147): the transcript cursor ([ConvoHistory.lastSeq]) this client is
     * already caught up to for [resumeId]'s transcript — the daemon then replays only the DELTA past it
     * (a [ConvoHistory] with `delta = true`) instead of the full tail window, turning a reconnect's
     * ~550KB replay into a few KB. Null (the default) = "replay in full": a first open, a client that
     * dropped its transcript, or an OLD client that predates the field. 0 = also "replay in full" BUT
     * declares the client understands `delta = true` frames — an observe view then tails with deltas
     * instead of re-sending the whole window on every write. The daemon safely falls back to
     * a FULL replay whenever the cursor can't be honored (seq older than / past the on-disk transcript,
     * a /clear'd or forked session, a late patch that mutated already-delivered rows). Mixed versions:
     * an OLD daemon ignores the unknown key (ignoreUnknownKeys) and replays in full — exactly today's
     * behavior; an OLD client never sends it. The reconnect outbox dedupe (keep only the NEWEST
     * OpenSession per (workdir, resumeId)) stays correct: the newest open carries the freshest cursor.
     */
    val lastEventSeq: Long? = null,
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
 * phone -> daemon: read one file of the session's project. The daemon serves (issue #133):
 *   - any path sitting canonically INSIDE [workdir] (`..`/symlink escapes are refused — the same
 *     containment red line as [ExportFile]), so the files the composer's `@`-completion can name
 *     are also viewable; and
 *   - paths OUTSIDE the tree only when the session's own transcript shows this session changed
 *     them (the pre-#133 changed-set gate, kept for absolute-path edits) — never an arbitrary read.
 * The (workdir, sessionId) pair must still name a real transcript, anchoring the read to a session
 * the client can already see. Reply is one [FileContent] — unless [allowChunks] (issue #134):
 * a NEW client sets it true to declare it can reassemble a [FileContentChunk] stream, letting
 * binaries over the single-frame cap through (up to [MAX_CHUNKED_READ_BYTES]). A trailing
 * optional: an OLD daemon ignores the unknown key (single-frame behavior, oversized files still
 * refuse), an OLD client omits it (a new daemon never chunks at it — its one-frame world stands).
 */
@Serializable
@SerialName("pocket/file.read")
data class ReadFile(
    val workdir: String,
    val sessionId: String,
    val path: String,
    val agent: AgentKind = AgentKind.CLAUDE,
    val allowChunks: Boolean = false,
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

// ---- folder-share (issue #115): OWNER-only control plane. These are a full-power device gesture —
//      the daemon handles them only for an interactive OWNER device, NEVER a scoped guest or a headless
//      bridge (a guest re-sharing the owner's machine is exactly the escalation the scope prevents). A
//      guest's capability whitelist omits them, and the daemon double-checks the sender is full-power. ----

/**
 * owner -> daemon: mint a scoped, expiring folder-share invite. The daemon mints a pairing ticket over
 * its relay link, records a GUEST intent binding [path]+[tier]+lifetime to that ticket, and returns a
 * [ShareCreated] carrying the redeemable [ShareInvite]. [expiresInSec] is the SHARE lifetime (the guest
 * is cut when it lapses); the redeem ticket has its own short TTL. A daemon that predates this drops the
 * frame (the owner's app times out to "update the daemon").
 */
@Serializable
@SerialName("pocket/share.create")
data class CreateShare(
    val path: String,
    val tier: AccessTier = AccessTier.COLLABORATE,
    val expiresInSec: Long = 7 * 24 * 3600,
    val label: String? = null, // an optional nickname for the guest, shown on the owner's management page
) : ToDaemon

/** owner -> daemon: list the folders I've shared + who is using them (the management page). Reply: [ShareListing]. */
@Serializable
@SerialName("pocket/share.list")
data object ListShares : ToDaemon

/** owner -> daemon: revoke a share by its guest [deviceId] — cuts the guest's live link NOW and deletes
 *  the credential (the ticket is already spent; the key dies). Reply: [ShareRevoked]. */
@Serializable
@SerialName("pocket/share.revoke")
data class RevokeShare(val deviceId: String) : ToDaemon

// ---- headless bridge control plane (issue #91 follow-up): OWNER-only, on exactly the same footing as
//      the folder-share plane above — a guest/bridge whitelist omits every frame here, so a bridge can
//      never mint or inspect another bridge (self-escalation), and the daemon only reaches this dispatch
//      from its full-power-owner branch. Until now this plane was CLI-only (`pair --headless`,
//      `bridges`), which put the whole feature behind a terminal. ----

/**
 * owner -> daemon: mint a bridge credential (the wire twin of `pair --headless`). The daemon mints a
 * headless pairing ticket over its relay link, records a BRIDGE intent binding [workdirs] + caps to that
 * ticket, and replies [BridgeCreated] with the redeemable blob. Null caps = the daemon's defaults.
 * A daemon that predates this drops the frame (the owner's app times out to "update the daemon").
 */
@Serializable
@SerialName("pocket/bridge.create")
data class CreateBridge(
    val name: String,
    val workdirs: List<String>,
    val maxSessions: Int? = null,
    val opensPerMin: Int? = null,
    val promptsPerMin: Int? = null,
    /** The permission-mode ceiling to grant. Defaults to the STRICTEST ([AccessTier.REVIEW]: every
     *  dangerous action prompts the owner) — a bridge relays prompts from anyone in a chat, so silent
     *  edits must be opted into, not inherited. Never reaches bypassPermissions at any tier. */
    val tier: AccessTier = AccessTier.REVIEW,
    /**
     * Non-null = the daemon MANAGES the adapter process for this bridge (starts it, restarts it, keeps it
     * alive across reboots) instead of the owner running it themselves.
     *
     * This must be decided HERE rather than in a later [ConfigureBridgeRunner], because the daemon does not
     * retain the minted ticket (only its hash, as a pairing intent) — the plaintext exists for exactly this
     * one reply. So the daemon can only hand a credential to a runner at mint time. The upside: when
     * managed, [BridgeCreated.credential] is null and the owner never handles the ticket at all.
     */
    val runner: BridgeRunnerSpec? = null,
) : ToDaemon

/** owner -> daemon: list my bridges + their activity and runner state (the management page). Reply: [BridgeListing]. */
@Serializable
@SerialName("pocket/bridge.list")
data object ListBridges : ToDaemon

/** owner -> daemon: revoke a bridge by [name] — cuts its live link NOW, deletes the credential, and stops
 *  its runner if one is managed. Reply: [BridgeRevoked]. */
@Serializable
@SerialName("pocket/bridge.revoke")
data class RevokeBridge(val name: String) : ToDaemon

/**
 * owner -> daemon: UPDATE the managed runner of an already-managed bridge [name] — rotate the IM app
 * secret, set the admin open_id after the bootstrap round-trip, point at a moved checkout, toggle
 * autostart. The daemon restarts the process if it was running. Reply: [BridgeRunnerStatus].
 *
 * This cannot ATTACH a runner to a bridge minted without one: the daemon no longer holds that bridge's
 * ticket (see [CreateBridge.runner]), so it has nothing to authenticate a new process with. Attaching
 * after the fact means revoking and re-creating. [spec]'s env replaces the stored env wholesale.
 */
@Serializable
@SerialName("pocket/bridge.runner.configure")
data class ConfigureBridgeRunner(
    val name: String,
    val spec: BridgeRunnerSpec,
    /**
     * True = overlay [spec]'s NON-BLANK env values onto the stored env instead of replacing it wholesale.
     * This exists for the edit form: the owner types ONLY what changes (say, FEISHU_ADMIN_OPEN_ID after
     * the bot's bootstrap echo) — they cannot retype the app secret because it is never echoed back out.
     *
     * Mixed-version honesty: an OLD daemon ignores this field and replaces wholesale, which can drop keys
     * the app didn't resend. That loss is immediately visible — the [BridgeRunnerStatus] reply's
     * [BridgeRunnerState.envKeys] no longer lists them — so the client can tell the owner to re-enter,
     * rather than the secret silently vanishing.
     */
    val mergeEnv: Boolean = false,
) : ToDaemon

/** owner -> daemon: start / stop / restart bridge [name]'s managed runner. Reply: [BridgeRunnerStatus].
 *  Unknown [action] is refused rather than guessed. */
@Serializable
@SerialName("pocket/bridge.runner.control")
data class ControlBridgeRunner(val name: String, val action: String) : ToDaemon

/** owner -> daemon: drop the managed runner for [name] (stops it; keeps the bridge credential).
 *  Reply: [BridgeRunnerStatus] with a cleared state. */
@Serializable
@SerialName("pocket/bridge.runner.detach")
data class DetachBridgeRunner(val name: String) : ToDaemon

const val RUNNER_KIND_FEISHU = "feishu"
const val RUNNER_START = "start"
const val RUNNER_STOP = "stop"
const val RUNNER_RESTART = "restart"

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
data class Sessions(
    val workdir: String,
    val items: List<SessionSummary>,
    // The project's session groups (issue #119), ordered — the client renders the group headers from this and
    // files each row under [SessionSummary.group]. A trailing optional: an old daemon omits it (the client shows
    // no groups, a flat list), an old app ignores it. Re-pushed after every pocket/group.* mutation.
    val groups: List<SessionGroup>? = null,
    // This daemon handles [RenameSession] and this connection may send it (owner only — false for a guest,
    // issue #158). A trailing optional: an old daemon omits it → false → clients hide their rename entry
    // instead of sending a frame that would be silently dropped; an old app ignores it.
    val renameSupported: Boolean = false,
) : ToPhone

/**
 * Aggregated token usage (issue #26). [tokensToday]/[requestsToday]/[cacheHitPct]/[costUsdToday] are for the
 * current local day; [days] is the per-day trend (oldest→newest, last element = today); [models] is the by-model
 * breakdown (desc by tokens). Cost comes from the transcript's own costUSD (null when none is recorded).
 * [hours] is the 24 hourly buckets (00:00→23:00) of TODAY, filled only for the Today range by a newer daemon;
 * null from an older daemon (the phone then hides the Today trend area) and null for the 7d/30d ranges.
 * [prevWindowTokens] is the total of the PREVIOUS equal-width window (span 1 → yesterday, 7 → the 7 days
 * before the window, 30 → the 30 days before), so the hero delta compares like-for-like windows (issue #128).
 * A trailing optional: an old daemon omits it (the app shows no delta), an old app ignores it.
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
    val prevWindowTokens: Long? = null,
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
    // The external trigger source that opened this conversation — the bridge credential's name (issue #91,
    // e.g. "feishu-bot"), so clients can label it "via feishu-bot". Null = opened by an interactive
    // device (today's behavior) or an older daemon; old clients ignore the field.
    val origin: String? = null,
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
 *  old clients ignore the field and keep today's behavior.
 *
 *  [usageLimitResetAt] (issue #137, riding #138's usage-limit detection): when [error] reads as a
 *  usage/rate-limit refusal AND the CLI's error text carries the window's reset moment (the
 *  `…usage limit reached|<unix-epoch>` wording), this is that moment as EPOCH MILLIS — the client
 *  then offers one-tap "auto-continue when the limit resets" (a [ScheduleCreate] one-shot back at
 *  this session). Null whenever the error isn't a limit hit or no epoch could be parsed — the
 *  client shows no button then (graceful degrade). A trailing optional both ways: an old daemon
 *  omits it (null — no button), an old phone ignores the unknown key (ignoreUnknownKeys). */
@Serializable
@SerialName("pocket/turn.done")
data class TurnDone(
    val convoId: String,
    val finalText: String? = null,
    val usage: TokenUsage? = null,
    val error: String? = null,
    val usageLimitResetAt: Long? = null,
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
 * [gatewayBaseUrl] is the third-party `ANTHROPIC_BASE_URL` the daemon's claude launches would use
 * (active preset → process env → user settings.json), null when unset or pointing at the official
 * `api.anthropic.com` (issue #139) — the client then surfaces the gateway model presets first in its
 * model picker. Trailing optional both ways: an old daemon omits it (no gateway hint, presets stay
 * collapsed), an old app ignores it. Re-evaluated per handshake, so activating a preset shows up on
 * the next connect.
 */
@Serializable
@SerialName("pocket/daemon.info")
data class DaemonInfo(
    val lanUrl: String? = null,
    val hostname: String? = null,
    val gatewayBaseUrl: String? = null,
    // capability advertisement (issue #91): this daemon understands the bridge control plane. ABSENT from an
    // older daemon's DaemonInfo → decodes to false → the management page can say "update your daemon" up front
    // instead of sending a bridge frame that just times out on a build that never learned it.
    val bridgeControl: Boolean = false,
) : ToPhone

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

/**
 * daemon -> phone: the prior transcript of a resumed session, sent once after [SessionLive].
 *
 * Incremental reattach (issue #147) rides four TRAILING OPTIONALS — an old daemon omits them (a plain
 * full replay, exactly today's frame) and an old phone ignores them (and, since it never sends
 * [OpenSession.lastEventSeq], never receives a `delta = true` frame it couldn't interpret):
 *  - [lastSeq]: the daemon's transcript cursor after this replay (the source `.jsonl` line count at
 *    read time). The client stores it per session and echoes it back as [OpenSession.lastEventSeq] on
 *    the next reattach. Null = the daemon predates #147 (the client then never asks for a delta).
 *  - [firstSeq]: the cursor of the FIRST included message — the `beforeSeq` anchor a
 *    [FetchHistoryPage] uses to lazy-load older history. Null when the replay is empty.
 *  - [delta]: true = [messages] CONTINUE the client's transcript after the cursor it sent (append/merge
 *    at the tail — never a wipe/replace). Only ever true in reply to an open that carried a honorable
 *    lastEventSeq. false (default) keeps today's semantics: a full window that replaces/merges
 *    wholesale, and an EMPTY non-delta replay is still the daemon's explicit /clear wipe.
 *  - [hasMore]: messages older than [firstSeq] exist on disk — the client may offer "load earlier".
 */
@Serializable
@SerialName("pocket/history")
data class ConvoHistory(
    val convoId: String,
    val messages: List<HistoryMessage>,
    val lastSeq: Long? = null,
    val firstSeq: Long? = null,
    val delta: Boolean = false,
    val hasMore: Boolean = false,
) : ToPhone

/**
 * phone -> daemon: lazy-load one page of history OLDER than [beforeSeq] (a [ConvoHistory.firstSeq] /
 * [ConvoHistoryPage.firstSeq] the client already holds) for a live conversation — the scroll-to-top
 * pagination behind the first screen's ~100-row window (issue #147). The reply is one
 * [ConvoHistoryPage], sent ONLY to the requesting client (never fanned out — other attached clients
 * didn't ask and would prepend rows they may already have). A daemon that predates this drops the
 * unknown frame (runCatching-at-decode); the client arms a reply deadline and stops offering the
 * load-earlier affordance. An old phone never sends it.
 */
@Serializable
@SerialName("pocket/history.page")
data class FetchHistoryPage(
    val convoId: String,
    val beforeSeq: Long,
    val limit: Int = 100,
) : ToDaemon

/**
 * daemon -> phone: one page of older history — [FetchHistoryPage]'s single reply. [messages] are the
 * newest `limit` rows strictly BEFORE the requested seq, in chronological order — the client PREPENDS
 * them. [firstSeq] anchors the next page; [hasMore] = rows older than that still exist. Same
 * frame-budget guard as [ConvoHistory] (a page can never blow the relay cap). An old phone drops the
 * unknown frame harmlessly; an old daemon never sends it.
 */
@Serializable
@SerialName("pocket/history.older")
data class ConvoHistoryPage(
    val convoId: String,
    val messages: List<HistoryMessage>,
    val firstSeq: Long? = null,
    val hasMore: Boolean = false,
) : ToPhone

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

// ── installed skills/plugins catalog (issue #132): the desktop browse page ──────────────────────

/**
 * phone -> daemon: request this machine's installed skills + plugins for the browse page (issue #132).
 * [workdir] additionally scans that project's `.claude/skills`; null = the user-level catalog only.
 * A NEW message type — wire-safe both ways: an old daemon can't decode the unknown discriminator and
 * silently DROPS the frame (its inbound decodes are runCatching-wrapped; no reply ever comes), so the
 * client arms a reply deadline and shows an "update the daemon" state; an old phone never sends it.
 */
@Serializable
@SerialName("pocket/skills.fetch")
data class FetchSkillCatalog(val workdir: String? = null) : ToDaemon

/** Where an installed skill was discovered. */
@Serializable
enum class SkillScope {
    @SerialName("user") USER,       // ~/.claude/skills/<name>/
    @SerialName("project") PROJECT, // <workdir>/.claude/skills/<name>/
}

/** One installed skill with browse-page detail — the composer autocomplete keeps the lighter
 *  [SlashCommand]. Every field beyond [name] is optional-with-default so the shape can grow
 *  tail-first without breaking older peers. */
@Serializable
data class SkillInfo(
    val name: String,
    val description: String = "",
    val scope: SkillScope = SkillScope.USER,
    /** The remaining top-level frontmatter scalars (argument-hint, allowed-tools, license, …) —
     *  [description] is surfaced separately and excluded here. */
    val meta: Map<String, String> = emptyMap(),
    /** SKILL.md body after the frontmatter, capped daemon-side (~4KB); [truncated] marks a longer original. */
    val excerpt: String = "",
    val truncated: Boolean = false,
    /** Absolute path of the skill directory on the daemon's machine (display only). */
    val path: String? = null,
)

/** One installed Claude Code plugin (`~/.claude/plugins`), manifest-derived. Everything beyond
 *  [name] is optional so a plugin with a missing or partial manifest still lists. */
@Serializable
data class PluginInfo(
    val name: String,
    val description: String = "",
    val version: String? = null,
    /** The marketplace half of the install ledger's "name@marketplace" key. */
    val marketplace: String? = null,
    /** The ledger's install scope, raw ("user" / "project"). */
    val scope: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    /** Command names the plugin ships (manifest `commands`), no leading "/". */
    val commands: List<String> = emptyList(),
    /** README.md excerpt, capped like [SkillInfo.excerpt]. */
    val excerpt: String = "",
    val truncated: Boolean = false,
    /** The plugin's install directory on the daemon's machine (display only). */
    val path: String? = null,
)

/** daemon -> phone: the machine's installed skills + plugins — [FetchSkillCatalog]'s single reply. */
@Serializable
@SerialName("pocket/skills")
data class SkillCatalog(
    val skills: List<SkillInfo> = emptyList(),
    val plugins: List<PluginInfo> = emptyList(),
) : ToPhone

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
 * daemon -> phone: one piece of a chunked [ReadFile] reply (issue #134) — the read-direction mirror
 * of [FileChunk]. Sent ONLY when the request carried [ReadFile.allowChunks] and the file is a binary
 * payload (image/document/unknown-binary) over the single-frame cap; text stays capped single-frame
 * and small binaries keep riding one [FileContent]. Chunks arrive IN ORDER on the E2E channel,
 * identity-keyed like [FileContent] (workdir, sessionId, path); [mediaType]/[totalBytes] ride EVERY
 * chunk so reassembly stays stateless. Every chunk's [base64] encodes [READ_CHUNK_RAW_BYTES] raw
 * bytes (a multiple of 3) except the last, so the CONCATENATION of the base64 strings in idx order
 * is itself valid base64 of the whole file — the client needs no per-chunk decode. A failure
 * mid-stream arrives as a plain ok=false [FileContent] for the same identity, superseding the
 * partial. An OLD phone never receives these (it never sets allowChunks); if one ever leaks, the
 * unknown frame is dropped harmlessly.
 */
@Serializable
@SerialName("pocket/file.content.chunk")
data class FileContentChunk(
    val workdir: String,
    val sessionId: String,
    val path: String,
    val idx: Int,            // 0-based, contiguous
    val last: Boolean,       // true on the final chunk -> the client assembles + renders
    val base64: String,
    val mediaType: String? = null,
    val totalBytes: Long = 0,
) : ToPhone

/** Hard total cap for a chunked [ReadFile] (issue #134) — generous for office documents while still
 *  bounding what one tap can pull across the relay. One shared constant so the daemon's refusal and
 *  any client-side caption can't drift. */
const val MAX_CHUNKED_READ_BYTES: Long = 50L * 1024 * 1024

/** Raw bytes per [FileContentChunk] (384 KiB -> 512 KiB of base64 per frame, comfortable margin under
 *  the relay's 4 MiB frame cap with JSON + E2E overhead). MUST stay a multiple of 3: that's what makes
 *  the per-chunk base64 strings concatenable without a decode (see [FileContentChunk]). */
const val READ_CHUNK_RAW_BYTES: Int = 384 * 1024

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

// ---- folder-share (issue #115): OWNER-side replies ----

/** daemon -> owner: the reply to [CreateShare]. On success [invite] carries the redeemable [ShareInvite]
 *  (the app renders it as a QR / copyable blob for the guest); on failure [error] says why (e.g. a phone
 *  pairing is mid-flight, bad path, relay offline). */
@Serializable
@SerialName("pocket/share.created")
data class ShareCreated(
    val ok: Boolean,
    val invite: ShareInvite? = null,
    val error: String? = null,
) : ToPhone

/** daemon -> owner: the reply to [ListShares] — my active shares + their activity (the management page). */
@Serializable
@SerialName("pocket/share.listing")
data class ShareListing(val items: List<ShareInfo> = emptyList()) : ToPhone

/** daemon -> owner: the reply to [RevokeShare]. [ok] false + [error] when the deviceId wasn't a share. */
@Serializable
@SerialName("pocket/share.revoked")
data class ShareRevoked(val deviceId: String, val ok: Boolean, val error: String? = null) : ToPhone

// ---- headless bridge control plane (issue #91 follow-up): OWNER-side replies ----

/**
 * daemon -> owner: the reply to [CreateBridge]. On failure [error] says why (duplicate name, non-absolute
 * workdir, relay offline, adapter wouldn't start).
 *
 * On success exactly ONE of these is set, and which one is the whole UX difference:
 *  - [credential]: an UNMANAGED bridge — hand this blob to an adapter you run yourself, before its short
 *    TTL lapses.
 *  - [runner]: a MANAGED bridge — the daemon already injected the credential into the process it started,
 *    so the ticket never leaves the machine and the owner has nothing to copy.
 */
@Serializable
@SerialName("pocket/bridge.created")
data class BridgeCreated(
    val ok: Boolean,
    val credential: BridgeCredential? = null,
    val error: String? = null,
    val runner: BridgeRunnerState? = null,
) : ToPhone

/** daemon -> owner: the reply to [ListBridges] — my bridges, their activity, and any managed runner. */
@Serializable
@SerialName("pocket/bridge.listing")
data class BridgeListing(val items: List<BridgeInfo> = emptyList()) : ToPhone

/** daemon -> owner: the reply to [RevokeBridge]. [ok] false + [error] when [name] wasn't a bridge. */
@Serializable
@SerialName("pocket/bridge.revoked")
data class BridgeRevoked(val name: String, val ok: Boolean, val error: String? = null) : ToPhone

/** daemon -> owner: the reply to [ConfigureBridgeRunner] / [ControlBridgeRunner] / [DetachBridgeRunner].
 *  [state] is the runner's state AFTER the request (null once detached). */
@Serializable
@SerialName("pocket/bridge.runner.status")
data class BridgeRunnerStatus(
    val name: String,
    val ok: Boolean,
    val error: String? = null,
    val state: BridgeRunnerState? = null,
) : ToPhone

/**
 * daemon -> GUEST (issue #115 follow-up): this device's folder share just ended — the precise "why"
 * behind the disconnect that follows, so the guest terminal can light "Access ended · revoked"
 * instead of a bare connection drop. [reason] is [REASON_REVOKED] (the owner cut it) or
 * [REASON_EXPIRED] (the share lapsed); render unknown future values as a generic ending.
 *
 * Delivery is BEST-EFFORT: the daemon seals it right BEFORE pruning the guest credential and asking
 * the relay to force-close the socket, so it can lose that race (or the guest can simply be offline).
 * The guest must still survive on the fallback path — credential dead → auth refused on reconnect →
 * generic "access ended". An OLD app drops the unknown frame (tolerant envelope decode) and keeps
 * today's disconnect-only behavior. New fields must be TRAILING OPTIONALS (wire compat).
 */
@Serializable
@SerialName("pocket/share.ended")
data class ShareEnded(
    val reason: String = REASON_REVOKED,
    /** owner's computer name for "%s ended this share" — the guest already learned it from the invite
     *  ([ShareInvite.ownerLabel]), so this leaks nothing new. Null = generic "the owner". */
    val ownerLabel: String? = null,
) : ToPhone {
    companion object {
        const val REASON_REVOKED = "revoked"
        const val REASON_EXPIRED = "expired"
    }
}

// ── scheduled tasks (issue #137): one-shot & simple-repeat prompts the daemon fires on its own clock ──
// Wire compat, all four directions:
//  - NEW app → OLD daemon: the daemon can't decode the unknown "t" and silently DROPS the frame (its
//    inbound decodes are runCatching-wrapped; no reply ever comes) — the client arms a reply deadline
//    and shows its "update the computer's cc-pocket" state instead of a spinner.
//  - OLD app → NEW daemon: an old app never sends pocket/schedule.*, so nothing changes for it.
//  - NEW daemon → OLD app: [ScheduleState] is only ever sent in reply to a schedule request, which an
//    old app never makes; if one ever leaks, the unknown frame is dropped harmlessly (tolerant decode).
//  - OLD daemon → NEW app: never sends these — silence is the client's only signal (the deadline above).
// Management plane: NOT admitted for restricted credentials — GuestCaps/BridgeCaps are whitelists, so
// every pocket/schedule.* frame is denied-by-default for a guest/bridge (pinned by their exhaustive tests).

/**
 * How a schedule repeats. Exactly ONE of the two fields is set (both null = one-shot, expressed by
 * [ScheduleCreate.repeat] being null instead):
 *  - [intervalMs]: fixed interval from the previous planned fire time (e.g. 24h = "daily at roughly
 *    the first run's time"), floor-guarded daemon-side ([MIN_SCHEDULE_INTERVAL_MS]);
 *  - [dailyAtMinute]: every day at this minute-of-day (0..1439) in the DAEMON HOST's local timezone —
 *    the daemon is the machine that fires, so its wall clock is the one that means anything.
 */
@Serializable
data class ScheduleRepeat(
    val intervalMs: Long? = null,
    val dailyAtMinute: Int? = null,
)

/**
 * client -> daemon: create one scheduled prompt delivery (issue #137). At [runAtMs] (epoch millis,
 * the FIRST fire for a repeating schedule) the daemon injects [prompt] into the target session —
 * resuming [resumeId] under [workdir] when set (the "限额重置后自动继续" case), else starting a fresh
 * session there — through the SAME open/queue path an interactive prompt takes (mid-turn sends queue
 * into the running turn; a session live in an outside terminal refuses and the miss is recorded).
 * The reply is one [ScheduleState] carrying the full updated list. Persisted on the daemon
 * (~/.cc-pocket/schedules.json): survives restarts; a fire missed while the daemon was down runs
 * late within its grace window, else is marked missed (one-shot) / skipped forward (repeat).
 */
@Serializable
@SerialName("pocket/schedule.create")
data class ScheduleCreate(
    val workdir: String,
    val prompt: String,
    val runAtMs: Long,
    val repeat: ScheduleRepeat? = null,
    val resumeId: String? = null,
    val agent: AgentKind = AgentKind.CLAUDE,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val label: String? = null, // short display name; null = the client renders the prompt preview
    // A1 (#137): a client-chosen id. When set (and free), the daemon ADOPTS it as the new schedule's
    // id, so the client can later cancel by an id it already holds — no wait for the ScheduleState
    // reply, no fragile "reverse-lookup by label + nextRunAtMs" (which the daemon's runAtMs=maxOf(...,now)
    // clamp breaks once the reset moment is in the past). Tail-optional: an OLD daemon ignores it and
    // mints its own UUID; an OLD app never sends it.
    val clientId: String? = null,
) : ToDaemon

/** client -> daemon: list this machine's schedules. Reply: one [ScheduleState]. */
@Serializable
@SerialName("pocket/schedule.list")
data object ScheduleList : ToDaemon

/** client -> daemon: delete schedule [id] (one-shot or repeating; a settled one-shot may also be
 *  cleared this way). Unknown ids are a no-op. Reply: one [ScheduleState] (the updated list). */
@Serializable
@SerialName("pocket/schedule.cancel")
data class ScheduleCancel(val id: String) : ToDaemon

/** One schedule as the client sees it. [nextRunAtMs] is the daemon's computed next fire (null = a
 *  one-shot that already settled — [lastOutcome] says how). [lastOutcome] is "ok", "missed", or a
 *  short user-facing error from the last fire attempt; null = never fired yet. Fields beyond [id]
 *  default so the shape can grow tail-first without breaking older peers. */
@Serializable
data class ScheduleInfo(
    val id: String,
    val workdir: String = "",
    val prompt: String = "",
    val repeat: ScheduleRepeat? = null,
    val resumeId: String? = null,
    val agent: AgentKind = AgentKind.CLAUDE,
    val label: String? = null,
    val nextRunAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastOutcome: String? = null,
)

/** daemon -> client: the schedules truth — the single reply to every pocket/schedule.* request.
 *  [error] is a user-facing refusal of the request that prompted this reply (validation, bad
 *  workdir); the list still reflects the actual stored state alongside it (same contract as
 *  [PresetsState]). Never pushed unsolicited, so an old app can only ever see it by asking. */
@Serializable
@SerialName("pocket/schedule.state")
data class ScheduleState(
    val items: List<ScheduleInfo> = emptyList(),
    val error: String? = null,
) : ToPhone

/** Floor for [ScheduleRepeat.intervalMs], enforced daemon-side and mirrored by client forms so the
 *  two can't drift — a runaway sub-minute repeat would hammer sessions in a loop. */
const val MIN_SCHEDULE_INTERVAL_MS: Long = 60_000L

// ── agent model listing ─────────────────────────────────────────────────

/** client -> daemon: fetch the model list for one backend from the Mac daemon. */
@Serializable
@SerialName("pocket/models.fetch")
data class FetchModels(
    val agent: AgentKind = AgentKind.OPENCODE,
    val workdir: String? = null,
) : ToDaemon

/** daemon -> client: the model list for the requested backend.
 *  [models] is raw ids in the order the picker should present them.
 *  [error] is set when the daemon couldn't inspect the backend. */
@Serializable
@SerialName("pocket/models.list")
data class ModelsList(
    val agent: AgentKind = AgentKind.OPENCODE,
    val models: List<String> = emptyList(),
    val error: String? = null,
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

/** The [DaemonHello.protoV] from which a daemon understands headless bridge devices (issue #91).
 *  The relay replays a headless [DevicePaired] ONLY to daemons at or above this version: an older
 *  daemon has no bridges store, would file the announced key into its full-power device allow-list,
 *  and a bridge credential would silently escalate to a complete device on downgrade. */
const val PROTO_V_HEADLESS: Int = 2

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
 *  key so the relay can serve it to a phone that pairs by short code (the QR path keeps it out-of-band).
 *  [headless] (issue #91) is the AUTHORITATIVE bridge marker: the MINTING daemon knows whether it is
 *  issuing a bridge ticket, so the relay stamps the flag onto the ticket and later onto the redeemed
 *  device — never trusting the redeeming client's self-declared [PairRedeem.headless]. Old daemons omit
 *  it (mint a phone ticket, as before); old relays ignore it (the device then rides PairRedeem.headless,
 *  which is why that field remains). */
@Serializable
@SerialName("pocket/pair.begin")
data class PairBegin(val e2ePub: String, val headless: Boolean = false) : ToRelay

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
    // issue #91: an [urgent] notify (a bridge conversation needs the OWNER's approval) is pushed even
    // when an interactive device socket is live — unlike an ordinary turn-complete, the ask is NOT on
    // the data plane of whatever conversation that phone is viewing, so suppressing it would strand the
    // approval until it times out to deny. Old relays ignore the field (they gate on deviceCount==0 as
    // before → an online-but-elsewhere phone misses it, degrading to the timeout).
    val urgent: Boolean = false,
) : ToRelay

// ---- pairing redeem (REST DTOs over POST /v1/pair/redeem; not Frames) ----

/** device -> relay (HTTP body): redeem a scanned ticket, registering its X25519 static pubkey.
 *  [headless] is a LEGACY / fallback hint (issue #91): a NEW relay ignores it and derives the
 *  authoritative bridge marker from the TICKET (stamped by the minting daemon via [PairBegin.headless]),
 *  so a client that redeems a bridge ticket while lying `headless=false` cannot escape the relay-side
 *  presence/push/replay handling. The field is only consulted by an OLD relay that predates ticket-side
 *  marking. Either way the daemon enforces the capability restriction independently, anchored to the
 *  pairing ticket. */
@Serializable
data class PairRedeem(val ticket: String, val devicePubKey: String, val headless: Boolean = false)

/** relay -> device (HTTP body): the issued device credential + the account it is bound to. */
@Serializable
data class PairCredential(val deviceId: String, val credential: String, val accountId: String)

/** device -> relay (HTTP body): resolve a short pairing code typed by the user. */
@Serializable
data class PairCodeResolve(val code: String)

/** relay -> device (HTTP body): the pairing payload behind a code (relay is the one being asked). */
@Serializable
data class PairCodePayload(val accountId: String, val daemonPub: String, val ticket: String)
