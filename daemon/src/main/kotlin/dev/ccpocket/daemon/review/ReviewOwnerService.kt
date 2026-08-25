package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.handoff.CollaboratorControl
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewExecutionBundle
import dev.ccpocket.protocol.ReviewInboxAction
import dev.ccpocket.protocol.ReviewInboxItem
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.acceptsReviewRequest
import dev.ccpocket.protocol.collaboratorFingerprint
import dev.ccpocket.protocol.isTerminal

/**
 * The OWNER-LOCAL ReviewRequest control plane (REVIEW-REQUEST.md §6 + §12): contacts, THIS machine's
 * received inbox, prepare, and the recipient-side actions the daemon queues on the owner's behalf.
 *
 * It exists so the three owner surfaces — the CLI's HTTP API, the App/desktop wire frames, and any
 * future MCP tool — run ONE implementation. Before it, `resolve this contact`, `is this contact allowed
 * to receive a review` and `what does prepare refuse` lived only inside the HTTP handlers; a second
 * caller would have had to re-derive them, and the two would have drifted exactly where the security
 * argument lives (§3.3: "Skill 不能直接实现联系人、网络协议或状态机").
 *
 * Everything here is OWNER-ONLY. The service does not authenticate — its callers do (the HTTP layer by
 * its 0600 token, the wire layer by refusing every restricted credential) — but it is written on the
 * assumption that its caller is the machine's owner, so it will happily list every peer's inbox.
 *
 * Nothing it returns may carry a ticket, relay credential, private key or control token: the DTOs are
 * the wire's public metadata types, and [invite] is the single deliberate exception (a one-time connect
 * URI, returned once, never logged).
 */
class ReviewOwnerService(
    /** The contact ledger, which only exists once the relay link is up — hence a provider, not a value
     *  (a LAN-only `serve` and the seconds before the first connect both legitimately have none). */
    private val collaborators: () -> CollaboratorControl?,
    val reviews: ReviewService,
    val peerInbox: PeerInboxService,
) {
    private val log = logger("ReviewOwner")

    /** One answer shape for every operation: the value, or a machine-readable refusal both the CLI's
     *  `code` field and the wire's error frames key on. */
    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Refused(val code: String, val message: String) : Outcome<Nothing>
    }

    /**
     * A minted one-time connect URI. Establishment material — show once, never log.
     *
     * [fingerprint] is the same word group the JOINER will see, computed here from the daemon key inside
     * [uri]. Verification is only worth anything if BOTH people can read the words out; without this the
     * inviter has nothing to compare against and "check the fingerprint" degrades into the joiner
     * confirming a value only they can see. Public, derived, display-only — never key material.
     */
    data class Invite(val uri: String, val ttlSec: Int, val label: String?, val fingerprint: String)

    /** The honest answer to a queued recipient action: recorded locally, not seen by the colleague. */
    data class Acted(val requestId: String, val queued: Boolean, val status: ReviewStatus)

    // ---- contacts ----------------------------------------------------------

    /**
     * Both directions in one list. OUTBOUND rows come from the contact ledger the App also renders,
     * INBOUND rows from this machine's own peer links. The two id spaces never collide (`pl_…` vs a
     * relay deviceId), which is what lets `(id, direction)` be the stable handle for every action.
     */
    suspend fun contacts(): List<ReviewContact> {
        // purpose-scoped at the LEDGER, not filtered afterwards: `list()` is the legacy Session Handoff
        // view and a Review surface must never read from it (§13.3), in either direction
        val outbound = collaborators()?.contacts(CollaboratorPurpose.REVIEW).orEmpty()
            .filter { it.direction != CollaboratorDirection.INBOUND }
            .map { it.toReviewContact() }
        val inbound = peerInbox.list().map { it.toReviewContact() }
        return outbound + inbound
    }

    /** Mint a REVIEW-purpose one-time invite for a colleague's daemon to redeem. */
    suspend fun invite(label: String?): Outcome<Invite> {
        ReviewLimits.singleLine(label, ReviewLimits.MAX_LABEL, "label")?.let {
            return Outcome.Refused("invite_invalid", it)
        }
        val control = collaborators() ?: return Outcome.Refused(
            "relay_offline",
            "minting an invite needs the relay link — the daemon is still starting, or it's running LAN-only",
        )
        val res = control.createTicket(label, CollaboratorPurpose.REVIEW)
        val invite = res.invite
        if (!res.ok || invite == null) {
            return Outcome.Refused("invite_refused", res.error ?: "could not mint an invite")
        }
        return Outcome.Ok(
            Invite(
                uri = invite.encodeUri(),
                ttlSec = invite.ttlSec,
                label = label,
                // from the invite's own daemonPub — the exact bytes the peer will pin — so the two ends
                // are computing over the same input, not over two hopefully-equal copies of it
                fingerprint = collaboratorFingerprint(invite.daemonPub),
            ),
        )
    }

    /**
     * Redeem a peer's invite ON THIS DAEMON. The credential belongs to the always-on daemon, never to
     * the App that scanned the QR — that is precisely what keeps delivery, retry and history working
     * with every UI closed (§3.3).
     */
    suspend fun join(invite: String, label: String?): Outcome<ReviewContact> =
        when (val res = peerInbox.join(invite, label)) {
            is PeerInboxService.JoinResult.Refused -> Outcome.Refused(res.code, res.message)
            is PeerInboxService.JoinResult.Ok -> Outcome.Ok(res.link.toReviewContact())
        }

    /**
     * Sever one contact. [direction] disambiguates the two id spaces; [CollaboratorDirection.UNKNOWN]
     * means "resolve by id or label across both", which is what the CLI's `remove <id-or-label>` does.
     *
     * AN ID IS NEVER RE-READ AS A LABEL. A label is not owner-controlled on an inbound link: it falls
     * back to the `ownerLabel` the PEER put in its own invite (see [PeerInboxService.join]). So a peer
     * can set its label to ANOTHER contact's id, and a single id-or-label pass would let it decide what
     * `remove <that-id>` does — first jamming it (two hits → ambiguous), then, once the real contact is
     * gone, absorbing it (the only remaining hit is the peer's own link, severed under a name the owner
     * meant for someone else).
     *
     * So the resolution is strictly staged: an exact live id wins; a string that names a KNOWN id — even
     * an already-removed one — stops there rather than falling through to labels; only then are labels
     * considered, and a genuine collision among them still fails closed.
     *
     * History is deliberately kept on both paths: past requests reference the contact by label.
     */
    suspend fun remove(idOrLabel: String, direction: CollaboratorDirection = CollaboratorDirection.UNKNOWN): Outcome<ReviewContact> {
        val all = contacts()
        val visible = all.filter {
            !it.removed && (direction == CollaboratorDirection.UNKNOWN || it.direction == direction)
        }
        val byId = visible.filter { it.id == idOrLabel }
        val matches = when {
            byId.isNotEmpty() -> byId
            // the id exists but its contact is already gone: say so, rather than letting a peer that
            // labelled itself with that id inherit the command
            all.any { it.id == idOrLabel } ->
                return Outcome.Refused("contact_not_found", "that contact is already removed")
            else -> visible.filter { it.label == idOrLabel }
        }
        val contact = when {
            matches.isEmpty() -> return Outcome.Refused("contact_not_found", "no active contact matches \"$idOrLabel\"")
            matches.size > 1 -> return Outcome.Refused("contact_ambiguous", "several contacts match \"$idOrLabel\" — use the id")
            else -> matches.single()
        }
        if (contact.direction == CollaboratorDirection.INBOUND) {
            when (peerInbox.remove(contact.id)) {
                PeerInboxService.RemoveResult.NotFound ->
                    return Outcome.Refused("contact_not_found", "that contact no longer exists")
                PeerInboxService.RemoveResult.PersistFailed ->
                    return Outcome.Refused("peer_link_persist_failed", "the link credential could not be removed; nothing changed")
                is PeerInboxService.RemoveResult.Ok -> Unit
            }
        } else {
            val control = collaborators()
                ?: return Outcome.Refused("relay_offline", "revoking needs the relay link — the daemon is still starting")
            // the SAME revoke path the app drives (credential revoked, live grants settled), scoped to
            // REVIEW so a Review Center can only ever sever a Review link
            when (val removed = control.remove(contact.id, CollaboratorPurpose.REVIEW)) {
                is CollaboratorUpdated ->
                    if (!removed.collaborator.removed) return Outcome.Refused("contact_remove_failed", "the collaborator was not removed")
                is PocketError -> return Outcome.Refused(removed.code, removed.message)
                else -> return Outcome.Refused("contact_remove_failed", "the collaborator was not removed")
            }
        }
        log.info("review contact \"${contact.label}\" (${contact.direction.name.lowercase()}) removed by owner")
        return Outcome.Ok(contact.copy(removed = true))
    }

    /** Resolve a `--to`/picker value to exactly one contact this machine may send a review to. */
    suspend fun resolveRecipient(needle: String): Outcome<String> {
        val matches = contacts().filter {
            it.direction != CollaboratorDirection.INBOUND && it.canSend && (it.id == needle || it.label == needle)
        }
        return when {
            matches.isEmpty() -> Outcome.Refused(
                "review_no_recipient",
                "no active contact matches \"$needle\" — check `collaborator list`",
            )
            matches.size > 1 -> Outcome.Refused("contact_ambiguous", "several contacts match \"$needle\" — use the id")
            else -> Outcome.Ok(matches.single().id)
        }
    }

    // ---- the received inbox -------------------------------------------------

    /**
     * The bounded complete snapshot a reconnecting client heals from.
     *
     * BOUNDED IN BYTES, not in rows, and that distinction is the whole point. The store's own caps are
     * [PeerInboxStore.MAX_MIRRORED_HISTORY] + [PeerInboxStore.MAX_ACTIVE_ROWS] rows, and a single request
     * may be [ReviewLimits.MAX_ENCODED_BYTES] — so a row cap alone permits a ~28 MiB frame against the
     * relay's 4 MiB `MAX_FRAME`, which does not fail as one big error: it kills the connection, the
     * client reconnects, asks again, and loops. Mirrors [ReviewRegistry.list]'s guard for the same reason.
     *
     * Open work is emitted FIRST so a bounded historical replay can never be what hides a request a
     * colleague is still waiting on.
     */
    fun inbox(status: ReviewStatus? = null): List<ReviewInboxItem> {
        val rows = peerInbox.inbox(status)
            .sortedWith(compareBy<MirrorRow> { it.request.status.isTerminal }.thenByDescending { it.request.createdAt })
        var bytes = LISTING_OVERHEAD_BYTES
        return buildList {
            for (row in rows) {
                val link = peerInbox.links.byId(row.linkId)
                val item = ReviewInboxItem(
                    linkId = row.linkId,
                    peerLabel = link?.label ?: "peer",
                    peerFingerprint = link?.fingerprint.orEmpty(),
                    request = row.request,
                    pending = peerInbox.pendingActions(row),
                )
                val rowBytes = PocketJson.encodeToString(ReviewInboxItem.serializer(), item)
                    .toByteArray(Charsets.UTF_8).size + 1
                // `continue`, not `break`: a single oversized row must not truncate everything after it
                if (bytes + rowBytes > MAX_LISTING_BYTES) continue
                add(item)
                bytes += rowBytes
            }
        }
    }

    /** Build the safe execution bundle for one RECEIVED request. Opens nothing and runs nothing. */
    fun prepare(requestId: String): Outcome<ReviewExecutionBundle> {
        val rows = peerInbox.resolve(requestId)
        return when {
            rows.isEmpty() ->
                // deliberately explicit: preparing a request you SENT is a category error, not a 404
                if (reviews.registry.byId(requestId) != null) {
                    Outcome.Refused("review_not_received", "that is a request you sent — `prepare` is for requests you received")
                } else {
                    Outcome.Refused("review_not_found", "no review request with that id is in your inbox")
                }
            rows.size > 1 -> Outcome.Refused("review_ambiguous", "two peers sent a request with that id")
            else -> {
                val row = rows.single()
                val link = peerInbox.links.byId(row.linkId)
                    ?: return Outcome.Refused("review_link_removed", "the link that sent this request is gone")
                ReviewPrepare.build(link, row.request, peerInbox.pendingActions(row)).fold(
                    onSuccess = { Outcome.Ok(it) },
                    onFailure = {
                        Outcome.Refused(
                            (it as? PrepareError)?.code ?: "review_not_preparable",
                            it.message ?: "cannot prepare this request",
                        )
                    },
                )
            }
        }
    }

    /**
     * Queue one recipient-side action. The daemon owns the retry; the answer says whether the intent was
     * RECORDED, never whether the colleague has seen it (§8) — `queued=false` with a success means the
     * request was already in that state and nothing had to be sent.
     */
    fun act(
        requestId: String,
        action: ReviewInboxAction,
        reason: String? = null,
        result: ReviewResult? = null,
    ): Outcome<Acted> {
        val (expect, run) = when (action) {
            ReviewInboxAction.ACKNOWLEDGE -> ReviewStatus.ACKNOWLEDGED to { peerInbox.acknowledge(requestId) }
            ReviewInboxAction.START -> ReviewStatus.IN_PROGRESS to { peerInbox.start(requestId) }
            ReviewInboxAction.DECLINE -> ReviewStatus.DECLINED to { peerInbox.decline(requestId, reason) }
            ReviewInboxAction.RESPOND -> {
                val body = result
                    ?: return Outcome.Refused("review_invalid", "a respond action needs a result")
                ReviewStatus.RESPONDED to { peerInbox.respond(requestId, body) }
            }
            // fail closed: a verb only a newer client knows takes NO action at all
            ReviewInboxAction.UNKNOWN -> return Outcome.Refused(
                "review_unknown_action",
                "this daemon does not understand that review action — update the daemon",
            )
        }
        return when (val res = run()) {
            is PeerInboxService.ActionResult.Refused -> Outcome.Refused(res.code, res.message)
            is PeerInboxService.ActionResult.Ok -> Outcome.Ok(Acted(res.requestId, res.queued, expect))
        }
    }
}

/** Same budget as [ReviewRegistry]'s sender-side listing: comfortably under the relay's 4 MiB frame
 *  cap with room for the envelope and the E2E overhead around it. */
private const val MAX_LISTING_BYTES = 3_500_000
private const val LISTING_OVERHEAD_BYTES = 256

/** Ledger row → wire contact. [ReviewContact.canSend] is the DAEMON's answer to "may I pick this as a
 *  recipient", so no client has to re-derive eligibility from purpose/direction/removed. */
private fun Collaborator.toReviewContact() = ReviewContact(
    id = deviceId,
    label = label,
    direction = if (direction == CollaboratorDirection.UNKNOWN) CollaboratorDirection.OUTBOUND else direction,
    fingerprint = fingerprint,
    connectedAt = connectedAt,
    removed = removed,
    purpose = purpose,
    canSend = acceptsReviewRequest,
)

/** Inbound peer link → wire contact. A peer link is a review link by construction, and it is never a
 *  send target: it is a credential WE hold in THEIR account, so requests only ever flow inward. */
private fun PeerLink.toReviewContact() = ReviewContact(
    id = id,
    label = label,
    direction = CollaboratorDirection.INBOUND,
    fingerprint = fingerprint,
    connectedAt = joinedAt,
    removed = removed,
    purpose = CollaboratorPurpose.REVIEW,
    canSend = false,
)
