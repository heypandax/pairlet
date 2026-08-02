package dev.ccpocket.daemon.approval

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which gate a [PermissionAsk] came through. Approval semantics (timeout wording, remember rules,
 *  bypass paths) stay with the source adapter; the coordinator only needs the label for observability
 *  and snapshot filtering. */
enum class ApprovalSource { AGENT, BRIDGE_REQUEST, SHELL, EXPORT }

/** The single terminal state of one pending approval. Exactly one outcome is ever delivered per request. */
sealed interface ApprovalOutcome {
    /** A human answered — the full verdict, so the adapter applies its own remember/answers semantics. */
    data class Answered(val verdict: PermissionVerdict) : ApprovalOutcome

    /** Nobody answered inside the window. The card was already retired via [AskWithdrawn] (TIMED_OUT). */
    data object TimedOut : ApprovalOutcome

    /** The requester gave up (agent cancel, session close, engine stop). Card already retired. */
    data object Withdrawn : ApprovalOutcome
}

/**
 * The daemon's single pending-approval ledger (approval design M1). Every human security approval —
 * agent tool asks, bridge request-level approvals, quick-terminal commands, file exports — registers
 * here instead of keeping a private pending map, so timeout, withdraw, verdict idempotency, reattach
 * resurfacing and the account-wide snapshot behave identically across sources, and a verdict routes by
 * askId in ONE place instead of being try-offered to each service in turn.
 *
 * What deliberately stays in the source adapters: WHETHER to ask (bypass modes, remembered rules,
 * hard policies), what the deny/timeout answer looks like to the backend, and remember-rule
 * bookkeeping. The coordinator never grants anything on its own — it only carries a pending question
 * to its single terminal state.
 *
 * Lifecycle per request: [submit] emits the [PermissionAsk] and arms the timeout; then exactly one of
 * [onVerdict] / timeout / [withdraw] removes it and delivers the [ApprovalOutcome]. Late or duplicate
 * verdicts return false (idempotency lives here, not in each gate).
 */
class ApprovalCoordinator(
    private val scope: CoroutineScope,
    // design §17.9: no approval outlives 24h, however the lease is chained — injectable only so a test
    // can exercise the ceiling without waiting a day
    private val absoluteDeadlineMs: Long = ABSOLUTE_DEADLINE_MS,
) {
    private val log = logger("Approvals")

    private class Pending(
        val ask: PermissionAsk,
        val source: ApprovalSource,
        val owner: Any,
        val isQuestion: Boolean,
        val expiresAt: Long,
        val createdAt: Long,
        val emit: suspend (Frame) -> Unit,
        val onOutcome: suspend (ApprovalOutcome) -> Unit,
        val onReminder: (suspend () -> Unit)? = null,
        @Volatile var timeoutJob: Job? = null,
        // ── AttentionLease (design §10): a foreground-visible card pauses the no-response budget ──
        // Only extends READING time: authority, grants and the absolute deadline are untouched, and a
        // late heartbeat for a terminal ask no-ops (the pending entry is already gone).
        @Volatile var leaseUntil: Long = 0L,
        @Volatile var everLeased: Boolean = false,
    )

    // Keyed by (convoId, askId), NOT askId alone: an askId is only unique within one CLI process — Codex
    // mints JSON-RPC ids from a per-connection counter ("1", "2", …), so two live conversations routinely
    // collide. Namespacing by conversation is also the security floor the old per-conversation routing
    // provided: a guest's verdict is vetted against ITS convoId upstream (GuestGuard), so a matching
    // (convoId, askId) can never resolve another conversation's ask.
    private data class Key(val convoId: String, val askId: String)

    // Insertion-ordered so resurfacing / snapshots present asks in arrival order (the phone renders a
    // queue, not a set). Guarded by [lock]; outcome callbacks always run OUTSIDE it (they suspend on
    // backend IO / phone emits).
    private val pending = LinkedHashMap<Key, Pending>()
    private val lock = Any()

    // M0 observability: how often the same rule re-asks within one conversation — the baseline the
    // design's task-grant work is measured against. Bounded: cleared wholesale if it ever balloons.
    private val ruleAskCounts = HashMap<String, Int>()

    /**
     * Register + surface one approval. [onOutcome] is invoked exactly once, off [lock]. [owner] scopes
     * the bulk operations ([withdrawAllFor], [hasPendingFor], …) to the adapter instance that asked —
     * a relaunched agent process gets a fresh PermissionBridge, and its stale predecessor must not be
     * able to retire the new instance's cards.
     */
    suspend fun submit(
        ask: PermissionAsk,
        source: ApprovalSource,
        owner: Any,
        timeoutMs: Long,
        isQuestion: Boolean = false,
        emit: suspend (Frame) -> Unit,
        // fired ONCE at half the no-response budget if nobody has looked at the card yet (design §9.5's
        // single non-urgent second reminder) — the caller decides what a reminder means (ask push re-nudge)
        onReminder: (suspend () -> Unit)? = null,
        onOutcome: suspend (ApprovalOutcome) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val key = Key(ask.convoId, ask.askId)
        val p = Pending(ask, source, owner, isQuestion, now + timeoutMs, now, emit, onOutcome, onReminder)
        val (replaced, repeat) = synchronized(lock) {
            val prev = pending.put(key, p)
            prev?.timeoutJob?.cancel() // its orphaned timeout must not retire the replacement
            prev to (
                ask.rule?.let { rule ->
                    if (ruleAskCounts.size > RULE_COUNT_CAP) ruleAskCounts.clear()
                    ruleAskCounts.merge("${ask.convoId}|$rule", 1, Int::plus) ?: 1
                } ?: 1
            )
        }
        // a re-submitted (convoId, askId) supersedes its predecessor — unblock the old caller quietly
        // (same card on the phone, no AskWithdrawn)
        replaced?.onOutcome(ApprovalOutcome.Withdrawn)
        if (repeat > 1) {
            log.info("repeat ask source=$source tool=${ask.tool} rule=${ask.rule} hit#$repeat convo=${ask.convoId}")
        }
        // Soft budget + attention lease + absolute cap (design §10): [timeoutMs] is the NO-RESPONSE
        // budget; a live lease (foreground-visible card, [heartbeat]) pauses its consumption; nothing —
        // no lease chain — carries the request past createdAt + ABSOLUTE_DEADLINE_MS. Watched-socket
        // presence alone never pauses anything: only explicit heartbeats do.
        p.timeoutJob = scope.launch {
            var budgetLeftMs = timeoutMs
            var lastTick = System.currentTimeMillis()
            var reminded = onReminder == null
            val absoluteDeadline = p.createdAt + absoluteDeadlineMs
            while (true) {
                val nowMs = System.currentTimeMillis()
                if (nowMs >= absoluteDeadline) break // the hard ceiling always terminates
                val lease = p.leaseUntil
                if (lease <= nowMs) { // unleased: the budget is burning
                    budgetLeftMs -= nowMs - lastTick
                    if (budgetLeftMs <= 0) break
                    if (!reminded && !p.everLeased && budgetLeftMs <= timeoutMs / 2) {
                        reminded = true
                        runCatching { onReminder?.invoke() }
                    }
                }
                lastTick = nowMs
                val leased = lease > nowMs
                val next = minOf(
                    // leased: tick fast enough to notice an early release (visible=false / silence)
                    // without a wake channel; unleased: sleep straight to the budget's end…
                    if (leased) minOf(lease - nowMs, LEASED_TICK_MS) else budgetLeftMs,
                    // …except wake at the half-budget point once, for the single reminder nudge
                    if (!reminded && !leased) (budgetLeftMs - timeoutMs / 2).coerceAtLeast(10) else Long.MAX_VALUE,
                    absoluteDeadline - nowMs,
                    MAX_TICK_MS,
                ).coerceAtLeast(10)
                delay(next)
            }
            // identity check: only remove the exact entry this job guards, never a replacement
            val timedOut = synchronized(lock) { if (pending[key] === p) pending.remove(key) else null } ?: return@launch
            // retire the phone's card FIRST (it can't observe the timeout on its own — issue #100),
            // then let the adapter answer its backend honestly ("no answer", never "denied by user")
            timedOut.emit(AskWithdrawn(ask.convoId, ask.askId, AskWithdrawnReason.TIMED_OUT))
            record(timedOut, "TIMED_OUT")
            timedOut.onOutcome(ApprovalOutcome.TimedOut)
        }
        emit(ask)
    }

    /** A client reports the card is (in)visible in its foreground (design §10 AttentionLease). Grants a
     *  short lease that pauses the no-response budget; [visible]=false releases it early. A heartbeat can
     *  only stretch reading time — never the absolute deadline, never authority, never grants — and one
     *  for an unknown/terminal ask is dropped. */
    fun heartbeat(convoId: String, askId: String, visible: Boolean) {
        val p = synchronized(lock) { pending[Key(convoId, askId)] } ?: return
        if (visible) {
            p.leaseUntil = System.currentTimeMillis() + LEASE_MS
            p.everLeased = true
        } else {
            p.leaseUntil = 0L
        }
    }

    /** Route a verdict by askId. True iff it resolved a pending request; false = unknown/expired/duplicate
     *  (the caller surfaces "ask_expired" to the device that tapped — a tap must never look like a silent
     *  success, issue #100). */
    suspend fun onVerdict(v: PermissionVerdict): Boolean {
        // the composite lookup is load-bearing: a verdict resolves ONLY an ask of the conversation it names
        // (which upstream guards vetted the sender against) — never a same-askId ask of another conversation
        val p = synchronized(lock) { pending.remove(Key(v.convoId, v.askId)) } ?: run {
            log.warn("verdict for unknown/expired ask ${v.askId} (${v.decision}) convo=${v.convoId} — already resolved, timed out, or withdrawn")
            return false
        }
        p.timeoutJob?.cancel()
        record(p, "${v.decision}${if (v.remember) "+remember" else ""}")
        p.onOutcome(ApprovalOutcome.Answered(v))
        return true
    }

    /** Retire one request from the requester side (agent control_cancel, caller cancellation). Emits the
     *  card-dismissing [AskWithdrawn] and delivers [ApprovalOutcome.Withdrawn]. False = already terminal. */
    suspend fun withdraw(convoId: String, askId: String, reason: AskWithdrawnReason = AskWithdrawnReason.WITHDRAWN): Boolean {
        val p = synchronized(lock) { pending.remove(Key(convoId, askId)) } ?: return false
        p.timeoutJob?.cancel()
        p.emit(AskWithdrawn(convoId, askId, reason))
        record(p, "WITHDRAWN")
        p.onOutcome(ApprovalOutcome.Withdrawn)
        return true
    }

    /** Retire every request [owner] registered (session close, relaunch, engine stop). */
    suspend fun withdrawAllFor(owner: Any) {
        val mine = synchronized(lock) {
            val hits = pending.values.filter { it.owner === owner }
            hits.forEach { pending.remove(Key(it.ask.convoId, it.ask.askId)) }
            hits
        }
        mine.forEach { p ->
            p.timeoutJob?.cancel()
            p.emit(AskWithdrawn(p.ask.convoId, p.ask.askId, AskWithdrawnReason.WITHDRAWN))
            record(p, "WITHDRAWN")
            p.onOutcome(ApprovalOutcome.Withdrawn)
        }
    }

    /** True while [owner] has any open ask — the idle reaper's "a blocked question is not idle" signal. */
    fun hasPendingFor(owner: Any): Boolean =
        synchronized(lock) { pending.values.any { it.owner === owner } }

    /** Snapshot rows for [owner]'s open asks, arrival-ordered. Questions are excluded by default — they
     *  are conversation-scoped answer UI, not security approvals (approval design §4.1). */
    fun rowsFor(owner: Any, includeQuestions: Boolean = false): List<PendingApproval> =
        synchronized(lock) {
            pending.values
                .filter { it.owner === owner && (includeQuestions || !it.isQuestion) }
                .map { PendingApproval(it.ask, expiresAt = it.expiresAt) }
        }

    /** Snapshot rows for every open ask from [source], arrival-ordered (account-wide inbox assembly). */
    fun rows(source: ApprovalSource): List<PendingApproval> =
        synchronized(lock) {
            pending.values.filter { it.source == source }.map { PendingApproval(it.ask, expiresAt = it.expiresAt) }
        }

    /** Re-emit [owner]'s still-open asks to a reattaching sink, arrival-ordered — a device that missed
     *  the live frame gets each card back verbatim; terminal asks never return (they left [pending]). */
    suspend fun resurfaceFor(owner: Any, to: suspend (Frame) -> Unit) {
        val open = synchronized(lock) { pending.values.filter { it.owner === owner }.map { it.ask } }
        open.forEach { to(it) }
    }

    /** M0 observability: one line per auto-decision that skipped the human (bypass mode, remembered rule,
     *  bridge policy, owner grant) — the denominator for "how many asks did the task-grant work remove". */
    fun recordAuto(source: ApprovalSource, convoId: String, tool: String, rule: String?, basis: String) {
        log.info("auto-allow source=$source tool=$tool rule=$rule basis=$basis convo=$convoId")
    }

    private fun record(p: Pending, decision: String) {
        val waitedMs = System.currentTimeMillis() - p.createdAt
        log.info(
            "resolved source=${p.source} tool=${p.ask.tool} rule=${p.ask.rule} decision=$decision " +
                "waitedMs=$waitedMs question=${p.isQuestion} convo=${p.ask.convoId} ask=${p.ask.askId}",
        )
    }

    private companion object {
        const val RULE_COUNT_CAP = 4096
        const val LEASE_MS = 60_000L               // one heartbeat buys ~60s of paused budget (30s cadence)
        const val LEASED_TICK_MS = 500L            // lease-release detection latency bound
        const val ABSOLUTE_DEADLINE_MS = 24 * 60 * 60 * 1000L // design §17.9: no approval outlives 24h
        const val MAX_TICK_MS = 30_000L            // budget bookkeeping granularity while waiting
    }
}
