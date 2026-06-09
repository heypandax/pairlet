package dev.ccpocket.app.media

/**
 * Decode [bytes], downscale so the longest side is ≤ [maxDim] px (at true 1× scale), and JPEG-encode
 * with a descending-quality search until the result is ≤ [maxBytes] (or quality bottoms out). Returns
 * the original bytes only if decoding fails. This is what keeps an attachment inside the relay's frame —
 * peekaboo's own iOS resize renders at the device @3x scale and can't guarantee output size.
 */
expect fun compressImage(bytes: ByteArray, maxDim: Int, maxBytes: Int): ByteArray
