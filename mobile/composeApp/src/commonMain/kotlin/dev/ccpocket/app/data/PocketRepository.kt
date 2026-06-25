package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.ensureLocalNetworkAccess
import dev.ccpocket.app.net.RelayAuthException
import dev.ccpocket.app.net.RelayConnection
import dev.ccpocket.app.net.RelayE2EConnection
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.push.PushController
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.Telemetry
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.BackgroundJob
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.SlashCommand
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.ShellResult
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.TurnDone
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
    data class User(val text: String, val images: List<ByteArray> = emptyList()) : ChatItem
    data class Assistant(val text: String) : ChatItem

    /** Extended reasoning, rendered as a collapsible row. [seconds] lands when thinking finishes (null while streaming). */
    data class Thinking(val text: String, val seconds: Int? = null) : ChatItem
    data class Tool(val tool: String, val preview: String) : ChatItem
    data class Sys(val text: String) : ChatItem
    data class RuleChip(val rule: String) : ChatItem // "Always allowing X this session" confirmation
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

/** State hub: consumes inbound [Frame]s into observable Compose state, exposes user actions. */
class PocketRepository(private val scope: CoroutineScope) {
    private val direct = RelayConnection()
    private val relay = RelayE2EConnection()
    private var useRelay = false
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
    // per-session connection bookkeeping (plain vars; [phase]/[directoriesLoaded] hold the observable truth)
    private var attachedThisSession = false // relay Attached seen (or, direct mode, socket + first Directories)
    private var daemonOffline = false       // explicit: got PeerPresence(false), or the post-attach list-wait elapsed
    private var pairingInvalid = false      // relay AuthError -> needs re-pair, never auto-retry
    private var hadReadyThisSession = false // reached Ready at least once -> a later drop shows Reconnecting
    private var relayDeadlinePassed = false // grace elapsed without attaching -> RelayUnreachable

    // ── push notifications: register the device's APNs/FCM token so the relay can wake it while offline ──
    private var pushToken: PushToken? = null
    private var pushStarted = false
    private var pushRegistered: Pair<String, String>? = null // last (platform, token) sent; skip redundant re-sends
    /** Task-complete push toggle (persisted, default on); the single source of truth the Settings switch binds to. */
    val notificationsOn = mutableStateOf(SecureStore.getString(K_NOTIFY) != "0")

    /** Persisted default execution mode for NEW sessions (Settings binds to it; the new-session picker pre-selects it).
     *  Resumed sessions keep their own remembered mode — this only seeds brand-new ones. Default: Ask each step. */
    val defaultMode = mutableStateOf(
        SecureStore.getString(K_DEFAULT_MODE)?.let { s -> PermissionMode.entries.firstOrNull { it.name == s } } ?: PermissionMode.DEFAULT,
    )

    /** Persisted default reasoning effort for NEW sessions (null = the model's own default). Resumed sessions
     *  keep their own. Stored as "" for the null/default choice (SecureStore can't hold null). */
    val defaultEffort = mutableStateOf(SecureStore.getString(K_DEFAULT_EFFORT)?.takeIf { it.isNotEmpty() })

    /** Persisted default agent backend for NEW sessions (Claude unless the user switched to Codex). Resumed
     *  sessions keep their own backend (the picker only seeds new ones). */
    val defaultAgent = mutableStateOf(
        SecureStore.getString(K_DEFAULT_AGENT)?.let { s -> AgentKind.entries.firstOrNull { it.name == s } } ?: AgentKind.CLAUDE,
    )

    /** Projects screen: tree (drill-down) vs flat. Persisted (default tree). */
    val treeView = mutableStateOf(SecureStore.getString(K_VIEW_MODE) != "flat")

    /** Chat text scale (FONT_SCALE_MIN..MAX), persisted. 1.0 = the design's default sizes; bumped for eye comfort
     *  on small screens (issue #8). Threaded into every message via LocalFontScale. */
    val fontScale = mutableStateOf(
        SecureStore.getString(K_FONT_SCALE)?.toFloatOrNull()?.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX) ?: 1f,
    )

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

    /** Composer draft persisted per working directory: a half-typed message survives leaving the chat
     *  (or backgrounding the app) and returns on reopening that project. Blank clears it; sending clears it. */
    fun draftFor(wd: String?): String = wd?.let { SecureStore.getString(K_DRAFT_PREFIX + it) } ?: ""

    fun saveDraft(wd: String?, text: String) {
        wd ?: return
        if (text.isBlank()) SecureStore.remove(K_DRAFT_PREFIX + wd) else SecureStore.putString(K_DRAFT_PREFIX + wd, text)
    }

    fun clearDraft(wd: String?) { wd?.let { SecureStore.remove(K_DRAFT_PREFIX + it) } }

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
    val paired = mutableStateOf<PairedDaemon?>(Pairing.active())
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
    val mode = mutableStateOf(PermissionMode.DEFAULT)        // current execution/permission mode
    val model = mutableStateOf<String?>(null)                // daemon's actual model for this session (header + info sheet)
    val sessionAgent = mutableStateOf<AgentKind?>(null)      // backend driving this session (Claude/Codex) — header badge
    val effort = mutableStateOf<String?>(null)               // reasoning effort: low|medium|high|xhigh|max (null = default)
    val contextWindow = mutableStateOf<Long?>(null)          // context capacity in tokens (derived from model if daemon omits it)
    val contextUsed = mutableStateOf<Long?>(null)            // ~tokens occupying the window (from the last turn's usage)
    val backgroundJobs = mutableStateListOf<BackgroundJob>() // bg shells / sub-agents / monitors the daemon is tracking
    val allowRules = mutableStateListOf<String>()            // "Always allow" scopes remembered this session
    val switching = mutableStateOf(false)                    // a mode switch is relaunching the session
    val streaming = mutableStateOf(false)
    val observing = mutableStateOf(false) // viewing a session running outside the daemon (read-only tail)
    private var currentSessionId: String? = null

    // mode/model/effort are claude launch flags, NOT stored in the transcript jsonl. Leaving an idle
    // session closes its process; reopening resumes a FRESH process that would otherwise default these.
    // Remember the last-known set per sessionId so a reopen restores the badge + relaunches under them.
    private data class SessionParams(val mode: PermissionMode, val model: String?, val effort: String?, val agent: AgentKind = AgentKind.CLAUDE)
    private val sessionParams = mutableMapOf<String, SessionParams>()

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
            hadReadyThisSession                              -> ConnPhase.Reconnecting
            relayDeadlinePassed                              -> ConnPhase.RelayUnreachable
            else                                             -> ConnPhase.Connecting
        }
        if (next != phase.value) { // emit only real transitions — the honest connection-state trail in Firebase
            phase.value = next
            Telemetry.track(TelEvent.ConnPhase, mapOf(TelKey.Phase to next.name, TelKey.Transport to transportName()))
        }
        consumePendingOpenIfReady() // a push-tap target waits here until the link is actually Ready
    }

    private fun transportName() = if (useRelay) "relay" else "direct"

    /**
     * A tapped task-complete push wants to resume a specific session. Stash it, bring the link up if the
     * app was disconnected, and open it now if we're already Ready — otherwise [recomputePhase] opens it
     * the moment the directory list lands (proving the computer is online). Idempotent for repeat taps.
     */
    fun requestOpenSession(workdir: String, sessionId: String) {
        pendingOpen = dev.ccpocket.app.SessionRoute(workdir, sessionId)
        if (demoMode.value) { pendingOpen = null; return }
        if (paired.value != null && !sessionActive.value) startRelay()
        consumePendingOpenIfReady()
    }

    private fun consumePendingOpenIfReady() {
        val t = pendingOpen ?: return
        if (phase.value != ConnPhase.Ready) return
        pendingOpen = null
        if (convoId.value != null && currentSessionId == t.sessionId) return // already in this session — don't churn it
        sessionsDir.value = null // drop any half-open session list so the chat is what shows
        openSession(t.workdir, t.sessionId)
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
        if (pushStarted || !notificationsOn.value) return
        pushStarted = true
        PushController.start { token -> pushToken = token; registerPush() }
    }

    /** (Re)send the push token to the relay over the control plane — or an empty token to de-register when
     *  notifications are off. Skips when unchanged since the last send (the relay persists it), so the
     *  foreground-triggered reconnect storm doesn't rewrite the same row. Relay transport only. */
    private fun registerPush() {
        if (!useRelay) return
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

    /** Settings: persist the default agent backend that new sessions start under. */
    fun setDefaultAgent(a: AgentKind) {
        if (a == defaultAgent.value) return
        defaultAgent.value = a
        SecureStore.putString(K_DEFAULT_AGENT, a.name)
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
                if (useRelay) relay.inbound.collect { handle(it) } else direct.inbound.collect { handle(it) }
            }
        }
        if (controlJob == null) {
            controlJob = scope.launch {
                (if (useRelay) relay.control else direct.control).collect { handleControl(it) }
            }
        }
        connectJob?.cancel()
        connectJob = scope.launch {
            val result = runCatching {
                if (useRelay) {
                    val p = paired.value ?: error("not paired")
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
            launchTransport(reconnect = true)
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
        retryJob?.cancel(); connectJob?.cancel(); inboundJob?.cancel(); controlJob?.cancel(); graceJob?.cancel(); listWaitJob?.cancel(); connectWatchdog?.cancel()
        retryJob = null; connectJob = null; inboundJob = null; controlJob = null; graceJob = null; listWaitJob = null; connectWatchdog = null
        connected.value = false
        phase.value = ConnPhase.Connecting
        pendingOpen = null // a queued push-tap target is moot once the user drops the connection
        attachedThisSession = false; daemonOffline = false; pairingInvalid = false
        hadReadyThisSession = false; relayDeadlinePassed = false; directoriesLoaded.value = false
        pushRegistered = null // a fresh connect (or a switched daemon) must re-register the token
        convoId.value = null
        sessionsDir.value = null
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

    /** Switch the active computer: tear down the current link, pin [target], reconnect to it. */
    fun switchDaemon(target: PairedDaemon) {
        if (paired.value?.accountId == target.accountId && sessionActive.value) return
        disconnect()
        paired.value = target
        Pairing.setActive(target.accountId)
        firstTicket = null // an already-paired daemon authenticates by static key — the PSK is only for first pair
        startRelay()
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
            if (useRelay) relay.send(frame) else direct.send(frame)
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
            is Sessions -> { sessionsDir.value = f.workdir; replace(sessions, f.items) }
            is SessionLive -> {
                convoId.value = f.convoId; workdir.value = f.workdir; observing.value = f.observing; currentSessionId = f.sessionId
                f.mode?.let { mode.value = it } // daemon is the source of truth — corrects the optimistic badge
                f.model?.let { model.value = it }
                f.effort?.let { effort.value = it }
                f.agent?.let { sessionAgent.value = it } // daemon truth for the backend badge
                contextWindow.value = f.contextWindow ?: contextWindowFor(f.model ?: model.value)
                // seed the usage statusline on resume (before the first new turn). Only when we have no
                // value yet — a TurnDone this session is fresher than the daemon's transcript snapshot.
                if (contextUsed.value == null) f.contextUsed?.let { contextUsed.value = it }
                // daemon truth beats the local guess: a turn that ended (or started) while the link was
                // down would otherwise leave the ■/mic button stuck; null = old daemon, keep local state
                f.executing?.let { exec ->
                    if (!exec) finishThinking() // a turn killed mid-thinking has no TurnDone to stamp the block
                    streaming.value = exec
                }
                switching.value = false
                // remember this session's launch flags so a close+reopen cycle can restore (and relaunch under) them
                f.sessionId?.let { sessionParams[it] = SessionParams(mode.value, model.value, effort.value, sessionAgent.value ?: AgentKind.CLAUDE) }
            }
            // Stream/turn frames carry their source convoId; this single-active-view model has one `messages`
            // list, so a frame from a just-left conversation (its tail still in flight when we switched) must
            // be dropped — else it renders into whatever convo is now open. Reopening the source replays its
            // full transcript via ConvoHistory, so nothing is actually lost. (Matches the BackgroundJobs guard.)
            is AssistantChunk -> if (f.convoId == convoId.value) appendChunk(f)
            is ToolEvent -> if (f.convoId == convoId.value) { finishThinking(); messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: "")) }
            is PermissionAsk -> if (f.convoId == convoId.value) { pendingAsk.value = f; Telemetry.track(TelEvent.ApprovalShown, mapOf(TelKey.Tool to f.tool)) }
            is TurnDone -> if (f.convoId == convoId.value) {
                finishThinking(); streaming.value = false
                // ~context occupancy: the prompt claude just saw (fresh input + the cached prefix still in the window)
                f.usage?.let { contextUsed.value = it.contextTokens }
            }
            is BackgroundJobs -> if (f.convoId == convoId.value) replace(backgroundJobs, f.jobs)
            is PocketError -> {
                messages.add(ChatItem.Sys(f.message)) // UI prepends the localized "error:" prefix
                // a dead claude process never sends TurnDone — clear the streaming state here
                if (f.code == "process_exited" && (f.convoId == null || f.convoId == convoId.value)) {
                    finishThinking(); streaming.value = false
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
            else -> {}
        }
    }

    private fun historyItem(h: HistoryMessage): ChatItem = when (h.role) {
        ChatRole.USER -> ChatItem.User(h.text)
        ChatRole.ASSISTANT -> ChatItem.Assistant(h.text)
        ChatRole.TOOL -> ChatItem.Tool(h.tool ?: "tool", h.text)
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
    fun refreshDirectories() {
        refreshing.value = true
        scope.launch {
            runCatching { send(ListDirectories()) }
            delay(4000); refreshing.value = false // safety: clear the spinner even if no reply
        }
    }

    /** Keep the open-project list fresh without the pull-to-refresh spinner (the daemon list is pull-only). */
    fun refreshDirectoriesSilently() = scope.launch { runCatching { send(ListDirectories()) } }

    fun listSessions(wd: String) = scope.launch { send(ListSessions(wd)) }
    // startMode defaults to the persisted default mode (mirrors effort), so tapping a session straight from
    // the list applies it too — not just the new-session picker. A session opened before keeps its own (saved).
    fun openSession(wd: String, resumeId: String? = null, startMode: PermissionMode = defaultMode.value, title: String? = null, agent: AgentKind = defaultAgent.value) = scope.launch {
        convoId.value?.let { send(CloseSession(it)) } // reclaim any lingering claude process first
        messages.clear(); convoId.value = null
        terminalEntries.clear(); terminalBusy.value = false // the quick-terminal scrollback is per-session
        streaming.value = false // the previous session's in-flight turn must not leak the ■ button
        pendingAsk.value = null
        chatTitle.value = title // resumed sessions carry their list title; new sessions fill in from the first prompt
        // restore the session's last-known launch flags: shows the right badge immediately (no default flash)
        // AND relaunches under them if the daemon closed the process while we were away. A live session's
        // reattach SessionLive still wins as the source of truth right after.
        val saved = resumeId?.let { sessionParams[it] }
        val openMode = saved?.mode ?: startMode
        val openEffort = saved?.effort ?: defaultEffort.value // new sessions seed from the persisted default; resumed keep their own
        val openAgent = saved?.agent ?: agent // resumed sessions keep their backend; new ones use the picked default
        mode.value = openMode; allowRules.clear()
        model.value = saved?.model; effort.value = openEffort; contextUsed.value = null // reconciled by SessionLive
        sessionAgent.value = openAgent // optimistic; SessionLive corrects from daemon truth
        clearBackgroundJobs()
        Telemetry.track(TelEvent.SessionOpened, mapOf(TelKey.Resume to if (resumeId != null) 1 else 0))
        send(OpenSession(wd, resumeId, model = saved?.model, mode = openMode, effort = openEffort, agent = openAgent))
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

    @OptIn(ExperimentalEncodingApi::class)
    fun sendPrompt(text: String) {
        val c = convoId.value ?: return
        val ready = pendingImages.filter { it.state == ImgState.Ready }.map { it.bytes }
        if (text.isBlank() && ready.isEmpty()) return
        if (voice.value is VoiceState.Failed) clearVoice() // sending dismisses the error chip
        val images = ready.map { ImageData("image/jpeg", Base64.Default.encode(it)) }
        messages.add(ChatItem.User(text, ready))
        if (chatTitle.value == null && text.isNotBlank()) chatTitle.value = text.take(48) // new session: first prompt becomes the header title
        pendingImages.clear()
        streaming.value = true
        Telemetry.track(TelEvent.PromptSent)
        scope.launch { send(SendPrompt(c, text, images)) }
    }

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

    fun resolve(decision: Decision, remember: Boolean = false) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        pendingAsk.value = null
        if (decision == Decision.ALLOW && remember) a.rule?.let { r ->
            if (r !in allowRules) allowRules.add(r)
            messages.add(ChatItem.RuleChip(r)) // drop the "always allowing X" chip into the stream
        }
        Telemetry.track(TelEvent.ApprovalDecided, mapOf(TelKey.Decision to (if (remember) "always" else decision.name.lowercase())))
        scope.launch { send(PermissionVerdict(c, a.askId, decision, remember = remember)) }
    }

    /** Timeout: the daemon already auto-denied at 30s; just clear the prompt without re-sending. */
    fun dismissAsk() { pendingAsk.value = null }

    /** Switch the execution/permission mode — relaunches the session on the daemon. */
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

    /** Switch the model — routed through the daemon's `/model` interception (relaunch under --model). */
    fun switchModel(name: String) {
        val target = name.trim()
        if (convoId.value == null || target.isEmpty() || target == model.value) return
        model.value = target // optimistic; the daemon's next SessionLive corrects it to the resolved id
        switchViaCommand("/model $target")
    }

    /** Switch reasoning effort — routed through the daemon's `/effort` interception (relaunch under --effort). */
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

    /** Context-window capacity for a model id: 1M for the `[1m]` variants, else the standard 200k. */
    private fun contextWindowFor(model: String?): Long {
        val m = model?.lowercase() ?: return 200_000
        return if ("[1m]" in m || "-1m" in m) 1_000_000 else 200_000
    }

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

    fun backToBrowse() {
        val c = convoId.value
        // observing or idle -> reclaim; still executing -> leave it running in the background
        if (c != null && (observing.value || !streaming.value)) scope.launch { send(CloseSession(c)) }
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
            send(OpenSession(wd, sid, takeOver = true))
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
        const val LIST_WAIT_MS = 6_000L       // after Attached, wait this long for the list before "computer offline"
        const val CONNECT_TIMEOUT_MS = 12_000L // no Attached within this → treat the connect as wedged, force a retry
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
        const val K_DEFAULT_AGENT = "default_session_agent"   // SecureStore: AgentKind.name new sessions start under (default CLAUDE)
        const val K_VIEW_MODE = "projects_view_mode"          // SecureStore: "tree" | "flat" for the Projects screen
        const val K_PINNED = "pinned_projects"                 // SecureStore: '\n'-joined project paths pinned to the top
        const val K_DRAFT_PREFIX = "draft:"                    // SecureStore: "draft:<workdir>" → unsent composer text for that project
        const val K_FONT_SCALE = "chat_font_scale"            // SecureStore: chat text scale factor (Float string, default 1.0)
        const val FONT_SCALE_MIN = 0.85f                       // smallest chat text scale (Settings slider lower bound)
        const val FONT_SCALE_MAX = 1.4f                        // largest chat text scale (eye-comfort upper bound)
    }
}
