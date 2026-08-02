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
class ApprovalCoordinator(private val scope: CoroutineScope) {
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
        @Volatile var timeoutJob: Job? = null,
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
        onOutcome: suspend (ApprovalOutcome) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val key = Key(ask.convoId, ask.askId)
        val p = Pending(ask, source, owner, isQuestion, now + timeoutMs, now, emit, onOutcome)
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
        p.timeoutJob = scope.launch {
            delay(timeoutMs)
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
    }
}
