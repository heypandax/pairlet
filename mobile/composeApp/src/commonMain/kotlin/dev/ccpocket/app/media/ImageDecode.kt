package dev.ccpocket.app.media

import androidx.compose.ui.graphics.ImageBitmap

/** Decode encoded image bytes (JPEG/PNG) into an [ImageBitmap] for display, or null if undecodable. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

internal fun observeImageDecode(size: Int, decode: () -> ImageBitmap?): ImageBitmap? {
    val result = runCatching(decode)
    if (result.getOrNull() == null) dev.ccpocket.observability.Diagnostics.report(
        dev.ccpocket.observability.ErrorPath.CONTENT, dev.ccpocket.observability.Stage.DECODE,
        dev.ccpocket.observability.ErrorCode.DECODE_FAILED, result.exceptionOrNull(),
        dev.ccpocket.observability.SafeMetrics(byteCount = size.toLong(),
            resultQuality = dev.ccpocket.observability.ResultQuality.FALLBACK),
        isError = false, // the viewer owns the operation outcome; decoding keeps its existing fallback
    )
    return result.getOrNull()
}
