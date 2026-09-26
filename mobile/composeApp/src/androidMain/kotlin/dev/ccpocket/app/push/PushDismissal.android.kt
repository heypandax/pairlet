package dev.ccpocket.app.push

import android.app.NotificationManager
import dev.ccpocket.app.voice.VoiceHost

/**
 * Android tray clearing (issue #389). Cancels by (tag, id = 0): FCM's system-tray display posts a notification
 * message with the payload's `android.notification.tag` and id 0, so the relay's tag — the session id for a
 * turn-end alert, `approval:<sid>` for an approval — is the only handle the app has on a notification it never
 * posted itself. [CcPocketMessagingService] posts foreground alerts under the same keys, so one cancel per key
 * covers both paths.
 */
actual object PushDismissal {
    actual fun dismiss(sessionId: String) {
        runCatching {
            val nm = VoiceHost.appContext.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(sessionId, 0)
            nm.cancel("approval:$sessionId", 0)
        } // no context/service yet — nothing we could have posted
    }
}
