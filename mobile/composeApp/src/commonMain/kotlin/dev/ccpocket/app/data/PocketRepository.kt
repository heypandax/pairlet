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
import dev.ccpocket.protocol.TurnDone
import io.ktor.client.HttpClient
import dev.ccpocket.app.media.compressImage
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
    data class Tool(val tool: String, val preview: String) : ChatItem
    data class Sys(val text: String) : ChatItem
    data class RuleChip(val rule: String) : ChatItem // "Always allowing X this session" confirmation
}

enum class ImgState { Compressing, Ready, Rejected }

/** A photo staged in the composer: [bytes] are the current JPEG for the thumbnail; [state] drives the tray UI. */
class PendingImage(val id: Long, val bytes: ByteArray, val state: ImgState)

/** State hub: consumes inbound [Frame]s into observable Compose state, exposes user actions. */
class PocketRepository(private val scope: CoroutineScope) {
    private val direct = RelayConnection()
    private val relay = RelayE2EConnection()
    private var useRelay = false
    private var firstTicket: String? = null // pairing ticket, used as PSK on the first relay connect only
    private val jobs = mutableListOf<Job>() // live connection coroutines, cancelled on disconnect

    val connected = mutableStateOf(false)
    val status = mutableStateOf("disconnected")
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
    val pendingAsk = mutableStateOf<PermissionAsk?>(null)
    val mode = mutableStateOf(PermissionMode.DEFAULT)        // current execution/permission mode
    val allowRules = mutableStateListOf<String>()            // "Always allow" scopes remembered this session
    val switching = mutableStateOf(false)                    // a mode switch is relaunching the session
    val streaming = mutableStateOf(false)
    val observing = mutableStateOf(false) // viewing a session running outside the daemon (read-only tail)
    private var currentSessionId: String? = null

    /** Pair from a scanned/pasted `ccpocket://pair?...` link, then connect end-to-end. */
    fun pair(link: String) {
        val info = Pairing.parse(link.trim())
        if (info == null) { status.value = "invalid pairing link"; return }
        status.value = "pairing…"
        scope.launch { doPair("link") { info } }
    }

    /** A scanned/opened `ccpocket://pair?...` URL — either a short ?code= or a full link. */
    fun handlePairUrl(url: String) {
        val code = Regex("[?&]code=([0-9]{6})").find(url)?.groupValues?.get(1)
        if (code != null) pairWithCode(code) else pair(url)
    }

    /** Pair from the 6-digit code shown by `cc-pocket pair` on the computer. */
    fun pairWithCode(code: String) {
        status.value = "pairing…"
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
            status.value = "pairing failed: ${t.message ?: t::class.simpleName}"
            Telemetry.track(TelEvent.PairFailed)
            Telemetry.recordError(t.message ?: "pair failed", "pairing")
        } finally {
            client.close()
        }
    }

    /** Connect to the already-paired daemon over the encrypted relay channel. */
    fun startRelay() {
        val p = paired.value ?: return
        useRelay = true
        connected.value = true
        status.value = "connecting…"
        jobs += scope.launch { relay.inbound.collect { handle(it) } }
        jobs += scope.launch {
            val keys = Pairing.deviceKeys()
            val result = runCatching { relay.connect(p, keys, firstTicket) }
            firstTicket = null
            val err = result.exceptionOrNull()
            if (err is CancellationException) return@launch // intentional disconnect — not a failure
            connected.value = false
            status.value = err?.let { "failed: ${it.message ?: it::class.simpleName}" } ?: "disconnected"
        }
        scope.launch { relay.send(ListDirectories()); status.value = "connected"; Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to "relay")) }
    }

    /** Advanced: connect directly to a daemon on the LAN (no relay), still over WebSocket. */
    fun startDirect(url: String) {
        useRelay = false
        status.value = "checking network access…"
        scope.launch {
            if (!ensureLocalNetworkAccess(url)) {
                status.value = "local network denied — allow cc-pocket in Settings → Privacy → Local Network"
                return@launch
            }
            connected.value = true
            status.value = "connecting…"
            jobs += scope.launch { direct.inbound.collect { handle(it) } }
            jobs += scope.launch {
                val result = runCatching { direct.connect(url) }
                val err = result.exceptionOrNull()
                if (err is CancellationException) return@launch // intentional disconnect — not a failure
                connected.value = false
                status.value = err?.let { "failed: ${it.message ?: it::class.simpleName}" } ?: "disconnected"
            }
            scope.launch { direct.send(ListDirectories()); status.value = "connected"; Telemetry.track(TelEvent.Connected, mapOf(TelKey.Transport to "direct")) }
        }
    }

    /** Drop the live connection and return to the Connect screen (pairing is kept). */
    fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        connected.value = false
        convoId.value = null
        sessionsDir.value = null
        pendingAsk.value = null
        directories.clear(); sessions.clear(); messages.clear(); pendingImages.clear()
        status.value = "disconnected"
        Telemetry.track(TelEvent.Disconnected)
    }

    fun unpair() { disconnect(); Pairing.forget(); paired.value = null }

    private suspend fun send(frame: Frame) = if (useRelay) relay.send(frame) else direct.send(frame)

    private fun handle(f: Frame) {
        when (f) {
            is Directories -> { replace(directories, f.entries); refreshing.value = false }
            is Sessions -> { sessionsDir.value = f.workdir; replace(sessions, f.items) }
            is SessionLive -> { convoId.value = f.convoId; workdir.value = f.workdir; observing.value = f.observing; currentSessionId = f.sessionId; switching.value = false }
            is AssistantChunk -> appendChunk(f)
            is ToolEvent -> messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: ""))
            is PermissionAsk -> { pendingAsk.value = f; Telemetry.track(TelEvent.ApprovalShown, mapOf(TelKey.Tool to f.tool)) }
            is TurnDone -> streaming.value = false
            is PocketError -> messages.add(ChatItem.Sys("error: ${f.message}"))
            is ConvoHistory -> { messages.clear(); messages.addAll(f.messages.map(::historyItem)) }
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
        val text = when (val p = c.piece) {
            is StreamPiece.Text -> p.text
            is StreamPiece.Thinking -> p.text
        }
        val last = messages.lastOrNull()
        if (last is ChatItem.Assistant) messages[messages.lastIndex] = last.copy(text = last.text + text)
        else messages.add(ChatItem.Assistant(text))
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
    fun openSession(wd: String, resumeId: String? = null, startMode: PermissionMode = PermissionMode.DEFAULT) = scope.launch {
        convoId.value?.let { send(CloseSession(it)) } // reclaim any lingering claude process first
        messages.clear(); convoId.value = null
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
        val images = ready.map { ImageData("image/jpeg", Base64.Default.encode(it)) }
        messages.add(ChatItem.User(text, ready))
        pendingImages.clear()
        streaming.value = true
        Telemetry.track(TelEvent.PromptSent)
        scope.launch { send(SendPrompt(c, text, images)) }
    }

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

    fun backToBrowse() {
        val c = convoId.value
        // observing or idle -> reclaim; still executing -> leave it running in the background
        if (c != null && (observing.value || !streaming.value)) scope.launch { send(CloseSession(c)) }
        convoId.value = null
        messages.clear()
        pendingImages.clear()
        observing.value = false
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
        messages.clear()
        pendingImages.clear()
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
    }
}
