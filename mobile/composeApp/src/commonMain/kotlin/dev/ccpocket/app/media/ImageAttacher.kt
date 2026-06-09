package dev.ccpocket.app.media

import androidx.compose.runtime.Composable

/**
 * Returns a launcher lambda that opens the platform image picker. Picked images are resized/compressed
 * on-device (JPEG) and handed back as raw bytes via [onPicked]; the repository renders them as
 * thumbnails and base64-encodes them only at send time, budgeting against the relay's 256 KiB frame.
 * Desktop has no picker (returns a no-op).
 */
@Composable
expect fun rememberImageAttacher(onPicked: (List<ByteArray>) -> Unit): () -> Unit
