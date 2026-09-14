package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PermissionMode
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * #367 — the TARGET side of an execution grant: the owner control plane (approve / confirm / revise /
 * revoke) plus the BIND hook the production credential chain calls when a source's first frame proves the
 * invite.
 *
 * There is exactly ONE handshake path in this daemon and it is not here. G0 prototyped a private
 * transport/handshake responder beside the production chain; G1 deleted it. Approval now goes through the
 * SAME serialized mint the collaborator/guest/bridge planes use
 * ([BridgeRegistry.reserveMint] → relay mint → [BridgeRegistry.recordIntent] →
 * [dev.ccpocket.daemon.relay.DeviceSessions.onMintedTicket]), the source's first decrypt is classified by
 * [BridgeRegistry.finalize] exactly like any other restricted credential, and the only #367-specific step
 * is [onRedeemed], which binds the proven deviceId + static key into the grant row.
 *
 * The one thing that IS #367-specific about the ceremony: what is armed as the first-contact PSK is not
 * the relay's ticket but `HKDF(ticket ‖ inviteSecret)` ([ExecutionPsk]). The relay mints and redeems the
 * RAW ticket and never sees the 32-byte invite secret, so "the first frame decrypted" proves the peer holds
 * the out-of-band invite, not merely something the relay could have issued itself. Everything downstream —
 * the armed PSK stack, the recorded intent's hash, [BridgeRegistry.finalize]'s comparison — operates on the
 * DERIVED value and is unchanged by this.
 *
 * Fail-closed properties this class is responsible for:
 *  - The invite never leaves [approve] before the grant is durably persisted, and the mint slot is claimed
 *    BEFORE the suspending relay round-trip (issue #207), so no other pairing of any class can interleave.
 *  - The relay-reported ticket lifetime is capped locally at [MAX_TICKET_TTL_SEC].
 *  - The invite secret and the derived PSK are never persisted: the row stores only `sha256(derived PSK)`,
 *    and a restart between approval and redemption disarms the link for good (user decision 09-14 —
 *    recovery is revoke + re-approve).
 *  - [onRedeemed] refuses a deviceId this daemon already knows as an owner device or as a restricted
 *    credential of another kind ([isKnownDevice]), and refuses to widen anything but a PENDING_REDEEM row
 *    whose ticket window is still open. A refusal leaves the credential unbound; the caller drops it.
 *  - A bound key is PINNED: [ExecutionAuthorizer] meets exactly that static key on every later frame.
 *  - Every frame is re-authorised against the store; an unavailable store (including a revoke that is not
 *    yet durable) refuses every approval, change and request.
 *  - Revoke drops the local credential ([BridgeRegistry.remove]) AND revokes at the relay; a failed relay
 *    revoke is reported ([pendingRelayRevokes]) and retried with backoff ([maintain]).
 *  - Every refusal records a stable diagnostic code per device ([lastRefusal]) — never key/ticket material.
 */
class ExecutionTarget(
    private val identity: Identity,
    private val relayUrl: String,
    private val store: ExecutionGrantStore,
    /** The shared restricted-credential authority: the mint slot, the pairing intent, the bound credential. */
    private val bridges: BridgeRegistry,
    /** Mint a one-time HEADLESS connect ticket at the relay (production: the collaborator mint path). */
    private val mintTicket: suspend () -> PairTicket?,
    /** Arm the derived first-contact PSK on the device pump — [dev.ccpocket.daemon.relay.DeviceSessions.onMintedTicket]
     *  with `headless = true`, so an execution mint never stamps the interactive-pairing exclusion clock. */
    private val armPsk: (psk: String) -> Unit,
    /** Relay-side credential revoke for a bound source device. */
    private val revokeRelayDevice: suspend (deviceId: String) -> Unit,
    /**
     * Cut the LIVE local link of a revoked source device — production:
     * [dev.ccpocket.daemon.relay.DeviceSessions.onDeviceRevoked].
     *
     * Dropping only the credential ([BridgeRegistry.remove]) is NOT enough, and the reason is worth stating:
     * the egress whitelist in `sealAndSend` keys on the device still BEING a restricted credential, so a
     * device whose credential has just been removed while its E2E session lingers stops being filtered at
     * all — the daily `reannounceDaemonInfo` sweep, which skips restricted credentials, would then seal a
     * [dev.ccpocket.protocol.DaemonInfo] (LAN address and all) to a link the owner just revoked. Killing the
     * session in the same step closes that window instead of relying on the relay's own DeviceRevoked
     * echo, which may be exactly what has failed.
     */
    private val cutLink: suspend (deviceId: String) -> Unit = {},
    /**
     * Does this daemon already know [deviceId] as an OWNER device, or as a restricted credential of a kind
     * other than this one? REQUIRED (no default): the whole point of the check is that a real caller wires
     * it to the real sources — `DeviceSessions.devicePubs` + [BridgeRegistry.ids] including provisional
     * keys — and a silent `{ false }` default would turn the strongest anti-confusion gate into a no-op.
     */
    private val isKnownDevice: suspend (deviceId: String) -> Boolean,
    /** True while an INTERACTIVE pairing ticket could still be redeemed; an execution mint waits (issue #91). */
    /** How much longer an INTERACTIVE pairing blocks this headless mint, in ms (0 = not blocking). */
    private val interactivePairingRemainingMs: () -> Long = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
    private val targetLabel: String? = null,
    private val newGrantId: () -> String = ::randomGrantId,
    /** Shared with [ExecutionGuard] and the run plane so one ledger answers "why is this link failing?". */
    private val refusals: ExecutionRefusals = ExecutionRefusals(),
) : ExecutionControl {
    private val log = logger("ExecutionTarget")

    sealed interface Approval {
        data class Ok(val invite: ExecutionInvite, val grant: ExecutionGrant) : Approval
        /**
         * @param retryAfterMs when the refusal is a WAIT rather than a NO — the #207 mint slot is taken, or
         *   an interactive phone pairing is still redeemable — how long until it is worth trying again.
         *   Null for a refusal no amount of waiting fixes (a bad ceiling, an unusable store). A burned
         *   execution invite holds the slot for its whole ticket TTL + grace, which is minutes, so "try
         *   again shortly" without a number is not an answer a human can act on.
         */
        data class Refused(val code: String, val retryAfterMs: Long? = null) : Approval
    }

    private class RelayRevoke(var failures: Int, var nextAt: Long, var backoffMs: Long)

    private val lock = Any()
    private val relayRevokes = LinkedHashMap<String, RelayRevoke>()

    /** The most recent refusal code for [deviceId] from ANY execution layer (diagnostics / tests). */
    fun lastRefusal(deviceId: String): String? = refusals.last(deviceId)

    /** Relay revokes that have failed and are being retried: deviceId -> failure count (owner-visible alert). */
    fun pendingRelayRevokes(): Map<String, Int> = synchronized(lock) { relayRevokes.mapValues { it.value.failures } }

    private fun clock(): Long = store.observeClock(now())

    /** Due retries: pending revoke writes, then pending relay revokes. Production calls this on a ticker;
     *  every owner entry point calls it too. */
    suspend fun maintain() {
        store.retryIfDue(now())
        val t = now()
        val due = synchronized(lock) { relayRevokes.filter { it.value.nextAt <= t }.keys.toList() }
        for (d in due) revokeAtRelay(d)
    }

    // ---------------------------------------------------------------- owner control plane

    suspend fun approve(draft: ExecutionGrantDraft): Approval {
        maintain()
        store.unavailableReason?.let { return Approval.Refused(it) }
        if (store.targetDaemonPub != identity.e2ePubB64) return Approval.Refused("store_identity_mismatch")
        val scope = ExecutionPolicy.validate(draft).getOrElse {
            return Approval.Refused((it as? ExecutionRefusal)?.code ?: "invalid")
        }
        // mint serialization (issue #91): an execution mint is a headless mint — refuse while a phone
        // pairing ticket could still be redeemed, so the LIFO PSK arming can't cross-bind them
        interactivePairingRemainingMs().takeIf { it > 0 }?.let {
            return Approval.Refused("interactive_pairing_pending", retryAfterMs = it)
        }
        // issue #207: claim the ONE mint slot BEFORE the suspending relay round-trip, so two overlapping
        // mints (of ANY class) cannot both arm a PSK while only one intent is recorded
        // NOTE the explicit clock: [BridgeRegistry] purges lapsed intents against whatever `now` it is
        // handed, and this plane's clock is the store's HIGH-WATER, not the wall clock
        if (!bridges.reserveMint(now())) {
            return Approval.Refused("mint_busy", retryAfterMs = bridges.mintBusyRemainingMs(now()).takeIf { it > 0 })
        }
        try {
            val ticket = runCatching { mintTicket() }.getOrNull() ?: return Approval.Refused("mint_failed")
            val t = clock()
            val pub = identity.e2ePubB64
            // the relay chooses expiresInSec; the window it controls is capped here (review HIGH-1)
            val ttlSec = ticket.expiresInSec.coerceIn(1, MAX_TICKET_TTL_SEC)
            val inviteSecret = b64(ByteArray(ExecutionPsk.SECRET_BYTES).also { RNG.nextBytes(it) })
            val psk = ExecutionPsk.derive(ticket.ticket, inviteSecret)
            val grant = ExecutionGrant(
                grantId = newGrantId(),
                revision = 1,
                state = ExecutionGrantState.PENDING_REDEEM,
                createdAt = t,
                expiresAt = t + draft.ttlMs,
                targetAccountId = identity.accountId,
                targetDaemonPub = pub,
                targetDaemonFingerprint = ExecutionFingerprint.of(pub),
                sourceLabel = draft.sourceLabel.trim(),
                ticketHash = hashHex(psk.encodeToByteArray()),
                ticketExpiresAt = t + ttlSec * 1000L,
                workspaces = scope.workspaces,
                allowedAgents = draft.allowedAgents.distinct(),
                approvalCeiling = draft.approvalCeiling,
                maxConcurrentRuns = draft.maxConcurrentRuns,
                maxQueuedRuns = draft.maxQueuedRuns,
                runTimeoutMs = draft.runTimeoutMs,
                perGrantRequestBudget = draft.perGrantRequestBudget,
            )
            // persist BEFORE the invite leaves this method: an approval the disk never saw must not be redeemable
            when (val w = store.insert(grant)) {
                is ExecutionGrantStore.Write.Ok -> {}
                is ExecutionGrantStore.Write.Refused -> return Approval.Refused(w.code)
                ExecutionGrantStore.Write.Unavailable -> return Approval.Refused(store.unavailableReason ?: "store_unavailable")
                else -> return Approval.Refused("persist_failed")
            }
            // the INTENT is keyed on the DERIVED value, not the relay ticket: `finalize` hashes the PSK the
            // first frame actually proved, and that is what the source mixes. bindable window = ticket TTL +
            // the shared grace, so a slow-to-first-frame source is still classified as an execution link.
            val spec = BridgeSpec.execution(label = grant.sourceLabel, grantId = grant.grantId)
            if (!bridges.recordIntent(psk, spec, ttlMs = ttlSec * 1000L + BridgeRegistry.INTENT_GRACE_MS, now = now())) {
                // the grant is already on disk; revoke it rather than leave a row nothing can ever redeem
                runCatching { store.revoke(grant.grantId, "mint_busy", t) }
                return Approval.Refused("mint_busy", retryAfterMs = bridges.mintBusyRemainingMs(now()).takeIf { it > 0 })
            }
            armPsk(psk)
            log.info("execution grant ${grant.grantId.take(10)}… approved, awaiting redeem")
            return Approval.Ok(
                ExecutionInvite(
                    kind = ExecutionInvite.KIND,
                    relay = relayUrl,
                    targetAccountId = identity.accountId,
                    targetDaemonPub = pub,
                    connectTicket = ticket.ticket,
                    inviteSecret = inviteSecret,
                    grantId = grant.grantId,
                    ttlSec = ttlSec,
                    targetLabel = targetLabel,
                ),
                grant,
            )
        } finally {
            bridges.releaseMint()
        }
    }

    /** The owner read the LINK fingerprint the source displays and entered/confirmed it here. Mismatch revokes. */
    suspend fun confirmSource(grantId: String, shownSourceLinkFingerprint: String): ExecutionGrantStore.Write {
        maintain()
        if (!store.available) return ExecutionGrantStore.Write.Unavailable
        val g = store.byId(grantId) ?: return ExecutionGrantStore.Write.NotFound
        ExecutionAuthorizer.lifecycleDeny(g, clock())?.let { return ExecutionGrantStore.Write.Refused(it) }
        if (g.state != ExecutionGrantState.AWAITING_OWNER_CONFIRM) return ExecutionGrantStore.Write.Refused("grant_not_awaiting_confirm")
        val pin = g.sourceLinkPub
        if (pin == null || !ExecutionFingerprint.matches(shownSourceLinkFingerprint, pin)) {
            revoke(grantId, "fingerprint_mismatch")
            return ExecutionGrantStore.Write.Refused("fingerprint_mismatch")
        }
        return store.widen(grantId) {
            if (it.state == ExecutionGrantState.AWAITING_OWNER_CONFIRM) it.copy(state = ExecutionGrantState.ACTIVE) else null
        }
    }

    /**
     * Owner changes scope: validated like an approval, revision + 1, binding untouched. The new expiry is
     * capped at createdAt + MAX_TTL, so revisions cannot extend a grant's total lifetime past the policy cap.
     */
    fun reviseScope(grantId: String, draft: ExecutionGrantDraft): ExecutionGrantStore.Write {
        store.retryIfDue(now())
        val scope = ExecutionPolicy.validate(draft).getOrElse {
            return ExecutionGrantStore.Write.Refused((it as? ExecutionRefusal)?.code ?: "invalid")
        }
        val t = clock()
        return store.widen(grantId) { g ->
            if (g.state != ExecutionGrantState.ACTIVE && g.state != ExecutionGrantState.AWAITING_OWNER_CONFIRM) return@widen null
            if (ExecutionAuthorizer.lifecycleDeny(g, t) != null) return@widen null
            g.copy(
                revision = g.revision + 1,
                sourceLabel = draft.sourceLabel.trim(),
                workspaces = scope.workspaces,
                allowedAgents = draft.allowedAgents.distinct(),
                approvalCeiling = draft.approvalCeiling,
                maxConcurrentRuns = draft.maxConcurrentRuns,
                maxQueuedRuns = draft.maxQueuedRuns,
                runTimeoutMs = draft.runTimeoutMs,
                perGrantRequestBudget = draft.perGrantRequestBudget,
                expiresAt = minOf(t + draft.ttlMs, g.createdAt + ExecutionPolicy.MAX_TTL_MS),
            )
        }
    }

    /**
     * Revoke: in force in memory immediately; tombstone + main file written, and if either write fails the
     * whole store is unavailable until the retry lands ([ExecutionGrantStore.revoke]). The LOCAL credential
     * is dropped from [BridgeRegistry] in the same step — otherwise the key would keep authenticating a
     * transport whose every frame the guard then refuses — and the relay credential is revoked; a relay
     * failure is recorded and retried, not just logged.
     */
    suspend fun revoke(grantId: String, reason: String = "owner_revoked"): ExecutionGrantStore.Write {
        maintain()
        val before = store.byId(grantId)
        val w = store.revoke(grantId, reason, clock())
        // #367 security review LOW-1: the credential dies UNCONDITIONALLY. The previous shape returned early
        // when the store was Unavailable or the row was NotFound — precisely the two states in which the
        // owner most needs the link gone. "The tombstone could not be written" is a reason to keep refusing
        // everything, never a reason to leave an authenticated remote link alive; and a row this daemon can
        // no longer read is not evidence that no credential is bound to it. So: cut first, report after.
        before?.sourceDeviceId?.let {
            runCatching { cutLink(it) }.exceptionOrNull()?.let { e -> if (e is kotlinx.coroutines.CancellationException) throw e }
            bridges.remove(it) // idempotent belt: [cutLink] normally did it, an unwired one did not
            revokeAtRelay(it)
        }
        return w
    }

    private suspend fun revokeAtRelay(deviceId: String) {
        val failure = runCatching { revokeRelayDevice(deviceId) }.exceptionOrNull()
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        synchronized(lock) {
            if (failure == null) {
                relayRevokes.remove(deviceId)
            } else {
                val s = relayRevokes.getOrPut(deviceId) { RelayRevoke(0, 0, ExecutionGrantStore.RETRY_INITIAL_MS / 2) }
                s.failures++
                s.backoffMs = minOf(s.backoffMs * 2, ExecutionGrantStore.RETRY_MAX_MS)
                s.nextAt = now() + s.backoffMs
                refusals.note(deviceId, "relay_revoke_pending")
            }
        }
        if (failure != null) {
            log.error("relay revoke FAILED for execution link ${deviceId.take(8)}… (${failure::class.simpleName}) — reported to owner, retrying")
        }
    }

    // ---------------------------------------------------------------- bind hook (the ONE integration point)

    /**
     * [BridgeRegistry.finalize] just classified [deviceId] as an EXECUTION credential: its first transport
     * frame decrypted under exactly the derived first-contact PSK this daemon armed for [grantId], which is
     * proof the peer holds the out-of-band invite secret and not merely a relay-issued ticket. Bind the
     * proven deviceId and static key into the grant row.
     *
     * Returns false to REFUSE the credential; the caller must then drop it (and its live session) — no
     * execution link exists whose grant row does not name it, and no grant names a device it did not bind.
     */
    override suspend fun onRedeemed(deviceId: String, linkPubB64: String, grantId: String?): Boolean {
        store.retryIfDue(now())
        val t = clock()
        store.unavailableReason?.let { refuse(deviceId, it); return false }
        val id = grantId?.takeIf { it.isNotBlank() } ?: run { refuse(deviceId, "grant_unknown"); return false }
        if (linkPubB64.isBlank()) { refuse(deviceId, "invalid_key"); return false }
        // a key this daemon already trusts under ANOTHER identity must never be re-labelled an execution
        // link: the announce that produced it is a confusion, not a fresh join
        if (isKnownDevice(deviceId)) { refuse(deviceId, "known_device"); return false }
        val pending = store.byId(id) ?: run { refuse(deviceId, "no_pending_grant"); return false }
        if (pending.state != ExecutionGrantState.PENDING_REDEEM) { refuse(deviceId, "grant_not_pending"); return false }
        if ((pending.ticketExpiresAt ?: 0L) + TICKET_GRACE_MS <= t) { refuse(deviceId, "ticket_expired"); return false }
        ExecutionAuthorizer.lifecycleDeny(pending, t)?.let { refuse(deviceId, it); return false }
        val w = store.widen(id) {
            if (it.state != ExecutionGrantState.PENDING_REDEEM) null
            else it.copy(
                state = ExecutionGrantState.AWAITING_OWNER_CONFIRM,
                sourceDeviceId = deviceId,
                sourceLinkPub = linkPubB64,
                sourceLinkFingerprint = ExecutionFingerprint.of(linkPubB64),
                ticketHash = null,
                ticketExpiresAt = null,
            )
        }
        if (w !is ExecutionGrantStore.Write.Ok) {
            log.warn("execution grant ${id.take(10)}… first contact could not be bound (${w::class.simpleName}) — refused")
            refuse(deviceId, if (w is ExecutionGrantStore.Write.Refused) w.code else "bind_persist_failed")
            return false
        }
        log.info("execution grant ${id.take(10)}… bound to ${deviceId.take(8)}…, awaiting owner link-fingerprint confirmation")
        return true
    }

    private fun refuse(deviceId: String, code: String) = refusals.note(deviceId, code)

    companion object {
        /** Same slack as BridgeRegistry.INTENT_GRACE_MS: redeem → connect → first frame latency. */
        const val TICKET_GRACE_MS = 120_000L
        /** Local ceiling on the relay-reported ticket lifetime (review HIGH-1). */
        const val MAX_TICKET_TTL_SEC = 600
    }
}

/**
 * The bind seam the transport calls, so [dev.ccpocket.daemon.relay.DeviceSessions] depends on an interface
 * rather than on the execution plane — the exact shape
 * [dev.ccpocket.daemon.handoff.CollaboratorControl.onRedeemed] already has for collaborator links.
 */
interface ExecutionControl {
    suspend fun onRedeemed(deviceId: String, linkPubB64: String, grantId: String?): Boolean
}

/**
 * What a source is told about its own grant. Scope is disclosed only for an ACTIVE grant: a grant still
 * awaiting the target owner's fingerprint confirmation reports its state and nothing else.
 */
fun executionGrantInfo(g: ExecutionGrant): ExecutionGrantInfo =
    if (g.state != ExecutionGrantState.ACTIVE) {
        ExecutionGrantInfo(g.grantId, g.revision, g.state.wire, g.expiresAt)
    } else {
        ExecutionGrantInfo(
            grantId = g.grantId,
            revision = g.revision,
            state = g.state.wire,
            expiresAt = g.expiresAt,
            workspaceAliases = g.workspaces.map { it.alias },
            agents = g.allowedAgents.map { AgentKind.serializer().descriptor.getElementName(it.ordinal) },
            approvalCeiling = PermissionMode.serializer().descriptor.getElementName(g.approvalCeiling.ordinal),
            maxConcurrentRuns = g.maxConcurrentRuns,
            maxQueuedRuns = g.maxQueuedRuns,
            runTimeoutMs = g.runTimeoutMs,
            perGrantRequestBudget = g.perGrantRequestBudget,
        )
    }

@OptIn(ExperimentalStdlibApi::class)
internal fun hashHex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).toHexString()

private val RNG = SecureRandom()

internal fun randomGrantId(): String = "xg_" + b64(ByteArray(16).also { RNG.nextBytes(it) })
