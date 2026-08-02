package dev.ccpocket.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A pairing URL delivered by the OS via the `ccpocket://` scheme — e.g. the user scanned the QR
 * shown by `cc-pocket pair` with their system Camera. The platform entry point calls [handle];
 * the Compose root observes [pending] and pairs.
 */
object DeepLink {
    val pending = MutableStateFlow<String?>(null)
    fun handle(url: String) { pending.value = url }
}

/** A session to resume — from a tapped task-complete push, or the desktop's cross-machine pin jump.
 *  [title]/[agent] are display seeds the opener may know (pins do; pushes don't — null keeps defaults). */
data class SessionRoute(
    val workdir: String,
    val sessionId: String,
    val title: String? = null,
    val agent: dev.ccpocket.protocol.AgentKind? = null,
)

/**
 * A pending "open this session" request from a tapped push. The platform entry points set it
 * (iOS: the notification's userInfo in `didReceive`; Android: the launch intent's `wd`/`sid` extras);
 * the Compose root observes [pending] and asks the repository to connect (if needed) and open it.
 */
object PushRoute {
    val pending = MutableStateFlow<SessionRoute?>(null)
    fun open(workdir: String, sessionId: String) {
        if (workdir.isNotEmpty() && sessionId.isNotEmpty()) pending.value = SessionRoute(workdir, sessionId)
    }

    /**
     * A tapped Handoff OFFER push (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4). The alert is content-free by
     * design, so the ONLY thing it can route by is the opaque handoff id (APNs/FCM key `hid`) — there is no
     * workdir or session to open, and there must not be: the offer's contents are pulled end-to-end
     * encrypted over the Collaborator Link once the app is awake. The Compose root hands this to the
     * repository's `pendingOfferId`, which SELECTS the offer in the incoming-handoff doorway — it never
     * accepts it. The confirm-then-accept screen is always in the path.
     */
    val pendingHandoff = MutableStateFlow<String?>(null)
    fun openHandoff(handoffId: String) {
        if (handoffId.isNotEmpty()) pendingHandoff.value = handoffId
    }
}
