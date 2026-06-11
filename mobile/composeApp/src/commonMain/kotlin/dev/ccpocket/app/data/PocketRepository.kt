package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.ensureLocalNetworkAccess
import dev.ccpocket.app.net.RelayConnection
import dev.ccpocket.app.net.RelayE2EConnection
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.Telemetry
import dev.ccpocket.protocol.AssistantChunk
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
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.status_checking_network
import dev.ccpocket.app.resources.status_conn_lost
import dev.ccpocket.app.resources.status_connected
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

/**
 * A localizable status line: the UI resolves [res] (and substitutes [arg] for %1$s when present).
 * Keeping the resource key — not resolved text — in state means the line re-renders in the right
 * language even though it was set outside composition.
 */
data class StatusMsg(val res: StringResource, val arg: String? = null)

/** A photo staged in the composer: [bytes] are the current JPEG for the thumbnail; [state] drives the tray UI. */
class PendingImage(val id: Long, val bytes: ByteArray, val state: ImgState)

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

    /**
     * True from a successful explicit connect until the user disconnects/unpairs. While true, a dead
     * transport does NOT route back to the Connect screen — the UI stays put, shows a slim banner,
     * and the repo reconnects (backoff timer + app-foreground trigger).
     */
    val sessionActive = mutableStateOf(false)
    val connected = mutableStateOf(false)
    /** Steady "link is being re-established" signal for the banner — set on drop, cleared only once
     *  the transport is truly attached (unlike [connected], which flips optimistically per attempt). */
    val reconnecting = mutableStateOf(false)
    val status = mutableStateOf(StatusMsg(Res.string.status_disconnected))
    val paired = mutableStateOf<PairedDaemon?>(Pairing.load())
    val directories = mutableStateListOf<DirectoryEntry>()
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
    val mode = mutableStateOf(PermissionMode.DEFAULT)        // current execution/permission mode
    val allowRules = mutableStateListOf<String>()            // "Always allow" scopes remembered this session
    val switching = mutableStateOf(false)                    // a mode switch is relaunching the session
    val streaming = mutableStateOf(false)
    val observing = mutableStateOf(false) // viewing a session running outside the daemon (read-only tail)
    private var currentSessionId: String? = null

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
            paired.value = Pairing.redeem(info, keys, client)
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

    /** (Re)open the active transport's socket. Both transports re-handshake on every connect() call. */
    private fun launchTransport(reconnect: Boolean) {
        connected.value = true // optimistic; onTransportDown reverts it if the link fails
        status.value = StatusMsg(if (reconnect) Res.string.status_reconnecting else Res.string.status_connecting)
        if (inboundJob == null) {
            inboundJob = scope.launch {
                if (useRelay) relay.inbound.collect { handle(it) } else direct.inbound.collect { handle(it) }
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
        scope.launch {
            // sends buffer in the transport's outbox and flush once the handshake lands
            send(ListDirectories())
            status.value = StatusMsg(Res.string.status_connected)
            retryAttempts = 0
            reconnecting.value = false
            if (reconnect) restoreAfterReconnect()
            Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to if (useRelay) "relay" else "direct"))
        }
    }

    /** The socket died. Stay on the current screen; banner + backoff retries take it from here. */
    private fun onTransportDown(err: Throwable?) {
        connected.value = false
        if (!sessionActive.value) {
            status.value = err?.let { StatusMsg(Res.string.status_failed, it.message ?: it::class.simpleName ?: "error") }
                ?: StatusMsg(Res.string.status_disconnected)
            return
        }
        reconnecting.value = true
        status.value = StatusMsg(Res.string.status_conn_lost)
        scheduleRetry()
    }

    private fun scheduleRetry() {
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
        if (sessionActive.value && !connected.value) {
            retryJob?.cancel()
            retryAttempts = 0
            launchTransport(reconnect = true)
        }
    }

    /** After the link is back: re-sync whatever page the user is parked on; reattach a live chat. */
    private suspend fun restoreAfterReconnect() {
        val sid = currentSessionId
        val wd = workdir.value
        val dir = sessionsDir.value
        when {
            // daemon finds the still-live conversation by sessionId → reattach + history replay
            convoId.value != null && !observing.value && sid != null && wd != null ->
                send(OpenSession(wd, sid, mode = mode.value))
            dir != null -> send(ListSessions(dir))
            else -> {} // directory list already refreshed by launchTransport
        }
    }

    /** Drop the live connection and return to the Connect screen (pairing is kept). */
    fun disconnect() {
        sessionActive.value = false
        retryJob?.cancel(); connectJob?.cancel(); inboundJob?.cancel()
        retryJob = null; connectJob = null; inboundJob = null
        connected.value = false
        reconnecting.value = false
        convoId.value = null
        sessionsDir.value = null
        pendingAsk.value = null
        directories.clear(); sessions.clear(); messages.clear(); pendingImages.clear()
        abandonVoice()
        status.value = StatusMsg(Res.string.status_disconnected)
        Telemetry.track(TelEvent.Disconnected)
    }

    fun unpair() { disconnect(); Pairing.forget(); paired.value = null }

    /** All outbound frames funnel here; a throw means the link is dead — trigger the reconnect path. */
    private suspend fun send(frame: Frame) {
        try {
            if (useRelay) relay.send(frame) else direct.send(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onTransportDown(t)
        }
    }

    private fun handle(f: Frame) {
        when (f) {
            is Directories -> { replace(directories, f.entries); refreshing.value = false }
            is Sessions -> { sessionsDir.value = f.workdir; replace(sessions, f.items) }
            is SessionLive -> {
                convoId.value = f.convoId; workdir.value = f.workdir; observing.value = f.observing; currentSessionId = f.sessionId
                f.mode?.let { mode.value = it } // daemon is the source of truth — corrects the optimistic badge
                switching.value = false
            }
            is AssistantChunk -> appendChunk(f)
            is ToolEvent -> { finishThinking(); messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: "")) }
            is PermissionAsk -> { pendingAsk.value = f; Telemetry.track(TelEvent.ApprovalShown, mapOf(TelKey.Tool to f.tool)) }
            is TurnDone -> { finishThinking(); streaming.value = false }
            is PocketError -> {
                messages.add(ChatItem.Sys(f.message)) // UI prepends the localized "error:" prefix
                // a dead claude process never sends TurnDone — clear the streaming state here
                if (f.code == "process_exited" && (f.convoId == null || f.convoId == convoId.value)) {
                    finishThinking(); streaming.value = false
                }
            }
            is ConvoHistory -> { messages.clear(); messages.addAll(f.messages.map(::historyItem)) }
            is CommandList -> replace(slashCommands, f.commands)
            is Transcript -> onTranscript(f)
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

    fun listSessions(wd: String) = scope.launch { send(ListSessions(wd)) }
    fun openSession(wd: String, resumeId: String? = null, startMode: PermissionMode = PermissionMode.DEFAULT, title: String? = null) = scope.launch {
        convoId.value?.let { send(CloseSession(it)) } // reclaim any lingering claude process first
        messages.clear(); convoId.value = null
        streaming.value = false // the previous session's in-flight turn must not leak the ■ button
        pendingAsk.value = null
        chatTitle.value = title // resumed sessions carry their list title; new sessions fill in from the first prompt
        mode.value = startMode; allowRules.clear()
        Telemetry.track(TelEvent.SessionOpened, mapOf(TelKey.Resume to if (resumeId != null) 1 else 0))
        send(OpenSession(wd, resumeId, mode = startMode))
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
        abandonVoice()
    }

    fun backToDirectories() {
        sessionsDir.value = null
        sessions.clear()
    }

    private companion object {
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
    }
}
