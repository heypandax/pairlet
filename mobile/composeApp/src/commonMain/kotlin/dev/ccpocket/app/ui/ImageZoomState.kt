package dev.ccpocket.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-zoom + pan state for one page of the full-screen [ImageViewer] (issue #332).
 *
 * Deliberately a plain holder rather than inline `remember { mutableStateOf(...) }` inside the
 * composable: the clamping rules below are the whole substance of the gesture (an unclamped pan is how
 * a zoomed screenshot slides off into empty space and can't be brought back), and they are worth
 * asserting directly instead of only through a gesture the test would have to synthesize.
 *
 * The viewer needs pictures now that a tool RESULT can return one — a browser screenshot is dense text
 * at phone size, so "tap to make it 1.25× bigger" (all the viewer had) was not a way to read it.
 */
class ImageZoomState(
    val minScale: Float = MIN_SCALE,
    val maxScale: Float = MAX_SCALE,
    val doubleTapScale: Float = DOUBLE_TAP_SCALE,
) {
    var scale by mutableFloatStateOf(minScale)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /**
     * At rest = fully zoomed out, which is the ONLY state where a vertical drag may dismiss the viewer.
     * Zoomed in, the same drag is a pan; without this distinction, panning up to read the bottom of a
     * tall screenshot would throw the viewer closed instead.
     */
    val atRest: Boolean get() = scale <= minScale + EPS

    /** A pinch or a drag. [zoomDelta] is multiplicative (1f = no pinch), [panX]/[panY] in pixels. */
    fun transform(zoomDelta: Float, panX: Float, panY: Float, bounds: ZoomBounds) {
        val next = (scale * zoomDelta).coerceIn(minScale, maxScale)
        // Pan is applied at the NEW scale so a pinch that also drags doesn't lurch: the clamp below
        // uses the post-zoom extents, which is what the finger is actually looking at.
        scale = next
        offsetX += panX
        offsetY += panY
        clamp(bounds)
    }

    /** Zoom around a point in the UNTRANSFORMED viewport (pinch centre, pointer, or button zoom).
     * Account for the effective scale at the limits, so further pinching at max zoom cannot drift.
     * Keeping gesture coordinates outside the scaled image also makes a 20px drag move it by 20px. */
    fun transformAt(
        zoomDelta: Float, panX: Float, panY: Float, bounds: ZoomBounds,
        focusX: Float = bounds.viewportWidth / 2f,
        focusY: Float = bounds.viewportHeight / 2f,
    ) {
        val ratio = (scale * zoomDelta).coerceIn(minScale, maxScale) / scale
        transform(
            zoomDelta,
            panX + (offsetX - (focusX - bounds.viewportWidth / 2f)) * (ratio - 1f),
            panY + (offsetY - (focusY - bounds.viewportHeight / 2f)) * (ratio - 1f),
            bounds,
        )
    }

    /** Double tap: zoom out to rest if zoomed at all, else jump to [doubleTapScale] centered. */
    fun toggleDoubleTap(bounds: ZoomBounds) {
        if (atRest) {
            scale = doubleTapScale.coerceIn(minScale, maxScale)
        } else {
            scale = minScale
            offsetX = 0f
            offsetY = 0f
        }
        clamp(bounds)
    }

    /** Leaving the page (pager swipe / viewer close) resets it — a page must not come back mid-zoom. */
    fun reset() {
        scale = minScale
        offsetX = 0f
        offsetY = 0f
    }

    /**
     * Keep the picture covering the viewport: the pan offset can never exceed the overhang the zoom
     * created. At rest (or on an axis where the scaled content is still narrower than the viewport)
     * the overhang is zero, so the offset is pinned to centre — which is also what snaps the image
     * back when a pinch-out ends.
     */
    fun clamp(bounds: ZoomBounds) {
        val maxX = max(0f, (bounds.contentWidth * scale - bounds.viewportWidth) / 2f)
        val maxY = max(0f, (bounds.contentHeight * scale - bounds.viewportHeight) / 2f)
        // `+ 0f` normalizes IEEE negative zero: coercing a negative pan into a ZERO-width range yields
        // -0.0f, which is numerically fine but makes the state read as "slightly off centre" to anything
        // comparing it (a test, a future animation gate). -0.0f + 0.0f is +0.0f.
        offsetX = offsetX.coerceIn(-maxX, maxX) + 0f
        offsetY = offsetY.coerceIn(-maxY, maxY) + 0f
    }

    /** How much of a vertical drag the pan can still absorb before it should read as a dismiss gesture. */
    fun canPanVertically(bounds: ZoomBounds): Boolean =
        (bounds.contentHeight * scale - bounds.viewportHeight) / 2f > EPS

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val EPS = 0.01f

        /** True when [a] and [b] are the same to within the state's own tolerance (test helper). */
        fun near(a: Float, b: Float): Boolean = abs(a - b) < EPS

        /** Fit [srcW]x[srcH] inside [viewW]x[viewH] preserving aspect — the base (scale 1) extents. */
        fun fit(srcW: Float, srcH: Float, viewW: Float, viewH: Float): ZoomBounds {
            if (srcW <= 0f || srcH <= 0f) return ZoomBounds(viewW, viewH, viewW, viewH)
            val k = min(viewW / srcW, viewH / srcH)
            return ZoomBounds(viewW, viewH, srcW * k, srcH * k)
        }
    }
}

/** Viewport and laid-out (scale == 1) content extents, in pixels — the input to every clamp. */
data class ZoomBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val contentWidth: Float,
    val contentHeight: Float,
)
