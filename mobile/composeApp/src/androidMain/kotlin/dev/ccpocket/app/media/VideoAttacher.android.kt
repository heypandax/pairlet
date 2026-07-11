package dev.ccpocket.app.media

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF document picker filtered to the video MIME wildcard — same read path as [rememberFileAttacher],
 *  so a picked video carries bytes + name + size + a replayable content-URI, then chunk-uploads like
 *  any file. */
@Composable
actual fun rememberVideoAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> readPicked(context, uri) } }
            if (files.isNotEmpty()) onPicked(files)
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("video/*")) } }
}

/** Open a just-picked video's content-URI in the system video player (issue #98). */
@Composable
actual fun rememberLocalVideoOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { uri ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uri), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Unit
        }
    }
}
