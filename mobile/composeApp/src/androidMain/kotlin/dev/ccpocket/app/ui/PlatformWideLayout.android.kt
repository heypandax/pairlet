package dev.ccpocket.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.catch

/**
 * Phone or large screen by the smallest width of the DISPLAY, against Android's `sw600dp` tablet line — unless the
 * device is a foldable, which is a phone on either of its screens (#378, second report). A smallest width is the
 * same in portrait and landscape and no keyboard changes it, so a phone stays a phone on its side; an unfolded
 * inner screen clears the tablet line by width, so the fold is read as well ([rememberFoldable]).
 */
@Composable
internal actual fun platformLayoutDeviceClass(): LayoutDeviceClass {
    val context = LocalContext.current
    // a fold, an unfold, a move to another display or a window resize each arrive as a new configuration — the
    // size-only ones in place (the manifest's configChanges) — and each re-reads the display here
    val configuration = LocalConfiguration.current
    val foldable by rememberFoldable(context)
    return remember(context, configuration, foldable) {
        layoutDeviceClassFor(smallestDisplayWidthDp(context, configuration).dp, foldable)
    }
}

/**
 * Whether this is a foldable, from two readings of the hardware, either one enough:
 *  - the hinge-angle sensor the device declares (`PackageManager.FEATURE_SENSOR_HINGE_ANGLE`, API 30+), while the
 *    app is on the built-in display: static, so it is known on the first frame and never flips through a fold or
 *    a rotation, and the mainstream foldables (Galaxy Z Fold, Pixel Fold, OPPO Find N…) declare it. On an external
 *    display — DeX, a monitor — the fold is not under the window, and the width rule applies as on any screen;
 *  - a [FoldingFeature] inside the app's window from Jetpack WindowManager: reported only while the inner screen is
 *    in use (a cover screen has no fold in its window), which covers a device that reports its fold but no hinge
 *    sensor. It arrives asynchronously, so the process's last reading seeds the next composition and a recreated
 *    Activity does not start from "no fold" for a frame.
 * Neither is a size heuristic: a tablet has no hinge and no fold, and keeps its two panes.
 */
@Composable
private fun rememberFoldable(context: Context): State<Boolean> {
    val hinge = remember(context) {
        context.packageManager.hasSystemFeature(HINGE_ANGLE_SENSOR_FEATURE) && onBuiltInDisplay(context)
    }
    val activity = remember(context) { context.findActivity() }
    return produceState(initialValue = hinge || lastFoldInWindow, hinge, activity) {
        // a hinge settles it, and without an Activity there is no window to read a fold from
        if (hinge || activity == null) return@produceState
        val layoutInfo = runCatching { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
            .getOrNull() ?: return@produceState
        layoutInfo
            // an OEM's window extensions misbehaving keeps the last reading; it must never take the root down
            .catch { }
            .collect { info ->
                val fold = info.displayFeatures.any { it is FoldingFeature }
                lastFoldInWindow = fold
                value = fold
            }
    }
}

/**
 * `PackageManager.FEATURE_SENSOR_HINGE_ANGLE` (API 30). Spelled out so API 26–29 simply read false for it, without
 * an InlinedApi lint hit on the constant.
 */
private const val HINGE_ANGLE_SENSOR_FEATURE = "android.hardware.sensor.hinge_angle"

/** The fold reading of the process so far, so a recreated Activity's first frame is the last frame's answer. */
private var lastFoldInWindow = false

/** Whether the window is on the device's own display ([Display.DEFAULT_DISPLAY]) rather than an external one. */
private fun onBuiltInDisplay(context: Context): Boolean = runCatching {
    val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.displayId
    } else {
        @Suppress("DEPRECATION") // nothing newer below API 30, where the branch above takes over
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.displayId
    }
    id == Display.DEFAULT_DISPLAY
}.getOrDefault(true)

/** The Activity behind a Compose [LocalContext] (a ContextThemeWrapper on it), or null off an Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
