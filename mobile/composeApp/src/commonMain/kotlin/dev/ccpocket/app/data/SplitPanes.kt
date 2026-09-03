package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CancelTurn
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
import dev.ccpocket.protocol.isModelCompatibleWithAgent
import dev.ccpocket.protocol.isQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** Pane-list index behind visual [slot] given where the focused chat sits — THE #336 invariant
 *  (highlighted slot == rendered position), defined once for the store and the shell both. For an
 *  insertion the same formula answers "at which pane index does slot k land". */
fun paneIndexForSlot(slot: Int, focusedSlot: Int): Int = if (slot > focusedSlot) slot - 1 else slot

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
    /** The daemon's actual model for THIS pane's session (its own SessionLive), the focused path's
     *  `repo.model` one column over — what the column's composer chip shows and switches. */
    val model = mutableStateOf<String?>(null)
    val transcript = ChatTranscript()
    val messages get() = transcript.messages
    val streaming get() = transcript.streaming
    val opening = mutableStateOf(true)

    /** The session ended underneath us (daemon-side close / crash) — the pane says so instead of going blank. */
    val gone = mutableStateOf(false)

    /** The open never landed inside [SESSION_OPEN_TIMEOUT_MS] (issue #235's problem, one column over):
     *  a column stuck on "Opening…" forever reads as "my click never happened", so say it failed and
     *  offer the retry instead. */
    val openFailed = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    /** The approval — or the AskUserQuestion — this pane's session is blocked on. Resolved through the
     *  repository's ask-keyed verbs, so a decision made here and one made in the bell popover travel the
     *  exact same path. Questions belong here too: the bell inbox deliberately excludes them (they are
     *  conversation, answered inside their own session), so this slot is the ONLY surface a column's
     *  question can ever reach. */
    val pendingAsk = mutableStateOf<PermissionAsk?>(null)

    /**
     * The asks that arrived while [pendingAsk] was already showing one (approval design M1, one column over).
     *
     * The daemon's ApprovalCoordinator lets one conversation hold SEVERAL open asks at once, and on a
     * reattach it resurfaces every one of them back to back. A single-value slot kept only the last —
     * every other question and approval was destroyed before it was ever drawn, and the agent sat waiting
     * for a verdict no surface would ever offer. So they wait in line, exactly as the focused chat's do.
     */
    val askQueue = mutableStateListOf<PermissionAsk>()

    /**
     * The askId the daemon reported TIMED_OUT (issue #100), for THIS column's current card.
     *
     * Kept as an id rather than a flag for the same reason the focused path keeps one: matched against the
     * card actually on screen, a stale value cannot bleed onto the next ask. A bare askId is enough here
     * (the focused path needs the composite key) because a pane only ever holds its own conversation's
     * asks, and askIds are unique within one.
     */
    val timedOutAskId = mutableStateOf<String?>(null)

    /** #147 reattach cursor for THIS pane's transcript, so a reconnect replays a delta, not the whole tail. */
    internal var historySeq: Long? = null
    internal var turnStartMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** A prompt sent from THIS column is still showing as unsent — the focused path's `promptPending`, one
     *  column over, and for the same reason: a turn boundary only has a bubble to settle when this device
     *  actually sent one. Most TurnDone frames (a reattached turn, work started at the computer) have none,
     *  and scanning the whole transcript for a bubble that cannot be there is pure waste on every turn. */
    internal var promptOutstanding = false

    /** The mode this column was opened under, replayed verbatim by a retry or a reconnect re-attach. */
    internal var mode: PermissionMode = PermissionMode.DEFAULT

    /** Ties an open's timeout to THAT open — a retry must not be failed by its predecessor's deadline. */
    internal var openGen = 0

    // ── delivery receipt + stall detection (issue #329) ─────────────────────────────────────────────
    // The focused conversation grew a two-stage watchdog for lost prompts — a receipt deadline (#78) and,
    // after a PromptAck lands, an ack→turn deadline (#104). A column had NEITHER: a prompt the daemon never
    // received left the bubble "sending…" and the column streaming forever, and [close]'s running guard then
    // skipped the reclaim, leaking the session on the daemon until a full reconnect. These fields mirror the
    // focused path's machinery PER PANE (its `sendStalled`/`turnStalled`/`turnQueued` and the two watchdogs),
    // keyed off the existing [promptOutstanding]. The one deliberate divergence — a column NEVER forces a
    // reconnect on a stall — lives in [SidePanes.armReceiptWatchdog].

    /** No receipt and no stream evidence within the receipt deadline (the focused path's `sendStalled`, #78):
     *  an E2E-deaf link can look healthy while nothing comes back, so a bounded wait is the only honest cue.
     *  Drives the column's "not delivered" marker; a per-pane resend is the recovery. */
    val sendStalled = mutableStateOf(false)

    /** A [PromptAck] landed but no turn frame followed within the turn deadline (the focused path's
     *  `turnStalled`, #104): the agent swallowed the write. Actionable — the column offers a resend. */
    val turnStalled = mutableStateOf(false)

    /** The calm queued flavor of the same deadline: a prompt sent mid-turn is parked by the CLI, so post-ack
     *  silence is expected there, not a swallow (the focused path's `turnQueued`). Informational, never a
     *  resend cue — a fresh-id resend would run the instruction twice once the turn yields. */
    val turnQueued = mutableStateOf(false)

    /** The newest prompt whose receipt/turn state may still move THIS pane's watchdogs. A [PromptAck] can
     *  race behind the first [dev.ccpocket.protocol.AssistantChunk] (the daemon's stdout pump is concurrent
     *  with its stdin write) and an older send's receipt must not touch a newer prompt — only an exact id
     *  match advances the machine (the focused path's `activePromptId`). */
    internal var activePromptId: String? = null
    internal var promptWatchdog: Job? = null // receipt deadline (#78)
    internal var turnWatchdog: Job? = null // ack→first-turn-frame deadline (#104)
    internal var awaitingTurn = false // between a PromptAck (delivered) and the first turn frame
    internal var promptQueued = false // the in-flight prompt was sent mid-turn — snapshot of [streaming] at send

    /** The last prompt's text, kept for a per-pane resend (the focused path's `promptRetry`, minus images —
     *  a column's [SidePanes.sendPrompt] carries none, so there is nothing else to replay). */
    internal var promptRetryText: String? = null
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
    /** Whether the connected daemon owns prompt redelivery (#122). A `supportsPromptRecovery` daemon keeps
     *  every acked prompt in an unconsumed ledger and re-delivers it after a process swap, so a quiet
     *  first-token window is normal there (large-context turns routinely exceed the deadline) and arming the
     *  ack→turn watchdog would only misreport a slow turn. Read LIVE — it flips on the DaemonInfo handshake —
     *  exactly as the focused path consults its own `daemonOwnsPromptRecovery`. Defaulted so the phone and the
     *  existing tests (which open no columns, or drive the deadline explicitly) construct unchanged. */
    private val daemonOwnsPromptRecovery: () -> Boolean = { false },
) {
    val panes = mutableStateListOf<SidePane>()
    private var paneSeq = 0L

    /** Per-pane receipt / turn deadlines — the focused path's `promptReceiptTimeoutMs` / `promptTurnTimeoutMs`,
     *  one column over. Production keeps the same 10s / 45s; a test seam shrinks them to drive the watchdogs
     *  in virtual time. */
    internal var receiptTimeoutMs = 10_000L
    internal var turnTimeoutMs = 45_000L

    /**
     * Which visual SLOT the focused chat occupies among the columns, 0-based over `panes.size + 1`
     * columns (issue #336). 0 = leftmost, the historic layout. Kept HERE and not in the shell because
     * every structural verb below must move it in the same breath as the list it describes — a close on
     * the focused chat's left that forgot to slide this down by one would visually teleport the focus.
     *
     * Snapshot state on purpose: the shell composes column order from it. It is only ever written by
     * the UI-thread verbs (open/close/detach/release/clear), never on the frame path — [openCount]
     * remains the only thing [route]/[claimsSessionLive] read.
     */
    val focusedSlot = mutableStateOf(0)


    /**
     * Sessions whose column was closed while its open was still in flight — see [close].
     *
     * The daemon cannot be told to stop an open it has not answered yet, so the answer arrives for a
     * column that no longer exists. Without a record of that, the focused pane's last acceptance rule
     * ("nothing is open here yet") would take it and the session the user just closed would open itself
     * in the main area. Membership is consumed once, by whichever comes first: the late `SessionLive`
     * (which is answered with a [CloseSession], handing the conversation back), or the user re-opening
     * that session — in a column ([open]) or as the focused chat ([releaseToFocus]).
     */
    private val disowned = mutableSetOf<String>()

    /**
     * `panes.size + disowned.size` as a PLAIN field, not snapshot state — and the only thing [route] and
     * [claimsSessionLive] consult before deciding they have nothing to do.
     *
     * Both run on the inbound frame path, which is every chunk of every turn, and which is not a
     * composition. Touching a snapshot list there costs more than a field read AND can advance the
     * global snapshot, flushing pending apply notifications at a moment nothing else would have — which
     * is exactly the kind of invisible retiming that moves a composer draft under the user. Nobody opens
     * a pane on the phone, so on mobile this keeps the hook to one integer comparison, forever.
     *
     * [disowned] counts into it because a disowned session's late answer must still be recognised after
     * its column is gone — the whole point of the record.
     */
    private var openCount = 0

    private fun recount() { openCount = panes.size + disowned.size }

    /** The frame-path counter, for tests pinning the recount invariant — never for production reads. */
    internal fun openCountForTest(): Int = openCount

    /** Room for another column, focused pane included. */
    fun canOpen(): Boolean = panes.size < MAX_SPLIT_PANES - 1

    fun paneFor(sessionId: String): SidePane? = panes.firstOrNull { it.sessionId == sessionId }

    /**
     * Open [sessionId] into a new column. Returns null when the split is already full or the session is
     * already in one — both are "nothing to do", not errors, so a repeated gesture is harmless.
     *
     * The open rides the ordinary [OpenSession] wire with `lastEventSeq = 0`, which tells the daemon this
     * client understands delta replays (see the field's contract) while still asking for a full first tail.
     *
     * [at] picks the position in [panes] (negative = the right end, the context menu's append); the
     * drag-to-split drop passes the index under the zone it was released on, so a column lands where
     * the pointer said it would. Clamped rather than trusted — a stale index after a concurrent close
     * must not crash the gesture that quoted it.
     */
    fun open(workdir: String, sessionId: String, title: String, agent: AgentKind, mode: PermissionMode, at: Int = -1): SidePane? {
        if (!canOpen() || paneFor(sessionId) != null) return null
        disowned.remove(sessionId) // asking for it again withdraws any pending disowning of this session
        val pane = SidePane(++paneSeq, sessionId, workdir, title, agent)
        // [at] is a visual SLOT over panes.size + 1 columns, the focused chat included (issue #336):
        // slot k lands the new column at position k, pushing the focused chat right when k is at or
        // before it. -1 keeps the historic append-at-the-right-end (the context-menu path).
        val slot = if (at < 0) panes.size + 1 else at.coerceIn(0, panes.size + 1)
        panes.add(paneIndexForSlot(slot, focusedSlot.value), pane)
        if (slot <= focusedSlot.value) focusedSlot.value += 1
        recount()
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
     *
     * Every live column goes back to "opening", convoId included, because the answer may name a DIFFERENT
     * conversation: a daemon that restarted while we were away resumes the session under a fresh convoId.
     * Holding the old one would freeze the column forever — [bind]'s session-id fallback only fires while
     * convoId is null, [claimsSessionLive] would match neither id, and the orphaned answer would be taken
     * by the focused area instead. Nothing is lost by clearing it: the link that carried the old
     * conversation's frames is gone, so none can still arrive. The transcript and its [SidePane.historySeq]
     * cursor stay, which is what keeps the re-attach a delta. Clearing [SidePane.openFailed] alongside also
     * retires the contradictory "opening AND failed" state a reconnect used to leave behind.
     */
    fun reopenAll() {
        if (openCount == 0) return
        for (pane in panes.toList()) {
            if (pane.gone.value) continue
            // #329: cancel the in-flight watchdog JOBS (not their state) — a receipt deadline must not fire
            // "not delivered" while the column shows "opening…". The outstanding-prompt state is deliberately
            // KEPT so the reconnect replay's [reconcilePromptReceipt] can still settle the bubble.
            cancelWatchdogs(pane)
            pane.convoId.value = null
            pane.opening.value = true
            pane.openFailed.value = false
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
            delay(SESSION_OPEN_TIMEOUT_MS)
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
        val pane = removePane(paneId) ?: return
        cancelWatchdogs(pane) // the column is gone — no deadline may fire onto a pane no longer in the list
        // Closed before the open landed: there is no conversation to reclaim YET, but one is on its way.
        // Record the session as disowned so the answer is recognised when it arrives — see [disowned].
        // A pane whose session is already gone has no answer coming and needs no record.
        if (pane.convoId.value == null && !pane.gone.value) disowned += pane.sessionId
        recount()
        val convo = pane.convoId.value ?: return
        // The idle-reclaim rule (the focused pane's), with the issue #329 correction: a column showing
        // [streaming] is normally left running so closing it doesn't kill the agent's turn — BUT a STALLED
        // column is not working, it is wedged. Skipping its reclaim (the old behavior) leaked the session on
        // the daemon, curable only by a full reconnect. A stalled-yet-"streaming" column is reclaimed too.
        val stalled = pane.sendStalled.value || pane.turnStalled.value
        if (pane.streaming.value && !stalled) return
        scope.launch { send(CloseSession(convo)) }
    }

    /**
     * Drop a column WITHOUT reclaiming its session and WITHOUT disowning it. The ordinary promotion path
     * does not come through here any more (releasing inlines in [releaseToFocus], on the open chokepoint);
     * what remains is the REPAIR verb for a refused promotion — a column whose session the focused chat
     * already shows must go away without a [CloseSession] that would hit the live conversation.
     */
    fun detach(paneId: Long) {
        val pane = removePane(paneId) ?: return
        cancelWatchdogs(pane) // #329: a removed pane keeps no live deadline
        recount()
    }

    /** Remove one pane and keep [focusedSlot] pointing at the same on-screen column: a removal LEFT of
     *  the focus slides it down by one. The shared half of [close] and [detach]. */
    private fun removePane(paneId: Long): SidePane? {
        val i = panes.indexOfFirst { it.paneId == paneId }
        if (i < 0) return null
        val pane = panes.removeAt(i)
        if (i < focusedSlot.value) focusedSlot.value -= 1
        return pane
    }

    /**
     * The focused chat is taking [sessionId] over. Called from the repository's one open chokepoint, so
     * EVERY way of focusing a session — a sidebar click, a promotion, a push tap, a deep link — means the
     * same thing when that session is currently in a column: promote it. The column lets go first, without
     * reclaiming the session ([detach]'s rule), so the answering `SessionLive` is no longer claimed here
     * and reaches the focused chat. Re-opening a just-disowned session also withdraws the disowning: the
     * user changed their mind, and that answer must not be met with a [CloseSession].
     */
    fun releaseToFocus(sessionId: String) {
        disowned.remove(sessionId)
        paneFor(sessionId)?.let { pane ->
            cancelWatchdogs(pane) // #329: the focused pane takes over its own delivery tracking from here
            // The focus moves INTO this column's place (issue #336): promoting a left column must not
            // teleport its conversation to wherever the focused chat happened to sit. Removing pane
            // index p and putting the focus at slot p is exactly "the chat walks over to that column".
            val p = panes.indexOfFirst { it.paneId == pane.paneId }
            panes.removeAt(p)
            focusedSlot.value = p
        }
        // Unconditional: disowned.remove above changes openCount's truth even when no pane matched
        // (a column closed before its open landed, then the same session re-focused — leaving the
        // stale count ≥ 1 would keep route()/claimsSessionLive reading snapshot state on every frame,
        // the exact hazard [openCount]'s doc exists to prevent).
        recount()
    }

    /** Drop every column without touching the sessions behind them (disconnect, machine switch, sign-out). */
    fun clear() {
        for (pane in panes) cancelWatchdogs(pane) // #329: no orphaned deadline may outlive the column list
        panes.clear()
        disowned.clear()
        focusedSlot.value = 0
        openCount = 0
    }

    /**
     * True when [f] answers a side pane's open, so the focused pane must NOT treat it as its own.
     * See the class doc — this is the single point where side panes take priority.
     */
    fun claimsSessionLive(f: SessionLive): Boolean = openCount > 0 && (
        panes.any { pane ->
            pane.convoId.value?.let { it == f.convoId } ?: (f.sessionId != null && f.sessionId == pane.sessionId)
        } ||
            // a column closed mid-open: its answer belongs to nobody, and least of all to the focused area
            (f.sessionId != null && f.sessionId in disowned)
        )

    /** Mirror [f] into the pane it belongs to. Never consumes: the caller's own handling runs regardless. */
    fun route(f: Frame) {
        if (openCount == 0) return
        when (f) {
            is SessionLive -> bind(f)
            is ConvoHistory -> byConvo(f.convoId)?.let { replay(it, f) }
            // real turn evidence first (issue #329): any of these retires this pane's stall watchdogs before
            // the frame is applied, exactly as the focused path calls promptEvidence() ahead of its handling.
            is AssistantChunk -> byConvo(f.convoId)?.let { promptEvidence(it); it.transcript.appendChunk(f) }
            is ToolEvent -> byConvo(f.convoId)?.let { promptEvidence(it); it.transcript.onToolEvent(f) }
            is TurnDone -> byConvo(f.convoId)?.let { promptEvidence(it); endTurn(it, f) }
            // delivery receipt (issue #329): clear any transport error AND record the daemon has the prompt —
            // flips the bubble to "delivered" and, on a legacy daemon, hands off to the ack→turn watchdog.
            is PromptAck -> byConvo(f.convoId)?.let { it.error.value = null; promptDelivered(it, f.promptId) }
            // Questions route here too. Filtering them out left AskUserQuestion in a column with NO surface
            // at all — the bell inbox excludes questions by design, and the focused question card is gated
            // on the focused conversation — so the column streamed until the daemon timed the ask out and
            // the turn died in silence. That is exactly the "lost ask" class v1.9.5 just closed (#321/#326),
            // reappearing one column over.
            is PermissionAsk -> byConvo(f.convoId)?.let { fileAsk(it, f) }
            is AskWithdrawn -> byConvo(f.convoId)?.let { withdrawAsk(it, f) }
            is SessionGone -> byConvo(f.convoId)?.let {
                it.gone.value = true
                it.streaming.value = false
                // the session is gone, so a stall is meaningless — retire both watchdogs and their cues, or a
                // deadline would fire "not delivered" / "no response" onto a column that has no session left to
                // deliver to (issue #329). The prompt-layer fresh-resend a SessionGone triggers on the focused
                // path is deliberately NOT mirrored: reopenAll() already re-opens columns on the link's return.
                cancelWatchdogs(it)
                it.sendStalled.value = false
                it.turnStalled.value = false
                it.turnQueued.value = false
                it.awaitingTurn = false
                // no verdict can be delivered to a conversation that no longer exists, so nothing may keep
                // offering one — a card left behind here answers into the void and reads as "it worked"
                clearAsks(it)
            }
            is PocketError -> f.convoId?.let { c -> byConvo(c)?.let { it.error.value = f.message } }
            else -> Unit
        }
    }

    /**
     * File [f] into [pane]'s card slot, with the focused chat's M1 queueing semantics (see [SidePane.askQueue]).
     *
     * The four cases are the focused path's, one for one: a duplicate of the card on screen (the daemon
     * resurfaces pending asks on every reattach) refreshes it in place, a duplicate of a QUEUED one
     * refreshes there, an empty slot takes the ask, and anything else waits its turn.
     */
    private fun fileAsk(pane: SidePane, f: PermissionAsk) {
        // a card sitting in its terminal "timed out" display must not dam the queue: a NEW live ask retires
        // it, which is also the only way that terminal state is ever left behind by something other than a tap
        pane.pendingAsk.value?.let { if (pane.timedOutAskId.value == it.askId) advanceAsk(pane) }
        val current = pane.pendingAsk.value
        val queuedAt = pane.askQueue.indexOfFirst { it.askId == f.askId }
        when {
            current?.askId == f.askId -> pane.pendingAsk.value = f
            queuedAt >= 0 -> pane.askQueue[queuedAt] = f
            current == null -> pane.pendingAsk.value = f
            else -> pane.askQueue.add(f)
        }
    }

    /** The daemon retired one of [pane]'s asks — mirrors the focused path's AskWithdrawn handling. */
    private fun withdrawAsk(pane: SidePane, f: AskWithdrawn) {
        val current = pane.pendingAsk.value
        if (current?.askId != f.askId) {
            // a queued card the user never saw was retired (agent cancel / timeout) — drop it silently,
            // there is nothing to explain about a card that was never drawn
            pane.askQueue.removeAll { it.askId == f.askId }
            return
        }
        if (f.reason == AskWithdrawnReason.TIMED_OUT && !current.isQuestion) {
            // issue #100, one column over: hold the card in its terminal "timed out" state instead of
            // letting it vanish — a card that silently disappears reads as "my decision went through"
            pane.timedOutAskId.value = f.askId
            return
        }
        // agent moved on / session closed / a question timed out. A question leaves a muted note, because
        // a question card that just evaporates leaves the column looking like the agent stopped talking
        // for no reason; then whatever queued behind it comes up.
        if (current.isQuestion) pane.messages.add(ChatItem.QuestionsWithdrawn)
        advanceAsk(pane)
    }

    /** Retire [pane]'s current card and surface the next queued ask, if any. The terminal timeout state
     *  dies with the card it described. */
    private fun advanceAsk(pane: SidePane) {
        pane.timedOutAskId.value = null
        pane.pendingAsk.value = pane.askQueue.removeFirstOrNull()
    }

    /** Drop every ask a pane is holding — for the paths where no verdict can land anymore. */
    private fun clearAsks(pane: SidePane) {
        pane.askQueue.clear()
        pane.timedOutAskId.value = null
        pane.pendingAsk.value = null
    }

    /** Resolving an approval anywhere retires the card here too — the pane must not keep offering a decision
     *  that has already been made (from the bell popover, the phone, or the computer itself). Also how a
     *  column's OWN verdict retires its card: the decision goes out through the repository, which lands
     *  back here, so one path advances the queue no matter which surface decided. */
    fun noteApprovalResolved(convoId: String, askId: String) {
        val pane = byConvo(convoId) ?: return
        if (pane.pendingAsk.value?.askId == askId) advanceAsk(pane)
        else pane.askQueue.removeAll { it.askId == askId }
    }

    /**
     * The user answered a column's question card: note the picks in THAT column's stream, then advance.
     *
     * The note is what makes the answer readable afterwards — the agent's next turn quotes nothing back,
     * so without it the transcript shows a question and then, apparently, nothing. Mirrors the focused
     * path's `ChatItem.QuestionsAnswered`, and lives here (not in the repository) because it belongs to
     * the pane's own transcript, and because the queue must advance in exactly one place.
     */
    fun noteQuestionsAnswered(convoId: String, askId: String, items: List<Pair<String, String>>) {
        val pane = byConvo(convoId) ?: return
        if (pane.pendingAsk.value?.askId != askId) {
            pane.askQueue.removeAll { it.askId == askId }
            return
        }
        pane.messages.add(ChatItem.QuestionsAnswered(items))
        advanceAsk(pane)
    }

    /** Send [text] into [pane]'s conversation. False = nothing to send, or the pane's open has not landed. */
    fun sendPrompt(pane: SidePane, text: String): Boolean {
        val convo = pane.convoId.value ?: return false
        if (text.isBlank()) return false
        val promptId = newPromptId()
        // A receipt/turn deadline from the previous prompt may still be pending (or already fired while this
        // column sat in the background): cancel it and clear its cues so the fresh bubble starts its own epoch.
        cancelWatchdogs(pane)
        pane.sendStalled.value = false
        pane.turnStalled.value = false
        pane.turnQueued.value = false
        pane.messages.add(ChatItem.User(text, pending = true, promptId = promptId))
        pane.promptOutstanding = true // there is now a bubble for the next turn boundary to settle
        pane.activePromptId = promptId
        pane.promptRetryText = text // #329 resend payload — text only (a column's send carries no images)
        pane.turnStartMark = TimeSource.Monotonic.markNow()
        pane.promptQueued = pane.streaming.value // a send into a running turn is QUEUED by the CLI (snapshot first)
        pane.streaming.value = true
        scope.launch { send(SendPrompt(convo, text, promptId = promptId)) }
        armReceiptWatchdog(pane, promptId)
        return true
    }

    /**
     * Bound the wait for [pane]'s delivery receipt (the focused path's `armPromptWatchdog`, #78): outboxes
     * buffer across reconnects, so a prompt sent into a link that quietly stopped answering would otherwise
     * stay "sending…" forever. On the deadline — and only while THIS prompt is still the outstanding one —
     * surface the column's "not delivered" cue.
     *
     * KEY DIVERGENCE from the focused path (implemented on purpose): a column NEVER forces a reconnect here.
     * The focused watchdog fires `launchTransport(reconnect = true)`; a background column doing that would
     * `reopenAll()` and drag EVERY other column and the focused chat through a reopen because one un-watched
     * column timed out. Reconnect stays the global / focused mechanism — a column only marks itself stalled
     * and offers a per-pane resend ([resendStalled]).
     */
    private fun armReceiptWatchdog(pane: SidePane, promptId: String) {
        pane.promptWatchdog?.cancel()
        pane.promptWatchdog = scope.launch {
            delay(receiptTimeoutMs)
            if (pane.promptOutstanding && pane.activePromptId == promptId) pane.sendStalled.value = true
        }
    }

    /**
     * [pane]'s delivery receipt landed (PromptAck, #66/#104): the daemon wrote the prompt to the agent's
     * stdin — delivered, but not yet a started turn. Mirrors the focused path's `promptDelivered`.
     *
     * Guarded on an exact id AND on the bubble still being outstanding: a PromptAck can race BEHIND the first
     * [dev.ccpocket.protocol.AssistantChunk] (which already retired [activePromptId] via [promptEvidence]),
     * and a receipt for an older queued send must never move the newest prompt's watchdog.
     */
    private fun promptDelivered(pane: SidePane, promptId: String) {
        if (pane.activePromptId != promptId || !pane.promptOutstanding) return
        pane.promptWatchdog?.cancel(); pane.promptWatchdog = null // receipt arrived — the delivery deadline is moot
        pane.sendStalled.value = false
        // flip THIS bubble's marker (ChatItem.User carries `delivered`, exactly as the focused path sets it).
        // promptOutstanding is deliberately left set: the id-agnostic bubble settle in [endTurn] is the backstop
        // that closes a bubble whose id a later resend replaced, so it must stay armed until the turn ends.
        val i = pane.messages.indexOfLast { it is ChatItem.User && it.promptId == promptId }
        (pane.messages.getOrNull(i) as? ChatItem.User)?.let { pane.messages[i] = it.copy(pending = false, delivered = true) }
        when {
            // a ledger-capable daemon owns redelivery; a quiet first-token window is normal, so no turn watchdog
            daemonOwnsPromptRecovery() -> Unit
            pane.promptQueued && pane.streaming.value -> { pane.awaitingTurn = true; armTurnWatchdog(pane, promptId, queued = true) }
            pane.streaming.value -> { pane.awaitingTurn = true; armTurnWatchdog(pane, promptId, queued = false) }
        }
    }

    /**
     * Second-stage watchdog for [pane] (the focused path's `armTurnWatchdog`, #104): a delivered prompt that
     * produces no turn frame within the deadline was swallowed by a wedged / mid-relaunch agent. Self-guarded
     * at fire time — a real frame ([awaitingTurn] cleared), a newer send ([activePromptId] moved), a session
     * change ([convoId] moved off the one captured here) or a turn that already ended ([streaming] false) all
     * no-op it. [queued] gets the calm [turnQueued]; otherwise the actionable [turnStalled].
     */
    private fun armTurnWatchdog(pane: SidePane, promptId: String, queued: Boolean) {
        val c = pane.convoId.value
        pane.turnWatchdog?.cancel()
        pane.turnWatchdog = scope.launch {
            delay(turnTimeoutMs)
            if (!pane.awaitingTurn || pane.activePromptId != promptId || pane.convoId.value != c || !pane.streaming.value) return@launch
            if (queued) pane.turnQueued.value = true else pane.turnStalled.value = true
        }
    }

    /**
     * Real turn evidence for [pane] (chunk / tool / turn-end): the agent is actually producing. Retires both
     * watchdogs, clears every stall cue, and drops the resend payload — the focused path's `promptEvidence`.
     *
     * DELIBERATE SIMPLIFICATION: a column does NOT do the focused path's `exactPrompt` old-vs-new-turn frame
     * distinction. For a mid-turn queued send, ANY real frame (even one from the still-running old turn) clears
     * the watchdog here; the only cost is the "queued" status showing for a little less time — no correctness
     * loss, because the bubble itself is settled by [endTurn] on the queued prompt's OWN TurnDone. The bubble
     * is intentionally NOT flipped here (unlike the focused path): [endTurn] and [promptDelivered] own that.
     */
    private fun promptEvidence(pane: SidePane) {
        cancelWatchdogs(pane)
        pane.awaitingTurn = false
        pane.sendStalled.value = false
        pane.turnStalled.value = false
        pane.turnQueued.value = false
        pane.activePromptId = null
        pane.promptRetryText = null
    }

    /**
     * A reconnect replay resolved [pane]'s pending bubble (the matching USER row came back no longer pending):
     * stronger evidence than link liveness, so retire the receipt/turn machinery too. Mirrors the focused
     * path's `reconcilePromptReceiptFromHistory`, fed the before/after lists by [ChatTranscript.mergeHistory].
     */
    private fun reconcilePromptReceipt(pane: SidePane, before: List<ChatItem>, after: List<ChatItem>) {
        if (!pane.promptOutstanding) return
        val promptId = pane.activePromptId ?: return
        val wasPending = before.any { it is ChatItem.User && it.promptId == promptId && it.pending }
        val remainsPending = after.any { it is ChatItem.User && it.promptId == promptId && it.pending }
        if (wasPending && !remainsPending) promptEvidence(pane)
    }

    /**
     * The user tapped [pane]'s "no response — resend" cue, or its "not delivered" one (the focused path's
     * `resendStalledPrompt`, #104). Re-drive under a FRESH id — the live daemon Conversation already recorded
     * the original id, so a same-id resend would be deduped (#66) into a bare re-ack that never runs. NO second
     * User bubble is added (that duplicate "You" is the very symptom #329 is about): the existing bubble settles
     * through [endTurn]'s id-agnostic tail scan when the re-driven turn ends. Guarded on a live stall cue, which
     * a real frame retracts first, so a resend can't land on top of a turn that actually started.
     */
    fun resendStalled(pane: SidePane) {
        if (!pane.sendStalled.value && !pane.turnStalled.value) return
        val convo = pane.convoId.value ?: return
        val text = pane.promptRetryText ?: return
        cancelWatchdogs(pane)
        pane.sendStalled.value = false
        pane.turnStalled.value = false
        pane.turnQueued.value = false
        val freshId = newPromptId()
        pane.activePromptId = freshId
        pane.promptRetryText = text
        pane.promptQueued = false // the stalled turn never started — the re-driven copy expects the strict deadline
        pane.promptOutstanding = true
        pane.streaming.value = true
        scope.launch { send(SendPrompt(convo, text, promptId = freshId)) }
        armReceiptWatchdog(pane, freshId)
    }

    /** Cancel [pane]'s two watchdog jobs, so a pending deadline can neither fire onto a removed pane nor
     *  survive a re-attach. State (the cues, [activePromptId], [promptOutstanding]) is left to the caller —
     *  a reconnect keeps it for [reconcilePromptReceipt]; a removal doesn't care. */
    private fun cancelWatchdogs(pane: SidePane) {
        pane.promptWatchdog?.cancel(); pane.promptWatchdog = null
        pane.turnWatchdog?.cancel(); pane.turnWatchdog = null
    }

    /**
     * Interrupt [pane]'s OWN turn — the column's ■ button and its Esc, which used to reach the focused
     * conversation's `cancelTurn` through the delegating pane model and stop whatever the user was
     * watching in the other column instead.
     *
     * Nothing flips locally: the answering `TurnDone` ends the column's streaming state, exactly as it
     * does for the focused chat. Guessing here would only mean a column that says "idle" while the agent
     * is still writing, and the frame that tells the truth is milliseconds away.
     */
    fun stopTurn(pane: SidePane) {
        val convo = pane.convoId.value ?: return
        if (!pane.streaming.value) return
        scope.launch { send(CancelTurn(convo)) }
    }

    /**
     * Switch [pane]'s OWN model — the focused path's `switchModel`, one column over: the same `/model`
     * command interception, the same agent-compatibility gate, the same optimistic set (this pane's next
     * `SessionLive` corrects it to the resolved id — see [bind]). What it deliberately does NOT mirror is
     * the effort/service-tier follow-up: a column tracks neither, so there is nothing stale to clear.
     */
    fun switchModel(pane: SidePane, name: String) {
        val convo = pane.convoId.value ?: return
        val target = name.trim()
        if (target.isEmpty() || target == pane.model.value) return
        if (!isModelCompatibleWithAgent(pane.agent, target)) return
        pane.model.value = target
        scope.launch { send(SendPrompt(convo, "/model $target")) }
    }

    private fun byConvo(convoId: String): SidePane? = panes.firstOrNull { it.convoId.value == convoId }

    private fun bind(f: SessionLive) {
        val pane = panes.firstOrNull { it.convoId.value == f.convoId }
            ?: panes.firstOrNull { it.convoId.value == null && f.sessionId != null && f.sessionId == it.sessionId }
        if (pane == null) {
            // Nobody is waiting for this answer, but it may be the one we disowned in [close]: the daemon
            // has now mounted a conversation with no viewer at all. Hand it straight back — the record is
            // consumed here, so a legitimate later open of the same session binds normally.
            val orphan = f.sessionId ?: return
            if (disowned.remove(orphan)) {
                recount()
                scope.launch { send(CloseSession(f.convoId)) }
            }
            return
        }
        pane.convoId.value = f.convoId
        pane.opening.value = false
        pane.openFailed.value = false
        pane.gone.value = false
        f.model?.let { pane.model.value = it } // daemon truth corrects the optimistic switchModel guess too
        // daemon truth beats the local guess, exactly as the focused path takes it — the stamp included:
        // a turn killed MID-THINKING has no TurnDone coming to close its block, so a reattach that reports
        // idle is the only chance to stamp it. Without this the column keeps an eternal "Thinking…" row
        // (no history replay can fix it — a replay carries no thinking rows at all) and, worse, leaves the
        // thinking clock armed, so the NEXT turn's first tool call stamps that stale row with the wall time
        // since the dead turn.
        f.executing?.let { exec ->
            pane.streaming.value = exec
            if (!exec) pane.transcript.finishThinking()
        }
    }

    /** This column's ConvoHistory, merged by the same [ChatTranscript.mergeHistory] the focused chat uses
     *  (issue #147 full/delta, issue #107 echo). Only the cursor is the pane's own — it keeps a bare one,
     *  where the repository keys its cursor by session. */
    private fun replay(pane: SidePane, f: ConvoHistory) {
        // the receipt reconciliation (issue #329) rides the same before/after seam the focused path uses:
        // if the replay resolved this pane's pending bubble, that is delivery evidence — settle the watchdogs.
        pane.transcript.mergeHistory(f) { before, after -> reconcilePromptReceipt(pane, before, after) }
            ?.let { pane.historySeq = it }
    }

    private fun endTurn(pane: SidePane, f: TurnDone) {
        // the turn is over, so no bubble is still "sending"; a bubble stuck at pending would misreport a
        // prompt the agent demonstrably answered. Gated and tail-scanned exactly like the focused path's
        // promptEvidence: a TurnDone with nothing outstanding (a reattached turn, work started at the
        // computer) settles nothing. This id-AGNOSTIC scan is also the #329 backstop that closes a bubble
        // whose id a [resendStalled] replaced — which is why [promptDelivered] never clears promptOutstanding.
        if (pane.promptOutstanding) {
            pane.promptOutstanding = false
            val i = pane.messages.indexOfLast { it is ChatItem.User && it.pending }
            (pane.messages.getOrNull(i) as? ChatItem.User)?.let { pane.messages[i] = it.copy(pending = false) }
        }
        // …and the boundary itself is the shared one (see [ChatTranscript.endTurn]); the completion marker
        // and its stopwatch stay here, because each path times ITS OWN turn from its own send.
        val wasLive = pane.transcript.endTurn(f.error)
        if (wasLive && f.error == null) {
            pane.messages.add(ChatItem.TurnEnded(pane.turnStartMark?.elapsedNow()?.inWholeSeconds?.toInt()))
        }
        pane.turnStartMark = null
    }
}
