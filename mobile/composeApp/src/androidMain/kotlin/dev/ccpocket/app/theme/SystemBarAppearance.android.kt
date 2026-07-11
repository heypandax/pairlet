package dev.ccpocket.app.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Drives the status/navigation-bar icon color from the resolved theme (issue #117). The bars are already
 * transparent (MainActivity.enableEdgeToEdge), so we only flip the foreground polarity: DARK theme wants
 * light icons (isAppearanceLight* = false), LIGHT theme wants dark ones (= true). A SideEffect re-applies it
 * after every recomposition, so a live LIGHT/DARK/SYSTEM change re-tints the bars without a relaunch.
 */
@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return // @Preview has no real window
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}

/** The Compose view's context is usually the Activity, but unwrap any ContextWrapper to be safe (a bare
 *  cast returns null under some test/embedding contexts, which would silently skip the tint). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
