package dev.ccpocket.app.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    observeImageDecode(bytes.size) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
