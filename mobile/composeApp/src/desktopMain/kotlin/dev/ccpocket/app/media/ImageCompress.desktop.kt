package dev.ccpocket.app.media

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual fun compressImage(bytes: ByteArray, maxDim: Int, maxBytes: Int): ByteArray {
    val img = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return bytes
    val longest = maxOf(img.width, img.height)
    val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
    val tw = (img.width * scale).toInt().coerceAtLeast(1)
    val th = (img.height * scale).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(tw, th)
    surface.canvas.drawImageRect(img, Rect.makeWH(tw.toFloat(), th.toFloat()))
    val snap = surface.makeImageSnapshot()
    var q = 60
    var out = snap.encodeToData(EncodedImageFormat.JPEG, q)?.bytes ?: bytes
    while (out.size > maxBytes && q > 25) {
        q -= 10
        out = snap.encodeToData(EncodedImageFormat.JPEG, q)?.bytes ?: out
    }
    return out
}
