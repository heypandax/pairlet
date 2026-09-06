package dev.ccpocket.daemon.media

import dev.ccpocket.protocol.ImageData
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire-safety contract for issue #332's thumbnails. These are the guarantees the live ToolEvent
 * path and the replay path BOTH lean on: a screenshot never arrives at its original size, and an
 * image that can't be handled disappears quietly instead of taking the tool card down with it.
 */
class ImageThumbnailTest {

    // ---- fixtures ----

    /**
     * A PNG of [w]x[h] that compresses like a real SCREENSHOT: a smooth gradient with hard-edged
     * blocks and thin rules over it. Not a flat fill (which would satisfy every byte budget for the
     * wrong reason) and deliberately not pure noise either — noise is the pathological worst case for
     * JPEG, so a noise fixture would drive the byte-ceiling retry on every test and hide the normal
     * path, which is what actually ships.
     */
    private fun png(w: Int, h: Int, alpha: Boolean = false, seed: Int = 7): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val img = BufferedImage(w, h, type)
        val a = if (alpha) 0x80 shl 24 else 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 255 / maxOf(1, w - 1))
                val g = (y * 255 / maxOf(1, h - 1))
                val b = ((x + y + seed) / 4) % 256
                val block = if ((x / 40 + y / 24 + seed) % 7 == 0) 0x202830 else 0
                img.setRGB(x, y, a or (((r shl 16) or (g shl 8) or b) xor block))
            }
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    /** Pure noise — the JPEG worst case, used only where the point IS to press on the byte budget. */
    private fun noisePng(w: Int, h: Int, alpha: Boolean = false, seed: Int = 7): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val img = BufferedImage(w, h, type)
        val rnd = Random(seed)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = rnd.nextInt(0xFFFFFF)
                img.setRGB(x, y, if (alpha) (0x80 shl 24) or rgb else rgb)
            }
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private fun image(bytes: ByteArray, media: String = "image/png") =
        ImageData(media, Base64.getEncoder().encodeToString(bytes))

    private fun decode(d: ImageData): BufferedImage =
        ImageIO.read(ByteArrayInputStream(Base64.getMimeDecoder().decode(d.base64)))!!

    // ---- downscale ----

    @Test
    fun downscales_the_long_edge_to_the_cap_and_keeps_the_aspect_ratio() {
        val out = ImageThumbnail.thumbnail(image(png(2400, 1200)))
        assertNotNull(out, "a plain large PNG must always produce a thumbnail")
        val img = decode(out)
        assertEquals(ImageThumbnail.MAX_EDGE, maxOf(img.width, img.height))
        // 2:1 in, 2:1 out — a squashed screenshot is worse than no screenshot
        assertEquals(2.0, img.width.toDouble() / img.height, 0.02)
    }

    @Test
    fun downscales_a_tall_image_by_its_height() {
        val img = decode(assertNotNull(ImageThumbnail.thumbnail(image(png(600, 2000)))))
        assertEquals(ImageThumbnail.MAX_EDGE, img.height)
        assertTrue(img.width < img.height)
    }

    @Test
    fun never_upscales_a_small_image() {
        // a 64x64 favicon blown up to 1024 would cost bytes and add nothing to look at
        val img = decode(assertNotNull(ImageThumbnail.thumbnail(image(png(64, 64)))))
        assertEquals(64, img.width)
        assertEquals(64, img.height)
    }

    @Test
    fun a_screenshot_shrinks_well_under_the_per_image_budget() {
        val src = image(png(2400, 1400))
        val out = assertNotNull(ImageThumbnail.thumbnail(src))
        assertTrue(
            out.base64.length <= ImageThumbnail.MAX_BASE64_BYTES,
            "thumbnail was ${out.base64.length} B of base64, over the ${ImageThumbnail.MAX_BASE64_BYTES} cap",
        )
        assertTrue(out.base64.length < src.base64.length, "the thumbnail must be smaller than the original")
        assertEquals("image/jpeg", out.mediaType, "an opaque source re-encodes as JPEG")
    }

    @Test
    fun an_image_with_alpha_stays_png_when_it_fits() {
        // a small transparent capture must not be flattened onto an opaque background needlessly
        val out = assertNotNull(ImageThumbnail.thumbnail(image(png(120, 90, alpha = true))))
        assertEquals("image/png", out.mediaType)
    }

    @Test
    fun an_oversized_alpha_image_falls_back_to_jpeg_rather_than_being_dropped() {
        // noise at 2000px will not fit MAX_BASE64_BYTES as PNG; the honest outcome is an opaque JPEG,
        // not "no picture at all"
        val out = assertNotNull(ImageThumbnail.thumbnail(image(noisePng(2000, 2000, alpha = true, seed = 11))))
        assertEquals("image/jpeg", out.mediaType)
        assertTrue(out.base64.length <= ImageThumbnail.MAX_BASE64_BYTES)
    }

    @Test
    fun an_image_that_cannot_fit_at_the_full_edge_retries_smaller_rather_than_being_dropped() {
        // pure noise at 2400px will not fit MAX_BASE64_BYTES as JPEG at 1024px — the halving retry is
        // what keeps this a (smaller) picture instead of a dropped one
        val out = assertNotNull(ImageThumbnail.thumbnail(image(noisePng(2400, 2400, seed = 5))))
        val img = decode(out)
        assertTrue(maxOf(img.width, img.height) < ImageThumbnail.MAX_EDGE, "should have retried at a smaller edge")
        assertTrue(maxOf(img.width, img.height) >= ImageThumbnail.MIN_EDGE)
        assertTrue(out.base64.length <= ImageThumbnail.MAX_BASE64_BYTES)
    }

    // ---- fail-soft ----

    @Test
    fun undecodable_bytes_are_dropped_never_thrown() {
        assertNull(ImageThumbnail.thumbnail(image(byteArrayOf(1, 2, 3, 4, 5), media = "image/webp")))
    }

    @Test
    fun garbage_base64_is_dropped_never_thrown() {
        assertNull(ImageThumbnail.thumbnail(ImageData("image/png", "!!!! not base64 !!!!")))
    }

    @Test
    fun a_batch_keeps_the_good_images_and_drops_only_the_bad_one() {
        val out = ImageThumbnail.thumbnails(
            listOf(image(png(300, 200)), image(byteArrayOf(9, 9, 9)), image(png(400, 400))),
        )
        assertEquals(2, out.size, "one undecodable image must not cost the other two")
    }

    // ---- count / total budget ----

    @Test
    fun never_returns_more_than_the_per_result_cap() {
        val many = (1..9).map { image(png(300, 200, seed = it)) }
        assertEquals(ImageThumbnail.MAX_IMAGES, ImageThumbnail.thumbnails(many).size)
    }

    @Test
    fun the_batch_total_stays_inside_the_live_frame_budget() {
        // the live ToolEvent path has NO ReplayBudget behind it — this sum is the only thing bounding
        // the frame, so it is asserted directly rather than inferred from the per-image cap
        val out = ImageThumbnail.thumbnails((1..ImageThumbnail.MAX_IMAGES).map { image(png(2400, 1400, seed = it)) })
        assertTrue(out.isNotEmpty())
        val total = out.sumOf { it.base64.length }
        assertTrue(
            total <= ImageThumbnail.MAX_TOTAL_BASE64_BYTES,
            "batch was $total B of base64, over the ${ImageThumbnail.MAX_TOTAL_BASE64_BYTES} cap",
        )
    }

    @Test
    fun an_empty_input_is_an_empty_output() {
        assertEquals(emptyList(), ImageThumbnail.thumbnails(emptyList()))
    }

    @Test
    fun the_cache_returns_an_equal_result_for_the_same_bytes() {
        // the replay path re-parses the whole transcript per request, so a repeat must be free AND
        // identical — a thumbnail that differed run to run would churn the phone's decode cache
        val src = image(png(900, 700, seed = 3))
        val first = assertNotNull(ImageThumbnail.thumbnail(src))
        val second = assertNotNull(ImageThumbnail.thumbnail(src))
        assertEquals(first.mediaType, second.mediaType)
        assertEquals(first.base64, second.base64)
    }
}
