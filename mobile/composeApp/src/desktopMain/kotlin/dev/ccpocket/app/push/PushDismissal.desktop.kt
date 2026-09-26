package dev.ccpocket.app.push

/** Desktop is not a push target — there is no tray entry to clear. */
actual object PushDismissal {
    actual fun dismiss(sessionId: String) {}
}
