package dev.ccpocket.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.preat.peekaboo.image.picker.ResizeOptions
import com.preat.peekaboo.image.picker.SelectionMode
import com.preat.peekaboo.image.picker.rememberImagePickerLauncher

@Composable
actual fun rememberImageAttacher(onPicked: (List<ByteArray>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val picker = rememberImagePickerLauncher(
        selectionMode = SelectionMode.Multiple(maxSelection = 4),
        scope = scope,
        // low threshold forces peekaboo to always bound the longest side to 1024 (it only resizes past the threshold)
        resizeOptions = ResizeOptions(width = 1024, height = 1024, resizeThresholdBytes = 1_024L, compressionQuality = 0.4),
        onResult = { bytesList -> if (bytesList.isNotEmpty()) onPicked(bytesList) },
    )
    return { picker.launch() }
}
