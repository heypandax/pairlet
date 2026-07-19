package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.ensureLocalNetworkAccess
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.net.DirectE2EConnection
import dev.ccpocket.app.net.DirectUnreachableException
import dev.ccpocket.app.net.RelayAuthException
import dev.ccpocket.app.net.RelayConnection
import dev.ccpocket.app.net.RelayControlDial
import dev.ccpocket.app.net.RelayE2EConnection
import kotlinx.coroutines.flow.merge
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.push.PushController
import dev.ccpocket.app.lock.AppLockController
import dev.ccpocket.app.lock.createBiometrics
import dev.ccpocket.app.theme.ThemeMode
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
import dev.ccpocket.protocol.ChatRole
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
import dev.ccpocket.app.pairing.toPairingInfo
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.SlashCommand
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
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
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
import dev.ccpocket.protocol.isModelCompatibleWithAgent
import dev.ccpocket.protocol.ActivatePreset
import dev.ccpocket.protocol.DeletePreset
import dev.ccpocket.protocol.FetchAuthStatus
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.AGENT_WIRE_OPENCODE
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
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
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
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.GroupCreate
import dev.ccpocket.protocol.GroupRename
import dev.ccpocket.protocol.RenameSession
import dev.ccpocket.protocol.GroupDelete
import dev.ccpocket.protocol.GroupAssign
import dev.ccpocket.app.isPreviewMode
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.preview_cmd_title
import dev.ccpocket.app.resources.preview_cmd_note
import dev.ccpocket.app.resources.status_checking_network
import dev.ccpocket.app.resources.status_conn_lost
import dev.ccpocket.app.resources.status_connecting
import dev.ccpocket.app.resources.status_disconnected
import dev.ccpocket.app.resources.status_failed
import dev.ccpocket.app.resources.status_invalid_link
import dev.ccpocket.app.resources.status_local_denied
import dev.ccpocket.app.resources.status_pair_failed
import dev.ccpocket.app.resources.status_pairing
import dev.ccpocket.app.resources.status_reconnecting
import dev.ccpocket.app.resources.voice_audio_engine
import dev.ccpocket.app.resources.voice_daemon_unreachable
import dev.ccpocket.app.resources.voice_dictation_failed
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ChatItem {
    /** [pending] = sent from this device but the daemon hasn't echoed any evidence back yet (stream
     *  chunk / tool event / turn end). Stays true while the link is down so the UI can say so —
     *  frames queue in the transport outbox indefinitely and would otherwise look "sent" (issue #41).
     *  [promptId] ties the bubble to its [dev.ccpocket.protocol.PromptAck] receipt; [delivered] flips
     *  when that ack lands — the explicit "the computer has it" marker (issue #66). */
    data class User(
        val text: String,
        val images: List<ByteArray> = emptyList(),
        val pending: Boolean = false,
        val promptId: String? = null,
        val delivered: Boolean = false,
        /** Files uploaded to the session's workspace inbox and referenced by this turn (issue #90) —
         *  rendered as file chips with their `@` landing path. Client-side only, like [images]. */
        val files: List<SentFile> = emptyList(),
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

    /** The compact transcript row left behind after answering an AskUserQuestion card:
     *  (question → answer) pairs; a freeform reply is a single ("" → response) pair. */
    data class QuestionsAnswered(val items: List<Pair<String, String>>) : ChatItem

    /** Claude withdrew its questions (control_cancel) — muted one-liner where the card used to be. */
    data object QuestionsWithdrawn : ChatItem

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

    /** Persisted default reasoning effort for NEW sessions (null = the model's own default). Resumed sessions
     *  keep their own. Stored as "" for the null/default choice (SecureStore can't hold null). */
    val defaultEffort = mutableStateOf(SecureStore.getString(K_DEFAULT_EFFORT)?.takeIf { it.isNotEmpty() })

    /** Persisted default model for NEW Claude sessions (null = the CLI's own default). Applied only when the
     *  new session's agent is Claude — the stored value is a Claude alias/id and would be meaningless to a
     *  Codex launch. Resumed sessions keep their own model. Stored as "" for the null/default choice. */
    val defaultModel = mutableStateOf(SecureStore.getString(K_DEFAULT_MODEL)?.takeIf { it.isNotEmpty() })

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
     *  catch-all. Null = no override, follow the daemon/model-derived window. */
    fun contextWindowOverrideFor(model: String?): Long? =
        modelKey(model)?.let { contextWindowOverrides[it] } ?: contextWindowOverride.value

    /** Persisted default agent backend for NEW sessions (Claude unless the user switched to Codex). Resumed
     *  sessions keep their own backend (the picker only seeds new ones). */
    val defaultAgent = mutableStateOf(
        SecureStore.getString(K_DEFAULT_AGENT)?.let { s -> AgentKind.entries.firstOrNull { it.name == s } } ?: AgentKind.CLAUDE,
    )

    /** Session-list agent filter: "both" | "claude" | "codex" (persisted). Hides the other agent's sessions
     *  from the Sessions list; each row keeps its own identity color (issue #31). */
    val agentFilter = mutableStateOf(SecureStore.getString(K_AGENT_FILTER)?.takeIf { it.isNotEmpty() } ?: "both")

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

    /** Sessions that finished while the user was looking at something ELSE — the switcher's attention
     *  dot. Cleared per-session on open; per-machine (a disconnect/switch drops them, see [disconnect]). */
    val unseenSessions = mutableStateOf<Set<String>>(emptySet())

    /** Session ids the LAST project list reported as working — the busy→idle edge detector's other half. */
    private var lastWorkingSessions: Set<String> = emptySet()

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

    /**
     * Record an open at the head of the MRU and clear that session's unseen mark. Called from the daemon's
     * [SessionLive] announce (authoritative identity), from the first prompt of a brand-new session (which
     * is when its title finally exists), and optimistically by [switchToSession] so the sheet re-orders on
     * the tap rather than a round-trip later. Idempotent — a re-touch just refreshes labels.
     */
    internal fun rememberOpenedSession(dirKey: String?, sessionId: String?, title: String?, agent: AgentKind?) {
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
        if (sessionId in unseenSessions.value) unseenSessions.value = unseenSessions.value - sessionId
    }

    /** Fold a fresh project list into the finished-while-away marks. Marks are pruned to sessions the
     *  switcher can actually SHOW (running or remembered) — a badge with no row behind it is a lie. */
    private fun noteWorkingSessions() {
        val now = runningSessions(directories.toList()).map { it.sessionId }.toSet()
        val marked = markFinishedAway(lastWorkingSessions, now, openSessionId(), unseenSessions.value)
        val visible = now + workingSetMru.map { it.sessionId }
        val pruned = marked.filterTo(mutableSetOf()) { it in visible }
        if (pruned != unseenSessions.value) unseenSessions.value = pruned
        lastWorkingSessions = now
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
    /** PREVIEW: brief connecting → end-to-end-encrypted opener shown before the demo project list. */
    val demoConnecting = mutableStateOf(false)
    val directories = mutableStateListOf<DirectoryEntry>()
    /** True once the first Directories of a session arrives — distinguishes "empty" from "still loading". */
    val directoriesLoaded = mutableStateOf(false)
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
    /** The daemon's refusal of the LAST [renameSession] attempt (issue #158), keyed to the session it
     *  targeted. Renames are asked from the SESSIONS list, so the feedback belongs there — the most
     *  common refusal (renaming a terminal-held session from the sidebar) happens with no chat open at
     *  all, and whatever chat IS open is an unrelated session whose transcript must not absorb the
     *  error line. Cleared by the next attempt / [dismissRenameError]. */
    val renameError = mutableStateOf<RenameRefusal?>(null)
    private var renameTarget: String? = null // the sessionId the in-flight RenameSession asked about
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
    // issue #100: the askId the daemon reported as TIMED_OUT — the permission sheet for THIS exact ask renders
    // its terminal "timed out / auto-denied" state instead of vanishing. Matched by id, so a stale value can
    // never bleed onto the next card (askIds are unique per request).
    val timedOutAskId = mutableStateOf<String?>(null)
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
    val pathListing = mutableStateOf<PathEntries?>(null)     // latest @-file completion listing (issue #75); match its subPath before use
    val browseListing = mutableStateOf<PathEntries?>(null)   // latest home-anchored folder-browse listing (issue #152); match its subPath before use
    private var lastBrowseSub: String? = null                // subPath of the LATEST browseHomeDirs request — only its reply may land in browseListing (#152 复核: stale out-of-order replies dropped)
    val mode = mutableStateOf(PermissionMode.DEFAULT)        // current execution/permission mode
    val model = mutableStateOf<String?>(null)                // daemon's actual model for this session (header + info sheet)
    val sessionAgent = mutableStateOf<AgentKind?>(null)      // backend driving this session (Claude/Codex) — header badge
    val effort = mutableStateOf<String?>(null)               // reasoning effort: low|medium|high|xhigh|max (null = default)
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
    val switching = mutableStateOf(false)                    // a mode switch is relaunching the session
    val opening = mutableStateOf(false)                      // an OpenSession is in flight — one-tap entries disable on it (a double-tap would open two fresh sessions)
    // A chat→chat switch is in flight (issue #165). [openSession] nulls convoId while it waits for the
    // daemon, and the router falls to the session LIST whenever convoId is null — so without this the
    // switcher visibly bounced you out to a list for a beat before landing. Cleared wherever [opening] is.
    val switchingSession = mutableStateOf(false)
    val openTimedOut = mutableStateOf(false)                 // the daemon never answered an OpenSession within 8s — slim banner, auto-dismissed (issue #41)
    private var openGen = 0                                  // generation counter matching each openSession call to its own safety-net timer
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

    /** The in-flight prompt got neither a [dev.ccpocket.protocol.PromptAck] nor any stream evidence within
     *  [promptReceiptTimeoutMs] (issue #78). The link can CLAIM healthy while nothing comes back — outboxes
     *  buffer across reconnects by design, and an E2E-deaf link (the daemon dropped this device's session
     *  while the socket stayed up — routine when a fleet of machines keeps cycling links) never errors — so
     *  without a deadline the bubble reads "sending…" forever. Drives the honest "not delivered" cue on
     *  both UIs; cleared by the first daemon evidence, a session change, or teardown. */
    val sendStalled = mutableStateOf(false)
    private var promptWatchdog: Job? = null
    internal var promptReceiptTimeoutMs = 10_000L // > relay RTT + a lazy agent spawn; a test seam shrinks it

    /** Second-stage deadline (issue #104): a [dev.ccpocket.protocol.PromptAck] only means the daemon WROTE
     *  the prompt to the agent's stdin — not that a turn started. A wedged or mid-relaunch agent can swallow
     *  that write and emit nothing, leaving [streaming] stuck true and the UI silently "thinking" forever
     *  (issue #78's receipt watchdog is already cancelled by the ack, so nothing catches this). Once delivered
     *  we hand off to [armTurnWatchdog]: no chunk/tool/turn-end within [promptTurnTimeoutMs] flips [turnStalled],
     *  which surfaces an inline "resend" cue instead of an endless spinner. This is deliberately NOT an
     *  auto-resend: the live daemon Conversation already recorded this promptId (the #66 dedup that makes a
     *  SessionGone same-id resend safe would here turn a same-id resend into a bare re-ack — no turn), and a
     *  fresh-id auto-resend would double-run a turn that was merely slow to start. The recovery is user-driven
     *  ([resendStalledPrompt], fresh id). [turnStalled] retracts on the first real turn frame or a session change.
     *  Only for prompts sent into an IDLE session — a mid-turn send is the queued case, [turnQueued]. */
    val turnStalled = mutableStateOf(false)

    /** Queued flavor of the same deadline: the prompt was sent INTO an already-running turn (the composer's
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

    /** Real turn evidence (chunk / tool / turn-end / error) or a terminal frame (process exit, session gone):
     *  the agent is actually producing — or the whole turn is being torn down. Cancels BOTH the delivery
     *  receipt watchdog (issue #78) and the turn-start watchdog (issue #104), clears both stall cues, drops
     *  the retry copy, and flips the pending User bubble to delivered (issue #41). */
    private fun promptEvidence() {
        promptRetry = null
        promptWatchdog?.cancel(); promptWatchdog = null // the daemon is talking — the receipt deadline is moot
        turnWatchdog?.cancel(); turnWatchdog = null; awaitingTurn = false // …and a real turn frame moots the turn deadline
        sendStalled.value = false
        turnStalled.value = false
        turnQueued.value = false
        if (!promptPending) return
        promptPending = false
        val i = messages.indexOfLast { it is ChatItem.User }
        (messages.getOrNull(i) as? ChatItem.User)?.takeIf { it.pending }?.let { messages[i] = it.copy(pending = false) }
    }

    /** Delivery receipt ONLY (PromptAck, issue #104): the daemon wrote the prompt to the agent's stdin, but an
     *  ack is not a started turn. Clear the delivery-stage machinery (the receipt deadline is met) and, if a turn
     *  is still expected, hand off to the turn-start watchdog. Deliberately keeps [promptRetry] — the resend cue
     *  (and a late SessionGone in this window) still needs the text/images. The PromptAck handler flips the
     *  specific bubble to delivered right after this; here we only clear the delivery FLAG. */
    private fun promptDelivered() {
        promptWatchdog?.cancel(); promptWatchdog = null // receipt arrived — the delivery deadline is moot
        sendStalled.value = false
        promptPending = false
        if (streaming.value) { awaitingTurn = true; armTurnWatchdog(queued = promptQueued) } // a TurnDone/error already in wouldn't re-arm
    }

    // mode/model/effort are claude launch flags, NOT stored in the transcript jsonl. Leaving an idle
    // session closes its process; reopening resumes a FRESH process that would otherwise default these.
    // Remember the last-known set per sessionId so a reopen restores the badge + relaunches under them.
    // Persisted (TSV in SecureStore, last 100) so an app restart doesn't reset every session to defaults.
    private data class SessionParams(val mode: PermissionMode, val model: String?, val effort: String?, val agent: AgentKind = AgentKind.CLAUDE)
    private val sessionParams = mutableMapOf<String, SessionParams>()

    init {
        SecureStore.getString(K_SESSION_PARAMS)?.lineSequence()?.forEach { line ->
            val t = line.split('\t')
            if (t.size >= 5) runCatching {
                sessionParams[t[0]] = SessionParams(PermissionMode.valueOf(t[1]), t[2].ifEmpty { null }, t[3].ifEmpty { null }, AgentKind.valueOf(t[4]))
            }
        }
        loadWorkingSet() // #165: keyed on [paired], which only exists this far down the constructor
    }

    private fun persistSessionParams() {
        val lines = sessionParams.entries.toList().takeLast(100)
            .joinToString("\n") { (sid, p) -> listOf(sid, p.mode.name, p.model ?: "", p.effort ?: "", p.agent.name).joinToString("\t") }
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
    private val recorder by lazy { VoiceRecorder() }
    private var usingNative = false
    private var preferRemote = false                         // sticky after a native-engine failure
    private var keptAudio: RecordedAudio? = null             // retained for S5 retry (re-send, not re-record)
    private var captureId: String? = null
    private var voiceTicker: Job? = null
    private var voiceTimeout: Job? = null
    private var levelsJob: Job? = null
    private var dictationJob: Job? = null
    private var noticeJob: Job? = null

    /** Pair from a scanned/pasted `ccpocket://pair?...` link, then connect end-to-end. */
    fun pair(link: String) {
        val info = Pairing.parse(link.trim())
        if (info == null) { status.value = StatusMsg(Res.string.status_invalid_link); return }
        status.value = StatusMsg(Res.string.status_pairing)
        scope.launch { doPair("link") { info } }
    }

    /** A scanned/opened `ccpocket://pair?...` URL — either a short ?code= or a full link. */
    fun handlePairUrl(url: String) {
        val code = Regex("[?&]code=([0-9]{6})").find(url)?.groupValues?.get(1)
        if (code != null) pairWithCode(code) else pair(url)
    }

    /** Pair from the 6-digit code shown by `cc-pocket pair` on the computer. */
    fun pairWithCode(code: String) {
        status.value = StatusMsg(Res.string.status_pairing)
        scope.launch { doPair("code") { Pairing.resolveCode(code.trim(), it) } }
    }

    private suspend fun doPair(source: String, getInfo: suspend (HttpClient) -> dev.ccpocket.app.pairing.PairingInfo) {
        val client = HttpClient()
        try {
            val info = getInfo(client)
            val keys = Pairing.deviceKeys()
            paired.value = Pairing.redeem(info, keys, client) // upserts the list + pins this as the active account
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
            Telemetry.track(TelEvent.PairFailed)
            Telemetry.recordError(t.message ?: "pair failed", "pairing")
        } finally {
            client.close()
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
        val ready = attachedThisSession && directoriesLoaded.value && !daemonOffline
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
        openSession(t.workdir, t.sessionId, title = t.title, agent = t.agent ?: defaultAgent.value)
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
        val seenRev = directoriesRev
        presenceProbeJob = scope.launch {
            send(ListDirectories())
            restoreAfterReconnect()
            delay(presenceProbeMs)
            if (sessionActive.value && directoriesRev == seenRev) launchTransport(reconnect = true, force = true)
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
     *  follows pairing). The token callback may land later — [registerPush] also runs on every Attached. */
    private fun ensurePushStarted() {
        if (pinnedTo != null) return // satellites leave the platform push singleton to the primary link
        if (pushStarted || !notificationsOn.value) return
        pushStarted = true
        PushController.start { onPushToken(it) }
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
        val tok = pushToken ?: return
        val sent = tok.platform to (if (notificationsOn.value) tok.token else "")
        if (sent == pushRegistered) return
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

    /** Settings toggle: persist the choice, then register (on) or clear (off) the token on the relay. */
    fun setNotificationsEnabled(on: Boolean) {
        if (on == notificationsOn.value) return
        notificationsOn.value = on
        SecureStore.putString(K_NOTIFY, if (on) "1" else "0")
        ensurePushStarted() // self-guards when off
        registerPush()
    }

    /** Settings: persist the default execution mode for new sessions. Takes effect on the next new session. */
    fun setDefaultMode(m: PermissionMode) {
        if (m == defaultMode.value) return
        defaultMode.value = m
        SecureStore.putString(K_DEFAULT_MODE, m.name)
    }

    /** Projects screen: persist the browse mode (true = tree, false = flat). */
    fun setTreeView(on: Boolean) {
        if (on == treeView.value) return
        treeView.value = on
        SecureStore.putString(K_VIEW_MODE, if (on) "tree" else "flat")
    }

    /** Settings: persist the default reasoning effort for new sessions (null = model default). */
    fun setDefaultEffort(level: String?) {
        val v = level?.takeIf { it.isNotEmpty() }
        if (v == defaultEffort.value) return
        defaultEffort.value = v
        SecureStore.putString(K_DEFAULT_EFFORT, v ?: "")
    }

    /** Settings: persist the default model for new Claude sessions (null = the CLI's own default). */
    fun setDefaultModel(id: String?) {
        val v = id?.takeIf { it.isNotEmpty() }
        if (v == defaultModel.value) return
        defaultModel.value = v
        SecureStore.putString(K_DEFAULT_MODEL, v ?: "")
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

    /** Settings: persist the session-list agent filter ("both" | "claude" | "codex"). */
    fun setAgentFilter(v: String) {
        if (v == agentFilter.value) return
        agentFilter.value = v
        SecureStore.putString(K_AGENT_FILTER, v)
    }

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
            if (sessionActive.value && attachedThisSession && !directoriesLoaded.value && !daemonOffline && !pairingInvalid) {
                daemonOffline = true
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
        if (!reconnect) { pairingInvalid = false; hadReadyThisSession = false; directoriesLoaded.value = false }
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
                    relay.connect(p, Pairing.deviceKeys(), firstTicket).also { firstTicket = null }
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
        scope.launch { send(ClientCaps(supportsAgents = listOf(AGENT_WIRE_OPENCODE))); send(ListDirectories()); if (reconnect) restoreAfterReconnect() }
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
            refreshDirectoriesSilently()
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
        retryJob?.cancel(); connectJob?.cancel(); inboundJob?.cancel(); controlJob?.cancel(); deafJob?.cancel(); graceJob?.cancel(); listWaitJob?.cancel(); connectWatchdog?.cancel(); reconnectGraceJob?.cancel(); linkStableJob?.cancel(); presenceProbeJob?.cancel()
        retryJob = null; connectJob = null; inboundJob = null; controlJob = null; deafJob = null; graceJob = null; listWaitJob = null; connectWatchdog = null; reconnectGraceJob = null; linkStableJob = null; presenceProbeJob = null
        promptWatchdog?.cancel(); promptWatchdog = null; sendStalled.value = false // pending bubbles leave with messages below
        turnWatchdog?.cancel(); turnWatchdog = null; awaitingTurn = false; turnStalled.value = false; turnQueued.value = false // (issue #104) drop the ack→turn deadline too
        // frames queued for the binding we're leaving must not leak into the next link (both transports
        // are reused across machine switches, and their outboxes deliberately buffer across reconnects)
        directAttemptInFlight = false
        directE2E.drainPending(); relay.drainPending()
        connected.value = false
        phase.value = ConnPhase.Connecting
        pendingOpen = null // a queued push-tap target is moot once the user drops the connection
        attachedThisSession = false; daemonOffline = false; pairingInvalid = false
        hadReadyThisSession = false; relayDeadlinePassed = false; reconnectGracePassed = false; listWaitRetried = false; directoriesLoaded.value = false
        pushDialJob?.cancel(); pushDialJob = null // an in-flight LAN-side token dial dies with the link
        pushRegistered = null // a fresh connect (or a switched daemon) must re-register the token
        // per-daemon truth must not survive a machine switch: a stale non-null presetsState would keep
        // the token-bearing create/edit form UNLOCKED after switching to a daemon that predates presets
        // (it silently drops FetchPresets), breaking "never fire a plaintext token at a peer that can't
        // store it". authState clears for the same reason — the next daemon's account is a fresh fetch.
        authState.value = null
        presetsState.value = null; presetsStateRev.value = 0
        gatewayBaseUrl.value = null // per-daemon truth (issue #139): the next machine re-announces via DaemonInfo
        bridgeControl.value = null  // per-daemon truth too — the next daemon re-advertises via DaemonInfo (issue #91)
        // per-daemon truth too: the next machine's skills/plugins are a fresh fetch (issue #132)
        skillCatalogDeadline?.cancel()
        skillCatalog.value = null; skillCatalogLoading.value = false; skillCatalogUnavailable.value = false
        convoId.value = null
        sessionsDir.value = null
        workdir.value = null // clear with the rest so a stale path can't leak into the next machine's ⌘N (issue #56)
        pendingAsk.value = null
        // #165: the busy→idle detector is per-MACHINE state. Carrying this machine's working ids into the
        // next one's first project list would mark every one of them "finished while you were away".
        lastWorkingSessions = emptySet(); unseenSessions.value = emptySet()
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
    fun beginAddDevice() { disconnect(); addingDevice.value = true }

    /** Back out of "add a computer" — returns to the device picker (bindings are untouched). */
    fun cancelAddDevice() { addingDevice.value = false }

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
                Telemetry.track(TelEvent.PairFailed)
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
        defaultEffort.value = from.defaultEffort.value
        defaultModel.value = from.defaultModel.value
        contextWindowOverride.value = from.contextWindowOverride.value
        contextWindowOverrides.clear(); contextWindowOverrides.putAll(from.contextWindowOverrides) // #169: per-model table travels with the rest of Settings
        defaultAgent.value = from.defaultAgent.value
        agentFilter.value = from.agentFilter.value
        treeView.value = from.treeView.value
        fontScale.value = from.fontScale.value
        themeMode.value = from.themeMode.value
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
        convoId.value?.let { c -> if (observing.value || !streaming.value) scope.launch { send(CloseSession(c)) } }
        pendingOpen = null
        promptWatchdog?.cancel(); promptWatchdog = null; sendStalled.value = false
        promptRetry = null; promptPending = false; promptResendArmed = false
        convoId.value = null; currentSessionId = null; sessionKey.value = null
        workdir.value = null // same reason as disconnect(): a stale path must not leak into a later ⌘N (issue #56)
        sessionsDir.value = null; sessions.clear()
        chatTitle.value = null; observing.value = false; streaming.value = false
        opening.value = false; openTimedOut.value = false; switching.value = false; switchingSession.value = false
        autoFocusComposer.value = false
        pendingAsk.value = null
        messages.clear(); pendingImages.clear()
        resetHistoryPaging() // #147
        terminalEntries.clear(); terminalBusy.value = false
        changedFiles.clear(); changedFilesLoading.value = false; changedFilesUnavailable.value = false
        closeFileViewer()
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

    /** All outbound frames funnel here; a throw means the link is dead — trigger the reconnect path. */
    private suspend fun send(frame: Frame) {
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
                handle(SessionLive(cid, frame.workdir, frame.resumeId ?: DemoData.LIVE_SESSION_ID, mode = frame.mode, executing = false))
                handle(CommandList(cid, DemoData.commands()))
                if (frame.resumeId != null) handle(ConvoHistory(cid, DemoData.history())) // resumed = preloaded transcript
            }
            is SendPrompt -> demoHandlePrompt(frame.convoId)
            is PermissionVerdict -> if (demoPendingReply) { demoPendingReply = false; demoStream(frame.convoId, DemoData.REPLY_CHUNKS, thinking = false) }
            is SwitchMode -> handle(SessionLive(frame.convoId, workdir.value ?: DemoData.LIVE_DIR, currentSessionId, mode = frame.mode, executing = false))
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
            else -> {} // CloseSession / ClearAllowRule / SwitchDirectory / AudioCancel / FileUploadCancel — nothing to echo
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
            val title = if (preview) getString(Res.string.preview_cmd_title) else DemoData.ASK_TITLE
            val note = if (preview) getString(Res.string.preview_cmd_note) else null
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

    // control-plane counterpart: Attached/PeerPresence/AuthError flow through handleControl, not handle.
    // Lets a test drive a (re)attach edge without a live transport — e.g. that Attached bumps connGen so
    // the Account pane's fetch re-keys on reconnect.
    internal fun receiveControlForTest(f: Frame) = handleControl(f)

    // #146: drive a deaf-link signal exactly as a transport reader would after N consecutive decrypt
    // failures, to assert it forces a re-handshake (mid-turn self-heal) without a live daemon.
    internal fun receiveDeafForTest() = onDeafLink()

    private fun handle(f: Frame) {
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
                    Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to transportName()))
                }
                recomputePhase()
            }
            is Sessions -> {
                sessionsDir.value = f.workdir; replace(sessions, f.items)
                replace(sessionGroups, f.groups ?: emptyList()) // #119: null (older daemon) → no groups, flat list
                groupsSupported.value = f.groups != null // groups=[] (owner, none yet) still enables management
                renameSupported.value = f.renameSupported // #158: false from an older daemon / a guest
                sessionsRefreshing.value = false
            }
            is Usage -> { usage.value = f; usageLoading.value = false }
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
                agentModels[f.agent] =
                    if (f.error != null && f.models.isEmpty() && prev != null && prev.models.isNotEmpty()) prev.copy(error = f.error)
                    else f
            }
            is PushPrefs -> pushPrefs.value = f.enabled
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
            }
            is SessionLive -> {
                migrateDraft(f.sessionId) // before re-keying: composerKey() still reads the old chain
                convoId.value = f.convoId; workdir.value = f.workdir; observing.value = f.observing; currentSessionId = f.sessionId
                f.sessionId?.let { sessionKey.value = it }
                f.mode?.let { mode.value = it } // daemon is the source of truth — corrects the optimistic badge
                f.effort?.let { effort.value = it }
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
                f.sessionId?.let { sessionParams[it] = SessionParams(mode.value, model.value, effort.value, sessionAgent.value ?: AgentKind.CLAUDE) }
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
                promptDelivered() // delivered ≠ turn started (issue #104): hand off to the turn-start watchdog
                val i = messages.indexOfLast { it is ChatItem.User && it.promptId == f.promptId }
                (messages.getOrNull(i) as? ChatItem.User)?.let { messages[i] = it.copy(pending = false, delivered = true) }
            }
            // Stream/turn frames carry their source convoId; this single-active-view model has one `messages`
            // list, so a frame from a just-left conversation (its tail still in flight when we switched) must
            // be dropped — else it renders into whatever convo is now open. Reopening the source replays its
            // full transcript via ConvoHistory, so nothing is actually lost. (Matches the BackgroundJobs guard.)
            is AssistantChunk -> if (f.convoId == convoId.value) { promptEvidence(); appendChunk(f) }
            is ToolEvent -> if (f.convoId == convoId.value) { promptEvidence(); finishThinking(); onToolEvent(f) }
            is PermissionAsk -> if (f.convoId == convoId.value) { pendingAsk.value = f; Telemetry.track(TelEvent.ApprovalShown, mapOf(TelKey.Tool to f.tool)) }
            // claude withdrew the ask (interrupt / moved on) — drop the card; a question card leaves a muted notice
            is AskWithdrawn -> if (f.convoId == convoId.value && pendingAsk.value?.askId == f.askId) {
                val ask = pendingAsk.value
                if (f.reason == AskWithdrawnReason.TIMED_OUT && ask?.questions == null) {
                    // issue #100: keep the permission card up but flip it to its terminal "timed out" state,
                    // so a returning user sees what happened instead of a card that silently vanished (which
                    // read as success). A tap on it now only dismisses — no more silent no-op.
                    timedOutAskId.value = f.askId
                } else {
                    // agent moved on / session closed / a question timed out → dismiss (a question leaves a note)
                    if (ask?.questions != null) messages.add(ChatItem.QuestionsWithdrawn)
                    pendingAsk.value = null
                }
            }
            is TurnDone -> if (f.convoId == convoId.value) {
                promptEvidence()
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
            is BackgroundJobs -> if (f.convoId == convoId.value) replace(backgroundJobs, f.jobs)
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
            is PocketError -> if (f.code == "rename_failed") {
                // #158: a rename refusal answers to the SESSIONS surface (the sidebar/list row that
                // asked) — the common case (renaming a terminal-held session) has no chat open, and an
                // open chat is an UNRELATED session whose transcript must not absorb the error line.
                renameError.value = renameTarget?.let { RenameRefusal(it, f.message) }
            } else {
                // …and a failed switch must release the router, or the chat would hold an empty screen
                opening.value = false; switchingSession.value = false // a failed open re-enables the one-tap entries right away
                messages.add(ChatItem.Sys(f.message)) // UI prepends the localized "error:" prefix
                // a dead claude process never sends TurnDone — clear the streaming state here
                if (f.code == "process_exited" && (f.convoId == null || f.convoId == convoId.value)) {
                    promptEvidence()
                    finishThinking(); streaming.value = false
                }
            }
            // The daemon no longer holds this conversation (idle-reaped during a link drop / daemon restart).
            // Recover instead of spinning: re-open (resume) the same session, and the SessionLive handler
            // resends the pending prompt exactly once. No session to resume → surface it honestly.
            is SessionGone -> if (f.convoId == convoId.value) {
                if (observing.value) {
                    // an observe view can't run turns — a stray send must not leave the caret spinning
                    // forever (the old blanket ignore did exactly that, issue #45 ②)
                    promptEvidence(); finishThinking(); streaming.value = false
                } else {
                    val sid = sessionKey.value ?: currentSessionId
                    val wd = workdir.value
                    if (promptRetry != null && !promptResendArmed && sid != null && wd != null) {
                        promptResendArmed = true
                        // lastEventSeq (issue #147): the transcript is still on screen — delta reattach
                        scope.launch { send(OpenSession(wd, sid, mode = mode.value, agent = sessionAgent.value ?: AgentKind.CLAUDE, lastEventSeq = lastEventSeqFor(sid))) }
                    } else {
                        promptEvidence(); promptResendArmed = false
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
            // @-file completion (issue #75): keyed on workdir, not a session id — it browses the cwd, not a
            // session's changed set. A reply for a workdir we've since left is dropped (the completer keys
            // the visible listing on its own requested subPath anyway).
            // The folder browser (issue #152) rides the same frame anchored at the literal "~" — a session's
            // workdir is always a real absolute path, so the two listings can't collide on the key. Browser
            // replies additionally pass the latest-request gate: replies can arrive out of order, and a
            // drilled-past level's late reply must not clobber the fresh one (#152 复核, [foldBrowseReply]).
            is PathEntries -> when (f.workdir) {
                BROWSE_HOME -> browseListing.value = foldBrowseReply(browseListing.value, f, lastBrowseSub)
                workdir.value -> pathListing.value = f
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
            else -> messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: "", taskId = f.toolUseId))
        }
    }

    private fun historyItem(h: HistoryMessage): ChatItem = when (h.role) {
        ChatRole.USER -> ChatItem.User(h.text)
        // a synthetic API-failure placeholder replays as the error it was, not as a normal reply (issue #65)
        ChatRole.ASSISTANT -> if (h.error) ChatItem.Sys("API request failed — placeholder reply: ${h.text}") else ChatItem.Assistant(h.text)
        // an answered AskUserQuestion replays as the same compact answered row the live path leaves, not a
        // raw-JSON tool card (issue #110); ok/output keep a sub-agent card's outcome + report (issue #77);
        // workflowRunId binds a Workflow card to its separately-pushed run (issue #106)
        ChatRole.TOOL -> h.answers?.let { a -> ChatItem.QuestionsAnswered(a.map { it.question to it.answer }) }
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

    fun fetchPresets() = scope.launch { runCatching { send(FetchPresets) } }

    /** Per-agent model lists from the daemon ([FetchModels] → [ModelsList]) — what the picker offers
     *  beyond the static presets. Keyed by agent so a late reply can't cross-pollute another backend. */
    val agentModels = mutableStateMapOf<AgentKind, ModelsList>()

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

    /** Ask the daemon to aggregate usage over the last [days] local days; the reply lands in [usage]. */
    fun fetchUsage(days: Int = 7) {
        usageLoading.value = true
        scope.launch { send(FetchUsage(days)) }
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
    ) {
        bridgeRequestStarted()
        bridgeCredential.value = null
        scope.launch { send(CreateBridge(name, workdirs, maxSessions, tier = tier, runner = runner)) }
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
    fun configureBridgeRunner(name: String, spec: BridgeRunnerSpec, mergeEnv: Boolean = false) {
        bridgeRequestStarted()
        // arm the merge-loss guard: remember what WAS configured, so the reply can prove nothing vanished
        pendingMergeCheck = if (mergeEnv) {
            name to (bridges.firstOrNull { it.name == name }?.runner?.envKeys?.toSet() ?: emptySet())
        } else null
        scope.launch { send(ConfigureBridgeRunner(name, spec, mergeEnv)) }
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

    /** Guest: redeem a scanned/pasted folder-share invite — the same relay redeem as pairing a computer,
     *  but the daemon scopes this binding to the one shared folder (issue #115). */
    fun redeemShareInvite(invite: ShareInvite) {
        status.value = StatusMsg(Res.string.status_pairing)
        scope.launch { doPair("share") { invite.toPairingInfo() } }
    }

    fun listSessions(wd: String) = scope.launch { send(ListSessions(wd)) }

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
    // startMode defaults to the persisted default mode (mirrors effort), so tapping a session straight from
    // the list applies it too — not just the new-session picker.
    fun openSession(wd: String, resumeId: String? = null, startMode: PermissionMode = defaultMode.value, title: String? = null, agent: AgentKind = defaultAgent.value) = scope.launch {
        opening.value = true // held until the daemon answers (SessionLive/PocketError) — 8s net below
        openTimedOut.value = false
        promptPending = false // the pending marker belongs to the previous conversation's transcript
        promptWatchdog?.cancel(); promptWatchdog = null; sendStalled.value = false // and so does its receipt deadline
        turnWatchdog?.cancel(); turnWatchdog = null; awaitingTurn = false; turnStalled.value = false; turnQueued.value = false // (issue #104) new session clears any ack→turn stall
        sessionDegraded.value = false; degradedSendArmed = false // per-session — SessionLive re-announces the truth
        val gen = ++openGen // ties the 8s safety net below to THIS open — a quick second open isn't cleared by the first one's timer
        // Reclaim the current session ONLY if it's idle (or a read-only observe): a RUNNING turn stays
        // alive in the background — same rule as backToBrowse. Desktop switches sessions directly
        // (sidebar click → here, no backToBrowse in between), so an unconditional close was killing
        // the previous session's in-flight work on every switch. Switching back later resumes by
        // sessionId and reattaches the still-live conversation (registry live-match), no fork.
        convoId.value?.let { if (observing.value || !streaming.value) send(CloseSession(it)) }
        messages.clear(); convoId.value = null; replayEcho = false
        resetHistoryPaging() // #147: a fresh open replays in full — a stale cursor must not ask for a delta
        sessionKey.value = resumeId // durable draft key known immediately on resume; null for a brand-new session
        composerEpoch.value++ // a REAL context switch — composers re-init from the target's draft (#29/#88); identity flips don't
        terminalEntries.clear(); terminalBusy.value = false // the quick-terminal scrollback is per-session
        changedFiles.clear(); changedFilesLoading.value = false; closeFileViewer() // changed-files view is per-session too
        streaming.value = false // the previous session's in-flight turn must not leak the ■ button
        turnStartMark = null // …nor stamp its send time onto this session's TurnEnded duration / stop-refill window
        pendingAsk.value = null
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
        val openEffort = saved?.effort ?: defaultEffort.value // new sessions seed from the persisted default; resumed keep their own
        val openAgent = saved?.agent ?: agent // resumed sessions keep their backend; new ones use the picked default
        // new Claude sessions seed from the persisted default model; resumed keep their own, and a Codex launch
        // never inherits the (Claude-shaped) default id — it would be a meaningless --model to the Codex backend.
        val openModel = compatibleModelForAgent(openAgent, saved?.model) ?: when (openAgent) {
            // New OpenCode sessions let the daemon choose from OpenCode config / `opencode models`.
            AgentKind.OPENCODE -> null
            else -> defaultModel.value?.takeIf { openAgent == AgentKind.CLAUDE }
        }
        mode.value = openMode; allowRules.clear()
        model.value = openModel; effort.value = openEffort; contextUsed.value = null // reconciled by SessionLive
        sessionAgent.value = openAgent // optimistic; SessionLive corrects from daemon truth
        // Pre-fetch OpenCode model list so the picker has it ready when the user opens it,
        // rather than only fetching on picker-open (SessionSheets.kt ModelPicker LaunchedEffect).
        fetchModels(openAgent)
        clearBackgroundJobs()
        Telemetry.track(TelEvent.SessionOpened, mapOf(TelKey.Resume to if (resumeId != null) 1 else 0))
        // lastEventSeq = 0 (never null, via lastEventSeqFor after the reset above): full replay, but it
        // declares this client delta-capable so an observe view tails with deltas (issue #147)
        send(OpenSession(wd, resumeId, model = openModel, mode = openMode, effort = openEffort, agent = openAgent, lastEventSeq = lastEventSeqFor(resumeId)))
        delay(8000) // safety: clear if the daemon never answers (matches `switching`)
        if (gen == openGen && opening.value) {
            opening.value = false
            // …and release the router too (issue #165): a switch that never landed must fall back to the
            // session list rather than strand the user on a chat that will never fill
            switchingSession.value = false
            openTimedOut.value = true // surfaced as a slim banner instead of the old silent spinner reset (issue #41)
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
        messages.add(ChatItem.User(text, ready, pending = true, promptId = promptId, files = sentFiles))
        promptPending = true
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
        workdir.value?.let { promptRetry = PromptRetry(outText, images, it, promptId); promptResendArmed = false }
        Telemetry.track(TelEvent.PromptSent)
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
        promptWatchdog?.cancel()
        promptWatchdog = scope.launch {
            delay(promptReceiptTimeoutMs)
            if (!promptPending) return@launch
            sendStalled.value = true
            // non-Ready phases already have the retry/backoff machinery (and the UI banner) on the case
            if (!demoMode.value && sessionActive.value && phase.value == ConnPhase.Ready) launchTransport(reconnect = true)
        }
    }

    /** Second-stage watchdog (issue #104): a delivered prompt that produces no turn frame within the deadline
     *  was swallowed by a wedged / mid-relaunch agent. Surface an inline resend cue instead of spinning forever.
     *  Self-guarded at fire time — a turn frame ([awaitingTurn] cleared), a session change ([convoId] moved off
     *  the one captured here), or a turn that already ended ([streaming] false) all no-op it, so it never fires
     *  into a stale conversation and no teardown path has to reach in and cancel it.
     *  [queued] flips the fired state: a prompt sent mid-turn waits in the CLI's queue, where the same silence
     *  is expected — it gets the calm [turnQueued] status, never the resend cue (see [turnQueued] for why). */
    private fun armTurnWatchdog(queued: Boolean) {
        val c = convoId.value
        turnWatchdog?.cancel()
        turnWatchdog = scope.launch {
            delay(promptTurnTimeoutMs)
            if (!awaitingTurn || convoId.value != c || !streaming.value) return@launch
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
        turnStalled.value = false
        turnWatchdog?.cancel(); turnWatchdog = null; awaitingTurn = false
        val freshId = newPromptId()
        promptRetry = PromptRetry(retry.text, retry.images, retry.workdir, freshId)
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
        Telemetry.track(TelEvent.PromptSent)
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

    /** Ask the daemon for the children under its HOME + [subPath] ('/'-joined, "" = home itself) for the
     *  "open a project folder" browser (issue #152). Reuses the #75 listing frame anchored at the literal
     *  "~" — only the daemon knows the remote machine's home, and its NIO resolve accepts '/' on Windows
     *  too. The reply lands in [browseListing] only while it answers the LATEST request — a stale reply
     *  from a drilled-past level is dropped at fold time (#152 复核), and the picker additionally keys
     *  rendering on its own subPath. A guest credential gets a PocketError instead (GuestGuard denies the
     *  "~" anchor), which the picker never sees — the entry is owner-only client-side and the daemon
     *  stays the authority. */
    fun browseHomeDirs(subPath: String) {
        lastBrowseSub = subPath
        scope.launch { send(ListPathEntries(BROWSE_HOME, subPath)) }
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
                val audio = runCatching { recorder.stop() }.getOrNull()
                if (audio == null || audio.bytes.isEmpty()) {
                    showNotice(Res.string.voice_no_speech)
                    clearVoice()
                } else {
                    keptAudio = audio
                    uploadCapture(audio)
                }
            }
        }
    }

    /** ✕ cancel (S2/S3) — discard everything, back to the idle composer. */
    fun cancelVoice() = stopCapture(notifyDaemon = true)

    /** Tear down capture jobs + engine and reset to Idle; [notifyDaemon] also aborts an in-flight remote transcription. */
    private fun stopCapture(notifyDaemon: Boolean) {
        voiceTicker?.cancel(); levelsJob?.cancel(); voiceTimeout?.cancel(); dictationJob?.cancel()
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

    /** ✓ = confirm AND send (user decision): the transcript goes straight out as the prompt. */
    private fun deliverTranscript(text: String) {
        if (text.isBlank()) {
            showNotice(Res.string.voice_no_speech)
            clearVoice()
            return
        }
        clearVoice()
        sendPrompt(text.trim()) // picks up any staged images too
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
        scope.launch {
            try {
                recorder.start()
            } catch (_: VoicePermissionDenied) {
                micPermissionSheet.value = true
                return@launch
            } catch (t: Throwable) {
                voice.value = VoiceState.Failed(Res.string.voice_record_failed)
                return@launch
            }
            beginTicker()
            levelsJob = scope.launch { recorder.levels.collect { pushLevel(it) } }
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
        if (f.captureId != captureId) return // a superseded/cancelled capture
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

    fun resolve(decision: Decision, remember: Boolean = false, message: String? = null) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        pendingAsk.value = null
        if (decision == Decision.ALLOW && remember) a.rule?.let { r ->
            if (r !in allowRules) allowRules.add(r)
            messages.add(ChatItem.RuleChip(r)) // drop the "always allowing X" chip into the stream
        }
        Telemetry.track(TelEvent.ApprovalDecided, mapOf(TelKey.Decision to (if (remember) "always" else decision.name.lowercase())))
        scope.launch { send(PermissionVerdict(c, a.askId, decision, message = message, remember = remember)) }
    }

    /** Answer an AskUserQuestion prompt: the picks (question text → label/comma-joined labels/"Other…" text)
     *  and/or a freeform [response] ride the ALLOW verdict; the daemon merges them into claude's updatedInput. */
    fun answerQuestions(answers: Map<String, String>?, response: String? = null) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        pendingAsk.value = null
        val items = response?.takeIf { it.isNotBlank() }?.let { listOf("" to it.trim()) }
            ?: a.questions.orEmpty().mapNotNull { q -> answers?.get(q.question)?.takeIf { it.isNotBlank() }?.let { q.question to it } }
        messages.add(ChatItem.QuestionsAnswered(items))
        Telemetry.track(TelEvent.ApprovalDecided, mapOf(TelKey.Decision to "answered"))
        scope.launch { send(PermissionVerdict(c, a.askId, Decision.ALLOW, answers = answers, response = response)) }
    }

    /** Timeout: the daemon already auto-denied at 30s; just clear the prompt without re-sending. */
    fun dismissAsk() { pendingAsk.value = null }

    /** Switch the execution/permission mode — applied on the next turn (issue #84), never interrupting a
     *  running turn (the daemon relaunches Claude before the next send; Codex carries it in-turn). */
    fun switchMode(m: PermissionMode) {
        val c = convoId.value ?: return
        if (m == mode.value) return
        mode.value = m
        switching.value = true
        scope.launch {
            send(SwitchMode(c, m))
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
    }

    /** Switch reasoning effort — routed through the daemon's `/effort` interception; applied on the next
     *  turn (issue #84), never interrupting a running one. */
    fun switchEffort(level: String) {
        val target = level.trim().lowercase()
        if (convoId.value == null || target.isEmpty() || target == effort.value) return
        effort.value = target // optimistic; the daemon's next SessionLive corrects it
        switchViaCommand("/effort $target")
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
        messages.clear(); chatTitle.value = null; contextUsed.value = null
        resetHistoryPaging() // #147: the wiped transcript's cursor dies with it
        clearBackgroundJobs()
        scope.launch { send(SendPrompt(c, "/clear")) }
    }

    /** True when `/simplify` is an available command in this workdir (gates the quick-action row). */
    fun hasSimplify(): Boolean = slashCommands.any { it.name == "simplify" }

    fun clearRule(rule: String) {
        allowRules.remove(rule)
        convoId.value?.let { c -> scope.launch { send(ClearAllowRule(c, rule)) } }
    }

    fun clearAllRules() {
        allowRules.clear()
        convoId.value?.let { c -> scope.launch { send(ClearAllowRule(c, null)) } }
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
        val c = convoId.value
        val dir = sessionsDir.value // non-null = we land on the session list: re-pull it so the rows reflect this session's run
        // observing or idle -> reclaim; still executing -> leave it running in the background.
        // One coroutine for both sends: the re-list must see the close, not race it.
        val close = c != null && (observing.value || !streaming.value)
        scope.launch {
            if (close && c != null) send(CloseSession(c))
            dir?.let { send(ListSessions(it)) }
        }
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
        switchingSession.value = convoId.value != null
        sessionsDir.value = item.dirKey
        listSessions(item.dirKey) // freshen that project's list so the back trip doesn't show the old one's
        // Optimistic touch so the sheet re-orders under the tap. The daemon's SessionLive re-touches with
        // the authoritative id right after (a fork or lock-heal can hand back a different one), so a
        // wrong guess self-corrects instead of sticking in the MRU.
        rememberOpenedSession(item.dirKey, item.sessionId, item.title, item.agent)
        openSession(item.dirKey, item.sessionId, title = item.title, agent = item.agent ?: defaultAgent.value)
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
            messages.clear(); convoId.value = null; observing.value = false
            resetHistoryPaging() // #147: the take-over open replays in full
            // "Continue here" resumes under the Settings default mode — omitting it fell back to the
            // wire default (ask each step), ignoring the user's chosen mode (issue #50). Model/effort
            // still restore per-session, same as openSession.
            val saved = sessionParams[sid]
            val agent = saved?.agent ?: sessionAgent.value ?: AgentKind.CLAUDE
            mode.value = defaultMode.value
            send(OpenSession(wd, sid, model = compatibleModelForAgent(agent, saved?.model), mode = defaultMode.value, effort = saved?.effort ?: defaultEffort.value, takeOver = true, lastEventSeq = lastEventSeqFor(sid)))
        }
    }

    /** Explicitly end the session now (force-reclaim the claude process), even if it is still running. */
    fun stopSession() {
        convoId.value?.let { c -> scope.launch { send(CloseSession(c)) } }
        streaming.value = false
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
        sessionsDir.value = null
        sessions.clear()
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
        const val SOCKET_RETIRE_TIMEOUT_MS = 3_000L // #142: bounded wait for the old socket to really close before dialing anew
        const val TRANSPORT_COALESCE_MS = 3_000L    // #143: reconnect triggers within this of an in-flight attempt merge into it
        const val STABLE_LINK_RESET_MS = 60_000L    // #144: the retry ladder resets only after the link stays up this long

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
        const val K_DEFAULT_MODE = "default_session_mode" // SecureStore: PermissionMode.name seeding new sessions (default DEFAULT)
        const val K_DEFAULT_EFFORT = "default_session_effort" // SecureStore: effort level for new sessions ("" = model default)
        const val K_DEFAULT_MODEL = "default_session_model"   // SecureStore: model id for new Claude sessions ("" = CLI default)
        const val K_CONTEXT_WINDOW_OVERRIDE = "context_window_override" // SecureStore: LEGACY global statusline denominator in tokens ("" = follow derived window); now the fallback tier under K_CONTEXT_WINDOW_OVERRIDES
        const val K_CONTEXT_WINDOW_OVERRIDES = "context_window_overrides" // SecureStore: TSV modelId\ttokens per line — per-model denominators (issue #169)
        const val K_DEFAULT_AGENT = "default_session_agent"   // SecureStore: AgentKind.name new sessions start under (default CLAUDE)
        const val K_AGENT_FILTER = "sessions_agent_filter"    // SecureStore: "both" | "claude" | "codex" — Sessions-list filter (issue #31)
        const val K_VIEW_MODE = "projects_view_mode"          // SecureStore: "tree" | "flat" for the Projects screen
        const val K_PINNED = "pinned_projects"                 // SecureStore: '\n'-joined project paths pinned to the top
        const val K_WORKING_SET_PREFIX = "working_set_mru:"    // SecureStore: "working_set_mru:<accountId>" → TSV dirKey\tsessionId\ttitle\tproject\tat\tagent — that computer's switcher MRU (issue #165)
        const val K_DRAFT_PREFIX = "draft:"                    // SecureStore: "draft:<sessionId|convoId|workdir>" → unsent composer text for that conversation
        const val K_SESSION_PARAMS = "session_params"          // SecureStore: TSV sid\tmode\tmodel\teffort\tagent per line (last 100 sessions)
        const val K_FONT_SCALE = "chat_font_scale"            // SecureStore: chat text scale factor (Float string, default 1.0)
        const val K_THEME_MODE = "appearance_theme_mode"      // SecureStore: ThemeMode name (SYSTEM/LIGHT/DARK; issue #63)
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
