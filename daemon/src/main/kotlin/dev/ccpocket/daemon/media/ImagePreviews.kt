package dev.ccpocket.daemon.media

import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ImageRefs
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Lean history, part 2 (docs/design/SLOW-LINK-RESILIENCE.md §6): the pictures on a history row or a tool
 * result go out as tile-sized PREVIEWS, and the full version waits here until someone opens it.
 *
 * The full version is whatever the wire carried before this existed — [ImageThumbnail]'s 1024 px rendition of
 * a tool result, or a prompt attachment exactly as the transcript holds it. That is sized for the full-screen
 * viewer; the chat shows it in a strip 92 dp tall. Measured on the session that prompted this (2026-10-06):
 * two screenshots were 307 KB of a 394 KB window, and 29 KB as previews.
 *
 * A preview carries [ImageData.ref] = [ImageRefs.of] the full picture's bytes. The client sends it back in
 * `FetchImage`; [full] answers from memory, and [recover] re-reads it off the transcript when memory no longer
 * has it (a restart, an evicted entry). Nothing is written to disk: the transcript already is the durable copy.
 * An entry is only ever served to a request that names a conversation still open on this daemon (the registry
 * checks before it asks here), so what a closed conversation left behind is unreachable and simply ages out.
 *
 * FAIL-SOFT like [ImageThumbnail]: a picture this cannot decode, or cannot make meaningfully smaller, is sent
 * whole and without a ref, exactly as before. A preview is an optimisation and must never cost the picture.
 */
object ImagePreviews {

    /** Longest edge of a tool result's preview. The strip is 92 dp tall — 276 px on a 3x phone — and a wide
     *  browser capture is letterboxed to at most 2.6:1, so 384 px covers the tile at roughly native density. */
    const val TOOL_EDGE = 384

    /** Longest edge of a prompt attachment's preview: the bubble shows it up to 240 dp wide, so it gets more. */
    const val USER_EDGE = 480

    /** A picture whose base64 is already this small ships whole: a second rendition would save little and
     *  cost a round trip the first time someone opens it. */
    const val INLINE_MAX_BASE64 = 16_000

    /** A preview must be at most this fraction of the full picture's base64, or the full one is sent instead. */
    private const val WORTHWHILE = 0.7

    private const val TOOL_QUALITY = 0.6f
    private const val USER_QUALITY = 0.66f

    /** Full pictures kept for [full], by base64 size. ~48 MB is a few hundred screenshots; an evicted one is
     *  re-read from the transcript by [recover]. */
    private const val FULL_STORE_BYTES = 48L * 1024 * 1024

    /** Previews kept so a reopened session does not re-encode its pictures. They are small: ~16 MB is thousands. */
    private const val PREVIEW_CACHE_BYTES = 16L * 1024 * 1024

    private val fulls = ByteBoundedLru(FULL_STORE_BYTES)
    private val previews = ByteBoundedLru(PREVIEW_CACHE_BYTES)

    /**
     * [images] as they should travel to a connection that declared `supportsImagePreviews`: each one a preview
     * with its ref, or itself when a preview is not possible or not worth it. Order and count are unchanged.
     * Every picture that becomes a preview is remembered for [full] under [convoId].
     */
    fun shape(convoId: String, images: List<ImageData>, edge: Int): List<ImageData> {
        if (images.isEmpty()) return images
        var changed = false
        val out = images.map { image ->
            val preview = preview(convoId, image, edge)
            if (preview !== image) changed = true
            preview
        }
        return if (changed) out else images
    }

    /** The full picture [ref] stands for in [convoId], when it is still held in memory. */
    fun full(convoId: String, ref: String): ImageData? = fulls.get(storeKey(convoId, ref))

    /**
     * Find the full picture for [ref] among [rows]' images — the rows the transcript holds at the cursor the
     * client named — and remember every picture met on the way. Matches by content first; when no picture on
     * those rows hashes to [ref] any more (the thumbnailer changed between writing the preview and now), falls
     * back to position [index], but only when exactly one of the rows carries pictures, since the position was
     * counted on one row.
     */
    fun recover(convoId: String, ref: String, index: Int, rows: List<List<ImageData>>): ImageData? {
        var hit: ImageData? = null
        for (row in rows) for (image in row) {
            val bytes = decode(image) ?: continue
            val found = ImageRefs.of(bytes)
            fulls.put(storeKey(convoId, found), image)
            if (found == ref) hit = image
        }
        if (hit != null) return hit
        // position is a weaker proof than content: the client counted among the pictures IT could decode, so on
        // a row where it dropped one the index is off by that much. Reached only when no picture hashes to the
        // ref any more, where the alternative is no picture at all.
        val withPictures = rows.filter { it.isNotEmpty() }
        return withPictures.singleOrNull()?.getOrNull(index)
    }

    private fun preview(convoId: String, image: ImageData, edge: Int): ImageData {
        if (image.ref != null || image.base64.length <= INLINE_MAX_BASE64) return image
        val bytes = decode(image) ?: return image
        val ref = ImageRefs.of(bytes)
        val cacheKey = "$ref|$edge"
        previews.get(cacheKey)?.let { cached ->
            if (cached === SEND_WHOLE) return image
            fulls.put(storeKey(convoId, ref), image)
            return cached
        }
        val made = render(bytes, edge)
        if (made == null || made.length > image.base64.length * WORTHWHILE) {
            // remembered too: this runs on the sealing path for every connection and every reopen, and a
            // picture that cannot be previewed would otherwise be decoded and re-encoded each time to learn it
            previews.put(cacheKey, SEND_WHOLE)
            return image
        }
        val preview = ImageData("image/jpeg", made, ref = ref)
        previews.put(cacheKey, preview)
        fulls.put(storeKey(convoId, ref), image)
        return preview
    }

    private fun storeKey(convoId: String, ref: String) = "$convoId|$ref"

    /** Cache marker: "this picture was tried at this size and travels whole". Compared by identity. */
    private val SEND_WHOLE = ImageData("", "")

    /** The LENIENT decoder, like [ImageThumbnail]: a backend that wrapped its base64 must still be readable. */
    private fun decode(image: ImageData): ByteArray? = runCatching { Base64.getMimeDecoder().decode(image.base64) }.getOrNull()

    /** [bytes] as a JPEG whose longest edge is at most [edge], base64-encoded; null when the JDK cannot read it. */
    private fun render(bytes: ByteArray, edge: Int): String? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (src.width <= 0 || src.height <= 0) return null
        val jpeg = encodeJpeg(downscale(src, edge), if (edge >= USER_EDGE) USER_QUALITY else TOOL_QUALITY) ?: return null
        return Base64.getEncoder().encodeToString(jpeg)
    }

    /**
     * Downscale in halving steps, then one final step to the exact size. A single bilinear pass from 1024 px
     * (or a multi-thousand-pixel pasted screenshot) down to a few hundred samples only four source pixels per
     * output pixel and turns text into noise; halving keeps every source pixel in the average. Flattened onto
     * the app's own near-black surface, like [ImageThumbnail]'s JPEG path, so a see-through capture reads the
     * way it does in the chat.
     */
    internal fun downscale(src: BufferedImage, edge: Int): BufferedImage {
        val longest = maxOf(src.width, src.height)
        val ratio = if (longest <= edge) 1.0 else edge.toDouble() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        var current = flatten(src, src.width, src.height)
        while (current.width / 2 >= w && current.height / 2 >= h) {
            current = flatten(current, current.width / 2, current.height / 2)
        }
        return if (current.width == w && current.height == h) current else flatten(current, w, h)
    }

    private fun flatten(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = java.awt.Color(0x0A, 0x0B, 0x0C)
            g.fillRect(0, 0, w, h)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray? = runCatching {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                if (canWriteCompressed()) {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
            }
            writer.write(null, IIOImage(img, null, null), params)
        }
        writer.dispose()
        bos.toByteArray()
    }.getOrNull()

    /** Test seam: forget everything, so one test's pictures cannot answer another's request. */
    internal fun clearForTest() { fulls.clear(); previews.clear() }

    /** A string-keyed LRU bounded by the summed base64 length of its values. Access order; oldest goes first. */
    private class ByteBoundedLru(private val maxBytes: Long) {
        private val map = LinkedHashMap<String, ImageData>(64, 0.75f, true)
        private var bytes = 0L

        @Synchronized fun get(key: String): ImageData? = map[key]

        @Synchronized fun put(key: String, value: ImageData) {
            map.put(key, value)?.let { bytes -= it.base64.length }
            bytes += value.base64.length
            val oldest = map.entries.iterator()
            while (bytes > maxBytes && map.size > 1 && oldest.hasNext()) {
                val entry = oldest.next()
                bytes -= entry.value.base64.length
                oldest.remove()
            }
        }

        @Synchronized fun clear() { map.clear(); bytes = 0L }
    }
}
