package dev.ccpocket.app.push

import kotlin.concurrent.Volatile // commonMain: Kotlin/Native (iOS) doesn't resolve kotlin.jvm.Volatile

/**
 * The session the phone's chat is showing right now (issue #382), read by the platform push callbacks —
 * iOS `willPresent`, Android `onMessageReceived` — which only run while the App is in the foreground.
 * Written by the UI (App.kt) from the primary repository's open chat; null when no chat is open.
 * Switching to a session clears that session's delivered tray alerts via [PushDismissal] (issue #389).
 */
object ForegroundSession {
    @Volatile
    var sessionId: String? = null
        private set

    fun update(sessionId: String?) {
        val previous = this.sessionId
        this.sessionId = sessionId
        if (sessionId != null && sessionId != previous) PushDismissal.dismiss(sessionId)
    }

    /** Should a push that arrived while the App is in the foreground show its banner? See [shouldPresentForegroundPush]. */
    fun shouldPresent(pushSessionId: String?, pushKind: String?): Boolean =
        shouldPresentForegroundPush(sessionId, pushSessionId, pushKind)
}

/**
 * What the UI publishes to [ForegroundSession]: the open chat's [sessionKey], but only while a chat is actually
 * bound ([convoId] non-null — sessionKey survives backToBrowse as a draft key) AND the link is up ([connected]).
 * A chat page left on screen after the transport died receives nothing on the data plane, so its session's
 * pushes must still present — publishing null there keeps the foreground rule from swallowing them.
 */
fun foregroundSessionOf(sessionKey: String?, convoId: String?, connected: Boolean): String? =
    sessionKey?.takeIf { convoId != null && connected }

/**
 * Foreground banner rule (issue #382). Daemons push turn ends (complete / error / usage limit) and — since
 * 2026-10, #382 applied to asks — permission asks even while clients are online, so a phone already showing
 * that session would double-alert: the chat has the reply, or the ask card, inline. Hide ONLY when the push is
 * about the very session on screen; a push about another session, and any push without session routing (a
 * Handoff offer, a daemon notice), presents. [pushKind] no longer exempts a category: an approval for the
 * viewed session is on screen as its card, and now that the daemon pushes owner asks regardless of presence
 * the old "approval always presents" rule would have doubled every ask the user was already looking at. The
 * parameter stays so the platform callbacks (iOS `willPresent`, Android `onMessageReceived`) keep one
 * signature and a future category can opt back in without touching them.
 */
@Suppress("UNUSED_PARAMETER")
fun shouldPresentForegroundPush(viewingSessionId: String?, pushSessionId: String?, pushKind: String?): Boolean {
    if (viewingSessionId.isNullOrEmpty() || pushSessionId.isNullOrEmpty()) return true
    return viewingSessionId != pushSessionId
}
