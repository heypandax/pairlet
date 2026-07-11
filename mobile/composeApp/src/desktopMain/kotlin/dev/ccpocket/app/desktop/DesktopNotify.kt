package dev.ccpocket.app.desktop

import java.awt.Taskbar

/**
 * Local "turn finished" signals for the desktop app (macOS notification + Dock badge). Fired from
 * Main.kt's repo.onTurnFinished hook when the window is unfocused — a focused window already shows
 * the ✓ TurnEnded marker, so notifying there would just be noise.
 */
object DesktopNotify {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    /** Click→jump seam (issue #99): fires with the clicked banner's sessionId (null when unknown).
     *  Set by Main.kt; invoked on the AppKit main thread — hop to the EDT before touching UI. */
    var onActivate: ((sessionId: String?) -> Unit)?
        get() = MacNotifier.onActivate
        set(v) { MacNotifier.onActivate = v }

    /** macOS banner. Preferred channel is [MacNotifier] — NSUserNotificationCenter under the app's own
     *  bundle identity, so the banner shows the cc-pocket icon and clicking it comes back to us with
     *  [sessionId] (issue #99: the old osascript-only path attributed everything to Script Editor and
     *  clicks opened Script Editor). osascript stays as the fallback for environments where the native
     *  channel can't post (bundle-less JVMs, or a future macOS dropping the deprecated center).
     *  Other platforms: quietly nothing — there is no system notification channel there today (see the
     *  issue #99 report); only the badge below, where supported. */
    fun notify(title: String, body: String, sessionId: String? = null) {
        if (!mac) return
        if (MacNotifier.deliver(title, body, sessionId)) return
        runCatching {
            ProcessBuilder("osascript", "-e", osascriptSource(title, body)).start()
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

    /** The AppleScript source for the legacy fallback banner — pure, for tests. */
    internal fun osascriptSource(title: String, body: String) =
        "display notification \"${esc(body)}\" with title \"${esc(title)}\""

    internal fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").take(180)
}
