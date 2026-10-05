package dev.ccpocket.daemon.server

import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.ListCollaborators
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.ListReviewContacts
import dev.ccpocket.protocol.ListReviewInbox
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.ListShares
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ShareListing

/**
 * Retired features (2026-10): review requests (`pocket/review.*`), session handoff (`pocket/handoff.*`),
 * collaborator contacts (`pocket/collaborator.*`) and folder sharing (`pocket/share.*`, #115). The protocol
 * keeps their types so an older App or an older peer daemon still decodes; this is the whole of this daemon's
 * answer to them.
 *
 * The LIST requests answer with their own reply type, empty: an older App sends them by itself — opening
 * any session sends `ListHandoffs`, opening its Review Center, contacts or shared-folders screen sends the
 * others — and
 * renders an empty list cleanly, where an `unsupported` error would add an error row to the open chat (and
 * could cut short the session open it arrived during). Every other retired request gets null here and takes
 * the router's ordinary `unsupported` answer. Either way every request is answered, so no caller waits out a
 * timeout.
 */
internal fun retiredFeatureListing(frame: Frame): Frame? = when (frame) {
    is ListReviewRequests -> ReviewListing()
    is ListReviewInbox -> ReviewInboxListing()
    ListReviewContacts -> ReviewContactsListing()
    is ListHandoffs -> HandoffListing()
    ListCollaborators -> CollaboratorListing()
    ListShares -> ShareListing()
    else -> null
}
