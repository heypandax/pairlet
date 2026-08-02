package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.conversation.sinkKey
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.isTerminal
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * The wiring layer between [HandoffRegistry]/[HandoffGuard] and the transports (SESSION-HANDOFF.md
 * §9.2): it owns the set of attached client sinks and pushes [HandoffUpdated] on every state change,
 * so both sides' UI reconciles from daemon truth (§5.3 item 8). Installed once on
 * [dev.ccpocket.daemon.session.SessionRegistry.handoffs] by DaemonCore, which also runs [sweepLoop]
 * on the core scope — the same both-transports footing as the schedule pump.
 *
 * Fan-out is DIFF-based ([reconcile]): transitions the registry settles INSIDE another call (a guard
 * check's internal sweep expiring a WAITING handoff, an accept() settling a stale lease) return to no
 * fan-out-aware caller, so instead of trusting each mutation's return value alone we also compare the
 * ledger against the last broadcast statuses and announce whatever moved. Router mutations still
 * [broadcast] their result eagerly (instant feedback) and then [reconcile] for the stragglers.
 */
class HandoffService(
    val registry: HandoffRegistry = HandoffRegistry(),
    /** §5.4 upper bound on how long a graceful recall waits for the interrupted turn to reach a stable
     *  point before taking control back anyway (and stamping [SessionHandoff.recallIncomplete]).
     *  Injectable so tests don't sleep for the production bound. */
    private val recallTimeoutMs: Long = RECALL_TIMEOUT_MS,
    private val recallPollMs: Long = RECALL_POLL_MS,
) {
    val guard = HandoffGuard(registry)
    private val log = logger("HandoffService")

    /** The live-session levers the graceful recall needs (§5.4) — [dev.ccpocket.daemon.session.SessionRegistry]
     *  implements it and installs itself the moment it takes ownership of this service. Null = no live
     *  session view at all (a bare registry in a unit test): every recall then settles immediately, which
     *  is exactly the pre-§5.4 behaviour. */
    @Volatile
    var sessions: SessionTurnControl? = null

    /** The owner's contact ledger (SESSION-HANDOFF.md §4.1), installed by RelayClient alongside the
     *  collaborator control plane; null until then (and on a LAN-only `serve`). The router reads it to
     *  validate/label a [dev.ccpocket.protocol.CreateHandoff.recipientDeviceId] binding — when null,
     *  bindings pass through unvalidated (dev/local mode) but are still ENFORCED at accept time. */
    @Volatile
    var collaborators: CollaboratorDirectory? = null

    /** The content-free offer push (§3.4), installed by [dev.ccpocket.daemon.relay.RelayClient] — the only
     *  component that knows whether the relay can even deliver a targeted push. Null on a LAN-only `serve`
     *  and in unit tests: a missing hook simply means no nudge, and the recipient still discovers the offer
     *  on its next connect/foreground pull (the daemon's ledger is the source of truth, the push is not). */
    @Volatile
    var offerPush: OfferPush? = null

    /** One fan-out target: the sink + the COLLABORATOR deviceId it is restricted to (null = a
     *  full-power owner device that sees every update). */
    private class Target(val sink: OutboundSink, val recipientDeviceId: String?)

    /** Attached fan-out targets by sink identity ([sinkKey]): relay sinks are re-attached per frame
     *  under their stable `dev:<deviceId>` key (bounded by the paired-device count — a revoked
     *  device's stale sink is a no-op, its sealAndSend drops without a live E2E session); LAN sinks
     *  attach at connect and MUST [detach] at disconnect. */
    private val clients = ConcurrentHashMap<Any, Target>()

    /** Per-collaborator grant enforcement (SESSION-HANDOFF.md §8.1) — one guard per contact deviceId,
     *  living here so both transports and the fan-out share one instance. In-memory only: what matters
     *  across restarts (handoff status + recipient binding) is the registry's persisted truth. */
    private val collabGuards = ConcurrentHashMap<String, CollaboratorGuard>()

    /** handoffId -> the status last announced (or first observed) — the [reconcile] diff baseline.
     *  Guarded by itself; bounded by the registry's own history cap. */
    private val lastBroadcast = HashMap<String, HandoffStatus>()

    /** Register a fan-out target. [recipientDeviceId] non-null marks a COLLABORATOR credential's sink:
     *  it receives ONLY updates for handoffs addressed to that device (its offers + their transitions —
     *  the §4.2 offer delivery), never the owner's other handoffs. */
    fun attach(sink: OutboundSink, recipientDeviceId: String? = null) {
        clients[sinkKey(sink)] = Target(sink, recipientDeviceId)
    }

    fun detach(sink: OutboundSink) { clients.remove(sinkKey(sink)) }

    fun collaboratorGuard(deviceId: String): CollaboratorGuard =
        collabGuards.computeIfAbsent(deviceId) { CollaboratorGuard(it, registry) }

    /** Push [changed] to every attached client its credential may see: owner devices see everything;
     *  a collaborator's sink only handoffs bound to its own deviceId (fail closed — an unaddressed
     *  handoff never reaches a restricted sink). The egress whitelist at seal time is the second gate.
     *
     *  CUT FIRST, ANNOUNCE SECOND (§5.3 item 7): every transition this daemon fans out passes through
     *  here, so this is also the choke point where a recipient that just lost its Grant loses its live
     *  SESSION view ([cutRecipientSinks]) — before the [HandoffUpdated] that tells the world about it.
     *  The reverse order would hand a recipient app the news plus a still-live stream to keep reading;
     *  this order means the last frame it can ever receive from that session is the one saying it's over.
     *  (Re-attaching after the announcement is separately impossible: [CollaboratorGuard.vetOpen]
     *  refuses every open once the registry row — written before any broadcast — left IN_PROGRESS.) */
    suspend fun broadcast(changed: List<SessionHandoff>) {
        if (changed.isEmpty()) return
        for (h in changed) cutRecipientSinks(h)
        synchronized(lastBroadcast) { changed.forEach { lastBroadcast[it.id] = it.status } }
        for (h in changed) {
            for (t in clients.values) {
                if (t.recipientDeviceId != null && h.recipientDeviceId != t.recipientDeviceId) continue
                runCatching { t.sink.emit(HandoffUpdated(h)) }
            }
        }
    }

    /** Deliver a non-handoff owner-plane frame (Collaborator* contact changes) to every FULL-POWER
     *  attached client — restricted sinks are skipped entirely (their egress caps would drop these
     *  frames anyway; keeping them out of the target set is the belt to that brace). */
    suspend fun emitToOwners(frame: dev.ccpocket.protocol.ToPhone) {
        for (t in clients.values) {
            if (t.recipientDeviceId != null) continue
            runCatching { t.sink.emit(frame) }
        }
    }

    /** A collaborator link was severed: settle every Grant bound to that device (WAITING→CANCELLED,
     *  IN_PROGRESS→RECALLED) and fan the transitions out — [CollaboratorService.remove]'s first step. */
    suspend fun revokeRecipient(deviceId: String) {
        broadcast(registry.revokeRecipient(deviceId))
    }

    /**
     * §3.4: nudge an OFFLINE bound recipient that an offer is waiting. Called once, right after the offer is
     * created — recall/return/expiry deliberately do NOT push: those land on the recipient's live data plane
     * when it is there, and when it is not, the state it would have been told about is already gone by the
     * time it opens the app.
     *
     * Two preconditions, both checked here so every caller (and every transport) gets the same answer:
     * the offer must actually be WAITING (a create whose internal sweep already settled it must not ring
     * someone's phone about work that is over), and it must be BOUND to a device — an unbound handoff has
     * nobody to address, and the account fan-out is exactly what §3.4 must not use.
     *
     * Failures are swallowed: a push that could not be queued must never fail the create that earned it.
     */
    suspend fun announceOffer(h: SessionHandoff) {
        if (h.status != HandoffStatus.WAITING) return
        val recipient = h.recipientDeviceId ?: return
        val hook = offerPush ?: return
        runCatching { hook.send(h.id, recipient) }
            .onFailure { log.warn("handoff ${h.id.take(8)}…: offer push not queued: ${it.message}") }
    }

    // ---- §5.3 item 7: the Grant's sinks die with the Grant --------------------

    /** Is this recipient's live SESSION view still justified? Only while it actually holds the Grant.
     *  WAITING has no session access at all yet, so everything else — the five terminal states, the
     *  RETURNED hand-back that still awaits the initiator's acknowledge, and an UNKNOWN a newer peer
     *  wrote (fail closed) — means the view must go. `recallPending` is deliberately NOT included: the
     *  status is still IN_PROGRESS there and the recipient may watch its own turn being interrupted;
     *  the cut lands with the terminal RECALLED [settleRecall] settles moments later. */
    private val HandoffStatus.grantEnded: Boolean
        get() = this != HandoffStatus.IN_PROGRESS && this != HandoffStatus.WAITING

    /**
     * SESSION-HANDOFF.md §5.3 item 7 — "Credential 撤销或 Handoff 到终态后，所有关联 sink 与在途权限立即失效".
     *
     * [CollaboratorGuard] kills the recipient's INBOUND rights the instant the Grant leaves IN_PROGRESS,
     * but a conversation's fan-out set is OUTBOUND state no guard ever sees: the sink the recipient
     * attached while it held the lease keeps streaming the owner's SUBSEQUENT AssistantChunk / ToolEvent /
     * transcript until that app volunteers a CloseSession. Until now only revoking the whole collaborator
     * credential ([CollaboratorService.remove] → revokeCredential → force-close) cut it.
     *
     * Scoped twice over, so no legitimate view is collateral damage:
     *  - by GRANT — only the convos THIS handoff opened ([CollaboratorGuard.convosGrantedBy]); the same
     *    contact may hold a second, still-IN_PROGRESS grant on another session;
     *  - by CREDENTIAL — the removal keys on the recipient's own stable `dev:<deviceId>` fan-out identity,
     *    never on "close the conversation". The initiator's §3.3 auto-spectate view keys on
     *    `dev:<initiatorDeviceId>`, and [HandoffRegistry.accept] refuses a self-accept, so the two keys
     *    cannot coincide: the owner keeps streaming its own session through the transition.
     *
     * Idempotent and quiet by construction: a status that never granted anything, a device that never
     * sent a frame, a sink already detached and a conversation already closed all cut nothing.
     */
    private suspend fun cutRecipientSinks(h: SessionHandoff) {
        if (!h.status.grantEnded) return
        val deviceId = h.recipientDeviceId ?: return
        val guard = collabGuards[deviceId] ?: return // never vetted a frame → never opened anything
        val convoIds = guard.convosGrantedBy(h.id)
        if (convoIds.isEmpty()) return
        val cut = runCatching { sessions?.detachDevice(convoIds, deviceId) ?: 0 }
            .onFailure { log.warn("handoff ${h.id.take(8)}…: could not cut the recipient's session view: ${it.message}") }
            .getOrDefault(0)
        if (cut > 0) {
            log.info(
                "handoff ${h.id.take(8)}… ${h.status.name}: cut $cut live session view(s) from " +
                    "recipient ${deviceId.take(8)}… (§5.3 item 7)",
            )
        }
    }

    // ---- §5.4 graceful recall ---------------------------------------------

    /** What [beginRecall] decided. [Pending] means the daemon armed the hand-back and the caller must
     *  run [settleRecall] OFF the inbound pump (it waits on the turn); the entity in it is the
     *  still-IN_PROGRESS row marked [SessionHandoff.recallPending], to be pushed to both sides at once. */
    sealed interface RecallOutcome {
        data class Settled(val handoff: SessionHandoff) : RecallOutcome
        data class Pending(val handoff: SessionHandoff) : RecallOutcome
        data class Refused(val code: String, val message: String) : RecallOutcome
    }

    /**
     * §5.4 STEP 1: the initiator wants the session back.
     *  - IDLE session (no live turn) → straight to RECALLED, the lease dies now — nothing is running,
     *    so there is no stable point to wait for;
     *  - EXECUTING turn → [HandoffRegistry.markRecallPending]: the lease is marked recallRequested (so
     *    every subsequent prompt/verdict — the recipient's included — is refused) and the row is marked
     *    recallPending. Control is NOT transferred yet; [settleRecall] does that at the stable point.
     */
    suspend fun beginRecall(handoffId: String, deviceId: String): RecallOutcome {
        val h = registry.byId(handoffId)
        val executing = h != null &&
            runCatching { sessions?.turnExecuting(h.sourceSessionId) == true }.getOrDefault(false)
        val out = if (executing) registry.markRecallPending(handoffId, deviceId) else registry.recall(handoffId, deviceId)
        return when (out) {
            is HandoffRegistry.HandoffOutcome.Refused -> RecallOutcome.Refused(out.code, out.message)
            is HandoffRegistry.HandoffOutcome.Ok ->
                // markRecallPending settles immediately when there was no lease to wait on
                if (out.handoff.status == HandoffStatus.IN_PROGRESS) RecallOutcome.Pending(out.handoff)
                else RecallOutcome.Settled(out.handoff)
        }
    }

    /**
     * §5.4 STEP 2 (run OFF the inbound pump — it suspends for as long as the turn takes): interrupt the
     * executing turn, wait for the stable point, then take control back and tell both sides.
     *
     * The stable point is "the session is no longer executing" — a TurnDone landed, the agent process
     * died, or the conversation is gone entirely — polled because the turn's end arrives on the agent's
     * own stream, not through this service. The wait is BOUNDED ([recallTimeoutMs]): a turn that will
     * not stop must not hold the owner hostage, so control is taken anyway and the row is stamped
     * [SessionHandoff.recallIncomplete]. The same honest stamp records background work the daemon
     * cannot kill (a detached shell the agent spawned survives the interrupt).
     */
    suspend fun settleRecall(handoffId: String) {
        val h = registry.byId(handoffId) ?: return
        val sid = h.sourceSessionId
        val ctrl = sessions
        runCatching { ctrl?.interruptTurn(sid) }
            .onFailure { log.warn("recall ${handoffId.take(8)}…: interrupt failed: ${it.message}") }
        var stopped = true
        if (ctrl != null) {
            val deadline = System.currentTimeMillis() + recallTimeoutMs
            while (runCatching { ctrl.turnExecuting(sid) }.getOrDefault(false)) {
                if (System.currentTimeMillis() >= deadline) {
                    stopped = false
                    log.warn("recall ${handoffId.take(8)}…: the turn did not stop within ${recallTimeoutMs}ms — taking control back anyway")
                    break
                }
                delay(recallPollMs)
            }
        }
        val leftovers = runCatching { ctrl?.hasUnstoppableWork(sid) == true }.getOrDefault(false)
        when (val out = registry.settleRecall(handoffId, incomplete = !stopped || leftovers)) {
            is HandoffRegistry.HandoffOutcome.Ok -> broadcast(listOf(out.handoff))
            // the row already left IN_PROGRESS (the recipient returned first, or the sweep settled it):
            // announce whatever the ledger says now rather than overwrite a legitimate transition
            is HandoffRegistry.HandoffOutcome.Refused -> reconcile()
        }
    }

    /** Sweep the clock forward, then fan out every handoff whose status moved since its last
     *  broadcast. Handoffs seen for the FIRST time (boot recovery, pre-wiring history) only seed the
     *  baseline — replaying a page of terminal history at every daemon boot would be noise. */
    suspend fun reconcile(now: Long = System.currentTimeMillis()) {
        registry.sweep(now)
        val all = registry.list()
        val changed = synchronized(lastBroadcast) {
            val moved = all.filter { h ->
                val prev = lastBroadcast.put(h.id, h.status)
                prev != null && prev != h.status
            }
            lastBroadcast.keys.retainAll(all.mapTo(HashSet()) { it.id }) // pruned history drops out
            moved
        }
        // §5.3 item 7 BACKSTOP, over the whole ledger rather than the diff — two ended Grants a diff
        // structurally cannot see:
        //  - BOOT RECOVERY: rows the registry normalized to a terminal state (dead lease, expired
        //    WAITING, a recall that was in flight at shutdown) are first-seen here, so they only SEED
        //    the baseline and never reach [broadcast];
        //  - a recipient OpenSession already IN FLIGHT when the transition landed: it passed the guard
        //    while the Grant was live and binds its convo just AFTER that transition's own cut ran.
        // Cheap (one map lookup per row, and the second pass detaches nothing) and idempotent, so it can
        // run on every sweep — which also bounds the window of any transition settled inside another
        // call's internal sweep, with no fan-out-aware caller, to one [SWEEP_SCAN_MS].
        for (h in all) cutRecipientSinks(h)
        if (changed.isNotEmpty()) log.info("reconcile: ${changed.size} handoff transition(s) fanned out")
        broadcast(changed)
    }

    /** Sessions holding a non-terminal handoff — the idle reaper must never reclaim these (§9.2):
     *  a WAITING/IN_PROGRESS session reaped mid-handoff would strand the recipient on a dead convo. */
    fun activeSessionIds(): Set<String> =
        registry.list().filterNot { it.status.isTerminal }.mapTo(HashSet()) { it.sourceSessionId }

    /** The periodic expiry pump (the SchedulerService.runLoop pattern): WAITING past its deadline →
     *  EXPIRED, a dead lease → RECALLED, each fanned out as [HandoffUpdated]. */
    suspend fun sweepLoop(intervalMs: Long = SWEEP_SCAN_MS) {
        runCatching { reconcile() } // seed the diff baseline; boot-recovered history is not re-announced
        while (true) {
            delay(intervalMs)
            runCatching { reconcile() }
        }
    }

    companion object {
        /** Sweep cadence — expiry/lease granularity is minutes, so half a minute of slack is fine
         *  (the guard itself never honors an outrun deadline; the sweep only SETTLES + announces). */
        const val SWEEP_SCAN_MS = 30_000L

        /** How long a graceful recall waits for an interrupted turn to settle. Generous enough for a
         *  tool call in flight to unwind (an interrupt lands between stream events, not mid-syscall),
         *  short enough that the owner is never stuck watching a spinner. */
        const val RECALL_TIMEOUT_MS = 15_000L
        const val RECALL_POLL_MS = 100L
    }
}

/**
 * The "an offer is waiting for you" nudge (§3.4). Takes ONLY the two opaque ids — the id to route back to
 * the inbox and the device to address — so the transport that implements it structurally cannot widen the
 * payload into carrying brief/project/path even by accident.
 */
fun interface OfferPush {
    suspend fun send(handoffId: String, recipientDeviceId: String)
}

/**
 * The live-session levers a graceful recall (SESSION-HANDOFF.md §5.4) needs, keyed by the handoff's
 * PERSISTENT sessionId — implemented by [dev.ccpocket.daemon.session.SessionRegistry], which owns the
 * live conversations. Kept an interface so the handoff plane never depends on the session package
 * (the dependency already runs the other way) and so tests can drive both agent backends through it.
 */
interface SessionTurnControl {
    /** Is a turn EXECUTING on this session right now? False when no live conversation exists — an idle
     *  session on disk IS a stable point. */
    suspend fun turnExecuting(sessionId: String): Boolean

    /** Interrupt the in-flight turn — the same primitive as the composer ■ (claude: the stream-json
     *  control request; codex: turn/interrupt). The turn aborts; the session/process survive. */
    suspend fun interruptTurn(sessionId: String)

    /** Work this daemon CANNOT stop from here: background jobs the agent started (a detached shell keeps
     *  running past the interrupt). Recorded onto the recalled row so the UI says so (§5 item 4). */
    suspend fun hasUnstoppableWork(sessionId: String): Boolean

    /**
     * §5.3 item 7: drop [deviceId]'s LIVE view of each of [convoIds] — the outbound half of "the Grant
     * is over", which no guard can enforce (guards see inbound frames only).
     *
     * Removal is by the device's stable fan-out identity, NOT by closing the conversation: a handed-back
     * session usually still has the owner watching it (the §3.3 auto-spectate migration), and killing it
     * would take the owner's own work down with the recipient's view. A conversation left with no clients
     * simply streams headless, exactly as when any client drops off — and once the handoff is terminal it
     * is no longer reap-protected, so the idle reaper reclaims it on the ordinary schedule.
     *
     * Returns how many views were actually removed — 0 when there was nothing attached, which is the
     * normal answer for every repeat call (this runs on each terminal transition AND on every reconcile).
     */
    suspend fun detachDevice(convoIds: Set<String>, deviceId: String): Int
}
