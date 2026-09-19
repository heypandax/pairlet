package dev.ccpocket.app.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one platform push token, shared by every link that has to register it.
 *
 * [PushController] holds a SINGLE callback (`start` replaces it), which was fine while exactly one
 * connection ever registered a token — the primary computer link. A Collaborator Link inbox
 * (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4) breaks that assumption: it is a separate relay account with
 * its own deviceId, and a targeted offer push can only reach the token registered UNDER THAT deviceId. Two
 * links calling `PushController.start` would quietly steal the callback from each other, and whichever lost
 * would keep a token that never refreshes.
 *
 * So the platform registration is started exactly once here, and its token is published as state every
 * interested link observes. The token itself is not a secret to the app — it is the same value the relay
 * already stores — and it is deliberately NOT persisted: APNs/FCM hand it back on every launch.
 *
 * [ensureStarted] no longer ASKS for a token, only wires the sinks. Asking is [requestToken], which
 * [PushRegistrar] owns: on iOS that call is what shows the permission dialog, so the moment it happens
 * is a product decision and must not be a side effect of any link happening to attach.
 */
object PushTokens {
    private val _token = MutableStateFlow<PushToken?>(null)
    // replay=0: a failure is an EVENT, and a late subscriber re-reading an old refusal would knock a
    // perfectly healthy registration back into "blocked". extraBufferCapacity so emitting never suspends
    // the platform callback thread.
    private val _failures = MutableSharedFlow<PushRegistrationFailure>(extraBufferCapacity = 8)

    /** The latest platform token, or null until the OS hands one over (never on desktop). */
    val token: StateFlow<PushToken?> = _token

    /** Why the platform refused / could not produce a token. Drives the coordinator's retry budget. */
    val failures: SharedFlow<PushRegistrationFailure> = _failures

    private var started = false

    /**
     * Wire the platform sinks once. Idempotent, side-effect free, and safe to call from every attach:
     * unlike the version this replaces it does NOT trigger registration, so it can no longer surprise
     * the user with a permission prompt at an arbitrary moment.
     */
    fun ensureStarted() {
        if (started) return
        started = true
        PushController.start { _token.value = it }
        PushController.onRegistrationFailed = { _failures.tryEmit(it) }
    }

    /** Ask the platform for a token; [prompt] may show the system permission dialog (iOS). */
    fun requestToken(prompt: Boolean) = PushController.requestToken(prompt)

    /** Read the OS authorization state (never prompts). */
    fun readAuthorization(cb: (PushAuthorization) -> Unit) = PushController.readAuthorization(cb)

    /** Send the user to this app's OS notification settings — the only cure for a DENIED state. */
    fun openNotificationSettings() = PushController.openNotificationSettings()

    /** Test seam: publish a token without a platform push stack. */
    internal fun deliverForTest(token: PushToken?) { _token.value = token }

    /** Test seam: publish a registration failure without a platform push stack. */
    internal fun failForTest(failure: PushRegistrationFailure) { _failures.tryEmit(failure) }
}
