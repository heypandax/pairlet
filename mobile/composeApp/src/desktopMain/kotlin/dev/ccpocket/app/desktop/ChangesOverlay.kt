package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.diff_stale_hint
import dev.ccpocket.app.resources.diff_stale_title
import dev.ccpocket.app.resources.file_open
import dev.ccpocket.app.resources.file_save_as
import dev.ccpocket.app.resources.files_empty
import dev.ccpocket.app.resources.files_title
import dev.ccpocket.app.share.exportBytesOf
import dev.ccpocket.app.share.previewFile
import dev.ccpocket.app.share.shareFile
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.app.ui.DiffFileToggle
import dev.ccpocket.app.ui.DiffPaneBody
import dev.ccpocket.app.ui.DiffStatText
import dev.ccpocket.app.ui.DiffTok
import dev.ccpocket.app.ui.FileTabBody
import dev.ccpocket.app.ui.FilesSummaryText
import dev.ccpocket.app.ui.StatusChip
import dev.ccpocket.app.ui.TailPathText
import dev.ccpocket.app.ui.fileNameOf
import dev.ccpocket.app.ui.isImagePath
import dev.ccpocket.app.ui.parentDirOf
import dev.ccpocket.app.ui.rememberCopied
import dev.ccpocket.app.ui.rememberDiffTab
import dev.ccpocket.app.ui.shownStats
import dev.ccpocket.protocol.ChangedFile
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Changes — the desktop two-pane diff browser (changed-files v2).
//  Left: the session's changed files; right: the selected file's diff
//  at desktop density (dual gutter). Same overlay language as ⌘K; the
//  panes and tab policy are DiffView.kt's shared pieces.
// ════════════════════════════════════════════════════════════════════

/** The chat-header "± N" entry pill (design: .pmpill). Hidden while the session has no changes. */
@Composable
fun ChangesPill(model: DesktopModel) {
    // keep the count fresh: on session switch, and again each time a turn finishes writing
    LaunchedEffect(model.selectedSessionId, model.streaming) {
        if (model.hasChat && !model.streaming) model.fetchChangedFiles()
    }
    val n = model.changedFiles.size
    if (n == 0) return
    Text(
        "± $n",
        color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(Tok.accent.copy(alpha = 0.12f))
            .border(1.dp, Tok.accent.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable { model.openChanges() }
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
fun ChangesOverlay(model: DesktopModel, onDismiss: () -> Unit) {
    val files = model.changedFiles
    val selectedPath = model.selectedChangedPath
    // first open (or the selection left the list): land on the first file
    LaunchedEffect(files) {
        if (files.isNotEmpty() && files.none { it.path == selectedPath }) model.selectChangedFile(files.first().path)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp).fillMaxHeight(0.88f)
            .shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val idx = files.indexOfFirst { it.path == model.selectedChangedPath }
                when (e.key) {
                    Key.DirectionDown -> { files.getOrNull(idx + 1)?.let { model.selectChangedFile(it.path) }; true }
                    Key.DirectionUp -> { files.getOrNull(idx - 1)?.let { model.selectChangedFile(it.path) }; true }
                    else -> false
                }
            },
    ) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.files_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (files.isNotEmpty()) FilesSummaryText(files, fontSize = 12.sp)
            Box(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Close, null, tint = Tok.tx2,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        when {
            model.changedFilesLoading && files.isEmpty() -> LoadingBody()
            files.isEmpty() && model.changedFilesStale -> Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                // no reply at all — the daemon predates the changed-files messages (mirrors mobile's banner)
                DiffEmptyState(
                    glyph = ">_",
                    title = stringResource(Res.string.diff_stale_title),
                    caption = stringResource(Res.string.diff_stale_hint),
                )
            }
            files.isEmpty() -> EmptyBody()
            else -> Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.width(280.dp).fillMaxHeight().background(Tok.raised)) {
                    items(files, key = { it.path }) { f ->
                        DesktopFileRow(f, selected = f.path == model.selectedChangedPath) { model.selectChangedFile(f.path) }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                Column(Modifier.weight(1f).fillMaxHeight().background(DiffTok.codeBg)) {
                    val file = files.firstOrNull { it.path == model.selectedChangedPath }
                    if (file != null) SelectedFilePane(model, file)
                }
            }
        }

        // footer: keyboard hints (only what's real: ↑↓ + esc; hunk headers collapse on click)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().height(32.dp).background(Tok.raised).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FootHint("↑↓", "switch file")
            FootHint("click @@", "collapse hunk")
            FootHint("esc", "close")
        }
    }
}

@Composable
private fun FootHint(keycap: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key(keycap)
        Text(label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
    }
}

@Composable
private fun LoadingBody() {
    Row(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
        Box(Modifier.width(280.dp))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Tok.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EmptyBody() {
    Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.files_empty), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 14.sp)
    }
}

/** One left-pane file row (design: .dfr): chip · name/dir · stats; selected = lift + accent edge. */
@Composable
private fun DesktopFileRow(f: ChangedFile, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .background(
                when {
                    selected -> Tok.surface
                    hovered -> Color.White.copy(alpha = 0.025f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hover).clickable(onClick = onClick),
    ) {
        if (selected) Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = 5.dp)
                .width(2.dp).clip(RoundedCornerShape(2.dp)).background(Tok.accent),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            StatusChip(f.op)
            Column(Modifier.weight(1f)) {
                Text(fileNameOf(f.path), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dir = parentDirOf(f.path)
                if (dir.isNotEmpty()) TailPathText(dir, fontSize = 10.5.sp, color = Tok.muted)
            }
            if (isImagePath(f.path)) {
                Text("img", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp)
            } else {
                DiffStatText(f.adds, f.dels, fontSize = 11.sp)
            }
        }
    }
}

/** The right pane for the selected file: sticky header (path · copy · chip · stats · toggle) + body. */
@Composable
private fun SelectedFilePane(model: DesktopModel, file: ChangedFile) {
    val diff = model.selectedDiff
    val isImage = isImagePath(file.path)
    val deleted = file.op == "delete"
    val ext = file.path.substringAfterLast('.', "").lowercase()
    var diffTab by rememberDiffTab(file.path, isImage, deleted, diff)

    Row(
        Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.weight(1f)) { TailPathText(file.path, fontSize = 12.sp, color = Tok.tx2) }
        CopyPathButton(file.path)
        // export what the viewer holds (issue #67): open with the system app / save a copy
        val content = model.selectedContent
        val exportable = remember(content) { exportBytesOf(content) }
        if (exportable != null) {
            HeaderIconButton(Icons.Rounded.OpenInNew, stringResource(Res.string.file_open)) {
                previewFile(fileNameOf(file.path), exportable, content?.mediaType)
            }
            HeaderIconButton(Icons.Rounded.Download, stringResource(Res.string.file_save_as)) {
                shareFile(fileNameOf(file.path), exportable, content?.mediaType)
            }
        }
        StatusChip(file.op)
        val (adds, dels) = shownStats(file, diff)
        if (!isImage && (adds != null || dels != null)) DiffStatText(adds, dels, fontSize = 12.sp)
        DiffFileToggle(
            diffSelected = diffTab,
            isImage = isImage,
            deleted = deleted,
            onPick = { diffTab = it },
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

    Box(Modifier.fillMaxSize()) {
        if (diffTab) DiffPaneBody(diff, ext = ext.ifEmpty { null }, dense = true)
        else FileTabBody(model.selectedContent, ext, dense = true)
    }
}

@Composable
private fun CopyPathButton(path: String) {
    val (copied, copy) = rememberCopied()
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable { copy(path) }.padding(3.dp),
    ) {
        if (copied) Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(13.dp))
        else Icon(Icons.Rounded.ContentCopy, "copy path", tint = Tok.muted, modifier = Modifier.size(13.dp))
    }
}

/** Quiet 13dp header action, matching [CopyPathButton]'s footprint. */
@Composable
private fun HeaderIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(3.dp)) {
        Icon(icon, label, tint = Tok.muted, modifier = Modifier.size(13.dp))
    }
}
