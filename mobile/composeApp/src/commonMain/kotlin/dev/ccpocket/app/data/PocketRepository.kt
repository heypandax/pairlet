package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.ensureLocalNetworkAccess
import dev.ccpocket.app.net.RelayConnection
import dev.ccpocket.app.net.RelayE2EConnection
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String) : ChatItem
    data class Tool(val tool: String, val preview: String) : ChatItem
    data class Sys(val text: String) : ChatItem
}

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
    val sessions = mutableStateListOf<SessionSummary>()
    val sessionsDir = mutableStateOf<String?>(null)
    val messages = mutableStateListOf<ChatItem>()
    val convoId = mutableStateOf<String?>(null)
    val workdir = mutableStateOf<String?>(null)
    val pendingAsk = mutableStateOf<PermissionAsk?>(null)
    val streaming = mutableStateOf(false)

    /** Pair from a scanned/pasted `ccpocket://pair?...` link, then connect end-to-end. */
    fun pair(link: String) {
        val info = Pairing.parse(link.trim())
        if (info == null) { status.value = "invalid pairing link"; return }
        status.value = "pairing…"
        scope.launch { doPair { info } }
    }

    /** A scanned/opened `ccpocket://pair?...` URL — either a short ?code= or a full link. */
    fun handlePairUrl(url: String) {
        val code = Regex("[?&]code=([0-9]{6})").find(url)?.groupValues?.get(1)
        if (code != null) pairWithCode(code) else pair(url)
    }

    /** Pair from the 6-digit code shown by `cc-pocket pair` on the computer. */
    fun pairWithCode(code: String) {
        status.value = "pairing…"
        scope.launch { doPair { Pairing.resolveCode(code.trim(), it) } }
    }

    private suspend fun doPair(getInfo: suspend (HttpClient) -> dev.ccpocket.app.pairing.PairingInfo) {
        val client = HttpClient()
        try {
            val info = getInfo(client)
            val keys = Pairing.deviceKeys()
            paired.value = Pairing.redeem(info, keys, client)
            firstTicket = info.ticket
            startRelay()
        } catch (t: Throwable) {
            status.value = "pairing failed: ${t.message ?: t::class.simpleName}"
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
        scope.launch { relay.send(ListDirectories()); status.value = "connected" }
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
            scope.launch { direct.send(ListDirectories()); status.value = "connected" }
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
        directories.clear(); sessions.clear(); messages.clear()
        status.value = "disconnected"
    }

    fun unpair() { disconnect(); Pairing.forget(); paired.value = null }

    private suspend fun send(frame: Frame) = if (useRelay) relay.send(frame) else direct.send(frame)

    private fun handle(f: Frame) {
        when (f) {
            is Directories -> replace(directories, f.entries)
            is Sessions -> { sessionsDir.value = f.workdir; replace(sessions, f.items) }
            is SessionLive -> { convoId.value = f.convoId; workdir.value = f.workdir }
            is AssistantChunk -> appendChunk(f)
            is ToolEvent -> messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: ""))
            is PermissionAsk -> pendingAsk.value = f
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

    fun listSessions(wd: String) = scope.launch { send(ListSessions(wd)) }
    fun openSession(wd: String, resumeId: String? = null) = scope.launch {
        convoId.value?.let { send(CloseSession(it)) } // reclaim any lingering claude process first
        messages.clear(); convoId.value = null; send(OpenSession(wd, resumeId))
    }

    fun sendPrompt(text: String) {
        val c = convoId.value ?: return
        messages.add(ChatItem.User(text)); streaming.value = true
        scope.launch { send(SendPrompt(c, text)) }
    }

    fun resolve(decision: Decision) {
        val a = pendingAsk.value ?: return
        val c = convoId.value ?: return
        pendingAsk.value = null
        scope.launch { send(PermissionVerdict(c, a.askId, decision)) }
    }

    fun switchDir(wd: String) {
        val c = convoId.value ?: return
        scope.launch { send(SwitchDirectory(c, wd)) }
    }

    fun backToBrowse() {
        convoId.value?.let { c -> scope.launch { send(CloseSession(c)) } } // kill the claude process on leaving the chat
        convoId.value = null
        messages.clear()
    }

    fun backToDirectories() {
        sessionsDir.value = null
        sessions.clear()
    }
}
