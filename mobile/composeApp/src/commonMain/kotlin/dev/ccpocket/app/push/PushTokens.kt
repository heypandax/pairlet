package dev.ccpocket.app.push

import kotlinx.coroutines.flow.MutableStateFlow
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
 */
object PushTokens {
    private val _token = MutableStateFlow<PushToken?>(null)

    /** The latest platform token, or null until the OS hands one over (never on desktop). */
    val token: StateFlow<PushToken?> = _token

    private var started = false

    /**
     * Begin platform registration if it has not begun (on iOS this is what triggers the permission prompt,
     * which is why callers only do it once a link is actually up). Idempotent and safe from any thread that
     * matters here: every caller runs on the app's main dispatcher.
     */
    fun ensureStarted() {
        if (started) return
        started = true
        PushController.start { _token.value = it }
    }

    /** Test seam: publish a token without a platform push stack. */
    internal fun deliverForTest(token: PushToken?) { _token.value = token }
}
