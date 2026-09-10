package dev.ccpocket.app.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    observeImageDecode(bytes.size) { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
