package dev.ccpocket.app.push

/** Desktop is not a push target — registration is a no-op.
 *
 *  [readAuthorization] answers UNKNOWN rather than DENIED on purpose: DENIED means "the user said no",
 *  which the coordinator treats as a dead end worth surfacing in Settings. Desktop simply has no such
 *  state, and calling it denied would paint a permanent error on a platform that never wanted push. */
actual object PushController {
    actual fun start(onToken: (PushToken) -> Unit) {}
    actual fun requestToken(prompt: Boolean, onFailed: (PushRegistrationFailure) -> Unit) {}
    actual fun readAuthorization(cb: (PushAuthorization) -> Unit) { cb(PushAuthorization.UNKNOWN) }
    actual fun openNotificationSettings() {}
}
