package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * This device's [LayoutDeviceClass] (#378): what the platform knows about the hardware, never a guess from the
 * window's current height (an open keyboard shrinks that). Composable so Android can follow a fold, an unfold or
 * a move to another display as its configuration changes.
 */
@Composable
internal expect fun platformLayoutDeviceClass(): LayoutDeviceClass

/**
 * Android's own tablet line, the `sw600dp` resource qualifier. It lives here beside the pure mapping so the rule
 * is testable off-device; only the Android actual has a smallest width to feed it.
 */
internal val LARGE_SCREEN_MIN_SMALLEST_WIDTH = 600.dp

/** A smallest width — the same held upright or sideways — to the kind of device it belongs to. */
internal fun layoutDeviceClassForSmallestWidth(smallestWidth: Dp): LayoutDeviceClass =
    if (smallestWidth >= LARGE_SCREEN_MIN_SMALLEST_WIDTH) LayoutDeviceClass.LARGE_SCREEN else LayoutDeviceClass.PHONE

/**
 * The same mapping with the fold known (#378, second report). An unfolded foldable's inner screen clears the
 * `sw600dp` line — a Galaxy Z Fold's short side is about 690dp, a Pixel Fold's about 700dp — so by width alone it
 * was a tablet: its ~690dp portrait stayed one column, and the ~830dp it measures sideways split the very chat the
 * user had turned it for. The fold is what tells it from a tablet of the same size: a [foldable] is a handset on
 * either of its screens and stays [LayoutDeviceClass.PHONE] at any width. The flag is the platform's reading of the
 * hardware (the Android actual: a fold inside the app's window, or a hinge the device declares), never a size
 * heuristic, so a tablet keeps its two panes.
 */
internal fun layoutDeviceClassFor(smallestWidth: Dp, foldable: Boolean): LayoutDeviceClass =
    if (foldable) LayoutDeviceClass.PHONE else layoutDeviceClassForSmallestWidth(smallestWidth)
