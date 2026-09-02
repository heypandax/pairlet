package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  文件浏览：变更 / 全部 双视角
//  （design handoff：claude-design-handoff/files-browser-dual-view/）
//
//  裁决：一个「文件」入口 + 两个视角过滤，不做平行双入口。变更是默认视角、
//  形态不变；全部视角在桌面是缩进树、在手机是 iOS Files 式逐层下钻。两个
//  视角靠同一套 M/A/D 状态标记贯通——被本会话改过的文件在哪儿都带状态点，
//  含改动的目录带 terracotta 子树计数，任何行点开都进同一个查看器。
//
//  这个文件放：两端共用的纯逻辑（相对路径 / 状态索引 / 树摊平 / 面包屑）、
//  两端共用的小件（状态点、计数徽章），以及手机侧的全部视角列表。桌面侧的
//  树用 Dk 字体自成一套，住在 desktop/ChangesOverlay.kt。
// ════════════════════════════════════════════════════════════════════

/** 文件面的两个视角过滤。CHANGES 是默认——绝大多数时候要看的就是这一坨。 */
enum class FilesView { CHANGES, ALL }

/** 层级键的分隔符：客户端这一侧一律 '/'，daemon 的 NIO resolve 在 Windows 上同样接受
 *  （#152 的文件夹浏览器早就靠这一点跨平台，这里沿用同一个约定）。 */
private const val TREE_SEP = '/'

// ── 纯逻辑：路径代数 ────────────────────────────────────────────────────────────────────────────

internal fun treeJoin(subPath: String, name: String): String =
    if (subPath.isEmpty()) name else subPath + TREE_SEP + name

internal fun treeParentOf(subPath: String): String = subPath.substringBeforeLast(TREE_SEP, "")

/** subPath 的层级段（根是空列表）。 */
internal fun treeSegmentsOf(subPath: String): List<String> = subPath.split(TREE_SEP).filter { it.isNotEmpty() }

/** 面包屑第 [index] 段（0 起）对应的 subPath；[index] < 0 → 根。 */
internal fun treeSubPathAt(subPath: String, index: Int): String =
    if (index < 0) "" else treeSegmentsOf(subPath).take(index + 1).joinToString(TREE_SEP.toString())

/**
 * [abs]（daemon 主机上的绝对原生路径，[ChangedFile.path] 就是这个形状）在 [workdir] 下的相对键
 * （'/'-keyed）；不在 workdir 里返回 null。
 *
 * 截断处必须落在分隔符上——否则 "/a/bc" 会被误当成 "/a/b" 的子路径，目录计数就会虚高。
 */
internal fun relUnderWorkdir(workdir: String, abs: String): String? {
    if (workdir.isEmpty()) return null
    val sep = sepOf(workdir)
    // 根（"/"、"C:\"）自带尾分隔符，不能 trim 掉，否则 root 变成空串
    val root = workdir.takeIf { it.length <= 1 || it.trimEnd(sep).isEmpty() } ?: workdir.trimEnd(sep)
    if (!abs.startsWith(root)) return null
    val rest = abs.substring(root.length)
    val rooted = root.endsWith('/') || root.endsWith('\\')
    if (!rooted && rest.firstOrNull() != '/' && rest.firstOrNull() != '\\') return null
    val body = rest.trimStart('/', '\\')
    return body.ifEmpty { null }?.replace('\\', TREE_SEP)
}

/** 变更集 → 「workdir 相对路径（'/'-keyed）→ op」。workdir 外的路径（#67 导出过的外部文件）不进树标记：
 *  树本来就只长在 workdir 里面，标不上的东西不该混进计数。 */
fun changedIndexOf(workdir: String?, files: List<ChangedFile>): Map<String, String> {
    if (workdir.isNullOrEmpty()) return emptyMap()
    return files.mapNotNull { f -> relUnderWorkdir(workdir, f.path)?.let { it to f.op } }.toMap()
}

/** [subPath] 子树里有多少个改动文件（目录行尾的 terracotta 计数徽章；0 = 不显示徽章）。 */
fun subtreeChangeCount(index: Map<String, String>, subPath: String): Int {
    val prefix = if (subPath.isEmpty()) "" else subPath + TREE_SEP
    return index.keys.count { it.startsWith(prefix) }
}

/** [subPath] 这一层里名为 [name] 的文件的 op（null = 本会话没改过它 → 不带状态点）。 */
fun statusOpAt(index: Map<String, String>, subPath: String, name: String): String? = index[treeJoin(subPath, name)]

/** 眼睛关掉时滤掉 `.` 开头的条目。daemon 侧的 gitignore 智能过滤是另一条并行线，这里的客户端过滤
 *  是兜底——两路合到一起之后这一层依然保留（少一层依赖，旧 daemon 上也有隐藏项开关）。 */
fun visibleEntries(entries: List<PathEntry>, showHidden: Boolean): List<PathEntry> =
    if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }

// ── 纯逻辑：桌面树的摊平 ────────────────────────────────────────────────────────────────────────

enum class FileRowKind { DIR, FILE, TRUNCATED }

/**
 * 桌面缩进树摊平后的一行。[subPath] 是这一行自己的 '/'-keyed 路径（TRUNCATED 行是它所属那一层的）。
 * [loading] = 目录已展开但那一层还没回来（占位一行，避免树「点了没反应」）。
 */
data class FileTreeRow(
    val subPath: String,
    val name: String,
    val kind: FileRowKind,
    val depth: Int,
    val expanded: Boolean = false,
    val loading: Boolean = false,
)

/**
 * 逐层缓存 + 展开集 → 可直接喂 LazyColumn 的行序列。纯函数，好单测；缺的层直接跳过（调用方看到
 * [FileTreeRow.loading] 再去请求），被截断的层在自己最后一行后面补一条 TRUNCATED 提示。
 */
fun flattenFileTree(
    levels: Map<String, PathEntries>,
    expanded: Set<String>,
    showHidden: Boolean,
): List<FileTreeRow> {
    val out = mutableListOf<FileTreeRow>()
    fun walk(sub: String, depth: Int) {
        val level = levels[sub] ?: return
        for (e in visibleEntries(level.entries, showHidden)) {
            val child = treeJoin(sub, e.name)
            if (e.isDir) {
                val open = child in expanded
                out += FileTreeRow(child, e.name, FileRowKind.DIR, depth, expanded = open, loading = open && levels[child] == null)
                if (open) walk(child, depth + 1)
            } else {
                out += FileTreeRow(child, e.name, FileRowKind.FILE, depth)
            }
        }
        if (level.truncated) out += FileTreeRow(sub, "", FileRowKind.TRUNCATED, depth)
    }
    walk("", 0)
    return out
}

// ── 两端共用的小件 ──────────────────────────────────────────────────────────────────────────────

/**
 * 圆形状态点：改过的文件在树 / 下钻列表 / 变更列表里都是这一颗（设计稿 status dot 14×14 r7 桌面、
 * 18×18 r9 手机）。字母与配色沿用 [statusLetter] / [statusColor]——一处口径，两个视角不会漂。
 */
@Composable
fun StatusDot(op: String, size: Dp, fontSize: TextUnit, fontFamily: FontFamily = FontFamily.Monospace) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(percent = 50)).background(statusColor(op).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            statusLetter(op), color = statusColor(op), fontFamily = fontFamily,
            fontSize = fontSize, fontWeight = FontWeight.SemiBold,
            // 文字与几何盒同排居中 → tightCenter（项目铁律，见 theme/TightText.kt）
            style = tightCenter(fontSize), textAlign = TextAlign.Center,
        )
    }
}

/** 目录行尾的子树改动计数（terracotta 16% 底）。0 由调用方判掉，这里只负责画。 */
@Composable
fun SubtreeCountBadge(count: Int, height: Dp, minWidth: Dp, radius: Dp, fontSize: TextUnit, fontFamily: FontFamily = FontFamily.Monospace) {
    Box(
        Modifier.height(height).widthIn(min = minWidth).clip(RoundedCornerShape(radius))
            .background(Tok.accent.copy(alpha = 0.16f)).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(), color = Tok.accent, fontFamily = fontFamily,
            fontSize = fontSize, fontWeight = FontWeight.SemiBold,
            style = tightCenter(fontSize), textAlign = TextAlign.Center,
        )
    }
}

// ── 手机：视角分段 / 隐藏项开关 / 面包屑 / 下钻列表 ─────────────────────────────────────────────

/** h30 r8 的两段控件（设计稿 segmented h30 r8 pad2、内 pill r6、字 13）；变更段带 terracotta 计数。 */
@Composable
fun FilesViewSegmented(view: FilesView, changedCount: Int, onPick: (FilesView) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun seg(target: FilesView, content: @Composable () -> Unit) {
            val on = view == target
            Row(
                Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) Tok.hair else Color.Transparent)
                    .clickable(enabled = !on) { onPick(target) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
        seg(FilesView.CHANGES) {
            Text(
                stringResource(Res.string.files_view_changes),
                color = if (view == FilesView.CHANGES) Tok.tx else Tok.tx2,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, style = tightCenter(13.sp),
            )
            // 异字号 Text 同排 → 两个都要 tightCenter（只加一个照样错位）
            if (changedCount > 0) Text(
                changedCount.toString(), color = Tok.accent, fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(11.sp),
            )
        }
        seg(FilesView.ALL) {
            Text(
                stringResource(Res.string.files_view_all),
                color = if (view == FilesView.ALL) Tok.tx else Tok.tx2,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, style = tightCenter(13.sp),
            )
        }
    }
}

/** 30×30 r8 的隐藏项开关；开着时用 accent 描边+图标，关着时是安静的 hairline 方钮。 */
@Composable
fun HiddenFilesToggle(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (on) Tok.accent.copy(alpha = 0.5f) else Tok.hair, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            stringResource(Res.string.files_show_hidden),
            tint = if (on) Tok.accent else Tok.tx2,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** h32 的面包屑：根段是项目文件夹名，当前层高亮、上级可点回跳。 */
@Composable
private fun FilesBreadcrumb(rootLabel: String, subPath: String, onJump: (String) -> Unit) {
    val segs = treeSegmentsOf(subPath)
    Row(
        Modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val atRoot = segs.isEmpty()
        Text(
            rootLabel, color = if (atRoot) Tok.tx else Tok.tx2, fontFamily = FontFamily.Monospace,
            fontSize = 12.sp, style = tightCenter(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(enabled = !atRoot) { onJump("") },
        )
        segs.forEachIndexed { i, s ->
            val last = i == segs.lastIndex
            Text("›", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, style = tightCenter(12.sp))
            Text(
                s, color = if (last) Tok.tx else Tok.tx2, fontFamily = FontFamily.Monospace,
                fontSize = 12.sp, style = tightCenter(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = !last) { onJump(treeSubPathAt(subPath, i)) },
            )
        }
    }
}

/**
 * 手机「全部」视角：iOS Files 式逐层下钻（不是缩进树——窄屏上缩进很快就没地方放名字了）。
 * 面包屑 → 文件夹行（带子树计数，点击下钻）→ 分隔线 → 文件行（带状态点，点击进查看器）。
 *
 * [subPath] 由调用方持有，这样切回变更视角再切回来还站在原来那一层。
 */
@Composable
fun AllFilesList(
    repo: PocketRepository,
    subPath: String,
    onSubPath: (String) -> Unit,
    onOpen: (String) -> Unit,
    maxHeight: Dp = 420.dp,
) {
    val workdir = repo.workdir.value.orEmpty()
    // 只请求还没缓存的层；返回上层 / 切视角都直接命中缓存，不重复打扰 daemon
    LaunchedEffect(subPath, workdir) { repo.browseFileTree(subPath) }

    val level = repo.fileTree[subPath]
    val index = changedIndexOf(workdir, repo.changedFiles)
    val rootLabel = fileNameOf(workdir).ifEmpty { workdir.ifEmpty { "/" } }

    FilesBreadcrumb(rootLabel, subPath, onJump = onSubPath)
    Hairline()
    when {
        level == null -> Text(
            stringResource(Res.string.dir_picker_loading), color = Tok.muted, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
        )
        !level.ok -> Text(
            stringResource(Res.string.files_dir_error), color = Tok.tx2, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
        )
        else -> {
            val rows = visibleEntries(level.entries, repo.filesShowHidden.value)
            val dirs = rows.filter { it.isDir }
            val files = rows.filterNot { it.isDir }
            if (rows.isEmpty()) {
                Text(
                    stringResource(Res.string.files_dir_empty), color = Tok.tx2, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
                )
                return
            }
            LazyColumn(Modifier.heightIn(max = maxHeight)) {
                items(dirs, key = { "d:" + it.name }) { e ->
                    val child = treeJoin(subPath, e.name)
                    DrillFolderRow(e.name, subtreeChangeCount(index, child)) { onSubPath(child) }
                }
                if (dirs.isNotEmpty() && files.isNotEmpty()) item(key = "__split") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Hairline() }
                }
                items(files, key = { "f:" + it.name }) { e ->
                    DrillFileRow(e.name, statusOpAt(index, subPath, e.name)) {
                        onOpen(joinNative(workdir, treeJoin(subPath, e.name)))
                    }
                }
                if (level.truncated) item(key = "__trunc") {
                    Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            stringResource(Res.string.files_truncated, PocketRepository.FILE_TREE_LIMIT),
                            color = Tok.muted, fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }
    }
}

/** h44 文件夹行：图标 + 名字 + 子树改动计数 + ›。 */
@Composable
private fun DrillFolderRow(name: String, changes: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
        Text(
            name, color = Tok.tx, fontSize = 14.sp, style = tightCenter(14.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (changes > 0) SubtreeCountBadge(changes, height = 17.dp, minWidth = 17.dp, radius = 5.dp, fontSize = 10.5.sp)
        Text("›", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, style = tightCenter(12.sp))
    }
}

/** h44 文件行：图标 + mono 名字 + 状态点（没改过就没有点）。 */
@Composable
private fun DrillFileRow(name: String, op: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.InsertDriveFile, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        Text(
            name, color = if (op != null) Tok.tx else Tok.tx2, fontFamily = FontFamily.Monospace,
            fontSize = 13.sp, style = tightCenter(13.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (op != null) StatusDot(op, size = 18.dp, fontSize = 10.5.sp)
        Spacer(Modifier.width(2.dp))
    }
}
