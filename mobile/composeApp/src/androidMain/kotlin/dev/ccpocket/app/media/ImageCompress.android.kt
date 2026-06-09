package dev.ccpocket.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

actual fun compressImage(bytes: ByteArray, maxDim: Int, maxBytes: Int): ByteArray {
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val longest = maxOf(src.width, src.height)
    val scaled = if (longest > maxDim) {
        val s = maxDim.toFloat() / longest
        Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    } else {
        src
    }
    var q = 60
    var out = bytes
    while (true) {
        out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
        if (out.size <= maxBytes || q <= 25) break
        q -= 10
    }
    return out
}
