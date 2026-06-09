package dev.ccpocket.app.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun compressImage(bytes: ByteArray, maxDim: Int, maxBytes: Int): ByteArray {
    if (bytes.isEmpty()) return bytes
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val img = UIImage.imageWithData(data) ?: return bytes
    val w = img.size.useContents { width }
    val h = img.size.useContents { height }
    val longest = maxOf(w, h)
    val scale = if (longest > maxDim) maxDim.toDouble() / longest else 1.0
    val tw = w * scale
    val th = h * scale
    // scale 1.0 = exact pixel size; peekaboo's 0.0 uses the device @3x scale and bloats the output
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(tw, th), false, 1.0)
    img.drawInRect(CGRectMake(0.0, 0.0, tw, th))
    val resized = UIGraphicsGetImageFromCurrentImageContext() ?: img
    UIGraphicsEndImageContext()
    var q = 0.6
    var jpeg = UIImageJPEGRepresentation(resized, q) ?: return bytes
    while (jpeg.length.toInt() > maxBytes && q > 0.25) {
        q -= 0.1
        jpeg = UIImageJPEGRepresentation(resized, q) ?: jpeg
    }
    val len = jpeg.length.toInt()
    return ByteArray(len).also { arr ->
        if (len > 0) arr.usePinned { memcpy(it.addressOf(0), jpeg.bytes, jpeg.length) }
    }
}
