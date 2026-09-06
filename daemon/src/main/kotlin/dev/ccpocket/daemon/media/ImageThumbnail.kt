package dev.ccpocket.daemon.media

import dev.ccpocket.protocol.ImageData
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import java.util.Base64

/**
 * Wire-safe thumbnails for the images a TOOL RESULT returned (issue #332) — a Playwright
 * `browser_take_screenshot`, or (measured on this machine's own transcripts, which is the only shape
 * that actually occurs there today) a `Read` of a PNG/JPEG file.
 *
 * Why a thumbnail and not the original: a retina screenshot arrives as 400–620 KB of base64 and the
 * relay drops any frame over 4 MiB outright ([dev.ccpocket.daemon.disk.ReplayBudget] explains the
 * whole budget). Four originals on one row is already past the entire 1.5 MB replay text budget, so
 * an untouched screenshot would either kill the connection or evict the conversation around itself.
 * A phone shows the picture at a few hundred CSS px; 1024 px on the long edge is generous for that
 * and for a pinch-zoom look, and the full-resolution file — when the tool wrote one inside the
 * workdir — stays reachable through the file browser / `ReadFile`.
 *
 * Every entry point is FAIL-SOFT by construction: an image the JDK decoder refuses, or one that will
 * not fit the byte ceiling even at the smallest retry edge, is DROPPED (with a log line) and the
 * event still ships. A tool result must never fail to reach the phone because its picture was
 * awkward — a card with no thumbnail is a far better outcome than a missing card.
 */
object ImageThumbnail {

    /** Longest-edge ceiling for the first attempt. Chosen for a phone screen + a 4x pinch-zoom. */
    const val MAX_EDGE = 1024

    /** Smallest edge the byte-ceiling retry will shrink to before giving up on the image entirely. */
    const val MIN_EDGE = 512

    /** Base64 ceiling per thumbnail. Four of these is ~800 KB — comfortably inside the 1.5 MB
     *  replay budget and far inside the relay's 4 MiB frame cap, even with JSON escaping on top. */
    const val MAX_BASE64_BYTES = 200_000

    /** Per-result tile ceiling, matching [dev.ccpocket.daemon.disk.ReplayBudget.MAX_IMAGES_PER_MESSAGE]:
     *  both renderers are built around a handful of tiles, so more costs frame budget nobody sees. */
    const val MAX_IMAGES = 4

    /**
     * Ceiling on the SUM of one result's thumbnails.
     *
     * The live `ToolEvent` path has no [dev.ccpocket.daemon.disk.ReplayBudget] behind it — that budget
     * only trims a replayed `ConvoHistory`, so a live frame is bounded by nothing but this. Four images
     * at [MAX_BASE64_BYTES] is 800 KB, already inside this cap; the cap exists so a future loosening of
     * either per-image limit cannot silently walk a live frame toward the relay's 4 MiB kill threshold.
     */
    const val MAX_TOTAL_BASE64_BYTES = 1_000_000

    private const val JPEG_QUALITY = 0.8f

    /**
     * Thumbnail up to [MAX_IMAGES] of [images], dropping any the decoder refuses, any that will not fit
     * [MAX_BASE64_BYTES], and any that would push the batch past [MAX_TOTAL_BASE64_BYTES]. Returns an
     * empty list when nothing survives — the caller's signal to emit no images at all rather than an
     * empty tile strip.
     *
     * The count cap is applied BEFORE decoding: a result carrying 30 screenshots must not cost 30 JPEG
     * encodes to then throw 26 away.
     */
    fun thumbnails(images: List<ImageData>): List<ImageData> {
        if (images.isEmpty()) return emptyList()
        val out = ArrayList<ImageData>(MAX_IMAGES)
        var total = 0L
        for (src in images.take(MAX_IMAGES)) {
            val thumb = thumbnail(src) ?: continue
            // Stop rather than skip-and-continue: the tiles are shown in order, and a strip that
            // silently omits the middle screenshot is more confusing than one that ends early.
            if (total + thumb.base64.length > MAX_TOTAL_BASE64_BYTES) break
            total += thumb.base64.length
            out += thumb
        }
        return out
    }

    /**
     * One image → a bounded thumbnail, or null when it cannot be produced.
     *
     * Cached on the source bytes because the replay path re-parses the WHOLE transcript on every slice
     * and page request (see [dev.ccpocket.daemon.disk.TranscriptReplay.parseRows]) — without this, a
     * reattach to a screenshot-heavy session would re-decode and re-encode the same PNGs on every
     * single history read.
     */
    fun thumbnail(image: ImageData): ImageData? {
        val key = cacheKey(image)
        synchronized(cache) { cache[key] }?.let { return it.value }
        val made = compute(image)
        synchronized(cache) {
            cache[key] = Cached(made)
            while (cache.size > CACHE_ENTRIES) cache.remove(cache.keys.first())
        }
        return made
    }

    private fun compute(image: ImageData): ImageData? {
        val raw = runCatching { Base64.getMimeDecoder().decode(image.base64) }.getOrNull()
            ?: return drop(image, "base64 did not decode")
        // ImageIO.read returns null (rather than throwing) for a format no reader claims — e.g. the
        // SVG/WEBP an MCP tool may hand back. Both outcomes are the same non-event here.
        val src = runCatching { ImageIO.read(ByteArrayInputStream(raw)) }.getOrNull()
            ?: return drop(image, "no ImageIO reader accepted ${image.mediaType} (${raw.size} B)")
        if (src.width <= 0 || src.height <= 0) return drop(image, "degenerate ${src.width}x${src.height}")

        // Alpha is preserved only when PNG also fits the ceiling: re-encoding a screenshot with a
        // transparent chrome as JPEG would black out (or white out) exactly the region that was
        // see-through, which reads as a rendering bug rather than a compression trade.
        val wantsAlpha = src.colorModel.hasAlpha()
        var edge = MAX_EDGE
        while (edge >= MIN_EDGE) {
            val scaled = scaleToEdge(src, edge, keepAlpha = wantsAlpha)
            if (wantsAlpha) {
                encodePng(scaled)?.let { png ->
                    if (base64Len(png.size) <= MAX_BASE64_BYTES) return ImageData("image/png", B64.encodeToString(png))
                }
                // PNG blew the budget — an opaque JPEG of the same pixels is the honest fallback, and
                // flattening onto the phone's own dark surface keeps a transparent screenshot readable
                // instead of compositing it onto JPEG's default black-or-garbage.
                encodeJpeg(scaleToEdge(src, edge, keepAlpha = false))?.let { jpg ->
                    if (base64Len(jpg.size) <= MAX_BASE64_BYTES) return ImageData("image/jpeg", B64.encodeToString(jpg))
                }
            } else {
                encodeJpeg(scaled)?.let { jpg ->
                    if (base64Len(jpg.size) <= MAX_BASE64_BYTES) return ImageData("image/jpeg", B64.encodeToString(jpg))
                }
            }
            edge /= 2
        }
        return drop(image, "still over ${MAX_BASE64_BYTES} B of base64 at ${MIN_EDGE}px")
    }

    /**
     * Scale so the LONGEST edge is at most [edge], preserving aspect ratio. An image already inside
     * the bound is still re-encoded (that is what bounds the bytes) but never upscaled — enlarging a
     * 200 px favicon to 1024 would cost bytes and add nothing to look at.
     */
    fun scaleToEdge(src: BufferedImage, edge: Int, keepAlpha: Boolean): BufferedImage {
        val longest = maxOf(src.width, src.height)
        val ratio = if (longest <= edge) 1.0 else edge.toDouble() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val type = if (keepAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val out = BufferedImage(w, h, type)
        val g = out.createGraphics()
        try {
            if (!keepAlpha) {
                // JPEG has no alpha: composite onto the app's own near-black surface so a screenshot
                // with transparency doesn't arrive with black-on-black or fringed text.
                g.color = java.awt.Color(0x0A, 0x0B, 0x0C)
                g.fillRect(0, 0, w, h)
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun encodeJpeg(img: BufferedImage): ByteArray? = runCatching {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                if (canWriteCompressed()) {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
            }
            writer.write(null, IIOImage(img, null, null), params)
        }
        writer.dispose()
        bos.toByteArray()
    }.getOrNull()

    private fun encodePng(img: BufferedImage): ByteArray? = runCatching {
        val bos = ByteArrayOutputStream()
        if (!ImageIO.write(img, "png", bos)) return null
        bos.toByteArray()
    }.getOrNull()

    /** The strict, padded, single-line encoder — the shape every consumer of [ImageData.base64]
     *  expects (a MIME encoder would inject CRLFs into the JSON string). Decoding is deliberately the
     *  LENIENT MIME decoder instead: a backend that wrapped its base64 must still be readable. */
    private val B64: Base64.Encoder = Base64.getEncoder()

    /** Base64 length of [n] bytes, without doing the encode: 4 chars per 3 bytes, padded. */
    private fun base64Len(n: Int): Int = ((n + 2) / 3) * 4

    private fun drop(image: ImageData, why: String): ImageData? {
        // stderr, like the rest of the daemon's diagnostics — a dropped picture is worth a line,
        // never an exception that would take the whole tool event down with it.
        System.err.println("[image-thumbnail] dropped ${image.mediaType} (${image.base64.length} B base64): $why")
        return null
    }

    // ---- decode cache: the replay path re-parses the whole transcript per request ----

    private const val CACHE_ENTRIES = 16
    private class Cached(val value: ImageData?)

    /** LinkedHashMap in insertion order = a cheap FIFO; the eviction loop above trims the oldest. */
    private val cache = LinkedHashMap<String, Cached>()

    /** Content identity without holding a second copy of a 600 KB string as a map key. */
    private fun cacheKey(image: ImageData): String =
        "${image.mediaType}|${image.base64.length}|${image.base64.hashCode()}"
}
