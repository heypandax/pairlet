package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.server.isOwner
import dev.ccpocket.protocol.AcknowledgeReviewRequest
import dev.ccpocket.protocol.ActOnReviewInbox
import dev.ccpocket.protocol.CancelReviewRequest
import dev.ccpocket.protocol.CloseReviewRequest
import dev.ccpocket.protocol.CreateReviewInvite
import dev.ccpocket.protocol.CreateReviewRequest
import dev.ccpocket.protocol.DeclineReviewRequest
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GetReviewRequest
import dev.ccpocket.protocol.JoinReviewContact
import dev.ccpocket.protocol.ListReviewContacts
import dev.ccpocket.protocol.ListReviewInbox
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.MarkReviewDelivered
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PrepareReviewRequest
import dev.ccpocket.protocol.RemoveReviewContact
import dev.ccpocket.protocol.RespondReviewRequest
import dev.ccpocket.protocol.ReviewContactUpdated
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewInboxActed
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewInviteCreated
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewPrepared
import dev.ccpocket.protocol.ReviewRequestCreated
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.StartReviewRequest

/**
 * The ReviewRequest frame family, dispatched for [dev.ccpocket.daemon.server.RequestRouter.handle] — moved
 * out of the router's `when` verbatim, in the style of `dispatchOwnerControl`. The router forwards from its
 * final `else`, so every frame type it handles itself still wins first, and anything this returns false
 * for still gets the router's `unsupported` answer.
 *
 * [dev] / [origin] / [guestScope] / [collabScope] are exactly the router's own values for this frame;
 * [reviews] / [reviewOwner] are the router's (nullable) review planes.
 *
 * @return true when [frame] is a review frame (and has been answered), false otherwise.
 */
internal suspend fun dispatchReviewFrame(
    frame: Frame,
    sink: OutboundSink,
    dev: String,
    origin: String?,
    guestScope: GuestScope?,
    collabScope: CollaboratorScope?,
    reviews: ReviewService?,
    reviewOwner: ReviewOwnerService?,
): Boolean {
    when (frame) {
        // ---- ReviewRequest control frames (REVIEW-REQUEST.md §10). A SEPARATE plane from the
        // handoff frames above: no session, no lease, no workdir — so the checks are correspondingly
        // narrow. Two credential classes only:
        //   OWNER (create/cancel/close): origin == null && guestScope == null && collabScope == null;
        //   RECIPIENT (delivered/acknowledge/start/decline/respond): collabScope != null, and the
        //     registry additionally requires the row to be addressed to THAT device.
        // A bridge or guest reaches neither: their ingress caps deny these types outright, and the
        // explicit re-checks here are the defence in depth that made #202's owner test worth writing.
        is CreateReviewRequest -> {
            val svc = reviews
            when {
                svc == null -> sink.emit(ReviewRequestCreated(ok = false, error = "review requests are not available on this daemon", code = "review_unavailable"))
                !isOwner(origin, guestScope, collabScope) ->
                    sink.emit(PocketError("review_forbidden", "not permitted for a restricted credential"))
                else -> when (
                    val out = svc.send(
                        senderDeviceId = dev,
                        senderLabel = null,
                        recipientDeviceId = frame.recipientDeviceId,
                        title = frame.title,
                        brief = frame.brief,
                        artifacts = frame.artifacts,
                        dueAt = frame.dueAt,
                        expiresAt = frame.expiresAt,
                    )
                ) {
                    is ReviewRegistry.Outcome.Ok -> sink.emit(ReviewRequestCreated(ok = true, request = out.request))
                    is ReviewRegistry.Outcome.Refused ->
                        sink.emit(ReviewRequestCreated(ok = false, error = out.message, code = out.code))
                }
            }
        }
        is ListReviewRequests -> {
            val svc = reviews
            when {
                svc == null -> sink.emit(ReviewListing())
                origin != null || guestScope != null -> sink.emit(PocketError("review_forbidden", "not permitted for a restricted credential"))
                else -> sink.emit(
                    ReviewListing(
                        svc.registry.list(
                            status = frame.status,
                            // a COLLABORATOR sees ONLY rows addressed to its own device; an owner sees all
                            recipientDeviceId = collabScope?.deviceId,
                            sinceRevision = frame.sinceRevision,
                        ),
                    ),
                )
            }
        }
        is GetReviewRequest -> {
            val svc = reviews
            when {
                svc == null -> sink.emit(PocketError("review_unavailable", "review requests are not available on this daemon"))
                origin != null || guestScope != null -> sink.emit(PocketError("review_forbidden", "not permitted for a restricted credential"))
                else -> {
                    val r = svc.registry.byId(frame.requestId)
                    // "no such request" and "not yours" share ONE answer: a bound recipient must not be
                    // able to probe the owner's ledger for ids it was never given
                    if (r == null || (collabScope != null && r.recipientDeviceId != collabScope.deviceId)) {
                        sink.emit(PocketError("review_not_allowed", "no review request with that id is addressed to you"))
                    } else {
                        sink.emit(ReviewUpdated(r))
                    }
                }
            }
        }
        is MarkReviewDelivered -> reviewRecipientMutation(reviews, sink, origin, guestScope, collabScope) {
            it.markDelivered(frame.requestId, collabScope!!.deviceId, frame.idempotencyKey)
        }
        is AcknowledgeReviewRequest -> reviewRecipientMutation(reviews, sink, origin, guestScope, collabScope) {
            it.acknowledge(frame.requestId, collabScope!!.deviceId, frame.idempotencyKey)
        }
        is StartReviewRequest -> reviewRecipientMutation(reviews, sink, origin, guestScope, collabScope) {
            it.start(frame.requestId, collabScope!!.deviceId, frame.idempotencyKey)
        }
        is DeclineReviewRequest -> reviewRecipientMutation(reviews, sink, origin, guestScope, collabScope) {
            it.decline(frame.requestId, collabScope!!.deviceId, frame.reason, frame.idempotencyKey)
        }
        is RespondReviewRequest -> reviewRecipientMutation(reviews, sink, origin, guestScope, collabScope) {
            it.respond(frame.requestId, collabScope!!.deviceId, frame.result, frame.idempotencyKey)
        }
        is CancelReviewRequest -> reviewOwnerMutation(reviews, sink, origin, guestScope, collabScope) { it.cancel(frame.requestId) }
        is CloseReviewRequest -> reviewOwnerMutation(reviews, sink, origin, guestScope, collabScope) { it.close(frame.requestId) }

        // ---- the OWNER-LOCAL review plane (REVIEW-REQUEST.md §6 + §12): the App/desktop driving the
        // same local-control surface the CLI drives — contacts, THIS machine's received inbox,
        // prepare, and the recipient actions this daemon queues on the owner's behalf.
        //
        // Every one is OWNER-ONLY via [ownerReview], which tests all THREE restricted credential
        // classes. A collaborator arrives with origin == null && guestScope == null, so the two-field
        // check the older frames grew up with would have admitted precisely the weakest credential
        // this daemon issues — and these frames expose every colleague's inbox, not just its own.
        is ListReviewContacts -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            sink.emit(ReviewContactsListing(svc.contacts()))
        }
        is CreateReviewInvite -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            when (val out = svc.invite(frame.label)) {
                is ReviewOwnerService.Outcome.Refused ->
                    sink.emit(ReviewInviteCreated(ok = false, error = out.message, code = out.code))
                // the one-time URI: emitted once to the owner that asked, never logged
                is ReviewOwnerService.Outcome.Ok -> sink.emit(
                    ReviewInviteCreated(ok = true, invite = out.value.uri, ttlSec = out.value.ttlSec, label = out.value.label),
                )
            }
        }
        is JoinReviewContact -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            sink.emit(svc.join(frame.invite, frame.label).toContactReply())
        }
        is RemoveReviewContact -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            sink.emit(svc.remove(frame.id, frame.direction).toContactReply())
        }
        is ListReviewInbox -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            sink.emit(ReviewInboxListing(svc.inbox(frame.status)))
        }
        is PrepareReviewRequest -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            when (val out = svc.prepare(frame.requestId)) {
                is ReviewOwnerService.Outcome.Refused ->
                    sink.emit(ReviewPrepared(ok = false, error = out.message, code = out.code))
                is ReviewOwnerService.Outcome.Ok -> sink.emit(ReviewPrepared(ok = true, bundle = out.value))
            }
        }
        is ActOnReviewInbox -> ownerReview(reviewOwner, sink, origin, guestScope, collabScope) { svc ->
            when (val out = svc.act(frame.requestId, frame.action, frame.reason, frame.result)) {
                is ReviewOwnerService.Outcome.Refused -> sink.emit(
                    ReviewInboxActed(ok = false, requestId = frame.requestId, error = out.message, code = out.code),
                )
                // honest by construction: `queued` says the daemon recorded the intent and will keep
                // retrying — never that the colleague has seen it
                is ReviewOwnerService.Outcome.Ok -> sink.emit(
                    ReviewInboxActed(
                        ok = true,
                        requestId = out.value.requestId,
                        queued = out.value.queued,
                        status = out.value.status,
                    ),
                )
            }
        }

        else -> return false
    }
    return true
}

/**
 * One ReviewRequest transition driven by the BOUND RECIPIENT. The recipient binding itself is
 * enforced INSIDE [ReviewRegistry] against the transport-proven deviceId — never here, and never
 * from a payload field — so there is exactly one place that decides "is this yours".
 *
 * A repeat ([ReviewRegistry.Outcome.Ok] with `changed = false`) still answers the caller with the
 * authoritative row (that is how its outbox learns the mutation landed) but is NOT fanned out: it is
 * not news, and re-broadcasting it would make every retry look like a fresh transition.
 */
private suspend fun reviewRecipientMutation(
    reviews: ReviewService?,
    sink: OutboundSink,
    origin: String?,
    guestScope: GuestScope?,
    collab: CollaboratorScope?,
    op: (ReviewRegistry) -> ReviewRegistry.Outcome,
) {
    val svc = reviews
    if (svc == null) { sink.emit(PocketError("review_unavailable", "review requests are not available on this daemon")); return }
    if (origin != null || guestScope != null) { sink.emit(PocketError("review_forbidden", "not permitted for a restricted credential")); return }
    if (collab == null) {
        // the recipient plane belongs to the bound COLLABORATOR credential. An owner device answering
        // its own request would be answering itself; there is no such thing on this machine.
        sink.emit(PocketError("review_forbidden", "only the bound recipient can act on a review request"))
        return
    }
    when (val out = op(svc.registry)) {
        is ReviewRegistry.Outcome.Ok -> {
            sink.emit(ReviewUpdated(out.request))
            if (out.changed) svc.broadcast(listOf(out.request))
        }
        is ReviewRegistry.Outcome.Refused -> sink.emit(PocketError(out.code, out.message))
    }
}

/**
 * The single gate for the OWNER-LOCAL review plane. One helper rather than a repeated `when` because
 * every frame it guards exposes the SAME blast radius — this machine's whole peer inbox and contact
 * ledger — so they must not be able to drift apart, and a new one added later inherits the check by
 * construction rather than by the author remembering.
 */
private fun ReviewOwnerService.Outcome<dev.ccpocket.protocol.ReviewContact>.toContactReply() = when (this) {
    is ReviewOwnerService.Outcome.Refused -> ReviewContactUpdated(ok = false, error = message, code = code)
    is ReviewOwnerService.Outcome.Ok -> ReviewContactUpdated(ok = true, contact = value)
}

private suspend fun ownerReview(
    reviewOwner: ReviewOwnerService?,
    sink: OutboundSink,
    origin: String?,
    guestScope: GuestScope?,
    collab: CollaboratorScope?,
    op: suspend (dev.ccpocket.daemon.review.ReviewOwnerService) -> Unit,
) {
    val svc = reviewOwner
    if (svc == null) { sink.emit(PocketError("review_unavailable", "review requests are not available on this daemon")); return }
    if (!isOwner(origin, guestScope, collab)) {
        sink.emit(PocketError("review_forbidden", "not permitted for a restricted credential")); return
    }
    op(svc)
}

/** Cancel/close: owner plane only. A collaborator that could close a request could make its own
 *  result look acknowledged; one that could cancel could erase a colleague's ask. */
private suspend fun reviewOwnerMutation(
    reviews: ReviewService?,
    sink: OutboundSink,
    origin: String?,
    guestScope: GuestScope?,
    collab: CollaboratorScope?,
    op: (ReviewRegistry) -> ReviewRegistry.Outcome,
) {
    val svc = reviews
    if (svc == null) { sink.emit(PocketError("review_unavailable", "review requests are not available on this daemon")); return }
    if (!isOwner(origin, guestScope, collab)) { sink.emit(PocketError("review_forbidden", "not permitted for a restricted credential")); return }
    when (val out = op(svc.registry)) {
        is ReviewRegistry.Outcome.Ok -> {
            sink.emit(ReviewUpdated(out.request))
            if (out.changed) svc.broadcast(listOf(out.request))
        }
        is ReviewRegistry.Outcome.Refused -> sink.emit(PocketError(out.code, out.message))
    }
}
