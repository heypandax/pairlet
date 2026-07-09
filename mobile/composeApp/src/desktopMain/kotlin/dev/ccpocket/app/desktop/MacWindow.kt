package dev.ccpocket.app.desktop

import java.awt.Window
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * TRUE native macOS fullscreen (issue #94) for the undecorated Compose window — the green traffic light
 * enters a separate Space with the menu bar / Dock hidden (⌃⌘F / Esc to exit), distinct from the
 * double-click "zoom" (maximize) which merely fills the usable screen.
 *
 * We drive AppKit through `com.apple.eawt` (`FullScreenUtilities` + `Application.requestToggleFullScreen`).
 * That package only exists on the macOS JDK and is encapsulated since Java 9, so EVERY touch goes through
 * reflection guarded by an [isMac] check — Windows/Linux compile and run against this file as pure no-ops.
 * The AWT window behind a Compose `Window` is a `ComposeWindow` (a `JFrame`, hence a [java.awt.Window]),
 * which is what these APIs expect. Native fullscreen is more reliable for undecorated windows than
 * `WindowPlacement.Fullscreen` — the same pitfall class as the abandoned `WindowPlacement.Maximized`.
 */
object MacWindow {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Marks [window] as fullscreen-capable (idempotent) and subscribes to the OS-driven transitions so our
     * state tracks reality — the user can leave fullscreen by ⌃⌘F, Esc, Mission Control, or the green button
     * in the auto-revealed menu bar, none of which route through our own toggle. [onChanged] is invoked with
     * `true` once fully entered and `false` once fully exited. Returns an [AutoCloseable] that unsubscribes,
     * or `null` off macOS / when the APIs are unavailable (the caller then simply gets no callbacks).
     */
    fun installFullScreen(window: Window, onChanged: (Boolean) -> Unit): AutoCloseable? {
        if (!isMac) return null
        return try {
            val util = Class.forName("com.apple.eawt.FullScreenUtilities")
            util.getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, window, true)

            val listenerClass = Class.forName("com.apple.eawt.FullScreenListener")
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "windowEnteredFullScreen" -> { onChanged(true); null }
                    "windowExitedFullScreen" -> { onChanged(false); null }
                    "windowEnteringFullScreen", "windowExitingFullScreen" -> null // mid-animation; ignore
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MacFullScreenListener"
                    else -> null
                }
            }
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
            util.getMethod("addFullScreenListener", Window::class.java, listenerClass).invoke(null, window, listener)

            AutoCloseable {
                runCatching {
                    util.getMethod("removeFullScreenListener", Window::class.java, listenerClass)
                        .invoke(null, window, listener)
                }
            }
        } catch (_: Throwable) {
            null // older / non-Apple JDK: fullscreen just won't engage, everything else keeps working
        }
    }

    /**
     * Toggle native fullscreen for [window]. On success the actual state change arrives asynchronously via
     * the [installFullScreen] listener (don't flip local state optimistically here). Returns `false` off
     * macOS or if the request couldn't be dispatched, so the caller can fall back.
     */
    fun toggleFullScreen(window: Window): Boolean {
        if (!isMac) return false
        return try {
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            appClass.getMethod("requestToggleFullScreen", Window::class.java).invoke(app, window)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
