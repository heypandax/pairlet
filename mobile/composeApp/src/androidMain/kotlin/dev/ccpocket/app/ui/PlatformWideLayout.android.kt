package dev.ccpocket.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Phone or large screen by the smallest width of the DISPLAY, against Android's `sw600dp` tablet line. A smallest
 * width is the same in portrait and landscape and no keyboard changes it, so a phone stays a phone on its side;
 * a foldable is whichever screen it is using right now.
 */
@Composable
internal actual fun platformLayoutDeviceClass(): LayoutDeviceClass {
    val context = LocalContext.current
    // a fold, an unfold, a move to another display or a window resize each arrive as a new configuration — the
    // size-only ones in place (the manifest's configChanges) — and each re-reads the display here
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        layoutDeviceClassForSmallestWidth(smallestDisplayWidthDp(context, configuration).dp)
    }
}

/**
 * The smallest side of the display the app is on, in that display's dp — never of the app's window, which split
 * screen or a freeform window can shape to 1000×400dp on a tablet. API 30+ takes the largest window the display
 * can give the app (a folded foldable reports its cover screen). API 26–29 take the display's real metrics: its
 * whole size in the current rotation, at its own density, in any window mode. [Configuration.smallestScreenWidthDp]
 * is the app window's smallest side, so it stands in only when no display can be read (no window manager or
 * display, or empty metrics) — and there a tablet in a short split window would read as a phone.
 */
private fun smallestDisplayWidthDp(context: Context, configuration: Configuration): Float {
    val display = runCatching {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            smallestSideDp(bounds.width(), bounds.height(), context.resources.displayMetrics.density)
        } else {
            @Suppress("DEPRECATION") // nothing newer below API 30, where the branch above takes over
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            smallestSideDp(metrics.widthPixels, metrics.heightPixels, metrics.density)
        }
    }.getOrNull()
    return display ?: configuration.smallestScreenWidthDp.toFloat()
}

/** Null when there is no area or density to measure: no display behind the context. */
private fun smallestSideDp(widthPx: Int, heightPx: Int, density: Float): Float? =
    if (widthPx > 0 && heightPx > 0 && density > 0f) minOf(widthPx, heightPx) / density else null
