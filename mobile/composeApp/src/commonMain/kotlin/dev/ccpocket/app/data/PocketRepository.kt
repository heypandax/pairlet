package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.ensureLocalNetworkAccess
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.update.VersionStatus
import dev.ccpocket.app.net.DirectE2EConnection
import dev.ccpocket.app.net.DirectUnreachableException
import dev.ccpocket.app.net.RelayAuthException
import dev.ccpocket.app.net.RelayConnection
import dev.ccpocket.app.net.RelayControlDial
import dev.ccpocket.app.net.RelayE2EConnection
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.IncomingLink
import dev.ccpocket.app.pairing.PairFailure
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.pairing.classifyPairFailure
import dev.ccpocket.app.pairing.parseIncomingLink
import dev.ccpocket.app.pairing.wireReason
import dev.ccpocket.app.push.PushTokens
import dev.ccpocket.app.lock.AppLockController
import dev.ccpocket.app.lock.createBiometrics
import dev.ccpocket.app.theme.AccentTheme
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.sameDirPath
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.Telemetry
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.BackgroundJob
import dev.ccpocket.protocol.GetWorkflowAgentDetail
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.AddWorktree
import dev.ccpocket.protocol.FetchGitStatus
import dev.ccpocket.protocol.GIT_OPS
import dev.ccpocket.protocol.GIT_OP_FETCH
import dev.ccpocket.protocol.GIT_OP_REVERT
import dev.ccpocket.protocol.GIT_OP_WORKTREE_ADD
import dev.ccpocket.protocol.GIT_OP_WORKTREE_REMOVE
import dev.ccpocket.protocol.GIT_TWO_STEP_OPS
import dev.ccpocket.protocol.GitAction
import dev.ccpocket.protocol.GitActionPreview
import dev.ccpocket.protocol.GitActionResult
import dev.ccpocket.protocol.GitDiff
import dev.ccpocket.protocol.GitStatus
import dev.ccpocket.protocol.ListWorktrees
import dev.ccpocket.protocol.ReadGitDiff
import dev.ccpocket.protocol.RemoveWorktree
import dev.ccpocket.protocol.WorktreeList
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaGet
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.BridgeCreated
import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.BridgeRevoked
import dev.ccpocket.protocol.BridgeRunnerSpec
import dev.ccpocket.protocol.BridgeRunnerStatus
import dev.ccpocket.protocol.ConfigureBridgeRunner
import dev.ccpocket.protocol.ControlBridgeRunner
import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.ListBridges
import dev.ccpocket.protocol.RevokeBridge
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.ListShares
import dev.ccpocket.protocol.RevokeShare
import dev.ccpocket.protocol.ShareCreated
import dev.ccpocket.protocol.ShareEnded
import dev.ccpocket.protocol.ShareInfo
import dev.ccpocket.protocol.ShareInvite
import dev.ccpocket.protocol.ShareListing
import dev.ccpocket.protocol.ShareRevoked
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.CancelHandoff
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorTicketCreated
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.CreateCollaboratorTicket
import dev.ccpocket.protocol.ListCollaborators
import dev.ccpocket.protocol.RemoveCollaborator
import dev.ccpocket.protocol.ActOnReviewInbox
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.CancelReviewRequest
import dev.ccpocket.protocol.CloseReviewRequest
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CreateReviewInvite
import dev.ccpocket.protocol.CreateReviewRequest
import dev.ccpocket.protocol.JoinReviewContact
import dev.ccpocket.protocol.ListReviewContacts
import dev.ccpocket.protocol.ListReviewInbox
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.PrepareReviewRequest
import dev.ccpocket.protocol.RemoveReviewContact
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewContactUpdated
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewExecutionBundle
import dev.ccpocket.protocol.ReviewInboxActed
import dev.ccpocket.protocol.ReviewInboxAction
import dev.ccpocket.protocol.ReviewInboxItem
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewInviteCreated
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewPrepared
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewRequestCreated
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.CompleteHandoff
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.RecallHandoff
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.isTerminal
import dev.ccpocket.app.pairing.toPairingInfo
import dev.ccpocket.app.pairing.toCollabPairingInfo
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.SlashCommand
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.contextWindowFor
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.ExportFile
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileContentChunk
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.FileUploadCancel
import dev.ccpocket.protocol.FileUploaded
import dev.ccpocket.protocol.MAX_UPLOAD_BYTES
import dev.ccpocket.protocol.isImageFile
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPendingApprovals
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.ListArchivedSessions
import dev.ccpocket.protocol.SetSessionArchived
import dev.ccpocket.protocol.ArchivedSessions
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.ApprovalAttentionHeartbeat
import dev.ccpocket.protocol.ApprovalGrantMutationResult
import dev.ccpocket.protocol.AuthorizedActionRecorded
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionRiskUpdated
import dev.ccpocket.protocol.RevokeGrant
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ScheduleCancel
import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleList
import dev.ccpocket.protocol.ScheduleRepeat
import dev.ccpocket.protocol.ScheduleState
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.ShellResult
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.AuthLogin
import dev.ccpocket.protocol.AuthLoginCancel
import dev.ccpocket.protocol.AuthLoginCode
import dev.ccpocket.protocol.AuthLogout
import dev.ccpocket.protocol.AuthState
import dev.ccpocket.protocol.compatibleModelForAgent
import dev.ccpocket.protocol.migrateLegacyClaudeModel
import dev.ccpocket.protocol.isModelCompatibleWithAgent
import dev.ccpocket.protocol.ActivatePreset
import dev.ccpocket.protocol.DeletePreset
import dev.ccpocket.protocol.FetchAuthStatus
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_KIMI
import dev.ccpocket.protocol.AGENT_WIRE_OPENCODE
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.FetchPresets
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.FetchSkillCatalog
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.SkillCatalog
import dev.ccpocket.protocol.PresetsState
import dev.ccpocket.protocol.SavePreset
import dev.ccpocket.protocol.Secret
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.JobStatus
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.SwitchServiceTier
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.WorkflowAgentDetail
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowUpdate
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.SetPushPrefs
import dev.ccpocket.protocol.PushPrefs
import dev.ccpocket.protocol.SetApprovalPrefs
import dev.ccpocket.protocol.ApprovalPrefs
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.GroupCreate
import dev.ccpocket.protocol.GroupRename
import dev.ccpocket.protocol.RenameSession
import dev.ccpocket.protocol.RewindDone
import dev.ccpocket.protocol.RewindMode
import dev.ccpocket.protocol.RewindPreview
import dev.ccpocket.protocol.RewindRefusal
import dev.ccpocket.protocol.RewindSession
import dev.ccpocket.protocol.GroupDelete
import dev.ccpocket.protocol.GroupAssign
import dev.ccpocket.app.isPreviewMode
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.ho_accept_declined
import dev.ccpocket.app.resources.ho_accept_expired
import dev.ccpocket.app.resources.ho_accept_no_reply
import dev.ccpocket.app.resources.ho_accept_taken
import dev.ccpocket.app.resources.ho_accept_withdrawn
import dev.ccpocket.app.resources.ho_daemon_too_old
import dev.ccpocket.app.resources.preview_cmd_title
import dev.ccpocket.app.resources.preview_cmd_note
import dev.ccpocket.app.resources.status_checking_network
import dev.ccpocket.app.resources.status_conn_lost
import dev.ccpocket.app.resources.status_connecting
import dev.ccpocket.app.resources.status_disconnected
import dev.ccpocket.app.resources.status_failed
import dev.ccpocket.app.resources.status_invalid_link
import dev.ccpocket.app.resources.status_review_invite_wrong_door
import dev.ccpocket.app.resources.status_local_denied
import dev.ccpocket.app.resources.status_pair_failed
import dev.ccpocket.app.resources.status_pairing
import dev.ccpocket.app.resources.status_reconnecting
import dev.ccpocket.app.resources.voice_audio_engine
import dev.ccpocket.app.resources.voice_daemon_unreachable
import dev.ccpocket.app.resources.voice_dictation_failed
import dev.ccpocket.app.resources.voice_interrupted
import dev.ccpocket.app.resources.voice_no_response
import dev.ccpocket.app.resources.voice_no_speech
import dev.ccpocket.app.resources.voice_record_failed
import dev.ccpocket.app.resources.voice_speech_unavailable
import dev.ccpocket.app.resources.voice_transcribe_failed
import dev.ccpocket.app.voice.AUDIO_CHUNK_B64
import dev.ccpocket.app.voice.DictationEvent
import dev.ccpocket.app.voice.DictationFail
import dev.ccpocket.app.voice.NativeDictation
import dev.ccpocket.app.voice.RecordedAudio
import dev.ccpocket.app.voice.VOICE_MAX_MS
import dev.ccpocket.app.voice.VoicePermissionDenied
import dev.ccpocket.app.voice.VoiceRecorder
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import io.ktor.client.HttpClient
import dev.ccpocket.app.media.PickedFile
import dev.ccpocket.app.media.compressImage
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** §18.1 P1-3: askId is only unique per agent connection — every client-side approval table keys on
 *  the (convoId, askId) composite, mirroring the daemon coordinator's ledger key. */
data class ApprovalKey(val convoId: String, val askId: String)

/** A local mutation is pending until the daemon explicitly confirms it. Nothing in [allowRules] is
 * removed merely because bytes were queued to a socket. */
private data class PendingGrantMutation(
    val convoId: String,
    val eventId: String? = null,
    val rule: String? = null,
    val clearAll: Boolean = false,
)

sealed interface ChatItem {
    /** [pending] = sent from this device but the daemon hasn't echoed any evidence back yet (stream
     *  chunk / tool event / turn end). Stays true while the link is down so the UI can say so —
     *  frames queue in the transport outbox indefinitely and would otherwise look "sent" (issue #41).
     *  [promptId] ties the bubble to its [dev.ccpocket.protocol.PromptAck] receipt; [delivered] flips
     *  when that ack lands — the explicit "the computer has it" marker (issue #66). */
    data class User(
        val text: String,
        /** Decoded attachment bytes. Filled on send from this device, and — since issue #254 — also
         *  from a replayed transcript, so a prompt typed AT THE COMPUTER shows its images here too
         *  (before, every replayed user row was text-only and an image-only prompt read as blank). */
        val images: List<ByteArray> = emptyList(),
        val pending: Boolean = false,
        val promptId: String? = null,
        val delivered: Boolean = false,
        /** Files uploaded to the session's workspace inbox and referenced by this turn (issue #90) —
         *  rendered as file chips with their `@` landing path. Client-side only (unlike [images],
         *  the transcript has no record of them). */
        val files: List<SentFile> = emptyList(),
        /** The replay budget shed some of this turn's images to keep the history frame under the relay
         *  cap (issue #254) — the renderers say so in place instead of showing fewer tiles silently. */
        val imagesTruncated: Boolean = false,
        /** This turn's transcript coordinates, replayed straight off [HistoryMessage] (issue #282).
         *  Present TOGETHER or not at all, and only on rows a new daemon replayed from a Claude
         *  transcript — a locally-composed bubble has none until the history comes back. Both being
         *  non-null IS the rewind capability probe: no coordinates, no rewind/fork entry, which is what
         *  keeps the affordance off older daemons and off every non-Claude backend without a version
         *  check. Never invented client-side: a guessed anchor would cut somewhere the daemon can't
         *  verify. */
        val seq: Long? = null,
        val uuid: String? = null,
    ) : ChatItem
    data class Assistant(val text: String) : ChatItem

    /** Extended reasoning, rendered as a collapsible row. [seconds] lands when thinking finishes (null while streaming). */
    data class Thinking(val text: String, val seconds: Int? = null) : ChatItem
    /** A tool card. The sub-agent (Task/Agent) fields (issue #77) light up only with a new daemon:
     *  [taskId] correlates the card with its later RESULT/progress events; [ok] is the run's outcome
     *  (null = running or unknown); [output] the sub-agent's final report (expandable);
     *  [childCount]/[lastChild] summarize the inner tool calls folded into this card. */
    data class Tool(
        val tool: String,
        val preview: String,
        val taskId: String? = null,
        val ok: Boolean? = null,
        val output: String? = null,
        val childCount: Int = 0,
        val lastChild: String? = null,
        /** A replayed Workflow card's run id (issue #106) — binds it to [PocketRepository.workflowRuns].
         *  Live cards bind via [taskId] == the run's originating tool_use id instead. */
        val workflowRunId: String? = null,
    ) : ChatItem
    data class Sys(val text: String) : ChatItem
    data class RuleChip(val rule: String) : ChatItem // "Always allowing X this session" confirmation

    /** Approval design M2 §9.6: an action auto-ran under a task/session grant — the in-stream audit
     *  chip ("自动执行 · <rule> · 依据"). [grantId] non-null enables "收紧" (RevokeGrant); a
     *  session-rule hit tightens via ClearAllowRule([summary]) instead. Daemon-redacted summary only. */
    data class AutoRun(
        val eventId: String,
        val summary: String,
        val basis: String,       // "task-grant" | "session-rule" | tolerant future values
        val tool: String? = null,
        val grantId: String? = null,
        val at: Long = 0L,
        val tightening: Boolean = false,
        val tightened: Boolean = false,
    ) : ChatItem

    /** The compact transcript row left behind after answering an AskUserQuestion card:
     *  (question → answer) pairs; a freeform reply is a single ("" → response) pair. */
    data class QuestionsAnswered(val items: List<Pair<String, String>>) : ChatItem

    /** Claude withdrew its questions (control_cancel) — muted one-liner where the card used to be. */
    data object QuestionsWithdrawn : ChatItem

    /** OpenCode's `question` tool surfaced as a READ-ONLY question card (issue #210, phase 1): the
     *  parsed questions + options, rendered like the AskUserQuestion card but non-interactive with a
     *  "作答暂不支持" note. OpenCode runs fully automatic — there is no answer channel yet, so this
     *  replaces the raw JSON tool row without pretending it can be answered. */
    data class OpenCodeQuestion(val questions: List<dev.ccpocket.protocol.AskQuestion>) : ChatItem

    /** A live turn finished here — muted "✓ done · 42s" divider so turn boundaries stay visible after
     *  the streaming caret stops. Appended on TurnDone only, never present in replayed history. */
    data class TurnEnded(val seconds: Int? = null) : ChatItem
}

enum class ImgState { Compressing, Ready, Rejected }

/** One quick-terminal command and its [ShellResult] (null while awaiting approval/result). Issue #3. */
data class TerminalEntry(val command: String, val result: ShellResult? = null)

/** A refused session rename (issue #158): [sessionId] is the row that asked, [message] the daemon's
 *  reason — surfaced on the sessions list (the asking row), never in a chat transcript. */
data class RenameRefusal(val sessionId: String, val message: String)

/**
 * A localizable status line: the UI resolves [res] (and substitutes [arg] for %1$s when present).
 * Keeping the resource key — not resolved text — in state means the line re-renders in the right
 * language even though it was set outside composition.
 */
data class StatusMsg(val res: StringResource, val arg: String? = null)

/**
 * The single source of truth for what the connection UI shows. Driven by REAL transport/relay events
 * (Attached, PeerPresence, the first Directories reply, AuthError) — never set optimistically.
 *  - [Connecting] first attempt (skeleton during a short grace window)
 *  - [Reconnecting] was Ready, link dropped — keep the old list under a slim banner
 *  - [RelayUnreachable] never reached the relay within the grace window — "can't reach server"
 *  - [ComputerOffline] relay reached (Attached) but the daemon is offline (PeerPresence=false)
 *  - [PairingInvalid] relay rejected our credential (AuthError) — re-pair
 *  - [Ready] Attached + daemon online + Directories received
 */
enum class ConnPhase { Connecting, Reconnecting, RelayUnreachable, ComputerOffline, PairingInvalid, Ready }

/** A photo staged in the composer: [bytes] are the current JPEG for the thumbnail; [state] drives the tray UI. */
class PendingImage(val id: Long, val bytes: ByteArray, val state: ImgState)

/** Upload lifecycle of a staged file (issue #90). Files upload BEFORE send — the bytes land in the
 *  session's workspace inbox and the send merely references the landed path — unlike photos, which
 *  ride inline in the prompt frame. One file uploads at a time; the rest wait [Queued]. */
enum class FileUpState { Queued, Uploading, Landed, Failed }

/**
 * A file staged in the composer (issue #90). [bytes] are retained until landed so a retry can
 * re-stream (cleared on land to release memory). [captureId] is minted fresh per attempt;
 * [path]/[landedName] arrive with the daemon's [dev.ccpocket.protocol.FileUploaded] receipt —
 * the path is what the sent prompt references as an `@`-token.
 */
data class PendingFile(
    val id: Long,
    val name: String,
    val size: Long,
    val bytes: ByteArray,
    val mediaType: String,
    val state: FileUpState,
    val progress: Float = 0f,
    val captureId: String? = null,
    val path: String? = null,
    val landedName: String? = null,
    val error: String? = null,
    // A local playback handle for a picked video (issue #98) — survives the byte-eviction on land so a
    // just-sent video's card can play it back on this device; null for non-video / where the platform
    // picker had no stable URI. Never uploaded — client-side only.
    val localUri: String? = null,
)

/**
 * A file that already landed in the workspace inbox, as referenced by a sent turn
 * ([ChatItem.User.files]). [mediaType] routes the render — a `video/` MIME draws the video card instead
 * of the file chip (issue #98); it defaults to "" so older call sites keep the chip. [durationSecs] fills
 * the duration pill when known (null → the pill is omitted; v1 never probes it client-side).
 * [localUri] is a platform playback handle for the freshly-picked video on THIS device (the card is
 * client-side + ephemeral, so it only ever exists in the session that picked it) — null after the
 * bytes are gone / on any other viewer, which the player degrades to "open it on the computer".
 */
data class SentFile(
    val name: String,
    val size: Long,
    val path: String,
    val mediaType: String = "",
    val durationSecs: Int? = null,
    val localUri: String? = null,
)

/** A connect that never reached Attached within the deadline (silent pre-attach hang). Surfaced as a
 *  normal failure so the backoff reconnect kicks in — NOT a CancellationException (which would read as
 *  an intentional teardown and skip the retry). */
class ConnectWedgedException : Exception("connect wedged: no attach within timeout")

/**
 * State hub: consumes inbound [Frame]s into observable Compose state, exposes user actions.
 *
 * [pinnedTo] makes this a fleet SATELLITE: an instance bound to one specific computer, running the same
 * battle-tested connection stack (reconnect/backoff/heartbeat/watchdog) but never reading or writing the
 * global active binding — the [FleetCoordinator] keeps exactly one such link per non-active binding so
 * every paired machine stays live at once. Satellites are passive data links: the UI never routes pairing,
 * switching, settings writes, or session-opening through them today, and they skip push registration
 * (the platform push singleton stays owned by the primary until the per-machine policy work).
 */
class PocketRepository(private val scope: CoroutineScope, private val pinnedTo: PairedDaemon? = null) {
    private val direct = RelayConnection()
    private val relay = RelayE2EConnection()
    private val directE2E = DirectE2EConnection()
    internal var useRelay = false // internal for tests (mirrors promptReceiptTimeoutMs)
    // direct-first routing: a failed direct attempt silently falls back to the relay and cools down,
    // so a dead stored address costs one 3s probe per minute, not one per reconnect tick. Both maps are
    // per ACCOUNT — this repo switches bindings, and machine A's failed probe must not gate machine B's.
    private val directCooldownUntil = HashMap<String, Long>()
    // addresses that ANSWERED the handshake with the wrong daemon key (a remote daemon advertising its
    // own 127.0.0.1 reaches a DIFFERENT local daemon here) — dead for this binding, don't re-dial or
    // re-persist them; the daemon re-teaches a good address via DaemonInfo if it ever gets one
    private val badDirectUrl = HashMap<String, String>()
    private var directAttemptInFlight = false
    private var firstTicket: String? = null // pairing ticket, used as PSK on the first relay connect only
    private var lastDirectUrl: String? = null
    private var inboundJob: Job? = null     // persistent collector over the transport's inbound flow
    private var connectJob: Job? = null     // the socket loop; returns/throws when the link dies
    private var retryJob: Job? = null       // scheduled auto-reconnect
    internal var retryAttempts = 0          // internal for tests (#144 — the backoff-ladder reset rule)
    private var controlJob: Job? = null     // collects relay control frames (Attached/PeerPresence/AuthError)
    private var deafJob: Job? = null        // #146: collects the E2E transports' deaf-link signals (mid-turn force re-handshake)
    private var graceJob: Job? = null       // silent window before showing RelayUnreachable
    private var listWaitJob: Job? = null    // post-attach wait for the first list before assuming the computer is offline
    private var connectWatchdog: Job? = null // forces a retry if a connect wedges pre-attach (no socket error)
    private var listWaitRetried = false     // one deaf-link re-handshake per episode (see startListWait); reset on Ready
    private var lastTransportLaunchAt = 0L  // #143: reconnect triggers inside the coalesce window merge into the in-flight attempt
    internal var transportLaunches = 0      // test seam: counts real (non-coalesced) launchTransport runs
    private var linkStableJob: Job? = null  // #144: clears the retry-backoff ladder only once the link stays up stableLinkResetMs
    internal var stableLinkResetMs = STABLE_LINK_RESET_MS // test seam
    private var presenceProbeJob: Job? = null // #145: healthy-link re-sync probe armed by a daemon-comeback presence edge
    internal var presenceProbeMs = LIST_WAIT_MS           // test seam
    internal var linkHealthOverride: (() -> Boolean)? = null // test seam for transportHealthy()
    private var directoriesRev = 0          // bumped on every Directories reply — the #145 probe's "did the computer answer" check
    private var handoffListingRev = 0       // the inbox-mode counterpart: bumped on every HandoffListing reply
    // per-session connection bookkeeping (plain vars; [phase]/[directoriesLoaded] hold the observable truth)
    private var attachedThisSession = false // relay Attached seen (or, direct mode, socket + first Directories)
    private var daemonOffline = false       // explicit: got PeerPresence(false), or the post-attach list-wait elapsed
    private var pairingInvalid = false      // relay AuthError -> needs re-pair, never auto-retry
    private var hadReadyThisSession = false // reached Ready at least once -> a later drop shows Reconnecting
    private var relayDeadlinePassed = false // grace elapsed without attaching -> RelayUnreachable
    private var reconnectGraceJob: Job? = null // brief hold before showing the Reconnecting banner on a blip (#28)
    private var reconnectGracePassed = false   // that hold elapsed -> the Reconnecting banner may show
    // a ConvoHistory was just merged (issue #107): the very next stream event may be the block the
    // replay's disk read already caught (chunks parsed during the read race its ConvoHistory on the
    // wire) — one-shot flag; consumed by the first AssistantChunk/ToolEvent, reset at turn boundaries
    private var replayEcho = false

    // ── push notifications: register the device's APNs/FCM token so the relay can wake it while offline ──
    private var pushToken: PushToken? = null
    private var pushStarted = false
    private var pushRegistered: Pair<String, String>? = null // last (platform, token) sent; skip redundant re-sends
    private var pushDialJob: Job? = null // in-flight one-shot relay dial (direct-LAN registration compensation)
    private var pushTokenJob: Job? = null // observes the shared platform token (see PushTokens)
    // direct-LAN registration seams (internal for tests, mirroring promptReceiptTimeoutMs). directLinkUp
    // includes the in-flight direct attempt: a RegisterPush buffered into the relay control outbox during
    // that ≤3s window would otherwise sit undrained for as long as the phone stays on the LAN, then flush
    // a STALE token over a newer one at the next real relay attach.
    internal var directLinkUp: () -> Boolean = { directE2E.connected || directAttemptInFlight }
    internal var pushDial: suspend (PairedDaemon, RegisterPush) -> Unit = { p, f -> RelayControlDial.deposit(p, f) }
    internal var pushDialRetryMs = 30_000L
    /** Task-complete push toggle (persisted, default on); the single source of truth the Settings switch binds to. */
    val notificationsOn = mutableStateOf(SecureStore.getString(K_NOTIFY) != "0")

    /** Persisted default execution mode (Settings binds to it; the new-session picker pre-selects it).
     *  Applies to new sessions AND resumes (issue #50) — a resumed session no longer revives its old mode. */
    val defaultMode = mutableStateOf(
        SecureStore.getString(K_DEFAULT_MODE)?.let { s -> PermissionMode.entries.firstOrNull { it.name == s } } ?: PermissionMode.DEFAULT,
    )
    /** Backend-native companion to [defaultMode]. `auto` is valid only for Claude and keeps DEFAULT as
     *  its legacy fallback/security rank. */
    val defaultPermissionMode = mutableStateOf(
        SecureStore.getString(K_DEFAULT_PERMISSION_MODE)?.takeIf { it == CLAUDE_PERMISSION_MODE_AUTO },
    )

    /** Persisted default reasoning effort for NEW Claude sessions (null = the model's own default). Resumed
     *  sessions keep their own. Before per-agent defaults this key was shared by every backend; construction
     *  migrates that historical value to each new scoped key, then this original key remains Claude's source
     *  of truth. Stored as "" for the null/default choice (SecureStore can't hold null). */
    val defaultEffort = mutableStateOf(SecureStore.getString(K_DEFAULT_EFFORT)?.takeIf { it.isNotEmpty() })
    /** Every non-Claude backend owns a separate effort value, just like its model. Kept private so old call
     *  sites that bind [defaultEffort] continue to mean Claude until they explicitly become agent-aware. */
    private val defaultCodexEffort = mutableStateOf(loadScopedDefaultEffort(K_DEFAULT_CODEX_EFFORT))
    private val defaultOpenCodeEffort = mutableStateOf(loadScopedDefaultEffort(K_DEFAULT_OPENCODE_EFFORT))
    private val defaultKimiEffort = mutableStateOf(loadScopedDefaultEffort(K_DEFAULT_KIMI_EFFORT))
    private val defaultZCodeEffort = mutableStateOf(loadScopedDefaultEffort(K_DEFAULT_ZCODE_EFFORT))
    private val defaultDshEffort = mutableStateOf(loadScopedDefaultEffort(K_DEFAULT_DSH_EFFORT))

    /**
     * One-time compatibility migration from the build where every backend read the same effort preference.
     * Each absent scoped key copies that value regardless of which agent happened to be selected at upgrade:
     * changing the selected agent did not change the old preference's scope. Absence and an explicit empty
     * value remain intentionally different: only an ABSENT scoped key may copy the legacy value; `""` means
     * the user explicitly chose the CLI default and must never be repopulated. The legacy key remains untouched
     * because it becomes Claude's source of truth after the split.
     */
    private fun loadScopedDefaultEffort(key: String): String? {
        SecureStore.getString(key)?.let { return it.takeIf(String::isNotEmpty) }
        // Key absence is the one-shot migration gate. Close it during THIS construction even when there is
        // nothing to copy: otherwise a user who starts this build on Claude, later switches to Codex, and
        // relaunches would make the then-current Claude value look like pre-split Codex history.
        val migrated = SecureStore.getString(K_DEFAULT_EFFORT)
        SecureStore.putString(key, migrated ?: "")
        return migrated?.takeIf(String::isNotEmpty)
    }

    fun defaultEffortFor(agent: AgentKind): String? = when (agent) {
        AgentKind.CLAUDE -> defaultEffort.value
        AgentKind.CODEX -> defaultCodexEffort.value
        AgentKind.OPENCODE -> defaultOpenCodeEffort.value
        AgentKind.KIMI -> defaultKimiEffort.value
        AgentKind.ZCODE -> defaultZCodeEffort.value
        AgentKind.DSH -> defaultDshEffort.value
    }
    /** Default Codex service tier for new sessions (`priority` = Fast); null follows the account default. */
    val defaultServiceTier = mutableStateOf(SecureStore.getString(K_DEFAULT_SERVICE_TIER)?.takeIf { it.isNotEmpty() })

    /** Persisted default model for NEW Claude sessions (null = the CLI's own default). Kept on the original
     *  storage key so existing installs retain their choice when per-agent defaults are introduced. */
    val defaultModel = mutableStateOf(SecureStore.getString(K_DEFAULT_MODEL)?.takeIf { it.isNotEmpty() })

    /** Backend-scoped defaults: a Claude alias must never leak into Codex/OpenCode, and switching the default
     *  agent in Settings must not erase the choice the user made for another backend. */
    private val defaultCodexModel = mutableStateOf(SecureStore.getString(K_DEFAULT_CODEX_MODEL)?.takeIf { it.isNotEmpty() })
    private val defaultOpenCodeModel = mutableStateOf(SecureStore.getString(K_DEFAULT_OPENCODE_MODEL)?.takeIf { it.isNotEmpty() })
    private val defaultKimiModel = mutableStateOf(SecureStore.getString(K_DEFAULT_KIMI_MODEL)?.takeIf { it.isNotEmpty() })
    private val defaultZCodeModel = mutableStateOf(SecureStore.getString(K_DEFAULT_ZCODE_MODEL)?.takeIf { it.isNotEmpty() })
    /** DSH (issue #255) has no model switching in v1, so this stays null in practice. It exists anyway so the
     *  scoped-storage invariant holds structurally: nothing can fall through to another backend's key. */
    private val defaultDshModel = mutableStateOf(SecureStore.getString(K_DEFAULT_DSH_MODEL)?.takeIf { it.isNotEmpty() })

    fun defaultModelFor(agent: AgentKind): String? = when (agent) {
        // legacy persisted bare "opus" follows the Opus row to Opus 5. Official endpoint only: on a
        // gateway the alias IS the contract (#167 — vendors map it onto their own tiers).
        AgentKind.CLAUDE -> defaultModel.value.let { if (gatewayBaseUrl.value == null) migrateLegacyClaudeModel(it) else it }
        AgentKind.CODEX -> defaultCodexModel.value
        AgentKind.OPENCODE -> defaultOpenCodeModel.value
        AgentKind.KIMI -> defaultKimiModel.value
        AgentKind.ZCODE -> defaultZCodeModel.value
        AgentKind.DSH -> defaultDshModel.value
    }

    /** Persisted context-window override (tokens) used as the usage statusline's denominator, or null to follow
     *  the model-derived / daemon-reported window. Exists because the CLI never reports a CUSTOM model's real
     *  window, so [contextWindowFor] falls back to 200k and the % reads wrong (issue #60). Applied AHEAD of the
     *  daemon's SessionLive.contextWindow, which for Claude is never null.
     *
     *  FALLBACK TIER (issue #169): this is one value for EVERY model, which is wrong on its face — a window is a
     *  property of the model, not of the phone. Run a 256k gateway model and an official 200k Sonnet side by side
     *  and one of them is always measured against the other's denominator. [contextWindowOverrides] is the
     *  per-model answer and wins where it has an entry; this stays as the catch-all so the value existing users
     *  already typed keeps working untouched (no migration, no silent loss of a setting they can't see move). */
    val contextWindowOverride = mutableStateOf(SecureStore.getString(K_CONTEXT_WINDOW_OVERRIDE)?.toLongOrNull())

    /** Persisted PER-MODEL context-window overrides: normalized model id → tokens (issue #169). Keyed the same way
     *  [contextWindowFor] normalizes (trim + lowercase) so "DeepSeek-Chat" and "deepseek-chat" are one entry.
     *  Beats [contextWindowOverride]; absent entry falls through to it. */
    val contextWindowOverrides = mutableStateMapOf<String, Long>().also { m ->
        SecureStore.getString(K_CONTEXT_WINDOW_OVERRIDES).orEmpty().lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach // blank line or malformed — skip rather than poison the whole map
            val id = modelKey(line.substring(0, tab)) ?: return@forEach
            val tokens = line.substring(tab + 1).toLongOrNull()?.takeIf { it > 0 } ?: return@forEach
            m[id] = tokens
        }
    }

    /** Normalizer for [contextWindowOverrides] keys. Mirrors how [contextWindowFor] folds an id (trim + lowercase)
     *  so the override table and the window table agree on what counts as "the same model". Blank → null (no key). */
    private fun modelKey(model: String?): String? = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Public view of [modelKey] for UI that has to line a live model up against a table row (#171): Settings marks
     *  which row is currently in force, Session Info picks "set" vs "edit". One folding rule, one definition. */
    fun contextWindowKeyOf(model: String?): String? = modelKey(model)

    /** The denominator the user pinned for [model], most-specific first: a per-model entry beats the legacy
     *  catch-all. Null = no override, follow the daemon/model-derived window.
     *
     *  The catch-all YIELDS for a model whose window we positively know — [contextWindowFor] returning the large
     *  window is evidence (a `[1m]` marker, the known-1M table, an alias), never a fallback guess. That tier was
     *  typed for the gateway model whose window nobody can derive; letting it also answer for a native-1M Claude
     *  made an 852k / 1M Opus 5 session read "ctx 426%" — and because an override is exempt from the
     *  proven-usage upgrade, the occupancy that disproved the 200k could not correct it either. A per-model row
     *  still wins over everything: that one IS a statement about this model. */
    fun contextWindowOverrideFor(model: String?): Long? {
        modelKey(model)?.let { contextWindowOverrides[it] }?.let { return it }
        return contextWindowOverride.value?.takeIf { contextWindowFor(model) != LARGE_CONTEXT_WINDOW }
    }

    /** Persisted default agent backend for NEW sessions (Claude unless the user switched to Codex). Resumed
     *  sessions keep their own backend (the picker only seeds new ones). */
    val defaultAgent = mutableStateOf(
        SecureStore.getString(K_DEFAULT_AGENT)?.let { s -> AgentKind.entries.firstOrNull { it.name == s } } ?: AgentKind.CLAUDE,
    )

    /** Project + session agent filter: the SET of backends whose rows are shown; the full set = no filter
     *  (persisted, migrating the pre-#248 single value — see [parseAgentFilter]). Session rows were covered
     *  by #31; project rows consume DirectoryEntry.sessionAgents as of issue #188. */
    val agentFilter = mutableStateOf(parseAgentFilter(SecureStore.getString(K_AGENT_FILTER)))

    /** Projects screen: tree (drill-down) vs flat. Persisted (default tree). */
    val treeView = mutableStateOf(SecureStore.getString(K_VIEW_MODE) != "flat")

    /** Chat text scale (FONT_SCALE_MIN..MAX), persisted. 1.0 = the design's default sizes; bumped for eye comfort
     *  on small screens (issue #8). Threaded into every message via LocalFontScale. */
    val fontScale = mutableStateOf(
        SecureStore.getString(K_FONT_SCALE)?.toFloatOrNull()?.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX) ?: 1f,
    )

    /** Appearance: follow the system, or force light/dark (issue #63). Persisted; passed straight to
     *  PocketTheme(mode = …), which resolves SYSTEM against isSystemInDarkTheme() at the app root. */
    val themeMode = mutableStateOf(ThemeMode.from(SecureStore.getString(K_THEME_MODE)))
    fun setThemeMode(mode: ThemeMode) {
        if (mode == themeMode.value) return
        themeMode.value = mode
        SecureStore.putString(K_THEME_MODE, mode.name)
    }

    /** Global accent source (issue #204): POCKET terracotta (default) or Codex teal. Persisted; passed to
     *  PocketTheme(accent = …) at the app root, which points Tok.accent at the chosen hue. */
    val accentTheme = mutableStateOf(AccentTheme.from(SecureStore.getString(K_ACCENT_THEME)))
    fun setAccentTheme(theme: AccentTheme) {
        if (theme == accentTheme.value) return
        accentTheme.value = theme
        SecureStore.putString(K_ACCENT_THEME, theme.name)
    }

    // Voice engine choice: route captures to the computer's whisper instead of on-device dictation —
    // whisper handles mixed-language speech (zh + embedded English terms) far better than the native
    // recognizer, at the cost of the live word-by-word transcript.
    val voiceWhisper = mutableStateOf(SecureStore.getString(K_VOICE_ENGINE) == "whisper")
    fun setVoiceWhisper(on: Boolean) {
        if (on == voiceWhisper.value) return
        voiceWhisper.value = on
        SecureStore.putString(K_VOICE_ENGINE, if (on) "whisper" else "")
    }

    /** App Lock (issue #109): the biometric gate state machine + its persisted enable/auto-lock prefs. Lazy so
     *  the desktop root and the repo unit tests — neither of which mounts the gate — never build the platform
     *  prompt (createBiometrics()); it is constructed the first time App() reads it on Android/iOS. */
    val appLock: AppLockController by lazy { AppLockController(scope, createBiometrics()) }

    /** Projects the user pinned to the top, newest pin first. Persisted client-side (paths never contain
     *  '\n', so a newline-joined string is a safe, dependency-free encoding). */
    val pinnedPaths = mutableStateListOf<String>().also { list ->
        SecureStore.getString(K_PINNED)?.split('\n')?.filter { it.isNotBlank() }?.let(list::addAll)
    }

    fun isPinned(path: String) = path in pinnedPaths

    /** Toggle a project's pinned state (most-recent pin first) and persist. */
    fun togglePin(path: String) {
        if (!pinnedPaths.remove(path)) pinnedPaths.add(0, path)
        SecureStore.putString(K_PINNED, pinnedPaths.joinToString("\n"))
    }

    // ── cross-project working set (issue #165) ───────────────────────────────────────────────────────
    // The switcher has to name sessions whose PROJECT isn't loaded right now, so "what was I just in" is
    // remembered locally (the daemon's list only knows what is alive). Same client-side, dependency-free
    // storage the pins use; all the rules live in SessionWorkingSet.kt as pure functions.

    /**
     * Sessions this device opened, newest first, capped at [WORKING_SET_MAX]. Persisted PER COMPUTER: a row
     * names its session by path + id on one machine, so carrying the list across a machine switch would
     * offer rows that open nothing there. Loaded by [loadWorkingSet] once [paired] exists (this property is
     * declared before it) and again on every switch.
     */
    private val workingSetMru = mutableStateListOf<WorkingSetEntry>()

    private fun workingSetKey(accountId: String? = paired.value?.accountId) = accountId?.let { K_WORKING_SET_PREFIX + it }

    /** (Re)point the switcher's memory at [accountId]'s sessions. */
    private fun loadWorkingSet(accountId: String? = paired.value?.accountId) = replace(
        workingSetMru,
        workingSetKey(accountId)?.let { decodeWorkingSet(SecureStore.getString(it)) } ?: emptyList(),
    )

    /** Sessions whose real work (`executing || busy`) settled while the user was looking at something
     *  ELSE. Cleared per-session only after an authoritative open; per-machine and in-memory by design. */
    val unseenSessions = mutableStateOf<Set<String>>(emptySet())

    /** Session ids the LAST project list reported as actually working — the work→idle edge detector. */
    private var lastWorkingSessions: Set<String> = emptySet()
    /** Directory for each prior working id, retained across the edge so the visible project can re-list. */
    private var lastWorkingDirectories: Map<String, String> = emptyMap()

    /** The session identity the chat is actually showing, or null when no chat is open. [sessionKey] alone
     *  can't say this: it deliberately survives [backToBrowse] as the draft key, and a switcher opened from
     *  the project list must count that session as "other", not as "current". */
    private fun openSessionId(): String? = sessionKey.value?.takeIf { convoId.value != null }

    /**
     * The switcher's read-model (issue #165). Snapshot-observable end to end — it reads [directories], the
     * MRU, the unseen marks and the current session's identity, so any Compose surface re-derives when the
     * daemon re-lists, a session is opened, or a turn finishes elsewhere.
     */
    fun workingSet(): SessionWorkingSet = buildWorkingSet(
        running = runningSessions(directories.toList()),
        mru = workingSetMru.toList(),
        currentSessionId = openSessionId(),
        currentDirKey = workdir.value,
        currentTitle = chatTitle.value,
        unseen = unseenSessions.value,
        // approvals stays empty by construction today: the daemon binds an ask to the connection that
        // opened its conversation, so this client only ever holds the CURRENT session's ask (see
        // fleetAttention). The seam is here for when asks go account-wide — no other change needed.
    )

    /** Current work for display from the latest directory snapshot. This intentionally includes the
     * terminal-Claude mtime heuristic so its existing Running affordance remains visible; only
     * [noteWorkingSessions] uses the stricter authoritative subset to infer completion edges. */
    fun currentlyWorkingSessionIds(): Set<String> =
        runningSessions(directories.toList()).filterTo(mutableListOf()) { it.executing }
            .mapTo(mutableSetOf()) { it.sessionId }

    /**
     * Record an open at the head of the MRU and clear that session's unseen mark. Called from the daemon's
     * [SessionLive] announce (authoritative identity), from the first prompt of a brand-new session (which
     * is when its title finally exists), and optimistically by [switchToSession] so the sheet re-orders on
     * the tap rather than a round-trip later. The optimistic touch passes [markSeen]=false: a failed open
     * must not erase a result the user never saw. Idempotent — a re-touch just refreshes labels.
     */
    internal fun rememberOpenedSession(
        dirKey: String?,
        sessionId: String?,
        title: String?,
        agent: AgentKind?,
        markSeen: Boolean = true,
    ) {
        if (dirKey.isNullOrBlank() || sessionId.isNullOrBlank()) return
        val known = workingSetMru.firstOrNull { it.sessionId == sessionId }
        val project = directories.firstOrNull { it.path == dirKey }?.name?.takeIf { it.isNotBlank() }
            ?: known?.project?.takeIf { it.isNotBlank() }
            ?: projectLabelOf(dirKey)
        val label = title?.takeIf { it.isNotBlank() } ?: known?.title?.takeIf { it.isNotBlank() } ?: project
        val entry = WorkingSetEntry(dirKey, sessionId, label, project, epochMillis(), agent ?: known?.agent)
        replace(workingSetMru, mruTouch(workingSetMru.toList(), entry))
        // the demo walks fake projects — it may drive the switcher, but it must never write them into
        // the real store (same rule the rest of demo mode follows: no persistence, no network)
        if (!demoMode.value) workingSetKey()?.let { SecureStore.putString(it, encodeWorkingSet(workingSetMru.toList())) }
        if (markSeen && sessionId in unseenSessions.value) unseenSessions.value = unseenSessions.value - sessionId
    }

    /** Fold a fresh project list into the finished-while-away marks. Only daemon-reported work
     *  (`executing || busy`) participates; a merely live/idle process is not a running task. Marks are
     *  retained even for sessions never opened on this client so a later Sessions list can surface them.
     *  The set is process-local and reset on disconnect, so this does not create a durable notification log. */
    private fun noteWorkingSessions() {
        val observedRows = runningSessions(directories.toList())
        val nowRows = observedRows.filter { it.executing && it.workStateAuthoritative }
        val now = nowRows.map { it.sessionId }.toSet()
        val settled = observedRows.filterTo(mutableListOf()) { !it.executing && it.workStateAuthoritative }
            .mapTo(mutableSetOf()) { it.sessionId }
        val before = unseenSessions.value
        val marked = markFinishedAway(lastWorkingSessions, settled, openSessionId(), unseenSessions.value)
        if (marked != before) unseenSessions.value = marked
        val newlyFinished = marked - before
        val listedDir = sessionsDir.value
        if (listedDir != null && newlyFinished.any { sameDirPath(lastWorkingDirectories[it], listedDir) }) {
            // A session started on another client may not exist in our stale Sessions snapshot yet.
            // Refresh exactly on a completion edge so its NEW_RESULT row becomes renderable immediately.
            scope.launch { runCatching { send(ListSessions(listedDir)) } }
        }
        lastWorkingSessions = now
        lastWorkingDirectories = nowRows.associate { it.sessionId to it.dirKey }
    }

    /** The current surface has already shown this session settling, so a delayed directory snapshot must
     * not relabel it as unseen after a quick Back. If background work is still live, its next snapshot
     * re-enters the baseline and a later away-from-screen completion remains discoverable. */
    private fun noteCurrentSettledSeen(sessionId: String?) {
        sessionId ?: return
        lastWorkingSessions = lastWorkingSessions - sessionId
        lastWorkingDirectories = lastWorkingDirectories - sessionId
    }

    /** Composer draft persisted per conversation. Keyed most-durable-first: the real sessionId (stable across
     *  daemon reopens AND app restarts) → convoId (daemon-run-scoped) → workdir. #29 fixed the cross-session
     *  bleed with convoId keying, but convoIds are minted per open, so a draft rarely survived leave-and-reopen;
     *  [sessionKey] restores that durability. Blank/sending clears it. */
    fun draftFor(key: String?): String = key?.let { SecureStore.getString(K_DRAFT_PREFIX + it) } ?: ""

    fun saveDraft(key: String?, text: String) {
        key ?: return
        if (text.isBlank()) SecureStore.remove(K_DRAFT_PREFIX + key) else SecureStore.putString(K_DRAFT_PREFIX + key, text)
    }

    fun clearDraft(key: String?) { key?.let { SecureStore.remove(K_DRAFT_PREFIX + it) } }

    /** The active session's durable draft key (its real sessionId when known). Set optimistically from the
     *  resumeId on open; corrected by SessionLive. Null for a not-yet-materialized brand-new session. */
    val sessionKey = mutableStateOf<String?>(null)

    /** THE draft-key derivation — mobile App and the desktop model both key their composers off this;
     *  a private copy at either call site would silently fork draft storage when a key tier is added. */
    fun composerKey(): String? = sessionKey.value ?: convoId.value ?: workdir.value

    /** Composer CONTEXT generation — bumped once per [openSession]. [composerKey]'s chain flips IN PLACE
     *  while the user may be typing (a brand-new session's first SessionLive minting convoId/sessionId,
     *  a forked resume/lock-heal corrected from resumeId to the real id), so composers must key their
     *  LIVE text off this and only re-home the draft on a key flip: re-initializing from the persisted
     *  draft on every flip rolled the field back to a ≤400ms-stale snapshot mid-IME-composition, which
     *  on iOS committed the pinyin keyboard's space-segmented marked text as raw letters (#108/#93). */
    val composerEpoch = mutableStateOf(0)

    /** Carry a mid-typing draft onto [to] BEFORE the composer re-keys (a brand-new session's first init
     *  flips the key from convoId to the freshly-minted sessionId while the user may be typing). */
    private fun migrateDraft(to: String?) {
        to ?: return
        val from = composerKey() ?: return
        if (from == to) return
        val text = draftFor(from)
        if (text.isNotBlank() && draftFor(to).isBlank()) { saveDraft(to, text); clearDraft(from) }
    }

    /** Current tree drill-down path (null = root). Hoisted here (not screen-local) so it survives opening a
     *  session and returning — DirectoryScreen leaves the composition on that navigation. Not persisted. */
    val browsePath = mutableStateOf<String?>(null)

    /** A session a tapped push asked to open, held until the link is Ready (see [requestOpenSession]). */
    private var pendingOpen: dev.ccpocket.app.SessionRoute? = null

    /**
     * True from a successful explicit connect until the user disconnects/unpairs. While true, a dead
     * transport does NOT route back to the Connect screen — the UI stays put, shows a slim banner,
     * and the repo reconnects (backoff timer + app-foreground trigger).
     */
    val sessionActive = mutableStateOf(false)
    val connected = mutableStateOf(false)
    /** Monotonic count of [Attached] edges — every genuine (re)attach to the active daemon. The desktop
     *  Account pane keys its one-shot auth/presets fetch on this so a pane left open across a daemon restart
     *  re-fetches instead of stranding the pre-restart account (or a transient "claude CLI not found" captured
     *  mid-restart) until the user closes and reopens it. Note [connected] alone can't drive this: a daemon
     *  restart detected via relay PeerPresence re-handshakes without ever flipping [connected] false→true.
     *  Bumps only on the real attach edge, which the reconnect backoff ladder already rate-limits, so a
     *  flapping link can't spin the fetch. */
    val connGen = mutableStateOf(0)
    /** Single source of truth for the connection-state UI (see [ConnPhase]); driven by real events. */
    val phase = mutableStateOf(ConnPhase.Connecting)
    val status = mutableStateOf(StatusMsg(Res.string.status_disconnected))
    /** The active binding the transport talks to. Stays a single value so all transport code is unchanged. */
    val paired = mutableStateOf<PairedDaemon?>(pinnedTo ?: Pairing.active())
    /** Every bound computer (observable mirror of [Pairing.loadAll]); drives the device picker + settings list. */
    val pairedList = mutableStateListOf<PairedDaemon>().also { it.addAll(Pairing.loadAll()) }
    /** Pair-another-computer mode: routes to PairingScreen even though bindings already exist. */
    val addingDevice = mutableStateOf(false)
    /** No-pairing demo: when true, all I/O is short-circuited to local sample data (see [enterDemo]). */
    val demoMode = mutableStateOf(false)

    /** `demo=1` for the funnel events the demo also fires. [enterDemo] deliberately reuses the REAL state
     *  machine, so connected/session_opened/prompt_sent land whether or not a computer was ever paired —
     *  untagged, demo browsing read as activation (issue #278). Tag, don't suppress: demo usage is a metric
     *  of its own, and reports split on the parameter. */
    private fun demoTag(): Map<TelKey, Any> = if (demoMode.value) mapOf(TelKey.Demo to 1) else emptyMap()
    /** PREVIEW: brief connecting → end-to-end-encrypted opener shown before the demo project list. */
    val demoConnecting = mutableStateOf(false)
    val directories = mutableStateListOf<DirectoryEntry>()
    /** True once the first Directories of a session arrives — distinguishes "empty" from "still loading". */
    val directoriesLoaded = mutableStateOf(false)

    /**
     * INBOX MODE (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.2.3): this link speaks a Collaborator credential,
     * whose baseline grants ZERO session access. Everything the ordinary connect sequence does — ClientCaps,
     * ListDirectories, ListPendingApprovals — is refused by the daemon's collaborator capability whitelist,
     * so the app used to sit on the loading skeleton and then claim "computer offline" ~6s later.
     *
     * An inbox link instead asks for the ONE thing it may have (its own Handoff offers) and treats the reply
     * as its readiness proof. It is a background link: it drives no content screen, so it must never show a
     * connection failure state either.
     */
    val isCollaboratorInbox: Boolean get() = paired.value?.role == BindingRole.COLLABORATOR

    /** Inbox mode's [directoriesLoaded]: the first HandoffListing proves the collaborator channel is up. */
    val handoffsLoaded = mutableStateOf(false)
    val refreshing = mutableStateOf(false)
    val sessions = mutableStateListOf<SessionSummary>()
    val sessionsDir = mutableStateOf<String?>(null)
    /** Custom session groups for [sessionsDir] (issue #119); empty = none / older daemon that omits them.
     *  Per-session membership rides on [SessionSummary.group] (a group id, or null = ungrouped). */
    val sessionGroups = mutableStateListOf<SessionGroup>()
    /** True when THIS connection may manage groups (issue #119): the daemon sent a groups array (owner on a
     *  group-aware daemon). Distinguishes it from the two "no groups" cases that both leave [sessionGroups]
     *  empty — a group-aware daemon with zero groups yet (show "+ New group" so the FIRST one is creatable)
     *  vs an older daemon / a guest connection that omits groups entirely (hide the affordance). */
    val groupsSupported = mutableStateOf(false)
    /** True when THIS connection may rename sessions (issue #158): the daemon stamped
     *  [Sessions.renameSupported] (owner on a rename-aware daemon). False — an older daemon or a guest —
     *  hides the rename entry instead of sending a frame the daemon would silently drop. */
    val renameSupported = mutableStateOf(false)
    /** True when THIS connection may archive sessions (issue #202): the daemon stamped
     *  [Sessions.archiveSupported] (owner on an archive-aware daemon). False — an older daemon or a guest —
     *  hides every archive affordance instead of firing frames the daemon would silently drop. */
    val archiveSupported = mutableStateOf(false)

    /** Every archived session across ALL projects (issue #202) — the cross-project archive view. Populated
     *  only by an explicit [listArchivedSessions]; each row's [SessionSummary.cwd] names its own project. */
    val archivedSessions = mutableStateListOf<SessionSummary>()
    val archivedRefreshing = mutableStateOf(false)

    /** The last archive/restore, for the confirmation toast (issue #202). The design deliberately offers the
     *  REVERSE VERB rather than an "Undo": the two directions are exact inverses, so the toast's action is
     *  the same call with the flag flipped — no undo stack, and the wording stays honest about what happens.
     *  [running] drives the "still running" note: a disappearing green dot is the one moment a user fears
     *  they killed the session, and archiving never stops anything. */
    data class ArchiveToast(
        val workdir: String,
        val sessionId: String,
        val title: String,
        val archived: Boolean,
        val running: Boolean,
        val at: Long,
    )

    val archiveToast = mutableStateOf<ArchiveToast?>(null)

    fun dismissArchiveToast() { archiveToast.value = null }
    /** The daemon's refusal of the LAST [renameSession] attempt (issue #158), keyed to the session it
     *  targeted. Renames are asked from the SESSIONS list, so the feedback belongs there — the most
     *  common refusal (renaming a terminal-held session from the sidebar) happens with no chat open at
     *  all, and whatever chat IS open is an unrelated session whose transcript must not absorb the
     *  error line. Cleared by the next attempt / [dismissRenameError]. */
    val renameError = mutableStateOf<RenameRefusal?>(null)
    private var renameTarget: String? = null // the sessionId the in-flight RenameSession asked about

    // ── session rewind / fork (issue #282, docs/design/REWIND-FORK.md) ──────────────────────────────

    /** The message a rewind/fork was asked about, held while the dry run is out and the confirmation
     *  sheet is up. [text] is the anchor's own wording — the composer is prefilled with it after a
     *  rewind, which is the whole point of the gesture ("say that again, differently"). */
    data class RewindTarget(val convoId: String, val seq: Long, val uuid: String, val text: String, val mode: String)

    /** What the confirmation sheet is showing. `null` = no sheet. [counts] is null while the dry run is
     *  in flight — the sheet opens immediately in a loading state rather than after a round trip, so the
     *  gesture feels answered even on a slow link. */
    data class RewindSheet(val target: RewindTarget, val counts: RewindCounts? = null, val submitting: Boolean = false)
    data class RewindCounts(val turns: Int, val toolCalls: Int)

    val rewindSheet = mutableStateOf<RewindSheet?>(null)

    /** The daemon's machine-readable refusal of the last rewind attempt (a [dev.ccpocket.protocol.RewindRefusal]
     *  value), surfaced as a transient bar. Never rendered raw — the UI maps known values to copy and
     *  falls back to a generic line for anything a newer daemon invents. */
    val rewindError = mutableStateOf<String?>(null)

    fun dismissRewindError() { rewindError.value = null }

    /** Where the OPEN conversation came from, when this app is the one that branched it (issue #282).
     *  Deliberately local and short-lived rather than read back off the session list: the branch has no
     *  transcript — and therefore no list row and no lineage ledger entry — until its first turn, so for
     *  the whole "rewound, now retype it" window the client's own memory is the only source there is.
     *  [convoId] scopes it: the banner shows only while that exact conversation is on screen. */
    data class SessionLineage(val convoId: String, val mode: String, val fromSessionId: String?, val fromTitle: String)

    val sessionLineage = mutableStateOf<SessionLineage?>(null)

    /** The cut that has been SENT and is waiting for its [dev.ccpocket.protocol.RewindDone].
     *  Deliberately outlives [rewindSheet]: the daemon opens the branch and announces it BEFORE it answers
     *  the request, so by the time the answer lands `convoId` may already be the branch's — matching the
     *  answer against the live conversation would drop it exactly when it succeeded. Cleared by the answer
     *  (or by a conversation switch, which orphans it). */
    private var rewindAwaiting: RewindTarget? = null
    val messages = mutableStateListOf<ChatItem>()
    val pendingImages = mutableStateListOf<PendingImage>() // photos staged in the composer (pre-send)
    val pendingFiles = mutableStateListOf<PendingFile>()   // files staged/uploading into the workspace inbox (issue #90)
    private var fileUploadJob: Job? = null                 // the chunk-send loop of the ONE Uploading file
    private var fileAckDeadline: Job? = null               // last chunk sent → FileUploaded receipt guard
    private var pendingIdSeq = 0L
    val convoId = mutableStateOf<String?>(null)
    val workdir = mutableStateOf<String?>(null)
    val chatTitle = mutableStateOf<String?>(null)            // session title for the chat header (client-side)
    private var thinkStartMs: Long? = null                   // first Thinking chunk of the in-progress block
    val pendingAsk = mutableStateOf<PermissionAsk?>(null)
    /** Ordered waiting room behind [pendingAsk] (approval design M1): a same-session ask arriving while a
     * card is already up QUEUES here instead of overwriting it — three back-to-back asks are shown one by
     * one, none lost. Invariant: non-empty only while [pendingAsk] is non-null (resolving pops the head). */
    private val askQueue = mutableListOf<PermissionAsk>()
    /** "n / m" for the current approval burst (1-based position, burst total) — null while only one card
     * is in flight, so the single-ask UI stays exactly as before. */
    val askQueueProgress = mutableStateOf<Pair<Int, Int>?>(null)
    /** M3 advisory risk per pending ask (composite key, P1-3) — updates the card badge in place, never
     * the daemon deadline. */
    val askRisk = mutableStateMapOf<ApprovalKey, PermissionRiskUpdated>()

    /** The current card's advisory risk LEVEL, if any (M3) — the desktop card's badge input. */
    fun riskFor(ask: PermissionAsk): String? = riskDetailFor(ask)?.risk

    /** The current card's FULL risk event (M3): level plus reason, reason codes and assessed time. The
     *  Secure Approval sheet renders the evidence, not just the badge — a level with no reason is a verdict
     *  the user can't check. */
    fun riskDetailFor(ask: PermissionAsk): PermissionRiskUpdated? = askRisk[ApprovalKey(ask.convoId, ask.askId)]

    /** issue #100: is THIS exact ask the one the daemon reported TIMED_OUT? Composite-matched (P1-3). */
    fun askTimedOut(ask: PermissionAsk): Boolean = timedOutAskId.value == ApprovalKey(ask.convoId, ask.askId)
    private var askBurstTotal = 0
    private var askBurstDone = 0
    /** Daemon-authoritative account-wide approval queue for this machine. Kept across transient reconnects;
     * only a fresh [PendingApprovals] replaces it, so a network blip never masquerades as "all clear".
     * §18.1 P1-3: keyed by the COMPOSITE [ApprovalKey] — askId alone is only unique per agent connection,
     * so two sessions both asking as "1" must stay two rows. */
    val pendingApprovals = mutableStateMapOf<ApprovalKey, PendingApproval>()
    // issue #100: the askId the daemon reported as TIMED_OUT — the permission sheet for THIS exact ask renders
    // its terminal "timed out / auto-denied" state instead of vanishing. Matched by id, so a stale value can
    // never bleed onto the next card (askIds are unique per request).
    val timedOutAskId = mutableStateOf<ApprovalKey?>(null)
    val slashCommands = mutableStateListOf<SlashCommand>()   // composer "/" autocomplete, pushed by the daemon
    val terminalEntries = mutableStateListOf<TerminalEntry>() // quick-terminal history for the active session (issue #3)
    val terminalBusy = mutableStateOf(false)                  // a shell command is awaiting approval/result
    val changedFiles = mutableStateListOf<ChangedFile>()      // files this session touched (issue #36) — filled on demand
    val changedFilesLoading = mutableStateOf(false)
    val changedFilesUnavailable = mutableStateOf(false)       // no reply (old daemon silently drops the frame) — distinct from "no files"
    val viewedFilePath = mutableStateOf<String?>(null)        // non-null = file viewer open (content may still be loading)
    val viewedFile = mutableStateOf<FileContent?>(null)       // the loaded content; ok=false carries a user-facing error
    val viewedFileProgress = mutableStateOf<Pair<Long, Long>?>(null) // received/total bytes of an in-flight chunked read (#134 · 0714 A1 determinate bar)
    val viewedFileDiff = mutableStateOf<FileDiff?>(null)      // the loaded line-level diff; ok=false = none/too-old daemon
    val exportWaiting = mutableStateOf(false)                 // an ExportFile awaits the owner's approval/reply (issue #67 v2)

    // ── Git panel (issue #280) + worktrees (issue #281): owner-only, per-session surfaces ────────
    // Every request below is a NEW frame family, so an old daemon drops it silently — each one arms
    // an 8s deadline that lands in the honest "update the computer" state rather than spinning.
    val gitStatus = mutableStateOf<GitStatus?>(null)          // the repository's whole state (Screen A)
    val gitStatusLoading = mutableStateOf(false)
    val gitStatusUnavailable = mutableStateOf(false)          // no reply — the daemon predates pocket/git.*
    val gitDiff = mutableStateOf<GitDiff?>(null)              // the open file diff (Screen B); ok=false = none/too-old
    val gitDiffPath = mutableStateOf<String?>(null)           // non-null = the git diff screen is open
    val gitDiffStaged = mutableStateOf(false)                 // which side the Working|Staged control shows
    val gitBusyOp = mutableStateOf<String?>(null)             // the verb whose button spins; nothing ELSE locks (A4)
    val gitError = mutableStateOf<GitActionResult?>(null)     // the last failed action — drives the A5/A6 strip
    // A fetch changes nothing local, so without this the panel answers a successful fetch with silence
    // (issue #280 真机反馈 1). Cleared the moment the next verb starts — it is a receipt, not a state.
    val gitFetchNote = mutableStateOf<GitFetchReport?>(null)
    val gitPendingConfirm = mutableStateOf<GitActionPreview?>(null) // a two-step verb's preview → Screen D / C1-C2
    val worktrees = mutableStateOf<WorktreeList?>(null)       // every checkout of this repository (#281 Screen A)
    // the post-create receipt (#281 功能范围「在新建 worktree 中启动会话」, restored by #294 真机反馈):
    // a successful worktree.add answers with the created path, and the sheet it drives offers to open a
    // session there. A receipt, not a state — dismissed by hand or replaced by the next add.
    val worktreeCreated = mutableStateOf<WorktreeCreated?>(null)
    val worktreesLoading = mutableStateOf(false)
    val worktreesUnavailable = mutableStateOf(false)          // no reply — the daemon predates pocket/worktree.*
    val pathListing = mutableStateOf<PathEntries?>(null)     // latest @-file completion listing (issue #75); match its subPath before use
    val browseListing = mutableStateOf<PathEntries?>(null)   // latest anchored folder-browse listing (issue #152); match its (workdir, subPath) before use
    val browseRoots = mutableStateOf<List<String>>(emptyList()) // #176: fs roots latched from the "~" home-anchor reply (owner-only; empty on old daemon / guest → root switcher hidden)
    private var lastBrowseAnchor: String = BROWSE_HOME       // #176: anchor of the LATEST browseDirs request — a real fs root ("/", "C:\") routes its reply by matching this
    private var lastBrowseSub: String? = null                // subPath of the LATEST browseDirs request — only its reply may land in browseListing (#152 复核: stale out-of-order replies dropped)
    val mode = mutableStateOf(PermissionMode.DEFAULT)        // current execution/permission mode
    val permissionMode = mutableStateOf<String?>(null)       // backend-native mode: Claude `auto`
    val model = mutableStateOf<String?>(null)                // daemon's actual model for this session (header + info sheet)
    val sessionAgent = mutableStateOf<AgentKind?>(null)      // backend driving this session (Claude/Codex) — header badge
    val effort = mutableStateOf<String?>(null)               // reasoning effort: low|medium|high|xhigh|max (null = default)
    val serviceTier = mutableStateOf<String?>(null)          // Codex `priority` = Fast (independent from effort)
    val sessionOrigin = mutableStateOf<String?>(null)        // external trigger source, e.g. "feishu-bot" → header "via …" chip (issue #91)
    val contextWindow = mutableStateOf<Long?>(null)          // context capacity in tokens (derived from model if daemon omits it)
    val contextUsed = mutableStateOf<Long?>(null)            // ~tokens occupying the window (from the last turn's usage)

    /** Observed occupancy beyond the declared window PROVES a bigger one (beta-1M, or an alias the window
     *  table didn't know — a `/model fable` session once pinned the statusline at 100% mid-1M-session).
     *  The rule itself lives in ONE place — [dev.ccpocket.protocol.provenWindow] (daemon announce paths
     *  call it too); this is the phone's defensive re-check against old daemons. Codex sessions
     *  keep window=null (raw-token display) and are untouched.
     *
     *  An explicit user override is EXEMPT (issue #159): the rule infers a window from a declaration we
     *  guessed, but a hand-typed number isn't a guess — it's the user telling us the answer. Without this
     *  guard the inference outranks them: type 256000 for a gateway model, cross 256k, and the denominator
     *  silently becomes 1M — the percentage drops off a cliff and the setting looks broken. That made the
     *  custom field this issue asked for pointless, since the value it writes wouldn't survive use. */
    private fun upgradeWindowIfProven() {
        // #169: the exemption follows the EFFECTIVE override for the running model, not a single global flag —
        // otherwise a value typed for model A would also suppress the upgrade while model B is running.
        if (contextWindowOverrideFor(model.value) != null) return
        val win = contextWindow.value ?: return
        contextWindow.value = dev.ccpocket.protocol.provenWindow(win, contextUsed.value)
    }

    /** Re-derive the live statusline denominator from the current model + overrides, then let the proven-window
     *  rule have its say. Shared by both override setters so a mid-session change shows up immediately instead of
     *  waiting for the next SessionLive/relaunch (issue #60), and so the derive order lives in ONE place. */
    private fun reapplyContextWindow() {
        if (convoId.value == null) return
        val claudeish = (sessionAgent.value ?: AgentKind.CLAUDE) == AgentKind.CLAUDE
        contextWindow.value = contextWindowOverrideFor(model.value)
            ?: (if (claudeish) contextWindowFor(model.value) else null)
        upgradeWindowIfProven()
    }
    val backgroundJobs = mutableStateListOf<BackgroundJob>() // bg shells / sub-agents / monitors the daemon is tracking

    // ── Workflow orchestration (issue #106) ──────────────────────────────────────────────────────
    /** Workflow runs for the ACTIVE conversation, keyed by runId — live pushes and replayed finished
     *  manifests both land here; the chat card + progress tree render from this one map. */
    val workflowRuns = mutableStateMapOf<String, WorkflowRun>()

    /** On-demand full prompt/return per agent, keyed "runId#index" ([fetchWorkflowAgentDetail]). */
    val workflowAgentDetails = mutableStateMapOf<String, WorkflowAgentDetail>()

    /** Non-null = the full-screen workflow run view is open on this run. */
    val viewedWorkflowRunId = mutableStateOf<String?>(null)

    /** The run a chat Tool card binds to: live cards match the run's originating tool_use id;
     *  replayed cards carry the run id itself ([ChatItem.Tool.workflowRunId]). */
    fun workflowFor(item: ChatItem.Tool): WorkflowRun? =
        item.workflowRunId?.let { workflowRuns[it] }
            ?: item.taskId?.let { tid -> workflowRuns.values.firstOrNull { it.toolUseId == tid } }

    fun openWorkflow(runId: String) { viewedWorkflowRunId.value = runId }
    fun closeWorkflow() { viewedWorkflowRunId.value = null }

    /** Ask the daemon for one agent's full prompt/return (detail sheet). Cached per (run, index);
     *  an old daemon drops the frame silently — the sheet keeps showing the snapshot previews. */
    fun fetchWorkflowAgentDetail(runId: String, agentIndex: Int, agentId: String?) {
        val key = "$runId#$agentIndex"
        if (workflowAgentDetails.containsKey(key)) return
        val convo = convoId.value ?: return
        scope.launch { send(GetWorkflowAgentDetail(convo, runId, agentIndex, agentId)) }
    }
    val allowRules = mutableStateListOf<String>()            // "Always allow" scopes remembered this session
    private val pendingGrantMutations = mutableMapOf<String, PendingGrantMutation>()
    val switching = mutableStateOf(false)                    // a mode switch is relaunching the session
    val opening = mutableStateOf(false)                      // an OpenSession is in flight — one-tap entries disable on it (a double-tap would open two fresh sessions)
    // A chat→chat switch is in flight (issue #165). [openSession] nulls convoId while it waits for the
    // daemon, and the router falls to the session LIST whenever convoId is null — so without this the
    // switcher visibly bounced you out to a list for a beat before landing. Cleared wherever [opening] is.
    val switchingSession = mutableStateOf(false)
    val openTimedOut = mutableStateOf(false)                 // the daemon never answered an OpenSession within 8s — slim banner, auto-dismissed (issue #41)
    private var openGen = 0                                  // generation counter matching each openSession call to its own safety-net timer
    private var openDispatchedGen = 0                        // current generation has reached its OpenSession send (#235 identity handoff)
    private var openJob: Job? = null                         // owns both the state-switch worker and its 8s deadline
    /**
     * Explicit navigation fence (issue #226). [sessionKey] intentionally survives [backToBrowse] so a
     * draft keeps its durable key, but it therefore cannot also prove that the user still wants a chat
     * route. Once the user backs out, late SessionLive re-announces from that same session are background
     * state and must not bind [convoId] again. A later explicit [openSession] lowers the fence.
     *
     * Default false preserves the cold/test bootstrap seam where a first SessionLive establishes an
     * otherwise-unbound view; every real browse action raises it before any late frame can arrive.
     */
    private var sessionNavigationFenced = false
    /** The workdir of an in-flight BRAND-NEW OpenSession (resumeId == null), armed by [openSession] and
     *  disarmed when its SessionLive answer lands (or the open fails / times out). A brand-new session has
     *  no sessionId to recognize its announce by, so the #219 identity guard in the SessionLive handler
     *  matches the answer on this workdir instead. Resume opens never need it — their announce carries the
     *  resumed sessionId, which [sessionKey] already pins. */
    private var pendingNewOpenWd: String? = null

    /** One open request's full identity: everything [openSession] needs to REPLAY it (the desktop's retry)
     *  and enough to tell two requests apart (the no-op guard). Issue #235. */
    private data class OpenAttempt(
        val wd: String,
        val resumeId: String?,
        val startMode: PermissionMode,
        val title: String?,
        val agent: AgentKind?,
        val startPermissionMode: String?,
        val startModel: String?,
    )

    /** The open currently in flight, claimed SYNCHRONOUSLY by [openSession] before it launches (issue #235).
     *  [opening] alone could not gate a double-click: it was raised inside the coroutine, so two clicks in
     *  the same frame both got past it and the second one's CloseSession+OpenSession tore down the session
     *  the first had just landed. Released on every terminal path — the SessionLive answer, a PocketError,
     *  the 8s net, disconnect/demote — exactly where [pendingNewOpenWd] is, so a claim can never outlive
     *  the request that made it. (The wire carries no request id; releasing on any of those is the
     *  provably safe direction — an early release only re-enables a retry, it never opens anything.) */
    private var openInFlight: OpenAttempt? = null

    /** The last open asked for, kept PAST its terminal path so [retryOpen] can replay the same request —
     *  the desktop's open-failed pane offers a retry and must not silently re-open under other flags. */
    private var lastOpenAttempt: OpenAttempt? = null
    val autoFocusComposer = mutableStateOf(false)            // brand-new session: ChatScreen raises the keyboard once on landing (consumed there)
    val streaming = mutableStateOf(false)
    val observing = mutableStateOf(false) // viewing a session running outside the daemon (read-only tail)
    private var currentSessionId: String? = null

    // ── incremental reattach + older-history paging (issue #147) ─────────────────────────────────────
    // The transcript cursor the last full/delta ConvoHistory left us at, and which session it belongs
    // to — echoed back as OpenSession.lastEventSeq on reconnect re-opens so the daemon replays only the
    // delta. In-memory only, by design: a delta is only meaningful while `messages` still holds the
    // transcript it continues; a fresh open always replays in full.
    private var historySeq: Long? = null
    private var historySeqSession: String? = null
    // older-history paging: the on-screen window's oldest cursor + whether more exists on disk
    private var historyFirstSeq: Long? = null
    val historyHasMore = mutableStateOf(false)
    val historyLoadingOlder = mutableStateOf(false)
    private var historyPageDeadline: Job? = null
    /** The anchor (beforeSeq) of an outstanding older-history request, or null when none is in flight
     *  (issue #147). This — NOT [historyLoadingOlder] — is what gates an incoming [ConvoHistoryPage]:
     *  on a slow cross-border link the reply deadline may already have collapsed the spinner, yet the
     *  page is still a valid reply we must ACCEPT, not drop (the old bug: a page that took >10s was
     *  discarded and paging was permanently disabled). Cleared the moment a page lands (which dedupes a
     *  duplicate late fan-out) or the transcript/anchor is reset out from under it. An unsolicited page
     *  (null here) is dropped — the old `historyLoadingOlder` guard's role, now anchored on the request. */
    private var historyPageAnchor: Long? = null
    /** How many rows the last page PREPENDED (read with [historyPrependGen]) — the chat list scrolls
     *  by this to keep the viewport anchored on the row the user was reading. */
    var lastHistoryPrependCount = 0
        private set
    val historyPrependGen = mutableStateOf(0)

    /** The cursor to ride an [OpenSession] re-open (issue #147): the stored seq only when the target
     *  session still matches the one it was recorded for AND we still hold its transcript; else 0 =
     *  "replay in full, but this client understands delta frames" (arms the observe tail's deltas).
     *  Never null from a new client — null is how an OLD client looks on the wire. */
    private fun lastEventSeqFor(sid: String?): Long =
        historySeq?.takeIf { sid != null && historySeqSession == sid && messages.isNotEmpty() } ?: 0L

    /** Forget the #147 cursors/paging — every place the transcript itself is dropped must call this,
     *  or a stale cursor would ask the daemon to continue a transcript we no longer hold. */
    private fun resetHistoryPaging() {
        historySeq = null; historySeqSession = null; historyFirstSeq = null
        historyHasMore.value = false; historyLoadingOlder.value = false
        historyPageDeadline?.cancel(); historyPageDeadline = null
        historyPageAnchor = null
        lastHistoryPrependCount = 0
    }

    /** Scrolled to the top of the loaded window — fetch one page of OLDER history (issue #147). The
     *  deadline only COLLAPSES THE SPINNER (a stuck link shouldn't spin forever), it no longer disables
     *  paging: a daemon that predates paging silently drops the frame, but the affordance stays so the
     *  user can retry — while a slow cross-border reply that lands after the deadline is still accepted
     *  (gated on [historyPageAnchor], not the spinner) and prepended normally. */
    fun loadOlderHistory() {
        val convo = convoId.value ?: return
        val before = historyFirstSeq ?: return
        if (!historyHasMore.value || historyLoadingOlder.value) return
        historyLoadingOlder.value = true
        historyPageAnchor = before // the request is outstanding until a page lands, even past the deadline
        scope.launch { send(FetchHistoryPage(convo, beforeSeq = before)) }
        historyPageDeadline?.cancel()
        historyPageDeadline = scope.launch {
            delay(10_000)
            if (historyLoadingOlder.value) historyLoadingOlder.value = false // stop the spinner; keep the affordance + the outstanding request
        }
    }

    /** The last prompt sent that the daemon hasn't visibly started processing (no chunk/tool/done yet).
     *  If the daemon answers [SessionGone] (convo idle-reaped while the link was down), we auto-reopen the
     *  session and resend this once — the fix for "sent a message into a ghost session, nothing happened".
     *  Cleared on the first sign of processing; consumed (single retry, no loops) by the resend.
     *  [promptId] rides along so the resend reuses it — the daemon dedupes if the original landed (#66). */
    private class PromptRetry(val text: String, val images: List<ImageData>, val workdir: String, val promptId: String?)
    private var promptRetry: PromptRetry? = null
    private var promptResendArmed = false // set by SessionGone: the next matching SessionLive resends promptRetry
    private var promptPending = false // a User bubble is marked pending until the daemon shows signs of life
    /** The newest prompt whose receipt/start state may still change the global watchdogs. PromptAck can race
     *  behind the first AssistantChunk (the daemon's stdout pump is concurrent with the stdin write), and old
     *  receipts can arrive after a newer send. Only an exact id match may advance this state machine. */
    private var activePromptId: String? = null

    /** The in-flight prompt got neither a [dev.ccpocket.protocol.PromptAck] nor any stream evidence within
     *  [promptReceiptTimeoutMs] (issue #78). The link can CLAIM healthy while nothing comes back — outboxes
     *  buffer across reconnects by design, and an E2E-deaf link (the daemon dropped this device's session
     *  while the socket stayed up — routine when a fleet of machines keeps cycling links) never errors — so
     *  without a deadline the bubble reads "sending…" forever. Drives the honest "not delivered" cue on
     *  both UIs; cleared by the first daemon evidence, a session change, or teardown. */
    val sendStalled = mutableStateOf(false)
    private var promptWatchdog: Job? = null
    internal var promptReceiptTimeoutMs = 10_000L // > relay RTT + a lazy agent spawn; a test seam shrinks it

    /** Legacy-daemon second-stage deadline (issue #104): a [dev.ccpocket.protocol.PromptAck] only means the daemon WROTE
     *  the prompt to the agent's stdin — not that a turn started. A wedged or mid-relaunch agent can swallow
     *  that write and emit nothing, leaving [streaming] stuck true and the UI silently "thinking" forever
     *  (issue #78's receipt watchdog is already cancelled by the ack, so nothing catches this). Once delivered
     *  we hand off to [armTurnWatchdog]: no chunk/tool/turn-end within [promptTurnTimeoutMs] flips [turnStalled],
     *  which surfaces an inline "resend" cue instead of an endless spinner. This is deliberately NOT an
     *  auto-resend: the live daemon Conversation already recorded this promptId (the #66 dedup that makes a
     *  SessionGone same-id resend safe would here turn a same-id resend into a bare re-ack — no turn), and a
     *  fresh-id auto-resend would double-run a turn that was merely slow to start. The recovery is user-driven
     *  ([resendStalledPrompt], fresh id). [turnStalled] retracts on the first real turn frame or a session change.
     *  Only for prompts sent into an IDLE session — a mid-turn send is the queued case, [turnQueued]. Daemons
     *  advertising [dev.ccpocket.protocol.DaemonInfo.supportsPromptRecovery] own this recovery with their
     *  unconsumed ledger, so the silence-only resend timer is disabled for them. */
    val turnStalled = mutableStateOf(false)

    /** Legacy queued flavor of the same deadline: the prompt was sent INTO an already-running turn (the composer's
     *  "sending will queue" state), so the CLI parks it until the next tool boundary / turn end — silence past
     *  the deadline is expected there, not a swallow. The watchdog can only be pending while the prompt is
     *  provably still queued (consuming it takes a tool boundary or turn end, and either frame feeds
     *  [promptEvidence] first), so this surfaces a calm "queued" status instead of [turnStalled]'s resend cue.
     *  Deliberately NOT actionable: the original still sits in the CLI queue, and a fresh-id resend (the #66
     *  dedup doesn't apply) would run the instruction twice the moment the turn yields. */
    val turnQueued = mutableStateOf(false)
    private var turnWatchdog: Job? = null
    private var awaitingTurn = false // between a PromptAck (delivered) and the first turn frame
    private var promptQueued = false // the in-flight prompt was sent mid-turn — the CLI queues it (see [turnQueued])
    internal var promptTurnTimeoutMs = 45_000L // ack→first-frame budget: a cold model / big context still streams
        // *some* frame (thinking token, tool start) well inside this; a test seam shrinks it

    /** The daemon flagged this session degraded (recent turns were all API-failure placeholders — issue #65). */
    val sessionDegraded = mutableStateOf(false)
    // first send into a degraded session is blocked with an explanation; the next one goes through
    private var degradedSendArmed = false
    private var turnStartMark: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null // stamps TurnEnded's duration

    /** ms since THIS app sent the in-flight turn's prompt — null once the turn ends, or when the turn
     *  wasn't started here (attached to an already-running session). Anchors the desktop stop-refill
     *  window (#48): handing the prompt back for re-editing only makes sense near its own send. */
    fun turnElapsedMs(): Long? = turnStartMark?.elapsedNow()?.inWholeMilliseconds

    /** Desktop notifier seam: fires when the active conversation's turn completes (after the TurnEnded
     *  marker lands). The UI layer decides whether that deserves a system notification / dock badge.
     *  [sessionId] identifies the finished session (null before the daemon named it) so a clicked
     *  notification can jump back to it (issue #99). */
    var onTurnFinished: ((title: String, preview: String?, sessionId: String?) -> Unit)? = null

    /** §18.2 P2-4 (desktop): a NEW security approval arrived — the shell notifies (system banner + badge)
     *  when the window is unfocused. Fired for approval asks only (questions are conversation UI); the
     *  callback receives NO command/path content, matching the push minimization contract. */
    var onApprovalArrived: (() -> Unit)? = null

    /** Real turn evidence (chunk / tool / turn-end / error) or a terminal frame (process exit, session gone):
     *  the agent is actually producing — or the whole turn is being torn down. Cancels BOTH the delivery
     *  receipt watchdog (issue #78) and the turn-start watchdog (issue #104), clears both stall cues, drops
     *  the retry copy, and flips the matching pending User bubble out of its local-only state (issue #41).
     *
     *  A frame from a turn that was ALREADY running when this prompt was sent is not receipt evidence for
     *  the queued prompt: the old turn can keep streaming before this SendPrompt even reaches the daemon.
     *  [exactPrompt] is reserved for evidence that names/resolves this prompt itself (currently a matching
     *  ConvoHistory USER row) and for terminal teardown where no delivery claim remains on screen. */
    private fun promptEvidence(exactPrompt: Boolean = false) {
        clearTurnWatchdogState() // any real frame retires a silence-only turn inference
        if (promptPending && promptQueued && !exactPrompt) return

        val promptId = activePromptId
        promptRetry = null
        activePromptId = null
        promptWatchdog?.cancel(); promptWatchdog = null // the daemon is talking — the receipt deadline is moot
        sendStalled.value = false
        if (!promptPending) return
        promptPending = false
        val i = messages.indexOfLast {
            it is ChatItem.User && it.pending && (promptId == null || it.promptId == promptId)
        }
        (messages.getOrNull(i) as? ChatItem.User)?.takeIf { it.pending }?.let { messages[i] = it.copy(pending = false) }
    }

    /** TranscriptMerge may resolve (or replace) the active local pending bubble when a reconnect replay
     *  contains the matching USER row. That is stronger evidence than link liveness: retire the receipt
     *  deadline and retry state too, otherwise the invisible old [sendStalled] leaks into the next send. */
    private fun reconcilePromptReceiptFromHistory(before: List<ChatItem>, after: List<ChatItem>) {
        if (!promptPending) return
        val promptId = activePromptId ?: return
        val wasPending = before.any { it is ChatItem.User && it.promptId == promptId && it.pending }
        val remainsPending = after.any { it is ChatItem.User && it.promptId == promptId && it.pending }
        if (wasPending && !remainsPending) promptEvidence(exactPrompt = true)
    }

    /** Delivery receipt ONLY (PromptAck, issue #104): the daemon wrote the prompt to the agent's stdin, but an
     *  ack is not a started turn. Clear the delivery-stage machinery (the receipt deadline is met) and, if a turn
     *  is still expected, hand off to the turn-start watchdog. Deliberately keeps [promptRetry] — the resend cue
     *  (and a late SessionGone in this window) still needs the text/images. The PromptAck handler flips the
     *  specific bubble to delivered right after this; here we only clear the delivery FLAG. */
    private fun promptDelivered(promptId: String) {
        // The pump may emit real output before sendPrompt() returns and the daemon emits PromptAck. In that
        // ordering promptEvidence() already retired activePromptId; re-arming here creates the exact false
        // "no response — resend" cue on top of a live/completed answer. A receipt for an older queued send is
        // equally forbidden from controlling the newest prompt's watchdog.
        if (activePromptId != promptId || !promptPending) return
        promptWatchdog?.cancel(); promptWatchdog = null // receipt arrived — the delivery deadline is moot
        sendStalled.value = false
        promptPending = false
        // #122-capable daemons keep the prompt in an unconsumed ledger and re-deliver it after process
        // replacement. A quiet first-token window is therefore not a failure signal (large-context Codex and
        // Claude turns routinely exceed 45s); blind fresh-id resend can double-execute the request.
        if (promptQueued && streaming.value) {
            // Queue status is informational and non-actionable, so it remains useful with a ledger-capable
            // daemon; only the unsafe "swallowed → resend" inference is retired.
            awaitingTurn = true
            armTurnWatchdog(queued = true, promptId = promptId)
        } else if (daemonOwnsPromptRecovery) {
            clearTurnWatchdogState()
        } else if (streaming.value) {
            awaitingTurn = true
            armTurnWatchdog(queued = false, promptId = promptId)
        } // a TurnDone/error already in wouldn't re-arm
    }

    /** Retire only the ack→turn fallback. The prompt retry/receipt state is separate: a SessionLive reattach
     *  proves the conversation lifecycle again, but it does not by itself prove that this exact prompt was
     *  consumed. */
    private fun clearTurnWatchdogState() {
        turnWatchdog?.cancel(); turnWatchdog = null
        awaitingTurn = false
        turnStalled.value = false
        turnQueued.value = false
    }

    /** A prompt's retry/receipt/turn cues belong to one visible conversation. Every explicit conversation
     *  boundary calls this alongside clearing [messages], so an invisible timer cannot resurrect underneath
     *  a later session or its first fresh send. Transport reconnect is intentionally NOT such a boundary. */
    private fun clearPromptLifecycleState() {
        promptWatchdog?.cancel(); promptWatchdog = null
        promptRetry = null
        promptResendArmed = false
        promptPending = false
        activePromptId = null
        sendStalled.value = false
        clearTurnWatchdogState()
    }

    // mode/model/effort are claude launch flags, NOT stored in the transcript jsonl. Leaving an idle
    // session closes its process; reopening resumes a FRESH process that would otherwise default these.
    // Remember the last-known set per sessionId so a reopen restores the badge + relaunches under them.
    // Persisted (TSV in SecureStore, last 100) so an app restart doesn't reset every session to defaults.
    private data class SessionParams(
        val mode: PermissionMode,
        val model: String?,
        val effort: String?,
        val agent: AgentKind = AgentKind.CLAUDE,
        val permissionMode: String? = null,
        val serviceTier: String? = null,
    )
    private val sessionParams = mutableMapOf<String, SessionParams>()

    init {
        SecureStore.getString(K_SESSION_PARAMS)?.lineSequence()?.forEach { line ->
            val t = line.split('\t')
            if (t.size >= 5) runCatching {
                sessionParams[t[0]] = SessionParams(
                    PermissionMode.valueOf(t[1]),
                    t[2].ifEmpty { null },
                    t[3].ifEmpty { null },
                    AgentKind.valueOf(t[4]),
                    t.getOrNull(5)?.ifEmpty { null },
                    t.getOrNull(6)?.ifEmpty { null },
                )
            }
        }
        loadWorkingSet() // #165: keyed on [paired], which only exists this far down the constructor
    }

    private fun persistSessionParams() {
        val lines = sessionParams.entries.toList().takeLast(100)
            .joinToString("\n") { (sid, p) ->
                listOf(
                    sid, p.mode.name, p.model ?: "", p.effort ?: "", p.agent.name,
                    p.permissionMode ?: "", p.serviceTier ?: "",
                ).joinToString("\t")
            }
        SecureStore.putString(K_SESSION_PARAMS, lines)
    }

    // ── voice input (dictation) ───────────────────────────────────────────
    val voice = mutableStateOf<VoiceState>(VoiceState.Idle)
    val voiceLevels = mutableStateListOf<Float>()            // rolling envelope window driving the waveform
    val liveDictation = mutableStateOf(false)                // native engine active → S2 shows the live transcript field
    val liveFinal = mutableStateOf("")                       // native dictation: committed text (primary color)
    val livePartial = mutableStateOf("")                     // native dictation: volatile tail (muted)
    val micPermissionSheet = mutableStateOf(false)           // S6
    val voiceNotice = mutableStateOf<StringResource?>(null)  // transient "didn't catch any speech"
    // A finished transcript waiting for the composer to pick up (issue #221): recognition can be wrong, so
    // the result lands in the input box for the user to review/edit and send EXPLICITLY — it never auto-sends.
    // The UI (App.kt) appends it after the current draft, drops the caret at the end and takes focus, then
    // clears this back to null.
    val pendingVoiceText = mutableStateOf<String?>(null)
    private val recorder by lazy { VoiceRecorder() }
    private var usingNative = false
    private var preferRemote = false                         // sticky after a native-engine failure
    private var keptAudio: RecordedAudio? = null             // retained for S5 retry (re-send, not re-record)
    internal var captureId: String? = null // internal: tests drive onTranscript's capture-match gate
    private var voiceTicker: Job? = null
    private var voiceTimeout: Job? = null
    private var levelsJob: Job? = null
    private var dictationJob: Job? = null
    private var noticeJob: Job? = null
    private var voiceStartJob: Job? = null                    // the async recorder.start() window (issue #266)
    private var interruptJob: Job? = null                     // system interruption (call / route steal) collector

    /** Pair from a scanned/pasted `ccpocket://pair?...` link, then connect end-to-end.
     *  [fromScan] only flavors telemetry (source=qr-link vs link) — see [handleIncomingLink]. */
    fun pair(link: String, fromScan: Boolean = false) {
        val info = Pairing.parse(link.trim())
        if (info == null) {
            status.value = StatusMsg(Res.string.status_invalid_link)
            // a reject BEFORE any network is still a pairing failure — untracked, it looked like "never tried"
            setPairFailure(PairFailure.PARSE)
            Telemetry.track(TelEvent.PairFailed, mapOf(TelKey.Reason to PairFailure.PARSE.wireReason(null)))
            return
        }
        setPairFailure(null)
        pairVerifying.value = true
        status.value = StatusMsg(Res.string.status_pairing)
        scope.launch { doPair(if (fromScan) "qr-link" else "link") { info } }
    }

    /** A scanned/opened `ccpocket://…` URL. Kept as the historical name for the pairing call sites, but it
     *  is now just [handleIncomingLink]: the scanner that used to see only pair links routes collaborator
     *  and share invites to their trust screens instead of rejecting them (§7). */
    fun handlePairUrl(url: String, fromScan: Boolean = false) { handleIncomingLink(url, fromScan = fromScan) }

    /** Pair from the 6-digit code shown by `cc-pocket pair` on the computer.
     *  [fromScan] only flavors telemetry: a scanned QR carries the same `code=` payload, so without it every
     *  camera pairing reported source=code and the scanner's share of activations was invisible. */
    fun pairWithCode(code: String, fromScan: Boolean = false) {
        status.value = StatusMsg(Res.string.status_pairing)
        // cleared HERE as well as in doPair: the launch is one dispatch away, and a card from the previous
        // attempt surviving into the new one's first frames reads as "that failed again, instantly"
        setPairFailure(null)
        pairVerifying.value = true
        scope.launch { doPair(if (fromScan) "qr" else "code") { Pairing.resolveCode(code.trim(), it) } }
    }

    /**
     * pair_failed cause as a CLASS, never the exception text: a redeem failure's message carries the relay's
     * raw response body, which has no business leaving the device. Same shape as onTransportDown's conn_failed.
     *
     * Both halves now come from ONE place — [classifyPairFailure] decides the class, [wireReason] names it —
     * so the screen's actionable card and the funnel's category can never describe the same failure
     * differently. The strings are unchanged by construction; `PairFailureTest` pins that.
     */
    private fun pairFailReason(t: Throwable): String = classifyPairFailure(t).wireReason(t)

    /**
     * Why the last pairing attempt failed, as the class the SCREEN can act on (issue #278 batch 2).
     *
     * [status] alone could not carry this: one sentence cannot both name a stale code and offer the command
     * that mints a new one. Written at every point that reports `pair_failed` from THIS screen's routes, and
     * cleared the moment a new attempt starts — a card that outlives its attempt is a lie about the present.
     */
    val pairFailure = mutableStateOf<PairFailure?>(null)

    /**
     * Bumped on every write to [pairFailure], so a pairing surface can tell news from history.
     *
     * The state is repository-scoped but the card is screen-scoped: an unroutable `ccpocket://` link tapped
     * while the user was happily connected sets PARSE from the ROOT deep-link handler, and without this a
     * pairing screen opened days later would greet them with it. A surface records the value it entered on
     * and shows only failures recorded after that.
     */
    val pairFailureSeq = mutableStateOf(0)

    /** True while a pairing attempt is in flight, so the code field can lock and say why it is locked.
     *  Distinct from [status], which is a sentence and cannot gate an input. */
    val pairVerifying = mutableStateOf(false)

    /** Drop the failure card — the user is starting over (the "Try again" / "Retry" actions). */
    fun clearPairFailure() { setPairFailure(null) }

    /** Which attempt currently OWNS the screen state. The alternate routes (scan, paste, LAN) stay live
     *  while an attempt is in flight, so a slow attempt can still be running when a later one succeeds —
     *  and a loser that reports its timeout afterwards would arm a failure card over a completed pairing. */
    private var pairAttempt = 0

    private fun setPairFailure(kind: PairFailure?) {
        pairFailure.value = kind
        pairFailureSeq.value++
    }

    private suspend fun doPair(source: String, getInfo: suspend (HttpClient) -> dev.ccpocket.app.pairing.PairingInfo) {
        // before any network: this is the "the user actually tried" mark the funnel was missing (issue #278)
        Telemetry.track(TelEvent.PairStarted, mapOf(TelKey.Source to source))
        val attempt = ++pairAttempt
        setPairFailure(null)          // this attempt's outcome is not known yet; the last one's card must go
        pairVerifying.value = true
        // constructed INSIDE the try: an engine that fails to initialise would otherwise skip the finally
        // and strand the screen on a locked field with a spinner and no recovery
        var client: HttpClient? = null
        try {
            client = HttpClient()
            val info = getInfo(client)
            val keys = Pairing.deviceKeys()
            paired.value = Pairing.redeem(info, keys, client!!) // upserts the list + pins this as the active account
            // a FRESH pairing (e.g. a guest redeeming a new invite for the same daemon/accountId) supersedes
            // any recorded "share ended" terminal state — else the new binding would open on the dead card
            paired.value?.let { SecureStore.remove(K_SHARE_ENDED_PREFIX + it.accountId) }
            shareEnded.value = null
            replace(pairedList, Pairing.loadAll())
            addingDevice.value = false
            firstTicket = info.ticket
            Telemetry.track(TelEvent.Paired, mapOf(TelKey.Source to source))
            startRelay()
        } catch (t: Throwable) {
            status.value = StatusMsg(Res.string.status_pair_failed, t.message ?: t::class.simpleName ?: "error")
            // …but only the NEWEST attempt owns the SCREEN state. Telemetry below is deliberately left
            // unconditional: a superseded attempt really did fail, and suppressing it here would change what
            // the funnel counts (a pre-existing race, out of this change's scope).
            if (attempt == pairAttempt) setPairFailure(classifyPairFailure(t))
            Telemetry.track(TelEvent.PairFailed, mapOf(TelKey.Reason to pairFailReason(t)))
            Telemetry.recordError(t.message ?: "pair failed", "pairing")
        } finally {
            if (attempt == pairAttempt) pairVerifying.value = false
            client?.close()
        }
    }

    /** Connect to the already-paired daemon over the encrypted relay channel. */
    fun startRelay() {
        if (paired.value == null) return
        if (sessionActive.value) return // already connected/connecting — the transport layer self-heals from here
        useRelay = true
        sessionActive.value = true
        retryAttempts = 0
        launchTransport(reconnect = false)
    }

    /** Advanced: connect directly to a daemon on the LAN (no relay), still over WebSocket. */
    fun startDirect(url: String) {
        useRelay = false
        lastDirectUrl = url
        status.value = StatusMsg(Res.string.status_checking_network)
        scope.launch {
            if (!ensureLocalNetworkAccess(url)) {
                status.value = StatusMsg(Res.string.status_local_denied)
                return@launch
            }
            sessionActive.value = true
            retryAttempts = 0
            launchTransport(reconnect = false)
        }
    }

    /** Recompute the observable [phase] from the per-session flags. Call after every relevant event. */
    private fun recomputePhase() {
        // inbox mode has its own readiness proof: a collaborator credential is REFUSED directory discovery,
        // so waiting for Directories here would keep the link permanently "not ready" (§3.2.3)
        val listed = if (isCollaboratorInbox) handoffsLoaded.value else directoriesLoaded.value
        val ready = attachedThisSession && listed && !daemonOffline
        val next = when {
            pairingInvalid                                   -> ConnPhase.PairingInvalid
            ready                                            -> ConnPhase.Ready
            attachedThisSession && daemonOffline && useRelay -> ConnPhase.ComputerOffline
            // brief grace on a drop from a healthy link: hold the Ready look so a quick re-attach doesn't flash
            // the Reconnecting banner (#28 — every background→foreground otherwise blipped it)
            hadReadyThisSession && !reconnectGracePassed     -> ConnPhase.Ready
            hadReadyThisSession                              -> ConnPhase.Reconnecting
            relayDeadlinePassed                              -> ConnPhase.RelayUnreachable
            else                                             -> ConnPhase.Connecting
        }
        if (next != phase.value) { // emit only real transitions — the honest connection-state trail in Firebase
            phase.value = next
            Telemetry.track(TelEvent.ConnPhase, mapOf(TelKey.Phase to next.name, TelKey.Transport to transportName()))
        }
        if (ready) { reconnectGraceJob?.cancel(); reconnectGraceJob = null; reconnectGracePassed = false; listWaitRetried = false } // truly back — reset for the next blip
        consumePendingOpenIfReady() // a push-tap target waits here until the link is actually Ready
    }

    private fun transportName() = when {
        useRelay && directE2E.connected -> "direct-e2e"
        useRelay -> "relay"
        else -> "direct"
    }

    /**
     * A tapped task-complete push wants to resume a specific session. Stash it, bring the link up if the
     * app was disconnected, and open it now if we're already Ready — otherwise [recomputePhase] opens it
     * the moment the directory list lands (proving the computer is online). Idempotent for repeat taps.
     */
    fun requestOpenSession(workdir: String, sessionId: String, title: String? = null, agent: AgentKind? = null) {
        pendingOpen = dev.ccpocket.app.SessionRoute(workdir, sessionId, title, agent)
        if (demoMode.value) { pendingOpen = null; return }
        if (paired.value != null && !sessionActive.value) startRelay()
        consumePendingOpenIfReady()
    }

    private fun consumePendingOpenIfReady() {
        val t = pendingOpen ?: return
        if (phase.value != ConnPhase.Ready || !attachedThisSession) return // real ready, not the grace-held Ready (#28)
        pendingOpen = null
        if (convoId.value != null && currentSessionId == t.sessionId) return // already in this session — don't churn it
        sessionsDir.value = null // drop any half-open session list so the chat is what shows
        openSession(t.workdir, t.sessionId, title = t.title, agent = t.agent)
    }

    /** Relay control-plane events (not E2E daemon traffic) drive the honest connection phase. */
    private fun handleControl(f: Frame) {
        when (f) {
            is Attached -> { attachedThisSession = true; connected.value = true; connGen.value++; relayDeadlinePassed = false; armLinkStableReset(); ensurePushStarted(); registerPush(); startListWait(); recomputePhase() }
            // Only re-handshake on a genuine offline->online transition. The relay re-broadcasts
            // PeerPresence(true) on every daemon (re)attach; a redundant true must NOT tear down a healthy
            // transport (that surfaced as a spurious Reconnecting banner when opening a session).
            is PeerPresence -> { val wasOffline = daemonOffline; daemonOffline = !f.online; if (f.online && wasOffline) onComputerBackOnline(); recomputePhase() }
            is AuthError -> { pairingInvalid = true; retryJob?.cancel(); recomputePhase() }
            else -> {}
        }
    }

    /** The computer (re)attached. Tearing our HEALTHY socket down on this edge was the #145 cascade's
     *  first domino (teardown → brief two-socket overlap → relay supersede-kick → drop → retry storm):
     *  the daemon's own relay blip broadcasts PeerPresence(false→true) at every device, and the daemon
     *  now KEEPS device E2E sessions across its relay reconnects (#146) — so with a healthy link the
     *  right move is to re-sync the page over it (refresh the list, reattach the open chat), not to
     *  rebuild the socket. If the daemon actually RESTARTED (fresh process — our Noise session died with
     *  it), those frames land in the void: the probe sees no Directories reply within [presenceProbeMs]
     *  and escalates to ONE full re-handshake. An unhealthy link skips straight to the full reconnect. */
    private fun onComputerBackOnline() {
        if (!transportHealthy()) { launchTransport(reconnect = true); return }
        presenceProbeJob?.cancel()
        val inbox = isCollaboratorInbox
        val seenRev = if (inbox) handoffListingRev else directoriesRev
        presenceProbeJob = scope.launch {
            if (inbox) send(ListHandoffs()) else send(ListDirectories())
            restoreAfterReconnect()
            delay(presenceProbeMs)
            val rev = if (inbox) handoffListingRev else directoriesRev
            if (sessionActive.value && rev == seenRev) launchTransport(reconnect = true, force = true)
        }
    }

    /** An E2E transport reported it went DEAF mid-stream (issue #146): its inbound frames stopped
     *  decrypting while the socket keeps pinging, so [onTransportDown] never fires and none of the other
     *  self-heal nets reach it — [startListWait] is connection-period only (guards !directoriesLoaded), the
     *  #145 presence probe is a snapshot edge, the turn watchdog covers only a locally-issued prompt's ack
     *  gap. A passive observer of a long turn falls through all of them. Force a re-handshake — the same
     *  deliberate teardown of a live-but-deaf link the connection-period deaf-link retry uses — so the
     *  daemon re-keys its outbound onto this socket. The N-consecutive-failure threshold lives in the
     *  connection (never trips on a lone stray frame); reaching here already means the link is deaf. */
    private fun onDeafLink() {
        if (demoMode.value || pairingInvalid || !sessionActive.value) return
        launchTransport(reconnect = true, force = true)
    }

    /** Is the CURRENT transport demonstrably up? (attached, no observed failure, socket loop still alive) */
    private fun transportHealthy(): Boolean =
        linkHealthOverride?.invoke() ?: (connected.value && attachedThisSession && connectJob?.isActive == true)

    // ── push registration ───────────────────────────────────────────────────────────────────────────

    /** Start platform push registration once, after the first relay attach (so the iOS permission prompt
     *  follows pairing). The token callback may land later — [registerPush] also runs on every Attached.
     *
     *  Fleet satellites stay out: they are other machines of the SAME user, and the primary link's token
     *  already wakes this phone for them. A COLLABORATOR inbox is not a satellite (§3.4) — it is a different
     *  person's daemon, which can only reach this phone through the token registered under the INBOX's own
     *  deviceId (the owner's account fan-out deliberately excludes it). So an inbox registers for itself,
     *  sharing the one platform token through [PushTokens] rather than fighting the primary over the
     *  single-callback [PushController]. */
    private fun ensurePushStarted() {
        if (pinnedTo != null && !isCollaboratorInbox) return
        if (pushStarted || !notificationsOn.value) return
        pushStarted = true
        PushTokens.ensureStarted()
        pushTokenJob = scope.launch { PushTokens.token.collect { t -> t?.let(::onPushToken) } }
    }

    /** Platform token callback (and the test seam for it): remember the token, then (re)register. */
    internal fun onPushToken(token: PushToken) { pushToken = token; registerPush() }

    /** (Re)send the push token — or an empty token to de-register when notifications are off — to the
     *  relay, whose store is the single push-routing truth. On the relay transport it rides the live
     *  control plane. The direct-LAN transport has NO relay control plane, and a phone that always finds
     *  its daemon on the LAN never relay-attaches — "register on the next real relay attach" never comes,
     *  so its token (and every APNs/FCM rotation) would rot server-side forever (#114 follow-up): instead
     *  the direct path deposits the token through a one-shot [RelayControlDial]. Skips when unchanged
     *  since the last send (the relay persists it), so the foreground-triggered reconnect storm doesn't
     *  rewrite the same row; a failed dial rolls that guard back (+ one timed retry that self-arms while
     *  the direct link stays up) so the token still converges. */
    private fun registerPush() {
        if (!useRelay) return // unpaired dev-direct transport: no relay account to deposit to
        val tok = pushToken
        // OFF must converge even with no platform token in hand. A cold start with notifications already
        // off never starts the platform stack (starting it is what prompts on iOS), so [pushToken] stays
        // null — and if the clearing frame was lost the first time (killed app, drained outbox) the relay
        // would keep a live token forever while the UI reads "off". The relay ignores the platform of a
        // blank-token register (it nulls both columns), so the last known one — or a placeholder — clears
        // the row just as well. §3.4 makes this consequential: a contact's daemon is now a pusher too.
        val sent = when {
            !notificationsOn.value -> (tok?.platform ?: lastPushPlatform() ?: "unknown") to ""
            tok != null -> tok.platform to tok.token
            else -> return // ON but no token yet — the platform callback will re-enter here
        }
        if (sent == pushRegistered) return
        if (sent.second.isNotEmpty()) SecureStore.putString(K_PUSH_PLATFORM, sent.first)
        pushRegistered = sent
        if (directLinkUp()) {
            val p = paired.value ?: return
            pushDialJob?.cancel() // a newer (platform, token) supersedes an in-flight dial/retry
            pushDialJob = scope.launch {
                val err = runCatching { pushDial(p, RegisterPush(sent.first, sent.second)) }.exceptionOrNull() ?: return@launch
                if (err is CancellationException) throw err // superseded — the newer dial owns the dedup state
                if (pushRegistered == sent) pushRegistered = null // roll back the dedup: this send never landed
                if (err is RelayAuthException) return@launch // a revoked credential won't fix itself on a timer
                delay(pushDialRetryMs)
                if (directLinkUp() && pushRegistered == null) registerPush()
            }
        } else {
            scope.launch { runCatching { relay.sendControl(RegisterPush(sent.first, sent.second)) } }
        }
    }

    /** The platform tag of the last token we registered, so a later "off" can still clear the relay row
     *  even when this launch never obtained a token. Not a secret and not the token itself. */
    private fun lastPushPlatform(): String? = SecureStore.getString(K_PUSH_PLATFORM)?.takeIf { it.isNotBlank() }

    /**
     * Clear THIS link's push token at the relay without touching the device-wide preference — used when a
     * Collaborator Link is severed. The credential is about to be discarded, so the token registered under
     * that colleague's account has to go with it; otherwise their daemon keeps pushing at a link we no
     * longer hold, and only the OWNER revoking the contact would ever stop it.
     */
    fun deregisterPush() {
        if (!useRelay) return
        val platform = pushToken?.platform ?: lastPushPlatform() ?: "unknown"
        pushRegistered = platform to ""
        val p = paired.value
        scope.launch {
            runCatching {
                if (directLinkUp() && p != null) pushDial(p, RegisterPush(platform, ""))
                else relay.sendControl(RegisterPush(platform, ""))
            }
        }
    }

    /** Settings toggle: persist the choice, then register (on) or clear (off) the token on the relay. */
    fun setNotificationsEnabled(on: Boolean) {
        if (on == notificationsOn.value) return
        notificationsOn.value = on
        SecureStore.putString(K_NOTIFY, if (on) "1" else "0")
        ensurePushStarted() // self-guards when off
        registerPush()
        onNotificationsChanged?.invoke(on) // §3.4: contacts' inbox links hold their own tokens
    }

    /** Settings: persist the default execution mode for new sessions. Takes effect on the next new session. */
    fun setDefaultMode(m: PermissionMode) {
        if (m == defaultMode.value && defaultPermissionMode.value == null) return
        defaultMode.value = m
        defaultPermissionMode.value = null
        SecureStore.putString(K_DEFAULT_MODE, m.name)
        SecureStore.putString(K_DEFAULT_PERMISSION_MODE, "")
    }

    /** Settings: persist Claude Code's classifier-driven `auto` permission mode. */
    fun setDefaultAutoMode() {
        defaultMode.value = PermissionMode.DEFAULT // safe fallback for an old daemon
        defaultPermissionMode.value = CLAUDE_PERMISSION_MODE_AUTO
        SecureStore.putString(K_DEFAULT_MODE, PermissionMode.DEFAULT.name)
        SecureStore.putString(K_DEFAULT_PERMISSION_MODE, CLAUDE_PERMISSION_MODE_AUTO)
    }

    /** Projects screen: persist the browse mode (true = tree, false = flat). */
    fun setTreeView(on: Boolean) {
        if (on == treeView.value) return
        treeView.value = on
        SecureStore.putString(K_VIEW_MODE, if (on) "tree" else "flat")
    }

    /** Legacy Claude entry point retained for source/storage compatibility. */
    fun setDefaultEffort(level: String?) {
        val v = level?.takeIf { it.isNotEmpty() }
        if (v == defaultEffort.value) return
        defaultEffort.value = v
        SecureStore.putString(K_DEFAULT_EFFORT, v ?: "")
    }

    /** Settings: persist an agent-scoped default effort. No backend may overwrite another one's choice. */
    fun setDefaultEffortFor(agent: AgentKind, level: String?) {
        if (agent == AgentKind.CLAUDE) {
            setDefaultEffort(level)
            return
        }
        val v = level?.takeIf { it.isNotEmpty() }
        val (state, key) = when (agent) {
            AgentKind.CLAUDE -> error("handled above")
            AgentKind.CODEX -> defaultCodexEffort to K_DEFAULT_CODEX_EFFORT
            AgentKind.OPENCODE -> defaultOpenCodeEffort to K_DEFAULT_OPENCODE_EFFORT
            AgentKind.KIMI -> defaultKimiEffort to K_DEFAULT_KIMI_EFFORT
            AgentKind.ZCODE -> defaultZCodeEffort to K_DEFAULT_ZCODE_EFFORT
            AgentKind.DSH -> defaultDshEffort to K_DEFAULT_DSH_EFFORT
        }
        if (v == state.value) return
        state.value = v
        SecureStore.putString(key, v ?: "")
    }

    fun setDefaultServiceTier(tier: String?) {
        val v = tier?.trim()?.takeIf { it.isNotEmpty() }
        if (v == defaultServiceTier.value) return
        defaultServiceTier.value = v
        SecureStore.putString(K_DEFAULT_SERVICE_TIER, v ?: "")
    }

    /** Mobile Settings' legacy Claude-only entry point. */
    fun setDefaultModel(id: String?) {
        setDefaultModelFor(AgentKind.CLAUDE, id)
    }

    /** Settings: persist a backend-scoped default model (null = that CLI's own default). */
    fun setDefaultModelFor(agent: AgentKind, id: String?) {
        val v = id?.trim()?.takeIf { it.isNotEmpty() }
        if (v != null && !isModelCompatibleWithAgent(agent, v)) return
        val state = when (agent) {
            AgentKind.CLAUDE -> defaultModel
            AgentKind.CODEX -> defaultCodexModel
            AgentKind.OPENCODE -> defaultOpenCodeModel
            AgentKind.KIMI -> defaultKimiModel
            AgentKind.ZCODE -> defaultZCodeModel
            AgentKind.DSH -> defaultDshModel
        }
        if (v == state.value) return
        state.value = v
        reconcileDefaultCapabilities(agent)
        SecureStore.putString(
            when (agent) {
                AgentKind.CLAUDE -> K_DEFAULT_MODEL
                AgentKind.CODEX -> K_DEFAULT_CODEX_MODEL
                AgentKind.OPENCODE -> K_DEFAULT_OPENCODE_MODEL
                AgentKind.KIMI -> K_DEFAULT_KIMI_MODEL
                AgentKind.ZCODE -> K_DEFAULT_ZCODE_MODEL
                AgentKind.DSH -> K_DEFAULT_DSH_MODEL
            },
            v ?: "",
        )
    }

    /** Settings: persist the context-window override (tokens; null or ≤0 = follow the derived window). Sits ahead
     *  of the daemon's value because the CLI can't report a custom model's real window. Re-applied to the open
     *  session right away so its % updates without waiting for the next SessionLive/relaunch (issue #60). */
    fun setContextWindowOverride(tokens: Long?) {
        val v = tokens?.takeIf { it > 0 }
        if (v == contextWindowOverride.value) return
        contextWindowOverride.value = v
        SecureStore.putString(K_CONTEXT_WINDOW_OVERRIDE, v?.toString() ?: "")
        reapplyContextWindow()
    }

    /** Settings: pin the denominator for ONE model (issue #169; tokens null or ≤0 = drop the entry and fall back to
     *  the catch-all / derived window). This is the write path that knows what it is measuring — a window belongs to
     *  a model, so two sessions on different models no longer share one number. No-op without a model to key on:
     *  silently writing to the catch-all instead would recreate the exact bleed this replaces. */
    fun setContextWindowOverrideFor(model: String?, tokens: Long?) {
        val key = modelKey(model) ?: return
        val v = tokens?.takeIf { it > 0 }
        if (v == contextWindowOverrides[key]) return
        if (v == null) contextWindowOverrides.remove(key) else contextWindowOverrides[key] = v
        SecureStore.putString(
            K_CONTEXT_WINDOW_OVERRIDES,
            // sorted so the stored blob is stable across writes (diffable, and no spurious keychain churn)
            contextWindowOverrides.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" },
        )
        reapplyContextWindow()
    }

    /** Settings: persist the default agent backend that new sessions start under. */
    fun setDefaultAgent(a: AgentKind) {
        if (a == defaultAgent.value) return
        defaultAgent.value = a
        SecureStore.putString(K_DEFAULT_AGENT, a.name)
    }

    /** Settings: persist the project/session agent filter (the set of backends to show; #248). */
    fun setAgentFilter(selected: Set<AgentKind>) {
        val v = if (selected.isEmpty()) ALL_AGENTS else selected.toSet() // empty would hide everything with no way back
        if (v == agentFilter.value) return
        agentFilter.value = v
        SecureStore.putString(K_AGENT_FILTER, encodeAgentFilter(v))
    }

    /** Back to "show every agent" — the one call the removable chip and the Projects empty state share. */
    fun clearAgentFilter() = setAgentFilter(ALL_AGENTS)

    /** Settings: persist the chat text scale (clamped to the slider range). Applies live to every message. */
    fun setFontScale(scale: Float) {
        val v = scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        if (v == fontScale.value) return
        fontScale.value = v
        SecureStore.putString(K_FONT_SCALE, v.toString())
    }

    /** Silent window before declaring [ConnPhase.RelayUnreachable]. A first connect shows the skeleton for a
     *  beat; a reconnect already keeps the old list under a banner, so it tolerates a longer quiet window. */
    private fun startGrace(reconnect: Boolean) {
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(if (reconnect) RECONNECT_GRACE_MS else FIRST_GRACE_MS)
            if (sessionActive.value && !attachedThisSession && !hadReadyThisSession && !pairingInvalid) {
                relayDeadlinePassed = true
                recomputePhase()
            }
        }
    }

    /** Briefly hold the Ready look on a drop from a healthy link so a fast re-attach doesn't flash the
     *  Reconnecting banner (#28). [restart] forces a fresh window (used on foreground return); otherwise it
     *  arms once per reconnect episode, so a genuinely stuck reconnect still surfaces the banner after the window. */
    private fun startReconnectGrace(restart: Boolean) {
        if (!restart && (reconnectGracePassed || reconnectGraceJob?.isActive == true)) return
        reconnectGracePassed = false
        reconnectGraceJob?.cancel()
        reconnectGraceJob = scope.launch {
            delay(RECONNECT_BANNER_GRACE_MS)
            reconnectGracePassed = true
            recomputePhase()
        }
    }

    /** After [Attached], wait briefly for the first Directories. The relay sends no daemon-presence snapshot
     *  on attach, so a silent computer (offline / zombie daemon) would otherwise hang on the skeleton — escalate
     *  to ComputerOffline. A real Directories reply (handle()) cancels this and proves the computer is online. */
    private fun startListWait() {
        listWaitJob?.cancel()
        listWaitJob = scope.launch {
            delay(LIST_WAIT_MS)
            val listed = if (isCollaboratorInbox) handoffsLoaded.value else directoriesLoaded.value
            if (sessionActive.value && attachedThisSession && !listed && !daemonOffline && !pairingInvalid) {
                // §3.2.3: an inbox link must NEVER report "computer offline" — it is a background contact
                // channel with no screen of its own, and a quiet colleague's machine is not an app error.
                // The deaf-link re-handshake below still runs: that's a real self-heal, not a claim.
                if (!isCollaboratorInbox) daemonOffline = true
                recomputePhase()
                // A silent computer here is EITHER really offline or a DEAF E2E link: the daemon keeps one
                // session per device, so if another of this device's sockets (fleet satellite, reconnect
                // overlap) re-keyed it, both sides silently drop every frame while the relay socket still
                // pings fine. One forced re-handshake per episode tells the cases apart — a deaf link comes
                // back alive; a truly offline computer costs one extra handshake and stays on this screen.
                if (!listWaitRetried) {
                    listWaitRetried = true
                    launchTransport(reconnect = true, force = true) // deliberate teardown of a live-but-deaf link — never coalesced (#143)
                }
            }
        }
    }

    /** (Re)open the active transport's socket. Both transports re-handshake on every connect() call.
     *  [force] bypasses the #143 coalescing — for triggers that deliberately tear down a LIVE socket
     *  (the deaf-link retry, the presence probe's escalation, the user's manual "Try again"). */
    private fun launchTransport(reconnect: Boolean, force: Boolean = false) {
        if (demoMode.value) return // demo mode never touches the network
        // #143: five triggers fire this independently (presence edge, foreground return, retry timer,
        // list-wait, manual retry) and don't know about each other — while an attempt is already in
        // flight, later triggers inside the window merge into it instead of stacking another socket +
        // reattach volley into the cross-reconnect outbox.
        if (shouldCoalesceReconnect(force, reconnect, connectJob?.isActive == true, epochMillis() - lastTransportLaunchAt)) return
        lastTransportLaunchAt = epochMillis()
        transportLaunches++
        presenceProbeJob?.cancel(); presenceProbeJob = null // a full relaunch moots the #145 probe
        connected.value = true // internal "attempt active/attached" guard for retry/foreground — NOT the UI
        attachedThisSession = false; daemonOffline = false; relayDeadlinePassed = false; listWaitJob?.cancel()
        if (!reconnect) { pairingInvalid = false; hadReadyThisSession = false; directoriesLoaded.value = false; handoffsLoaded.value = false }
        recomputePhase() // Connecting, or Reconnecting if we were Ready before — recomputePhase is the sole writer of phase
        status.value = StatusMsg(if (reconnect) Res.string.status_reconnecting else Res.string.status_connecting)
        if (inboundJob == null) {
            inboundJob = scope.launch {
                // only the transport that's actually connected emits — merging idle flows is free
                merge(relay.inbound, direct.inbound, directE2E.inbound).collect { handle(it) }
            }
        }
        if (controlJob == null) {
            controlJob = scope.launch {
                merge(relay.control, direct.control, directE2E.control).collect { handleControl(it) }
            }
        }
        if (deafJob == null) {
            // #146: a live-but-deaf E2E socket (the daemon's reconnect-overlap flipped its seal onto a
            // session we can't open) can't self-heal from the passive-observer side — onTransportDown never
            // fires because the WS still pings. Force a re-handshake so the daemon re-keys onto this socket.
            deafJob = scope.launch {
                merge(relay.deaf, directE2E.deaf).collect { onDeafLink() }
            }
        }
        val prev = connectJob
        connectJob = scope.launch {
            // #142: retire the old socket BEFORE dialing. cancel() alone is cooperative — the old
            // client.webSocket{} body (whose writer drains the shared cross-reconnect outbox) can outlive
            // it, so the same deviceId briefly runs TWO relay sockets: the relay supersede-kicks the old
            // one (mutual-kick loop) while both writers split queued frames across the two links. The
            // join is bounded — a wedged close must not stall reconnecting — and the connection-side
            // generation guard (connSeq in both E2E connections) fences any straggler past the bound.
            retireJobBounded(prev, SOCKET_RETIRE_TIMEOUT_MS)
            val result = runCatching {
                if (useRelay) {
                    val p = paired.value ?: error("not paired")
                    // direct-first: the daemon-advertised LAN/loopback address skips the relay AND the
                    // proxy leg entirely. Unreachable/refused/bad handshake → silent same-attempt relay
                    // fallback + cooldown. A drop AFTER it was live exits normally into the reconnect path.
                    val du = p.directUrl?.takeIf { it != badDirectUrl[p.accountId] }
                    if (du != null && epochMillis() >= (directCooldownUntil[p.accountId] ?: 0L)) {
                        directAttemptInFlight = true
                        try {
                            directE2E.connect(du, p, Pairing.deviceKeys())
                            return@runCatching
                        } catch (e: DirectUnreachableException) {
                            directCooldownUntil[p.accountId] = epochMillis() + DIRECT_RETRY_COOLDOWN_MS
                            if (e.keyMismatch) { // wrong daemon at that address — retries can never succeed
                                badDirectUrl[p.accountId] = du
                                rememberDirectUrl(p.accountId, null)
                            }
                        } finally {
                            directAttemptInFlight = false
                        }
                    }
                    // frames optimistically queued for the direct leg (incl. this attempt's ListDirectories)
                    // ride the relay instead — nothing silently evaporates in the fallback
                    directE2E.drainPending().forEach { relay.send(it) }
                    // Consumed per ATTEMPT, not per normal return (#298 security review, Medium 2): an
                    // abnormal socket death — DeadLinkException, or the forced relaunch #146/#298 fire —
                    // used to skip a trailing `.also` and re-feed the STALE ticket into every retry. The
                    // daemon burns its pskFor on first confirm (#161), so each retry then handshook with
                    // mismatched PSKs: silent zombie on pairing day, which the silence watchdog would
                    // re-trip forever without converging. The daemon's #161 twin exists to absorb exactly
                    // this asymmetry — consuming on attempt is the contract its comment already assumes.
                    val t = firstTicket
                    firstTicket = null
                    relay.connect(p, Pairing.deviceKeys(), t)
                } else {
                    direct.connect(lastDirectUrl ?: error("no direct url"))
                }
            }
            val err = result.exceptionOrNull()
            if (err is CancellationException) return@launch // intentional disconnect/relaunch — not a failure
            onTransportDown(err)
        }
        // ask for the list now; it buffers in the outbox and flushes once the handshake lands. Ready is
        // asserted only when the real Directories reply arrives (see handle()), never optimistically here.
        // On a reconnect, also re-sync whatever page the user is parked on (re-open a live chat, re-list sessions).
        // ClientCaps FIRST: it declares this build understands agent="opencode", so the daemon stops
        // filtering those rows out of the lists that follow (old builds never send it — see Messages.kt).
        scope.launch {
            if (isCollaboratorInbox) {
                // §3.2.3: a collaborator credential may send exactly one thing on connect — an UNFILTERED
                // ListHandoffs, which the daemon answers with (and registers a fan-out sink for) only the
                // offers addressed to THIS device. Sending the ordinary volley here would be three refusals.
                send(ListHandoffs())
            } else {
                send(ClientCaps(supportsAgents = listOf(AGENT_WIRE_OPENCODE, AGENT_WIRE_KIMI, AGENT_WIRE_ZCODE, AGENT_WIRE_DSH), supportsApprovalV2 = true))
                send(ListDirectories())
                send(ListPendingApprovals)
            }
            if (reconnect) restoreAfterReconnect()
        }
        startGrace(reconnect)
        startConnectWatchdog()
    }

    /**
     * A connect that never reaches [Attached] within [CONNECT_TIMEOUT_MS] is wedged — a known mobile failure
     * where the socket hangs pre-attach (QUIC/TCP black-hole or a stalled handshake) WITHOUT ever erroring,
     * so [onTransportDown] never fires and nothing retries until the user manually re-opens. Force a teardown +
     * backoff retry so it self-heals. Self-guards: a clean failure already flipped connected=false (no-op),
     * and an Attached flips attachedThisSession (no-op) — so this only bites the silent-hang case.
     */
    private fun startConnectWatchdog() {
        connectWatchdog?.cancel()
        connectWatchdog = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (sessionActive.value && connected.value && !attachedThisSession && !pairingInvalid) {
                connectJob?.cancel()
                onTransportDown(ConnectWedgedException())
            }
        }
    }

    /** The socket died. Stay on the current screen; banner + backoff retries take it from here. */
    private fun onTransportDown(err: Throwable?) {
        connected.value = false
        linkStableJob?.cancel(); linkStableJob = null // #144: this link died — it never (or no longer) counts as stable
        presenceProbeJob?.cancel(); presenceProbeJob = null // #145: the retry path owns recovery from here
        if (!sessionActive.value) { // an intentional teardown is not a connection failure — don't report it
            status.value = err?.let { StatusMsg(Res.string.status_failed, it.message ?: it::class.simpleName ?: "error") }
                ?: StatusMsg(Res.string.status_disconnected)
            return
        }
        val reason = when (err) {
            null -> "closed"
            is RelayAuthException -> "auth"
            is ConnectWedgedException -> "wedged"
            else -> err::class.simpleName ?: "error"
        }
        Telemetry.track(TelEvent.ConnFailed, mapOf(TelKey.Transport to transportName(), TelKey.Reason to reason, TelKey.Attempt to retryAttempts))
        if (err is RelayAuthException || pairingInvalid) { // expired/invalid pairing — re-pair, never auto-retry
            pairingInvalid = true; recomputePhase(); return
        }
        if (hadReadyThisSession) startReconnectGrace(restart = false) // a blip holds Ready briefly before the banner (#28)
        status.value = StatusMsg(Res.string.status_conn_lost)
        recomputePhase() // hadReady -> Reconnecting (keep list); else stays Connecting until grace -> RelayUnreachable
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (pairingInvalid) return // an expired pairing won't fix itself by retrying
        retryJob?.cancel()
        val delayMs = (1000L shl retryAttempts.coerceAtMost(5)).coerceAtMost(30_000) // 1s 2s 4s 8s 16s 30s…
        retryAttempts++
        retryJob = scope.launch {
            delay(delayMs)
            if (sessionActive.value && !connected.value) launchTransport(reconnect = true)
        }
    }

    /** #144: the ladder above used to reset on any single healthy round-trip (and on every foreground
     *  return), so a flapping link reconnected at max frequency forever — every drop restarted at 1s and
     *  each reconnect fed the #142/#145 storm. Now the ladder only resets after the link PROVES stable by
     *  staying up [stableLinkResetMs]; a link that keeps dying young keeps climbing toward 30s. Armed on
     *  every attach edge (and, for the dev-direct transport, on its first Directories). */
    private fun armLinkStableReset() {
        linkStableJob?.cancel()
        linkStableJob = scope.launch {
            delay(stableLinkResetMs)
            if (sessionActive.value && connected.value && attachedThisSession) retryAttempts = 0
        }
    }

    /** App came to the foreground (iOS suspends sockets in background) — reconnect NOW; the backoff
     *  ladder deliberately survives the return (#144 — resetting it here let a flapping link hammer). */
    fun onAppForeground() {
        if (demoMode.value || pairingInvalid) return
        if (sessionActive.value && !connected.value) {
            retryJob?.cancel()
            if (hadReadyThisSession) startReconnectGrace(restart = true) // fresh Ready-hold on return so a quick reconnect shows no banner (#28)
            launchTransport(reconnect = true)
        } else if (sessionActive.value && connected.value) {
            // `connected` may be a lie after a background suspension (the heartbeat was frozen; the TCP died
            // silently). Exercise a WRITE right now: healthy link → this merely refreshes the stale project
            // list; wedged link → the bounded send trips DeadLink in ≤10s instead of ~25s of fake Ready.
            // Inbox links do the same with the one frame they're allowed — which doubles as the §3.2.3
            // "foreground → re-pull ListHandoffs" requirement (a missed offer push heals here).
            if (isCollaboratorInbox) refreshHandoffsSilently() else refreshDirectoriesSilently()
        }
    }

    /** Manual "Try again" from the RelayUnreachable / ComputerOffline screens. Human-paced, so the
     *  explicit backoff reset stays; force = the user's tap must win over the #143 coalescing. */
    fun retryConnection() {
        if (!sessionActive.value || pairingInvalid) return
        retryJob?.cancel()
        retryAttempts = 0
        relayDeadlinePassed = false
        launchTransport(reconnect = true, force = true)
    }

    /** After the link is back: re-sync whatever page the user is parked on; reattach a live chat. */
    private suspend fun restoreAfterReconnect() {
        val sid = currentSessionId
        val wd = workdir.value
        val dir = sessionsDir.value
        val convo = convoId.value
        // §3.2.8: offer/accept/decline/return/recall/expire are all recovered from DAEMON TRUTH after a
        // reconnect — the inbox re-pulls its whole list rather than trusting whatever it held locally.
        if (isCollaboratorInbox) send(ListHandoffs())
        when {
            // daemon finds the still-live conversation by sessionId → reattach + history replay, which the
            // ConvoHistory MERGE turns into a backfill of whatever streamed while the link was down (#107)
            convo != null && sid != null && wd != null -> {
                // an observe view re-opens the same way (the daemon mints a fresh ObserveSession — or a
                // controllable resume if the terminal quit meanwhile). Close the stale observer first: its
                // sink revives with this reconnected device, and an old daemon would keep BOTH observers
                // tailing — two SessionLive/ConvoHistory streams ping-ponging the phone between convoIds.
                if (observing.value) send(CloseSession(convo))
                // lastEventSeq (issue #147): we still hold this session's transcript — ask for the delta
                send(OpenSession(wd, sid, mode = mode.value, agent = sessionAgent.value ?: AgentKind.CLAUDE, lastEventSeq = lastEventSeqFor(sid)))
            }
            dir != null -> send(ListSessions(dir))
            else -> {} // directory list already refreshed by launchTransport
        }
    }

    /** Drop the live connection and return to the Connect screen (pairing is kept). */
    fun disconnect() {
        sessionActive.value = false
        // An OpenSession worker belongs to the link/computer that accepted the click. It may still be
        // queued (or suspended on a full reconnect outbox), so invalidating only the claim is insufficient:
        // the worker could wake after drainPending() and enqueue the old machine's open into the next link.
        openGen++
        openJob?.cancel(); openJob = null
        retryJob?.cancel(); connectJob?.cancel(); inboundJob?.cancel(); controlJob?.cancel(); deafJob?.cancel(); graceJob?.cancel(); listWaitJob?.cancel(); connectWatchdog?.cancel(); reconnectGraceJob?.cancel(); linkStableJob?.cancel(); presenceProbeJob?.cancel()
        retryJob = null; connectJob = null; inboundJob = null; controlJob = null; deafJob = null; graceJob = null; listWaitJob = null; connectWatchdog = null; reconnectGraceJob = null; linkStableJob = null; presenceProbeJob = null
        clearPromptLifecycleState() // pending bubbles and every related deadline leave with messages below
        // frames queued for the binding we're leaving must not leak into the next link (both transports
        // are reused across machine switches, and their outboxes deliberately buffer across reconnects)
        directAttemptInFlight = false
        directE2E.drainPending(); relay.drainPending()
        connected.value = false
        phase.value = ConnPhase.Connecting
        pendingOpen = null // a queued push-tap target is moot once the user drops the connection
        // #235: so is the open claim — carrying it across a reconnect would make the same row's next click
        // a permanent no-op, and its retry target names a machine we may never come back to
        openInFlight = null; lastOpenAttempt = null; pendingNewOpenWd = null
        opening.value = false; switchingSession.value = false; openTimedOut.value = false
        sessionNavigationFenced = true
        attachedThisSession = false; daemonOffline = false; pairingInvalid = false
        hadReadyThisSession = false; relayDeadlinePassed = false; reconnectGracePassed = false; listWaitRetried = false; directoriesLoaded.value = false
        handoffsLoaded.value = false // inbox mode's readiness proof dies with the link, same as the list
        clearReviewState() // a review ledger belongs to one machine — never show the last daemon's inbox
        pushDialJob?.cancel(); pushDialJob = null // an in-flight LAN-side token dial dies with the link
        // the shared-token observer dies with the link too (an inbox link that was removed must not be kept
        // alive by a collector); pushStarted re-arms it on the next Attached. PushTokens.ensureStarted() is
        // globally once-only, so this never re-triggers the iOS permission prompt.
        pushTokenJob?.cancel(); pushTokenJob = null; pushStarted = false
        pushRegistered = null // a fresh connect (or a switched daemon) must re-register the token
        // per-daemon truth must not survive a machine switch: a stale non-null presetsState would keep
        // the token-bearing create/edit form UNLOCKED after switching to a daemon that predates presets
        // (it silently drops FetchPresets), breaking "never fire a plaintext token at a peer that can't
        // store it". authState clears for the same reason — the next daemon's account is a fresh fetch.
        authState.value = null
        presetsState.value = null; presetsStateRev.value = 0
        agentModels.clear() // model/effort capabilities belong to the daemon we just left; UNKNOWN on the next one until it replies
        gatewayBaseUrl.value = null // per-daemon truth (issue #139): the next machine re-announces via DaemonInfo
        bridgeControl.value = null  // per-daemon truth too — the next daemon re-advertises via DaemonInfo (issue #91)
        daemonSupportedAgents.value = emptySet() // reverse agent capability: no stale ZCode across machines
        daemonAgentsKnown = false // #276: back to "not told yet" — the guard must not deny during reconnect
        daemonUsageAgentFilter.value = false // ditto (issue #258): the next machine re-advertises its own
        // per-daemon truth: the allowance belongs to the ACCOUNT on the machine we just left. Showing it
        // under the next machine's name would be a straight lie about a billing number.
        claudeQuotaDeadline?.cancel(); claudeQuota.value = null; claudeQuotaLoading.value = false; claudeQuotaStatus.value = null
        daemonOwnsPromptRecovery = false // ditto: an older next daemon still needs the legacy fallback
        versionStatus.value = VersionStatus(APP_VERSION) // ditto (issue #200): the next machine reports its own
        // per-daemon truth too: the next machine's skills/plugins are a fresh fetch (issue #132)
        skillCatalogDeadline?.cancel()
        skillCatalog.value = null; skillCatalogLoading.value = false; skillCatalogUnavailable.value = false
        convoId.value = null
        sessionsDir.value = null
        workdir.value = null // clear with the rest so a stale path can't leak into the next machine's ⌘N (issue #56)
        clearAskQueue()
        pendingApprovals.clear()
        // #165/#239: the work→idle detector is per-MACHINE state. Carrying this machine's ids into the
        // next one's first project list would mark every one of them "finished while you were away".
        lastWorkingSessions = emptySet(); lastWorkingDirectories = emptyMap(); unseenSessions.value = emptySet()
        directories.clear(); sessions.clear(); messages.clear(); pendingImages.clear(); clearFileUploads(); clearBackgroundJobs()
        resetHistoryPaging() // #147: the transcript left with messages — so must its cursor
        demoMode.value = false // leaving the demo returns to real pairing
        demoConnecting.value = false
        abandonVoice()
        status.value = StatusMsg(Res.string.status_disconnected)
        Telemetry.track(TelEvent.Disconnected)
    }

    // ── multi-device: bind several computers, talk to one at a time ─────────────────────────────────

    /** Pair another computer without dropping the existing ones: tear down the live link, show PairingScreen. */
    fun beginAddDevice() { disconnect(); clearPairFailure(); addingDevice.value = true }

    /** Back out of "add a computer" — returns to the device picker (bindings are untouched). */
    fun cancelAddDevice() { clearPairFailure(); addingDevice.value = false }

    /**
     * Desktop "add a computer" while a session is live: pair the new binding and add it to the list, but
     * DON'T tear down or switch the current connection (unlike [beginAddDevice] + [pairWithCode], which
     * disconnect then connect to the new one). The new computer just shows up in the switcher; the user
     * switches to it when ready. [onDone] reports success so the modal can close itself.
     */
    fun addDeviceByCode(code: String, onDone: (Boolean) -> Unit = {}) {
        val keepActive = paired.value?.accountId // the computer we stay connected to
        status.value = StatusMsg(Res.string.status_pairing)
        scope.launch {
            val client = HttpClient()
            try {
                val info = Pairing.resolveCode(code.trim(), client)
                Pairing.redeem(info, Pairing.deviceKeys(), client) // upserts the list + pins the NEW account active…
                keepActive?.let { Pairing.setActive(it) }          // …undo that pin so the live session stays put
                replace(pairedList, Pairing.loadAll())
                Telemetry.track(TelEvent.Paired, mapOf(TelKey.Source to "code-add"))
                onDone(true)
            } catch (t: Throwable) {
                status.value = StatusMsg(Res.string.status_pair_failed, t.message ?: t::class.simpleName ?: "error")
                Telemetry.track(TelEvent.PairFailed, mapOf(TelKey.Reason to pairFailReason(t)))
                onDone(false)
            } finally {
                client.close()
            }
        }
    }

    /** Runs synchronously at the top of [switchDaemon]. The FleetCoordinator registers itself here (its
     *  satellite for the target machine must die BEFORE we dial it — see its class doc); a repo without a
     *  coordinator (tests, satellites, headless) has nothing to retire. Keeps the repo core off the fleet global. */
    var onBeforeSwitch: ((accountId: String) -> Unit)? = null

    /** Switch the active computer: tear down the current link, pin [target], reconnect to it.
     *  This is the COLD path — [FleetCoordinator.switchTo] promotes a hot satellite instead when it can
     *  (issue #103) and only falls back here when no live link to [target] exists yet. */
    fun switchDaemon(target: PairedDaemon) {
        if (paired.value?.accountId == target.accountId && sessionActive.value) return
        onBeforeSwitch?.invoke(target.accountId)
        disconnect()
        paired.value = target
        shareEnded.value = loadShareEnded(target.accountId) // per-account guest ending follows the switch
        loadWorkingSet(target.accountId) // #165: and so does the switcher's memory — see [workingSetMru]
        Pairing.setActive(target.accountId)
        firstTicket = null // an already-paired daemon authenticates by static key — the PSK is only for first pair
        startRelay()
    }

    // ── fleet promote (issue #103): switching machines swaps two live repos instead of re-dialing ────

    /**
     * The RISING side of a fleet promote: a satellite about to become the primary adopts the shell-level
     * state whose in-memory mirrors were seeded at CONSTRUCTION time and kept fresh only on the outgoing
     * primary — the setters persist to SecureStore, but this instance never re-reads it. Covers the
     * Settings prefs, the per-session launch-params memory, and the pairing list (renames / direct URLs /
     * host names learned after this satellite was built). Without this, promoting flips Settings back in
     * time (a theme picked after app start would snap back on the first machine switch).
     */
    internal fun adoptShellState(from: PocketRepository) {
        notificationsOn.value = from.notificationsOn.value
        defaultMode.value = from.defaultMode.value
        defaultPermissionMode.value = from.defaultPermissionMode.value
        defaultEffort.value = from.defaultEffort.value
        defaultCodexEffort.value = from.defaultCodexEffort.value
        defaultOpenCodeEffort.value = from.defaultOpenCodeEffort.value
        defaultKimiEffort.value = from.defaultKimiEffort.value
        defaultZCodeEffort.value = from.defaultZCodeEffort.value
        defaultDshEffort.value = from.defaultDshEffort.value
        defaultServiceTier.value = from.defaultServiceTier.value
        defaultModel.value = from.defaultModel.value
        defaultCodexModel.value = from.defaultCodexModel.value
        defaultOpenCodeModel.value = from.defaultOpenCodeModel.value
        defaultKimiModel.value = from.defaultKimiModel.value
        defaultZCodeModel.value = from.defaultZCodeModel.value
        defaultDshModel.value = from.defaultDshModel.value
        // This repository keeps the promoted MACHINE's capability catalog. Reconcile the shell defaults
        // copied from the outgoing machine against that truth now, so Settings and the next OpenSession
        // cannot disagree until another ModelsList happens to arrive.
        AgentKind.entries.forEach(::reconcileDefaultCapabilities)
        contextWindowOverride.value = from.contextWindowOverride.value
        contextWindowOverrides.clear(); contextWindowOverrides.putAll(from.contextWindowOverrides) // #169: per-model table travels with the rest of Settings
        defaultAgent.value = from.defaultAgent.value
        agentFilter.value = from.agentFilter.value
        treeView.value = from.treeView.value
        fontScale.value = from.fontScale.value
        themeMode.value = from.themeMode.value
        accentTheme.value = from.accentTheme.value
        voiceWhisper.value = from.voiceWhisper.value
        replace(pinnedPaths, from.pinnedPaths.toList())
        // #165: NOT copied from the outgoing primary — the working set is per-computer, and this promote is
        // precisely the moment the machine changes. Load this satellite's own instead.
        loadWorkingSet()
        sessionParams.clear(); sessionParams.putAll(from.sessionParams)
        replace(pairedList, from.pairedList.toList())
        // freshen this binding's own copy too (pinned at construction — a rename/hostName/directUrl learned
        // since then lives only in the outgoing primary's list)
        paired.value = from.pairedList.firstOrNull { it.accountId == paired.value?.accountId } ?: paired.value
    }

    /**
     * The DEMOTED side of a fleet promote: the outgoing primary becomes its machine's satellite WITHOUT
     * dropping the live link. Clears exactly the session/chat-scoped state that [disconnect] and
     * [openSession] clear — leaving a machine closes its chat view either way — but keeps the transport,
     * the loaded directory list, and the per-machine data (usage/auth) that make the link worth keeping
     * hot. The reclaim rule matches [openSession]/[backToBrowse]: an idle (or observed) conversation is
     * closed on the daemon; a RUNNING turn stays alive in the background and reattaches on the next
     * resume. [pendingOpen] must die here — a queued cross-machine open firing on a headless satellite
     * link would open a ghost session nobody is watching.
     */
    internal fun demoteToSatellite() {
        // The UI open worker is not a transport job and therefore survives the fleet swap unless it is
        // explicitly retired. A headless satellite must never execute a click queued by the old primary.
        openGen++
        openJob?.cancel(); openJob = null
        pendingNewOpenWd = null
        sessionNavigationFenced = true
        convoId.value?.let { c -> if (observing.value || !streaming.value) scope.launch { send(CloseSession(c)) } }
        pendingOpen = null
        clearPromptLifecycleState()
        convoId.value = null; currentSessionId = null; sessionKey.value = null
        workdir.value = null // same reason as disconnect(): a stale path must not leak into a later ⌘N (issue #56)
        sessionsDir.value = null; sessions.clear()
        chatTitle.value = null; observing.value = false; streaming.value = false
        opening.value = false; openTimedOut.value = false; switching.value = false; switchingSession.value = false
        openInFlight = null; lastOpenAttempt = null // #235: the claim + its retry target belong to the machine we're leaving
        autoFocusComposer.value = false
        clearAskQueue()
        messages.clear(); pendingImages.clear()
        resetHistoryPaging() // #147
        terminalEntries.clear(); terminalBusy.value = false
        changedFiles.clear(); changedFilesLoading.value = false; changedFilesUnavailable.value = false
        closeFileViewer()
        clearGitState() // the Git panel is per-session too (#280/#281)
        pathListing.value = null
        allowRules.clear()
        slashCommands.clear()
        clearBackgroundJobs()
        sessionDegraded.value = false; degradedSendArmed = false
        model.value = null; effort.value = null; sessionAgent.value = null
        contextUsed.value = null; contextWindow.value = null
        refreshing.value = false; sessionsRefreshing.value = false
        abandonVoice()
    }

    /** Write-through for a binding's stored direct URL: persist, refresh the list, patch the active copy.
     *  Uses [Pairing.setDirectUrl]'s returned list — no second store read. */
    private fun rememberDirectUrl(accountId: String, url: String?) {
        replace(pairedList, Pairing.setDirectUrl(accountId, url))
        paired.value?.takeIf { it.accountId == accountId }?.let { paired.value = it.copy(directUrl = url) }
    }

    /** Write-through for a binding's daemon-reported computer name (issue #62): persist, refresh the list,
     *  patch the active copy — same seam as [rememberDirectUrl]. */
    private fun rememberHostName(accountId: String, name: String?) {
        replace(pairedList, Pairing.setHostName(accountId, name))
        paired.value?.takeIf { it.accountId == accountId }?.let { paired.value = it.copy(hostName = name) }
    }

    /** Give a binding a local nickname (blank clears it). */
    fun renameDaemon(target: PairedDaemon, label: String?) {
        val list = Pairing.rename(target.accountId, label)
        replace(pairedList, list)
        if (paired.value?.accountId == target.accountId) paired.value = list.firstOrNull { it.accountId == target.accountId }
    }

    /** Remove one binding. If it was active, fall back to another (or to PairingScreen when none remain). */
    fun unpair(target: PairedDaemon) {
        val wasActive = paired.value?.accountId == target.accountId
        val remaining = Pairing.remove(target.accountId) // also re-points the active account if it was this one
        replace(pairedList, remaining)
        SecureStore.remove(K_SHARE_ENDED_PREFIX + target.accountId) // a removed binding's guest ending goes with it
        if (wasActive) { disconnect(); paired.value = remaining.lastOrNull(); shareEnded.value = loadShareEnded(paired.value?.accountId) }
    }

    /** Remove the currently active binding (the "re-pair" escape hatch when a pairing goes invalid). */
    fun unpairActive() { paired.value?.let { unpair(it) } }

    internal var onSendForTest: ((Frame) -> Unit)? = null // test seam: observe outbound frames (issue #104 resend)

    /** The backend an outbound frame targets, for the reverse capability guard in [send]; null = not agent-scoped. */
    private fun agentCarried(frame: Frame): AgentKind? = when (frame) {
        is OpenSession -> frame.agent
        is ScheduleCreate -> frame.agent
        is FetchModels -> frame.agent
        is ListSessionFiles -> frame.agent
        is ReadFile -> frame.agent
        is ExportFile -> frame.agent
        is ReadFileDiff -> frame.agent
        is CreateHandoff -> frame.agent
        is FetchUsage -> frame.agent
        else -> null
    }

    /** All outbound frames funnel here; a throw means the link is dead — trigger the reconnect path. */
    private suspend fun send(frame: Frame) {
        // Reverse capability guard (#275/#276): an old daemon coerces the unknown `zcode` enum to the Claude
        // default, so never fire an agent-carrying frame at a daemon that lacks
        // that backend — but ONLY once the daemon has actually told us, THIS connection, what it supports.
        // Pre-DaemonInfo the set is empty for the ordinary reason "not told yet", not "unsupported"; dropping
        // then silently killed ZCode reattach on every reconnect (no SessionLive, no retry). In that window
        // let it through — a genuinely absent backend is refused by the daemon with agent_unavailable, the
        // proper channel. The new-session picker is already gated upstream in openSession(), so this seam
        // backstops every lower-level session, schedule, model, file, handoff, and usage path.
        agentCarried(frame)?.let { if (daemonAgentsKnown && !supportsAgent(it)) return }
        onSendForTest?.invoke(frame)
        if (demoMode.value) { demoRespond(frame); return } // no network: synthesize the daemon's reply locally
        try {
            when {
                // direct leg live (or being dialed) FOR THIS BINDING: queue there — on fallback, drainPending
                // re-routes to the relay. The account guard matters on a machine switch: the old machine's
                // socket dies asynchronously, so `connected` alone can still read true while we're already
                // dialing the new machine — routing there would strand the frame in a dead outbox.
                useRelay && (directAttemptInFlight || (directE2E.connected && directE2E.account == paired.value?.accountId)) ->
                    directE2E.send(frame)
                useRelay -> relay.send(frame)
                else -> direct.send(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onTransportDown(t)
        }
    }

    // ── demo mode ─────────────────────────────────────────────────────────
    // A no-pairing, no-account, fully on-device walkthrough (App Store review + first-run preview).
    // It reuses the real path: outbound frames are answered by [demoRespond] with sample ToPhone frames
    // fed back through handle(), so the UI state machine behaves exactly as it does over a live link.
    private var demoSeq = 0L
    private var demoAsked = false        // the one-time tool + permission demo has fired this session
    private var demoPendingReply = false // a turn is paused on the demo permission prompt

    /** Enter the demo: seed the project list + slash commands, then render like a connected session. */
    fun enterDemo() {
        demoMode.value = true
        // Demo has no handshake, so explicitly emulate a current daemon rather than inheriting the
        // disconnected socket's deny-by-default capability state.
        daemonSupportedAgents.value = DAEMON_SUPPORTED_AGENT_WIRES.toSet()
        daemonAgentsKnown = true // #276: demo asserts a current daemon's caps — the guard may act on them
        daemonUsageAgentFilter.value = true // a current daemon honors the usage filter (issue #258)
        daemonOwnsPromptRecovery = true // demo emulates the current daemon's prompt-lifecycle contract
        demoAsked = false
        sessionActive.value = true
        replace(slashCommands, DemoData.commands())
        // preview mode opens with a connecting → encrypted animation; normal demo goes straight to the list
        if (isPreviewMode()) demoConnecting.value = true
        else handle(Directories(DemoData.dirs())) // drives phase -> Ready via the normal handle() path
    }

    /** Preview opener finished — reveal the project list via the normal handle() path. */
    fun finishDemoConnect() {
        demoConnecting.value = false
        handle(Directories(DemoData.dirs()))
    }

    /** Synthesize the daemon's reply to an outbound [frame] from local sample data. */
    private suspend fun demoRespond(frame: Frame) {
        when (frame) {
            is ListDirectories -> handle(Directories(DemoData.dirs()))
            // @-file completion (issue #75): a small on-device tree so the demo composer's @ menu works
            is ListPathEntries -> handle(
                PathEntries(
                    workdir = frame.workdir,
                    subPath = frame.subPath,
                    entries = when (frame.subPath) {
                        "" -> listOf(PathEntry("src", true), PathEntry("README.md", false), PathEntry("build.gradle.kts", false))
                        "src" -> listOf(PathEntry("main", true), PathEntry("App.kt", false))
                        else -> listOf(PathEntry("Main.kt", false))
                    },
                    // #176: the "~" home-anchor reply reports the machine's fs roots so the demo shows the
                    // root switcher (the real daemon sends the actual roots; here a stand-in filesystem root)
                    roots = if (frame.workdir == BROWSE_HOME) listOf("/") else emptyList(),
                ),
            )
            is ListSessions -> handle(Sessions(frame.workdir, DemoData.sessions(frame.workdir)))
            // #119 groups: demo has no persistence — just re-echo the sample sessions so the UI settles
            is GroupCreate, is GroupRename, is GroupDelete, is GroupAssign -> {
                val wd = (frame as? GroupCreate)?.workdir ?: (frame as? GroupRename)?.workdir
                    ?: (frame as? GroupDelete)?.workdir ?: (frame as GroupAssign).workdir
                handle(Sessions(wd, DemoData.sessions(wd)))
            }
            // #158 rename: same no-persistence re-echo (the demo Sessions omits renameSupported, so the
            // entry stays hidden anyway — this just keeps every sendable frame answered)
            is RenameSession -> handle(Sessions(frame.workdir, DemoData.sessions(frame.workdir)))
            is OpenSession -> {
                val cid = "demo-convo-${frame.resumeId ?: "new"}"
                handle(
                    SessionLive(
                        cid,
                        frame.workdir,
                        frame.resumeId ?: DemoData.LIVE_SESSION_ID,
                        mode = frame.mode,
                        executing = false,
                        permissionMode = frame.permissionMode,
                        serviceTier = frame.serviceTier,
                    ),
                )
                handle(CommandList(cid, DemoData.commands()))
                if (frame.resumeId != null) handle(ConvoHistory(cid, DemoData.history())) // resumed = preloaded transcript
            }
            is SendPrompt -> demoHandlePrompt(frame.convoId)
            is PermissionVerdict -> if (demoPendingReply) { demoPendingReply = false; demoStream(frame.convoId, DemoData.REPLY_CHUNKS, thinking = false) }
            is RevokeGrant -> frame.requestId?.let {
                handle(ApprovalGrantMutationResult(it, frame.convoId, success = true))
            }
            is ClearAllowRule -> frame.requestId?.let {
                handle(ApprovalGrantMutationResult(it, frame.convoId, success = true))
            }
            is SwitchMode -> handle(
                SessionLive(
                    frame.convoId,
                    workdir.value ?: DemoData.LIVE_DIR,
                    currentSessionId,
                    mode = frame.mode,
                    executing = false,
                    permissionMode = frame.permissionMode,
                    effort = effort.value,
                    serviceTier = serviceTier.value,
                ),
            )
            is SwitchServiceTier -> handle(
                SessionLive(
                    frame.convoId,
                    workdir.value ?: DemoData.LIVE_DIR,
                    currentSessionId,
                    mode = mode.value,
                    executing = false,
                    permissionMode = permissionMode.value,
                    effort = effort.value,
                    serviceTier = frame.serviceTier,
                ),
            )
            is CancelTurn -> handle(TurnDone(frame.convoId))
            is AudioChunk -> if (frame.last) handle(Transcript(frame.convoId, frame.captureId, text = "show me the open files", ok = true))
            // file upload (issue #90): pretend the file landed in the demo workspace's inbox
            is FileChunk -> if (frame.last) {
                handle(FileUploaded(frame.convoId, frame.captureId, path = ".ccpocket/inbox/${frame.captureId}/${frame.name}", name = frame.name, size = frame.totalBytes))
            }
            // folder-share (issue #115): loop the owner control plane back with sample data
            is CreateShare -> handle(ShareCreated(ok = true, invite = DemoData.sampleInvite(frame.path, frame.tier, frame.expiresInSec)))
            is ListShares -> handle(ShareListing(DemoData.shares()))
            is RevokeShare -> handle(ShareRevoked(frame.deviceId, ok = true))
            else -> {} // CloseSession / SwitchDirectory / AudioCancel / FileUploadCancel — nothing to echo
        }
    }

    private suspend fun demoHandlePrompt(convoId: String) {
        if (!demoAsked) {
            // first turn demonstrates a tool call + permission prompt; the verdict resumes the reply
            demoAsked = true
            // preview mode demos a destructive command (danger styling); normal demo shows a benign one
            val preview = isPreviewMode()
            val cmd = if (preview) DemoData.PREVIEW_ASK_PREVIEW else DemoData.ASK_PREVIEW
            val rule = if (preview) DemoData.PREVIEW_ASK_RULE else DemoData.ASK_RULE
            // #251: same containment as daemonTooOldText() — a demo/App-Review script must never be
            // the thing that kills the process it is demonstrating.
            val title = if (preview) safeString("Run command") { getString(Res.string.preview_cmd_title) } else DemoData.ASK_TITLE
            val note = if (preview) safeString("delete files") { getString(Res.string.preview_cmd_note) } else null
            delay(500)
            handle(AssistantChunk(convoId, demoSeq++, StreamPiece.Thinking(DemoData.THINKING)))
            delay(700)
            handle(ToolEvent(convoId, demoSeq++, ToolPhase.START, DemoData.ASK_TOOL, cmd))
            delay(300)
            demoPendingReply = true
            handle(PermissionAsk(convoId, "demo-ask-${demoSeq++}", DemoData.ASK_TOOL, cmd, title = title, rule = rule, danger = preview, dangerNote = note))
            scope.launch { // safety: never leave the ■ button stuck if the prompt is ignored/dismissed
                delay(40_000)
                if (demoPendingReply) { demoPendingReply = false; demoStream(convoId, DemoData.REPLY_CHUNKS, thinking = false) }
            }
        } else {
            demoStream(convoId, DemoData.PLAIN_REPLY_CHUNKS, thinking = true)
        }
    }

    private suspend fun demoStream(convoId: String, chunks: List<String>, thinking: Boolean) {
        if (thinking) { delay(400); handle(AssistantChunk(convoId, demoSeq++, StreamPiece.Thinking(DemoData.THINKING))); delay(600) }
        chunks.forEach { part -> handle(AssistantChunk(convoId, demoSeq++, StreamPiece.Text(part))); delay(350) }
        handle(TurnDone(convoId))
    }

    // test seam (issue #104): feed an inbound frame exactly as a transport would, to exercise the
    // delivery→turn watchdog handoff (PromptAck vs. a following turn frame) without a live daemon.
    internal fun receiveForTest(f: Frame) = handle(f)

    // Test the single outbound capability boundary with arbitrary protocol frames. Keeping this beside
    // receiveForTest makes additions to agentCarried() independently pin-able without UI setup.
    internal suspend fun sendForTest(f: Frame) = send(f)

    /**
     * Session-identity check for an incoming [SessionLive] (issue #219). The daemon deliberately keeps
     * this device attached to every conversation it left running in the background (that fan-out is what
     * feeds the machine-wide approval inbox), so announces from conversations OTHER than the open view
     * arrive here routinely — a background turn starting, a relaunch, a Full-Control expiry. Those must
     * never rebind the single active view. Accepted shapes:
     *  - the open conversation's own re-announce (mode/model/effort switch, relaunch, init backfill);
     *  - the SAME session coming back under a fresh convoId — a reconnect re-open, a SessionGone
     *    recovery, a handoff spectator migration (§3.3), a registry hot→cold rebuild — recognized by
     *    [sessionKey], which every open path pins to the target session before the daemon answers;
     *  - the answer to an in-flight brand-new open (no sessionId exists yet), matched on the workdir
     *    the open targeted ([pendingNewOpenWd]);
     *  - a fully unbound client (no view, no open in flight): nothing to protect, keep today's behavior
     *    (cold-start seams and a background announce while browsing both land here harmlessly).
     */
    private fun acceptsSessionLive(f: SessionLive): Boolean {
        // #226: BACK is an authoritative navigation decision. The daemon may already have queued a
        // SessionLive for the chat we just left (turn-state reannounce, close race, reconnect replay).
        // sessionKey still names that chat for draft durability, so reject before consulting identity.
        // An explicit open owns openInFlight and is allowed through; openSession also lowers the fence.
        if (sessionNavigationFenced && openInFlight == null) return false
        // #235: openSession claims this target synchronously, while sessionKey/pendingNewOpenWd are still
        // assigned by the launched worker. During that narrow gap the previous chat can re-announce; the
        // old guard would accept it by convoId, clear the new claim/opening state, and leave the worker's
        // eventual open waiting behind an EmptyChat pane. No SessionLive can answer until THIS generation
        // has actually sent OpenSession; after that, only its target may answer. Resume opens keep #219's
        // session-id authority (the daemon may canonicalize the cwd beyond what the client can prove),
        // while new sessions retain the existing workdir match (demo mode assigns their id immediately).
        openInFlight?.let { target ->
            if (openDispatchedGen != openGen) return false
            return if (target.resumeId != null) {
                f.sessionId == target.resumeId
            } else {
                sameDirPath(f.workdir, target.wd)
            }
        }
        return f.convoId == convoId.value ||
            (f.sessionId != null && f.sessionId == sessionKey.value) ||
            (pendingNewOpenWd != null && f.workdir == pendingNewOpenWd) ||
            (!opening.value && convoId.value == null && sessionKey.value == null)
    }

    // control-plane counterpart: Attached/PeerPresence/AuthError flow through handleControl, not handle.
    // Lets a test drive a (re)attach edge without a live transport — e.g. that Attached bumps connGen so
    // the Account pane's fetch re-keys on reconnect.
    internal fun receiveControlForTest(f: Frame) = handleControl(f)

    // #146: drive a deaf-link signal exactly as a transport reader would after N consecutive decrypt
    // failures, to assert it forces a re-handshake (mid-turn self-heal) without a live daemon.
    internal fun receiveDeafForTest() = onDeafLink()

    private fun handle(f: Frame) {
        // A completed turn is the moment the subscription allowance actually moves, so it is one of the
        // quota refresh triggers. Counted HERE rather than inside the `is TurnDone` branch below, because
        // that branch is filtered to the currently-open conversation (chat-state bookkeeping) and would
        // miss a turn finishing in another session/window on the same machine.
        if (f is TurnDone) turnCompletions.value++
        when (f) {
            is Directories -> {
                replace(directories, f.entries); refreshing.value = false
                noteWorkingSessions() // #165: a session that stopped working while you were elsewhere earns a dot
                directoriesRev++ // the #145 presence probe checks this to prove the computer answered
                directoriesLoaded.value = true; daemonOffline = false; listWaitJob?.cancel() // a reply proves the computer is online
                if (!useRelay) attachedThisSession = true // direct mode: socket + data == attached
                connected.value = true; relayDeadlinePassed = false
                // #144: deliberately NOT retryAttempts = 0 here — one healthy round-trip is not a stable
                // link, and resetting on it kept a flapping link reconnecting at 1s forever. The ladder
                // resets in armLinkStableReset once the link stays up; dev-direct (no Attached edge)
                // arms it from here.
                if (!useRelay && linkStableJob?.isActive != true) armLinkStableReset()
                if (!hadReadyThisSession) {
                    hadReadyThisSession = true
                    Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to transportName()) + demoTag())
                }
                recomputePhase()
            }
            is Sessions -> {
                // distinctBy: one row per session id NO MATTER what the daemon sent. A codex resume-rollout
                // (two files, one id) once reached the phone's LazyColumn as two rows with one key — that is
                // an instant native crash, not a cosmetic glitch. The daemon dedupes too; this edge survives
                // an older daemon. First occurrence wins = newest (the list arrives sorted by recency).
                sessionsDir.value = f.workdir; replace(sessions, f.items.distinctBy { it.sessionId })
                replace(sessionGroups, f.groups ?: emptyList()) // #119: null (older daemon) → no groups, flat list
                groupsSupported.value = f.groups != null // groups=[] (owner, none yet) still enables management
                renameSupported.value = f.renameSupported // #158: false from an older daemon / a guest
                archiveSupported.value = f.archiveSupported // #202: same contract as renameSupported
                sessionsRefreshing.value = false
            }
            is ArchivedSessions -> { // #202: the cross-project archive view's rows
                replace(archivedSessions, f.items)
                archivedRefreshing.value = false
            }
            is Usage -> { usage.value = f; usageLoading.value = false }
            // The Claude subscription allowance behind the CLI's `/usage` panel. Two different kinds of
            // non-OK reply, deliberately handled differently:
            //  · NO_TOKEN is AUTHORITATIVE — the machine signed out, or authenticates with an API key.
            //    There is no allowance any more, so the old snapshot must go; keeping it would leave a
            //    stale bar promising headroom on an account that is no longer in play.
            //  · NETWORK / HTTP are TRANSIENT — we simply failed to ask. Keep the last good snapshot and
            //    its original fetchedAt (which is what ages the "updated N min ago" line honestly) and
            //    wait for the next trigger. Blanking the bar every time a laptop's wifi blips would make
            //    a persistent indicator useless.
            is ClaudeQuota -> {
                claudeQuotaDeadline?.cancel()
                claudeQuotaStatus.value = f.status
                if (f.status == CLAUDE_QUOTA_OK || f.status == CLAUDE_QUOTA_NO_TOKEN) claudeQuota.value = f
                claudeQuotaLoading.value = false
                onClaudeQuotaReply?.invoke()
            }
            is SkillCatalog -> {
                skillCatalogDeadline?.cancel()
                skillCatalog.value = f; skillCatalogLoading.value = false; skillCatalogUnavailable.value = false
            }
            // headless bridges (issue #91 follow-up) — the owner's control plane replies
            is BridgeListing -> {
                bridgesDeadline?.cancel(); bridgeBusyDeadline?.cancel()
                bridges.clear(); bridges.addAll(f.items)
                bridgesLoaded.value = true; bridgesUnavailable.value = false; bridgeBusy.value = false
            }
            is BridgeCreated -> {
                bridgeBusyDeadline?.cancel()
                bridgeBusy.value = false
                bridgeError.value = f.error
                // only an UNMANAGED mint hands back a ticket; a managed one already gave it to its process
                bridgeCredential.value = f.credential
                if (f.ok) fetchBridges() // the new row (and its runner state) comes from the listing
            }
            is BridgeRevoked -> {
                bridgeBusyDeadline?.cancel()
                bridgeBusy.value = false
                bridgeError.value = f.error
                if (f.ok) fetchBridges()
            }
            is BridgeRunnerStatus -> {
                bridgeBusyDeadline?.cancel()
                bridgeBusy.value = false
                bridgeError.value = f.error
                // merge-loss guard: an OLD daemon ignores mergeEnv and replaces wholesale — keys the edit
                // didn't retype silently die. envKeys in the reply is the proof either way.
                pendingMergeCheck?.let { (n, prior) ->
                    if (f.name == n) {
                        pendingMergeCheck = null
                        val lost = prior - (f.state?.envKeys?.toSet() ?: emptySet())
                        if (lost.isNotEmpty()) bridgeMergeLost.value = lost.sorted()
                    }
                }
                fetchBridges() // start/stop/detach all move the row's state
            }
            is AuthState -> authState.value = f
            // scheduled tasks (issue #137): the single reply to every pocket/schedule.* request
            is ScheduleState -> {
                scheduleDeadline?.cancel()
                replace(schedules, f.items)
                schedulesLoaded.value = true; schedulesUnavailable.value = false
                scheduleError.value = f.error
            }
            // rev bumps on EVERY reply, including one equal to the last (a no-change save): UI effects
            // key on the rev, not the value, so an identical state still settles spinners/pending forms
            is PresetsState -> { presetsState.value = f; presetsStateRev.value++ }
            is ModelsList -> {
                // keep the LAST-GOOD list under a failed refresh: one `opencode models` timeout must
                // not wipe a working picker back to the empty state — carry the fresh error alongside
                val prev = agentModels[f.agent]
                val merged =
                    if (f.error != null && f.models.isEmpty() && prev != null && prev.models.isNotEmpty()) prev.copy(error = f.error)
                    else f
                // Same rule one level down (#167 ②): the gateway probe fails INDEPENDENTLY of the alias
                // list, so a refresh can carry good `models` and an empty `gatewayModels`. Taking that
                // verbatim would flip the picker from the gateway's real ids back to the seed table
                // mid-session — the model the user was reaching for vanishing under their finger.
                val accepted =
                    if (merged.gatewayModels.isEmpty() && !prev?.gatewayModels.isNullOrEmpty()) {
                        merged.copy(
                            gatewayModels = prev!!.gatewayModels,
                            gatewayModelsSource = prev.gatewayModelsSource,
                        )
                    } else merged
                agentModels[f.agent] = accepted
                reconcileDefaultCapabilities(f.agent)
            }
            // rewind/fork dry run (issue #282): fills the open sheet's numbers, or replaces the sheet
            // with the refusal. Convo-scoped like every other per-conversation frame — a preview for a
            // conversation we have since left must not repaint a sheet the user opened somewhere else.
            is RewindPreview -> if (f.convoId == rewindSheet.value?.target?.convoId) {
                if (!f.ok) {
                    rewindSheet.value = null
                    rewindError.value = f.reason ?: RewindRefusal.LAUNCH_FAILED
                } else {
                    rewindSheet.value = rewindSheet.value?.copy(counts = RewindCounts(f.dropTurns, f.dropToolCalls))
                }
            }
            // rewind/fork executed. On success the daemon has ALREADY opened the branch and announced it
            // (SessionLive with the new convoId, which the #219 identity guard accepts because the branch
            // still resumes this session's id) — so there is nothing to open here, only the two things
            // only this side knows: where the branch came from, and what the person was trying to say.
            // Matched against the conversation the REQUEST named, never against the live one: the daemon
            // opens the branch and announces it (SessionLive, new convoId) before answering, so `convoId`
            // has often already moved on by now — and a success is precisely when it has.
            is RewindDone -> if (f.convoId == rewindAwaiting?.convoId) {
                val target = rewindAwaiting
                rewindAwaiting = null
                rewindSheet.value = null
                val branch = f.newConvoId
                if (!f.ok || branch == null) {
                    rewindError.value = f.reason ?: RewindRefusal.LAUNCH_FAILED
                } else {
                    sessionLineage.value = SessionLineage(
                        convoId = branch,
                        mode = target?.mode ?: RewindMode.REWIND,
                        fromSessionId = sessionKey.value,
                        fromTitle = chatTitle.value.orEmpty(),
                    )
                    // Prefill the anchor's own words for a REWIND ("say it differently"); a FORK explores
                    // from that point instead and starts empty. Routed through the draft + epoch bump
                    // rather than poked into a ComposerState: that is the one write both the mobile and
                    // the desktop composer re-read, and it survives the re-key the branch's first turn
                    // will do (#93/#108 — never touch a live IME field from underneath).
                    if (target != null && target.mode == RewindMode.REWIND) {
                        saveDraft(composerKey(), target.text)
                        composerEpoch.value++
                    }
                }
            }
            is PushPrefs -> pushPrefs.value = f.enabled
            is ApprovalPrefs -> {
                approvalPrefs.value = f.noAutoDeny
                approvalFullControlExpiryMs.value = f.fullControlExpiryMs // #220 (0 = never expires)
            }
            // the daemon told us where it lives on the LAN — persist per binding; the next connect (this
            // repo OR a rebuilt fleet satellite reading the same store) dials it before the relay. An
            // address that already answered with the WRONG daemon key stays blacklisted — the daemon
            // re-advertises the same value on every handshake, which must not resurrect a dead probe.
            is DaemonInfo -> {
                paired.value?.let { p ->
                    if (p.directUrl != f.lanUrl && (f.lanUrl == null || f.lanUrl != badDirectUrl[p.accountId])) {
                        rememberDirectUrl(p.accountId, f.lanUrl)
                    }
                    // adopt the daemon's real computer name as this binding's default display name (issue #62);
                    // a user-set nickname still wins in displayName(). Independent of the directUrl guard above.
                    if (!f.hostname.isNullOrBlank() && f.hostname != p.hostName) rememberHostName(p.accountId, f.hostname)
                }
                // gateway hint (issue #139): unconditional, incl. null — a daemon back on the official
                // endpoint (or an old daemon omitting the field) must clear a previous gateway's value
                gatewayBaseUrl.value = f.gatewayBaseUrl
                bridgeControl.value = f.bridgeControl // capability advertisement (issue #91): false = daemon too old
                daemonSupportedAgents.value = f.supportedAgents.toSet()
                daemonAgentsKnown = true // #276: the daemon has now told us — the guard may deny an unsupported agent
                daemonUsageAgentFilter.value = f.supportsUsageAgentFilter // issue #258: false = daemon ignores the filter
                daemonOwnsPromptRecovery = f.supportsPromptRecovery
                if (daemonOwnsPromptRecovery) clearTurnWatchdogState()
                // version visibility (issue #200): unconditional, incl. nulls from a daemon that predates
                // the fields — "unknown" must not be shown as the previous machine's numbers
                versionStatus.value = VersionStatus(
                    appVersion = APP_VERSION,
                    daemonVersion = f.daemonVersion,
                    latestVersion = f.latestVersion,
                    updateCommand = f.updateCommand,
                )
            }
            // #219 identity guard: a SessionLive that fails [acceptsSessionLive] is a BACKGROUND
            // conversation's announce (turn start / relaunch / mode expiry, fanned out because this
            // device stays attached to conversations it left running). Letting it through re-pointed
            // `convoId.value` — the very baseline every stream guard below compares against — so that
            // conversation's AssistantChunk/ToolEvent frames then passed the guards and spliced another
            // session's live turn into the open transcript. Not our view's session → no state touched.
            is SessionLive -> if (acceptsSessionLive(f)) {
                // Reattach is an authoritative lifecycle snapshot. A local 45s deadline may have fired while
                // the phone was suspended and missed both output + TurnDone; never carry that stale inference
                // over the daemon's fresh executing/idle truth (the screenshot bug).
                if (f.executing != null) clearTurnWatchdogState()
                openJob?.cancel(); openJob = null // the answer owns the view; retire its dormant 8s deadline
                pendingNewOpenWd = null // the in-flight open (if any) is answered by this announce
                openInFlight = null // …and so is its #235 claim — the next click on this row is a real request again
                migrateDraft(f.sessionId) // before re-keying: composerKey() still reads the old chain
                convoId.value = f.convoId; workdir.value = f.workdir; observing.value = f.observing; currentSessionId = f.sessionId
                f.sessionId?.let { sessionKey.value = it }
                // The opener's title is only an optimistic seed: push/deep-link routes know no title at
                // all, and a project row can be stale while Codex renames its thread. A new daemon sends
                // transcript/index truth here; null from an older daemon deliberately keeps the seed.
                f.title?.takeIf { it.isNotBlank() }?.let { chatTitle.value = it }
                f.mode?.let { mode.value = it } // daemon is the source of truth — corrects the optimistic badge
                permissionMode.value = f.permissionMode // unconditional: a normal mode clears a prior `auto`
                effort.value = f.effort // unconditional: `/effort default` must clear an optimistic explicit level
                serviceTier.value = f.serviceTier // unconditional: null restores account/default tier
                f.agent?.let { sessionAgent.value = it } // daemon truth for the backend badge
                val liveAgent = f.agent ?: sessionAgent.value ?: AgentKind.CLAUDE
                // daemon truth verbatim: filtering the REPORTED model through the compat guard nulled
                // legitimate ids (codex "o3", gateway "vendor/model") and wiped the header — the guard
                // is for what we SEND (openSession seeding), never for what the daemon says is running
                f.model?.let { model.value = it }
                // unconditional (not ?.let): switching from a bridge session to a normal one must CLEAR the chip
                sessionOrigin.value = f.origin // "via <bridge>" header chip (issue #91); null = interactive/old daemon
                // window fallback is Claude-only: contextWindowFor knows nothing about gpt-* ids, and a Codex
                // session with no daemon-sent window was rendering a % against a meaningless Claude 200k —
                // null instead, and the UI shows raw tokens without a denominator
                val claudeish = liveAgent == AgentKind.CLAUDE
                // the user's override wins over the daemon's value (for Claude, f.contextWindow is never null and
                // would otherwise pin a custom model at the CLI's 200k fallback — issue #60). Resolved against the
                // model THIS session is running (#169), so switching sessions switches denominators with it.
                contextWindow.value = contextWindowOverrideFor(f.model ?: model.value) ?: f.contextWindow ?: (if (claudeish) contextWindowFor(f.model ?: model.value) else null)
                // seed the usage statusline on resume (before the first new turn). Only when we have no
                // value yet — a TurnDone this session is fresher than the daemon's transcript snapshot.
                if (contextUsed.value == null) f.contextUsed?.let { contextUsed.value = it }
                upgradeWindowIfProven()
                // daemon truth beats the local guess: a turn that ended (or started) while the link was
                // down would otherwise leave the ■/mic button stuck; null = old daemon, keep local state
                f.executing?.let { exec ->
                    if (!exec) finishThinking() // a turn killed mid-thinking has no TurnDone to stamp the block
                    streaming.value = exec
                }
                switching.value = false
                opening.value = false; switchingSession.value = false // the open (or reattach) landed
                openTimedOut.value = false
                // remember this session's launch flags so a close+reopen cycle can restore (and relaunch under) them
                f.sessionId?.let {
                    sessionParams[it] = SessionParams(
                        mode.value,
                        model.value,
                        effort.value,
                        sessionAgent.value ?: AgentKind.CLAUDE,
                        permissionMode.value,
                        serviceTier.value,
                    )
                }
                persistSessionParams() // survive app restarts too — reopening tomorrow restores mode/effort/agent
                // #165: daemon-authoritative identity for the working set (a fork/lock-heal corrected the id
                // we opened with, so this — not the optimistic resumeId — is what the switcher remembers)
                rememberOpenedSession(f.workdir, f.sessionId, chatTitle.value, sessionAgent.value)
                // SessionGone recovery: the reopen landed — resend the prompt that hit the dead convo. Single
                // shot: a second SessionGone for the resent prompt takes the honest-error branch, never a loop.
                // Workdir-matched so a user who navigated elsewhere mid-recovery doesn't get it misdelivered.
                // degraded flag (issue #65): daemon truth on every announce; a healthy re-announce
                // (e.g. after /clear) also disarms the send gate
                sessionDegraded.value = f.degraded
                if (!f.degraded) degradedSendArmed = false
                val retry = promptRetry
                if (promptResendArmed && retry != null && !f.observing && f.workdir == retry.workdir) {
                    promptRetry = null; promptResendArmed = false
                    activePromptId = retry.promptId
                    promptPending = true
                    promptQueued = false // fresh process, no running turn to queue behind — its ack arms the strict deadline
                    streaming.value = true
                    // same promptId as the original: if the first send actually landed, the daemon
                    // dedupes and just re-acks instead of running the turn twice (issue #66)
                    scope.launch { send(SendPrompt(f.convoId, retry.text, retry.images, promptId = retry.promptId)) }
                    armPromptWatchdog() // the resent copy gets its own receipt deadline
                }
            }
            // delivery receipt (issue #66): the daemon handed the turn to the agent — flip the bubble's
            // marker. Also first evidence: the retry copy is obsolete (the prompt cannot be lost anymore).
            is PromptAck -> if (f.convoId == convoId.value) {
                promptDelivered(f.promptId) // delivered ≠ turn started; only the matching active prompt may move the watchdog
                val i = messages.indexOfLast { it is ChatItem.User && it.promptId == f.promptId }
                (messages.getOrNull(i) as? ChatItem.User)?.let { messages[i] = it.copy(pending = false, delivered = true) }
            }
            // Stream/turn frames carry their source convoId; this single-active-view model has one `messages`
            // list, so a frame from a just-left conversation (its tail still in flight when we switched) must
            // be dropped — else it renders into whatever convo is now open. Reopening the source replays its
            // full transcript via ConvoHistory, so nothing is actually lost. (Matches the BackgroundJobs guard.)
            is AssistantChunk -> if (f.convoId == convoId.value) { promptEvidence(); appendChunk(f) }
            is ToolEvent -> if (f.convoId == convoId.value) { promptEvidence(); finishThinking(); onToolEvent(f) }
            is PendingApprovals -> {
                pendingApprovals.clear()
                f.items.filterNot { it.ask.isQuestion }.forEach { pendingApprovals[ApprovalKey(it.ask.convoId, it.ask.askId)] = it }
            }
            is PermissionAsk -> {
                // Every approval contributes to the machine-wide inbox, even when its conversation is not
                // the screen currently open. AskUserQuestion remains in its conversation-specific answer UI.
                if (!f.isQuestion) {
                    onApprovalArrived?.invoke() // P2-4: desktop banner/badge hook (content-free)
                    pendingApprovals[ApprovalKey(f.convoId, f.askId)] = PendingApproval(
                        ask = f,
                        expiresAt = f.timeoutSec?.let { epochMillis() + it * 1000L },
                    )
                }
                if (f.convoId == convoId.value) {
                    // a card sitting in its terminal timed-out display (issue #100) must not dam the queue:
                    // a NEW live ask retires it (the old single-value model overwrote it here too)
                    if (pendingAsk.value?.let { ApprovalKey(it.convoId, it.askId) } == timedOutAskId.value && pendingAsk.value != null) advanceAsk()
                    val current = pendingAsk.value
                    val queuedAt = askQueue.indexOfFirst { it.askId == f.askId }
                    when {
                        // duplicate frames (resurface on reattach) refresh in place — never double-queue
                        current?.askId == f.askId -> pendingAsk.value = f
                        queuedAt >= 0 -> askQueue[queuedAt] = f
                        current == null -> { // fresh burst — the queue is empty whenever no card is up
                            askBurstTotal = 1; askBurstDone = 0
                            updateAskProgress()
                            pendingAsk.value = f
                            Telemetry.track(TelEvent.ApprovalShown, mapOf(TelKey.Tool to f.tool))
                        }
                        else -> { // a card is already up: queue behind it instead of overwriting (design M1)
                            askQueue.add(f)
                            askBurstTotal++
                            updateAskProgress()
                        }
                    }
                }
            }
            // approval design M2 §9.6: an action auto-ran under a grant — drop the audit chip into the
            // stream. Idempotent by eventId (reminder re-emits / reattach replays must not duplicate).
            is AuthorizedActionRecorded -> if (f.convoId == convoId.value) {
                if (messages.none { it is ChatItem.AutoRun && it.eventId == f.eventId }) {
                    messages.add(ChatItem.AutoRun(f.eventId, f.actionSummary, f.basis, f.tool, f.matchedGrantId, f.decidedAt))
                }
            }
            // M3 advisory risk: update a STILL-PENDING card's badge in place — never reset the daemon
            // deadline, and drop updates for asks already terminal (SMART-APPROVAL §八)
            is PermissionRiskUpdated -> if (f.convoId == convoId.value) {
                if (pendingAsk.value?.askId == f.askId || askQueue.any { it.askId == f.askId }) {
                    askRisk[ApprovalKey(f.convoId, f.askId)] = f
                }
            }
            is ApprovalGrantMutationResult -> {
                val pending = pendingGrantMutations.remove(f.requestId)
                if (pending != null && pending.convoId == f.convoId) {
                    pending.eventId?.let { eventId ->
                        updateAutoRun(eventId) { it.copy(tightening = false, tightened = f.success) }
                    }
                    if (f.success && convoId.value == pending.convoId) {
                        when {
                            pending.clearAll -> allowRules.clear()
                            pending.rule != null -> allowRules.remove(pending.rule)
                        }
                    }
                }
            }
            // claude withdrew the ask (interrupt / moved on) — drop the card; a question card leaves a muted notice
            is AskWithdrawn -> {
                pendingApprovals.remove(ApprovalKey(f.convoId, f.askId))
                if (f.convoId == convoId.value && pendingAsk.value?.askId == f.askId) {
                    val ask = pendingAsk.value
                    if (f.reason == AskWithdrawnReason.TIMED_OUT && ask?.questions == null) {
                        // issue #100: keep the permission card up but flip it to its terminal "timed out" state,
                        // so a returning user sees what happened instead of a card that silently vanished (which
                        // read as success). A tap on it now only dismisses — no more silent no-op.
                        timedOutAskId.value = ApprovalKey(f.convoId, f.askId)
                    } else {
                        // agent moved on / session closed / a question timed out → dismiss (a question leaves a
                        // note) and surface the next queued ask, if any
                        if (ask?.questions != null) messages.add(ChatItem.QuestionsWithdrawn)
                        advanceAsk()
                    }
                } else if (f.convoId == convoId.value) {
                    // a QUEUED card the user never saw was retired (agent cancel / timeout) — drop it silently
                    // and shrink the burst so "n / m" stays honest
                    if (askQueue.removeAll { it.askId == f.askId }) {
                        askBurstTotal--
                        updateAskProgress()
                    }
                }
            }
            is TurnDone -> if (f.convoId == convoId.value) {
                val queuedReceiptStillPending = promptPending && promptQueued
                promptEvidence()
                // This boundary closes the turn that existed before the queued prompt was sent. If its Ack
                // was lost, keep the bubble pending, but the NEXT stream frame can now safely prove that
                // prompt started; it can no longer be mistaken for output from the preceding turn.
                if (queuedReceiptStillPending) promptQueued = false
                replayEcho = false // turn boundary — the next block belongs to a new turn, never a replay echo
                val turnWasLive = streaming.value // gate the marker/notify on a turn we actually watched run
                finishThinking(); streaming.value = false
                // a FAILED turn (API error / synthetic placeholder — issue #65): show the error row where
                // the reply would be; no green ✓ marker for a turn that produced nothing
                f.error?.let { messages.add(ChatItem.Sys(it)) }
                // usage-limit hit with a parsed reset moment (issue #137): light the one-tap
                // "auto-continue after reset" banner. Null (ordinary error / old daemon) = no offer.
                if (f.error != null) {
                    f.usageLimitResetAt?.let {
                        limitOffer.value = LimitOffer(f.convoId, sessionKey.value ?: currentSessionId, workdir.value ?: "", it)
                    }
                }
                if (turnWasLive) {
                    if (f.error == null) messages.add(ChatItem.TurnEnded(turnStartMark?.elapsedNow()?.inWholeSeconds?.toInt()))
                    turnStartMark = null
                    val preview = f.error ?: (messages.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)
                        ?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(140)
                    onTurnFinished?.invoke(
                        chatTitle.value ?: workdir.value?.substringAfterLast('/') ?: "CC Pocket",
                        preview,
                        sessionKey.value, // click→jump target for the desktop banner (issue #99)
                    )
                }
                // the listed snapshot said `live` at listing time — correct it locally so sidebar/list
                // dots stop pulsing the moment the turn ends instead of waiting for a manual re-list
                sessionKey.value?.let { sid ->
                    // #239: this TurnDone was delivered while the session was on screen, so it is already
                    // seen. If the user backs out before the next directory poll, do not reinterpret the
                    // delayed executing→idle snapshot as a result completed while away. Real background
                    // work will re-enter the baseline on that poll and can still finish unseen later.
                    if (backgroundJobs.none { it.status == JobStatus.RUNNING }) noteCurrentSettledSeen(sid)
                    val i = sessions.indexOfFirst { it.sessionId == sid }
                    if (i >= 0 && sessions[i].live) sessions[i] = sessions[i].copy(live = false)
                }
                // ~context occupancy: the prompt claude just saw + the reply it wrote (null = interrupted/
                // error turn without usage — keep showing the last known value, never snap to 0). A ZERO
                // footprint is equally never a real turn: daemons predating hasUsage send zero-filled
                // placeholders for interrupted turns, which snapped the statusline to 0% (07-04 report).
                f.usage?.takeIf { it.contextTokens > 0 }?.let { contextUsed.value = it.contextTokens }
                upgradeWindowIfProven()
            }
            is BackgroundJobs -> if (f.convoId == convoId.value) {
                replace(backgroundJobs, f.jobs)
                if (!streaming.value && f.jobs.none { it.status == JobStatus.RUNNING }) {
                    noteCurrentSettledSeen(sessionKey.value ?: currentSessionId)
                }
            }
            // Workflow orchestration (issue #106): whole-run snapshots keyed by runId; a re-push of the
            // same run reconciles in place. finalResult arrives only on the explicit terminal patch —
            // never let a later plain snapshot blank an already-received final return.
            is WorkflowUpdate -> if (f.convoId == convoId.value) {
                val prev = workflowRuns[f.run.runId]
                workflowRuns[f.run.runId] = if (f.run.finalResult == null && prev?.finalResult != null) {
                    f.run.copy(finalResult = prev.finalResult)
                } else f.run
            }
            is WorkflowAgentDetail -> if (f.convoId == convoId.value) {
                workflowAgentDetails["${f.runId}#${f.agentIndex}"] = f
            }
            // §6: the daemon accepts exactly REVIEW + REVIEW_READ_ONLY in v1 and refuses every other known
            // (but not yet fully implemented) combination by name. Route it to the handoff surfaces instead
            // of dropping an "error:" line into an unrelated transcript.
            is PocketError -> if (f.code == "handoff_not_supported") {
                handoffCreating.value = false
                handoffAccepting.value = null
                handoffError.value = f.message
                handoffUnsupported.value = f.message
            } else if (f.code == "rename_failed") {
                // #158: a rename refusal answers to the SESSIONS surface (the sidebar/list row that
                // asked) — the common case (renaming a terminal-held session) has no chat open, and an
                // open chat is an UNRELATED session whose transcript must not absorb the error line.
                renameError.value = renameTarget?.let { RenameRefusal(it, f.message) }
            } else if (f.convoId != null && (openInFlight != null || f.convoId != convoId.value)) {
                // Conversation-scoped errors fan out from background sessions just like SessionLive and
                // stream frames. They must not splice a system row into this transcript or terminate a
                // different in-flight open. OpenSession failures have no conversation yet and therefore
                // arrive with convoId == null; the current view's own errors still pass below.
            } else {
                // …and a failed switch must release the router, or the chat would hold an empty screen
                openJob?.cancel(); openJob = null
                opening.value = false; switchingSession.value = false // a failed open re-enables the one-tap entries right away
                pendingNewOpenWd = null // #219: the failed open's marker must not admit a later background announce
                openInFlight = null // #235: release the claim on the same edge — a refused open must stay retryable
                messages.add(ChatItem.Sys(f.message)) // UI prepends the localized "error:" prefix
                // a dead claude process never sends TurnDone — clear the streaming state here
                if (f.code == "process_exited" && (f.convoId == null || f.convoId == convoId.value)) {
                    promptEvidence(exactPrompt = true)
                    finishThinking(); streaming.value = false
                    noteCurrentSettledSeen(sessionKey.value ?: currentSessionId)
                }
            }
            // The daemon no longer holds this conversation (idle-reaped during a link drop / daemon restart).
            // Recover instead of spinning: re-open (resume) the same session, and the SessionLive handler
            // resends the pending prompt exactly once. No session to resume → surface it honestly.
            is SessionGone -> if (f.convoId == convoId.value) {
                if (observing.value) {
                    // an observe view can't run turns — a stray send must not leave the caret spinning
                    // forever (the old blanket ignore did exactly that, issue #45 ②)
                    promptEvidence(exactPrompt = true); finishThinking(); streaming.value = false
                } else {
                    val sid = sessionKey.value ?: currentSessionId
                    val wd = workdir.value
                    if (promptRetry != null && !promptResendArmed && sid != null && wd != null) {
                        promptResendArmed = true
                        // lastEventSeq (issue #147): the transcript is still on screen — delta reattach
                        scope.launch { send(OpenSession(wd, sid, mode = mode.value, agent = sessionAgent.value ?: AgentKind.CLAUDE, lastEventSeq = lastEventSeqFor(sid))) }
                    } else {
                        promptEvidence(exactPrompt = true); promptResendArmed = false
                        finishThinking(); streaming.value = false
                        messages.add(ChatItem.Sys("session expired on the computer — send again to restart it"))
                    }
                }
            }
            // also convo-scoped: a stale ConvoHistory would wipe the active convo and load the wrong transcript.
            // MERGED, not replaced (issue #107): the replay is the backfill channel for output streamed while
            // the link was down, but the app may hold rows the transcript doesn't (pending bubbles, dividers,
            // scrollback past the replay window, a bubble ahead of a lagging disk read) — TranscriptMerge
            // reconciles without flashing, duplicating, or reordering.
            is ConvoHistory -> if (f.convoId == convoId.value) {
                if (f.delta) {
                    // incremental reattach (issue #147): only the rows past the cursor we sent — merged at
                    // the tail (or into the live-received overlap), NEVER a wipe/replace. An empty delta
                    // means "already caught up" (the daemon normally doesn't even send one).
                    if (f.messages.isNotEmpty()) {
                        val localRows = messages.toList()
                        val merged = TranscriptMerge.mergeDelta(localRows, f.messages.map(::historyItem))
                        if (merged != localRows) replace(messages, merged)
                        reconcilePromptReceiptFromHistory(localRows, merged)
                        replayEcho = true // same replay/stream race as the full path
                    }
                    f.lastSeq?.let { historySeq = it; historySeqSession = currentSessionId }
                } else {
                    // an EMPTY full replay is only ever the daemon's explicit /clear wipe (every other emit
                    // site guards isNotEmpty) — the fresh session's window is empty, so the "Context NN%"
                    // statusline resets and hides until the first new turn reports usage (issue #149).
                    // Without this a composer-typed /clear pinned the badge at the wiped session's % forever:
                    // TurnDone deliberately ignores zero-usage frames, and the menu path's optimistic reset
                    // (clearConversation) never runs for a typed command.
                    if (f.messages.isEmpty()) contextUsed.value = null
                    val localRows = messages.toList()
                    val merged = TranscriptMerge.merge(localRows, f.messages.map(::historyItem))
                    if (merged != localRows) replace(messages, merged)
                    reconcilePromptReceiptFromHistory(localRows, merged)
                    replayEcho = true // arm the one-shot live-stream dedupe for the replay/stream race
                    // reattach cursor + paging anchors (issue #147); null fields = a pre-#147 daemon
                    historySeq = f.lastSeq
                    historySeqSession = if (f.lastSeq != null) currentSessionId else null
                    historyFirstSeq = f.firstSeq
                    historyHasMore.value = f.hasMore && f.firstSeq != null
                    // a full replay re-anchors the window; a page still in flight against the OLD anchor
                    // would prepend misaligned rows, so retire that outstanding request.
                    historyPageAnchor = null; historyPageDeadline?.cancel(); historyPageDeadline = null
                    historyLoadingOlder.value = false
                }
            }
            // one page of OLDER history (issue #147) — prepended above the current window. Gated on an
            // OUTSTANDING request ([historyPageAnchor]), NOT the spinner: a page that lands after the slow-
            // link deadline collapsed the spinner is still a valid reply and must be accepted (the fixed
            // bug). Clearing the anchor here dedupes a duplicate late fan-out; a page for a client that
            // never asked (anchor null) is dropped.
            is ConvoHistoryPage -> if (f.convoId == convoId.value && historyPageAnchor != null) {
                historyPageAnchor = null
                historyPageDeadline?.cancel(); historyPageDeadline = null
                historyLoadingOlder.value = false
                val older = f.messages.map(::historyItem)
                if (older.isNotEmpty()) {
                    messages.addAll(0, older)
                    lastHistoryPrependCount = older.size
                    historyPrependGen.value++
                }
                historyFirstSeq = f.firstSeq ?: historyFirstSeq
                historyHasMore.value = f.hasMore && f.firstSeq != null
            }
            is CommandList -> if (f.convoId == convoId.value) replace(slashCommands, f.commands)
            is Transcript -> onTranscript(f)
            // file upload receipt (issue #90) — matched on captureId inside; convo guard like CommandList
            is FileUploaded -> if (f.convoId == convoId.value) onFileUploaded(f)
            is ShellResult -> if (f.convoId == convoId.value) {
                terminalBusy.value = false
                val i = terminalEntries.indexOfLast { it.result == null } // fill the in-flight command's slot
                if (i >= 0) terminalEntries[i] = terminalEntries[i].copy(result = f)
            }
            // matched on the persistent identity (not convoId): a reply for a session we've left is dropped
            is SessionFiles -> if (f.workdir == workdir.value && f.sessionId == (sessionKey.value ?: currentSessionId)) {
                changedFilesDeadline?.cancel()
                replace(changedFiles, f.files); changedFilesLoading.value = false; changedFilesUnavailable.value = false
            }
            // full-identity match: a late reply for the SAME path from a session we've since left must not land
            // no deadline cancel here: the ONE viewer deadline serves both replies and no-ops per
            // side once its value landed — canceling on the first arrival would strand the other
            is FileContent -> if (f.path == viewedFilePath.value && f.workdir == workdir.value && f.sessionId == (sessionKey.value ?: currentSessionId)) {
                dropChunkStream() // a whole-frame reply (incl. a mid-stream failure) supersedes any partial stream
                viewedFile.value = f
                // an ExportFile reply rides the same channel + identity — settle the waiting state either way
                if (exportWaiting.value) { exportWaiting.value = false; exportDeadline?.cancel() }
            }
            // chunked ReadFile reply (issue #134): same identity match as FileContent; each piece re-arms
            // the viewer deadline (it now bounds the inter-chunk gap), the last one lands the whole file
            is FileContentChunk -> if (f.path == viewedFilePath.value && f.workdir == workdir.value && f.sessionId == (sessionKey.value ?: currentSessionId)) {
                if (viewedFile.value == null) { // stop re-arming once something (even an error) landed
                    // the deadline is NOT cancelled on completion: it still owes the FileDiff side its
                    // honest fallback (same one-deadline-serves-both rule as the FileContent path)
                    armViewedFileDeadline(f.path, f.workdir, f.sessionId, wantDiff = !isImageFile(f.path))
                    fileChunks.add(f)?.let { whole -> viewedFile.value = whole }
                    // one read after add: mid-stream it advances the loading card's determinate bar,
                    // the final piece resets the assembler → null clears the bar with it (0714 A1)
                    viewedFileProgress.value = fileChunks.progress
                }
            }
            is FileDiff -> if (f.path == viewedFilePath.value && f.workdir == workdir.value && f.sessionId == (sessionKey.value ?: currentSessionId)) {
                viewedFileDiff.value = f
            }
            // ── Git panel (#280) / worktrees (#281): identity-matched on (convoId, workdir) before any
            // state moves — a reply for a conversation or repository we've left is dropped, never merged.
            is GitStatus -> if (f.convoId == convoId.value && f.workdir == workdir.value) {
                gitStatusDeadline?.cancel()
                gitStatusLoading.value = false; gitStatusUnavailable.value = false
                gitStatus.value = f
            }
            // [staged] is part of the identity: a late reply for the OTHER side of the Working|Staged
            // control must not overwrite the side currently on screen.
            is GitDiff -> if (f.convoId == convoId.value && f.workdir == workdir.value &&
                f.path == gitDiffPath.value && f.staged == gitDiffStaged.value
            ) {
                gitDiffDeadline?.cancel()
                gitDiff.value = f
            }
            // workdir is a TRAILING optional: an older daemon omits it and we fall back to the convoId
            // match, but when it is present it must agree — a session can switch directory while a
            // preview is in flight, and a stale one would offer to discard files from the old tree.
            is GitActionPreview -> if (f.convoId == convoId.value && (f.workdir == null || f.workdir == workdir.value)) {
                gitActionDeadline?.cancel()
                gitBusyOp.value = null
                gitPendingConfirm.value = f
            }
            is GitActionResult -> if (f.convoId == convoId.value && (f.workdir == null || f.workdir == workdir.value)) {
                gitActionDeadline?.cancel()
                if (gitBusyOp.value == f.op) gitBusyOp.value = null
                gitPendingAction = null; gitPendingRemove = null
                gitError.value = if (f.ok) null else f
                // the receipt a fetch otherwise has no way to give: read off the snapshot it came with,
                // so "远端领先 1，可合并" is the SAME number the header is about to show (真机反馈 1)
                if (f.op == GIT_OP_FETCH) {
                    gitFetchNote.value = if (f.ok) gitFetchReport(f.statusAfter ?: gitStatus.value) else null
                }
                // the daemon hands back a fresh snapshot with a successful mutation — refresh IN PLACE
                // rather than asking again (no second round trip, and no polling anywhere in this surface)
                f.statusAfter?.takeIf { it.workdir == workdir.value }?.let {
                    gitStatus.value = it
                    gitStatusLoading.value = false; gitStatusUnavailable.value = false
                }
                // the worktree verbs change a list the status snapshot doesn't carry
                if (f.ok && (f.op == GIT_OP_WORKTREE_ADD || f.op == GIT_OP_WORKTREE_REMOVE)) fetchWorktrees()
                // the post-create receipt: the daemon names the checkout it just made (an older daemon
                // omits path — the sheet then shows the fact without the open-here verb)
                if (f.op == GIT_OP_WORKTREE_ADD) {
                    if (f.ok) worktreeCreated.value = WorktreeCreated(path = f.path, branch = pendingWorktreeAddBranch)
                    pendingWorktreeAddBranch = null
                }
                // reverting the file on screen makes the open diff a lie — re-read the side we're showing
                if (f.ok && f.op == GIT_OP_REVERT) gitDiffPath.value?.let { openGitDiff(it, gitDiffStaged.value) }
            }
            is WorktreeList -> if (f.convoId == convoId.value && f.workdir == workdir.value) {
                worktreesDeadline?.cancel()
                worktreesLoading.value = false; worktreesUnavailable.value = false
                worktrees.value = f
            }
            // @-file completion (issue #75): keyed on workdir, not a session id — it browses the cwd, not a
            // session's changed set. A reply for a workdir we've since left is dropped (the completer keys
            // the visible listing on its own requested subPath anyway).
            // The folder browser (issue #152) rides the same frame anchored at the literal "~" — a session's
            // workdir is always a real absolute path, so the two listings can't collide on the key. Browser
            // replies additionally pass the latest-request gate: replies can arrive out of order, and a
            // drilled-past level's late reply must not clobber the fresh one (#152 复核, [foldBrowseReply]).
            // #176: the browser can also anchor at a filesystem root ("/", "C:\") once the switcher picks
            // one — those replies route by matching [lastBrowseAnchor]. @-completion (workdir.value) is
            // checked FIRST so a real session's listing is never mistaken for a root browse. The "~" reply
            // uniquely carries the machine's fs roots (owner-only) — latch them so the switcher survives
            // switching away from home.
            is PathEntries -> when {
                f.workdir == BROWSE_HOME -> {
                    browseListing.value = foldBrowseReply(browseListing.value, f, lastBrowseSub)
                    if (f.roots.isNotEmpty()) browseRoots.value = f.roots
                }
                f.workdir == workdir.value -> pathListing.value = f
                f.workdir == lastBrowseAnchor -> browseListing.value = foldBrowseReply(browseListing.value, f, lastBrowseSub)
            }
            // ── folder-share (issue #115): owner control-plane replies ──
            is ShareCreated -> { lastShareCreated.value = f; sharesRefreshing.value = false }
            is ShareListing -> { replace(shares, f.items); sharesLoaded.value = true; sharesRefreshing.value = false }
            is ShareRevoked -> {
                sharesRefreshing.value = false
                if (f.ok) shares.removeAll { it.deviceId == f.deviceId } // optimistic; a follow-up listShares() refreshes for real
            }
            // guest side (#115 follow-up): the daemon's precise "your share ended" — arrives right before
            // the cut, so the terminal card can say revoked-vs-expired instead of a bare disconnect
            is ShareEnded -> onShareEnded(f)
            // ── session handoff (SESSION-HANDOFF.md): daemon truth replaces local state wholesale ──
            is HandoffCreated -> {
                handoffCreating.value = false
                val h = f.handoff
                if (f.ok && h != null) {
                    handoffError.value = null
                    upsertHandoff(h)
                    lastHandoffInvite.value = h
                } else handoffError.value = f.error
            }
            is HandoffListing -> {
                replace(handoffs, f.items.sortedByDescending { it.createdAt })
                recomputeActiveHandoff()
                handoffListingRev++
                // §3.2.3: for an inbox link THIS is the readiness proof — the collaborator channel answered,
                // which (unlike Directories, which it may never send) is all such a credential can prove.
                handoffsLoaded.value = true
                if (isCollaboratorInbox) {
                    daemonOffline = false; listWaitJob?.cancel()
                    connected.value = true; relayDeadlinePassed = false
                    if (!hadReadyThisSession) {
                        hadReadyThisSession = true
                        Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to transportName()) + demoTag())
                    }
                    recomputePhase()
                }
                // a wholesale replace also has to reconcile the accept spinner and the auto-open rule
                // against daemon truth — a listing is how a reconnect learns the accept already landed
                f.items.forEach(::reconcileHandoffLifecycle)
            }
            is HandoffUpdated -> upsertHandoff(f.handoff)
            // ── collaborator links (SESSION-HANDOFF.md §4.1) ──
            is CollaboratorTicketCreated -> {
                collaboratorTicketCreating.value = false
                if (f.ok && f.invite != null) { collaboratorTicket.value = f.invite; collaboratorError.value = null }
                else collaboratorError.value = f.error
            }
            is CollaboratorListing -> { replace(collaborators, f.items); collaboratorsLoaded.value = true }
            is CollaboratorUpdated -> upsertCollaborator(f.collaborator)
            is CollaboratorConnected -> {
                upsertCollaborator(f.collaborator)
                lastCollaboratorConnected.value = f.collaborator // flips "waiting for scan…" → Connected
            }
            // ── ReviewRequest (REVIEW-REQUEST.md §12): daemon snapshots replace, pushes upsert ──
            // Any reply at all clears [reviewUnsupported]: it only ever meant "nothing came back".
            is ReviewListing -> {
                // ONLY an owner connection's listing is this machine's sent ledger. A collaborator-inbox
                // link is somebody else's account, and its rows are requests addressed TO this device —
                // filing those under "I sent these" would be a straightforward lie about who asked whom.
                if (!isCollaboratorInbox) {
                    replace(reviewsSent, f.items.sortedByDescending { it.createdAt })
                    reviewsSentLoaded.value = true
                }
                reviewUnsupported.value = false
            }
            is ReviewInboxListing -> {
                replace(reviewsReceived, f.items)
                reviewInboxLoaded.value = true; reviewUnsupported.value = false
            }
            is ReviewContactsListing -> {
                replace(reviewContacts, f.items)
                reviewContactsLoaded.value = true; reviewUnsupported.value = false
            }
            // same reason as the listing above: on a collaborator-inbox link this push is a request
            // addressed to us, not one we sent
            is ReviewUpdated -> if (!isCollaboratorInbox) upsertReview(f.request)
            is ReviewRequestCreated -> {
                reviewSending.value = false; reviewUnsupported.value = false
                val r = f.request
                if (f.ok && r != null) { reviewError.value = null; reviewLastCreated.value = r; upsertReview(r) }
                else reviewError.value = f.error
            }
            is ReviewInviteCreated -> {
                reviewInviteCreating.value = false; reviewUnsupported.value = false
                if (f.ok && f.invite != null) {
                    reviewInvite.value = f.invite; reviewInviteTtlSec.value = f.ttlSec; reviewError.value = null
                } else reviewError.value = f.error
            }
            is ReviewContactUpdated -> {
                reviewJoining.value = false; reviewUnsupported.value = false
                if (f.ok) {
                    reviewError.value = null
                    // re-list rather than patch: a join starts an inbox connection and a remove settles
                    // an outbox, so the daemon's next snapshot says more than the single row does
                    refreshReviewContacts()
                } else reviewError.value = f.error
            }
            is ReviewPrepared -> {
                reviewPreparing.value = null; reviewUnsupported.value = false
                if (f.ok && f.bundle != null) { reviewBundle.value = f.bundle; reviewError.value = null }
                else reviewError.value = f.error
            }
            is ReviewInboxActed -> {
                reviewActing.value = null; reviewUnsupported.value = false
                reviewLastActed.value = f
                if (f.ok) {
                    reviewError.value = null
                    // the daemon owns `pending`; re-read it instead of guessing what the queue now holds
                    refreshReviewInbox()
                } else reviewError.value = f.error
            }
            else -> {}
        }
    }

    /**
     * Fold a live tool event into the transcript (issue #77). Plain tools append a card, exactly as
     * before. Sub-agent extras — all keyed on the optional ids an old daemon never sends:
     *  - RESULT patches the matching Task/Agent card in place with the outcome + expandable report;
     *  - an event tagged with a parent folds into that parent's card as "N tool uses · latest" progress
     *    (falling back to today's inline card when the parent isn't on screen, e.g. attached mid-run).
     */
    private fun onToolEvent(f: ToolEvent) {
        val parent = f.parentToolUseId
        // one-shot replay-echo dedupe (issue #107), tool flavor: a START right after a merged
        // ConvoHistory may duplicate the replayed tail card (which has no taskId). Fold into it —
        // patching the live toolUseId in even upgrades the card for later RESULT correlation.
        if (replayEcho) {
            replayEcho = false
            if (f.phase == ToolPhase.START && parent == null) {
                val i = TranscriptMerge.echoToolIndex(messages, f.tool, f.inputPreview)
                if (i >= 0) {
                    messages[i] = (messages[i] as ChatItem.Tool).copy(taskId = f.toolUseId)
                    return
                }
            }
        }
        fun cardIndex(taskId: String?) =
            if (taskId == null) -1 else messages.indexOfLast { it is ChatItem.Tool && it.taskId == taskId }
        when {
            f.phase == ToolPhase.RESULT -> {
                val i = cardIndex(f.toolUseId)
                // no card on screen (opened mid-run): the reattach history replay carries the outcome instead
                if (i >= 0) messages[i] = (messages[i] as ChatItem.Tool).copy(ok = f.ok, output = f.output)
            }
            parent != null -> {
                val i = cardIndex(parent)
                if (i >= 0) {
                    val card = messages[i] as ChatItem.Tool
                    messages[i] = card.copy(childCount = card.childCount + 1, lastChild = f.tool)
                } else messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: ""))
            }
            // OpenCode's question tool renders as a read-only question card, not a raw JSON row (issue
            // #210); a parse miss (old truncated preview / malformed) falls back to the plain tool card.
            else -> OpenCodeQuestionParse.parse(f.tool, f.inputPreview)
                ?.let { messages.add(ChatItem.OpenCodeQuestion(it)) }
                ?: messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: "", taskId = f.toolUseId))
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun historyItem(h: HistoryMessage): ChatItem = when (h.role) {
        // images the prompt carried replay as real tiles (issue #254) — a turn composed at the computer
        // is no longer text-only here, and an image-ONLY turn is no longer a blank bubble. A base64 blob
        // the platform can't decode is dropped rather than rendered as a broken tile; the renderer's own
        // decode-failure card covers bytes that only fail later (on the image decoder).
        ChatRole.USER -> ChatItem.User(
            h.text,
            images = h.images.mapNotNull { runCatching { Base64.Default.decode(it.base64) }.getOrNull() },
            imagesTruncated = h.imagesTruncated,
            // rewind/fork anchor coordinates (issue #282) — carried verbatim, including their absence
            seq = h.seq,
            uuid = h.uuid,
        )
        // a synthetic API-failure placeholder replays as the error it was, not as a normal reply (issue #65).
        // Attribution follows the placeholder text so the replay reads the same as the daemon live prompt:
        // an upstream gateway/5xx signal stops blaming context (issue #208).
        ChatRole.ASSISTANT -> if (h.error) {
            ChatItem.Sys(
                "API request failed — the agent wrote a placeholder, not a real reply. " +
                    dev.ccpocket.protocol.SyntheticAttribution.attribution(h.text) +
                    "\n\nplaceholder reply: ${h.text}",
            )
        } else {
            ChatItem.Assistant(h.text)
        }
        // an answered AskUserQuestion replays as the same compact answered row the live path leaves, not a
        // raw-JSON tool card (issue #110); ok/output keep a sub-agent card's outcome + report (issue #77);
        // workflowRunId binds a Workflow card to its separately-pushed run (issue #106)
        ChatRole.TOOL -> h.answers?.let { a -> ChatItem.QuestionsAnswered(a.map { it.question to it.answer }) }
            ?: OpenCodeQuestionParse.parse(h.tool ?: "", h.text)?.let { ChatItem.OpenCodeQuestion(it) }
            ?: ChatItem.Tool(h.tool ?: "tool", h.text, ok = h.ok, output = h.output, workflowRunId = h.workflowRunId)
    }

    private fun <T> replace(list: MutableList<T>, items: List<T>) {
        list.clear(); list.addAll(items)
    }

    private fun clearBackgroundJobs() {
        replace(backgroundJobs, emptyList())
        // workflow state is per-conversation, cleared at the same session boundaries (#106)
        workflowRuns.clear()
        workflowAgentDetails.clear()
        viewedWorkflowRunId.value = null
    }

    private fun appendChunk(c: AssistantChunk) {
        streaming.value = true
        when (val p = c.piece) {
            is StreamPiece.Text -> {
                finishThinking() // prose starting = the thinking block (if any) is done
                // one-shot replay-echo dedupe (issue #107): the first block after a merged ConvoHistory
                // can be the very block the replay already included — appending it would double the
                // bubble's tail. Only an exact tail match is dropped; anything else streams normally.
                val echo = replayEcho && TranscriptMerge.isEchoText(messages, p.text)
                replayEcho = false
                if (echo) return
                val last = messages.lastOrNull()
                if (last is ChatItem.Assistant) messages[messages.lastIndex] = last.copy(text = last.text + p.text)
                else messages.add(ChatItem.Assistant(p.text))
            }
            is StreamPiece.Thinking -> {
                replayEcho = false // replay carries no thinking rows — a thinking chunk can't be an echo
                val last = messages.lastOrNull()
                if (last is ChatItem.Thinking && last.seconds == null) {
                    messages[messages.lastIndex] = last.copy(text = last.text + p.text)
                } else {
                    thinkStartMs = dev.ccpocket.app.epochMillis()
                    messages.add(ChatItem.Thinking(p.text))
                }
            }
        }
    }

    /** Stamp the duration onto a still-open Thinking block (design: "Thought for 5s"). */
    private fun finishThinking() {
        val start = thinkStartMs ?: return
        thinkStartMs = null
        val i = messages.indexOfLast { it is ChatItem.Thinking }
        if (i < 0) return
        val t = messages[i] as ChatItem.Thinking
        if (t.seconds == null) {
            val secs = (((dev.ccpocket.app.epochMillis() - start) + 500) / 1000).toInt().coerceAtLeast(1)
            messages[i] = t.copy(seconds = secs)
        }
    }

    /** Pull-to-refresh the project list (re-scans the daemon's directories + live state). */
    fun refreshDirectories() = refreshWithSpinner(refreshing, ListDirectories())

    /** One-shot list refresh behind a pull-to-refresh spinner: the frame handler clears [flag] when the
     *  reply lands; a safety window clears it even if no reply ever does (send() swallows transport
     *  errors itself). One copy of that knowledge for both the project and session lists. */
    private fun refreshWithSpinner(flag: MutableState<Boolean>, frame: Frame) {
        flag.value = true
        scope.launch { send(frame); delay(REFRESH_SPINNER_SAFETY_MS); flag.value = false }
    }

    /** Keep the open-project list fresh without the pull-to-refresh spinner (the daemon list is pull-only). */
    fun refreshDirectoriesSilently() = scope.launch { runCatching { send(ListDirectories()) } }

    /** The daemon host's Claude CLI login (Settings ▸ Account) — the latest [AuthState] push wins;
     *  the client never builds its own login state machine. Null until first fetched (or the daemon
     *  predates pocket/auth.* and silently drops the request). */
    val authState = mutableStateOf<AuthState?>(null)

    fun fetchAuthStatus() = scope.launch { runCatching { send(FetchAuthStatus) } }

    /** Whether the daemon pushes "turn complete" alerts to phones — daemon truth via [PushPrefs].
     *  Null until first fetched (or the daemon predates pocket/push.prefs.* — hide the toggle then). */
    val pushPrefs = mutableStateOf<Boolean?>(null)

    fun fetchPushPrefs() = scope.launch { runCatching { send(SetPushPrefs()) } }

    fun setPushEnabled(enabled: Boolean) = scope.launch { runCatching { send(SetPushPrefs(enabled)) } }

    /** Issue #201: whether the owner's own approval asks wait for a manual decision instead of auto-denying.
     *  Daemon truth via [ApprovalPrefs] — null until first fetched, and PERMANENTLY null against a daemon that
     *  predates #201 (it drops the request), which is exactly the signal to hide the setting rather than offer
     *  a switch that would silently do nothing. */
    val approvalPrefs = mutableStateOf<Boolean?>(null)

    /** Issue #220: the owner's Full Control expiry duration in ms; 0 = never expires (the default). Daemon
     *  truth via [ApprovalPrefs] — null until first fetched, and PERMANENTLY null against a pre-#220 daemon
     *  (which decodes the frame but the field simply defaults 0 there); the UI reads [approvalPrefs] for its
     *  capability gate, so this rides alongside it. */
    val approvalFullControlExpiryMs = mutableStateOf<Long?>(null)

    fun fetchApprovalPrefs() = scope.launch { runCatching { send(SetApprovalPrefs()) } }

    fun setAskNoAutoDeny(enabled: Boolean) = scope.launch { runCatching { send(SetApprovalPrefs(noAutoDeny = enabled)) } }

    /** Issue #220: set how long a manually-entered Full Control lasts (ms; 0 = never expires). */
    fun setFullControlExpiryMs(ms: Long) = scope.launch { runCatching { send(SetApprovalPrefs(fullControlExpiryMs = ms.coerceAtLeast(0L))) } }

    /** Switch account: the daemon logs the CLI out (when needed) and starts `claude auth login` —
     *  the browser opens on the daemon host; [authState] turns loginPending with the OAuth URL.
     *  [force] = the user saw [AuthState.blockers] and chose "stop them & switch": the daemon closes
     *  mid-task sessions too (resumable from disk) instead of refusing again. */
    fun authLogin(force: Boolean = false) = scope.launch { runCatching { send(AuthLogin(force = force)) } }

    /** Stop ONE blocker session (hard close, busy or not), then re-attempt the switch — the daemon
     *  either proceeds (that was the last blocker) or replies with the remaining list. */
    fun authStopBlocker(convoId: String) = scope.launch {
        runCatching {
            send(CloseSession(convoId, force = true))
            send(AuthLogin())
        }
    }

    /** The authorization code the user copied from the browser — completion arrives as a fresh [AuthState]. */
    fun authSubmitCode(code: String) = scope.launch { runCatching { send(AuthLoginCode(code)) } }

    fun authCancelLogin() = scope.launch { runCatching { send(AuthLoginCancel) } }

    fun authLogout() = scope.launch { runCatching { send(AuthLogout) } }

    /** API presets (issue #113): the daemon's presets truth — the latest [PresetsState] reply wins.
     *  Null until first fetched (or the daemon predates pocket/presets.* and silently drops the
     *  request — the client then shows an "update the daemon" line and NEVER offers the token form,
     *  so a plaintext token can't be fired at a peer that won't store it). */
    val presetsState = mutableStateOf<PresetsState?>(null)

    /** Monotonic count of [PresetsState] replies — the settle signal for spinners/forms (see handle). */
    val presetsStateRev = mutableStateOf(0)

    /** The daemon's third-party `ANTHROPIC_BASE_URL` (issue #139), learned from [DaemonInfo] after each
     *  handshake — non-null means claude launches route through a gateway, so the model picker leads
     *  with the gateway model presets. Null on the official endpoint or from a daemon that predates it. */
    val gatewayBaseUrl = mutableStateOf<String?>(null)

    /** Whether the connected daemon understands the bridge control plane (issue #91), from [DaemonInfo]:
     *  null until the first one lands, false from a daemon too old to carry the field. The Bridges screen
     *  shows "update the daemon" up front on false, instead of waiting for a bridge fetch to time out. */
    val bridgeControl = mutableStateOf<Boolean?>(null)

    /** Agent wire names accepted by the connected daemon, learned from [DaemonInfo]. Empty is both the
     * safe pre-handshake state and the decoded value from an older daemon. Only post-advertisement agents
     * use it as a hard gate; see [agentAvailableFromDaemon]. */
    val daemonSupportedAgents = mutableStateOf<Set<String>>(emptySet())

    /** #276: has THIS connection's DaemonInfo (or the demo/current-daemon assertion) actually landed yet?
     *  [daemonSupportedAgents] being empty conflates "not told yet" (reconnect window) with "told, lacks the
     *  backend" (old daemon). The egress guard must only deny on the latter, or it drops legitimate reattach
     *  frames during every reconnect. False until a handshake sets the capability set. */
    private var daemonAgentsKnown = false

    /** Whether the connected daemon honors [FetchUsage.agent] (issue #258). Deliberately NOT inferred from
     * [daemonSupportedAgents]: that advertisement shipped a release EARLIER, so a v1.7.7 daemon advertises
     * every agent while silently ignoring the filter — the usage page would then label a full-machine total
     * as one agent's. False (the pre-handshake and old-daemon value) hides the filter row entirely. */
    val daemonUsageAgentFilter = mutableStateOf(false)

    /** DaemonInfo capability: this daemon keeps every acked prompt in its unconsumed ledger and owns
     * redelivery after process replacement (#122). Silence is not a safe client-side resend signal then.
     * Plain Boolean because it only selects repository mechanics; no UI observes it directly. */
    private var daemonOwnsPromptRecovery = false

    fun supportsAgent(agent: AgentKind): Boolean = agentAvailableFromDaemon(agent, daemonSupportedAgents.value)

    /** Persisted ZCode remains the user's preference across machine switches, but an older daemon gets a
     * temporary Claude fallback so its UI never displays or launches an agent it did not advertise. */
    val sessionDefaultAgent: AgentKind
        get() = defaultAgent.value.takeIf(::supportsAgent) ?: AgentKind.CLAUDE

    val availableAgents: List<AgentKind>
        get() = availableAgentsFromDaemon(daemonSupportedAgents.value)

    // ── "open a session carrying a first prompt" (issues #256 / #260) ────────────────────────────────
    // There is no protocol frame for it and this deliberately doesn't invent one: the queue is a wait for
    // the SAME convoId the chat surfaces wait for, followed by the ordinary [sendPrompt] path — so the
    // prompt passes every gate a typed prompt passes and a daemon of any vintage works unchanged.
    //
    // The WAIT lives here, in commonMain, because both shells need exactly it: the desktop empty pane
    // (#256) and the phone's new-task sheet (#260). Only the wait is shared — each shell keeps its own
    // orchestration, because "where does the text go when it fails" is a different answer per surface
    // (the desktop pane re-renders with it; the phone re-opens its sheet with it).

    /** How long a queued first prompt waits for its session to go live. Generous on purpose — a cold agent
     *  start on a big repo is seconds, and the alternative to waiting is throwing the prompt back at a user
     *  whose session is about to open anyway. Tests shrink it. */
    internal var firstPromptTimeoutMs: Long = 30_000L

    /**
     * Suspend until the session opened after [previousConvo] is live enough to take a prompt.
     *
     * @return true = a NEW convoId landed (SessionLive), false = the repo's own open watchdog gave up,
     *   null = [timeoutMs] elapsed with neither. Callers must treat all three as distinct: only `true`
     *   may send, and the two failures owe the user their text back.
     *
     * [previousConvo] must be captured BEFORE the open: [openSession] nulls convoId from a coroutine that
     * can suspend on the outgoing CloseSession first, so "convoId is non-null" alone could still be the
     * PREVIOUS session — and would send this prompt into it.
     */
    internal suspend fun awaitOpenedConvo(previousConvo: String?, timeoutMs: Long = firstPromptTimeoutMs): Boolean? =
        withTimeoutOrNull(timeoutMs) {
            snapshotFlow {
                val id = convoId.value
                when {
                    id != null && id != previousConvo -> true
                    openTimedOut.value -> false
                    else -> null
                }
            }.filterNotNull().first()
        }

    /** Why a queued first prompt didn't become a turn; the text is always still held by the caller. */
    enum class NewTaskError { OPEN_REFUSED, TIMEOUT, SEND_REFUSED }

    // The phone's new-task sheet state (#260). REPOSITORY-owned, not `remember`ed in the sheet, for two
    // reasons: a dismissed-and-reopened sheet must show the same draft and the same two chips, and a queue
    // that fails has to hand the text back to a sheet that no longer exists at the moment it fails. (This
    // deliberately stays OUT of the composer draft collector — see the #256 note on startSessionWithPrompt:
    // a queued prompt landing in a live composer races the target session's own draft restore.)
    val newTaskDraft = mutableStateOf("")
    /** Chip pick: the project to start in. Null = "not chosen yet", so the sheet prefills from recents. */
    val newTaskDir = mutableStateOf<String?>(null)
    /** Chip pick: the backend. Null = "not chosen yet", so the sheet prefills from [sessionDefaultAgent]. */
    val newTaskAgent = mutableStateOf<AgentKind?>(null)
    val newTaskError = mutableStateOf<NewTaskError?>(null)
    /** A first prompt is queued and its session hasn't landed — the sheet is closed and the list waits. */
    val newTaskStarting = mutableStateOf(false)

    /**
     * Open a session at [wd] on [agent] and send [prompt] as its first turn (issue #260).
     *
     * [onDelivered] runs only when the prompt actually became a turn — that is the phone's cue to route
     * into the chat. Every failure path instead leaves [newTaskDraft] / the two chip picks exactly as the
     * user left them and sets [newTaskError], so re-opening the sheet shows the draft and one inline line.
     *
     * @return false when nothing was started at all (blank prompt, or one already queued).
     */
    fun startTaskWithPrompt(wd: String, prompt: String, agent: AgentKind, onDelivered: () -> Unit = {}): Boolean {
        if (prompt.isBlank() || newTaskStarting.value) return false
        newTaskError.value = null
        newTaskDraft.value = prompt // held until a send actually succeeds; only then is it cleared
        val previousConvo = convoId.value
        // A backend this daemon never advertised must be refused BEFORE the sheet closes — openSession
        // would return false anyway, but saying so while the picks are still on screen is the honest order.
        if (!supportsAgent(agent) || !openSession(wd, agent = agent)) {
            newTaskError.value = NewTaskError.OPEN_REFUSED
            return false
        }
        newTaskStarting.value = true
        scope.launch {
            val live = awaitOpenedConvo(previousConvo)
            when {
                live == null -> newTaskError.value = NewTaskError.TIMEOUT
                !live -> newTaskError.value = NewTaskError.OPEN_REFUSED
                sendPrompt(prompt) -> { newTaskDraft.value = ""; newTaskError.value = null; onDelivered() }
                // gated (degraded session / uploads in flight): the session IS open, but the text is still
                // held here rather than silently dropped into a turn that never happened
                else -> newTaskError.value = NewTaskError.SEND_REFUSED
            }
            // Published LAST: "starting is over" is the signal observers key on, so the outcome above must
            // already be readable when it flips — clearing it first opened a window where the sheet (and the
            // test) saw "finished, no error" for a queued prompt that actually failed (same lesson as #245:
            // the transient flag must not be the last word).
            newTaskStarting.value = false
        }
        return true
    }

    /** App / daemon / newest-release versions (issue #200), refreshed from every [DaemonInfo]. Starts as
     *  "only our own version known"; a daemon too old to report leaves the other fields null, which reads
     *  as "unknown" everywhere rather than as "up to date". */
    val versionStatus = mutableStateOf(VersionStatus(APP_VERSION))

    fun fetchPresets() = scope.launch { runCatching { send(FetchPresets) } }

    /** Per-agent model lists from the daemon ([FetchModels] → [ModelsList]) — what the picker offers
     *  beyond the static presets. Keyed by agent so a late reply can't cross-pollute another backend. */
    val agentModels = mutableStateMapOf<AgentKind, ModelsList>()

    fun modelCapabilities(agent: AgentKind, modelId: String? = model.value): dev.ccpocket.protocol.ModelCapabilities? {
        val listed = agentModels[agent] ?: return null
        // null is the CLI's real "Default" selection, not an alias for the first advertised model. The
        // daemon cannot know which concrete model that default will resolve to for this account/session, so
        // its capabilities are UNKNOWN and persisted launch options must pass through unchanged.
        val id = modelId ?: return null
        return listed.modelCapabilities.firstOrNull { it.model.equals(id, ignoreCase = true) }
    }

    /** The authoritative reasoning levels for one launch, when the daemon knows them. A matching
     * per-model row wins even when its list is empty; otherwise a non-empty backend-wide advertisement
     * (Claude CLI) applies. Null means an old daemon or an unknown/custom model, so callers preserve the
     * legacy pass-through behaviour instead of guessing that a persisted value is invalid. */
    private fun supportedReasoningEfforts(agent: AgentKind, modelId: String?): List<String>? {
        val listed = agentModels[agent] ?: return null
        modelCapabilities(agent, modelId)?.let { return it.reasoningEfforts }
        return listed.supportedEfforts.takeIf { it.isNotEmpty() }
    }

    /** Reconcile Codex's persisted service tier against a fresh daemon catalog / model change.
     *
     * #274: the effort preference is deliberately NOT reconciled here. SecureStore is global, not
     * per-machine, so clearing it whenever THIS machine's CLI advertises a leaner set permanently erased a
     * preference other machines still support — and switching back never restored it. openSession AND
     * takeOver already clamp the effort to the machine's supported set at launch (knownEfforts /
     * supportedEfforts), so an unsupported preference is simply not sent where it doesn't fit and honoured
     * where it does — non-destructively, restored on return. (The Codex service-tier reconcile below stays:
     * its launch-time clamp isn't yet uniform across takeOver, so removing it could leak an unsupported
     * tier — tracked with #274 as the same class, to be lifted once that clamp is uniform.) */
    private fun reconcileDefaultCapabilities(agent: AgentKind) {
        val modelId = defaultModelFor(agent)
        modelCapabilities(agent, modelId)?.let { caps ->
            if (agent == AgentKind.CODEX &&
                defaultServiceTier.value != null && caps.serviceTiers.none { it.id == defaultServiceTier.value }
            ) {
                setDefaultServiceTier(null)
            }
        }
    }

    /** Reasoning choices for the active model. Codex is strictly model-specific; Claude's installed CLI
     *  advertises a backend-wide set. A present per-model row is authoritative even when empty. If both
     *  capability fields are absent, the daemon predates #183, so retain the picker it already supported. */
    fun effortOptions(agent: AgentKind = sessionAgent.value ?: AgentKind.CLAUDE, modelId: String? = model.value): List<String> {
        if (agentModels[agent] == null) return emptyList()
        return supportedReasoningEfforts(agent, modelId) ?: LEGACY_EFFORT_OPTIONS
    }

    fun serviceTierOptions(
        agent: AgentKind = sessionAgent.value ?: AgentKind.CLAUDE,
        modelId: String? = model.value,
    ) = modelCapabilities(agent, modelId)?.serviceTiers.orEmpty()

    fun supportsPermissionMode(id: String, agent: AgentKind = AgentKind.CLAUDE): Boolean =
        id in agentModels[agent]?.permissionModes.orEmpty()

    /** The permission-mode presets [agent]'s daemon advertises — the picker's vocabulary when it is
     *  non-empty. Empty means "not advertised" (an older daemon, or no [ModelsList] for this agent yet),
     *  never "this backend has no modes": the caller falls back to the App's built-in table. */
    fun modePresetsFor(agent: AgentKind): List<dev.ccpocket.protocol.AgentModePreset> =
        agentModels[agent]?.modePresets.orEmpty()

    fun fetchModels(agent: AgentKind = sessionAgent.value ?: AgentKind.CLAUDE) {
        scope.launch { runCatching { send(FetchModels(agent = agent, workdir = workdir.value)) } }
    }

    /** Create (null [id]) / update one preset. [token] is write-only plaintext (E2E protects the
     *  transport; the daemon stores it and only ever echoes a mask); null token on update = keep. */
    fun savePreset(id: String?, name: String, baseUrl: String, tokenVar: String, token: String?, model: String?, smallFastModel: String?) =
        scope.launch {
            runCatching {
                send(
                    SavePreset(
                        id = id, name = name, baseUrl = baseUrl, tokenVar = tokenVar,
                        token = token?.takeIf { it.isNotBlank() }?.let(::Secret),
                        model = model?.takeIf { it.isNotBlank() },
                        smallFastModel = smallFastModel?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    fun deletePreset(id: String, force: Boolean = false) = scope.launch { runCatching { send(DeletePreset(id, force)) } }

    /** Switch the active preset (null = back to the computer's own env/login). Same semantics as an
     *  account switch: mid-task sessions refuse via [PresetsState.blockers]; idle ones close + resume. */
    fun activatePreset(id: String?, force: Boolean = false) = scope.launch { runCatching { send(ActivatePreset(id, force)) } }

    /** Stop ONE preset-switch blocker (hard close), then re-attempt activating [retryId] — the daemon
     *  either proceeds (that was the last blocker) or replies with the remaining list. */
    fun presetStopBlocker(convoId: String, retryId: String?) = scope.launch {
        runCatching {
            send(CloseSession(convoId, force = true))
            send(ActivatePreset(retryId))
        }
    }

    /** Same, for a blocked DELETE of the active preset: close one blocker, retry the delete. */
    fun presetStopBlockerForDelete(convoId: String, deleteId: String) = scope.launch {
        runCatching {
            send(CloseSession(convoId, force = true))
            send(DeletePreset(deleteId))
        }
    }

    /** Token-usage dashboard (issue #26): the latest daemon-aggregated snapshot + a fetch-in-flight flag. */
    val usage = mutableStateOf<Usage?>(null)
    val usageLoading = mutableStateOf(false)

    /** Ask the daemon to aggregate usage over the last [days] local days; the reply lands in [usage].
     *  [agent] non-null narrows it to one backend (issue #258); null is the all-backends total. */
    fun fetchUsage(days: Int = 7, agent: AgentKind? = null) {
        usageLoading.value = true
        scope.launch { send(FetchUsage(days, agent)) }
    }

    /** Monotonic count of turns that FINISHED on this machine, in any conversation (see [handle]). A
     *  change is the "some agent just spent tokens" edge the quota refresh policy debounces on; the
     *  absolute value is meaningless and it is never persisted. */
    val turnCompletions = mutableStateOf(0L)

    // ── Claude subscription allowance (the 5h/7d windows behind the CLI's own `/usage` panel) ──
    /** The daemon's latest [ClaudeQuota]. Null = we have not been told: either the fetch is still in
     *  flight, or this daemon predates the frame and silently dropped the request — indistinguishable on
     *  the wire, which is why the page hides the block in BOTH cases rather than showing an error. */
    val claudeQuota = mutableStateOf<ClaudeQuota?>(null)
    val claudeQuotaLoading = mutableStateOf(false)

    /** The status of the LAST reply, including the transient failures [claudeQuota] deliberately does not
     *  absorb. Null = never answered. Diagnostics only — no UI should turn a blip into an alarm. */
    val claudeQuotaStatus = mutableStateOf<String?>(null)

    /** Fired whenever a [ClaudeQuota] lands, success or failure. The refresh policy clears its in-flight
     *  latch here; a callback rather than a state read so a dropped reply cannot look like a fresh one. */
    internal var onClaudeQuotaReply: (() -> Unit)? = null

    private var claudeQuotaDeadline: Job? = null

    /** Ask the daemon for the subscription allowance; the reply lands in [claudeQuota]. The daemon caches
     *  briefly on its side, so re-entering the usage page is cheap and [forceRefresh] is the manual
     *  override. A deadline clears the in-flight flag so an old daemon's silence does not spin forever. */
    fun fetchClaudeQuota(forceRefresh: Boolean = false) {
        claudeQuotaLoading.value = true
        claudeQuotaDeadline?.cancel()
        scope.launch { send(ClaudeQuotaGet(forceRefresh)) }
        claudeQuotaDeadline = scope.launch {
            delay(12_000)
            claudeQuotaLoading.value = false
        }
    }

    // ── installed skills/plugins catalog (issue #132): the desktop browse page ──
    val skillCatalog = mutableStateOf<SkillCatalog?>(null)
    val skillCatalogLoading = mutableStateOf(false)
    /** No reply — the daemon predates pocket/skills.* (an old daemon silently drops the unknown frame,
     *  so silence is the only signal) — distinct from an EMPTY catalog. */
    val skillCatalogUnavailable = mutableStateOf(false)
    private var skillCatalogDeadline: Job? = null

    /** Ask the daemon for its installed skills + plugins; the reply lands in [skillCatalog]. The open
     *  chat's workdir (when any) rides along so project-level skills list too. Same stale-daemon
     *  deadline discipline as [fetchChangedFiles]: better an honest "update the daemon" than a spinner. */
    fun fetchSkillCatalog() {
        skillCatalogLoading.value = true
        skillCatalogUnavailable.value = false
        skillCatalogDeadline?.cancel()
        skillCatalogDeadline = scope.launch {
            delay(8_000)
            if (skillCatalogLoading.value) { skillCatalogLoading.value = false; skillCatalogUnavailable.value = true }
        }
        scope.launch { send(FetchSkillCatalog(workdir.value)) }
    }

    // ── headless bridges (issue #91 follow-up): the owner's IM-bot control plane ──
    /** The daemon's bridges — the latest [BridgeListing]. */
    val bridges = mutableStateListOf<BridgeInfo>()
    /** True once the first [BridgeListing] of this session lands — "no bridges yet" vs. "still loading". */
    val bridgesLoaded = mutableStateOf(false)
    /** No reply — the daemon predates pocket/bridge.* and silently drops the unknown frame, so silence is
     *  the only signal. Distinct from an EMPTY list: one says "update the daemon", the other "create one". */
    val bridgesUnavailable = mutableStateOf(false)
    /** The last create/revoke/runner error, for the page to surface verbatim. Cleared on the next request. */
    val bridgeError = mutableStateOf<String?>(null)
    /** A just-minted UNMANAGED credential the owner must copy out before its TTL lapses. Managed bridges
     *  never set this — the daemon injected the ticket itself and there is nothing to hand over. */
    val bridgeCredential = mutableStateOf<BridgeCredential?>(null)
    val bridgeBusy = mutableStateOf(false)
    /** Non-null = the last MERGE edit came back with env keys MISSING — the daemon is too old for partial
     *  edits and replaced wholesale (the exact way a user once lost their app secret). The UI must shout,
     *  not shrug: these keys now need re-entering. Cleared on the next bridge request. */
    val bridgeMergeLost = mutableStateOf<List<String>?>(null)
    private var pendingMergeCheck: Pair<String, Set<String>>? = null
    private var bridgesDeadline: Job? = null
    private var bridgeBusyDeadline: Job? = null

    /** Arm a request: clear the last error and make sure a lost reply can't spin the page forever. */
    private fun bridgeRequestStarted() {
        bridgeBusy.value = true
        bridgeError.value = null
        bridgeMergeLost.value = null
        bridgeBusyDeadline?.cancel()
        bridgeBusyDeadline = scope.launch {
            delay(10_000)
            if (bridgeBusy.value) {
                bridgeBusy.value = false
                bridgeError.value = "the daemon didn't answer — try again"
            }
        }
    }

    fun fetchBridges() {
        bridgesUnavailable.value = false
        bridgesDeadline?.cancel()
        bridgesDeadline = scope.launch {
            delay(8_000)
            if (!bridgesLoaded.value) bridgesUnavailable.value = true
        }
        scope.launch { send(ListBridges) }
    }

    /** Mint a bridge. [runner] non-null = the daemon manages the adapter process and the credential never
     *  comes back to us (see [CreateBridge.runner]). */
    fun createBridge(
        name: String,
        workdirs: List<String>,
        tier: AccessTier = AccessTier.REVIEW,
        maxSessions: Int? = null,
        runner: BridgeRunnerSpec? = null,
        allowedCommands: List<String> = emptyList(),
    ) {
        bridgeRequestStarted()
        bridgeCredential.value = null
        scope.launch { send(CreateBridge(name, workdirs, maxSessions, tier = tier, allowedCommands = allowedCommands, runner = runner)) }
    }

    fun revokeBridge(name: String) {
        bridgeRequestStarted()
        scope.launch { send(RevokeBridge(name)) }
    }

    fun controlBridgeRunner(name: String, action: String) {
        bridgeRequestStarted()
        scope.launch { send(ControlBridgeRunner(name, action)) }
    }

    /** [mergeEnv] = the edit path: only non-blank env values land, everything else is kept daemon-side. */
    fun configureBridgeRunner(name: String, spec: BridgeRunnerSpec, mergeEnv: Boolean = false, workdirs: List<String>? = null, allowedCommands: List<String>? = null) {
        bridgeRequestStarted()
        // arm the merge-loss guard: remember what WAS configured, so the reply can prove nothing vanished
        pendingMergeCheck = if (mergeEnv) {
            name to (bridges.firstOrNull { it.name == name }?.runner?.envKeys?.toSet() ?: emptySet())
        } else null
        scope.launch { send(ConfigureBridgeRunner(name, spec, mergeEnv, workdirs, allowedCommands)) }
    }

    /** Dismiss the one-shot credential card once the owner says they've copied it. */
    fun clearBridgeCredential() { bridgeCredential.value = null }

    // ── scheduled tasks (issue #137): one-shot & repeat prompt deliveries the daemon fires ──
    /** The daemon's schedule list — the latest [ScheduleState]. */
    val schedules = mutableStateListOf<ScheduleInfo>()
    /** True once the first [ScheduleState] of this session lands — "empty" vs. "still loading". */
    val schedulesLoaded = mutableStateOf(false)
    /** No reply — the daemon predates pocket/schedule.* (it silently drops the unknown frame, so
     *  silence is the only signal) — distinct from an EMPTY list. */
    val schedulesUnavailable = mutableStateOf(false)
    /** The last request's user-facing refusal ([ScheduleState.error]); null = the last op succeeded. */
    val scheduleError = mutableStateOf<String?>(null)
    private var scheduleDeadline: Job? = null

    /** One-tap "auto-continue when the limit resets" (issue #137): set when a turn failed on a usage
     *  limit AND the daemon parsed the reset moment out of the CLI's error text
     *  ([TurnDone.usageLimitResetAt]); the banner offers [scheduleAutoContinue]. Cleared on session
     *  switch and on the next manual send (the user moved on). */
    data class LimitOffer(
        val convoId: String,
        val sessionId: String?,
        val workdir: String,
        val resetAtMs: Long,
        // A1 (#137): the client-chosen id [scheduleAutoContinue] sent as ScheduleCreate.clientId — the
        // daemon adopts it as the schedule's id, so [undoAutoContinue] cancels by an id we already hold
        // (no dependency on the ScheduleState reply having landed, immune to the daemon's runAtMs clamp).
        val autoContinueId: String? = null,
    )
    val limitOffer = mutableStateOf<LimitOffer?>(null)

    /** The offer [scheduleAutoContinue] just consumed — drives the banner's in-place "confirmed" flip
     *  (design: scheduled-prompts.jsx C2) with its Undo. Cleared wherever [limitOffer] is. */
    val limitConfirmed = mutableStateOf<LimitOffer?>(null)

    private fun armScheduleDeadline() {
        scheduleError.value = null
        scheduleDeadline?.cancel()
        scheduleDeadline = scope.launch {
            delay(8_000)
            if (!schedulesLoaded.value) schedulesUnavailable.value = true
        }
    }

    /** Pull the daemon's schedule list; the reply lands in [schedules]. Same stale-daemon deadline
     *  discipline as [fetchSkillCatalog] — better an honest "update the daemon" than a spinner. */
    fun fetchSchedules() {
        schedulesUnavailable.value = false
        armScheduleDeadline()
        scope.launch { send(ScheduleList) }
    }

    /**
     * Create one scheduled delivery: [prompt] fires into [resumeId] (default: the OPEN session) under
     * [workdir] (default: the open session's cwd) at [runAtMs]. Returns false when no target workdir is
     * known (nothing sent). The daemon answers with the updated [ScheduleState].
     */
    fun createSchedule(
        prompt: String,
        runAtMs: Long,
        repeat: ScheduleRepeat? = null,
        label: String? = null,
        workdir: String? = null,
        resumeId: String? = null,
        clientId: String? = null,
    ): Boolean {
        val wd = workdir ?: this.workdir.value ?: return false
        if (prompt.isBlank()) return false
        val sid = resumeId ?: sessionKey.value ?: currentSessionId
        armScheduleDeadline()
        scope.launch {
            send(
                ScheduleCreate(
                    workdir = wd, prompt = prompt, runAtMs = runAtMs, repeat = repeat, resumeId = sid,
                    agent = sessionAgent.value ?: AgentKind.CLAUDE,
                    model = model.value, mode = mode.value, label = label, clientId = clientId,
                ),
            )
        }
        return true
    }

    /** Remove one schedule; the daemon replies with the updated list. */
    fun cancelSchedule(id: String) {
        armScheduleDeadline()
        scope.launch { send(ScheduleCancel(id)) }
    }

    /** The limit-reset one-tap (issue #137): schedule a "Continue" back into the limited session shortly
     *  after the window resets. Returns false when the offer is gone / has no usable target. */
    fun scheduleAutoContinue(): Boolean {
        val offer = limitOffer.value ?: return false
        // A stable client id the daemon adopts as the schedule's id (see LimitOffer.autoContinueId).
        // Unique per (session, reset moment); it's what Undo cancels by.
        val clientId = "autocont-${offer.convoId}-${offer.resetAtMs}"
        val ok = createSchedule(
            prompt = "Continue",
            runAtMs = offer.resetAtMs + LIMIT_RESUME_MARGIN_MS,
            workdir = offer.workdir.takeIf { it.isNotEmpty() },
            resumeId = offer.sessionId,
            label = "Auto-continue",
            clientId = clientId,
        )
        if (ok) {
            limitOffer.value = null
            // the banner flips in place to "Will continue at …" + Undo, holding the id Undo cancels by
            limitConfirmed.value = offer.copy(autoContinueId = clientId)
            // same raw-English Sys convention as the session-expired notice above
            messages.add(ChatItem.Sys("auto-continue scheduled — this session resumes shortly after the limit resets"))
        }
        return ok
    }

    /** The confirmed banner's Undo: cancel the one-tap schedule and restore the offer so the user can
     *  re-decide. Cancels by [LimitOffer.autoContinueId] — the client-chosen id a NEW daemon adopted as
     *  the schedule's id — so it works the instant the banner is confirmed (no wait for the ScheduleState
     *  reply) and survives the daemon's runAtMs clamp (which broke the old nextRunAtMs signature match).
     *  A pre-clientId ("old") daemon minted its own id and ignored ours, so we ALSO try the legacy
     *  signature reverse-lookup as a best-effort fallback (an unknown id is a daemon no-op — sending both
     *  is safe). Either way the offer is restored — better an honest re-offer than a banner stuck
     *  confirmed. */
    fun undoAutoContinue() {
        val offer = limitConfirmed.value ?: return
        val id = offer.autoContinueId
        if (id != null) cancelSchedule(id) // NEW daemon adopted this as the schedule's id
        // legacy fallback: a daemon that ignored clientId listed the entry under its own id — match it
        // back by label (best-effort; the reply must have landed and the clamp not have moved nextRunAtMs)
        schedules.firstOrNull {
            it.label == "Auto-continue" && it.id != id &&
                it.nextRunAtMs == offer.resetAtMs + LIMIT_RESUME_MARGIN_MS
        }?.let { cancelSchedule(it.id) }
        limitConfirmed.value = null
        limitOffer.value = offer
    }

    // ── folder-share (issue #115): OWNER control plane + GUEST redeem ──
    /** Folders I've shared out (the management page) — the latest [ShareListing]. */
    val shares = mutableStateListOf<ShareInfo>()
    /** True once the first [ShareListing] of this session lands — distinguishes "empty" from "still loading". */
    val sharesLoaded = mutableStateOf(false)
    /** A create/list/revoke round-trip is in flight (spinner + button disable). */
    val sharesRefreshing = mutableStateOf(false)
    /** The most recent [ShareCreated] — the invite-ready screen reads its `invite`, or its `error`. */
    val lastShareCreated = mutableStateOf<ShareCreated?>(null)

    // ── guest side (issue #115 follow-up): the precise "your share ended" notice ──

    /** Set when the daemon told this GUEST its folder share ended ([ShareEnded]): the precise reason
     *  behind the disconnect that follows, driving the "Access ended · revoked/expired" terminal instead
     *  of the generic re-pair screen. Persisted per account (the frame can only ever precede the cut once —
     *  a relaunch must still light the card) and cleared when the binding is removed. Never set for an
     *  owner device: the daemon emits the frame exclusively to guest credentials. */
    val shareEnded = mutableStateOf(loadShareEnded(paired.value?.accountId))

    private fun loadShareEnded(accountId: String?): ShareEnded? {
        val raw = accountId?.let { SecureStore.getString(K_SHARE_ENDED_PREFIX + it) } ?: return null
        val t = raw.split('\t')
        return ShareEnded(reason = t[0], ownerLabel = t.getOrNull(1)?.takeIf { it.isNotEmpty() })
    }

    internal fun onShareEnded(f: ShareEnded) { // internal: exercised directly by ShareRepoTest
        shareEnded.value = f
        paired.value?.let { SecureStore.putString(K_SHARE_ENDED_PREFIX + it.accountId, f.reason + "\t" + (f.ownerLabel ?: "")) }
        // the credential dies with the notice — the disconnect that follows must not auto-retry (same
        // terminal treatment as AuthError; the gate renders the ended card off shareEnded, not the generic copy)
        pairingInvalid = true; retryJob?.cancel(); recomputePhase()
    }

    /** Owner: mint a scoped, expiring invite for [path]. Reply lands in [lastShareCreated]. */
    fun createShare(path: String, tier: AccessTier, expiresInSec: Long, label: String? = null) {
        lastShareCreated.value = null; sharesRefreshing.value = true
        scope.launch { runCatching { send(CreateShare(path, tier, expiresInSec, label)) } }
    }

    /** Owner: refresh the list of folders I've shared + who's using them (the management page). */
    fun listShares() { sharesRefreshing.value = true; scope.launch { runCatching { send(ListShares) } } }

    /** Owner: revoke a share by its guest [deviceId] — cuts the live link now, kills the credential. */
    fun revokeShare(deviceId: String) { sharesRefreshing.value = true; scope.launch { runCatching { send(RevokeShare(deviceId)) } } }

    // ════════════════════════════════════════════════════════════════
    //  Session handoff (SESSION-HANDOFF.md; design session-handoff/)
    // ════════════════════════════════════════════════════════════════
    /** The open session's handoffs, newest first (daemon truth via HandoffListing/HandoffUpdated). */
    val handoffs = mutableStateListOf<SessionHandoff>()

    /** The at-most-one non-terminal handoff on the open session — drives the WAITING lock banner,
     *  the IN_PROGRESS ribbons and the RETURNED result card. Null = a plain session. */
    val activeHandoff = mutableStateOf<SessionHandoff?>(null)

    val handoffCreating = mutableStateOf(false)
    val handoffError = mutableStateOf<String?>(null)

    /** The daemon's `handoff_not_supported` refusal (§6): this build asked for a kind/access combination the
     *  daemon knows about but hasn't fully implemented. Surfaced verbatim rather than swallowed, so the UI
     *  can say WHY the offer can't be taken instead of showing a dead Accept button. */
    val handoffUnsupported = mutableStateOf<String?>(null)

    /** The just-minted handoff, so the UI can flip from the draft sheet to the invite sheet. */
    val lastHandoffInvite = mutableStateOf<SessionHandoff?>(null)

    /** This device's role relative to [activeHandoff]: true when WE hold the controller lease side. */
    fun isHandoffRecipient(h: SessionHandoff): Boolean =
        h.recipientDeviceId != null && h.recipientDeviceId == paired.value?.deviceId

    fun isHandoffInitiator(h: SessionHandoff): Boolean = h.initiatorDeviceId == paired.value?.deviceId

    private fun upsertHandoff(h: SessionHandoff) {
        val i = handoffs.indexOfFirst { it.id == h.id }
        if (i >= 0) handoffs[i] = h else handoffs.add(0, h)
        recomputeActiveHandoff()
        reconcileHandoffLifecycle(h)
    }

    private fun recomputeActiveHandoff() {
        val sid = sessionKey.value ?: currentSessionId
        activeHandoff.value = handoffs.firstOrNull { !it.status.isTerminal && it.status != HandoffStatus.UNKNOWN && (sid == null || it.sourceSessionId == sid) }
    }

    // ── recipient: offers, the accept round-trip, and auto-entering the source session ──────────────

    /** WAITING offers addressed to THIS device, newest first — what the root-level incoming entry shows
     *  (§3.2.5). Never inferred from a local flag: an offer exists exactly while daemon truth says so, so
     *  a decline/cancel/expiry that happened while we were away simply drops out of the next listing. */
    fun incomingOffers(): List<SessionHandoff> {
        val me = paired.value?.deviceId ?: return emptyList()
        return handoffs.filter { it.status == HandoffStatus.WAITING && it.recipientDeviceId == me }
            .sortedByDescending { it.createdAt }
    }

    /** The handoff id whose AcceptHandoff is in flight — the Accept button's honest waiting state (§3.2.7).
     *  Cleared only by daemon truth (the handoff leaving WAITING) or by [handoffAcceptError]. */
    val handoffAccepting = mutableStateOf<String?>(null)

    /** Why the last accept could not be honoured (a race lost, an expired offer, an old daemon that dropped
     *  the frame). Non-null means the tap did NOT succeed — never silently swallowed. Held as a resource so
     *  the non-suspending frame handler can set it without a locale round-trip. */
    val handoffAcceptError = mutableStateOf<StringResource?>(null)

    /** Handoffs this process already auto-opened, so a replayed IN_PROGRESS (reconnect listing, a second
     *  HandoffUpdated) can't re-enter — or worse, churn — the session. */
    private val autoOpenedHandoffs = mutableSetOf<String>()

    /**
     * Daemon truth landed for [h] — settle the two things the recipient's UI owes the user (§3.2.6/§3.2.7):
     *
     *  1. the Accept spinner: it clears only when the daemon says the handoff left WAITING. Won by us →
     *     success; DECLINED/CANCELLED/EXPIRED/RECALLED, or IN_PROGRESS on someone ELSE's device → an
     *     explicit error, because "accepted" would be a lie;
     *  2. auto-open: an IN_PROGRESS handoff addressed to this device means the Grant is live and the source
     *     session is ours to drive — walk straight in instead of leaving the recipient on a dead-end card.
     *     mode / takeOver / pathScope are deliberately NOT sent: the daemon clamps them from the Grant.
     */
    private fun reconcileHandoffLifecycle(h: SessionHandoff) {
        val me = paired.value?.deviceId
        val mine = me != null && h.recipientDeviceId == me
        if (handoffAccepting.value == h.id && h.status != HandoffStatus.WAITING) {
            handoffAccepting.value = null
            if (!(mine && h.status == HandoffStatus.IN_PROGRESS)) {
                handoffAcceptError.value = handoffStatusRefusal(h.status)
            }
        }
        if (!mine || h.status != HandoffStatus.IN_PROGRESS) return
        // an owner device that merely OBSERVES someone else's handoff must not be yanked anywhere; only the
        // inbox link (whose sole purpose is this) and the device that just accepted walk in automatically
        if (!isCollaboratorInbox && h.id !in acceptedHere) return
        if (h.id in autoOpenedHandoffs) return
        if (convoId.value != null && currentSessionId == h.sourceSessionId) { autoOpenedHandoffs += h.id; return }
        autoOpenedHandoffs += h.id
        openSession(wd = h.workdir, resumeId = h.sourceSessionId, agent = h.agent)
    }

    /** Ids this device sent an AcceptHandoff for in this process — see [reconcileHandoffLifecycle]. */
    private val acceptedHere = mutableSetOf<String>()

    private fun handoffStatusRefusal(status: HandoffStatus): StringResource = when (status) {
        HandoffStatus.EXPIRED -> Res.string.ho_accept_expired
        HandoffStatus.CANCELLED, HandoffStatus.RECALLED -> Res.string.ho_accept_withdrawn
        HandoffStatus.DECLINED -> Res.string.ho_accept_declined
        else -> Res.string.ho_accept_taken
    }

    /**
     * The "update the daemon" copy every handoff / collaborator / review timeout below reaches for.
     *
     * Issue #251: these all run inside a detached `scope.launch { delay(8000); … }`, where a throwing
     * resource lookup has no handler and takes the desktop window down with an unnamed native error
     * box. Losing a translation on an error path is a cosmetic regression; losing the app is not — so
     * the lookup is contained and falls back to the English literal, tagged with a reportable code.
     * Keep the literal in sync with `ho_daemon_too_old` in strings.xml.
     */
    private suspend fun daemonTooOldText(): String =
        safeString(DAEMON_TOO_OLD_FALLBACK) { getString(Res.string.ho_daemon_too_old) }

    /** Owner: create a Handoff on the open session (v1: REVIEW read-only). [recipientDeviceId] non-null
     *  binds the Grant to that collaborator's device — only it may accept, and the daemon delivers the
     *  offer over the existing link (no invite artefact). The daemon replies with [HandoffCreated]; an
     *  old daemon drops the unknown frame — the timeout below surfaces that as the "update the daemon"
     *  error instead of hanging the sheet forever. */
    fun createHandoff(recipientLabel: String, expiresHours: Int, request: String, recipientDeviceId: String? = null) {
        val wd = workdir.value ?: return
        val sid = sessionKey.value ?: currentSessionId ?: return
        handoffCreating.value = true; handoffError.value = null
        val brief = HandoffBrief(request = request)
        scope.launch {
            runCatching {
                send(
                    CreateHandoff(
                        workdir = wd, sessionId = sid, brief = brief,
                        agent = sessionAgent.value ?: AgentKind.CLAUDE,
                        expiresInSec = expiresHours * 3600L,
                        recipientLabel = recipientLabel.takeIf { it.isNotBlank() },
                        sourceConvoId = convoId.value,
                        recipientDeviceId = recipientDeviceId,
                    ),
                )
            }
            delay(8000)
            if (handoffCreating.value) {
                handoffCreating.value = false
                handoffError.value = daemonTooOldText()
            }
        }
    }

    // ── collaborator links (SESSION-HANDOFF.md §4.1): contacts + one-time connect tickets ──
    /** My collaborator contacts, removed ones included (terminal group). Daemon truth via listing/updates. */
    val collaborators = mutableStateListOf<Collaborator>()

    val collaboratorTicket = mutableStateOf<CollaboratorInvite?>(null)
    val collaboratorTicketCreating = mutableStateOf(false)
    val collaboratorError = mutableStateOf<String?>(null)

    /** Someone redeemed the pending ticket — the connect screen flips to its Connected sub-state. */
    val lastCollaboratorConnected = mutableStateOf<Collaborator?>(null)

    private fun upsertCollaborator(c: Collaborator) {
        val i = collaborators.indexOfFirst { it.deviceId == c.deviceId }
        if (i >= 0) collaborators[i] = c else collaborators.add(0, c)
    }

    /** Set once any collaborator listing lands — distinguishes "none yet" from "old daemon dropped the frame". */
    val collaboratorsLoaded = mutableStateOf(false)

    fun listCollaborators() {
        scope.launch {
            runCatching { send(ListCollaborators) }
            delay(8000)
            if (!collaboratorsLoaded.value && collaboratorError.value == null) {
                collaboratorError.value = daemonTooOldText()
            }
        }
    }

    /** Mint a one-time connect ticket (short TTL). Reply lands in [collaboratorTicket]; old daemons drop it. */
    fun createCollaboratorTicket(label: String? = null) {
        collaboratorTicket.value = null; collaboratorTicketCreating.value = true; collaboratorError.value = null
        scope.launch {
            runCatching { send(CreateCollaboratorTicket(label)) }
            delay(8000)
            if (collaboratorTicketCreating.value) {
                collaboratorTicketCreating.value = false
                collaboratorError.value = daemonTooOldText()
            }
        }
    }

    fun removeCollaborator(deviceId: String) = scope.launch { runCatching { send(RemoveCollaborator(deviceId)) } }

    /** Pull this caller's handoffs. An inbox link asks UNFILTERED (§3.2.3 — it has no session to scope by,
     *  and the daemon answers with exactly the offers bound to its device); an ordinary link scopes to the
     *  session on screen, and simply skips when there isn't one. */
    fun listHandoffs() {
        if (isCollaboratorInbox) { refreshHandoffsSilently(); return }
        val sid = sessionKey.value ?: currentSessionId ?: return
        scope.launch { runCatching { send(ListHandoffs(sessionId = sid)) } }
    }

    /** The unfiltered pull, used by the inbox on connect / foreground / reconnect. */
    fun refreshHandoffsSilently() = scope.launch { runCatching { send(ListHandoffs()) } }

    fun cancelHandoff(id: String) = scope.launch { runCatching { send(CancelHandoff(id)) } }
    fun recallHandoff(id: String) = scope.launch { runCatching { send(RecallHandoff(id)) } }
    fun completeHandoff(id: String) = scope.launch { runCatching { send(CompleteHandoff(id)) } }

    /**
     * Accept an offer (§3.2.7). The button stays in its waiting state until the DAEMON says what happened:
     * a compare-and-set the second device loses, an offer that expired a moment ago, and an old daemon that
     * drops the unknown frame entirely must all read as "not accepted", never as a silent success.
     */
    fun acceptHandoff(id: String) {
        if (handoffAccepting.value == id) return // a double-tap is not a second accept
        handoffAccepting.value = id
        handoffAcceptError.value = null
        acceptedHere += id
        scope.launch {
            runCatching { send(AcceptHandoff(id)) }
            delay(ACCEPT_TIMEOUT_MS)
            if (handoffAccepting.value == id) { // no HandoffUpdated/HandoffListing ever arrived
                handoffAccepting.value = null
                handoffAcceptError.value = Res.string.ho_accept_no_reply
            }
        }
    }

    fun declineHandoff(id: String, reason: String? = null) = scope.launch { runCatching { send(DeclineHandoff(id, reason)) } }
    fun returnHandoff(id: String, result: HandoffResult?) = scope.launch { runCatching { send(ReturnHandoff(id, result)) } }

    // ── ReviewRequest (REVIEW-REQUEST.md §12): the Review Center's daemon-backed state ──
    //
    // Every list here is a MIRROR of a daemon snapshot, never a second source of truth. The daemon owns
    // the state machine, the retry, the dedupe and the history; closing this UI for a week changes
    // nothing about any of them (§3.3). So the rules are narrow on purpose:
    //  - a listing REPLACES its list wholesale (that is how a reconnect heals);
    //  - a single-row push UPSERTS, and only when its revision is not older than what we hold;
    //  - nothing here invents a transition, and "queued" is rendered as queued, never as delivered.

    /** Requests THIS machine sent. The sender's row is authoritative, so this is real state. */
    val reviewsSent = mutableStateListOf<ReviewRequest>()

    /** Requests THIS machine received — the daemon's local mirror of each peer's authoritative row,
     *  plus the peer label and the actions still queued toward them. */
    val reviewsReceived = mutableStateListOf<ReviewInboxItem>()

    /** Review contacts, both directions, removed ones included (terminal group like collaborators). */
    val reviewContacts = mutableStateListOf<ReviewContact>()

    /** Set once each listing lands — what distinguishes "you have none" from "the daemon never answered".
     *  Without them an empty Review Center and a broken one look identical. */
    val reviewsSentLoaded = mutableStateOf(false)
    val reviewInboxLoaded = mutableStateOf(false)
    val reviewContactsLoaded = mutableStateOf(false)

    /** The bounded wait elapsed with no reply: an older daemon silently drops an unknown frame, so the UI
     *  must say "update the computer's daemon" instead of spinning forever (§10). */
    val reviewUnsupported = mutableStateOf(false)

    /** The daemon's own words for the last refusal. Cleared by the next successful operation. */
    val reviewError = mutableStateOf<String?>(null)

    val reviewSending = mutableStateOf(false)
    /** The created row, as proof of a send — the id the user can quote back. */
    val reviewLastCreated = mutableStateOf<ReviewRequest?>(null)

    /** requestId of the action/prepare in flight, so a row spinner is per-row rather than global. */
    val reviewActing = mutableStateOf<String?>(null)
    val reviewPreparing = mutableStateOf<String?>(null)

    /** The last prepare bundle. Held so the detail screen can show and copy the daemon's recommended
     *  prompt; the App neither launches an agent nor opens anything from it. */
    val reviewBundle = mutableStateOf<ReviewExecutionBundle?>(null)

    /** The honest answer to the last queued action — `queued=false` means it was already in that state. */
    val reviewLastActed = mutableStateOf<ReviewInboxActed?>(null)

    val reviewInvite = mutableStateOf<String?>(null)
    val reviewInviteTtlSec = mutableStateOf(0)
    val reviewInviteCreating = mutableStateOf(false)
    val reviewJoining = mutableStateOf(false)

    /**
     * A scanned/opened `ccpocket://review-contact#…` waiting for the Review Center's join confirmation
     * (REVIEW-REQUEST.md §13.3) — the Review twin of [pendingCollabInvite], and held as the RAW line
     * because this App never redeems it: the daemon does, which is what keeps reviews arriving with the
     * app closed. Cleared when the join page is left or the join is submitted; a deep link therefore
     * never burns a ticket on sight.
     */
    val pendingReviewInvite = mutableStateOf<String?>(null)

    /** Received requests still waiting on this machine — the badge count. Terminal and already-responded
     *  rows are history, not work. */
    val reviewPendingCount: Int
        get() = reviewsReceived.count { !it.request.status.isTerminal && it.request.status != ReviewStatus.RESPONDED }

    /** Contacts a NEW request may be addressed to. `canSend` is the DAEMON's answer (purpose, direction
     *  and liveness all folded in), so the picker cannot drift from what the send path will accept. */
    fun reviewRecipients(): List<ReviewContact> = reviewContacts.filter { it.canSend }

    /** Replace by id, but never regress: a late replay of an older revision must not undo a newer state. */
    private fun upsertReview(r: ReviewRequest) {
        val i = reviewsSent.indexOfFirst { it.id == r.id }
        if (i >= 0) { if (r.revision >= reviewsSent[i].revision) reviewsSent[i] = r } else reviewsSent.add(0, r)
        // the same row may also be one we RECEIVED (an App attached as a collaborator of the sender):
        // keep that mirror in step under the same revision rule rather than letting the two disagree
        val j = reviewsReceived.indexOfFirst { it.request.id == r.id }
        if (j >= 0 && r.revision >= reviewsReceived[j].request.revision) {
            reviewsReceived[j] = reviewsReceived[j].copy(request = r)
        }
    }

    /**
     * Pull the whole Review Center from the active daemon. Three bounded snapshots rather than a cursor:
     * per-request revisions are not a global sequence (§10), so a complete listing is what a reconnect
     * heals from. Safe to call on open, on ⌘R and after a daemon switch.
     */
    fun refreshReviews() {
        reviewError.value = null
        scope.launch {
            runCatching { send(ListReviewRequests()) }
            runCatching { send(ListReviewInbox()) }
            runCatching { send(ListReviewContacts) }
            delay(REVIEW_REPLY_TIMEOUT_MS)
            // an old daemon drops all three silently; one arriving is enough to prove it understands us
            if (!reviewsSentLoaded.value && !reviewInboxLoaded.value && !reviewContactsLoaded.value) {
                reviewUnsupported.value = true
            }
        }
    }

    /** Just the inbox — what an action's aftermath re-reads, so `pending` comes from the daemon and is
     *  never guessed locally. */
    fun refreshReviewInbox() = scope.launch { runCatching { send(ListReviewInbox()) } }

    fun refreshReviewContacts() = scope.launch { runCatching { send(ListReviewContacts) } }

    /** Mint a one-time REVIEW-peer invite. The URI is establishment material: shown once, never logged. */
    fun createReviewInvite(label: String? = null) {
        reviewInvite.value = null; reviewInviteCreating.value = true; reviewError.value = null
        scope.launch {
            runCatching { send(CreateReviewInvite(label)) }
            delay(REVIEW_REPLY_TIMEOUT_MS)
            if (reviewInviteCreating.value) {
                reviewInviteCreating.value = false
                reviewError.value = daemonTooOldText()
            }
        }
    }

    /**
     * Hand a scanned/pasted invite to the ACTIVE DAEMON to redeem. Deliberately not [redeemCollaboratorInvite]:
     * that one stores a restricted binding on THIS PHONE for Session Handoff. A review peer link has to
     * live on the always-on daemon, or delivery and retry would stop the moment the app is closed.
     */
    fun joinReviewContact(invite: String, label: String? = null) {
        reviewJoining.value = true; reviewError.value = null
        scope.launch {
            runCatching { send(JoinReviewContact(invite, label)) }
            delay(REVIEW_REPLY_TIMEOUT_MS)
            if (reviewJoining.value) {
                reviewJoining.value = false
                reviewError.value = daemonTooOldText()
            }
        }
    }

    fun removeReviewContact(id: String, direction: CollaboratorDirection) {
        reviewError.value = null
        scope.launch { runCatching { send(RemoveReviewContact(id, direction)) } }
    }

    /** Send a review. The daemon validates and is authoritative; the form's own checks are only for
     *  fast feedback. Success proof is the returned row's id/status, not the absence of an error. */
    fun sendReview(
        recipientDeviceId: String,
        title: String,
        brief: ReviewBrief,
        artifacts: List<ArtifactRef>,
        dueAt: Long? = null,
        expiresAt: Long? = null,
    ) {
        reviewSending.value = true; reviewError.value = null; reviewLastCreated.value = null
        scope.launch {
            runCatching { send(CreateReviewRequest(recipientDeviceId, title, brief, artifacts, dueAt, expiresAt)) }
            delay(REVIEW_REPLY_TIMEOUT_MS)
            if (reviewSending.value) {
                reviewSending.value = false
                reviewError.value = daemonTooOldText()
            }
        }
    }

    /** Ask the daemon to build the safe execution bundle. It opens nothing and runs nothing — the
     *  recommended prompt is for the user's OWN agent, on their own machine, under their own policy. */
    fun prepareReview(requestId: String) {
        reviewPreparing.value = requestId; reviewBundle.value = null; reviewError.value = null
        scope.launch {
            runCatching { send(PrepareReviewRequest(requestId)) }
            delay(REVIEW_REPLY_TIMEOUT_MS)
            if (reviewPreparing.value == requestId) {
                reviewPreparing.value = null
                reviewError.value = daemonTooOldText()
            }
        }
    }

    fun clearReviewBundle() { reviewBundle.value = null }

    /** Queue a recipient-side action on the active daemon. The reply says whether it was RECORDED. */
    fun actOnReview(
        requestId: String,
        action: ReviewInboxAction,
        reason: String? = null,
        result: ReviewResult? = null,
    ) {
        if (reviewActing.value == requestId) return // a double-tap is not a second action
        reviewActing.value = requestId; reviewError.value = null; reviewLastActed.value = null
        scope.launch {
            runCatching { send(ActOnReviewInbox(requestId, action, reason, result)) }
            delay(REVIEW_REPLY_TIMEOUT_MS)
            if (reviewActing.value == requestId) {
                reviewActing.value = null
                reviewError.value = daemonTooOldText()
            }
        }
    }

    fun cancelReview(id: String) {
        reviewError.value = null
        scope.launch { runCatching { send(CancelReviewRequest(id)) } }
    }

    fun closeReview(id: String) {
        reviewError.value = null
        scope.launch { runCatching { send(CloseReviewRequest(id)) } }
    }

    /** Drop every review mirror. Called from [disconnect] — a review ledger belongs to ONE machine, and
     *  showing the previous daemon's inbox after a fleet switch would be a lie about whose work it is. */
    private fun clearReviewState() {
        reviewsSent.clear(); reviewsReceived.clear(); reviewContacts.clear()
        reviewsSentLoaded.value = false; reviewInboxLoaded.value = false; reviewContactsLoaded.value = false
        reviewUnsupported.value = false; reviewError.value = null
        reviewSending.value = false; reviewLastCreated.value = null
        reviewActing.value = null; reviewPreparing.value = null
        reviewBundle.value = null; reviewLastActed.value = null
        reviewInvite.value = null; reviewInviteTtlSec.value = 0
        reviewInviteCreating.value = false; reviewJoining.value = false
    }

    /** Guest: redeem a scanned/pasted folder-share invite — the same relay redeem as pairing a computer,
     *  but the daemon scopes this binding to the one shared folder (issue #115). */
    fun redeemShareInvite(invite: ShareInvite) {
        status.value = StatusMsg(Res.string.status_pairing)
        scope.launch { doPair("share") { invite.toPairingInfo() } }
    }

    /**
     * Recipient: redeem a confirmed collaborator connect ticket (§4.1). Same relay redeem as pairing, but
     * everything AFTER it is different, which is why this is not [doPair]:
     *
     *  - the credential the daemon mints is COLLABORATOR-kind — zero session access, an offer inbox;
     *  - it is stored in [Pairing.collaboratorLinks], NOT the computer list, so it never appears as a
     *    machine, never becomes a fleet satellite, and never joins the ⌘K switcher;
     *  - the active account does not move: connecting a colleague must not switch you off your own computer.
     *
     * The resulting link is handed to [onCollaboratorLinkAdded] so the app root can bring its inbox
     * connection up immediately (the offer that prompted the QR is usually already waiting).
     */
    fun redeemCollaboratorInvite(invite: CollaboratorInvite) {
        if (collabRedeeming.value) return
        collabRedeeming.value = true
        collabRedeemError.value = null
        scope.launch {
            val client = HttpClient()
            try {
                val link = Pairing.redeemCollaboratorLink(invite.toCollabPairingInfo(), Pairing.deviceKeys(), client)
                replace(collaboratorLinks, Pairing.collaboratorLinks())
                pendingCollabInvite.value = null
                Telemetry.track(TelEvent.Paired, mapOf(TelKey.Source to "collaborator"))
                onCollaboratorLinkAdded?.invoke(link, invite.ticket)
            } catch (t: Throwable) {
                collabRedeemError.value = t.message ?: t::class.simpleName ?: "error"
                Telemetry.track(TelEvent.PairFailed, mapOf(TelKey.Reason to pairFailReason(t)))
                Telemetry.recordError(t.message ?: "collaborator redeem failed", "pairing")
            } finally {
                collabRedeeming.value = false
                client.close()
            }
        }
    }

    /** Collaborator Links held by this device (observable mirror of [Pairing.collaboratorLinks]). */
    val collaboratorLinks = mutableStateListOf<PairedDaemon>().also { if (pinnedTo == null) it.addAll(Pairing.collaboratorLinks()) }

    /** True while a confirmed collaborator ticket is being redeemed — the confirm screen's waiting state. */
    val collabRedeeming = mutableStateOf(false)
    val collabRedeemError = mutableStateOf<String?>(null)

    /** A scanned/opened `ccpocket://collab#…` waiting for the fingerprint confirm screen (§7): a deep link
     *  or a QR must NEVER redeem on sight — the user reads the safety words and says yes first. */
    val pendingCollabInvite = mutableStateOf<CollaboratorInvite?>(null)

    /** A scanned/opened `ccpocket://share#…` waiting for the guest accept-preview (same rule as above). */
    val pendingShareInvite = mutableStateOf<ShareInvite?>(null)

    /** The offer id a push/deep link asked us to show, if any — consumed by the root incoming entry. */
    val pendingOfferId = mutableStateOf<String?>(null)

    /** Set by the app root: a fresh Collaborator Link (and its one-time ticket, the first connect's PSK)
     *  so the inbox connection for it comes up now rather than at the next app launch. */
    var onCollaboratorLinkAdded: ((PairedDaemon, String) -> Unit)? = null

    /** Set by the app root: the notifications toggle changed. Settings binds to the PRIMARY link, but the
     *  preference is the device's, and a Collaborator Link inbox now holds a push token of its own (§3.4) —
     *  without this fan-out, turning notifications off would leave every contact still able to buzz you. */
    var onNotificationsChanged: ((Boolean) -> Unit)? = null

    /** The one-time ticket doubles as the PSK on a binding's FIRST relay connect — a freshly redeemed inbox
     *  link is constructed after the redeem, so it needs it handed over explicitly. */
    internal fun armFirstTicket(ticket: String?) { firstTicket = ticket }

    /**
     * THE deep-link front door (§7). iOS `onOpenURL`, the Android VIEW intent, the pairing scanner and the
     * Join Folder paste field all come through here, so the routing table lives in exactly one place and a
     * collaborator QR can't be redeemed by whichever entry point happens to see it first.
     *
     * [allowBareBlob] is the explicit-paste opt-in: a naked base64 string is only treated as an invite when
     * a human deliberately pasted it into a field that asks for one.
     *
     * [fromScan] is telemetry-only origin: the camera and a tapped deep link hand over the SAME payload, so
     * only the entry point can tell them apart (issue #278). It changes no routing and no behaviour.
     */
    fun handleIncomingLink(raw: String, allowBareBlob: Boolean = false, fromScan: Boolean = false): IncomingLink {
        val link = parseIncomingLink(raw, allowBareBlob)
        when (link) {
            is IncomingLink.Code -> pairWithCode(link.code, fromScan = fromScan)
            is IncomingLink.Pair -> pair(link.url, fromScan = fromScan)
            // every invite kind parks in a trust screen; none of them redeems here
            is IncomingLink.Collab ->
                // A REVIEW invite does not belong to THIS door at all (REVIEW-REQUEST.md §13.3): it is
                // addressed to a colleague's DAEMON, and its ticket is single use, so redeeming it here
                // would burn it into a phone binding that can never answer a review. The parser already
                // refuses a review ticket at the collab door — this is the SECOND gate, kept because the
                // one that matters is the one standing right before the redeem.
                if (link.invite.purpose == CollaboratorPurpose.REVIEW) {
                    status.value = StatusMsg(Res.string.status_review_invite_wrong_door)
                } else {
                    pendingCollabInvite.value = link.invite
                }
            // …and its own door routes into the Review Center's join confirmation, where the human
            // compares fingerprints before the DAEMON (not this app) redeems anything.
            is IncomingLink.ReviewContact -> pendingReviewInvite.value = link.uri
            is IncomingLink.Share -> pendingShareInvite.value = link.invite
            is IncomingLink.Session -> requestOpenSession(link.workdir, link.sessionId)
            is IncomingLink.Handoff -> pendingOfferId.value = link.handoffId
            // an unroutable payload is the other silent pairing dead end (issue #278): the user scanned or
            // opened SOMETHING and got a red line, which the funnel used to see as no attempt at all.
            // (The review-invite wrong-door branch above is deliberately NOT this: it is a routing correction
            //  on a perfectly valid invite, not a failed pairing.)
            IncomingLink.Unknown -> {
                status.value = StatusMsg(Res.string.status_invalid_link)
                setPairFailure(PairFailure.PARSE)
                Telemetry.track(TelEvent.PairFailed, mapOf(TelKey.Reason to PairFailure.PARSE.wireReason(null)))
            }
        }
        return link
    }

    fun listSessions(wd: String) = scope.launch { send(ListSessions(wd)) }

    /** Fetch the cross-project archive (issue #202) — a multi-project scan on the daemon, so only ever on
     *  an explicit open/refresh, never on a timer. */
    fun listArchivedSessions() = scope.launch {
        archivedRefreshing.value = true
        runCatching { send(ListArchivedSessions) }.onFailure { archivedRefreshing.value = false }
    }

    /**
     * Archive or restore [sessionId] (issue #202). [fromArchiveView] MUST be set when the action originates
     * in the archive screen: without it the daemon answers with `Sessions(workdir)`, which would repoint the
     * client's listed directory to whatever project that row happened to belong to.
     */
    fun setSessionArchived(
        wd: String,
        sessionId: String,
        archived: Boolean,
        fromArchiveView: Boolean = false,
        title: String = "",
        running: Boolean = false,
    ) {
        scope.launch { runCatching { send(SetSessionArchived(wd, sessionId, archived, fromArchiveView)) } }
        if (fromArchiveView) listArchivedSessions() // frames are ordered: the mutation lands before the list
        archiveToast.value = ArchiveToast(wd, sessionId, title, archived, running, epochMillis())
    }

    /** Pull-to-refresh spinner for the sessions list (mirrors [refreshing] for the project list). */
    val sessionsRefreshing = mutableStateOf(false)

    /** Re-scan a project's sessions with the pull-to-refresh spinner ([wd] defaults to the open list's dir). */
    fun refreshSessions(wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        refreshWithSpinner(sessionsRefreshing, ListSessions(dir))
    }

    // Session groups (issue #119). Every mutation targets the currently-listed project ([sessionsDir]); the
    // daemon answers each by re-pushing that dir's Sessions frame, so [sessions]/[sessionGroups] refresh
    // themselves — no optimistic local edit. Guest connections are owner-gated at the daemon (no-op there).
    fun createGroup(name: String, wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        if (name.isBlank()) return
        scope.launch { send(GroupCreate(dir, name.trim())) }
    }
    fun renameGroup(groupId: String, name: String, wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        if (name.isBlank()) return
        scope.launch { send(GroupRename(dir, groupId, name.trim())) }
    }
    fun deleteGroup(groupId: String, wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        scope.launch { send(GroupDelete(dir, groupId)) }
    }
    /** Move [sessionId] into [groupId], or out of any group when [groupId] is null. */
    fun assignGroup(sessionId: String, groupId: String?, wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        scope.launch { send(GroupAssign(dir, sessionId, groupId)) }
    }

    /** Rename session [sessionId]'s title (issue #158) — the daemon lands claude's own `custom-title`
     *  record and answers with the re-pushed [Sessions] (same refresh contract as the group ops; no
     *  optimistic local edit), or a [PocketError] when the rename can't land (e.g. the session is live
     *  in another client). Gate the entry on [renameSupported]. */
    fun renameSession(sessionId: String, title: String, wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        if (title.isBlank()) return
        renameTarget = sessionId // key a rename_failed answer back to the asking row
        renameError.value = null // a fresh attempt clears the previous refusal
        scope.launch { send(RenameSession(dir, sessionId, title.trim())) }
    }

    /** Dismiss the inline rename-refusal feedback (Esc on the sidebar's rename row). */
    fun dismissRenameError() { renameError.value = null }

    // ── session rewind / fork actions (issue #282) ─────────────────────────────────────────────────

    /** May [item] offer the rewind/fork entry at all? Three conditions, none of them a version number:
     *  the row carries transcript coordinates (only a #282-aware daemon replaying a Claude transcript
     *  sends those), the session is Claude, and nothing is in flight — cutting history under a running
     *  turn is exactly what the daemon refuses, so the entry is shown disabled rather than firing a
     *  frame that can only come back as `not_idle`. */
    fun canRewind(item: ChatItem.User): Boolean =
        item.seq != null && item.uuid != null && !item.pending &&
            (sessionAgent.value ?: AgentKind.CLAUDE) == AgentKind.CLAUDE

    /** True when the entry must be shown but greyed out, with the "stop the current turn first" note. */
    fun rewindBlockedByTurn(): Boolean = streaming.value

    /** Step 1 of the gesture: open the confirmation sheet and ask the daemon what the cut would cost.
     *  Never cuts anything — [confirmRewind] is the only path that does. */
    fun startRewind(item: ChatItem.User, mode: String) {
        val seq = item.seq ?: return
        val uuid = item.uuid ?: return
        val convo = convoId.value ?: return
        rewindError.value = null
        rewindSheet.value = RewindSheet(RewindTarget(convo, seq, uuid, item.text, mode))
        scope.launch { send(RewindSession(convo, seq, uuid, mode, dryRun = true)) }
    }

    /** Step 2: the person read the numbers and tapped through. */
    fun confirmRewind() {
        val sheet = rewindSheet.value ?: return
        if (sheet.submitting) return // a double tap must not send two cuts
        val convo = convoId.value ?: return
        rewindSheet.value = sheet.copy(submitting = true)
        val t = sheet.target
        rewindAwaiting = t
        scope.launch { send(RewindSession(convo, t.seq, t.uuid, t.mode, dryRun = false)) }
    }

    /** Dismiss the sheet. A cut already on the wire is NOT cancelled — nothing can recall it — so the
     *  pending answer is left armed and still lands its lineage + prefill. */
    fun cancelRewind() { rewindSheet.value = null }
    /** Whether ([wd], [resumeId]) names the conversation the chat is ALREADY showing (issue #235). Demands a
     *  materialized identity on both sides: a brand-new open (resumeId == null) has none, so it must never
     *  fold into "already open" just because the workdir agrees — that would make ⌘N in the current project
     *  a permanent no-op. [convoId] (not [sessionKey] alone) is what proves a chat is open: sessionKey
     *  deliberately survives [backToBrowse] as the draft key. Workdir compared under the shared [sameDirPath]
     *  identity, so the daemon's absolute cwd and a raw `~/…` request (or a trailing separator) still name
     *  the same directory. */
    private fun alreadyOpen(wd: String, resumeId: String?): Boolean =
        resumeId != null && convoId.value != null && sessionKey.value == resumeId && sameDirPath(workdir.value, wd)

    /** Whether an open for the SAME target is already on the wire (issue #235). Keyed on the target's
     *  identity only — not the whole [OpenAttempt] — because a second click is a duplicate however the row
     *  spells the workdir or whatever title/flags the list happens to carry by then. */
    private fun openInFlightFor(wd: String, resumeId: String?): Boolean =
        openInFlight?.let { it.resumeId == resumeId && sameDirPath(it.wd, wd) } == true

    // startMode defaults to the persisted default mode (mirrors effort), so tapping a session straight from
    // the list applies it too — not just the new-session picker.
    /** @return whether this request was actually sent — false means it was refused as a duplicate (#235). */
    fun openSession(
        wd: String,
        resumeId: String? = null,
        startMode: PermissionMode = defaultMode.value,
        title: String? = null,
        // null means the caller has no backend provenance (old deep link / old daemon). An explicit
        // list-row agent must outrank a stale SessionParams value persisted by a previous App version.
        agent: AgentKind? = null,
        startPermissionMode: String? = defaultPermissionMode.value,
        // issue #199: a model picked in the new-session step, for THIS session only. Null = the usual
        // ladder (an existing session's remembered nullable value; a NEW session's per-agent Settings
        // default). Deliberately not persisted anywhere: the pick is part of creating one session, not a
        // new default.
        startModel: String? = null,
    ): Boolean {
        // Gate the EFFECTIVE agent (the same ladder openAgent resolves below: explicit row value, then the
        // remembered backend, then the default) rather than only the caller's seed. This is synchronous like
        // the idempotence refusals below: unsupported ZCode never clears the current chat, flips opening
        // state or reaches the wire.
        // sessionDefaultAgent (NOT the raw preference): an unsupported persisted default must degrade to
        // a Claude open like it always did, not silently refuse the tap (PR #296 re-review) — the hard
        // supportsAgent refusal below is for EXPLICIT asks the daemon can't serve, not for the fallback.
        val targetAgent = agent ?: resumeId?.let { sessionParams[it]?.agent } ?: sessionDefaultAgent
        if (!supportsAgent(targetAgent)) return false
        val attempt = OpenAttempt(wd, resumeId, startMode, title, agent, startPermissionMode, startModel)
        // #235: the two refusals, both decided SYNCHRONOUSLY — the defect they fix is two clicks landing in
        // the same frame, so any check that only ran inside the coroutine below was already too late.
        //  (a) the same target is in flight: a second OpenSession restarts the very session the first is
        //      about to land (observed: a resume re-Closed+re-Opened ~600ms after it landed, new convo).
        //  (b) it IS the open conversation: the CloseSession+OpenSession pair below would tear down and
        //      rebuild a session the user is looking at, blanking the transcript and the composer.
        // A DIFFERENT target still goes through — latest-wins switching is unchanged (#165), and the losing
        // open's late SessionLive is still refused by the #219 identity guard.
        if (openInFlightFor(wd, resumeId) || alreadyOpen(wd, resumeId)) return false
        sessionNavigationFenced = false // an explicit tap/push is the only way out of #226's browse fence
        openInFlight = attempt
        lastOpenAttempt = attempt
        opening.value = true // held until the daemon answers (SessionLive/PocketError) — 8s net below
        openTimedOut.value = false
        val gen = ++openGen // ties the 8s safety net below to THIS open — a quick second open isn't cleared by the first one's timer
        openJob?.cancel() // a different target supersedes even a worker suspended before dispatch
        val job = scope.launch(start = CoroutineStart.LAZY) { runOpen(attempt, gen) }
        openJob = job
        job.start()
        return true
    }

    /** Re-send the open that just failed (issue #235) — the desktop's open-failed pane's retry. Replays the
     *  SAME request, so a retry can never land under different flags than the click that failed. */
    fun retryOpen(): Boolean {
        val a = lastOpenAttempt ?: return false
        return openSession(a.wd, a.resumeId, a.startMode, a.title, a.agent, a.startPermissionMode, a.startModel)
    }

    /** The state switch + send of one accepted [openSession]. Split out only so the claim above stays
     *  synchronous; everything here runs on [scope] exactly as it always did. */
    private suspend fun runOpen(attempt: OpenAttempt, gen: Int) {
        if (gen != openGen) return // disconnect/demote/back may win before this queued worker gets CPU
        val (wd, resumeId, startMode, title, agent, startPermissionMode, startModel) = attempt
        clearPromptLifecycleState() // every prompt marker/deadline belongs to the previous conversation
        sessionDegraded.value = false; degradedSendArmed = false // per-session — SessionLive re-announces the truth
        abandonVoice() // #266: a capture in flight belongs to the session we're leaving — never carry it into the next
        // Reclaim the current session ONLY if it's idle (or a read-only observe): a RUNNING turn stays
        // alive in the background — same rule as backToBrowse. Desktop switches sessions directly
        // (sidebar click → here, no backToBrowse in between), so an unconditional close was killing
        // the previous session's in-flight work on every switch. Switching back later resumes by
        // sessionId and reattaches the still-live conversation (registry live-match), no fork.
        convoId.value?.let { if (observing.value || !streaming.value) send(CloseSession(it)) }
        if (gen != openGen) return // the close send can suspend while a newer navigation decision wins
        messages.clear(); convoId.value = null; replayEcho = false
        resetHistoryPaging() // #147: a fresh open replays in full — a stale cursor must not ask for a delta
        sessionKey.value = resumeId // durable draft key known immediately on resume; null for a brand-new session
        // #219: a brand-new session's SessionLive has no sessionId to recognize it by — arm the workdir
        // match instead. A resume open disarms any stale marker: its answer is pinned by sessionKey above.
        pendingNewOpenWd = if (resumeId == null) wd else null
        composerEpoch.value++ // a REAL context switch — composers re-init from the target's draft (#29/#88); identity flips don't
        terminalEntries.clear(); terminalBusy.value = false // the quick-terminal scrollback is per-session
        changedFiles.clear(); changedFilesLoading.value = false; closeFileViewer() // changed-files view is per-session too
        clearGitState() // …and so is the Git panel (#280/#281)
        streaming.value = false // the previous session's in-flight turn must not leak the ■ button
        turnStartMark = null // …nor stamp its send time onto this session's TurnEnded duration / stop-refill window
        clearAskQueue()
        limitOffer.value = null; limitConfirmed.value = null // the auto-continue offer belongs to the session that hit the limit (#137)
        chatTitle.value = title // resumed sessions carry their list title; new sessions fill in from the first prompt
        autoFocusComposer.value = resumeId == null // a just-created session opens on an empty composer — pop the keyboard right away
        // restore the session's last-known launch flags: shows the right badge immediately (no default flash)
        // AND relaunches under them if the daemon closed the process while we were away. A live session's
        // reattach SessionLive still wins as the source of truth right after.
        val saved = resumeId?.let { sessionParams[it] }
        // Mode intentionally ignores the session's remembered value: resuming applies the caller's mode
        // (default = the persisted Settings mode), so "continue here" honors what Settings says instead of
        // silently reviving a stale per-session mode (issue #50). Model/effort/agent still restore per-session.
        val openMode = startMode
        val openAgent = agent ?: saved?.agent ?: sessionDefaultAgent // same degrade-to-Claude ladder as the gate above
        val openPermissionMode =
            startPermissionMode?.takeIf { openAgent == AgentKind.CLAUDE && it == CLAUDE_PERMISSION_MODE_AUTO }
        // Each backend seeds from its own persisted default. The compatibility guard is the final defence against
        // an old/corrupt preference crossing agent families; null means that CLI chooses its configured default.
        // a session saved under an older build may carry bare "opus" — that now means Opus 5 on the
        // official endpoint (defaultModelFor applies the same migration to the persisted default)
        val savedModel = saved?.model?.let {
            if (openAgent == AgentKind.CLAUDE && gatewayBaseUrl.value == null) migrateLegacyClaudeModel(it) else it
        }
        // an explicit new-session pick (issue #199) leads: it was made for THIS open, so it outranks both
        // the session's remembered model and the Settings default. Same compatibility guard as the rest.
        val openModel = compatibleModelForAgent(openAgent, startModel)
            ?: compatibleModelForAgent(openAgent, savedModel)
            // #237 adds Codex defaults for NEW sessions only. Every other backend keeps the pre-#237
            // resume behavior: a missing/null local row falls through to its Settings default. In
            // particular, changing that fallback for Claude would be an unrelated behavioral regression.
            ?: if (resumeId == null || openAgent != AgentKind.CODEX) {
                compatibleModelForAgent(openAgent, defaultModelFor(openAgent))
            } else {
                null
        }
        val knownCapabilities = modelCapabilities(openAgent, openModel)
        val knownEfforts = supportedReasoningEfforts(openAgent, openModel)
        // Same boundary for effort. A Codex resume never inherits a newly-selected default, while Claude
        // and the other existing backends retain their historical null/missing-row fallback.
        val requestedEffort = saved?.effort ?: if (resumeId == null || openAgent != AgentKind.CODEX) {
            defaultEffortFor(openAgent)
        } else {
            null
        }
        val openEffort = requestedEffort.takeIf { candidate ->
            candidate == null || knownEfforts == null || candidate in knownEfforts
        }
        val openServiceTier = (saved?.serviceTier ?: defaultServiceTier.value).takeIf { candidate ->
            openAgent == AgentKind.CODEX &&
                (candidate == null || knownCapabilities == null || knownCapabilities.serviceTiers.any { it.id == candidate })
        }
        mode.value = openMode; permissionMode.value = openPermissionMode; allowRules.clear()
        model.value = openModel; effort.value = openEffort; serviceTier.value = openServiceTier; contextUsed.value = null // reconciled by SessionLive
        sessionAgent.value = openAgent // optimistic; SessionLive corrects from daemon truth
        // Pre-fetch OpenCode model list so the picker has it ready when the user opens it,
        // rather than only fetching on picker-open (SessionSheets.kt ModelPicker LaunchedEffect).
        fetchModels(openAgent)
        clearBackgroundJobs()
        Telemetry.track(TelEvent.SessionOpened, mapOf(TelKey.Resume to if (resumeId != null) 1 else 0) + demoTag())
        // lastEventSeq = 0 (never null, via lastEventSeqFor after the reset above): full replay, but it
        // declares this client delta-capable so an observe view tails with deltas (issue #147)
        if (gen != openGen) return
        openDispatchedGen = gen // the matching SessionLive may own the view from here
        send(
            OpenSession(
                wd,
                resumeId,
                model = openModel,
                mode = openMode,
                effort = openEffort,
                agent = openAgent,
                lastEventSeq = lastEventSeqFor(resumeId),
                permissionMode = openPermissionMode,
                serviceTier = openServiceTier,
            ),
        )
        delay(8000) // safety: clear if the daemon never answers (matches `switching`)
        if (gen == openGen && opening.value) {
            openJob = null
            opening.value = false
            // …and release the router too (issue #165): a switch that never landed must fall back to the
            // session list rather than strand the user on a chat that will never fill
            switchingSession.value = false
            openTimedOut.value = true // surfaced as a slim banner instead of the old silent spinner reset (issue #41)
            pendingNewOpenWd = null // #219: the open is dead — a later background announce must not claim it
            openInFlight = null // #235: …and the claim dies with it, so the same row can be clicked again
        }
    }

    fun hasReadyImages() = pendingImages.any { it.state == ImgState.Ready }

    /** Stage picked photos: show each as Compressing, downscale/compress off-thread, then budget them. */
    fun attachImages(raw: List<ByteArray>) {
        val room = MAX_IMAGES - pendingImages.size
        if (room <= 0) return
        raw.take(room).forEach { original ->
            val id = pendingIdSeq++
            pendingImages.add(PendingImage(id, original, ImgState.Compressing))
            scope.launch {
                val compressed = withContext(Dispatchers.Default) { compressImage(original, IMG_MAX_DIM, IMG_MAX_BYTES) }
                val i = pendingImages.indexOfFirst { it.id == id }
                if (i >= 0) {
                    pendingImages[i] = PendingImage(id, compressed, ImgState.Ready)
                    revalidatePending()
                }
            }
        }
    }

    fun removePendingImage(id: Long) {
        pendingImages.removeAll { it.id == id }
        revalidatePending() // freeing budget may let a previously-rejected photo back in
    }

    // ── file uploads into the workspace inbox (issue #90) ────────────────────────────────────

    /** Any staged file still moving? The send button waits (design: spinner) until uploads settle. */
    fun uploadsBusy() = pendingFiles.any { it.state == FileUpState.Uploading || it.state == FileUpState.Queued }

    fun hasLandedFiles() = pendingFiles.any { it.state == FileUpState.Landed && it.path != null }

    /** Stage picked files: over-cap picks fail immediately (nothing to stream); the rest queue and
     *  upload ONE at a time — chunks of a 200 MB file must not starve asks/heartbeats on the socket. */
    fun attachFiles(picked: List<PickedFile>) {
        val room = MAX_FILES - pendingFiles.size
        picked.take(room.coerceAtLeast(0)).forEach { p ->
            val id = pendingIdSeq++
            val tooBig = p.size > MAX_UPLOAD_BYTES || p.bytes.size > MAX_UPLOAD_BYTES
            val unreadable = !tooBig && p.bytes.isEmpty() && p.size > 0 // picker couldn't load it
            pendingFiles.add(
                when {
                    tooBig -> PendingFile(id, p.name, p.size, ByteArray(0), p.mediaType, FileUpState.Failed, error = "larger than 200 MB")
                    unreadable -> PendingFile(id, p.name, p.size, ByteArray(0), p.mediaType, FileUpState.Failed, error = "couldn't read the file")
                    else -> PendingFile(id, p.name, p.bytes.size.toLong(), p.bytes, p.mediaType, FileUpState.Queued, localUri = p.localUri)
                },
            )
        }
        pumpFileUploads()
    }

    fun removePendingFile(id: Long) {
        val i = pendingFiles.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = pendingFiles[i]
        if (p.state == FileUpState.Uploading) {
            fileUploadJob?.cancel()
            fileAckDeadline?.cancel()
            val c = convoId.value
            val cap = p.captureId
            if (c != null && cap != null) scope.launch { runCatching { send(FileUploadCancel(c, cap)) } }
        }
        pendingFiles.removeAt(i)
        pumpFileUploads()
    }

    fun retryPendingFile(id: Long) {
        val i = pendingFiles.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = pendingFiles[i]
        if (p.state != FileUpState.Failed) return
        if (p.bytes.isEmpty() && p.size > 0) return // over-cap pick — nothing retained to re-stream
        pendingFiles[i] = p.copy(state = FileUpState.Queued, error = null, progress = 0f, captureId = null)
        pumpFileUploads()
    }

    /** Start the next queued upload if none is in flight. Chunks the RAW bytes and base64s each
     *  chunk independently — the daemon streams chunks to disk as they arrive, so every chunk must
     *  decode on its own (unlike audio, which re-joins the base64 string before decoding once). */
    @OptIn(ExperimentalEncodingApi::class)
    private fun pumpFileUploads() {
        if (pendingFiles.any { it.state == FileUpState.Uploading }) return
        val i = pendingFiles.indexOfFirst { it.state == FileUpState.Queued }
        if (i < 0) return
        val f = pendingFiles[i]
        val c = convoId.value
        if (c == null) {
            pendingFiles[i] = f.copy(state = FileUpState.Failed, error = "no live session")
            return
        }
        val capId = randomCaptureId()
        pendingFiles[i] = f.copy(state = FileUpState.Uploading, captureId = capId, progress = 0f)
        fileUploadJob = scope.launch {
            val total = f.bytes.size
            val parts = fileChunkParts(total)
            try {
                for (idx in 0 until parts) {
                    val from = idx * FILE_CHUNK_RAW
                    val to = minOf(from + FILE_CHUNK_RAW, total)
                    val b64 = if (to > from) Base64.Default.encode(f.bytes.copyOfRange(from, to)) else ""
                    send(
                        FileChunk(
                            c, capId, idx, last = idx == parts - 1,
                            name = f.name, mediaType = f.mediaType, base64 = b64,
                            totalBytes = if (idx == 0) total.toLong() else 0,
                        ),
                    )
                    updateFile(capId) { it.copy(progress = (idx + 1).toFloat() / parts) }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failFileUpload(capId, "connection lost — retry")
                return@launch
            }
            armFileAckDeadline(capId)
        }
    }

    /** Last chunk sent → the [FileUploaded] receipt must arrive. An old daemon can't decode the
     *  chunk frames and silently drops them (the wire's forward-compat contract) — this deadline is
     *  the ONLY thing distinguishing that from success, same idea as the changed-files guard. */
    private fun armFileAckDeadline(capId: String) {
        fileAckDeadline?.cancel()
        fileAckDeadline = scope.launch {
            delay(UPLOAD_ACK_TIMEOUT_MS)
            failFileUpload(capId, "no reply from the computer — its cc-pocket may be too old for file upload")
        }
    }

    private fun updateFile(capId: String, f: (PendingFile) -> PendingFile) {
        val i = pendingFiles.indexOfFirst { it.captureId == capId }
        if (i >= 0) pendingFiles[i] = f(pendingFiles[i])
    }

    private fun failFileUpload(capId: String, error: String) {
        val i = pendingFiles.indexOfFirst { it.captureId == capId && it.state == FileUpState.Uploading }
        if (i < 0) return
        pendingFiles[i] = pendingFiles[i].copy(state = FileUpState.Failed, error = error)
        pumpFileUploads()
    }

    private fun onFileUploaded(f: FileUploaded) {
        val i = pendingFiles.indexOfFirst { it.captureId == f.captureId }
        if (i < 0) return // superseded / cancelled / already consumed by a send
        fileAckDeadline?.cancel()
        val p = pendingFiles[i]
        pendingFiles[i] = if (f.ok && f.path != null) {
            // bytes are on the computer's disk now — drop our copy (a 200 MB hold matters on a phone)
            p.copy(state = FileUpState.Landed, progress = 1f, path = f.path, landedName = f.name ?: p.name, bytes = ByteArray(0))
        } else {
            p.copy(state = FileUpState.Failed, error = f.error ?: "upload failed")
        }
        pumpFileUploads()
    }

    /** Session teardown/switch: kill the loop + receipts and drop staged files (their landed copies
     *  stay in the old session's inbox — harmless, swept by nothing on purpose: the user sent none). */
    private fun clearFileUploads() {
        fileUploadJob?.cancel(); fileUploadJob = null
        fileAckDeadline?.cancel(); fileAckDeadline = null
        pendingFiles.clear()
    }

    /** Mark photos Rejected once their cumulative base64 would exceed [IMAGE_BUDGET_B64] (one frame holds them all). */
    private fun revalidatePending() {
        var used = 0
        for (i in pendingImages.indices) {
            val img = pendingImages[i]
            if (img.state == ImgState.Compressing) continue
            val cost = base64Len(img.bytes.size)
            val ok = used + cost <= IMAGE_BUDGET_B64
            if (ok) used += cost
            val next = if (ok) ImgState.Ready else ImgState.Rejected
            if (img.state != next) pendingImages[i] = PendingImage(img.id, img.bytes, next)
        }
    }

    /** Send [text] as a user turn. Returns false when nothing was sent — blank input, no conversation,
     *  or the degraded-session gate (issue #65): the first send into a session whose recent turns were
     *  all API failures is intercepted with an explanation (each such send just bloats the transcript);
     *  sending again goes through. Callers keep the composer text on false. */
    @OptIn(ExperimentalEncodingApi::class)
    fun sendPrompt(text: String): Boolean {
        val c = convoId.value ?: return false
        if (uploadsBusy()) return false // send waits for uploads to settle (the button shows the spinner)
        val ready = pendingImages.filter { it.state == ImgState.Ready }.map { it.bytes }
        val landed = pendingFiles.filter { it.state == FileUpState.Landed && it.path != null }
        if (text.isBlank() && ready.isEmpty() && landed.isEmpty()) return false
        // slash commands bypass the gate — /clear and /compact are exactly how a dead session heals
        if (sessionDegraded.value && !degradedSendArmed && !text.trimStart().startsWith("/")) {
            degradedSendArmed = true
            messages.add(
                ChatItem.Sys(
                    "this session looks stuck past its context limit — recent replies were API-failure placeholders. " +
                        "Send again to try anyway, or start a new session / send /clear.",
                ),
            )
            return false
        }
        degradedSendArmed = false // consumed — the next prompt into a still-degraded session gates again
        if (voice.value is VoiceState.Failed) clearVoice() // sending dismisses the error chip
        val images = ready.map { ImageData("image/jpeg", Base64.Default.encode(it)) }
        // landed files ride as `@path` references appended to the prompt — the #75 mechanism, so the
        // agent Reads the inbox file by path; the daemon never re-parses anything upload-specific
        val refs = landed.joinToString("\n") { "@${it.path}" }
        val outText = when {
            landed.isEmpty() -> text
            text.isBlank() -> refs
            else -> text + "\n\n" + refs
        }
        val sentFiles = landed.map { SentFile(it.landedName ?: it.name, it.size, it.path!!, mediaType = it.mediaType, localUri = it.localUri) }
        val promptId = newPromptId()
        // Every send starts a new status epoch. A receipt/turn deadline from the previous prompt may have
        // fired while the app was suspended; carrying those booleans forward makes the fresh bubble say
        // "not delivered", "queued", or "no response" before its own deadline has even started.
        sendStalled.value = false
        clearTurnWatchdogState()
        messages.add(ChatItem.User(text, ready, pending = true, promptId = promptId, files = sentFiles))
        promptPending = true
        activePromptId = promptId
        turnStartMark = kotlin.time.TimeSource.Monotonic.markNow()
        if (chatTitle.value == null && text.isNotBlank()) {
            chatTitle.value = text.take(48) // new session: first prompt becomes the header title
            // …and the working-set row's label: a brand-new session had no title when SessionLive landed,
            // so without this it would sit in the switcher under its bare project name forever (#165)
            rememberOpenedSession(workdir.value, sessionKey.value, chatTitle.value, sessionAgent.value)
        }
        pendingImages.clear()
        pendingFiles.clear() // landed refs consumed; failed leftovers clear with the send
        promptQueued = streaming.value // a send into a running turn gets QUEUED by the CLI — flavors the ack→turn watchdog
        streaming.value = true
        limitOffer.value = null; limitConfirmed.value = null // a manual send supersedes the auto-continue offer (#137)
        promptRetry = workdir.value?.let { PromptRetry(outText, images, it, promptId) }
        promptResendArmed = false
        Telemetry.track(TelEvent.PromptSent, demoTag())
        scope.launch { send(SendPrompt(c, outText, images, promptId = promptId)) }
        armPromptWatchdog()
        return true
    }

    /** Bound the wait for a delivery receipt (issue #78): outboxes deliberately buffer across reconnects,
     *  so a prompt sent into a link that stopped answering would otherwise stay "sending…" forever — the
     *  failure mode multi-computer fleets hit constantly, because machine switches and daemon socket cycles
     *  leave links that look connected but whose E2E session the daemon no longer holds. On the deadline:
     *  surface the stall under the bubble, and — only when the phase still CLAIMS Ready, i.e. no other
     *  recovery is running — force one re-handshake. The queued frames re-flush on the fresh session, and
     *  the daemon dedupes the promptId if the original actually landed, so this can't double-run the turn. */
    private fun armPromptWatchdog() {
        val promptId = activePromptId
        promptWatchdog?.cancel()
        promptWatchdog = scope.launch {
            delay(promptReceiptTimeoutMs)
            if (!promptPending || activePromptId != promptId) return@launch
            sendStalled.value = true
            // non-Ready phases already have the retry/backoff machinery (and the UI banner) on the case
            if (!demoMode.value && sessionActive.value && phase.value == ConnPhase.Ready) launchTransport(reconnect = true)
        }
    }

    /** Legacy second-stage watchdog (issue #104): a delivered prompt that produces no turn frame within the deadline
     *  was swallowed by a wedged / mid-relaunch agent. Surface an inline resend cue instead of spinning forever.
     *  Self-guarded at fire time — a turn frame ([awaitingTurn] cleared), a session change ([convoId] moved off
     *  the one captured here), or a turn that already ended ([streaming] false) all no-op it, so it never fires
     *  into a stale conversation and no teardown path has to reach in and cancel it.
     *  [queued] flips the fired state: a prompt sent mid-turn waits in the CLI's queue, where the same silence
     *  is expected — it gets the calm [turnQueued] status, never the resend cue (see [turnQueued] for why). */
    private fun armTurnWatchdog(queued: Boolean, promptId: String) {
        val c = convoId.value
        turnWatchdog?.cancel()
        turnWatchdog = scope.launch {
            delay(promptTurnTimeoutMs)
            if (!awaitingTurn || activePromptId != promptId || convoId.value != c || !streaming.value) return@launch
            if (queued) {
                turnQueued.value = true
                Telemetry.track(TelEvent.PromptTurnQueued, mapOf(TelKey.Phase to phase.value.name))
                return@launch
            }
            turnStalled.value = true
            // this fires ONLY after a PromptAck, so it inherently means "daemon delivered, agent produced
            // nothing" (candidate 3) — distinct from the no-ack stall (issue #78). Phase tags the link state.
            Telemetry.track(TelEvent.PromptTurnStalled, mapOf(TelKey.Phase to phase.value.name))
        }
    }

    /** User tapped the "no response — resend" cue (issue #104). Re-drive the stalled turn under a FRESH
     *  promptId: the live daemon Conversation already recorded the original id, so a same-id resend would be
     *  deduped (#66) into a bare re-ack and never run. Single-shot — guarded on [turnStalled], which a real
     *  turn frame retracts first, so a resend can't land on top of a turn that actually started. No second
     *  User bubble is added (that duplicate "You" is the very symptom #104 is about); we just re-run the turn. */
    fun resendStalledPrompt() {
        if (!turnStalled.value) return
        val c = convoId.value ?: run { turnStalled.value = false; return }
        val retry = promptRetry ?: run { turnStalled.value = false; return }
        if (activePromptId != retry.promptId) { clearTurnWatchdogState(); return }
        turnStalled.value = false
        clearTurnWatchdogState()
        val freshId = newPromptId()
        promptRetry = PromptRetry(retry.text, retry.images, retry.workdir, freshId)
        activePromptId = freshId
        promptResendArmed = false
        promptPending = true // pending until the re-driven turn shows life
        promptQueued = false // the stalled turn never started — the re-driven copy expects the strict deadline
        streaming.value = true
        Telemetry.track(TelEvent.PromptResent)
        scope.launch { send(SendPrompt(c, retry.text, retry.images, promptId = freshId)) }
        armPromptWatchdog() // the resent copy gets its own receipt deadline; its ack re-arms the turn watchdog
    }

    /** Client-minted id a [dev.ccpocket.protocol.PromptAck] echoes back (issue #66) — random hex is
     *  plenty: uniqueness only matters within one conversation's recent sends. */
    private fun newPromptId(): String =
        Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Quick-terminal (issue #3): run one shell command in the session's cwd. The daemon gates approval and
     *  replies with a single [ShellResult]; one command is in flight at a time. */
    fun runShell(command: String) {
        val cmd = command.trim()
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        if (cmd.isEmpty() || terminalBusy.value) return
        terminalEntries.add(TerminalEntry(cmd))
        terminalBusy.value = true
        Telemetry.track(TelEvent.PromptSent, demoTag())
        scope.launch { send(RunShellCommand(c, cmd, wd)) }
    }

    /** Clear the quick-terminal scrollback for the active session. */
    fun clearTerminal() = terminalEntries.clear()

    // A daemon that predates issue #36 silently DROPS these frames (unknown type — by design), so both
    // requests carry a client-side deadline: better an honest "update the daemon" than an eternal spinner.
    private var changedFilesDeadline: Job? = null
    private var viewedFileDeadline: Job? = null
    private var exportDeadline: Job? = null // separate: approval can take the daemon's whole 30s window
    private val fileChunks = FileChunkAssembler() // reassembles a chunked ReadFile reply (issue #134)

    /** Drop the in-flight chunk stream AND its published progress together — every abandon path
     *  (fresh read, whole-frame supersede, stall, closed viewer) must clear both, or the next
     *  loading card would open on a stale determinate bar. */
    private fun dropChunkStream() {
        fileChunks.reset()
        viewedFileProgress.value = null
    }

    /** Ask the daemon for the files this session touched (issue #36); the reply lands in [changedFiles].
     *  Needs the persistent sessionId — pre-first-turn sessions have nothing to list anyway. */
    fun fetchChangedFiles() {
        val wd = workdir.value ?: return
        val sid = sessionKey.value ?: currentSessionId ?: return
        changedFilesLoading.value = true
        changedFilesUnavailable.value = false
        changedFilesDeadline?.cancel()
        changedFilesDeadline = scope.launch {
            delay(8_000)
            if (changedFilesLoading.value) { changedFilesLoading.value = false; changedFilesUnavailable.value = true }
        }
        scope.launch { send(ListSessionFiles(wd, sid, sessionAgent.value ?: AgentKind.CLAUDE)) }
    }

    /** Open one project file in the viewer (changed-files list, @-completion, typed path — issue #133);
     *  the daemon replies with a capped [FileContent] (or a [FileContentChunk] stream for over-cap
     *  binaries, issue #134) and, when its transcript has line-level data, a [FileDiff] — both requested
     *  up front because the viewer's default tab is the diff and the flip to full content should be
     *  instant. Images get no [ReadFileDiff]: there is no text diff, and the request would cost the
     *  daemon a full transcript re-scan just to say so. */
    fun openChangedFile(path: String) {
        val wd = workdir.value ?: return
        val sid = sessionKey.value ?: currentSessionId ?: return
        val wantDiff = !isImageFile(path)
        viewedFilePath.value = path
        viewedFile.value = null // show the loading state, not the previous file
        viewedFileDiff.value = null
        dropChunkStream() // a fresh read owes nothing to a prior chunk stream
        exportWaiting.value = false; exportDeadline?.cancel() // a fresh file owes nothing to a prior export
        armViewedFileDeadline(path, wd, sid, wantDiff)
        val agent = sessionAgent.value ?: AgentKind.CLAUDE
        scope.launch {
            send(ReadFile(wd, sid, path, agent, allowChunks = true)) // we can reassemble chunked binaries (issue #134)
            if (wantDiff) send(ReadFileDiff(wd, sid, path, agent))
        }
    }

    /** ONE deadline arms both viewer replies; each check no-ops once its reply landed, so a daemon that
     *  answers ReadFile but predates ReadFileDiff still gets the honest "needs a newer daemon" state.
     *  Re-armed on every [FileContentChunk] so a long transfer isn't misread as silence — [ms] then
     *  bounds the INTER-chunk gap, not the whole stream. */
    private fun armViewedFileDeadline(path: String, wd: String, sid: String, wantDiff: Boolean, ms: Long = 8_000) {
        viewedFileDeadline?.cancel()
        viewedFileDeadline = scope.launch {
            delay(ms)
            if (viewedFilePath.value != path) return@launch
            if (viewedFile.value == null) {
                dropChunkStream() // a stalled chunk stream is dead — don't let a late stray revive it
                viewedFile.value = FileContent(wd, sid, path, ok = false, error = "no reply from the computer — the daemon may be too old for this")
            }
            if (wantDiff && viewedFileDiff.value == null) {
                viewedFileDiff.value = FileDiff(wd, sid, path, ok = false, error = DIFF_ERROR_STALE_DAEMON)
            }
        }
    }

    // ── Git panel (#280) / worktrees (#281) ─────────────────────────────────────────────────────
    // Same shape as the changed-files pair above: a NEW frame family an old daemon drops, so every
    // request arms a reply deadline and settles into the "update the computer" state instead of a
    // spinner that never ends. The two-step verbs keep their ORIGINAL request around rather than
    // rebuilding one from the preview — the confirm must execute exactly what was previewed.
    private var gitStatusDeadline: Job? = null
    private var gitDiffDeadline: Job? = null
    private var gitActionDeadline: Job? = null
    private var worktreesDeadline: Job? = null
    private var gitPendingAction: GitAction? = null
    private var gitPendingRemove: RemoveWorktree? = null
    private var pendingWorktreeAddBranch: String? = null      // names the branch on the post-create receipt

    /** Read the whole repository state for the Git tab. [withBranches] rides along when the branch
     *  sheet is about to open, so it costs one round trip instead of two. */
    fun fetchGitStatus(withBranches: Boolean = false) {
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        gitStatusLoading.value = true
        gitStatusUnavailable.value = false
        gitStatusDeadline?.cancel()
        gitStatusDeadline = scope.launch {
            delay(GIT_REPLY_DEADLINE_MS)
            if (gitStatusLoading.value) { gitStatusLoading.value = false; gitStatusUnavailable.value = true }
        }
        scope.launch { send(FetchGitStatus(c, wd, withBranches)) }
    }

    /** Open ONE path's diff on one side (Screen B). Flipping the Working|Staged control re-asks: the
     *  two sides are two different truths and the daemon owns both. */
    fun openGitDiff(path: String, staged: Boolean = false) {
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        gitDiffPath.value = path
        gitDiffStaged.value = staged
        gitDiff.value = null
        gitDiffDeadline?.cancel()
        gitDiffDeadline = scope.launch {
            delay(GIT_REPLY_DEADLINE_MS)
            if (gitDiffPath.value == path && gitDiff.value == null) {
                gitDiff.value = GitDiff(c, wd, path, staged, ok = false, error = DIFF_ERROR_STALE_DAEMON)
            }
        }
        scope.launch { send(ReadGitDiff(c, wd, path, staged)) }
    }

    fun closeGitDiff() {
        gitDiffDeadline?.cancel()
        gitDiffPath.value = null; gitDiff.value = null; gitDiffStaged.value = false
    }

    /**
     * Run one verb from the closed allow-list. A [GIT_TWO_STEP_OPS] verb sent WITHOUT a token comes
     * back as a [GitActionPreview] (→ [gitPendingConfirm], the confirm sheet); [confirmPendingGit]
     * re-sends this exact frame with the token. An op outside [GIT_OPS] never leaves the app — the
     * daemon rejects it by name too, this is only so a typo can't reach the wire.
     */
    fun gitAct(
        op: String,
        paths: List<String> = emptyList(),
        message: String? = null,
        branch: String? = null,
        confirmToken: String? = null,
    ) {
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        if (op !in GIT_OPS) return
        val frame = GitAction(c, wd, op, paths, message, branch, confirmToken)
        if (confirmToken == null && op in GIT_TWO_STEP_OPS) gitPendingAction = frame
        armGitAction(op)
        scope.launch { send(frame) }
    }

    /** List every checkout of the repository the open session sits in (#281 Screen A). */
    fun fetchWorktrees(withStatus: Boolean = true) {
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        worktreesLoading.value = true
        worktreesUnavailable.value = false
        worktreesDeadline?.cancel()
        worktreesDeadline = scope.launch {
            delay(GIT_REPLY_DEADLINE_MS)
            if (worktreesLoading.value) { worktreesLoading.value = false; worktreesUnavailable.value = true }
        }
        scope.launch { send(ListWorktrees(c, wd, withStatus)) }
    }

    /** `git worktree add` — L1, one tap: it writes a new directory and touches no existing data. The
     *  destination is the daemon's own `<repoRoot>-worktrees/<slug>` policy, so no path crosses here. */
    fun addWorktree(branch: String, createBranch: Boolean) {
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        val name = branch.trim()
        if (name.isEmpty()) return
        pendingWorktreeAddBranch = name
        armGitAction(GIT_OP_WORKTREE_ADD)
        scope.launch { send(AddWorktree(c, wd, name, createBranch)) }
    }

    /** `git worktree remove` — two-step on the SAME token machinery as revert/dirty-checkout. */
    fun removeWorktree(path: String, confirmToken: String? = null) {
        val c = convoId.value ?: return
        val wd = workdir.value ?: return
        val frame = RemoveWorktree(c, wd, path, confirmToken)
        if (confirmToken == null) gitPendingRemove = frame
        armGitAction(GIT_OP_WORKTREE_REMOVE)
        scope.launch { send(frame) }
    }

    /** Redeem the preview's token by re-sending the request it previewed, verbatim plus the token. A
     *  [GitActionPreview.blocked] preview is never redeemable (a live session in the worktree) — the
     *  sheet renders an inert button, and this refuses too so a stray call can't route around it. */
    fun confirmPendingGit() {
        val preview = gitPendingConfirm.value ?: return
        gitPendingConfirm.value = null
        if (preview.blocked) return
        when (preview.op) {
            GIT_OP_WORKTREE_REMOVE -> {
                val req = gitPendingRemove ?: return
                gitPendingRemove = null
                removeWorktree(req.path, preview.confirmToken)
            }
            else -> {
                val req = gitPendingAction ?: return
                gitPendingAction = null
                gitAct(req.op, req.paths, req.message, req.branch, preview.confirmToken)
            }
        }
    }

    /** Cancel a confirm sheet — the token simply expires unredeemed on the daemon. */
    fun dismissGitConfirm() {
        gitPendingConfirm.value = null
        gitPendingAction = null; gitPendingRemove = null
        gitBusyOp.value = null
    }

    /** Close the A5/A6 error strip (× ). Purely local: nothing about the repository changed. */
    fun dismissGitError() { gitError.value = null }

    /** Close the post-create receipt. Nothing is undone — the worktree stays. */
    fun dismissWorktreeCreated() { worktreeCreated.value = null }

    /** Close the fetch receipt (×). Same nothing-changed promise as [dismissGitError]. */
    fun dismissGitFetchNote() { gitFetchNote.value = null }

    /** One in-flight verb drives the spinner in the button that started it; the deadline turns a
     *  dropped frame into the same "update the computer" sentence the reads use, in the strip. */
    private fun armGitAction(op: String) {
        gitBusyOp.value = op
        gitError.value = null
        gitFetchNote.value = null // a receipt for the PREVIOUS verb must not survive into this one
        gitActionDeadline?.cancel()
        gitActionDeadline = scope.launch {
            delay(GIT_REPLY_DEADLINE_MS)
            if (gitBusyOp.value == op) {
                gitBusyOp.value = null
                gitPendingAction = null; gitPendingRemove = null
                gitError.value = GitActionResult(convoId.value ?: "", op, ok = false, error = GIT_ERROR_STALE_DAEMON)
                gitStatusUnavailable.value = gitStatus.value == null
            }
        }
    }

    /** Per-session, exactly like the changed-files view: leaving a chat must not carry another
     *  repository's status, diff or half-finished confirmation into the next one. */
    private fun clearGitState() {
        gitStatusDeadline?.cancel(); gitDiffDeadline?.cancel()
        gitActionDeadline?.cancel(); worktreesDeadline?.cancel()
        gitStatus.value = null; gitStatusLoading.value = false; gitStatusUnavailable.value = false
        gitBusyOp.value = null; gitError.value = null; gitFetchNote.value = null
        gitPendingConfirm.value = null; gitPendingAction = null; gitPendingRemove = null
        worktrees.value = null; worktreesLoading.value = false; worktreesUnavailable.value = false
        worktreeCreated.value = null; pendingWorktreeAddBranch = null
        closeGitDiff()
    }

    fun closeFileViewer() {
        viewedFileDeadline?.cancel()
        exportDeadline?.cancel(); exportWaiting.value = false
        dropChunkStream()
        viewedFilePath.value = null; viewedFile.value = null; viewedFileDiff.value = null
    }

    /** Ask the daemon to export the viewer's current path even though this session never changed it
     *  (issue #67 v2 / #79 — Bash/script-generated files the changed-set firewall refuses). The daemon
     *  serves changed files straight away and gates everything else behind the owner's approval card
     *  (the same PermissionAsk sheet as Bash); the reply is a [FileContent] for the same identity, so
     *  it lands in [viewedFile] like any read — served, or ok=false carrying the refusal/denial reason.
     *  The deadline outlasts the daemon's 30s approval window; an old daemon drops the unknown frame
     *  and this lands in the honest "update the daemon" state instead of spinning forever. */
    fun requestExport() {
        val path = viewedFilePath.value ?: return
        val wd = workdir.value ?: return
        val sid = sessionKey.value ?: currentSessionId ?: return
        val cid = convoId.value ?: return
        exportWaiting.value = true
        exportDeadline?.cancel()
        exportDeadline = scope.launch {
            delay(45_000)
            if (viewedFilePath.value == path && exportWaiting.value) {
                exportWaiting.value = false
                viewedFile.value = FileContent(wd, sid, path, ok = false, error = "no reply from the computer — the daemon may be too old for this")
            }
        }
        scope.launch { send(ExportFile(cid, wd, sid, path, sessionAgent.value ?: AgentKind.CLAUDE)) }
    }

    /** Ask the daemon for the children under the open session's cwd + [subPath] (relative, daemon-native
     *  separators) for the composer's @-file completion (issue #75). The reply lands in [pathListing];
     *  the completer only uses it once its subPath matches what it asked for. No-op with no open workdir. */
    fun browseFiles(subPath: String) {
        val wd = workdir.value ?: return
        scope.launch { send(ListPathEntries(wd, subPath)) }
    }

    /** Ask the daemon for the children under [anchor] + [subPath] ('/'-joined, "" = the anchor itself) for
     *  the "open a project folder" browser (issue #152, #176). [anchor] is the literal "~" (the daemon
     *  home — only it knows the remote machine's home) or, once the #176 root switcher picks one, a
     *  filesystem root ("/", "C:\") the daemon reported; its NIO resolve accepts '/'-keyed subPaths on
     *  Windows too. The reply lands in [browseListing] only while it answers the LATEST request — a stale
     *  reply from a drilled-past level is dropped at fold time (#152 复核), and the picker additionally
     *  keys rendering on its own (anchor, subPath). A guest credential gets a PocketError instead
     *  (GuestGuard denies the "~" anchor and any out-of-scope root), which the picker never sees — the
     *  entry is owner-only client-side and the daemon stays the authority. */
    fun browseDirs(anchor: String, subPath: String) {
        lastBrowseAnchor = anchor
        lastBrowseSub = subPath
        scope.launch { send(ListPathEntries(anchor, subPath)) }
    }

    // ── voice input actions ───────────────────────────────────────────────

    /** Mic tap (S1). Picks the engine: iOS native streaming dictation, else record→daemon-whisper. */
    fun startVoice() {
        if (convoId.value == null) return
        if (voice.value !is VoiceState.Idle && voice.value !is VoiceState.Failed) return
        clearNotice()
        voiceLevels.clear()
        if (NativeDictation.available && !preferRemote && !voiceWhisper.value) startNativeVoice() else startRemoteVoice()
    }

    /** ✓ done (S2 → S3). */
    fun stopVoice() {
        if (voice.value !is VoiceState.Recording) return
        voiceTicker?.cancel()
        levelsJob?.cancel()
        voice.value = VoiceState.Transcribing
        if (usingNative) {
            scope.launch { NativeDictation.stop() } // Final lands via the dictation collector
            startVoiceTimeout(NATIVE_FINAL_TIMEOUT_MS)
        } else {
            scope.launch {
                // #266: a thrown stop() (mic stolen, route change, engine error) is a real FAILURE and must
                // be retryable — collapsing it into "no speech" told the user they stayed silent. Only an
                // empty successful capture is genuine silence.
                val result = runCatching { recorder.stop() }
                val audio = result.getOrNull()
                when {
                    result.isFailure -> voice.value = VoiceState.Failed(Res.string.voice_record_failed)
                    audio == null || audio.bytes.isEmpty() -> {
                        showNotice(Res.string.voice_no_speech)
                        clearVoice()
                    }
                    else -> {
                        keptAudio = audio
                        uploadCapture(audio)
                    }
                }
            }
        }
    }

    /** ✕ cancel (S2/S3) — discard everything, back to the idle composer. */
    fun cancelVoice() = stopCapture(notifyDaemon = true)

    /** Tear down capture jobs + engine and reset to Idle; [notifyDaemon] also aborts an in-flight remote transcription. */
    private fun stopCapture(notifyDaemon: Boolean) {
        voiceTicker?.cancel(); levelsJob?.cancel(); voiceTimeout?.cancel(); dictationJob?.cancel()
        voiceStartJob?.cancel(); interruptJob?.cancel() // #266: cancel a start still in its async window, and the interruption watch
        when (voice.value) {
            is VoiceState.Recording -> if (usingNative) NativeDictation.cancel() else recorder.cancel()
            is VoiceState.Transcribing -> {
                if (usingNative) NativeDictation.cancel()
                if (notifyDaemon) {
                    val id = captureId
                    val c = convoId.value
                    if (id != null && c != null) scope.launch { runCatching { send(AudioCancel(c, id)) } }
                }
            }
            else -> {}
        }
        clearVoice()
    }

    /** S5 retry mic: re-send the kept audio without re-recording; else record again (remote engine after a native failure). */
    fun retryVoice() {
        val kept = keptAudio
        if (kept != null) {
            voice.value = VoiceState.Transcribing
            uploadCapture(kept)
        } else {
            voice.value = VoiceState.Idle
            startVoice()
        }
    }

    fun dismissMicSheet() { micPermissionSheet.value = false }

    /** ✓ = capture confirmed: the transcript LANDS IN THE COMPOSER for the user to review/edit before
     *  sending (issue #221). Recognition results can be wrong, and auto-sending them wasted a model turn
     *  and could fire a bad instruction at the agent — so the result no longer sends itself; the user
     *  sends explicitly. Blank = "no speech". Any staged images stay staged and ride the eventual send. */
    private fun deliverTranscript(text: String) {
        val t = text.trim()
        if (t.isBlank()) {
            showNotice(Res.string.voice_no_speech)
            clearVoice()
            return
        }
        clearVoice()
        pendingVoiceText.value = t // App.kt appends it to the composer and takes focus; the user sends
    }

    private fun startNativeVoice() {
        usingNative = true
        liveDictation.value = true
        beginTicker()
        dictationJob = scope.launch {
            try {
                NativeDictation.start().collect { ev ->
                    when (ev) {
                        is DictationEvent.Partial -> { liveFinal.value = ev.final; livePartial.value = ev.partial }
                        is DictationEvent.Level -> pushLevel(ev.level)
                        is DictationEvent.Final -> onNativeFinal(ev.text)
                        is DictationEvent.Error -> onNativeError(dictationRes(ev.kind), ev.message)
                    }
                }
            } catch (_: VoicePermissionDenied) {
                voiceTicker?.cancel()
                clearVoice()
                micPermissionSheet.value = true
            } catch (_: CancellationException) {
                // cancelVoice() already cleaned up
            } catch (t: Throwable) {
                onNativeError(Res.string.voice_dictation_failed, t.message)
            }
        }
    }

    private fun startRemoteVoice() {
        usingNative = false
        // Claim Recording synchronously (issue #266): recorder.start() suspends across the permission
        // dialog + prepare(), and until it returns voice.value would otherwise stay Idle — a window in
        // which a second Mic tap passes startVoice()'s guard (double-recording) and an abandonVoice() is a
        // silent no-op (the recorder then starts AFTER the user left and runs to the cap). Both are closed
        // by owning the state now and tracking the start job so teardown can cancel mid-start.
        voice.value = VoiceState.Recording(0)
        voiceStartJob = scope.launch {
            try {
                recorder.start()
            } catch (c: CancellationException) {
                runCatching { recorder.cancel() } // abandoned during the start window — tear the recorder down
                throw c
            } catch (_: VoicePermissionDenied) {
                voice.value = VoiceState.Idle
                micPermissionSheet.value = true
                return@launch
            } catch (t: Throwable) {
                voice.value = VoiceState.Failed(Res.string.voice_record_failed)
                return@launch
            }
            beginTicker()
            levelsJob = scope.launch { recorder.levels.collect { pushLevel(it) } }
            // A system interruption (incoming call, another app steals the mic) tears the recorder down
            // natively; without this the ticker keeps counting over audio that is no longer being captured
            // and ✓ later reports "no speech". Surface it as an explicit, retryable failure instead.
            interruptJob = scope.launch {
                recorder.interruptions.collect {
                    if (voice.value is VoiceState.Recording) {
                        voiceTicker?.cancel(); levelsJob?.cancel()
                        voice.value = VoiceState.Failed(Res.string.voice_interrupted)
                    }
                }
            }
        }
    }

    private fun beginTicker() {
        voice.value = VoiceState.Recording(0)
        voiceTicker?.cancel()
        voiceTicker = scope.launch {
            var elapsed = 0L
            while (true) {
                delay(200)
                elapsed += 200
                if (voice.value !is VoiceState.Recording) break
                voice.value = VoiceState.Recording(elapsed)
                if (elapsed >= VOICE_MAX_MS) { stopVoice(); break } // cap reached = same as tapping ✓
            }
        }
    }

    private fun onNativeFinal(text: String) {
        voiceTimeout?.cancel()
        deliverTranscript(text)
    }

    private fun onNativeError(res: StringResource, detail: String?) {
        if (voice.value is VoiceState.Idle) return // teardown noise after completion
        voiceTicker?.cancel(); voiceTimeout?.cancel()
        preferRemote = true // this device's native engine is flaky — retry path uses the daemon
        voice.value = VoiceState.Failed(res, detail)
        liveFinal.value = ""; livePartial.value = ""
    }

    private fun dictationRes(kind: DictationFail): StringResource = when (kind) {
        DictationFail.UNAVAILABLE -> Res.string.voice_speech_unavailable
        DictationFail.AUDIO_ENGINE -> Res.string.voice_audio_engine
        DictationFail.RECOGNITION -> Res.string.voice_dictation_failed
    }

    /** Base64 the whole capture once, slice the STRING into frame-sized chunks (daemon re-joins then decodes). */
    @OptIn(ExperimentalEncodingApi::class)
    private fun uploadCapture(audio: RecordedAudio) {
        val c = convoId.value ?: run { clearVoice(); return }
        val id = randomCaptureId()
        captureId = id
        scope.launch {
            val parts = Base64.Default.encode(audio.bytes).chunked(AUDIO_CHUNK_B64)
            try {
                parts.forEachIndexed { i, p ->
                    send(AudioChunk(c, id, i, last = i == parts.lastIndex, mediaType = audio.mediaType, base64 = p))
                }
            } catch (t: Throwable) {
                voice.value = VoiceState.Failed(Res.string.voice_daemon_unreachable)
                return@launch
            }
            startVoiceTimeout(TRANSCRIBE_TIMEOUT_MS)
        }
    }

    private fun onTranscript(f: Transcript) {
        // #266: bind to the conversation too, not just captureId. A capture dictated in session A whose
        // transcript arrives after the user jumped to session B must never land in B's composer — captureId
        // alone let it through because it isn't reset on session switch.
        if (f.captureId != captureId || f.convoId != convoId.value) return // a superseded/cancelled/foreign capture
        voiceTimeout?.cancel()
        if (voice.value !is VoiceState.Transcribing) return
        if (f.ok) {
            deliverTranscript(f.text)
        } else {
            voice.value = VoiceState.Failed(Res.string.voice_transcribe_failed, f.error)
        }
    }

    private fun startVoiceTimeout(ms: Long) {
        voiceTimeout?.cancel()
        voiceTimeout = scope.launch {
            delay(ms)
            if (voice.value is VoiceState.Transcribing) {
                voice.value = VoiceState.Failed(Res.string.voice_no_response)
            }
        }
    }

    private fun pushLevel(l: Float) {
        voiceLevels.add(l.coerceIn(0f, 1f))
        while (voiceLevels.size > LEVEL_WINDOW) voiceLevels.removeAt(0)
    }

    private fun showNotice(msg: StringResource) {
        voiceNotice.value = msg
        noticeJob?.cancel()
        noticeJob = scope.launch { delay(2500); voiceNotice.value = null }
    }

    private fun clearNotice() { noticeJob?.cancel(); voiceNotice.value = null }

    /** Reset all composer voice state (keeps [preferRemote] — it describes the device, not the session). */
    private fun clearVoice() {
        voiceStartJob?.cancel(); interruptJob?.cancel() // #266: no reset path may leave the start/interruption watchers live
        voice.value = VoiceState.Idle
        liveDictation.value = false
        liveFinal.value = ""
        livePartial.value = ""
        keptAudio = null
        captureId = null
        usingNative = false
    }

    private fun randomCaptureId(): String =
        Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Pop the next queued ask into [pendingAsk] (or clear it) after the current card reached a terminal
     *  state. Ends the burst — resetting the "n / m" chip — once the queue drains. */
    private fun advanceAsk() {
        askBurstDone++
        pendingAsk.value?.let { askRisk.remove(ApprovalKey(it.convoId, it.askId)) } // the badge dies with its card
        val next = askQueue.removeFirstOrNull()
        pendingAsk.value = next
        if (next == null) {
            askBurstTotal = 0; askBurstDone = 0
            askQueueProgress.value = null
        } else {
            updateAskProgress()
            Telemetry.track(TelEvent.ApprovalShown, mapOf(TelKey.Tool to next.tool))
        }
    }

    private fun updateAskProgress() {
        askQueueProgress.value = if (askBurstTotal > 1) (askBurstDone + 1) to askBurstTotal else null
    }

    /** Drop the whole ask queue with the current card — session switch / machine switch / reset paths. */
    private fun clearAskQueue() {
        pendingAsk.value = null
        askQueue.clear()
        askBurstTotal = 0; askBurstDone = 0
        askQueueProgress.value = null
        askRisk.clear()
    }

    fun resolve(
        decision: Decision, remember: Boolean = false, message: String? = null,
        // approval design M2 (only sent for asks whose grantOptions offered them):
        grantScope: String? = null,          // "task" | "session" — 允许本任务 / Session 记忆
        retrySafer: Boolean = false,         // 换种安全方式 (rides a DENY)
        constraints: List<String>? = null,
    ) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        advanceAsk()
        pendingApprovals.remove(ApprovalKey(a.convoId, a.askId))
        if (decision == Decision.ALLOW && (remember || grantScope == "session")) a.rule?.let { r ->
            if (r !in allowRules) allowRules.add(r)
            messages.add(ChatItem.RuleChip(r)) // drop the "always allowing X" chip into the stream
        }
        val decisionLabel = when {
            retrySafer -> "retry-safer"
            grantScope != null -> "allow-$grantScope"
            remember -> "always"
            else -> decision.name.lowercase()
        }
        Telemetry.track(TelEvent.ApprovalDecided, mapOf(TelKey.Decision to decisionLabel))
        scope.launch {
            send(
                PermissionVerdict(
                    c, a.askId, decision, message = message, remember = remember,
                    grantScope = grantScope, retrySafer = retrySafer, constraints = constraints,
                ),
            )
        }
    }

    /** M2 AttentionLease: the visible approval card's read-time heartbeat (30s cadence from the UI).
     *  Only for asks a grant-aware daemon opted in via [PermissionAsk.grantOptions] — an old daemon never
     *  sees the frame. Pauses ONLY the reading budget; the daemon's absolute deadline still rules. */
    fun sendAskHeartbeat(visible: Boolean) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        if (a.grantOptions == null) return
        scope.launch { send(ApprovalAttentionHeartbeat(c, a.askId, visible)) }
    }

    /** "收紧后续授权" on an autorun chip: revoke the task grant (or clear the session rule) so the NEXT
     *  matching action asks again. Never claims to roll back what already ran. */
    fun tightenAutoRun(item: ChatItem.AutoRun) {
        val c = convoId.value ?: return
        if (item.tightening || item.tightened) return
        val requestId = "gm-${randomCaptureId()}"
        val pending = PendingGrantMutation(
            convoId = c,
            eventId = item.eventId,
            rule = item.summary.takeIf { item.grantId == null },
        )
        pendingGrantMutations[requestId] = pending
        updateAutoRun(item.eventId) { it.copy(tightening = true) }
        val frame: Frame = if (item.grantId != null) RevokeGrant(c, item.grantId, requestId)
        else ClearAllowRule(c, item.summary, requestId) // session-rule hits carry no grant id
        sendGrantMutation(requestId, pending, frame)
    }

    private fun updateAutoRun(eventId: String, transform: (ChatItem.AutoRun) -> ChatItem.AutoRun) {
        val index = messages.indexOfFirst { it is ChatItem.AutoRun && it.eventId == eventId }
        val item = messages.getOrNull(index) as? ChatItem.AutoRun ?: return
        messages[index] = transform(item)
    }

    private fun sendGrantMutation(requestId: String, pending: PendingGrantMutation, frame: Frame) {
        scope.launch {
            send(frame)
            delay(GRANT_MUTATION_ACK_MS)
            if (pendingGrantMutations.remove(requestId) == pending) {
                pending.eventId?.let { eventId -> updateAutoRun(eventId) { it.copy(tightening = false) } }
            }
        }
    }

    /** Resolve any account-wide inbox row directly, without opening or attaching its conversation. */
    fun resolvePendingApproval(convoId: String?, askId: String, allow: Boolean) {
        // P1-3: exact composite removal; a caller that (legacy) only knows the askId falls back to the
        // first row carrying it — single-session behavior, unchanged
        val key = if (convoId != null) ApprovalKey(convoId, askId)
        else pendingApprovals.keys.firstOrNull { it.askId == askId } ?: return
        val row = pendingApprovals.remove(key) ?: return
        if (pendingAsk.value?.let { ApprovalKey(it.convoId, it.askId) } == key) {
            advanceAsk()
        } else if (askQueue.removeAll { it.convoId == key.convoId && it.askId == key.askId }) { // resolved from the inbox while queued
            askBurstTotal--
            updateAskProgress()
        }
        Telemetry.track(TelEvent.ApprovalDecided, mapOf(TelKey.Decision to if (allow) "allow" else "deny"))
        scope.launch {
            send(PermissionVerdict(row.ask.convoId, askId, if (allow) Decision.ALLOW else Decision.DENY))
        }
    }

    /** Pull the source-of-truth queue. Safe against an old daemon: it drops the additive unknown frame. */
    fun refreshPendingApprovals() {
        if (!demoMode.value && sessionActive.value && phase.value == ConnPhase.Ready) {
            scope.launch { send(ListPendingApprovals) }
        }
    }

    /** Answer an AskUserQuestion prompt: the picks (question text → label/comma-joined labels/"Other…" text)
     *  and/or a freeform [response] ride the ALLOW verdict; the daemon merges them into claude's updatedInput. */
    fun answerQuestions(answers: Map<String, String>?, response: String? = null) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        advanceAsk()
        val items = response?.takeIf { it.isNotBlank() }?.let { listOf("" to it.trim()) }
            ?: a.questions.orEmpty().mapNotNull { q -> answers?.get(q.question)?.takeIf { it.isNotBlank() }?.let { q.question to it } }
        messages.add(ChatItem.QuestionsAnswered(items))
        Telemetry.track(TelEvent.ApprovalDecided, mapOf(TelKey.Decision to "answered"))
        scope.launch { send(PermissionVerdict(c, a.askId, Decision.ALLOW, answers = answers, response = response)) }
    }

    /** Timeout: the daemon already auto-denied; clear the prompt without re-sending, show the next queued ask. */
    fun dismissAsk() { if (pendingAsk.value != null) advanceAsk() }

    /** Switch the execution/permission mode — applied on the next turn (issue #84), never interrupting a
     *  running turn (the daemon relaunches Claude before the next send; Codex carries it in-turn). */
    fun switchMode(m: PermissionMode, nativeMode: String? = null) {
        val c = convoId.value ?: return
        val normalizedNative =
            nativeMode?.takeIf {
                (sessionAgent.value ?: AgentKind.CLAUDE) == AgentKind.CLAUDE &&
                    it == CLAUDE_PERMISSION_MODE_AUTO
            }
        if (m == mode.value && normalizedNative == permissionMode.value) return
        mode.value = m
        permissionMode.value = normalizedNative
        switching.value = true
        scope.launch {
            send(SwitchMode(c, m, permissionMode = normalizedNative))
            delay(8000); switching.value = false // safety: clear if the daemon never re-announces
        }
    }

    /** Switch the model — routed through the daemon's `/model` interception; applied on the next
     *  turn (issue #84), never interrupting a running one. For OpenCode sessions, rejects models
     *  that lack a provider prefix (e.g. "deepseek-chat", "sonnet") — those are Claude/gateway ids
     *  and would cause silent launch hangs in the opencode backend. */
    fun switchModel(name: String) {
        val target = name.trim()
        if (convoId.value == null || target.isEmpty() || target == model.value) return
        // Keep model ids scoped to the active agent. OpenCode requires provider/model, Codex uses
        // Codex-shaped ids, and Claude remains permissive for gateway custom ids.
        if (!isModelCompatibleWithAgent(sessionAgent.value ?: AgentKind.CLAUDE, target)) return
        model.value = target // optimistic; the daemon's next SessionLive corrects it to the resolved id
        switchViaCommand("/model $target")
        // Reasoning and Fast are model capabilities, not global Codex switches. Clear a choice the
        // target model explicitly does not advertise before the next real turn can be rejected.
        val activeAgent = sessionAgent.value ?: AgentKind.CLAUDE
        val caps = modelCapabilities(activeAgent, target)
        val supportedEfforts = supportedReasoningEfforts(activeAgent, target)
        if (supportedEfforts != null && effort.value != null && effort.value !in supportedEfforts) switchEffort(null)
        if (caps != null) {
            if (serviceTier.value != null && caps.serviceTiers.none { it.id == serviceTier.value }) switchServiceTier(null)
        }
    }

    /** Switch reasoning effort — routed through the daemon's `/effort` interception; applied on the next
     *  turn (issue #84), never interrupting a running one. */
    fun switchEffort(level: String?) {
        val target = level?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (convoId.value == null || target == effort.value) return
        effort.value = target // optimistic; the daemon's next SessionLive corrects it
        switchViaCommand("/effort ${target ?: "default"}")
    }

    fun switchServiceTier(tier: String?) {
        val c = convoId.value ?: return
        val target = tier?.trim()?.takeIf { it.isNotEmpty() }
        if (target == serviceTier.value) return
        serviceTier.value = target
        switching.value = true
        scope.launch {
            send(SwitchServiceTier(c, target))
            delay(8000); switching.value = false
        }
    }

    /** Send a daemon-intercepted relaunch command and hold the "switching" affordance until the next SessionLive. */
    private fun switchViaCommand(command: String) {
        val c = convoId.value ?: return
        switching.value = true
        scope.launch {
            send(SendPrompt(c, command))
            delay(8000); switching.value = false // safety: clear if the daemon never re-announces
        }
    }

    /** Clear the conversation — the daemon starts a fresh session (keeps model/effort/mode) and wipes history. */
    fun clearConversation() {
        val c = convoId.value ?: return
        clearPromptLifecycleState()
        messages.clear(); chatTitle.value = null; contextUsed.value = null
        resetHistoryPaging() // #147: the wiped transcript's cursor dies with it
        clearBackgroundJobs()
        scope.launch { send(SendPrompt(c, "/clear")) }
    }

    /** True when `/simplify` is an available command in this workdir (gates the quick-action row). */
    fun hasSimplify(): Boolean = slashCommands.any { it.name == "simplify" }

    fun clearRule(rule: String) {
        val c = convoId.value ?: return
        val requestId = "gm-${randomCaptureId()}"
        val pending = PendingGrantMutation(convoId = c, rule = rule)
        pendingGrantMutations[requestId] = pending
        sendGrantMutation(requestId, pending, ClearAllowRule(c, rule, requestId))
    }

    fun clearAllRules() {
        val c = convoId.value ?: return
        val requestId = "gm-${randomCaptureId()}"
        val pending = PendingGrantMutation(convoId = c, clearAll = true)
        pendingGrantMutations[requestId] = pending
        sendGrantMutation(requestId, pending, ClearAllowRule(c, null, requestId))
    }

    fun switchDir(wd: String) {
        val c = convoId.value ?: return
        scope.launch { send(SwitchDirectory(c, wd)) }
    }

    /** Interrupt the current turn (composer ■): the session stays alive, generation stops. */
    fun cancelTurn() {
        val c = convoId.value ?: return
        scope.launch { send(CancelTurn(c)) }
    }

    /** Stop one background job from the task panel (issue #80): the daemon interrupts the agent's work for
     *  this session and settles the job's row killed. UI-guarded by a confirm — a real build is costly to lose. */
    fun stopBackgroundJob(jobId: String) {
        val c = convoId.value ?: return
        scope.launch { send(StopBackgroundJob(c, jobId)) }
    }

    fun backToBrowse() {
        fenceSessionNavigation()
        val c = convoId.value
        val dir = sessionsDir.value // non-null = we land on the session list: re-pull it so the rows reflect this session's run
        // observing or idle -> reclaim; still executing -> leave it running in the background.
        // One coroutine for both sends: the re-list must see the close, not race it.
        val closeConvo = c?.takeIf { observing.value || !streaming.value }
        scope.launch {
            closeConvo?.let { send(CloseSession(it)) }
            dir?.let { send(ListSessions(it)) }
        }
        clearPromptLifecycleState()
        convoId.value = null
        chatTitle.value = null
        messages.clear()
        resetHistoryPaging() // #147
        pendingImages.clear()
        clearFileUploads()
        clearBackgroundJobs()
        observing.value = false
        abandonVoice()
    }

    /**
     * Jump from this chat straight into another project's session (issue #165) — the switcher's whole point
     * is that this costs one tap instead of a walk back out to the project list and in again.
     *
     * [openSession] already reclaims-or-leaves the outgoing conversation on exactly the rule [backToBrowse]
     * uses (a still-running turn keeps running in the background), so the only extra work here is pointing
     * the BACK stack at the session we're landing on: without it, backing out of a switched-into session
     * would drop the user at the project they came FROM, which reads as the switch having been undone.
     * The caller saves the outgoing draft first — it owns the composer text, same as the back button.
     */
    fun switchToSession(item: SessionSwitcherItem) {
        if (item.current) return // the row is on screen already; a tap that reopens it would just flash
        // hold the chat on screen across the round trip — see [switchingSession]. Only when we're actually
        // IN a chat: switching from the project list should keep showing the list, as it always has.
        // Preserve an existing hold on a duplicate tap: after the first open worker runs, convoId is null,
        // but the original in-flight transition still owns the chat route until it lands/fails/times out.
        switchingSession.value = switchingSession.value || convoId.value != null
        sessionsDir.value = item.dirKey
        listSessions(item.dirKey) // freshen that project's list so the back trip doesn't show the old one's
        // Optimistic touch so the sheet re-orders under the tap. The daemon's SessionLive re-touches with
        // the authoritative id right after (a fork or lock-heal can hand back a different one), so a
        // wrong guess self-corrects instead of sticking in the MRU.
        rememberOpenedSession(item.dirKey, item.sessionId, item.title, item.agent, markSeen = false)
        openSession(item.dirKey, item.sessionId, title = item.title, agent = item.agent)
    }

    /** Leaving the chat or losing the connection invalidates any in-flight capture. */
    private fun abandonVoice() {
        stopCapture(notifyDaemon = false) // the session is going away — an AudioCancel would be moot
        micPermissionSheet.value = false
        clearNotice()
    }

    /** Take over an observed (terminal-running) session: stop the read-only tail, resume a controllable process. */
    fun takeOver() {
        val obs = convoId.value
        val sid = currentSessionId ?: return
        val wd = workdir.value ?: return
        scope.launch {
            obs?.let { send(CloseSession(it)) }
            clearPromptLifecycleState()
            messages.clear(); convoId.value = null; observing.value = false
            resetHistoryPaging() // #147: the take-over open replays in full
            // "Continue here" resumes under the Settings default mode — omitting it fell back to the
            // wire default (ask each step), ignoring the user's chosen mode (issue #50). Model/effort
            // still restore per-session, same as openSession.
            val saved = sessionParams[sid]
            val agent = saved?.agent ?: sessionAgent.value ?: AgentKind.CLAUDE
            // A session saved under an older build may carry bare "opus" — resume it through the same legacy
            // migration openSession applies (Opus 5 on the official endpoint), or "Continue here" relaunches
            // on Opus 4.8. Migrate BEFORE the compatibility guard, exactly as openSession does.
            val savedModel = saved?.model?.let {
                if (agent == AgentKind.CLAUDE && gatewayBaseUrl.value == null) migrateLegacyClaudeModel(it) else it
            }
            val takeoverModel = compatibleModelForAgent(agent, savedModel)
            val requestedEffort = saved?.effort ?: if (agent == AgentKind.CODEX) null else defaultEffortFor(agent)
            val supportedEfforts = supportedReasoningEfforts(agent, takeoverModel)
            val takeoverEffort = requestedEffort.takeIf { candidate ->
                candidate == null || supportedEfforts == null || candidate in supportedEfforts
            }
            mode.value = defaultMode.value
            permissionMode.value = defaultPermissionMode.value.takeIf { agent == AgentKind.CLAUDE }
            serviceTier.value = (saved?.serviceTier ?: defaultServiceTier.value).takeIf { agent == AgentKind.CODEX }
            send(
                OpenSession(
                    wd,
                    sid,
                    model = takeoverModel,
                    mode = defaultMode.value,
                    // A Codex takeover is an existing session and must not inherit #237's new-session
                    // default. Other backends keep their established fallback to the Settings effort.
                    effort = takeoverEffort,
                    takeOver = true,
                    agent = agent,
                    lastEventSeq = lastEventSeqFor(sid),
                    permissionMode = permissionMode.value,
                    serviceTier = serviceTier.value,
                ),
            )
        }
    }

    /** Explicitly end the session now (force-reclaim the claude process), even if it is still running. */
    fun stopSession() {
        fenceSessionNavigation()
        convoId.value?.let { c -> scope.launch { send(CloseSession(c)) } }
        streaming.value = false
        clearPromptLifecycleState()
        convoId.value = null
        chatTitle.value = null
        messages.clear()
        resetHistoryPaging() // #147
        pendingImages.clear()
        clearFileUploads()
        clearBackgroundJobs()
        abandonVoice()
    }

    fun backToDirectories() {
        // "All projects" is reachable from inside a live chat (the switcher sheet, App.kt onAllProjects).
        // Leaving a chat here must tear it down exactly like BACK does — and, load-bearing, null convoId:
        // the #226 fence this raises makes acceptsSessionLive reject every reattach/reemit while fenced, so
        // a chat left bound (convoId != null) freezes its live state and dies on the next reconnect until
        // the user backs all the way out and reopens. Delegate the chat teardown, then drop to directories.
        if (convoId.value != null) backToBrowse()
        fenceSessionNavigation()
        sessionsDir.value = null
        sessions.clear()
    }

    /** Raise the #226 browse fence and abandon an open transition the user explicitly backed out of. */
    private fun fenceSessionNavigation() {
        sessionNavigationFenced = true
        if (openInFlight != null || opening.value || switchingSession.value) {
            openGen++ // invalidates the abandoned request's 8s safety-net coroutine
            openJob?.cancel(); openJob = null
            openInFlight = null
            lastOpenAttempt = null
            pendingNewOpenWd = null
            opening.value = false
            switchingSession.value = false
            openTimedOut.value = false
        }
    }

    internal companion object {
        /** The folder browser's workdir anchor (issue #152): the literal "~" the daemon expands to ITS
         *  home. Also the [PathEntries] routing key that separates browser replies from @-completion
         *  ones — a real session's workdir is never the bare "~" (SessionLive carries the resolved path). */
        const val BROWSE_HOME = "~"

        /** #152 复核 (pure, for tests): fold a home-browse [PathEntries] reply into the held browseListing.
         *  Replies can arrive out of order over the relay (drill fast → a drilled-past level's slow reply
         *  lands AFTER the current level's), so only the reply answering the LATEST request ([lastSub]) is
         *  accepted; a stale one is dropped. Letting it clobber the fresh listing would strand the picker
         *  on the loading skeleton forever — browseRows keys on subPath and no request is pending to
         *  repair it. */
        fun foldBrowseReply(held: PathEntries?, reply: PathEntries, lastSub: String?): PathEntries? =
            if (reply.subPath == lastSub) reply else held
        const val FIRST_GRACE_MS = 2_000L     // first connect: show the skeleton this long before "can't reach server"
        const val RECONNECT_GRACE_MS = 6_000L // a reconnect already keeps the old list under a banner
        const val RECONNECT_BANNER_GRACE_MS = 2_500L // hold the Ready look this long on a blip before the Reconnecting banner (#28)
        const val LIST_WAIT_MS = 6_000L       // after Attached, wait this long for the list before "computer offline"
        const val CONNECT_TIMEOUT_MS = 12_000L // no Attached within this → treat the connect as wedged, force a retry
        /** §3.2.7: the Accept button waits this long for daemon truth before it says so — an old daemon
         *  DROPS the unknown accept frame entirely, and a permanent spinner would read as success. */
        const val ACCEPT_TIMEOUT_MS = 12_000L

        /** How long a Review Center call waits for daemon truth before it says "update the daemon"
         *  (REVIEW-REQUEST.md §10). Every pocket/review.* frame is a NEW discriminator an older daemon
         *  silently drops, so the alternative to a bounded wait is an infinite spinner. Longer than the
         *  accept window because a mint or a join round-trips the relay, not just the local process. */
        const val REVIEW_REPLY_TIMEOUT_MS = 15_000L
        const val SOCKET_RETIRE_TIMEOUT_MS = 3_000L // #142: bounded wait for the old socket to really close before dialing anew
        const val TRANSPORT_COALESCE_MS = 3_000L    // #143: reconnect triggers within this of an in-flight attempt merge into it
        const val STABLE_LINK_RESET_MS = 60_000L    // #144: the retry ladder resets only after the link stays up this long
        const val GRANT_MUTATION_ACK_MS = 12_000L   // no daemon ack = keep local authorization state unchanged

        /** #143 (pure, for tests): should this reconnect trigger merge into the attempt already in flight?
         *  Only non-forced RECONNECT triggers coalesce, and only while an attempt is actually running and
         *  recent — a long-lived healthy connectJob (isActive for the socket's whole life) must not absorb
         *  a deliberate later teardown, which is what the time window is for. */
        fun shouldCoalesceReconnect(force: Boolean, reconnect: Boolean, attemptInFlight: Boolean, sinceLastLaunchMs: Long): Boolean =
            !force && reconnect && attemptInFlight && sinceLastLaunchMs < TRANSPORT_COALESCE_MS
        const val DIRECT_RETRY_COOLDOWN_MS = 60_000L // after a failed direct probe, stay on the relay this long before re-probing
        const val MAX_IMAGES = 4
        const val IMG_MAX_DIM = 1024 // longest side, true 1× pixels
        const val IMG_MAX_BYTES = 90_000 // per-image compression target (~120 KB base64); lets ~2 share a frame
        // all attached photos ride in ONE SendPrompt frame; keep their combined base64 under MAX_FRAME (256 KiB)
        // minus headroom for the JSON wrapper, the prompt text, and E2E framing overhead
        const val IMAGE_BUDGET_B64 = 240_000
        fun base64Len(rawBytes: Int) = 4 * ((rawBytes + 2) / 3)

        const val LEVEL_WINDOW = 48                  // rolling waveform samples (~4 s at 12 Hz)
        const val TRANSCRIBE_TIMEOUT_MS = 15_000L    // upload → Transcript round-trip guard
        const val NATIVE_FINAL_TIMEOUT_MS = 8_000L   // native engine: stop() → Final guard

        // file uploads (issue #90)
        const val MAX_FILES = 6                      // staged at once — matches the chip strip's comfortable width
        // Raw bytes per FileChunk. 768 000 raw → exactly 1 024 000 base64 chars (multiple of 3: no
        // mid-stream padding), ~1.0 MiB per wire frame after the JSON envelope + Noise AEAD tag —
        // a 4× margin under the relay's 4 MiB frame cap, big enough that a 200 MB file is ~274
        // frames, small enough that asks/heartbeats interleave between chunks on the shared socket.
        const val FILE_CHUNK_RAW = 768_000
        const val UPLOAD_ACK_TIMEOUT_MS = 20_000L    // last chunk → FileUploaded receipt guard ("update the daemon" state)

        /** Chunks a raw file of [total] bytes into ceil(total / [FILE_CHUNK_RAW]) frames, floored at 1 (an
         *  empty file still sends one terminal chunk carrying `last=true`). Extracted so the large-file
         *  boundary — a 200 MB video is 274 frames, and an exact multiple must NOT emit a trailing empty
         *  chunk — stays unit-testable (issues #90/#98). */
        fun fileChunkParts(total: Int): Int = ((total + FILE_CHUNK_RAW - 1) / FILE_CHUNK_RAW).coerceAtLeast(1)

        const val K_NOTIFY = "notify_on_complete"    // SecureStore flag: "0" = task-complete push off (default on)
        // the platform tag ("apns"/"apns_sandbox"/"fcm") of the last token registered — kept so a later
        // "notifications off" can still send the clearing register on a launch that never got a token
        const val K_PUSH_PLATFORM = "push_platform_last"
        const val K_DEFAULT_MODE = "default_session_mode" // SecureStore: PermissionMode.name seeding new sessions (default DEFAULT)
        const val K_DEFAULT_PERMISSION_MODE = "default_session_permission_mode" // backend-native mode (`auto`), "" = legacy mode
        const val K_DEFAULT_EFFORT = "default_session_effort" // SecureStore: effort level for new sessions ("" = model default)
        const val K_DEFAULT_CODEX_EFFORT = "default_session_effort_codex" // SecureStore: Codex-only effort; never overwrites Claude
        const val K_DEFAULT_OPENCODE_EFFORT = "default_session_effort_opencode" // SecureStore: OpenCode-only effort
        const val K_DEFAULT_KIMI_EFFORT = "default_session_effort_kimi" // SecureStore: Kimi-only effort
        const val K_DEFAULT_ZCODE_EFFORT = "default_session_effort_zcode" // SecureStore: ZCode-only thought level
        const val K_DEFAULT_DSH_EFFORT = "default_session_effort_dsh" // SecureStore: DSH-only effort (issue #255)
        const val K_DEFAULT_SERVICE_TIER = "default_session_service_tier" // Codex `priority` = Fast; "" = account default
        const val K_DEFAULT_MODEL = "default_session_model"   // SecureStore: Claude model id for new sessions ("" = CLI default)
        const val K_DEFAULT_CODEX_MODEL = "default_session_model_codex" // SecureStore: Codex model id for new sessions
        const val K_DEFAULT_OPENCODE_MODEL = "default_session_model_opencode" // SecureStore: OpenCode provider/model id for new sessions
        const val K_DEFAULT_KIMI_MODEL = "default_session_model_kimi" // SecureStore: Kimi model alias for new sessions (issue #206)
        const val K_DEFAULT_ZCODE_MODEL = "default_session_model_zcode" // SecureStore: ZCode model id for new sessions (issue #228)
        const val K_DEFAULT_DSH_MODEL = "default_session_model_dsh" // SecureStore: DSH model id — reserved; v1 has no model switching (issue #255)
        private val LEGACY_EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh", "max")
        const val K_CONTEXT_WINDOW_OVERRIDE = "context_window_override" // SecureStore: LEGACY global statusline denominator in tokens ("" = follow derived window); now the fallback tier under K_CONTEXT_WINDOW_OVERRIDES
        const val K_CONTEXT_WINDOW_OVERRIDES = "context_window_overrides" // SecureStore: TSV modelId\ttokens per line — per-model denominators (issue #169)
        const val K_DEFAULT_AGENT = "default_session_agent"   // SecureStore: AgentKind.name new sessions start under (default CLAUDE)
        const val K_AGENT_FILTER = "sessions_agent_filter"    // SecureStore: "both" | one agent key | comma-joined keys — project/session filter (#31/#188/#248, see AgentFilter.kt)
        const val K_VIEW_MODE = "projects_view_mode"          // SecureStore: "tree" | "flat" for the Projects screen
        const val K_PINNED = "pinned_projects"                 // SecureStore: '\n'-joined project paths pinned to the top
        const val K_WORKING_SET_PREFIX = "working_set_mru:"    // SecureStore: "working_set_mru:<accountId>" → TSV dirKey\tsessionId\ttitle\tproject\tat\tagent — that computer's switcher MRU (issue #165)
        const val K_DRAFT_PREFIX = "draft:"                    // SecureStore: "draft:<sessionId|convoId|workdir>" → unsent composer text for that conversation
        const val K_SESSION_PARAMS = "session_params"          // SecureStore: TSV sid\tmode\tmodel\teffort\tagent per line (last 100 sessions)
        const val K_FONT_SCALE = "chat_font_scale"            // SecureStore: chat text scale factor (Float string, default 1.0)
        const val K_THEME_MODE = "appearance_theme_mode"      // SecureStore: ThemeMode name (SYSTEM/LIGHT/DARK; issue #63)
        const val K_ACCENT_THEME = "appearance_accent_theme"  // SecureStore: AccentTheme name (POCKET/CODEX; issue #204)
        const val K_VOICE_ENGINE = "voice_engine"             // SecureStore: "whisper" = transcribe on the computer; "" = native dictation when available
        const val K_SHARE_ENDED_PREFIX = "share_ended:"        // SecureStore: "share_ended:<accountId>" → "reason\townerLabel" — the guest's ShareEnded notice (#115 follow-up)
        const val FONT_SCALE_MIN = 0.85f                       // smallest chat text scale (Settings slider lower bound)
        const val FONT_SCALE_MAX = 1.4f                        // largest chat text scale (eye-comfort upper bound)
        // auto-continue fires this long AFTER the parsed limit reset (issue #137) — absorbs clock skew
        // between the CLI's reported epoch and the account's actual window flip
        const val LIMIT_RESUME_MARGIN_MS = 90_000L
    }
}

private const val REFRESH_SPINNER_SAFETY_MS = 4_000L // spinner never outlives a lost reply by more than this

/** Degraded-mode copy for [PocketRepository.daemonTooOldText] (#251). Mirrors `ho_daemon_too_old`. */
private const val DAEMON_TOO_OLD_FALLBACK =
    "This computer's daemon doesn't support handoff yet — update it to use this."

/** #142: cancel the previous connection's job and WAIT (bounded) until it has actually finished — its
 *  socket closed and its writers off the shared outboxes — before the next connection dials. cancel()
 *  alone is cooperative, which is exactly how the two-socket overlap (relay supersede mutual-kick) was
 *  born. Bounded so a wedged close can't stall reconnecting; the connection-generation guard inside the
 *  E2E connections fences any straggler that outlives the bound. */
internal suspend fun retireJobBounded(prev: Job?, timeoutMs: Long) {
    prev ?: return
    prev.cancel()
    withTimeoutOrNull(timeoutMs) { prev.join() }
}
