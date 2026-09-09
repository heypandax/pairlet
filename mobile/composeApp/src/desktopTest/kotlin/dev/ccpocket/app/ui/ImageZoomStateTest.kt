package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pinch-zoom contract behind the full-screen viewer (issue #332).
 *
 * The viewer's zoom was a 1.25x tap toggle, which is not a way to read a browser screenshot on a
 * phone. What makes the replacement usable is entirely in the CLAMPS: an unclamped pan is how a
 * zoomed picture slides into empty space and can't be brought back, and a dismiss gesture that stays
 * armed while zoomed is how panning down throws the viewer closed under your finger. Both are
 * asserted here directly rather than through a synthesized gesture.
 */
class ImageZoomStateTest {

    /** A 1000x800 viewport showing a 1000x800 picture — offsets are then symmetric and easy to reason about. */
    private val bounds = ZoomBounds(viewportWidth = 1000f, viewportHeight = 800f, contentWidth = 1000f, contentHeight = 800f)

    @Test
    fun starts_at_rest_and_centered() {
        val s = ImageZoomState()
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
        assertTrue(s.atRest, "a fresh page must allow swipe-down dismissal")
    }

    @Test
    fun a_pinch_scales_within_the_clamp() {
        val s = ImageZoomState()
        s.transform(zoomDelta = 2f, panX = 0f, panY = 0f, bounds = bounds)
        assertEquals(2f, s.scale)
        assertFalse(s.atRest, "zoomed in — the dismiss gesture must now be disarmed")
    }

    @Test
    fun scale_never_exceeds_the_maximum() {
        val s = ImageZoomState()
        repeat(10) { s.transform(zoomDelta = 3f, panX = 0f, panY = 0f, bounds = bounds) }
        assertEquals(ImageZoomState.MAX_SCALE, s.scale)
    }

    @Test
    fun scale_never_drops_below_one() {
        val s = ImageZoomState()
        repeat(10) { s.transform(zoomDelta = 0.3f, panX = 0f, panY = 0f, bounds = bounds) }
        assertEquals(ImageZoomState.MIN_SCALE, s.scale)
        assertTrue(s.atRest)
    }

    @Test
    fun at_rest_the_picture_cannot_be_panned_off_center() {
        // otherwise a stray one-finger drag would slide a fitted image sideways for no reason
        val s = ImageZoomState()
        s.transform(zoomDelta = 1f, panX = 400f, panY = -300f, bounds = bounds)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
    }

    @Test
    fun a_zoomed_pan_is_clamped_to_the_overhang_the_zoom_created() {
        val s = ImageZoomState()
        s.transform(zoomDelta = 2f, panX = 0f, panY = 0f, bounds = bounds)
        // at 2x on a 1000-wide picture in a 1000-wide viewport the overhang per side is
        // (1000*2 - 1000)/2 = 500; a 9999px drag must stop exactly there, not sail past it
        s.transform(zoomDelta = 1f, panX = 9999f, panY = 9999f, bounds = bounds)
        assertEquals(500f, s.offsetX)
        assertEquals(400f, s.offsetY) // (800*2 - 800)/2
    }

    @Test
    fun panning_the_other_way_is_clamped_symmetrically() {
        val s = ImageZoomState()
        s.transform(zoomDelta = 2f, panX = -9999f, panY = -9999f, bounds = bounds)
        assertEquals(-500f, s.offsetX)
        assertEquals(-400f, s.offsetY)
    }

    @Test
    fun zooming_back_out_snaps_the_offset_back_to_center() {
        // the reported shape of "it got stuck off-screen": pan while zoomed, then pinch out, and the
        // offset that was legal at 4x is nonsense at 1x
        val s = ImageZoomState()
        s.transform(zoomDelta = 4f, panX = 2000f, panY = 2000f, bounds = bounds)
        assertTrue(s.offsetX > 0f)
        s.transform(zoomDelta = 0.25f, panX = 0f, panY = 0f, bounds = bounds)
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
    }

    @Test
    fun double_tap_toggles_between_rest_and_the_zoomed_step() {
        val s = ImageZoomState()
        s.toggleDoubleTap(bounds)
        assertEquals(ImageZoomState.DOUBLE_TAP_SCALE, s.scale)
        assertFalse(s.atRest)
        s.toggleDoubleTap(bounds)
        assertEquals(1f, s.scale)
        assertTrue(s.atRest)
    }

    @Test
    fun double_tap_while_partially_zoomed_returns_to_rest_rather_than_zooming_further() {
        val s = ImageZoomState()
        s.transform(zoomDelta = 1.6f, panX = 100f, panY = 0f, bounds = bounds)
        s.toggleDoubleTap(bounds)
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
    }

    @Test
    fun reset_clears_everything_for_the_next_page() {
        // a page that scrolls away and comes back still parked at 4x reads as a broken viewer
        val s = ImageZoomState()
        s.transform(zoomDelta = 3f, panX = 300f, panY = 200f, bounds = bounds)
        s.reset()
        assertEquals(1f, s.scale)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
        assertTrue(s.atRest)
    }

    @Test
    fun a_picture_narrower_than_the_viewport_still_cannot_pan_on_that_axis() {
        // a tall screenshot letterboxed side-to-side: vertical panning is legal, horizontal is not
        val tall = ZoomBounds(viewportWidth = 1000f, viewportHeight = 800f, contentWidth = 300f, contentHeight = 800f)
        val s = ImageZoomState()
        s.transform(zoomDelta = 2f, panX = 500f, panY = 500f, bounds = tall)
        assertEquals(0f, s.offsetX, "600px wide at 2x still fits the 1000px viewport — nothing to pan")
        assertEquals(400f, s.offsetY)
    }

    @Test
    fun canPanVertically_reports_whether_a_drag_has_anywhere_to_go() {
        val s = ImageZoomState()
        assertFalse(s.canPanVertically(bounds), "at rest a vertical drag is a dismiss, not a pan")
        s.transform(zoomDelta = 2f, panX = 0f, panY = 0f, bounds = bounds)
        assertTrue(s.canPanVertically(bounds))
    }

    @Test
    fun fit_computes_the_letterboxed_content_extents() {
        // a 2:1 picture in a square viewport is width-limited
        val b = ImageZoomState.fit(srcW = 2000f, srcH = 1000f, viewW = 800f, viewH = 800f)
        assertEquals(800f, b.contentWidth)
        assertEquals(400f, b.contentHeight)
    }

    @Test
    fun fit_of_a_degenerate_source_falls_back_to_the_viewport() {
        val b = ImageZoomState.fit(srcW = 0f, srcH = 0f, viewW = 640f, viewH = 480f)
        assertEquals(640f, b.contentWidth)
        assertEquals(480f, b.contentHeight)
    }

    @Test
    fun focused_zoom_keeps_the_image_point_under_the_finger_while_panning() {
        val s = ImageZoomState()
        s.transformAt(2f, 0f, 0f, bounds, focusX = 650f, focusY = 300f)
        assertEquals(-150f, s.offsetX)
        assertEquals(100f, s.offsetY)
        s.transformAt(1.25f, 25f, -35f, bounds, focusX = 650f, focusY = 300f)
        // The original point (150,-100) relative to the image centre stays at focus + pan.
        assertEquals(675f, 500f + s.offsetX + 150f * s.scale)
        assertEquals(265f, 400f + s.offsetY - 100f * s.scale)
    }

    @Test
    fun zooming_at_the_scale_limit_does_not_move_the_image() {
        val s = ImageZoomState()
        s.transformAt(99f, 0f, 0f, bounds, focusX = 100f, focusY = 100f)
        val x = s.offsetX
        val y = s.offsetY
        s.transformAt(99f, 0f, 0f, bounds, focusX = 100f, focusY = 100f)
        assertEquals(x, s.offsetX)
        assertEquals(y, s.offsetY)
        s.transformAt(0.001f, 0f, 0f, bounds, focusX = 100f, focusY = 100f)
        assertEquals(0f, s.offsetX)
        assertEquals(0f, s.offsetY)
        assertTrue(s.atRest)
    }
}
