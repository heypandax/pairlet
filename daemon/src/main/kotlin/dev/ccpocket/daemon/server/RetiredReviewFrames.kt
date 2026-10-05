package dev.ccpocket.daemon.server

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListReviewContacts
import dev.ccpocket.protocol.ListReviewInbox
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewListing

/**
 * Review requests were retired (2026-10). The protocol keeps the `pocket/review.*` types so an older App or
 * an older peer daemon still decodes; this is the whole of this daemon's answer to them.
 *
 * The three LIST requests answer with their own reply type, empty: an older App sends them by itself
 * (opening or refreshing its Review Center) and renders an empty list cleanly, where an `unsupported` error
 * would add an error row to the open chat. Every other review request gets null here and takes the router's
 * ordinary `unsupported` answer. Either way every request is answered, so no caller waits out a timeout.
 */
internal fun retiredReviewListing(frame: Frame): Frame? = when (frame) {
    is ListReviewRequests -> ReviewListing()
    is ListReviewInbox -> ReviewInboxListing()
    ListReviewContacts -> ReviewContactsListing()
    else -> null
}
