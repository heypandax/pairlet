package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CollaboratorTicketCreated
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.acceptsReviewRequest
import dev.ccpocket.protocol.acceptsSessionHandoff
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
    /**
     * Mint a one-time connect ticket. [purpose] is what the redeemed contact will be established FOR
     * (REVIEW-REQUEST.md §13.3) and is decided HERE, at mint time, by the owner surface that asked —
     * never by the redeeming peer. Plain parameters rather than a wire frame on purpose: two different
     * frames (`pocket/collaborator.ticket` and `pocket/review.contact_invite`) land on this one mint,
     * and each is responsible for naming its own purpose.
     */
    suspend fun createTicket(
        label: String?,
        purpose: CollaboratorPurpose = CollaboratorPurpose.SESSION_HANDOFF,
    ): CollaboratorTicketCreated
    /**
     * The LEGACY `pocket/collaborator.listing` view: SESSION HANDOFF contacts only.
     *
     * A Review peer is deliberately invisible here. This listing feeds the App's contact page and its
     * handoff recipient picker, both of which have always meant "people I can hand a session to"; a
     * daemon-purpose row appearing in them is a row a user can pick for something it was never
     * established for. Review surfaces ask [contacts] for their own purpose instead.
     */
    suspend fun list(): CollaboratorListing

    /** The ledger scoped to ONE purpose — how every non-legacy surface reads it, so no caller has to
     *  remember to filter and none can forget. */
    suspend fun contacts(purpose: CollaboratorPurpose): List<Collaborator>

    /**
     * Sever a SESSION HANDOFF link: revoke grants + credential, flag the row removed (kept for history).
     * Refuses any other purpose — see [remove] with an explicit purpose, and the note on [list].
     */
    suspend fun remove(deviceId: String): ToPhone

    /** Sever a link of exactly [purpose]. A mismatch REFUSES and changes nothing: an id space is shared
     *  between the two features, so "remove this id" must never mean "remove whatever holds it". */
    suspend fun remove(deviceId: String, purpose: CollaboratorPurpose): ToPhone

    /** DeviceSessions' finalize hook: a COLLABORATOR credential was just confirmed for [deviceId]. */
    suspend fun onRedeemed(deviceId: String, peerPubB64: String)
}

/** What [dev.ccpocket.daemon.server.RequestRouter] needs from the contact ledger when an owner binds a
 *  Handoff to a contact — installed on [HandoffService.collaborators] so the router reads one truth. */
interface CollaboratorDirectory {
    /** The contact's display label, or null when unknown/removed. */
    fun labelOf(deviceId: String): String?
    /** True when [deviceId] is a live (non-removed, credential-backed) contact. Credential liveness
     *  ONLY — what the link may be USED for is [acceptsHandoff]/[acceptsReview]. */
    fun isActive(deviceId: String): Boolean

    /**
     * May a SESSION HANDOFF be bound to [deviceId] (REVIEW-REQUEST.md §13.3)? A
     * [CollaboratorPurpose.REVIEW] peer may not: it is a colleague's daemon holding a task-context link,
     * and a runtime handoff would hand it a session drive lease it was never established for.
     *
     * Defaulted to [isActive] so an existing test double keeps compiling with its historical meaning;
     * [CollaboratorService] overrides it with the purpose-aware answer.
     */
    fun acceptsHandoff(deviceId: String): Boolean = isActive(deviceId)

    /** May a REVIEW REQUEST be sent to [deviceId]? ONLY a link the owner minted as a Review link — see
     *  [dev.ccpocket.protocol.acceptsReviewRequest] for why a legacy contact is not silently widened. */
    fun acceptsReview(deviceId: String): Boolean = false

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

    override suspend fun createTicket(label: String?, purpose: CollaboratorPurpose): CollaboratorTicketCreated {
        // mint serialization (issue #91): a collaborator mint is a headless mint — refuse while a phone
        // pairing ticket could still be redeemed, so the LIFO PSK arming can't cross-bind them
        if (interactivePairingPending()) {
            return CollaboratorTicketCreated(ok = false, error = "a phone pairing is still valid — try again in ~2 minutes")
        }
        // an UNKNOWN purpose can only come from a newer peer's value this build cannot honour: refuse the
        // mint rather than establish a link whose scope nobody here can enforce
        if (purpose == CollaboratorPurpose.UNKNOWN) {
            return CollaboratorTicketCreated(ok = false, error = "unsupported contact purpose — update this daemon")
        }
        // issue #207: claim the one mint slot BEFORE the suspending relay round-trip. A bare
        // intentPending() check here used to let two overlapping mints both pass (both burned a ticket,
        // both got PSK-armed LIFO, only one intent recorded) — the mis-promotion race.
        if (!registry.reserveMint()) {
            return CollaboratorTicketCreated(ok = false, error = "another pairing is in progress — try again shortly")
        }
        try {
            val spec = BridgeSpec.collaborator(
                label = label?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LABEL,
                purpose = purpose,
            )
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
                    // says what the redeemer is being invited INTO, so a scanner can refuse the wrong one
                    // before it burns the ticket
                    purpose = purpose,
                ),
            )
        } finally {
            registry.releaseMint()
        }
    }

    // the legacy frame family carries SESSION HANDOFF contacts and nothing else (see [CollaboratorControl.list])
    override suspend fun list(): CollaboratorListing =
        CollaboratorListing(reconciled().filter { it.purpose == CollaboratorPurpose.SESSION_HANDOFF })

    override suspend fun contacts(purpose: CollaboratorPurpose): List<Collaborator> =
        reconciled().filter { it.purpose == purpose }

    /** The ledger, with rows whose CREDENTIAL died elsewhere (revoked from another device, pruned by the
     *  relay's attach replay while we were offline) lazily settled to removed — display truth follows the
     *  key truth, never the other way around. */
    private fun reconciled(): List<Collaborator> = store.all().map { row ->
        if (!row.removed && !registry.isCollaborator(row.deviceId)) {
            row.copy(removed = true).also { store.upsert(it) }
        } else row
    }

    override suspend fun remove(deviceId: String): ToPhone = remove(deviceId, CollaboratorPurpose.SESSION_HANDOFF)

    override suspend fun remove(deviceId: String, purpose: CollaboratorPurpose): ToPhone {
        val row = store.byId(deviceId)
            ?: return PocketError("collaborator_not_found", "no such collaborator")
        // A purpose mismatch answers exactly like a miss, and does so BEFORE anything is revoked. The
        // legacy `pocket/collaborator.remove` frame is the reason this matters: an App that predates
        // ReviewRequest cannot see a Review row, so any id it sends that resolves to one is a stale
        // or guessed id — honouring it would revoke a credential its user never knew existed.
        if (row.purpose != purpose) return PocketError("collaborator_not_found", "no such collaborator")
        if (row.removed) return PocketError("collaborator_not_found", "this collaborator link is already removed")
        // 1) kill the temporary Grants first — a bound WAITING offer dies, an IN_PROGRESS lease is
        //    recalled — so no drive window survives the link (fan-out rides HandoffUpdated as usual)
        runCatching { revokeGrants(deviceId) }
        // 2) kill the CREDENTIAL (the security boundary): local key prune + relay revoke + convo cut
        runCatching { revokeCredential(deviceId) }
        // 3) flag the row (kept: past handoffs reference this contact) and tell every owner client
        val removed = row.copy(removed = true)
        store.upsert(removed)
        fanoutLegacy(removed, CollaboratorUpdated(removed))
        log.info("collaborator link ${deviceId.take(8)}… (\"${row.label}\") removed by owner")
        return CollaboratorUpdated(removed)
    }

    override suspend fun onRedeemed(deviceId: String, peerPubB64: String) {
        val spec = registry.specOf(deviceId)
        val label = spec?.name ?: DEFAULT_LABEL
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
            // The purpose the OWNER chose at mint time, carried on the credential's own spec. A re-redeem
            // keeps whatever the fresh mint said.
            //
            // With NO spec at all — it was never persisted, or was pruned between redeem and this call —
            // there is nothing that says what this link is for, and guessing SESSION_HANDOFF would turn a
            // lost REVIEW mint into a contact the owner can bind a session handoff to. UNKNOWN instead:
            // eligible for neither feature until a fresh mint says otherwise.
            purpose = spec?.purpose ?: existing?.purpose ?: CollaboratorPurpose.UNKNOWN,
        )
        store.upsert(row)
        log.info("collaborator link established: \"${label}\" (${deviceId.take(8)}…, fp ${row.fingerprint})")
        // flip the owner's "waiting for scan…" screen + refresh every contact picker
        fanoutLegacy(row, CollaboratorConnected(row), CollaboratorUpdated(row))
    }

    /**
     * Push a legacy `pocket/collaborator.*` frame — for a SESSION HANDOFF row and nothing else.
     *
     * A Review contact's establishment and removal are real events, but these frames are not where they
     * belong: an App renders `CollaboratorConnected`/`CollaboratorUpdated` straight into its handoff
     * contact list and its recipient picker, so pushing a daemon-purpose row through them would put a
     * Review peer in front of exactly the control it must never be pickable for — and would do it on
     * OLD builds, which cannot read [Collaborator.purpose] at all. The Review Center learns about its own
     * contacts by asking (`pocket/review.contacts`), which is purpose-scoped by construction.
     */
    private suspend fun fanoutLegacy(row: Collaborator, vararg frames: ToPhone) {
        if (row.purpose != CollaboratorPurpose.SESSION_HANDOFF) return
        frames.forEach { fanoutToOwners(it) }
    }

    // ---- CollaboratorDirectory (the router's Handoff-binding view) ----

    override fun labelOf(deviceId: String): String? =
        store.byId(deviceId)?.takeUnless { it.removed }?.label

    override fun isActive(deviceId: String): Boolean =
        store.byId(deviceId)?.removed == false && registry.isCollaborator(deviceId)

    override fun acceptsHandoff(deviceId: String): Boolean =
        isActive(deviceId) && store.byId(deviceId)?.acceptsSessionHandoff == true

    override fun acceptsReview(deviceId: String): Boolean =
        isActive(deviceId) && store.byId(deviceId)?.acceptsReviewRequest == true

    override suspend fun noteHandoff(deviceId: String, at: Long) {
        // only a handoff contact can have handed off; a row of any other purpose reaching here would be
        // a binding the router should already have refused ([acceptsHandoff])
        val row = store.byId(deviceId)?.takeIf { it.purpose == CollaboratorPurpose.SESSION_HANDOFF } ?: return
        val bumped = row.copy(handoffCount = row.handoffCount + 1, lastHandoffAt = at)
        store.upsert(bumped)
        fanoutToOwners(CollaboratorUpdated(bumped))
    }

    private companion object {
        const val DEFAULT_LABEL = "collaborator"
    }
}
