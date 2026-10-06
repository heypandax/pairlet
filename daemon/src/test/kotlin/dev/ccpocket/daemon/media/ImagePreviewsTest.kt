package dev.ccpocket.daemon.media

import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ImageRefs
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lean history's picture contract (SLOW-LINK-RESILIENCE §6): a preview is much smaller, names its full
 * version by content, and never costs the picture — whatever cannot be previewed travels exactly as before.
 */
class ImagePreviewsTest {

    @BeforeTest fun reset() = ImagePreviews.clearForTest()

    /** A picture that compresses like a real screenshot (gradient + hard-edged blocks), as [format] base64. */
    private fun picture(w: Int, h: Int, format: String = "jpeg", alpha: Int? = null, seed: Int = 7): ImageData {
        val img = BufferedImage(w, h, if (alpha != null) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val a = (alpha ?: 0) shl 24
        for (y in 0 until h) for (x in 0 until w) {
            val r = x * 255 / maxOf(1, w - 1)
            val g = y * 255 / maxOf(1, h - 1)
            val b = ((x + y + seed) / 4) % 256
            val block = if ((x / 40 + y / 24 + seed) % 7 == 0) 0x202830 else 0
            img.setRGB(x, y, a or (((r shl 16) or (g shl 8) or b) xor block))
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, format, bos)
        return ImageData("image/$format", Base64.getEncoder().encodeToString(bos.toByteArray()))
    }

    private fun decoded(image: ImageData): BufferedImage =
        ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.base64)))

    @Test
    fun a_wire_thumbnail_becomes_a_tile_sized_preview_that_names_its_full_version() {
        val full = picture(1024, 633)
        val out = ImagePreviews.shape("c1", listOf(full), ImagePreviews.TOOL_EDGE).single()
        assertEquals("image/jpeg", out.mediaType)
        assertEquals(ImageRefs.of(Base64.getDecoder().decode(full.base64)), out.ref)
        assertTrue(out.base64.length * 3 < full.base64.length, "preview ${out.base64.length} B vs full ${full.base64.length} B")
        val px = decoded(out)
        assertEquals(ImagePreviews.TOOL_EDGE, maxOf(px.width, px.height))
        assertEquals(1024.0 / 633, px.width.toDouble() / px.height, 0.02) // aspect kept: the tile reads it for its shape
        // the full version waits in memory, untouched, for exactly this conversation
        assertSame(full, ImagePreviews.full("c1", out.ref!!))
        assertNull(ImagePreviews.full("another-conversation", out.ref!!))
    }

    @Test
    fun a_prompt_attachment_gets_the_larger_preview() {
        val full = picture(1600, 1200)
        val tool = ImagePreviews.shape("c", listOf(full), ImagePreviews.TOOL_EDGE).single()
        val user = ImagePreviews.shape("c", listOf(full), ImagePreviews.USER_EDGE).single()
        assertEquals(ImagePreviews.USER_EDGE, maxOf(decoded(user).width, decoded(user).height))
        assertEquals(tool.ref, user.ref) // one picture, one identity, whatever size it is shown at
        assertTrue(user.base64.length > tool.base64.length)
    }

    @Test
    fun a_picture_that_is_already_small_travels_whole_and_without_a_ref() {
        val small = picture(120, 90)
        assertTrue(small.base64.length <= ImagePreviews.INLINE_MAX_BASE64)
        val images = listOf(small)
        assertSame(images, ImagePreviews.shape("c", images, ImagePreviews.TOOL_EDGE)) // nothing changed: the very same list
    }

    @Test
    fun what_cannot_be_previewed_is_sent_exactly_as_before() {
        val notBase64 = ImageData("image/png", "!".repeat(40_000))
        val notAnImage = ImageData("image/webp", Base64.getEncoder().encodeToString(ByteArray(40_000) { (it % 251).toByte() }))
        val alreadyAPreview = ImageData("image/jpeg", "A".repeat(40_000), ref = "x".repeat(ImageRefs.LENGTH))
        val images = listOf(notBase64, notAnImage, alreadyAPreview)
        assertSame(images, ImagePreviews.shape("c", images, ImagePreviews.TOOL_EDGE))
    }

    @Test
    fun order_and_count_survive_a_mixed_row() {
        val big = picture(1024, 768)
        val small = picture(100, 100)
        val out = ImagePreviews.shape("c", listOf(small, big, small), ImagePreviews.TOOL_EDGE)
        assertEquals(3, out.size)
        assertSame(small, out[0]); assertSame(small, out[2])
        assertNotNull(out[1].ref)
    }

    @Test
    fun a_second_shaping_reuses_the_preview_and_remembers_the_picture_for_the_new_conversation() {
        val full = picture(1024, 633)
        val first = ImagePreviews.shape("c1", listOf(full), ImagePreviews.TOOL_EDGE).single()
        val again = ImagePreviews.shape("c2", listOf(full.copy()), ImagePreviews.TOOL_EDGE).single()
        assertSame(first, again) // cached by content, not by instance
        assertNotNull(ImagePreviews.full("c2", again.ref!!))
    }

    @Test
    fun a_see_through_picture_is_flattened_for_its_preview() {
        val full = picture(1024, 512, format = "png", alpha = 0x40)
        val out = ImagePreviews.shape("c", listOf(full), ImagePreviews.TOOL_EDGE).single()
        assertEquals("image/jpeg", out.mediaType)
        assertFalse(decoded(out).colorModel.hasAlpha())
    }

    @Test
    fun recover_finds_the_picture_by_content_and_remembers_what_it_passed() {
        val a = picture(1024, 633, seed = 1)
        val b = picture(1024, 633, seed = 2)
        val refB = ImageRefs.of(Base64.getDecoder().decode(b.base64))
        assertNull(ImagePreviews.full("c", refB)) // memory is empty: a restart
        assertSame(b, ImagePreviews.recover("c", refB, index = 0, rows = listOf(listOf(a), listOf(b))))
        assertSame(b, ImagePreviews.full("c", refB))
        assertSame(a, ImagePreviews.full("c", ImageRefs.of(Base64.getDecoder().decode(a.base64))))
    }

    @Test
    fun recover_falls_back_to_position_only_when_one_row_carries_pictures() {
        val a = picture(800, 600, seed = 1)
        val b = picture(800, 600, seed = 2)
        val unknown = ImageRefs.of(byteArrayOf(9, 9, 9)) // a ref no picture here hashes to (the thumbnailer changed)
        assertSame(b, ImagePreviews.recover("c", unknown, index = 1, rows = listOf(emptyList(), listOf(a, b))))
        assertNull(ImagePreviews.recover("c", unknown, index = 5, rows = listOf(listOf(a, b))))
        assertNull(ImagePreviews.recover("c", unknown, index = 0, rows = listOf(listOf(a), listOf(b)))) // two rows: which one?
        assertNull(ImagePreviews.recover("c", unknown, index = 0, rows = emptyList()))
    }

    @Test
    fun a_palette_image_keeps_its_transparency() {
        // PNG-8 with a see-through colour: the transparency lives in the palette, there is no alpha band to scan
        fun indexed(transparentEntry: Boolean): ImageData {
            val r = byteArrayOf(0, 255.toByte(), 40); val g = byteArrayOf(0, 255.toByte(), 90); val b = byteArrayOf(0, 255.toByte(), 200.toByte())
            val a = byteArrayOf(if (transparentEntry) 0 else 255.toByte(), 255.toByte(), 255.toByte())
            val img = BufferedImage(200, 120, BufferedImage.TYPE_BYTE_INDEXED, java.awt.image.IndexColorModel(8, 3, r, g, b, a))
            for (y in 0 until 120) for (x in 0 until 200) img.raster.setSample(x, y, 0, if (x < 60) 0 else if ((x + y) % 9 == 0) 1 else 2)
            val bos = ByteArrayOutputStream(); ImageIO.write(img, "png", bos)
            return ImageData("image/png", Base64.getEncoder().encodeToString(bos.toByteArray()))
        }
        assertTrue(ImageThumbnail.hasTransparentPixel(decoded(indexed(transparentEntry = true))))
        assertEquals("image/png", ImageThumbnail.thumbnail(indexed(transparentEntry = true))!!.mediaType)
        assertFalse(ImageThumbnail.hasTransparentPixel(decoded(indexed(transparentEntry = false))))
    }

    @Test
    fun a_picture_not_worth_previewing_is_remembered_as_such() {
        // 40 KB of base64 that is not a picture: tried once, then answered from the cache — still sent whole
        val notAnImage = ImageData("image/webp", Base64.getEncoder().encodeToString(ByteArray(30_000) { (it % 251).toByte() }))
        val images = listOf(notAnImage)
        assertSame(images, ImagePreviews.shape("c", images, ImagePreviews.TOOL_EDGE))
        assertSame(images, ImagePreviews.shape("c", images, ImagePreviews.TOOL_EDGE))
        assertNull(ImagePreviews.full("c", ImageRefs.of(Base64.getDecoder().decode(notAnImage.base64))))
    }

    @Test
    fun the_wire_thumbnail_of_an_opaque_png_with_an_alpha_channel_is_a_jpeg() {
        // what a screenshot tool writes: ARGB, every pixel opaque. It used to stay PNG at about twice the bytes.
        val opaque = picture(1400, 900, format = "png", alpha = 0xFF)
        // small enough that the PNG fits the thumbnailer's own byte ceiling — a see-through picture that does
        // not is flattened to JPEG there for a different, older reason
        val seeThrough = picture(360, 240, format = "png", alpha = 0x80)
        assertEquals("image/jpeg", ImageThumbnail.thumbnail(opaque)!!.mediaType)
        assertEquals("image/png", ImageThumbnail.thumbnail(seeThrough)!!.mediaType)
    }
}
