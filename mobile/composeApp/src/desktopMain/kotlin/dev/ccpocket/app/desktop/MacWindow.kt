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
 *
 * IMPORTANT: `java.desktop` does NOT export `com.apple.eawt` to the unnamed (classpath) module, so a bare
 * reflective `invoke` throws `IllegalAccessException`. The macOS build/run therefore passes
 * `--add-exports java.desktop/com.apple.eawt=ALL-UNNAMED` (see composeApp/build.gradle.kts, gated to macOS
 * hosts). Without that flag every call here fails its try/catch and fullscreen silently won't engage —
 * everything else still works, so it degrades gracefully rather than crashing.
 */
object MacWindow {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /** Fullscreen transition phases, in AppKit callback order: the *ING pair fires at animation START —
     *  the moment to set the window's target frame so AppKit's own animation carries it there. */
    enum class FsPhase { ENTERING, ENTERED, EXITING, EXITED }

    /**
     * Marks [window] as fullscreen-capable (idempotent) and subscribes to the OS-driven transitions so our
     * state tracks reality — the user can leave fullscreen by ⌃⌘F, Esc, Mission Control, or the green button
     * in the auto-revealed menu bar, none of which route through our own toggle. [onPhase] receives all four
     * [FsPhase]s. Returns an [AutoCloseable] that unsubscribes, or `null` off macOS / when the APIs are
     * unavailable (the caller then simply gets no callbacks).
     */
    fun installFullScreen(window: Window, onPhase: (FsPhase) -> Unit): AutoCloseable? {
        if (!isMac) return null
        return try {
            val util = Class.forName("com.apple.eawt.FullScreenUtilities")
            util.getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, window, true)

            val listenerClass = Class.forName("com.apple.eawt.FullScreenListener")
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "windowEnteringFullScreen" -> { onPhase(FsPhase.ENTERING); null }
                    "windowEnteredFullScreen" -> { onPhase(FsPhase.ENTERED); null }
                    "windowExitingFullScreen" -> { onPhase(FsPhase.EXITING); null }
                    "windowExitedFullScreen" -> { onPhase(FsPhase.EXITED); null }
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MacFullScreenListener"
                    else -> null
                }
            }
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
            // NB: the real method names are ...ListenerTo / ...ListenerFrom (not add/removeFullScreenListener)
            util.getMethod("addFullScreenListenerTo", Window::class.java, listenerClass).invoke(null, window, listener)

            AutoCloseable {
                runCatching {
                    util.getMethod("removeFullScreenListenerFrom", Window::class.java, listenerClass)
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
