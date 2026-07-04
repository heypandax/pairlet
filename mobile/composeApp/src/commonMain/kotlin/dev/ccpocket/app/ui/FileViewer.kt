package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ChangedFile
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ════════════════════════════════════════════════════════════════════
//  Changed files (issue #36): what did this session touch → view one
// ════════════════════════════════════════════════════════════════════

/** Bottom sheet listing the files this session created/edited; tapping one opens the full-screen viewer. */
@Composable
fun ChangedFilesSheet(repo: PocketRepository, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.files_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            when {
                repo.changedFilesLoading.value -> Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.padding(4.dp), color = Tok.tx2, strokeWidth = 2.dp)
                }
                repo.changedFilesUnavailable.value -> Text(
                    stringResource(Res.string.files_unavailable), color = Tok.muted, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
                repo.changedFiles.isEmpty() -> Text(
                    stringResource(Res.string.files_empty), color = Tok.muted, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
                else -> LazyColumn(
                    Modifier.padding(top = 10.dp).heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(repo.changedFiles, key = { it.path }) { f ->
                        ChangedFileRow(f) { onOpen(f.path); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedFileRow(f: ChangedFile, onClick: () -> Unit) {
    val sep = if (f.path.contains('\\') && !f.path.contains('/')) '\\' else '/'
    val name = f.path.substringAfterLast(sep)
    val dir = f.path.substringBeforeLast(sep, "")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (dir.isNotEmpty()) TailPathText(dir, fontSize = 11.sp)
        }
        // op is a stable tool-ish vocabulary ("write"/"edit"/"delete"/"notebook") — shown as-is, mono
        Text(
            if (f.edits > 1) "${f.op} ×${f.edits}" else f.op,
            color = if (f.op == "delete") Tok.danger else Tok.muted,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
}

/**
 * Full-screen viewer for one changed file (replaces the chat screen like [TerminalScreen] does).
 * Markdown renders through [MarkdownText]; images decode from the daemon's base64; everything else
 * shows as selectable monospace text. Content state lives in the repo ([PocketRepository.viewedFile]),
 * so a reply landing after a reconnect still finds its way here.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun FileViewerScreen(repo: PocketRepository, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    val path = repo.viewedFilePath.value ?: return
    val content = repo.viewedFile.value
    val sep = if (path.contains('\\') && !path.contains('/')) '\\' else '/'
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast(sep), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TailPathText(path.substringBeforeLast(sep, ""), fontSize = 11.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tok.tx2, strokeWidth = 2.dp)
                }
                !content.ok -> Text(
                    content.error ?: "?", color = Tok.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                )
                content.base64 != null -> {
                    val bmp = remember(content.base64) {
                        runCatching { Base64.Default.decode(content.base64!!) }.getOrNull()?.let { dev.ccpocket.app.media.decodeImageBitmap(it) }
                    }
                    if (bmp != null) {
                        Image(
                            bmp, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    } else {
                        Text(
                            stringResource(Res.string.file_undecodable), color = Tok.muted, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                    if (content.truncated) Text(
                        stringResource(Res.string.file_truncated, (content.text?.length ?: 0) / 1024, (content.totalBytes / 1024).toInt()),
                        color = Tok.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val text = content.text ?: ""
                    if (path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")) {
                        MarkdownText(text, Tok.tx)
                    } else {
                        SelectionContainer {
                            Text(
                                text, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
                                softWrap = false,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
}
