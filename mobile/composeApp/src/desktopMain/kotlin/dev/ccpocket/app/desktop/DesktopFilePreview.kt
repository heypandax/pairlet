package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.close
import dev.ccpocket.app.resources.dir_refresh
import dev.ccpocket.app.resources.file_preview
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.CopyChip
import dev.ccpocket.app.ui.FileTabBody
import dev.ccpocket.app.ui.LocalPathCwd
import dev.ccpocket.app.ui.LocalPathOpener
import dev.ccpocket.app.ui.TailPathText
import dev.ccpocket.protocol.FileContent
import java.awt.Cursor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Window-local document state, independent of the session's Changes/Diff selection. */
internal class DesktopFilePreviewState {
    var file by mutableStateOf<File?>(null)
        private set
    var revision by mutableStateOf(0)
        private set

    fun open(file: File) {
        this.file = file.absoluteFile.normalize()
        refresh() // clicking the same link again re-reads the file after an agent edits it
    }

    fun refresh() { revision++ }
    fun close() { file = null }
}

internal val LocalDesktopFilePreview = staticCompositionLocalOf<DesktopFilePreviewState?> { null }

/** Chat retains its composition, input and scroll position while the right-hand preview opens. */
@Composable
internal fun DesktopFilePreviewLayout(
    state: DesktopFilePreviewState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var fraction by remember { mutableStateOf(0.45f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val availableWidth = maxWidth
        val widthPx = with(density) { availableWidth.toPx() }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) { content() }
            state.file?.let { file ->
                Box(
                    Modifier.width(5.dp).fillMaxHeight().testTag("file-preview-divider")
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                        .pointerInput(widthPx) {
                            detectHorizontalDragGestures { change, dx ->
                                change.consume()
                                if (widthPx > 0) fraction = (fraction - dx / widthPx).coerceIn(0.3f, 0.65f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair)) }
                DesktopFilePreviewPane(state, file, Modifier.width(availableWidth * fraction).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun DesktopFilePreviewPane(state: DesktopFilePreviewState, file: File, modifier: Modifier) {
    // A new path/reload disposes the previous read and scroll state; its late result cannot replace
    // the new document. Blocking disk I/O always runs off the UI thread.
    key(file, state.revision) {
        val content by produceState<FileContent?>(null) {
            value = withContext(Dispatchers.IO) { readDesktopMarkdown(file) }
        }
        val opener = remember(file.parent, state) { DesktopPathOpener(file.parent, state::open) }
        CompositionLocalProvider(LocalPathOpener provides opener, LocalPathCwd provides file.parent) {
            Column(modifier.background(Tok.base).clipToBounds().testTag("desktop-file-preview")) {
                Row(
                    Modifier.fillMaxWidth().background(Tok.surface).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(
                            file.name, color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TailPathText(file.parent.orEmpty(), fontSize = 10.5.sp, color = Tok.muted)
                    }
                    IconButton(state::refresh, Modifier.testTag("file-preview-refresh")) {
                        Icon(Icons.Rounded.Refresh, stringResource(Res.string.dir_refresh), tint = Tok.tx2)
                    }
                    IconButton(state::close, Modifier.testTag("file-preview-close")) {
                        Icon(Icons.Rounded.Close, stringResource(Res.string.close), tint = Tok.tx2)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.file_preview), color = Tok.tx2, fontSize = 12.sp)
                    Box(Modifier.weight(1f))
                    content?.takeIf { it.ok }?.text?.let { CopyChip(it) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                FileTabBody(content, file.extension.lowercase(), dense = true, path = file.path, wrap = true)
            }
        }
    }
}

// Same bounded prefix as the normal file viewer; FileTabBody displays the truncation notice.
internal const val DESKTOP_MARKDOWN_MAX_BYTES = 256_000

internal fun readDesktopMarkdown(file: File): FileContent {
    val result = FileContent(workdir = file.parent.orEmpty(), sessionId = "", path = file.path)
    if (!file.isFile) return result.copy(ok = false, error = "file no longer exists")
    return runCatching {
        val total = file.length()
        val bytes = file.inputStream().use { it.readNBytes(DESKTOP_MARKDOWN_MAX_BYTES + 1) }
        val truncated = bytes.size > DESKTOP_MARKDOWN_MAX_BYTES
        // Back off an incomplete UTF-8 sequence when the byte cap falls inside a Chinese character.
        var end = minOf(bytes.size, DESKTOP_MARKDOWN_MAX_BYTES)
        if (truncated) while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        result.copy(
            text = String(bytes, 0, end, Charsets.UTF_8), truncated = truncated,
            totalBytes = maxOf(total, bytes.size.toLong()), mediaType = "text/markdown",
        )
    }.getOrElse { result.copy(ok = false, error = "unreadable: ${it.message}") }
}
