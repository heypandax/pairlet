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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.close
import dev.ccpocket.app.resources.copy_path
import dev.ccpocket.app.resources.diff_stale_hint
import dev.ccpocket.app.resources.diff_stale_title
import dev.ccpocket.app.resources.file_open
import dev.ccpocket.app.resources.file_save_as
import dev.ccpocket.app.resources.files_dir_empty
import dev.ccpocket.app.resources.files_dir_error
import dev.ccpocket.app.resources.files_empty
import dev.ccpocket.app.resources.files_show_hidden
import dev.ccpocket.app.resources.files_title
import dev.ccpocket.app.resources.files_truncated
import dev.ccpocket.app.resources.files_view_all
import dev.ccpocket.app.resources.files_view_changes
import dev.ccpocket.app.resources.key_close
import dev.ccpocket.app.resources.key_collapse_hunk
import dev.ccpocket.app.resources.key_switch_file
import dev.ccpocket.app.share.exportBytesOf
import dev.ccpocket.app.share.previewFile
import dev.ccpocket.app.share.shareFile
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.app.ui.DiffFileToggle
import dev.ccpocket.app.ui.DiffPaneBody
import dev.ccpocket.app.ui.DiffStatText
import dev.ccpocket.app.ui.DiffTok
import dev.ccpocket.app.ui.FileRowKind
import dev.ccpocket.app.ui.FileTabBody
import dev.ccpocket.app.ui.FileTreeRow
import dev.ccpocket.app.ui.FilesSummaryText
import dev.ccpocket.app.ui.FilesView
import dev.ccpocket.app.ui.StatusChip
import dev.ccpocket.app.ui.StatusDot
import dev.ccpocket.app.ui.SubtreeCountBadge
import dev.ccpocket.app.ui.TailPathText
import dev.ccpocket.app.ui.WrapToggle
import dev.ccpocket.app.ui.changedIndexOf
import dev.ccpocket.app.ui.diffUnavailable
import dev.ccpocket.app.ui.fileNameOf
import dev.ccpocket.app.ui.flattenFileTree
import dev.ccpocket.app.ui.isImagePath
import dev.ccpocket.app.ui.joinNative
import dev.ccpocket.app.ui.parentDirOf
import dev.ccpocket.app.ui.rememberCopied
import dev.ccpocket.app.ui.rememberDiffTab
import dev.ccpocket.app.ui.rememberWrapState
import dev.ccpocket.app.ui.shownStats
import dev.ccpocket.app.ui.subtreeChangeCount
import dev.ccpocket.app.ui.wrapApplies
import dev.ccpocket.protocol.ChangedFile
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Changes — the desktop two-pane diff browser (changed-files v2).
//  Left: the session's changed files; right: the selected file's diff
//  at desktop density (dual gutter). Same overlay language as ⌘K; the
//  panes and tab policy are DiffView.kt's shared pieces.
// ════════════════════════════════════════════════════════════════════

/**
 * The chat-header files entry (design: .pmpill). **两态，永不消失**（files-browser-dual-view）：
 * 有改动 = terracotta 的 `± N`；零改动退化成一个安静的文件图标 chip，几何/描边与旁边的 `>_` 终端
 * chip 一致——文件面现在还管「浏览整个 workdir」，入口在没改动时消失就等于把整块能力藏了。
 */
@Composable
fun ChangesPill(model: DesktopModel) {
    // keep the count fresh: on session switch, and again each time a turn finishes writing
    LaunchedEffect(model.selectedSessionId, model.streaming) {
        if (model.hasChat && !model.streaming) model.fetchChangedFiles()
    }
    val n = model.changedFiles.size
    if (n == 0) {
        Box(
            Modifier.height(20.dp).width(26.dp).clip(RoundedCornerShape(999.dp))
                .border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                .clickable { model.openChanges() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.InsertDriveFile, stringResource(Res.string.files_title),
                tint = Tok.tx2, modifier = Modifier.size(12.dp),
            )
        }
        return
    }
    Text(
        "± $n",
        color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        style = tightCenter(11.sp),
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
    // 视角与树的展开集住在 overlay 这一层：来回切视角保持选中文件与右栏（设计裁决），
    // 关掉时把逐层缓存丢掉——下次打开重新读到最新的磁盘状态
    var view by remember { mutableStateOf(FilesView.CHANGES) }
    // 展开的目录（值无意义，只用 key）——这个 Compose 版本还没有 mutableStateSetOf
    val expanded = remember { mutableStateMapOf<String, Unit>() }
    DisposableEffect(Unit) {
        model.loadFilesShowHidden()
        onDispose { model.clearFileTree() }
    }
    // 只在「还没有任何选中」时落到第一条：一旦用户从「全部」视角点开了一个没改过的文件，
    // 它就不在 files 里——旧写法会把选中拽回 files.first()，等于禁止浏览未改文件
    LaunchedEffect(files, selectedPath) {
        if (selectedPath == null && files.isNotEmpty()) model.selectChangedFile(files.first().path)
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
                // ↑↓ 走的是变更列表；在「全部」视角里它会把选中拽出当前那棵树，所以只在变更视角生效
                if (view != FilesView.CHANGES) return@onPreviewKeyEvent false
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
            if (view == FilesView.CHANGES && files.isNotEmpty()) FilesSummaryText(files, fontSize = 12.sp)
            Box(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Close, stringResource(Res.string.close), tint = Tok.tx2,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        // 左栏（280dp）永远在：视角头 + 当前视角的列表；右栏是选中文件的预览（改过 = diff，
        // 没改过 = 全文）。零改动不再是一整块空态——「全部」视角照样能浏览 workdir。
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(280.dp).fillMaxHeight().background(Tok.raised)) {
                LeftPaneHeader(model, view, onPick = { view = it })
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                when (view) {
                    FilesView.CHANGES -> when {
                        model.changedFilesLoading && files.isEmpty() -> LoadingBody()
                        files.isEmpty() && model.changedFilesStale -> Box(Modifier.fillMaxSize()) {
                            // no reply at all — the daemon predates the changed-files messages (mirrors mobile's banner)
                            DiffEmptyState(
                                glyph = ">_",
                                title = stringResource(Res.string.diff_stale_title),
                                caption = stringResource(Res.string.diff_stale_hint),
                            )
                        }
                        files.isEmpty() -> EmptyBody()
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(files, key = { it.path }) { f ->
                                DesktopFileRow(f, selected = f.path == model.selectedChangedPath) { model.selectChangedFile(f.path) }
                            }
                        }
                    }
                    FilesView.ALL -> WorkdirTree(model, expanded)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
            Column(Modifier.weight(1f).fillMaxHeight().background(DiffTok.codeBg)) {
                // 未改过的文件不在 changedFiles 里 —— 右栏只需要一条 path，改动元信息（状态点 / 增删数）
                // 有就带、没有就不显示
                model.selectedChangedPath?.let { path ->
                    SelectedFilePane(model, path, files.firstOrNull { it.path == path })
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
            FootHint("↑↓", stringResource(Res.string.key_switch_file))
            FootHint("click @@", stringResource(Res.string.key_collapse_hunk))
            FootHint("esc", stringResource(Res.string.key_close))
        }
    }
}

@Composable
private fun FootHint(keycap: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key(keycap)
        Text(label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, style = tightCenter(11.sp))
    }
}

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Tok.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun EmptyBody() {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.files_empty), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp,
            textAlign = TextAlign.Center, lineHeight = 18.sp,
        )
    }
}

// ── 左栏头部：workdir + 视角分段 + 隐藏项开关 ───────────────────────────────────────────────────

/** 设计稿 left pane header：mono workdir 路径 / 分段控件 h24 r6 pad2（变更段带 terracotta 计数）/
 *  眼睛开关 24×24 r6。标题「文件」由 overlay 顶栏承担，这里不重复写一遍。 */
@Composable
private fun LeftPaneHeader(model: DesktopModel, view: FilesView, onPick: (FilesView) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TailPathText(model.chatWorkdir, fontSize = 10.sp, color = Tok.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(6.dp))
                    .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                @Composable
                fun seg(target: FilesView, content: @Composable () -> Unit) {
                    val on = view == target
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                            .background(if (on) Tok.hair else Color.Transparent)
                            .clickable(enabled = !on) { onPick(target) },
                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { content() }
                }
                seg(FilesView.CHANGES) {
                    Text(
                        stringResource(Res.string.files_view_changes),
                        color = if (view == FilesView.CHANGES) Tok.tx else Tok.tx2,
                        fontFamily = Dk.ui, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, style = tightCenter(11.5.sp),
                    )
                    // 同排异字号 → 两个 Text 都要 tightCenter
                    val n = model.changedFiles.size
                    if (n > 0) Text(
                        n.toString(), color = Tok.accent, fontFamily = Dk.mono,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(10.sp),
                    )
                }
                seg(FilesView.ALL) {
                    Text(
                        stringResource(Res.string.files_view_all),
                        color = if (view == FilesView.ALL) Tok.tx else Tok.tx2,
                        fontFamily = Dk.ui, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, style = tightCenter(11.5.sp),
                    )
                }
            }
            val on = model.filesShowHidden
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (on) Tok.accent.copy(alpha = 0.5f) else Tok.hair, RoundedCornerShape(6.dp))
                    .clickable { model.toggleFilesShowHidden() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (on) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    stringResource(Res.string.files_show_hidden),
                    tint = if (on) Tok.accent else Tok.tx2, modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

// ── 「全部」视角：workdir 缩进树 ────────────────────────────────────────────────────────────────

/** 设计稿 tree：行 h26、缩进步进 14dp、▾/▸ 展开、改过的文件行尾状态点、含改动的目录行尾计数徽章。 */
@Composable
private fun WorkdirTree(model: DesktopModel, expanded: MutableMap<String, Unit>) {
    LaunchedEffect(Unit) { model.browseFileTree("") } // 根这一层总要有
    val workdir = model.chatWorkdir
    val index = changedIndexOf(workdir, model.changedFiles)
    val rows = flattenFileTree(model.fileTree, expanded.keys, model.filesShowHidden)
    val root = model.fileTree[""]
    when {
        root == null -> LoadingBody()
        !root.ok -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(Res.string.files_dir_error), color = Tok.tx2, fontFamily = Dk.ui,
                fontSize = 12.5.sp, textAlign = TextAlign.Center,
            )
        }
        rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(Res.string.files_dir_empty), color = Tok.tx2, fontFamily = Dk.ui,
                fontSize = 12.5.sp, textAlign = TextAlign.Center,
            )
        }
        else -> LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
            items(rows, key = { it.kind.name + ":" + it.subPath }) { row ->
                when (row.kind) {
                    FileRowKind.TRUNCATED -> TreeTruncatedRow(row.depth)
                    FileRowKind.DIR -> TreeDirRow(row, subtreeChangeCount(index, row.subPath)) {
                        if (expanded.remove(row.subPath) == null) {
                            expanded[row.subPath] = Unit
                            model.browseFileTree(row.subPath)
                        }
                    }
                    FileRowKind.FILE -> {
                        // ChangedFile.path 是 daemon 主机上的绝对原生路径 —— 树行也必须拼成同一形状，
                        // 否则查看器认不出它是改过的文件（状态点 / 增删数会整个丢掉）
                        val abs = joinNative(workdir, row.subPath)
                        TreeFileRow(row, index[row.subPath], selected = abs == model.selectedChangedPath) {
                            model.selectChangedFile(abs)
                        }
                    }
                }
            }
        }
    }
}

/** 树行的公共几何：h26、按深度缩进、hover/selected 底色。 */
@Composable
private fun TreeRowShell(depth: Int, selected: Boolean, onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(26.dp)
            .background(
                when {
                    selected -> Tok.surface
                    hovered && onClick != null -> Color.White.copy(alpha = 0.025f)
                    else -> Color.Transparent
                },
            )
            .then(if (selected) Modifier.border(1.dp, Tok.hair) else Modifier)
            .hoverable(hover)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 10.dp + TREE_INDENT * depth, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

private val TREE_INDENT = 14.dp

@Composable
private fun TreeDirRow(row: FileTreeRow, changes: Int, onToggle: () -> Unit) {
    TreeRowShell(row.depth, selected = false, onClick = onToggle) {
        Text(
            if (row.expanded) "▾" else "▸", color = Tok.tx2, fontFamily = Dk.mono,
            fontSize = 8.sp, style = tightCenter(8.sp), modifier = Modifier.width(8.dp),
        )
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(11.dp))
        Text(
            row.name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            style = tightCenter(12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (row.loading) CircularProgressIndicator(Modifier.size(10.dp), color = Tok.muted, strokeWidth = 1.dp)
        if (changes > 0) SubtreeCountBadge(changes, height = 15.dp, minWidth = 15.dp, radius = 4.dp, fontSize = 9.5.sp, fontFamily = Dk.mono)
    }
}

@Composable
private fun TreeFileRow(row: FileTreeRow, op: String?, selected: Boolean, onClick: () -> Unit) {
    TreeRowShell(row.depth, selected, onClick) {
        Box(Modifier.width(8.dp))
        Text(
            row.name,
            color = when {
                selected -> Tok.tx
                op != null -> Tok.tx2
                else -> Tok.muted
            },
            fontFamily = Dk.mono, fontSize = 12.sp, style = tightCenter(12.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (op != null) StatusDot(op, size = 14.dp, fontSize = 9.sp, fontFamily = Dk.mono)
    }
}

/** 那一层被 daemon 的 2000 条上限截断了 —— 说清楚，别让人以为文件不存在。 */
@Composable
private fun TreeTruncatedRow(depth: Int) {
    TreeRowShell(depth, selected = false, onClick = null) {
        Text(
            stringResource(Res.string.files_truncated, PocketRepository.FILE_TREE_LIMIT),
            color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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

/**
 * The right pane for the selected file: sticky header (path · copy · chip · stats · toggle) + body.
 * [file] 是这条路径在本会话变更集里的记录，**可以为 null**——「全部」视角点开一个没改过的文件走的
 * 就是这条：没有状态点、没有增删数、Diff 段置灰，右栏落到全文。
 */
@Composable
private fun SelectedFilePane(model: DesktopModel, path: String, file: ChangedFile?) {
    val diff = model.selectedDiff
    val isImage = isImagePath(path)
    val deleted = file?.op == "delete"
    val ext = path.substringAfterLast('.', "").lowercase()
    var diffTab by rememberDiffTab(path, isImage, deleted, diff)
    val wrap = rememberWrapState()

    Row(
        Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.weight(1f)) { TailPathText(path, fontSize = 12.sp, color = Tok.tx2) }
        // copy · open · save-as as ONE tight right-aligned group (chat-cards handoff, §2.5):
        // muted at rest, each lifts to a raised chip on hover
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            CopyPathButton(path)
            // export what the viewer holds (issue #67): open with the system app / save a copy
            val content = model.selectedContent
            val exportable = remember(content) { exportBytesOf(content) }
            if (exportable != null) {
                HeaderIconButton(Icons.Rounded.OpenInNew, stringResource(Res.string.file_open)) {
                    previewFile(fileNameOf(path), exportable, content?.mediaType)
                }
                HeaderIconButton(Icons.Rounded.Download, stringResource(Res.string.file_save_as)) {
                    shareFile(fileNameOf(path), exportable, content?.mediaType)
                }
            }
        }
        file?.let { StatusChip(it.op) }
        val (adds, dels) = shownStats(file, diff)
        if (!isImage && (adds != null || dels != null)) DiffStatText(adds, dels, fontSize = 12.sp)
        if (wrapApplies(diffTab, diff, model.selectedContent, ext, isImage)) {
            val active = if (diffTab) wrap.diff else wrap.file
            WrapToggle(on = active.value) { active.value = !active.value }
        }
        DiffFileToggle(
            diffSelected = diffTab,
            isImage = isImage,
            deleted = deleted,
            noDiff = diffUnavailable(diff),
            onPick = { diffTab = it },
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

    Box(Modifier.fillMaxSize()) {
        if (diffTab) DiffPaneBody(diff, ext = ext.ifEmpty { null }, dense = true, wrap = wrap.diff.value)
        else FileTabBody(
            model.selectedContent, ext, dense = true, path = path, wrap = wrap.file.value,
            // chunked-read progress (issue #134): drives the loading card's determinate bar
            progress = model.selectedContentProgress,
        )
    }
}

@Composable
private fun CopyPathButton(path: String) {
    val (copied, copy) = rememberCopied()
    val label = stringResource(Res.string.copy_path)
    HeaderIconBox(label, onClick = { copy(path) }) { hovered ->
        if (copied) Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(14.dp))
        else Icon(Icons.Rounded.ContentCopy, label, tint = if (hovered) Tok.tx2 else Tok.muted, modifier = Modifier.size(14.dp))
    }
}

/** Quiet header action sharing [CopyPathButton]'s footprint and hover treatment. */
@Composable
private fun HeaderIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    HeaderIconBox(label, onClick) { hovered ->
        Icon(icon, label, tint = if (hovered) Tok.tx2 else Tok.muted, modifier = Modifier.size(14.dp))
    }
}

/** The handoff's .hicon hit target: 26dp rounded square, transparent at rest, raised on hover. */
@Composable
private fun HeaderIconBox(label: String, onClick: () -> Unit, content: @Composable (hovered: Boolean) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
            .background(if (hovered) Tok.raised else Color.Transparent)
            .hoverable(src).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(hovered) }
}
