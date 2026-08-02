package dev.ccpocket.relay.push

import dev.ccpocket.protocol.NotifyPush

/**
 * The two pure decisions behind a [NotifyPush] — WHERE it may route to, and WHETHER presence lets it
 * through — split out of the relay's socket loop so both are unit-testable without a websocket (the same
 * shape as the daemon's `PushPolicy`).
 */
object NotifyGate {

    /**
     * The silent routing payload delivered as APNs custom keys / FCM data.
     *
     * A handoff id WINS over workdir/sessionId and is carried alone: an offer alert
     * (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4) must be content-free, and workdir is cleartext to this
     * relay and to the lock screen. A daemon should never populate both — this is the belt to that brace.
     */
    fun routeOf(body: NotifyPush): NotifyRoute? = when {
        body.handoffId != null -> NotifyRoute(handoffId = body.handoffId, kind = body.kind)
        body.workdir != null && body.sessionId != null -> NotifyRoute(body.workdir, body.sessionId, kind = body.kind)
        else -> null
    }

    /**
     * Does presence let this push through?
     *
     * TARGETED ([NotifyPush.deviceId] non-null, §3.4): only that device's own sockets count. The
     * account-level counter is meaningless here in both directions — a collaborator inbox is
     * presence-invisible there (it would look permanently offline), and the owner's phone being attached
     * says nothing about whether a contact's phone is. [NotifyPush.urgent] does not enter into it either:
     * the one device this is addressed to either has the live data plane the alert duplicates, or it does
     * not.
     *
     * ACCOUNT fan-out: unchanged — push only when no INTERACTIVE device socket is live (an attached phone
     * already got the event on the data plane; an always-on bridge must never mute the owner's pushes,
     * issue #91), except for an [NotifyPush.urgent] notify, which lands even with a phone attached because
     * the thing it is about is NOT on the data plane of whatever that phone is currently viewing.
     */
    fun shouldSend(body: NotifyPush, interactiveDevices: Int, targetDeviceSockets: Int): Boolean =
        if (body.deviceId != null) targetDeviceSockets == 0
        else body.urgent || interactiveDevices == 0

    /** One alert, ready to hand to a [PushSender]. */
    data class Alert(val title: String, val body: String, val route: NotifyRoute?)

    /**
     * The alert copy for a push aimed at a CONTACT's phone. Fixed here, relay-side, on purpose.
     *
     * Every other push in this system travels from a person to their OWN devices, so letting the daemon
     * write the text is letting you talk to yourself. A §3.4 targeted push is the first one that crosses to
     * SOMEONE ELSE's phone, and the daemon is on the far side of that boundary: an owner who patched their
     * daemon could otherwise render arbitrary text on a colleague's lock screen under the cc-pocket app
     * identity ("Session expired — re-enter your pairing code"). The daemon's own copy is discarded and
     * these constants are used instead, so the worst an owner can do is ring the doorbell they were already
     * entitled to ring.
     *
     * Returns null when the push must be DROPPED: a contact alert has to be an offer nudge, i.e. it must
     * carry a handoff id and nothing else. Refusing the workdir/sessionId shape here also stops an owner
     * from pushing their project path onto a contact's phone and deep-linking that contact's app at a
     * session id of the owner's choosing.
     */
    fun contactAlert(body: NotifyPush): Alert? {
        val handoffId = body.handoffId ?: return null
        return Alert(CONTACT_TITLE, CONTACT_BODY, NotifyRoute(handoffId = handoffId))
    }

    /** The alert for a push the daemon addressed to one of its OWNER's own devices — its own copy, as
     *  always: there is no boundary being crossed, so nothing to launder. */
    fun ownAlert(body: NotifyPush) = Alert(body.title, body.body, routeOf(body))

    // Kept in step with the daemon's PushPolicy.OFFER_TITLE/OFFER_BODY. Duplicated rather than shared
    // because that is the point: the relay must not depend on the sender for this string.
    const val CONTACT_TITLE = "cc-pocket"
    const val CONTACT_BODY = "You have a new handoff offer. Open the app to see the details."
}
