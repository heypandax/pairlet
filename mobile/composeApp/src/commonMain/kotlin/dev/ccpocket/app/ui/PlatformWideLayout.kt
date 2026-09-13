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
