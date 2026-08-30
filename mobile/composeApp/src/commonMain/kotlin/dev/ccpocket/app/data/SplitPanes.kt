package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.isQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * How many conversations the desktop shows side by side, the focused one included (issue #311).
 *
 * Three is a product decision, not a technical ceiling: it is the widest split that still leaves each
 * column readable at a normal window width, and it is what the issue's "左边一个 / 中间一个 / 最右边一个"
 * asks for. The daemon holds no such limit — [SessionRegistry] keys conversations by id — so raising this
 * is a one-line change once a layout that stays legible past three exists.
 */
const val MAX_SPLIT_PANES = 3

/**
 * One conversation kept live BESIDE the focused one.
 *
 * A side pane is a real, writable view of a session: it replays that session's backlog, streams its output,
 * accepts prompts, and shows the approvals it raises. What it deliberately does NOT carry is the state that
 * only ever made sense for one conversation at a time — the Changes browser, the Git panel, rewind/fork,
 * attachments, model/mode switching. Those stay on the focused pane, and clicking a side pane promotes it
 * (a plain re-open by session id, which the daemon answers by reattaching the live conversation), so any
 * pane can reach them without this class having to grow a second copy of them.
 */
class SidePane(
    val paneId: Long,
    /** Resume identity: what a promotion re-opens, and how this pane recognises its own [SessionLive]. */
    val sessionId: String,
    val workdir: String,
    initialTitle: String,
    val agent: AgentKind,
) {
    /** Filled by this pane's own SessionLive. Null while the open is still in flight. */
    val convoId = mutableStateOf<String?>(null)
    val title = mutableStateOf(initialTitle)
    val transcript = ChatTranscript()
    val messages get() = transcript.messages
    val streaming get() = transcript.streaming
    val composer = mutableStateOf("")
    val opening = mutableStateOf(true)

    /** The session ended underneath us (daemon-side close / crash) — the pane says so instead of going blank. */
    val gone = mutableStateOf(false)

    /** The open never landed inside [SidePanes.OPEN_TIMEOUT_MS] (issue #235's problem, one column over):
     *  a column stuck on "Opening…" forever reads as "my click never happened", so say it failed and
     *  offer the retry instead. */
    val openFailed = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    /** The approval this pane's session is blocked on. Resolved through the repository's inbox verbs, so a
     *  decision made here and one made in the bell popover travel the exact same path. */
    val pendingAsk = mutableStateOf<PermissionAsk?>(null)

    /** #147 reattach cursor for THIS pane's transcript, so a reconnect replays a delta, not the whole tail. */
    internal var historySeq: Long? = null
    internal var turnStartMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** The mode this column was opened under, replayed verbatim by a retry or a reconnect re-attach. */
    internal var mode: PermissionMode = PermissionMode.DEFAULT

    /** Ties an open's timeout to THAT open — a retry must not be failed by its predecessor's deadline. */
    internal var openGen = 0
}

/**
 * The conversations the desktop keeps live beside the focused one, and the frame routing that feeds them.
 *
 * The repository still drives exactly ONE conversation with its full state machine. This sits beside it:
 * [route] is called for every inbound frame and mirrors the ones belonging to a side pane's conversation
 * into that pane. It never consumes a frame — every existing branch in `PocketRepository.handle` still runs
 * exactly as before — which is what keeps the phone (where no pane is ever opened) byte-for-byte unchanged
 * and keeps machine-wide bookkeeping like the approvals inbox working for panes too.
 *
 * The one place the repository has to yield is [claimsSessionLive]: a side pane's open is answered by the
 * same `SessionLive` frame shape as the focused pane's, and the focused pane's acceptance rule ends in a
 * catch-all for "nothing is open yet". Without this check, opening a pane while the main area is empty
 * would hijack the main area instead.
 */
class SidePanes(
    private val scope: CoroutineScope,
    private val send: suspend (Frame) -> Unit,
    private val newPromptId: () -> String,
) {
    val panes = mutableStateListOf<SidePane>()
    private var paneSeq = 0L

    /**
     * [panes.size] as a PLAIN field, not snapshot state — and the only thing [route] and
     * [claimsSessionLive] consult before deciding they have nothing to do.
     *
     * Both run on the inbound frame path, which is every chunk of every turn, and which is not a
     * composition. Touching a snapshot list there costs more than a field read AND can advance the
     * global snapshot, flushing pending apply notifications at a moment nothing else would have — which
     * is exactly the kind of invisible retiming that moves a composer draft under the user. Nobody opens
     * a pane on the phone, so on mobile this keeps the hook to one integer comparison, forever.
     */
    private var openCount = 0

    /** Room for another column, focused pane included. */
    fun canOpen(): Boolean = panes.size < MAX_SPLIT_PANES - 1

    fun paneFor(sessionId: String): SidePane? = panes.firstOrNull { it.sessionId == sessionId }

    /**
     * Open [sessionId] into a new column. Returns null when the split is already full or the session is
     * already in one — both are "nothing to do", not errors, so a repeated gesture is harmless.
     *
     * The open rides the ordinary [OpenSession] wire with `lastEventSeq = 0`, which tells the daemon this
     * client understands delta replays (see the field's contract) while still asking for a full first tail.
     */
    fun open(workdir: String, sessionId: String, title: String, agent: AgentKind, mode: PermissionMode): SidePane? {
        if (!canOpen() || paneFor(sessionId) != null) return null
        val pane = SidePane(++paneSeq, sessionId, workdir, title, agent)
        panes.add(pane)
        openCount = panes.size
        pane.mode = mode
        dispatchOpen(pane, lastEventSeq = 0L)
        return pane
    }

    /** Re-send a column's open after it timed out — the same request, so a retry cannot land under
     *  different flags than the gesture that failed. */
    fun retry(pane: SidePane) {
        if (pane.convoId.value != null) return
        pane.openFailed.value = false
        pane.opening.value = true
        dispatchOpen(pane, lastEventSeq = 0L)
    }

    /**
     * Re-attach every column after the link comes back, the way the focused chat does: resume by session
     * id and ask for the DELTA past the transcript cursor we already hold (issue #147), so a reconnect
     * backfills what streamed while the link was down instead of replaying the whole tail per column.
     */
    fun reopenAll() {
        if (openCount == 0) return
        for (pane in panes.toList()) {
            if (pane.gone.value) continue
            pane.opening.value = pane.convoId.value == null
            dispatchOpen(pane, lastEventSeq = pane.historySeq ?: 0L)
        }
    }

    private fun dispatchOpen(pane: SidePane, lastEventSeq: Long) {
        val gen = ++pane.openGen
        scope.launch {
            send(
                OpenSession(
                    workdir = pane.workdir, resumeId = pane.sessionId, mode = pane.mode,
                    agent = pane.agent, lastEventSeq = lastEventSeq,
                ),
            )
        }
        scope.launch {
            delay(OPEN_TIMEOUT_MS)
            // only THIS open's deadline, and only while it is still unanswered
            if (gen == pane.openGen && pane.convoId.value == null) {
                pane.opening.value = false
                pane.openFailed.value = true
            }
        }
    }

    /**
     * Drop a column. The conversation is reclaimed only when it is IDLE — the same rule the focused pane
     * uses when switching away, and for the same reason: a running turn belongs to the agent, not to
     * whether a window is showing it, so closing the column must not kill work in flight.
     */
    fun close(paneId: Long) {
        val i = panes.indexOfFirst { it.paneId == paneId }
        if (i < 0) return
        val pane = panes.removeAt(i)
        openCount = panes.size
        val convo = pane.convoId.value ?: return
        if (pane.streaming.value) return
        scope.launch { send(CloseSession(convo)) }
    }

    /**
     * Drop a column WITHOUT reclaiming its session — for a promotion, which re-opens that very session as
     * the focused chat a moment later. Sending [CloseSession] here would race that re-open on the daemon
     * and could close the conversation the user just promoted.
     */
    fun detach(paneId: Long) {
        panes.removeAll { it.paneId == paneId }
        openCount = panes.size
    }

    /** Drop every column without touching the sessions behind them (disconnect, machine switch, sign-out). */
    fun clear() {
        panes.clear()
        openCount = 0
    }

    /**
     * True when [f] answers a side pane's open, so the focused pane must NOT treat it as its own.
     * See the class doc — this is the single point where side panes take priority.
     */
    fun claimsSessionLive(f: SessionLive): Boolean = openCount > 0 && panes.any { pane ->
        pane.convoId.value?.let { it == f.convoId } ?: (f.sessionId != null && f.sessionId == pane.sessionId)
    }

    /** Mirror [f] into the pane it belongs to. Never consumes: the caller's own handling runs regardless. */
    fun route(f: Frame) {
        if (openCount == 0) return
        when (f) {
            is SessionLive -> bind(f)
            is ConvoHistory -> byConvo(f.convoId)?.let { replay(it, f) }
            is AssistantChunk -> byConvo(f.convoId)?.transcript?.appendChunk(f)
            is ToolEvent -> byConvo(f.convoId)?.let { it.transcript.finishThinking(); it.transcript.onToolEvent(f) }
            is TurnDone -> byConvo(f.convoId)?.let { endTurn(it, f) }
            is PromptAck -> byConvo(f.convoId)?.let { it.error.value = null }
            is PermissionAsk -> if (!f.isQuestion) byConvo(f.convoId)?.let { it.pendingAsk.value = f }
            is AskWithdrawn -> byConvo(f.convoId)?.let { pane ->
                if (pane.pendingAsk.value?.askId == f.askId) pane.pendingAsk.value = null
            }
            is SessionGone -> byConvo(f.convoId)?.let { it.gone.value = true; it.streaming.value = false }
            is PocketError -> f.convoId?.let { c -> byConvo(c)?.let { it.error.value = f.message } }
            else -> Unit
        }
    }

    /** Resolving an approval anywhere clears the card here too — the pane must not keep offering a decision
     *  that has already been made (from the bell popover, the phone, or the computer itself). */
    fun noteApprovalResolved(convoId: String, askId: String) {
        byConvo(convoId)?.let { if (it.pendingAsk.value?.askId == askId) it.pendingAsk.value = null }
    }

    /** Send [text] into [pane]'s conversation. False = nothing to send, or the pane's open has not landed. */
    fun sendPrompt(pane: SidePane, text: String): Boolean {
        val convo = pane.convoId.value ?: return false
        if (text.isBlank()) return false
        val promptId = newPromptId()
        pane.messages.add(ChatItem.User(text, pending = true, promptId = promptId))
        pane.composer.value = ""
        pane.turnStartMark = TimeSource.Monotonic.markNow()
        pane.streaming.value = true
        scope.launch { send(SendPrompt(convo, text, promptId = promptId)) }
        return true
    }

    private companion object {
        /** Matches the focused chat's own open window — long enough for a cold resume, short enough that
         *  a column does not sit on "Opening…" past the point a person decides it is broken. */
        const val OPEN_TIMEOUT_MS = 8_000L
    }

    private fun byConvo(convoId: String): SidePane? = panes.firstOrNull { it.convoId.value == convoId }

    private fun bind(f: SessionLive) {
        val pane = panes.firstOrNull { it.convoId.value == f.convoId }
            ?: panes.firstOrNull { it.convoId.value == null && f.sessionId != null && f.sessionId == it.sessionId }
            ?: return
        pane.convoId.value = f.convoId
        pane.opening.value = false
        pane.openFailed.value = false
        pane.gone.value = false
        f.executing?.let { pane.streaming.value = it }
    }

    /** The pane flavour of the focused path's ConvoHistory handling (issue #147 full/delta, issue #107 echo). */
    private fun replay(pane: SidePane, f: ConvoHistory) {
        val local = pane.messages.toList()
        val merged = if (f.delta) {
            if (f.messages.isEmpty()) return
            TranscriptMerge.mergeDelta(local, f.messages.map(::historyItem))
        } else {
            TranscriptMerge.merge(local, f.messages.map(::historyItem))
        }
        if (merged != local) {
            pane.messages.clear()
            pane.messages.addAll(merged)
        }
        pane.transcript.replayEcho = true // arm the one-shot live-stream dedupe for the replay/stream race
        f.lastSeq?.let { pane.historySeq = it }
    }

    private fun endTurn(pane: SidePane, f: TurnDone) {
        pane.transcript.replayEcho = false // turn boundary — the next block starts a new turn, never an echo
        val wasLive = pane.streaming.value
        pane.transcript.finishThinking()
        pane.streaming.value = false
        // the turn is over, so no bubble is still "sending" — the pane has no delivery watchdog of its
        // own, and a bubble stuck at pending would misreport a prompt the agent demonstrably answered
        for (i in pane.messages.indices) {
            val row = pane.messages[i]
            if (row is ChatItem.User && row.pending) pane.messages[i] = row.copy(pending = false)
        }
        f.error?.let { pane.messages.add(ChatItem.Sys(it)) }
        if (wasLive && f.error == null) {
            pane.messages.add(ChatItem.TurnEnded(pane.turnStartMark?.elapsedNow()?.inWholeSeconds?.toInt()))
        }
        pane.turnStartMark = null
    }
}
