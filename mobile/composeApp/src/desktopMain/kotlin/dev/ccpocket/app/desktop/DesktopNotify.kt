package dev.ccpocket.app.desktop

import java.awt.Taskbar

/**
 * Local "turn finished" signals for the desktop app (macOS notification + Dock badge). Fired from
 * Main.kt's repo.onTurnFinished hook when the window is unfocused — a focused window already shows
 * the ✓ TurnEnded marker, so notifying there would just be noise.
 */
object DesktopNotify {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    /** macOS banner via osascript — no notification-center entitlement needed for a dev/jpackage app.
     *  Other platforms: quietly nothing (the Dock/taskbar badge below still works where supported). */
    fun notify(title: String, body: String) {
        if (!mac) return
        runCatching {
            ProcessBuilder("osascript", "-e", "display notification \"${esc(body)}\" with title \"${esc(title)}\"").start()
        }
    }

    /** Dock badge with the count of finished-but-unseen turns; 0 clears it. */
    fun badge(count: Int) {
        runCatching {
            val tb = Taskbar.getTaskbar()
            if (tb.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                tb.setIconBadge(if (count > 0) count.toString() else null)
            }
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").take(180)
}
