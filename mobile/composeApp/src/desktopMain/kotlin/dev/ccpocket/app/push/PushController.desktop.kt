package dev.ccpocket.app.push

/** Desktop is not a push target — registration is a no-op. */
actual object PushController {
    actual fun start(onToken: (PushToken) -> Unit) {}
}
