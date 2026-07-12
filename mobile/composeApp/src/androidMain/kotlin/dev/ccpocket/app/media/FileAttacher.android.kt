package dev.ccpocket.app.media

import android.net.Uri
import android.provider.OpenableColumns
import dev.ccpocket.protocol.MAX_UPLOAD_BYTES
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SAF document picker (`OpenMultipleDocuments`) — no storage permission needed; works across
 * Downloads / Drive / third-party providers. Bytes + display name + size come off the content
 * resolver on Dispatchers.IO before the callback fires with ready [PickedFile]s.
 */
@Composable
actual fun rememberFileAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> readPicked(context, uri) }
            }
            if (files.isNotEmpty()) onPicked(files)
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

internal fun readPicked(context: android.content.Context, uri: Uri): PickedFile? = runCatching {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
        }
    }
    val mediaType = resolver.getType(uri) ?: mediaTypeFor(name)
    // keep the content-URI so a just-sent video can be replayed via ACTION_VIEW on this device (#98)
    val localUri = uri.toString()
    // over-cap pick: don't even load it — hand back an empty-bytes PickedFile whose size the
    // repository turns into an immediate "larger than 200 MB" failed chip
    if (size > MAX_UPLOAD_BYTES) return PickedFile(name, size, ByteArray(0), mediaType, localUri)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PickedFile(name, if (size >= 0) size else bytes.size.toLong(), bytes, mediaType, localUri)
}.getOrNull()
