package dev.ccpocket.app.media

import androidx.compose.ui.graphics.ImageBitmap

/** Decode encoded image bytes (JPEG/PNG) into an [ImageBitmap] for display, or null if undecodable. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
