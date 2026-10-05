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
     * A tapped Session Handoff OFFER push (APNs/FCM key `hid`). Session Handoff is retired (2026-10), but an
     * older daemon of a colleague may still send that push, and the native entry points (Android
     * `MainActivity`, iOS via `handlePushOpenHandoff`) still hand the id over — so this keeps its signature
     * and turns the tap into the retired-feature link: it takes the same consent-gated path as any
     * OS-delivered link and ends in `IncomingLink.Retired(HANDOFF)` — a "this feature has been retired"
     * status, nothing opened, no pairing failure recorded. The id itself is never used.
     */
    fun openHandoff(handoffId: String) {
        if (handoffId.isNotEmpty()) DeepLink.handle(RETIRED_HANDOFF_LINK)
    }

    /** What an offer push tap is routed as — the parser recognises the host alone. */
    internal const val RETIRED_HANDOFF_LINK = "ccpocket://handoff"
}
