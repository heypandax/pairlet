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

/** A session to resume, delivered by tapping a task-complete push notification. */
data class SessionRoute(val workdir: String, val sessionId: String)

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
}
