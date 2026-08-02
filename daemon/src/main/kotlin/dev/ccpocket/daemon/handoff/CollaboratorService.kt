package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorTicketCreated
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.CreateCollaboratorTicket
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.collaboratorFingerprint

/**
 * The OWNER-side Collaborator Link control plane (SESSION-HANDOFF.md §4.1): mint a one-time connect
 * ticket, list contacts, sever a link — plus the redeem-side hook that turns a confirmed COLLABORATOR
 * credential into a contact row. Handled ONLY for a full-power owner device (every restricted
 * credential's capability whitelist denies these frames before dispatch), so a collaborator inviting
 * another collaborator — the re-invite escalation — is structurally impossible, exactly as
 * [dev.ccpocket.daemon.relay.ShareControl] prevents a guest re-sharing the machine.
 *
 * Minting reuses #91's binding chain UNCHANGED (the ShareService pattern): mint a headless ticket over
 * the relay, record a COLLABORATOR intent binding the ticket to the contact label; the recipient app
 * redeems the ticket at the relay exactly like a share guest and the first transport frame's PSK proof
 * ([BridgeRegistry.finalize]) confirms the credential — zero relay schema change, the relay never
 * learns this is a contact link.
 */
interface CollaboratorControl {
    suspend fun createTicket(req: CreateCollaboratorTicket): CollaboratorTicketCreated
    suspend fun list(): CollaboratorListing
    /** Sever a link: revoke grants + credential, flag the row removed (kept for history). */
    suspend fun remove(deviceId: String): ToPhone
    /** DeviceSessions' finalize hook: a COLLABORATOR credential was just confirmed for [deviceId]. */
    suspend fun onRedeemed(deviceId: String, peerPubB64: String)
}

/** What [dev.ccpocket.daemon.server.RequestRouter] needs from the contact ledger when an owner binds a
 *  Handoff to a contact — installed on [HandoffService.collaborators] so the router reads one truth. */
interface CollaboratorDirectory {
    /** The contact's display label, or null when unknown/removed. */
    fun labelOf(deviceId: String): String?
    /** True when [deviceId] is a live (non-removed, credential-backed) contact a Handoff may bind to. */
    fun isActive(deviceId: String): Boolean
    /** A Handoff was just created bound to [deviceId] — bump stats + fan the row out to owner clients. */
    suspend fun noteHandoff(deviceId: String, at: Long)
}

class CollaboratorService(
    private val accountId: String,
    private val daemonPubB64: String,
    private val relayWsBase: String,
    private val ownerLabel: () -> String?,
    private val registry: BridgeRegistry,
    private val store: CollaboratorStore = CollaboratorStore.load(),
    /** Mint a COLLABORATOR ticket. No parameters on purpose (§3.4): a contact link is always headless AND
     *  collaborator-marked, and both markers are stamped relay-side from this one mint — there is no
     *  variation for a caller to get wrong. */
    private val mintTicket: suspend () -> PairTicket?,
    private val interactivePairingPending: () -> Boolean,
    /** Local prune (key dies) + relay RevokeDevice + force-close the collaborator's convos. */
    private val revokeCredential: suspend (deviceId: String) -> Unit,
    /** Settle every non-terminal Handoff bound to the device (WAITING→CANCELLED, IN_PROGRESS→RECALLED)
     *  and fan the transitions out — wired to [HandoffService.revokeRecipient]. */
    private val revokeGrants: suspend (deviceId: String) -> Unit = {},
    /** Deliver a contact-change frame to attached OWNER clients (never to restricted credentials) —
     *  wired to [HandoffService.emitToOwners]. */
    private val fanoutToOwners: suspend (ToPhone) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : CollaboratorControl, CollaboratorDirectory {
    private val log = logger("CollaboratorService")

    override suspend fun createTicket(req: CreateCollaboratorTicket): CollaboratorTicketCreated {
        // mint serialization (issue #91): a collaborator mint is a headless mint — refuse while a phone
        // pairing ticket could still be redeemed, so the LIFO PSK arming can't cross-bind them
        if (interactivePairingPending()) {
            return CollaboratorTicketCreated(ok = false, error = "a phone pairing is still valid — try again in ~2 minutes")
        }
        if (registry.intentPending()) {
            return CollaboratorTicketCreated(ok = false, error = "another pairing is in progress — try again shortly")
        }
        val spec = BridgeSpec.collaborator(req.label?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LABEL)
        val ticket = mintTicket() ?: return CollaboratorTicketCreated(ok = false, error = "can't reach the relay — check the connection")
        // bindable window = the redeem ticket's TTL + grace (mirrors ShareService), so a slow-to-redeem
        // contact is still classified as a collaborator, never mis-promoted to a full device
        if (!registry.recordIntent(ticket.ticket, spec, ttlMs = ticket.expiresInSec * 1000L + BridgeRegistry.INTENT_GRACE_MS)) {
            return CollaboratorTicketCreated(ok = false, error = "another pairing is in progress — try again shortly")
        }
        log.info("collaborator connect ticket minted for \"${spec.name}\" (ttl ${ticket.expiresInSec}s)")
        return CollaboratorTicketCreated(
            ok = true,
            invite = CollaboratorInvite(
                relay = relayWsBase, accountId = accountId, daemonPub = daemonPubB64, ticket = ticket.ticket,
                ownerLabel = ownerLabel(), ttlSec = ticket.expiresInSec,
            ),
        )
    }

    override suspend fun list(): CollaboratorListing = CollaboratorListing(reconciled())

    /** The ledger, with rows whose CREDENTIAL died elsewhere (revoked from another device, pruned by the
     *  relay's attach replay while we were offline) lazily settled to removed — display truth follows the
     *  key truth, never the other way around. */
    private fun reconciled(): List<Collaborator> = store.all().map { row ->
        if (!row.removed && !registry.isCollaborator(row.deviceId)) {
            row.copy(removed = true).also { store.upsert(it) }
        } else row
    }

    override suspend fun remove(deviceId: String): ToPhone {
        val row = store.byId(deviceId)
            ?: return PocketError("collaborator_not_found", "no such collaborator")
        if (row.removed) return PocketError("collaborator_not_found", "this collaborator link is already removed")
        // 1) kill the temporary Grants first — a bound WAITING offer dies, an IN_PROGRESS lease is
        //    recalled — so no drive window survives the link (fan-out rides HandoffUpdated as usual)
        runCatching { revokeGrants(deviceId) }
        // 2) kill the CREDENTIAL (the security boundary): local key prune + relay revoke + convo cut
        runCatching { revokeCredential(deviceId) }
        // 3) flag the row (kept: past handoffs reference this contact) and tell every owner client
        val removed = row.copy(removed = true)
        store.upsert(removed)
        fanoutToOwners(CollaboratorUpdated(removed))
        log.info("collaborator link ${deviceId.take(8)}… (\"${row.label}\") removed by owner")
        return CollaboratorUpdated(removed)
    }

    override suspend fun onRedeemed(deviceId: String, peerPubB64: String) {
        val label = registry.specOf(deviceId)?.name ?: DEFAULT_LABEL
        val existing = store.byId(deviceId)
        val row = Collaborator(
            deviceId = deviceId,
            label = label,
            // v1 mints only the A→B outbound leg (the reverse-ticket exchange is a later milestone)
            direction = CollaboratorDirection.OUTBOUND,
            connectedAt = now(),
            fingerprint = collaboratorFingerprint(peerPubB64),
            handoffCount = existing?.handoffCount ?: 0,
            lastHandoffAt = existing?.lastHandoffAt,
            hasDaemon = existing?.hasDaemon, // unknown until the contact self-reports (later milestone)
            removed = false,
        )
        store.upsert(row)
        log.info("collaborator link established: \"${label}\" (${deviceId.take(8)}…, fp ${row.fingerprint})")
        // flip the owner's "waiting for scan…" screen + refresh every contact picker
        fanoutToOwners(CollaboratorConnected(row))
        fanoutToOwners(CollaboratorUpdated(row))
    }

    // ---- CollaboratorDirectory (the router's Handoff-binding view) ----

    override fun labelOf(deviceId: String): String? =
        store.byId(deviceId)?.takeUnless { it.removed }?.label

    override fun isActive(deviceId: String): Boolean =
        store.byId(deviceId)?.removed == false && registry.isCollaborator(deviceId)

    override suspend fun noteHandoff(deviceId: String, at: Long) {
        val row = store.byId(deviceId) ?: return
        val bumped = row.copy(handoffCount = row.handoffCount + 1, lastHandoffAt = at)
        store.upsert(bumped)
        fanoutToOwners(CollaboratorUpdated(bumped))
    }

    private companion object {
        const val DEFAULT_LABEL = "collaborator"
    }
}
