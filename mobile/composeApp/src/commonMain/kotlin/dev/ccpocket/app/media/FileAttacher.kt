package dev.ccpocket.app.media

import androidx.compose.runtime.Composable

/**
 * A file the user picked for upload (issue #90). Unlike photos (compressed + inlined into the
 * prompt frame), these bytes are chunk-streamed to the daemon and LANDED in the session's
 * workspace inbox, so [bytes] are the exact original file. [size] is the picker-reported size
 * (bytes.size once loaded; kept separate so an over-cap pick can be refused without loading).
 */
class PickedFile(
    val name: String,
    val size: Long,
    val bytes: ByteArray,
    val mediaType: String,
    // A platform playback/open handle kept for a picked VIDEO (issue #98): Android content-URI string,
    // iOS/desktop file URL. Lets a just-sent video play back on this device without re-fetching from the
    // computer; null for files with no stable local URI. Never uploaded.
    val localUri: String? = null,
)

/**
 * Platform document picker for generic files (PDF/CSV/code/Office…): returns a launcher.
 *  - Android: SAF `OpenMultipleDocuments` (no storage permission needed)
 *  - iOS: `UIDocumentPickerViewController` (asCopy — Files app / iCloud Drive)
 *  - Desktop: unused no-op — the desktop composer picks via its own AWT dialog + drag-and-drop
 *    (see desktop/FileInput.kt), mirroring how images already work there.
 */
@Composable
expect fun rememberFileAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit

/** Best-effort MIME from the file name — rides [dev.ccpocket.protocol.FileChunk.mediaType]. */
fun mediaTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "csv" -> "text/csv"
    "tsv" -> "text/tab-separated-values"
    "txt", "log" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "json", "ipynb" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "yaml", "yml" -> "application/yaml"
    "zip" -> "application/zip"
    "gz", "tgz" -> "application/gzip"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    else -> "application/octet-stream"
}
