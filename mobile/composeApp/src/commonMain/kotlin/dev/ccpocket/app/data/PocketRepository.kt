package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.ensureLocalNetworkAccess
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.net.DirectE2EConnection
import dev.ccpocket.app.net.DirectUnreachableException
import dev.ccpocket.app.net.RelayAuthException
import dev.ccpocket.app.net.RelayConnection
import dev.ccpocket.app.net.RelayE2EConnection
import kotlinx.coroutines.flow.merge
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.push.PushController
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.Telemetry
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.BackgroundJob
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.SlashCommand
import dev.ccpocket.protocol.contextWindowFor
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
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
import dev.ccpocket.protocol.FetchAuthStatus
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.SetPushPrefs
import dev.ccpocket.protocol.PushPrefs
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
import dev.ccpocket.app.media.compressImage
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var useRelay = false
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
    private var retryAttempts = 0
    private var controlJob: Job? = null     // collects relay control frames (Attached/PeerPresence/AuthError)
    private var graceJob: Job? = null       // silent window before showing RelayUnreachable
    private var listWaitJob: Job? = null    // post-attach wait for the first list before assuming the computer is offline
    private var connectWatchdog: Job? = null // forces a retry if a connect wedges pre-attach (no socket error)
    private var listWaitRetried = false     // one deaf-link re-handshake per episode (see startListWait); reset on Ready
    // per-session connection bookkeeping (plain vars; [phase]/[directoriesLoaded] hold the observable truth)
    private var attachedThisSession = false // relay Attached seen (or, direct mode, socket + first Directories)
    private var daemonOffline = false       // explicit: got PeerPresence(false), or the post-attach list-wait elapsed
    private var pairingInvalid = false      // relay AuthError -> needs re-pair, never auto-retry
    private var hadReadyThisSession = false // reached Ready at least once -> a later drop shows Reconnecting
    private var relayDeadlinePassed = false // grace elapsed without attaching -> RelayUnreachable
    private var reconnectGraceJob: Job? = null // brief hold before showing the Reconnecting banner on a blip (#28)
    private var reconnectGracePassed = false   // that hold elapsed -> the Reconnecting banner may show

    // ── push notifications: register the device's APNs/FCM token so the relay can wake it while offline ──
    private var pushToken: PushToken? = null
    private var pushStarted = false
    private var pushRegistered: Pair<String, String>? = null // last (platform, token) sent; skip redundant re-sends
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
     *  window, so [contextWindowFor] falls back to 200k and the % reads wrong (issue #60). Global — one value for
     *  every session — and applied AHEAD of the daemon's SessionLive.contextWindow, which for Claude is never null. */
    val contextWindowOverride = mutableStateOf(SecureStore.getString(K_CONTEXT_WINDOW_OVERRIDE)?.toLongOrNull())

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
    val messages = mutableStateListOf<ChatItem>()
    val pendingImages = mutableStateListOf<PendingImage>() // photos staged in the composer (pre-send)
    private var pendingIdSeq = 0L
    val convoId = mutableStateOf<String?>(null)
    val workdir = mutableStateOf<String?>(null)
    val chatTitle = mutableStateOf<String?>(null)            // session title for the chat header (client-side)
    private var thinkStartMs: Long? = null                   // first Thinking chunk of the in-progress block
    val pendingAsk = mutableStateOf<PermissionAsk?>(null)
    val slashCommands = mutableStateListOf<SlashCommand>()   // composer "/" autocomplete, pushed by the daemon
    val terminalEntries = mutableStateListOf<TerminalEntry>() // quick-terminal history for the active session (issue #3)
    val terminalBusy = mutableStateOf(false)                  // a shell command is awaiting approval/result
    val changedFiles = mutableStateListOf<ChangedFile>()      // files this session touched (issue #36) — filled on demand
    val changedFilesLoading = mutableStateOf(false)
    val changedFilesUnavailable = mutableStateOf(false)       // no reply (old daemon silently drops the frame) — distinct from "no files"
    val viewedFilePath = mutableStateOf<String?>(null)        // non-null = file viewer open (content may still be loading)
    val viewedFile = mutableStateOf<FileContent?>(null)       // the loaded content; ok=false carries a user-facing error
    val viewedFileDiff = mutableStateOf<FileDiff?>(null)      // the loaded line-level diff; ok=false = none/too-old daemon
    val pathListing = mutableStateOf<PathEntries?>(null)     // latest @-file completion listing (issue #75); match its subPath before use
    val mode = mutableStateOf(PermissionMode.DEFAULT)        // current execution/permission mode
    val model = mutableStateOf<String?>(null)                // daemon's actual model for this session (header + info sheet)
    val sessionAgent = mutableStateOf<AgentKind?>(null)      // backend driving this session (Claude/Codex) — header badge
    val effort = mutableStateOf<String?>(null)               // reasoning effort: low|medium|high|xhigh|max (null = default)
    val contextWindow = mutableStateOf<Long?>(null)          // context capacity in tokens (derived from model if daemon omits it)
    val contextUsed = mutableStateOf<Long?>(null)            // ~tokens occupying the window (from the last turn's usage)

    /** Observed occupancy beyond the declared window PROVES a bigger one (beta-1M, or an alias the window
     *  table didn't know — a `/model fable` session once pinned the statusline at 100% mid-1M-session).
     *  The rule itself lives in ONE place — [dev.ccpocket.protocol.provenWindow] (daemon announce paths
     *  call it too); this is the phone's defensive re-check against old daemons. Codex sessions
     *  keep window=null (raw-token display) and are untouched. */
    private fun upgradeWindowIfProven() {
        val win = contextWindow.value ?: return
        contextWindow.value = dev.ccpocket.protocol.provenWindow(win, contextUsed.value)
    }
    val backgroundJobs = mutableStateListOf<BackgroundJob>() // bg shells / sub-agents / monitors the daemon is tracking
    val allowRules = mutableStateListOf<String>()            // "Always allow" scopes remembered this session
    val switching = mutableStateOf(false)                    // a mode switch is relaunching the session
    val opening = mutableStateOf(false)                      // an OpenSession is in flight — one-tap entries disable on it (a double-tap would open two fresh sessions)
    val openTimedOut = mutableStateOf(false)                 // the daemon never answered an OpenSession within 8s — slim banner, auto-dismissed (issue #41)
    private var openGen = 0                                  // generation counter matching each openSession call to its own safety-net timer
    val autoFocusComposer = mutableStateOf(false)            // brand-new session: ChatScreen raises the keyboard once on landing (consumed there)
    val streaming = mutableStateOf(false)
    val observing = mutableStateOf(false) // viewing a session running outside the daemon (read-only tail)
    private var currentSessionId: String? = null

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

    /** The daemon flagged this session degraded (recent turns were all API-failure placeholders — issue #65). */
    val sessionDegraded = mutableStateOf(false)
    // first send into a degraded session is blocked with an explanation; the next one goes through
    private var degradedSendArmed = false
    private var turnStartMark: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null // stamps TurnEnded's duration

    /** Desktop notifier seam: fires when the active conversation's turn completes (after the TurnEnded
     *  marker lands). The UI layer decides whether that deserves a system notification / dock badge. */
    var onTurnFinished: ((title: String, preview: String?) -> Unit)? = null

    /** First daemon evidence for the in-flight prompt (chunk/tool/turn-end/error): drop the retry copy
     *  and flip the pending User bubble to delivered (issue #41). */
    private fun promptEvidence() {
        promptRetry = null
        promptWatchdog?.cancel(); promptWatchdog = null // the daemon is talking — the receipt deadline is moot
        sendStalled.value = false
        if (!promptPending) return
        promptPending = false
        val i = messages.indexOfLast { it is ChatItem.User }
        (messages.getOrNull(i) as? ChatItem.User)?.takeIf { it.pending }?.let { messages[i] = it.copy(pending = false) }
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
            is Attached -> { attachedThisSession = true; connected.value = true; relayDeadlinePassed = false; ensurePushStarted(); registerPush(); startListWait(); recomputePhase() }
            // Only re-handshake on a genuine offline->online transition. The relay re-broadcasts
            // PeerPresence(true) on every daemon (re)attach; a redundant true must NOT tear down a healthy
            // transport (that surfaced as a spurious Reconnecting banner when opening a session).
            is PeerPresence -> { val wasOffline = daemonOffline; daemonOffline = !f.online; if (f.online && wasOffline) onComputerBackOnline(); recomputePhase() }
            is AuthError -> { pairingInvalid = true; retryJob?.cancel(); recomputePhase() }
            else -> {}
        }
    }

    /** The computer (re)attached. A restarted/reconnected daemon has a fresh E2E session and no memory of
     *  the old Noise session, so re-handshake the whole transport — re-sending over the stale session would
     *  just hit "transport before handshake" at the daemon. launchTransport(reconnect) re-syncs the page. */
    private fun onComputerBackOnline() {
        launchTransport(reconnect = true)
    }

    // ── push registration ───────────────────────────────────────────────────────────────────────────

    /** Start platform push registration once, after the first relay attach (so the iOS permission prompt
     *  follows pairing). The token callback may land later — [registerPush] also runs on every Attached. */
    private fun ensurePushStarted() {
        if (pinnedTo != null) return // satellites leave the platform push singleton to the primary link
        if (pushStarted || !notificationsOn.value) return
        pushStarted = true
        PushController.start { token -> pushToken = token; registerPush() }
    }

    /** (Re)send the push token to the relay over the control plane — or an empty token to de-register when
     *  notifications are off. Skips when unchanged since the last send (the relay persists it), so the
     *  foreground-triggered reconnect storm doesn't rewrite the same row. Relay transport only. */
    private fun registerPush() {
        if (!useRelay || directE2E.connected) return // direct transport has no relay control plane; register on the next real relay attach
        val tok = pushToken ?: return
        val sent = tok.platform to (if (notificationsOn.value) tok.token else "")
        if (sent == pushRegistered) return
        pushRegistered = sent
        scope.launch { runCatching { relay.sendControl(RegisterPush(sent.first, sent.second)) } }
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
        // reflect on the live statusline immediately — mirror SessionLive's derive + proven-window upgrade so a
        // mid-session change isn't invisible until reopen (clearing falls back to the model-derived window, which
        // for a Claude session equals the daemon's value; a custom id resolves to 200k either way)
        if (convoId.value != null) {
            val claudeish = (sessionAgent.value ?: AgentKind.CLAUDE) == AgentKind.CLAUDE
            contextWindow.value = v ?: (if (claudeish) contextWindowFor(model.value) else null)
            upgradeWindowIfProven() // keep the proven-window rule in ONE place instead of re-inlining provenWindow
        }
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
                    launchTransport(reconnect = true)
                }
            }
        }
    }

    /** (Re)open the active transport's socket. Both transports re-handshake on every connect() call. */
    private fun launchTransport(reconnect: Boolean) {
        if (demoMode.value) return // demo mode never touches the network
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
        connectJob?.cancel()
        connectJob = scope.launch {
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
        scope.launch { send(ListDirectories()); if (reconnect) restoreAfterReconnect() }
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

    /** App came to the foreground (iOS suspends sockets in background) — reconnect now, fresh backoff. */
    fun onAppForeground() {
        if (demoMode.value || pairingInvalid) return
        if (sessionActive.value && !connected.value) {
            retryJob?.cancel()
            retryAttempts = 0
            if (hadReadyThisSession) startReconnectGrace(restart = true) // fresh Ready-hold on return so a quick reconnect shows no banner (#28)
            launchTransport(reconnect = true)
        } else if (sessionActive.value && connected.value) {
            // `connected` may be a lie after a background suspension (the heartbeat was frozen; the TCP died
            // silently). Exercise a WRITE right now: healthy link → this merely refreshes the stale project
            // list; wedged link → the bounded send trips DeadLink in ≤10s instead of ~25s of fake Ready.
            refreshDirectoriesSilently()
        }
    }

    /** Manual "Try again" from the RelayUnreachable / ComputerOffline screens. */
    fun retryConnection() {
        if (!sessionActive.value || pairingInvalid) return
        retryJob?.cancel()
        retryAttempts = 0
        relayDeadlinePassed = false
        launchTransport(reconnect = true)
    }

    /** After the link is back: re-sync whatever page the user is parked on; reattach a live chat. */
    private suspend fun restoreAfterReconnect() {
        val sid = currentSessionId
        val wd = workdir.value
        val dir = sessionsDir.value
        when {
            // daemon finds the still-live conversation by sessionId → reattach + history replay
            convoId.value != null && !observing.value && sid != null && wd != null ->
                send(OpenSession(wd, sid, mode = mode.value, agent = sessionAgent.value ?: AgentKind.CLAUDE))
            dir != null -> send(ListSessions(dir))
            else -> {} // directory list already refreshed by launchTransport
        }
    }

    /** Drop the live connection and return to the Connect screen (pairing is kept). */
    fun disconnect() {
        sessionActive.value = false
        retryJob?.cancel(); connectJob?.cancel(); inboundJob?.cancel(); controlJob?.cancel(); graceJob?.cancel(); listWaitJob?.cancel(); connectWatchdog?.cancel(); reconnectGraceJob?.cancel()
        retryJob = null; connectJob = null; inboundJob = null; controlJob = null; graceJob = null; listWaitJob = null; connectWatchdog = null; reconnectGraceJob = null
        promptWatchdog?.cancel(); promptWatchdog = null; sendStalled.value = false // pending bubbles leave with messages below
        // frames queued for the binding we're leaving must not leak into the next link (both transports
        // are reused across machine switches, and their outboxes deliberately buffer across reconnects)
        directAttemptInFlight = false
        directE2E.drainPending(); relay.drainPending()
        connected.value = false
        phase.value = ConnPhase.Connecting
        pendingOpen = null // a queued push-tap target is moot once the user drops the connection
        attachedThisSession = false; daemonOffline = false; pairingInvalid = false
        hadReadyThisSession = false; relayDeadlinePassed = false; reconnectGracePassed = false; listWaitRetried = false; directoriesLoaded.value = false
        pushRegistered = null // a fresh connect (or a switched daemon) must re-register the token
        convoId.value = null
        sessionsDir.value = null
        workdir.value = null // clear with the rest so a stale path can't leak into the next machine's ⌘N (issue #56)
        pendingAsk.value = null
        directories.clear(); sessions.clear(); messages.clear(); pendingImages.clear(); clearBackgroundJobs()
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

    /** Switch the active computer: tear down the current link, pin [target], reconnect to it. */
    fun switchDaemon(target: PairedDaemon) {
        if (paired.value?.accountId == target.accountId && sessionActive.value) return
        onBeforeSwitch?.invoke(target.accountId)
        disconnect()
        paired.value = target
        Pairing.setActive(target.accountId)
        firstTicket = null // an already-paired daemon authenticates by static key — the PSK is only for first pair
        startRelay()
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
        if (wasActive) { disconnect(); paired.value = remaining.lastOrNull() }
    }

    /** Remove the currently active binding (the "re-pair" escape hatch when a pairing goes invalid). */
    fun unpairActive() { paired.value?.let { unpair(it) } }

    /** All outbound frames funnel here; a throw means the link is dead — trigger the reconnect path. */
    private suspend fun send(frame: Frame) {
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
            else -> {} // CloseSession / ClearAllowRule / SwitchDirectory / AudioCancel — nothing to echo
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

    private fun handle(f: Frame) {
        when (f) {
            is Directories -> {
                replace(directories, f.entries); refreshing.value = false
                directoriesLoaded.value = true; daemonOffline = false; listWaitJob?.cancel() // a reply proves the computer is online
                if (!useRelay) attachedThisSession = true // direct mode: socket + data == attached
                connected.value = true; relayDeadlinePassed = false
                retryAttempts = 0 // a healthy round-trip restarts the backoff ladder — otherwise a flapping link climbs to 30s and every reconnect crawls
                if (!hadReadyThisSession) {
                    hadReadyThisSession = true
                    Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to transportName()))
                }
                recomputePhase()
            }
            is Sessions -> { sessionsDir.value = f.workdir; replace(sessions, f.items); sessionsRefreshing.value = false }
            is Usage -> { usage.value = f; usageLoading.value = false }
            is AuthState -> authState.value = f
            is PushPrefs -> pushPrefs.value = f.enabled
            // the daemon told us where it lives on the LAN — persist per binding; the next connect (this
            // repo OR a rebuilt fleet satellite reading the same store) dials it before the relay. An
            // address that already answered with the WRONG daemon key stays blacklisted — the daemon
            // re-advertises the same value on every handshake, which must not resurrect a dead probe.
            is DaemonInfo -> paired.value?.let { p ->
                if (p.directUrl != f.lanUrl && (f.lanUrl == null || f.lanUrl != badDirectUrl[p.accountId])) {
                    rememberDirectUrl(p.accountId, f.lanUrl)
                }
                // adopt the daemon's real computer name as this binding's default display name (issue #62);
                // a user-set nickname still wins in displayName(). Independent of the directUrl guard above.
                if (!f.hostname.isNullOrBlank() && f.hostname != p.hostName) rememberHostName(p.accountId, f.hostname)
            }
            is SessionLive -> {
                migrateDraft(f.sessionId) // before re-keying: composerKey() still reads the old chain
                convoId.value = f.convoId; workdir.value = f.workdir; observing.value = f.observing; currentSessionId = f.sessionId
                f.sessionId?.let { sessionKey.value = it }
                f.mode?.let { mode.value = it } // daemon is the source of truth — corrects the optimistic badge
                f.model?.let { model.value = it }
                f.effort?.let { effort.value = it }
                f.agent?.let { sessionAgent.value = it } // daemon truth for the backend badge
                // window fallback is Claude-only: contextWindowFor knows nothing about gpt-* ids, and a Codex
                // session with no daemon-sent window was rendering a % against a meaningless Claude 200k —
                // null instead, and the UI shows raw tokens without a denominator
                val claudeish = (f.agent ?: sessionAgent.value ?: AgentKind.CLAUDE) == AgentKind.CLAUDE
                // the user's override wins over the daemon's value (for Claude, f.contextWindow is never null and
                // would otherwise pin a custom model at the CLI's 200k fallback — issue #60)
                contextWindow.value = contextWindowOverride.value ?: f.contextWindow ?: (if (claudeish) contextWindowFor(f.model ?: model.value) else null)
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
                opening.value = false // the open (or reattach) landed
                openTimedOut.value = false
                // remember this session's launch flags so a close+reopen cycle can restore (and relaunch under) them
                f.sessionId?.let { sessionParams[it] = SessionParams(mode.value, model.value, effort.value, sessionAgent.value ?: AgentKind.CLAUDE) }
                persistSessionParams() // survive app restarts too — reopening tomorrow restores mode/effort/agent
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
                promptEvidence()
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
                if (pendingAsk.value?.questions != null) messages.add(ChatItem.QuestionsWithdrawn)
                pendingAsk.value = null
            }
            is TurnDone -> if (f.convoId == convoId.value) {
                promptEvidence()
                val turnWasLive = streaming.value // gate the marker/notify on a turn we actually watched run
                finishThinking(); streaming.value = false
                // a FAILED turn (API error / synthetic placeholder — issue #65): show the error row where
                // the reply would be; no green ✓ marker for a turn that produced nothing
                f.error?.let { messages.add(ChatItem.Sys(it)) }
                if (turnWasLive) {
                    if (f.error == null) messages.add(ChatItem.TurnEnded(turnStartMark?.elapsedNow()?.inWholeSeconds?.toInt()))
                    turnStartMark = null
                    val preview = f.error ?: (messages.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)
                        ?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(140)
                    onTurnFinished?.invoke(chatTitle.value ?: workdir.value?.substringAfterLast('/') ?: "CC Pocket", preview)
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
            is PocketError -> {
                opening.value = false // a failed open re-enables the one-tap entries right away
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
                        scope.launch { send(OpenSession(wd, sid, mode = mode.value, agent = sessionAgent.value ?: AgentKind.CLAUDE)) }
                    } else {
                        promptEvidence(); promptResendArmed = false
                        finishThinking(); streaming.value = false
                        messages.add(ChatItem.Sys("session expired on the computer — send again to restart it"))
                    }
                }
            }
            // also convo-scoped: a stale ConvoHistory would wipe the active convo and load the wrong transcript
            is ConvoHistory -> if (f.convoId == convoId.value) { messages.clear(); messages.addAll(f.messages.map(::historyItem)) }
            is CommandList -> if (f.convoId == convoId.value) replace(slashCommands, f.commands)
            is Transcript -> onTranscript(f)
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
                viewedFile.value = f
            }
            is FileDiff -> if (f.path == viewedFilePath.value && f.workdir == workdir.value && f.sessionId == (sessionKey.value ?: currentSessionId)) {
                viewedFileDiff.value = f
            }
            // @-file completion (issue #75): keyed on workdir, not a session id — it browses the cwd, not a
            // session's changed set. A reply for a workdir we've since left is dropped (the completer keys
            // the visible listing on its own requested subPath anyway).
            is PathEntries -> if (f.workdir == workdir.value) pathListing.value = f
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
        // ok/output: a completed sub-agent card keeps its outcome + expandable report across replays (issue #77)
        ChatRole.TOOL -> ChatItem.Tool(h.tool ?: "tool", h.text, ok = h.ok, output = h.output)
    }

    private fun <T> replace(list: MutableList<T>, items: List<T>) {
        list.clear(); list.addAll(items)
    }

    private fun clearBackgroundJobs() = replace(backgroundJobs, emptyList())

    private fun appendChunk(c: AssistantChunk) {
        streaming.value = true
        when (val p = c.piece) {
            is StreamPiece.Text -> {
                finishThinking() // prose starting = the thinking block (if any) is done
                val last = messages.lastOrNull()
                if (last is ChatItem.Assistant) messages[messages.lastIndex] = last.copy(text = last.text + p.text)
                else messages.add(ChatItem.Assistant(p.text))
            }
            is StreamPiece.Thinking -> {
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

    /** Token-usage dashboard (issue #26): the latest daemon-aggregated snapshot + a fetch-in-flight flag. */
    val usage = mutableStateOf<Usage?>(null)
    val usageLoading = mutableStateOf(false)

    /** Ask the daemon to aggregate usage over the last [days] local days; the reply lands in [usage]. */
    fun fetchUsage(days: Int = 7) {
        usageLoading.value = true
        scope.launch { send(FetchUsage(days)) }
    }

    fun listSessions(wd: String) = scope.launch { send(ListSessions(wd)) }

    /** Pull-to-refresh spinner for the sessions list (mirrors [refreshing] for the project list). */
    val sessionsRefreshing = mutableStateOf(false)

    /** Re-scan a project's sessions with the pull-to-refresh spinner ([wd] defaults to the open list's dir). */
    fun refreshSessions(wd: String? = null) {
        val dir = wd ?: sessionsDir.value ?: return
        refreshWithSpinner(sessionsRefreshing, ListSessions(dir))
    }
    // startMode defaults to the persisted default mode (mirrors effort), so tapping a session straight from
    // the list applies it too — not just the new-session picker.
    fun openSession(wd: String, resumeId: String? = null, startMode: PermissionMode = defaultMode.value, title: String? = null, agent: AgentKind = defaultAgent.value) = scope.launch {
        opening.value = true // held until the daemon answers (SessionLive/PocketError) — 8s net below
        openTimedOut.value = false
        promptPending = false // the pending marker belongs to the previous conversation's transcript
        promptWatchdog?.cancel(); promptWatchdog = null; sendStalled.value = false // and so does its receipt deadline
        sessionDegraded.value = false; degradedSendArmed = false // per-session — SessionLive re-announces the truth
        val gen = ++openGen // ties the 8s safety net below to THIS open — a quick second open isn't cleared by the first one's timer
        // Reclaim the current session ONLY if it's idle (or a read-only observe): a RUNNING turn stays
        // alive in the background — same rule as backToBrowse. Desktop switches sessions directly
        // (sidebar click → here, no backToBrowse in between), so an unconditional close was killing
        // the previous session's in-flight work on every switch. Switching back later resumes by
        // sessionId and reattaches the still-live conversation (registry live-match), no fork.
        convoId.value?.let { if (observing.value || !streaming.value) send(CloseSession(it)) }
        messages.clear(); convoId.value = null
        sessionKey.value = resumeId // durable draft key known immediately on resume; null for a brand-new session
        terminalEntries.clear(); terminalBusy.value = false // the quick-terminal scrollback is per-session
        changedFiles.clear(); changedFilesLoading.value = false; closeFileViewer() // changed-files view is per-session too
        streaming.value = false // the previous session's in-flight turn must not leak the ■ button
        pendingAsk.value = null
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
        // never inherits the (Claude-shaped) default id — it would be a meaningless --model to the Codex backend
        val openModel = saved?.model ?: defaultModel.value?.takeIf { openAgent == AgentKind.CLAUDE }
        mode.value = openMode; allowRules.clear()
        model.value = openModel; effort.value = openEffort; contextUsed.value = null // reconciled by SessionLive
        sessionAgent.value = openAgent // optimistic; SessionLive corrects from daemon truth
        clearBackgroundJobs()
        Telemetry.track(TelEvent.SessionOpened, mapOf(TelKey.Resume to if (resumeId != null) 1 else 0))
        send(OpenSession(wd, resumeId, model = openModel, mode = openMode, effort = openEffort, agent = openAgent))
        delay(8000) // safety: clear if the daemon never answers (matches `switching`)
        if (gen == openGen && opening.value) {
            opening.value = false
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
        val ready = pendingImages.filter { it.state == ImgState.Ready }.map { it.bytes }
        if (text.isBlank() && ready.isEmpty()) return false
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
        val promptId = newPromptId()
        messages.add(ChatItem.User(text, ready, pending = true, promptId = promptId))
        promptPending = true
        turnStartMark = kotlin.time.TimeSource.Monotonic.markNow()
        if (chatTitle.value == null && text.isNotBlank()) chatTitle.value = text.take(48) // new session: first prompt becomes the header title
        pendingImages.clear()
        streaming.value = true
        workdir.value?.let { promptRetry = PromptRetry(text, images, it, promptId); promptResendArmed = false }
        Telemetry.track(TelEvent.PromptSent)
        scope.launch { send(SendPrompt(c, text, images, promptId = promptId)) }
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

    /** Open one changed file in the viewer; the daemon replies with a capped [FileContent] and,
     *  when its transcript has line-level data, a [FileDiff] — both requested up front because the
     *  viewer's default tab is the diff and the flip to full content should be instant. Images get
     *  no [ReadFileDiff]: there is no text diff, and the request would cost the daemon a full
     *  transcript re-scan just to say so. */
    fun openChangedFile(path: String) {
        val wd = workdir.value ?: return
        val sid = sessionKey.value ?: currentSessionId ?: return
        val wantDiff = !isImageFile(path)
        viewedFilePath.value = path
        viewedFile.value = null // show the loading state, not the previous file
        viewedFileDiff.value = null
        // ONE deadline arms both replies; each check no-ops once its reply landed, so a daemon that
        // answers ReadFile but predates ReadFileDiff still gets the honest "needs a newer daemon" state.
        viewedFileDeadline?.cancel()
        viewedFileDeadline = scope.launch {
            delay(8_000)
            if (viewedFilePath.value != path) return@launch
            if (viewedFile.value == null) {
                viewedFile.value = FileContent(wd, sid, path, ok = false, error = "no reply from the computer — the daemon may be too old for this")
            }
            if (wantDiff && viewedFileDiff.value == null) {
                viewedFileDiff.value = FileDiff(wd, sid, path, ok = false, error = DIFF_ERROR_STALE_DAEMON)
            }
        }
        val agent = sessionAgent.value ?: AgentKind.CLAUDE
        scope.launch {
            send(ReadFile(wd, sid, path, agent))
            if (wantDiff) send(ReadFileDiff(wd, sid, path, agent))
        }
    }

    fun closeFileViewer() {
        viewedFileDeadline?.cancel()
        viewedFilePath.value = null; viewedFile.value = null; viewedFileDiff.value = null
    }

    /** Ask the daemon for the children under the open session's cwd + [subPath] (relative, daemon-native
     *  separators) for the composer's @-file completion (issue #75). The reply lands in [pathListing];
     *  the completer only uses it once its subPath matches what it asked for. No-op with no open workdir. */
    fun browseFiles(subPath: String) {
        val wd = workdir.value ?: return
        scope.launch { send(ListPathEntries(wd, subPath)) }
    }

    // ── voice input actions ───────────────────────────────────────────────

    /** Mic tap (S1). Picks the engine: iOS native streaming dictation, else record→daemon-whisper. */
    fun startVoice() {
        if (convoId.value == null) return
        if (voice.value !is VoiceState.Idle && voice.value !is VoiceState.Failed) return
        clearNotice()
        voiceLevels.clear()
        if (NativeDictation.available && !preferRemote) startNativeVoice() else startRemoteVoice()
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

    /** Switch the model — routed through the daemon's `/model` interception; applied on the next turn
     *  (issue #84), never interrupting a running one. */
    fun switchModel(name: String) {
        val target = name.trim()
        if (convoId.value == null || target.isEmpty() || target == model.value) return
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
        pendingImages.clear()
        clearBackgroundJobs()
        observing.value = false
        abandonVoice()
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
            // "Continue here" resumes under the Settings default mode — omitting it fell back to the
            // wire default (ask each step), ignoring the user's chosen mode (issue #50). Model/effort
            // still restore per-session, same as openSession.
            val saved = sessionParams[sid]
            mode.value = defaultMode.value
            send(OpenSession(wd, sid, model = saved?.model, mode = defaultMode.value, effort = saved?.effort ?: defaultEffort.value, takeOver = true))
        }
    }

    /** Explicitly end the session now (force-reclaim the claude process), even if it is still running. */
    fun stopSession() {
        convoId.value?.let { c -> scope.launch { send(CloseSession(c)) } }
        streaming.value = false
        convoId.value = null
        chatTitle.value = null
        messages.clear()
        pendingImages.clear()
        clearBackgroundJobs()
        abandonVoice()
    }

    fun backToDirectories() {
        sessionsDir.value = null
        sessions.clear()
    }

    private companion object {
        const val FIRST_GRACE_MS = 2_000L     // first connect: show the skeleton this long before "can't reach server"
        const val RECONNECT_GRACE_MS = 6_000L // a reconnect already keeps the old list under a banner
        const val RECONNECT_BANNER_GRACE_MS = 2_500L // hold the Ready look this long on a blip before the Reconnecting banner (#28)
        const val LIST_WAIT_MS = 6_000L       // after Attached, wait this long for the list before "computer offline"
        const val CONNECT_TIMEOUT_MS = 12_000L // no Attached within this → treat the connect as wedged, force a retry
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

        const val K_NOTIFY = "notify_on_complete"    // SecureStore flag: "0" = task-complete push off (default on)
        const val K_DEFAULT_MODE = "default_session_mode" // SecureStore: PermissionMode.name seeding new sessions (default DEFAULT)
        const val K_DEFAULT_EFFORT = "default_session_effort" // SecureStore: effort level for new sessions ("" = model default)
        const val K_DEFAULT_MODEL = "default_session_model"   // SecureStore: model id for new Claude sessions ("" = CLI default)
        const val K_CONTEXT_WINDOW_OVERRIDE = "context_window_override" // SecureStore: statusline denominator in tokens ("" = follow derived window)
        const val K_DEFAULT_AGENT = "default_session_agent"   // SecureStore: AgentKind.name new sessions start under (default CLAUDE)
        const val K_AGENT_FILTER = "sessions_agent_filter"    // SecureStore: "both" | "claude" | "codex" — Sessions-list filter (issue #31)
        const val K_VIEW_MODE = "projects_view_mode"          // SecureStore: "tree" | "flat" for the Projects screen
        const val K_PINNED = "pinned_projects"                 // SecureStore: '\n'-joined project paths pinned to the top
        const val K_DRAFT_PREFIX = "draft:"                    // SecureStore: "draft:<sessionId|convoId|workdir>" → unsent composer text for that conversation
        const val K_SESSION_PARAMS = "session_params"          // SecureStore: TSV sid\tmode\tmodel\teffort\tagent per line (last 100 sessions)
        const val K_FONT_SCALE = "chat_font_scale"            // SecureStore: chat text scale factor (Float string, default 1.0)
        const val K_THEME_MODE = "appearance_theme_mode"      // SecureStore: ThemeMode name (SYSTEM/LIGHT/DARK; issue #63)
        const val FONT_SCALE_MIN = 0.85f                       // smallest chat text scale (Settings slider lower bound)
        const val FONT_SCALE_MAX = 1.4f                        // largest chat text scale (eye-comfort upper bound)
    }
}

private const val REFRESH_SPINNER_SAFETY_MS = 4_000L // spinner never outlives a lost reply by more than this
