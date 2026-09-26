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
 * Foreground banner rule (issue #382). Daemons now push turn ends (complete / error / usage limit) even while
 * clients are online, so a phone already showing that session would double-alert. Hide ONLY when the push is
 * about the very session on screen and isn't an approval (`kind == "approval"` always presents: the card may
 * belong to a conversation this phone isn't driving). Turn-end flavors share `kind == null` on the wire, so an
 * error/limit push for the viewed session is hidden too — the chat shows it inline. No session routing
 * (Handoff offers carry only `hid`) → present.
 */
fun shouldPresentForegroundPush(viewingSessionId: String?, pushSessionId: String?, pushKind: String?): Boolean {
    if (pushKind == "approval") return true
    if (viewingSessionId.isNullOrEmpty() || pushSessionId.isNullOrEmpty()) return true
    return viewingSessionId != pushSessionId
}
