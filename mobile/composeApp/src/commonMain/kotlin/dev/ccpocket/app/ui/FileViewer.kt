package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.share.exportBytesOf
import dev.ccpocket.app.share.shareFile
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.FileContent
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Changed files (issue #36 → v2): git-grade list + diff/file viewer,
//  per the design handoff in claude-design-handoff/changed-files-diff/.
//  The panes themselves (tab policy, diff body, file body) live in
//  DiffView.kt, shared with the desktop Changes browser.
// ════════════════════════════════════════════════════════════════════

/** Bottom sheet listing the files this session created/edited; tapping one opens the full-screen viewer. */
@Composable
fun ChangedFilesSheet(repo: PocketRepository, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(Res.string.files_title), color = Tok.tx, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f))
                if (repo.changedFiles.isNotEmpty()) FilesSummaryText(repo.changedFiles, fontSize = 12.sp)
            }
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
                else -> {
                    // rows without stats across the board = the daemon predates line-level diffs
                    val noStats = repo.changedFiles.none { it.adds != null || it.dels != null }
                    LazyColumn(
                        Modifier.padding(top = 10.dp).heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (noStats) item(key = "__stale") { StaleDaemonBanner() }
                        items(repo.changedFiles, key = { it.path }) { f ->
                            // Deliberately NOT dismissing: the viewer replaces the whole screen while it's up,
                            // and keeping the sheet's state alive means the viewer's back lands here again —
                            // browse the next file without re-digging through ⋯ → changed files (issue #53).
                            ChangedFileRow(f) { onOpen(f.path) }
                        }
                    }
                }
            }
            // files the session only GENERATED (Bash/scripts — issue #79) aren't in this list. Offer the
            // approval-gated export lane (issue #67 v2): type a project path; the viewer takes it from
            // there (the refusal it shows carries the "request export" entry).
            if (!repo.changedFilesLoading.value && !repo.changedFilesUnavailable.value) ExportOtherFileRow(onOpen)
        }
    }
}

/** The typed-path entry to the export lane: one subdued line, expanding to a path field. Submitting
 *  hands the path to the normal viewer flow — no new request kind here. */
@Composable
private fun ExportOtherFileRow(onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    if (!open) {
        Text(
            stringResource(Res.string.file_export_other),
            color = Tok.muted, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(top = 12.dp, bottom = 2.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            if (path.isEmpty()) Text(stringResource(Res.string.file_export_other_hint), color = Tok.muted, fontSize = 13.sp)
            BasicTextField(
                path, { path = it }, singleLine = true,
                textStyle = TextStyle(color = Tok.tx, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Tok.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton({ path.trim().takeIf { it.isNotEmpty() }?.let(onOpen) }) {
            Text(stringResource(Res.string.file_open), color = Tok.accent, fontSize = 13.sp)
        }
    }
}

/** Slim info banner: the daemon replied, but its rows carry no line stats (design: .banner.info). */
@Composable
private fun StaleDaemonBanner() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Tok.info.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ⓘ", color = Tok.info, fontSize = 13.sp)
        Text(stringResource(Res.string.files_stale_banner), color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ChangedFileRow(f: ChangedFile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        StatusChip(f.op)
        Column(Modifier.weight(1f)) {
            Text(fileNameOf(f.path), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val dir = parentDirOf(f.path)
            if (dir.isNotEmpty()) TailPathText(dir, fontSize = 11.sp, color = Tok.muted)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isImagePath(f.path)) {
                Text("img", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                DiffStatText(f.adds, f.dels, fontSize = 11.sp)
            }
            if (f.edits > 1) Text("×${f.edits}", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

// ── full-screen viewer: [ Diff | File ] ─────────────────────────────

/**
 * Full-screen viewer for one changed file (replaces the chat screen like [TerminalScreen] does).
 * Default tab is the line-level Diff (design handoff, Screen 2); the File tab keeps the original
 * full-content view. The panes and the tab policy are the shared pieces in DiffView.kt; content
 * state lives in the repo ([PocketRepository.viewedFile] + [PocketRepository.viewedFileDiff]), so
 * a reply landing after a reconnect still finds its way here.
 */
@Composable
fun FileViewerScreen(repo: PocketRepository, onExit: (() -> Unit)? = null, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    val path = repo.viewedFilePath.value ?: return
    val diff = repo.viewedFileDiff.value
    val ext = path.substringAfterLast('.', "").lowercase()

    val fileInfo = repo.changedFiles.firstOrNull { it.path == path }
    val isImage = isImagePath(path)
    val deleted = fileInfo?.op == "delete"
    var diffTab by rememberDiffTab(path, isImage, deleted, diff)
    val wrap = rememberWrapState()

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Column(Modifier.fillMaxWidth().background(Tok.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Column(Modifier.weight(1f)) {
                    Text(fileNameOf(path), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TailPathText(parentDirOf(path), fontSize = 11.sp)
                }
                // share/save whatever the viewer holds (issue #67) — text files ride the sheet too
                val content = repo.viewedFile.value
                val exportable = remember(content) { exportBytesOf(content) }
                if (exportable != null) TextButton({ shareFile(fileNameOf(path), exportable, content?.mediaType) }) {
                    Icon(Icons.Rounded.IosShare, stringResource(Res.string.file_share), tint = Tok.tx2, modifier = Modifier.size(18.dp))
                }
                // ← goes back UP one level (the changed-files list when that's where we came from);
                // ✕ skips the list and drops straight to the chat (issue #53's "一键返回").
                onExit?.let { TextButton(it) { Text("✕", color = Tok.tx2, fontSize = 16.sp) } }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiffFileToggle(
                    diffSelected = diffTab,
                    isImage = isImage,
                    deleted = deleted,
                    onPick = { diffTab = it },
                )
                if (wrapApplies(diffTab, diff, repo.viewedFile.value, ext, isImage)) {
                    val active = if (diffTab) wrap.diff else wrap.file
                    Box(Modifier.size(8.dp))
                    WrapToggle(on = active.value) { active.value = !active.value }
                }
                Box(Modifier.weight(1f))
                val (adds, dels) = shownStats(fileInfo, diff)
                if (!isImage && (adds != null || dels != null)) {
                    DiffStatText(adds, dels, fontSize = 12.sp)
                    Box(Modifier.size(9.dp))
                }
                fileInfo?.let { StatusChip(it.op) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (diffTab) DiffPaneBody(diff, ext = ext.ifEmpty { null }, dense = false, wrap = wrap.diff.value)
            else FileTabBody(
                repo.viewedFile.value, ext, path = path, wrap = wrap.file.value,
                // a path the changed-set refused can still leave through the owner's approval gate
                // (issue #67 v2 / #79) — dock the request entry / waiting row under the refusal text
                exportSlot = when {
                    repo.exportWaiting.value -> ({ ExportWaitingRow() })
                    exportRequestable(repo.viewedFile.value) -> ({ ExportRequestButton { repo.requestExport() } })
                    else -> null
                },
            )
        }
    }
}

/** FileContent failures the approval-gated export can still answer (issue #67 v2 / #79): the changed-set
 *  refusal (the #79 gap itself) and a not-approved outcome (retryable). Matched on the daemon's own error
 *  strings — the same in-band signal the too-large card already keys on. */
private fun exportRequestable(content: FileContent?): Boolean {
    val err = content?.takeIf { !it.ok }?.error ?: return false
    return "not a file this session changed" in err || "not approved" in err
}

@Composable
private fun ExportRequestButton(onClick: () -> Unit) {
    TextButton(onClick, Modifier.padding(top = 6.dp)) {
        Text(stringResource(Res.string.file_export_request), color = Tok.accent, fontSize = 13.sp)
    }
}

@Composable
private fun ExportWaitingRow() {
    Row(
        Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Tok.tx2, strokeWidth = 2.dp)
        Text(stringResource(Res.string.file_export_waiting), color = Tok.muted, fontSize = 12.sp)
    }
}
