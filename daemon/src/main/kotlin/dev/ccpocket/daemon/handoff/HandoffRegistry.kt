package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.SessionControllerLease
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.isTerminal
import java.util.UUID

/**
 * The daemon-local authority on Session Handoff state (SESSION-HANDOFF.md §5) — the ONLY place a
 * [HandoffStatus] transition is decided. App display state is never authorization (§5.3 item 8);
 * what this registry persisted is.
 *
 * Enforced invariants (§5.3):
 *  1. at most one active [SessionControllerLease] per session — the store keys leases by sessionId
 *     and every mutation here runs under ONE monitor lock;
 *  2. WAITING creates NO lease: nobody can drive (see [HandoffGuard]); the initiator must cancel first;
 *  4. handoffs are created / transitioned only through these methods (turn-boundary preconditions are
 *     checked by the CALLER, which owns the live conversation state — see the wiring TODO below);
 *  5. [accept] is compare-and-set under the lock: of N racing devices exactly one observes WAITING
 *     and wins; every loser gets a [HandoffOutcome.Refused].
 *
 * Expiry is clock-driven and pull-based: callers run [sweep] periodically (and before answering
 * reads); WAITING past [SessionHandoff.expiresAt] settles EXPIRED, an IN_PROGRESS lease past
 * [SessionControllerLease.leaseExpiresAt] settles RECALLED — both delete any lease. The clock is
 * injected for tests (the [dev.ccpocket.daemon.schedule.SchedulerService] pattern).
 *
 * Restart recovery (§5.4): the constructor reloads the store and NORMALIZES it — expired WAITING →
 * EXPIRED; IN_PROGRESS with a dead or MISSING lease → RECALLED (a lease that can't be proven must
 * fail closed to the owner, never be re-minted); orphan leases (no matching IN_PROGRESS handoff) are
 * dropped. Terminal handoffs are kept as history (capped, oldest-terminal pruned first).
 *
 * WIRING TODO (deliberately not done here — see HandoffGuard's KDoc for the drive-gate half):
 *  - RequestRouter: dispatch the pocket/handoff.* frames to these methods, using the TRANSPORT's
 *    device identity (never a frame field) as [deviceId], and fan [HandoffUpdated] out on every
 *    [HandoffOutcome.Ok] + every handoff returned by [sweep];
 *  - SessionRegistry: refuse [create] while a turn is executing / an ask is pending (§4.1), and
 *    consult [HandoffGuard] on send/cancel/answer/verdict;
 *  - HANDOFF credential (HandoffCredential.kt, §8.1): mint/finalize/revoke + caps. (The other half of
 *    that line — "on revoke or any terminal transition, cut the recipient's sinks" — is DONE:
 *    [HandoffService.broadcast]/[HandoffService.reconcile] cut the ended Grant's session views, §5.3
 *    item 7, and [revokeRecipient] settles the grants a severed credential leaves behind.);
 *  - push hook: notify on waiting/accepted/returned/declined/expired/recalled;
 *  - idle reaper: a session with a non-terminal handoff ([activeFor] != null) must not be reaped.
 */
class HandoffRegistry(
    private val store: HandoffStore = HandoffStore.load(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = logger("HandoffRegistry")

    /** The answer to every mutation: the updated entity, or a machine-readable refusal. */
    sealed interface HandoffOutcome {
        data class Ok(val handoff: SessionHandoff) : HandoffOutcome
        data class Refused(val code: String, val message: String) : HandoffOutcome
    }

    private fun refuse(code: String, message: String) = HandoffOutcome.Refused(code, message)

    init {
        recoverAtBoot()
    }

    // ---- creation ---------------------------------------------------------

    /**
     * Create a Handoff directly in WAITING (the wire CreateHandoff IS the "send"; DRAFT is a
     * client-side preview state the daemon never persists). Refuses when the session already has a
     * non-terminal handoff (§3: one at a time), or when kind/access decoded to UNKNOWN — a newer
     * client's value this build cannot enforce must fail closed, never be guessed at (§5.3 item 9).
     */
    @Synchronized
    fun create(
        sourceSessionId: String,
        workdir: String,
        agent: AgentKind,
        initiatorDeviceId: String,
        kind: HandoffKind,
        access: HandoffAccess,
        brief: HandoffBrief,
        allowedRoots: List<String> = emptyList(),
        expiresInSec: Long = dev.ccpocket.protocol.DEFAULT_HANDOFF_EXPIRES_SEC,
        initiatorLabel: String? = null,
        recipientLabel: String? = null,
        sourceConvoId: String? = null,
        sourceEventSeq: Long = 0,
        /** Collaborator-picker flow (§4.2 step 7): bind the Grant to this contact device up front —
         *  ONLY that device may accept. Null keeps the older open-invite behaviour. */
        recipientDeviceId: String? = null,
    ): HandoffOutcome {
        val now = clock()
        sweepLocked(now)
        unsupported(kind, access)?.let { return it }
        if (brief.request.isBlank()) return refuse("empty_request", "A handoff needs a request — what should the recipient do?")
        activeFor(sourceSessionId)?.let {
            return refuse("handoff_exists", "This session already has an active handoff (${it.first.status}) — finish or cancel it first.")
        }
        val handoff = SessionHandoff(
            id = UUID.randomUUID().toString(),
            sourceSessionId = sourceSessionId,
            workdir = workdir,
            agent = agent,
            initiatorDeviceId = initiatorDeviceId,
            initiatorLabel = initiatorLabel,
            recipientDeviceId = recipientDeviceId,
            recipientLabel = recipientLabel,
            kind = kind,
            status = HandoffStatus.WAITING,
            access = access,
            brief = brief,
            allowedRoots = if (access == HandoffAccess.CONTINUE_SCOPED) allowedRoots else emptyList(),
            createdAt = now,
            expiresAt = now + expiresInSec.coerceIn(MIN_EXPIRES_SEC, MAX_EXPIRES_SEC) * 1000,
            sourceConvoId = sourceConvoId,
            sourceEventSeq = sourceEventSeq,
        )
        store.putHandoff(prune(handoff))
        log.info("handoff ${handoff.id.take(8)}… WAITING on session ${sourceSessionId.take(8)}… (${kind.name}/${access.name})")
        return HandoffOutcome.Ok(handoff)
    }

    /**
     * v1 ships exactly ONE fully implemented authorization combination: REVIEW + REVIEW_READ_ONLY.
     * CONTINUE / CONTINUE_SCOPED are DEFINED on the wire (§8.4) but their enforcement — allowedRoots
     * validation, the ACCEPT_EDITS ceiling, the matching UI — is a later milestone, so minting such a
     * Grant would hand a recipient a promise this build cannot keep: [CollaboratorGuard] would still
     * clamp it to the REVIEW tier and the PermissionBridge would still hard-refuse every write tool,
     * while both apps render "edits allowed". Refuse it explicitly instead (`handoff_not_supported`),
     * and keep UNKNOWN on its own fail-closed code (a value only a NEWER peer knows — update the daemon).
     *
     * Checked on BOTH ends of the offer: at [create] (this daemon never mints one) and at [accept] (a
     * row a newer build wrote into handoffs.json, or a downgrade, must not become a live Grant either).
     */
    private fun unsupported(kind: HandoffKind, access: HandoffAccess): HandoffOutcome? = when {
        kind == HandoffKind.UNKNOWN ->
            refuse("unknown_kind", "This daemon does not understand the requested handoff kind — update the daemon.")
        access == HandoffAccess.UNKNOWN ->
            refuse("unknown_access", "This daemon does not understand the requested access mode — update the daemon.")
        kind != HandoffKind.REVIEW || access != HandoffAccess.REVIEW_READ_ONLY -> refuse(
            "handoff_not_supported",
            "This daemon only implements review / read-only handoffs — " +
                "${kind.name.lowercase()} + ${access.name.lowercase()} is a later milestone.",
        )
        else -> null
    }

    // ---- the WAITING exits ------------------------------------------------

    /**
     * Compare-and-set accept (§5.3 item 5): succeeds only for a handoff that is STILL WAITING and
     * unexpired at this instant — the whole read-check-write runs under the registry lock, so of two
     * racing devices exactly one flips WAITING → IN_PROGRESS and mints the lease; the other observes
     * IN_PROGRESS and is refused. An expired-but-unswept handoff settles EXPIRED here and refuses
     * (the expire-vs-accept race resolves to expiry, never a late grab). The initiator cannot accept
     * its own handoff.
     */
    @Synchronized
    fun accept(
        handoffId: String,
        deviceId: String,
        deviceLabel: String? = null,
        leaseTtlMs: Long = DEFAULT_LEASE_TTL_MS,
    ): HandoffOutcome {
        val now = clock()
        sweepLocked(now)
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.WAITING) return refuse("not_waiting", "This handoff is ${h.status.name.lowercase()} — it can no longer be accepted.")
        // v1 authorization gate, second end: a row this build cannot enforce never becomes a live Grant
        unsupported(h.kind, h.access)?.let { return it }
        if (deviceId == h.initiatorDeviceId) return refuse("self_accept", "The initiator cannot accept their own handoff.")
        // recipient binding (§4.2 step 7): a handoff addressed to a chosen contact device can be accepted
        // by exactly that device — a null binding keeps the older any-non-initiator open-invite behaviour
        if (h.recipientDeviceId != null && h.recipientDeviceId != deviceId) {
            return refuse("not_allowed", "This handoff is addressed to another device.")
        }
        // §5.3 invariant 1: WAITING holds no lease, and no other handoff can be non-terminal on this
        // session (enforced at create) — a lease here means corrupted state, so fail closed.
        if (store.leaseOf(h.sourceSessionId) != null) return refuse("lease_conflict", "The session already has a controller lease — refusing to double-grant.")
        val accepted = h.copy(
            status = HandoffStatus.IN_PROGRESS,
            recipientDeviceId = deviceId,
            recipientLabel = deviceLabel ?: h.recipientLabel,
            acceptedAt = now,
        )
        store.putHandoff(accepted)
        store.putLease(
            SessionControllerLease(
                sessionId = h.sourceSessionId,
                handoffId = h.id,
                controllerDeviceId = deviceId,
                acquiredAt = now,
                leaseExpiresAt = now + leaseTtlMs.coerceIn(MIN_LEASE_TTL_MS, MAX_LEASE_TTL_MS),
            ),
        )
        log.info("handoff ${h.id.take(8)}… accepted by ${deviceId.take(8)}… — lease granted")
        return HandoffOutcome.Ok(accepted)
    }

    /** WAITING → DECLINED, by the (would-be) recipient. When a recipient device is already named on
     *  the handoff it must be the caller; before that, any non-initiator invite holder may decline. */
    @Synchronized
    fun decline(handoffId: String, deviceId: String, reason: String? = null): HandoffOutcome =
        settleFromWaiting(handoffId, HandoffStatus.DECLINED) { h ->
            when {
                deviceId == h.initiatorDeviceId -> "The initiator cancels, not declines."
                h.recipientDeviceId != null && h.recipientDeviceId != deviceId -> "This handoff is addressed to another device."
                else -> null
            }
        }.also { if (it is HandoffOutcome.Ok && reason != null) log.info("handoff ${handoffId.take(8)}… declined: $reason") }

    /** WAITING → CANCELLED, by the initiator only. */
    @Synchronized
    fun cancel(handoffId: String, deviceId: String): HandoffOutcome =
        settleFromWaiting(handoffId, HandoffStatus.CANCELLED) { h ->
            if (deviceId != h.initiatorDeviceId) "Only the initiator can cancel a waiting handoff." else null
        }

    private fun settleFromWaiting(
        handoffId: String,
        to: HandoffStatus,
        deny: (SessionHandoff) -> String?,
    ): HandoffOutcome {
        sweepLocked(clock())
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.WAITING) return refuse("not_waiting", "This handoff is ${h.status.name.lowercase()}.")
        deny(h)?.let { return refuse("not_allowed", it) }
        val settled = h.copy(status = to)
        store.putHandoff(settled)
        log.info("handoff ${h.id.take(8)}… → ${to.name}")
        return HandoffOutcome.Ok(settled)
    }

    // ---- the IN_PROGRESS exits (leave = delete the lease, §5.3) -----------

    /**
     * IN_PROGRESS → RETURNED, by the lease-holding recipient only. [result] is the recipient's
     * confirmed draft; identity/time stamps ([HandoffResult.returnedByDeviceId]/[HandoffResult.returnedAt])
     * are OVERWRITTEN with daemon truth — a client-declared identity is never trusted. The lease dies
     * in the same locked step, so a duplicate return (or a send racing the return) observes RETURNED /
     * no-lease and is refused.
     */
    @Synchronized
    fun returnHandoff(handoffId: String, deviceId: String, result: HandoffResult? = null): HandoffOutcome {
        val now = clock()
        sweepLocked(now)
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.IN_PROGRESS) return refuse("not_in_progress", "This handoff is ${h.status.name.lowercase()} — nothing to return.")
        val lease = store.leaseOf(h.sourceSessionId)
        if (lease == null || lease.handoffId != h.id || lease.controllerDeviceId != deviceId) {
            return refuse("not_controller", "Only the controlling recipient can return this handoff.")
        }
        val returned = h.copy(
            status = HandoffStatus.RETURNED,
            returnedAt = now,
            result = (result ?: HandoffResult()).copy(returnedByDeviceId = deviceId, returnedAt = now),
            recallPending = false, // a return that beat an in-flight recall ends it: control is back either way
        )
        store.putHandoff(returned)
        store.removeLease(h.sourceSessionId)
        log.info("handoff ${h.id.take(8)}… RETURNED by ${deviceId.take(8)}… — lease released")
        return HandoffOutcome.Ok(returned)
    }

    /** IN_PROGRESS → RECALLED, by the initiator (§5.2) — the IDLE-session path. The lease dies NOW; a
     *  return racing this observes RECALLED and is refused (recall-vs-return resolves to whoever locked
     *  first). A caller that can see an EXECUTING turn must not use this: it goes through
     *  [markRecallPending] → interrupt → [settleRecall] instead, so control is only taken at a stable
     *  point (§5.4) — [HandoffService.beginRecall] picks between the two. */
    @Synchronized
    fun recall(handoffId: String, deviceId: String): HandoffOutcome {
        sweepLocked(clock())
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.IN_PROGRESS) return refuse("not_in_progress", "This handoff is ${h.status.name.lowercase()} — nothing to recall.")
        if (deviceId != h.initiatorDeviceId) return refuse("not_allowed", "Only the initiator can recall a handoff.")
        return recallLocked(h, why = "initiator recall")
    }

    /** The low-level marker: flag the session's lease recall-requested (§5.4 — a turn is executing,
     *  reclaim at the next stable point), which makes [HandoffGuard] refuse EVERY driver from this
     *  instant. Returns false when the session holds no lease. [markRecallPending] is the entry point
     *  that also authorizes the caller and marks the ENTITY (so both UIs see the hand-back). */
    @Synchronized
    fun requestRecall(sessionId: String): Boolean {
        val lease = store.leaseOf(sessionId) ?: return false
        if (!lease.recallRequested) store.putLease(lease.copy(recallRequested = true))
        return true
    }

    /**
     * §5.4 graceful recall, STEP 1 — the initiator asked for control back while a turn was EXECUTING.
     * Same authorization as [recall], but instead of settling now it arms the hand-back:
     *  - the LEASE is marked [SessionControllerLease.recallRequested], which makes [HandoffGuard] deny
     *    EVERY driver (the recipient must not start new work; the initiator must not race the dying
     *    turn) and [CollaboratorGuard] refuse the recipient's session frames;
     *  - the ENTITY is marked [SessionHandoff.recallPending] so both sides' UI shows the hand-back in
     *    flight rather than a completed recall (§5 item 3).
     * The status stays IN_PROGRESS until [settleRecall] reaches the stable point. A handoff with no
     * provable lease has nothing to wait for — it settles RECALLED right here (fail closed to the owner).
     */
    @Synchronized
    fun markRecallPending(handoffId: String, deviceId: String): HandoffOutcome {
        sweepLocked(clock())
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.IN_PROGRESS) return refuse("not_in_progress", "This handoff is ${h.status.name.lowercase()} — nothing to recall.")
        if (deviceId != h.initiatorDeviceId) return refuse("not_allowed", "Only the initiator can recall a handoff.")
        val lease = store.leaseOf(h.sourceSessionId)
        if (lease == null || lease.handoffId != h.id) return recallLocked(h, why = "initiator recall (no lease to wait on)")
        requestRecall(h.sourceSessionId) // ONE writer for the lease flag (reentrant under this lock)
        val pending = h.copy(recallPending = true)
        store.putHandoff(pending)
        log.info("handoff ${h.id.take(8)}… recall requested — interrupting the executing turn, lease held until it stops")
        return HandoffOutcome.Ok(pending)
    }

    /**
     * §5.4 graceful recall, STEP 2 — the stable point was reached (the turn stopped, the agent process
     * is gone, or the daemon's bounded wait ran out): IN_PROGRESS → RECALLED and the lease dies.
     * [incomplete] records an UNCLEAN settle (timeout, or background work the daemon can't kill) onto
     * [SessionHandoff.recallIncomplete] so the UI stays honest instead of claiming everything stopped.
     * Refuses when the row already left IN_PROGRESS — a return that beat the recall, or the sweep — so
     * the caller announces the ledger's truth rather than overwriting it.
     */
    @Synchronized
    fun settleRecall(handoffId: String, incomplete: Boolean = false): HandoffOutcome {
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.IN_PROGRESS) {
            return refuse("not_in_progress", "This handoff is ${h.status.name.lowercase()} — the recall settled another way.")
        }
        return recallLocked(h, why = if (incomplete) "graceful recall (unclean stop)" else "graceful recall", incomplete = incomplete)
    }

    private fun recallLocked(h: SessionHandoff, why: String, incomplete: Boolean = false): HandoffOutcome {
        val recalled = h.copy(status = HandoffStatus.RECALLED, recallPending = false, recallIncomplete = incomplete)
        store.putHandoff(recalled)
        store.removeLease(h.sourceSessionId)
        log.info("handoff ${h.id.take(8)}… RECALLED ($why) — lease released")
        return HandoffOutcome.Ok(recalled)
    }

    /**
     * The recipient's collaborator link was severed (SESSION-HANDOFF.md §4.1: remove = the credential
     * dies AND every temporary Grant with it): settle every non-terminal handoff BOUND to [deviceId] —
     * WAITING → CANCELLED (the offer can never be accepted), IN_PROGRESS → RECALLED (the lease dies
     * NOW, control back to the owner). RETURNED rows keep waiting for the initiator's acknowledge (the
     * recipient can no longer drive them anyway). Returns the transitioned handoffs for fan-out.
     */
    @Synchronized
    fun revokeRecipient(deviceId: String): List<SessionHandoff> {
        sweepLocked(clock())
        val changed = mutableListOf<SessionHandoff>()
        for (h in store.handoffs()) {
            if (h.status.isTerminal || h.recipientDeviceId != deviceId) continue
            when (h.status) {
                HandoffStatus.WAITING -> {
                    val cancelled = h.copy(status = HandoffStatus.CANCELLED)
                    store.putHandoff(cancelled)
                    log.info("handoff ${h.id.take(8)}… CANCELLED (recipient link severed)")
                    changed += cancelled
                }
                HandoffStatus.IN_PROGRESS ->
                    changed += (recallLocked(h, why = "recipient link severed") as HandoffOutcome.Ok).handoff
                else -> {} // RETURNED: result already back with the owner — leave it to complete()
            }
        }
        return changed
    }

    // ---- the RETURNED exit ------------------------------------------------

    /** RETURNED → COMPLETED, by the initiator acknowledging the result (§4.4). */
    @Synchronized
    fun complete(handoffId: String, deviceId: String): HandoffOutcome {
        sweepLocked(clock())
        val h = store.handoffById(handoffId) ?: return refuse("not_found", "No such handoff.")
        if (h.status != HandoffStatus.RETURNED) return refuse("not_returned", "This handoff is ${h.status.name.lowercase()} — nothing to acknowledge.")
        if (deviceId != h.initiatorDeviceId) return refuse("not_allowed", "Only the initiator can complete a handoff.")
        val completed = h.copy(status = HandoffStatus.COMPLETED)
        store.putHandoff(completed)
        log.info("handoff ${h.id.take(8)}… COMPLETED")
        return HandoffOutcome.Ok(completed)
    }

    // ---- expiry + recovery ------------------------------------------------

    /** Settle everything the clock has outrun: WAITING past its expiry → EXPIRED; an IN_PROGRESS
     *  lease past its own expiry → RECALLED (control back to the owner). Returns the handoffs that
     *  transitioned, for the caller to fan out as [dev.ccpocket.protocol.HandoffUpdated]. */
    @Synchronized
    fun sweep(now: Long = clock()): List<SessionHandoff> = sweepLocked(now)

    private fun sweepLocked(now: Long): List<SessionHandoff> {
        val changed = mutableListOf<SessionHandoff>()
        for (h in store.handoffs()) {
            when {
                h.status == HandoffStatus.WAITING && h.expiresAt <= now -> {
                    val expired = h.copy(status = HandoffStatus.EXPIRED)
                    store.putHandoff(expired)
                    store.removeLease(h.sourceSessionId) // belt-and-braces; WAITING must hold none
                    log.info("handoff ${h.id.take(8)}… EXPIRED (unaccepted)")
                    changed += expired
                }
                h.status == HandoffStatus.IN_PROGRESS -> {
                    val lease = store.leaseOf(h.sourceSessionId)
                    if (lease == null || lease.handoffId != h.id || lease.leaseExpiresAt <= now) {
                        val why = if (lease == null) "lease missing" else "lease expired"
                        changed += (recallLocked(h, why) as HandoffOutcome.Ok).handoff
                    }
                }
            }
        }
        return changed
    }

    /** §5.4: reload + normalize after a restart. Runs the same settlement as [sweepLocked] plus the
     *  orphan-lease prune (a lease whose handoff is not IN_PROGRESS anymore must not survive — it
     *  would let a stale controller keep driving). */
    private fun recoverAtBoot() {
        synchronized(this) {
            val now = clock()
            sweepLocked(now)
            // §5.4: a graceful recall that was IN FLIGHT when the daemon stopped (the lease is marked
            // recallRequested) settles HERE. The turn it was interrupting died with the daemon — agent
            // processes are our children — so the stable point is trivially reached; but nobody ever
            // observed it stop, so the row is marked recallIncomplete rather than claiming a clean stop.
            // Without this the guard would keep refusing EVERY driver (RECALL_PENDING) until the lease's
            // own expiry hours later: the owner asked for the session back and would get it back locked.
            for (h in store.handoffs()) {
                if (h.status != HandoffStatus.IN_PROGRESS) continue
                val lease = store.leaseOf(h.sourceSessionId) ?: continue
                if (lease.handoffId == h.id && lease.recallRequested) {
                    recallLocked(h, why = "recall was in flight at shutdown", incomplete = true)
                }
            }
            val inProgressBySession = store.handoffs()
                .filter { it.status == HandoffStatus.IN_PROGRESS }
                .associateBy { it.sourceSessionId }
            val orphans = store.leases().filterNot { lease ->
                inProgressBySession[lease.sessionId]?.id == lease.handoffId
            }
            if (orphans.isNotEmpty()) {
                store.replaceAll(store.handoffs(), store.leases() - orphans.toSet())
                log.warn("dropped ${orphans.size} orphan controller lease(s) at boot")
            }
            val open = store.handoffs().count { !it.status.isTerminal }
            if (open > 0) log.info("recovered $open non-terminal handoff(s)")
        }
    }

    // ---- reads ------------------------------------------------------------

    @Synchronized
    fun byId(handoffId: String): SessionHandoff? = store.handoffById(handoffId)

    /** The session's ONE non-terminal handoff (§3) + its lease (null outside IN_PROGRESS), or null —
     *  the tuple [HandoffGuard] gates on. Reads settle expiry first so a gate can never honor a
     *  handoff the clock has already outrun. */
    @Synchronized
    fun activeFor(sessionId: String): Pair<SessionHandoff, SessionControllerLease?>? {
        sweepLocked(clock())
        val h = store.handoffs().firstOrNull { it.sourceSessionId == sessionId && !it.status.isTerminal }
            ?: return null
        return h to store.leaseOf(sessionId)
    }

    /** All handoffs, optionally filtered — newest first. The ROUTER additionally filters by credential
     *  (a HANDOFF credential sees only its own rows) before this reaches a wire [dev.ccpocket.protocol.HandoffListing]. */
    @Synchronized
    fun list(workdir: String? = null, sessionId: String? = null): List<SessionHandoff> {
        sweepLocked(clock())
        return store.handoffs()
            .filter { workdir == null || it.workdir == workdir }
            .filter { sessionId == null || it.sourceSessionId == sessionId }
            .sortedByDescending { it.createdAt }
    }

    /** Keep the ledger bounded: on create, prune the OLDEST terminal rows past [MAX_HISTORY].
     *  Non-terminal rows are never pruned. Returns [fresh] untouched (it is the row being added). */
    private fun prune(fresh: SessionHandoff): SessionHandoff {
        val terminal = store.handoffs().filter { it.status.isTerminal }.sortedBy { it.createdAt }
        val excess = terminal.size - (MAX_HISTORY - 1)
        if (excess > 0) {
            val dropIds = terminal.take(excess).map { it.id }.toSet()
            store.replaceAll(store.handoffs().filterNot { it.id in dropIds }, store.leases())
        }
        return fresh
    }

    companion object {
        /** How long an accepted recipient may hold control before the daemon reclaims it (§5.4). */
        const val DEFAULT_LEASE_TTL_MS: Long = 2 * 3600_000L
        const val MIN_LEASE_TTL_MS: Long = 60_000L
        const val MAX_LEASE_TTL_MS: Long = 24 * 3600_000L

        /** Bounds for [dev.ccpocket.protocol.CreateHandoff.expiresInSec] (WAITING lifetime). */
        const val MIN_EXPIRES_SEC: Long = 60L
        const val MAX_EXPIRES_SEC: Long = 7 * 24 * 3600L

        /** Terminal-history cap in handoffs.json — enough for a "past handoffs" list, bounded forever. */
        const val MAX_HISTORY = 200
    }
}
