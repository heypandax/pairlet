package dev.ccpocket.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.DiffHunk
import dev.ccpocket.app.data.DiffLine
import dev.ccpocket.app.data.DiffLineKind
import dev.ccpocket.app.data.parseUnifiedDiff
import dev.ccpocket.app.data.staleDaemon
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.share.exportIsSaveDialog
import dev.ccpocket.app.share.previewFile
import dev.ccpocket.app.share.shareFile
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.isImageFile
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ════════════════════════════════════════════════════════════════════
//  Shared diff grammar (changed-files v2) — one visual language for the
//  mobile viewer and the desktop Changes browser, per the design handoff
//  (docs/design/claude-design-handoff/changed-files-diff/).
// ════════════════════════════════════════════════════════════════════

/** Design-handoff diff tints: 12% row fills / 16% gutter fills UNDER readable text. Getters (not cached
 *  vals) so a light/dark switch re-reads the live [Tok] palette; the non-token colors get a light variant. */
object DiffTok {
    val addBg get() = Tok.ok.copy(alpha = 0.12f)
    val addGut get() = Tok.ok.copy(alpha = 0.16f)
    val delBg get() = Tok.danger.copy(alpha = 0.12f)
    val delGut get() = Tok.danger.copy(alpha = 0.16f)
    val addGutText get() = if (Tok.current.dark) Color(0xFF7CC79A) else Color(0xFF2E7D4F)
    val delGutText get() = if (Tok.current.dark) Color(0xFFD98A7E) else Color(0xFFB4482F)
    val gutBorder get() = if (Tok.current.dark) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.06f)
    val codeBg get() = if (Tok.current.dark) Color(0xFF0B0C0D) else Color(0xFFF7F6F3)
}

/** op → the git-style status letter the list rows and header chips show. */
fun statusLetter(op: String): String = when (op) {
    "write" -> "A"; "delete" -> "D"; "notebook" -> "N"; else -> "M"
}

fun statusColor(op: String): Color = when (op) {
    "write" -> Tok.ok; "delete" -> Tok.danger; "notebook" -> Tok.tx2; else -> Tok.info
}

private fun statusBg(op: String): Color = when (op) {
    "write" -> Tok.ok.copy(alpha = 0.15f)
    "delete" -> Tok.danger.copy(alpha = 0.15f)
    "notebook" -> Tok.muted.copy(alpha = 0.20f)
    else -> Tok.info.copy(alpha = 0.15f)
}

/** The daemon-shared contract ([dev.ccpocket.protocol.IMAGE_FILE_EXTENSIONS]): what it serves as
 *  base64 is exactly what gets no Diff tab — one set, no drift. */
fun isImagePath(path: String): Boolean = isImageFile(path)

/** Extensions [FileTabBody] renders as reflowing markdown (via [MarkdownText]) rather than monospace
 *  source — one set so the File body and the wrap-toggle policy ([wrapApplies]) can't disagree. */
val markdownExts = setOf("md", "markdown")

// Windows-aware path splitting, on DirList's [sepOf] — the one separator heuristic (this repo has
// a documented Windows-path bug class; per-file copies of this split are how it recurs).
fun fileNameOf(path: String): String = path.substringAfterLast(sepOf(path))
fun parentDirOf(path: String): String = path.substringBeforeLast(sepOf(path), "")

/** 20×20 rounded-square status glyph: mono bold letter on a 15% tint (design: .st). */
@Composable
fun StatusChip(op: String) {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(statusBg(op)),
        contentAlignment = Alignment.Center,
    ) {
        Text(statusLetter(op), color = statusColor(op), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The mono "+N −M" stat: green adds, red dels, zero sides omitted; null stats render as "—". */
@Composable
fun DiffStatText(adds: Int?, dels: Int?, fontSize: androidx.compose.ui.unit.TextUnit = 11.sp) {
    if (adds == null && dels == null) {
        Text("—", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = fontSize)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if ((adds ?: 0) > 0) Text("+$adds", color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = fontSize)
        if ((dels ?: 0) > 0) Text("−$dels", color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = fontSize)
        if ((adds ?: 0) == 0 && (dels ?: 0) == 0) Text("±0", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = fontSize)
    }
}

/** "8 files · +214 −38" (sheet/overlay headers). All-null stats → "8 files · —". */
@Composable
fun FilesSummaryText(files: List<ChangedFile>, fontSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
    val hasStats = files.any { it.adds != null || it.dels != null }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${files.size} files ·", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = fontSize)
        DiffStatText(
            files.sumOf { it.adds ?: 0 }.takeIf { hasStats },
            files.sumOf { it.dels ?: 0 }.takeIf { hasStats },
            fontSize = fontSize,
        )
    }
}

/**
 * The raised [ Diff | File ] segmented pill (design: .pillseg). Enablement and the caption are the
 * shared policy, derived here so the two surfaces can't drift: images have no text diff (File only),
 * deleted files have no content left (Diff only).
 */
@Composable
fun DiffFileToggle(
    diffSelected: Boolean,
    isImage: Boolean,
    deleted: Boolean,
    onPick: (diff: Boolean) -> Unit,
) {
    val diffEnabled = !isImage
    val fileEnabled = !deleted
    val caption = when {
        isImage -> stringResource(Res.string.diff_binary)
        deleted -> stringResource(Res.string.file_deleted)
        else -> null
    }
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(Tok.raised).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        @Composable
        fun seg(label: String, isDiff: Boolean, enabled: Boolean) {
            val on = diffSelected == isDiff
            Text(
                label,
                color = if (on) Tok.tx else Tok.tx2,
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (on) Tok.surface else Tok.raised)
                    .clickable(enabled = enabled && !on) { onPick(isDiff) }
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(horizontal = 15.dp, vertical = 5.dp),
            )
        }
        seg(stringResource(Res.string.diff_tab), isDiff = true, enabled = diffEnabled)
        seg(stringResource(Res.string.file_tab), isDiff = false, enabled = fileEnabled)
        if (caption != null) Text(
            caption, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
        )
    }
}

// ── soft-wrap toggle (issue #95) ─────────────────────────────────────

/** The header soft-wrap toggle's per-view state. Two independent flags, not one, so flipping between
 *  the Diff and File tabs restores each view's own choice instead of one bleeding into the other. The
 *  defaults ARE the shared policy: the diff keeps its horizontal pan (OFF) so the +/− gutter and column
 *  alignment survive; a plain file reflows (ON) so long lines and prose read without side-scrolling. */
class WrapState(val diff: MutableState<Boolean>, val file: MutableState<Boolean>)

@Composable
fun rememberWrapState(): WrapState = remember { WrapState(mutableStateOf(false), mutableStateOf(true)) }

/** Whether a soft-wrap toggle is meaningful for what's on screen — derived here so both surfaces agree.
 *  The diff's code lines pan whenever there's a real diff; on the File tab only a plain-text body has
 *  lines to wrap (markdown already reflows, images/binaries have none). */
fun wrapApplies(diffTab: Boolean, diff: FileDiff?, content: FileContent?, ext: String, isImage: Boolean): Boolean =
    if (diffTab) diff?.ok == true
    else content?.ok == true && content.base64 == null && !isImage && ext !in markdownExts

/** The header's quiet soft-wrap toggle: a wrap-text glyph in a 30dp square, filled with a faint accent
 *  when ON. Shares the export chips' hover lift so it reads as a control on desktop; the glyph stays
 *  visible when OFF (muted) so it's discoverable on touch, where there is no hover. */
@Composable
fun WrapToggle(on: Boolean, onToggle: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.size(30.dp).clip(shape)
            .background(if (on) Tok.accent.copy(alpha = 0.14f) else if (hovered) Tok.raised else Color.Transparent)
            .then(if (on) Modifier.border(1.dp, Tok.accent.copy(alpha = 0.4f), shape) else Modifier)
            .hoverable(src).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.WrapText,
            stringResource(Res.string.diff_soft_wrap),
            tint = if (on) Tok.accent else if (hovered) Tok.tx2 else Tok.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── the diff body ───────────────────────────────────────────────────

private sealed interface DiffRow {
    /** [key] is a stable LazyColumn identity so collapsing a hunk reuses (not re-renders) the rows below. */
    val key: String

    data class Header(val idx: Int, val hunk: DiffHunk) : DiffRow {
        override val key get() = "h$idx"
    }
    data class Line(val idx: Int, val line: DiffLine) : DiffRow {
        // (oldNo, newNo) is strictly increasing within a hunk, so idx + the pair is unique
        override val key get() = "$idx:${line.oldNo}:${line.newNo}"
    }
    data class Gap(val idx: Int, val lines: Int) : DiffRow {
        override val key get() = "g$idx"
    }
}

/**
 * The unified diff, rendered per the shared grammar: collapsible hunk-header bands, +/− rows on
 * 12% tints, a quiet line-number gutter (single new-side column on mobile, old|new pair when
 * [dense]), and "⋯ N unchanged lines" separators where consecutive numbered hunks leave a gap.
 * The gutter stays put; code pans on one shared horizontal scroll, or reflows when [wrap] is on.
 * Wrapped in a [SelectionContainer] so the diff text selects and copies (issue #95).
 */
@Composable
fun DiffView(hunks: List<DiffHunk>, ext: String?, dense: Boolean = false, wrap: Boolean = false, modifier: Modifier = Modifier) {
    val collapsed = remember(hunks) { mutableStateMapOf<Int, Boolean>() }
    val hScroll = rememberScrollState()
    // derivedStateOf: rebuilt only when hunks/collapse actually change, not on every parent recomposition
    val rows by remember(hunks) {
        derivedStateOf {
            buildList {
                hunks.forEachIndexed { i, h ->
                    if (i > 0) {
                        val prev = hunks[i - 1]
                        if (prev.numbered && h.numbered) {
                            val gap = h.oldStart - (prev.oldStart + prev.oldLines)
                            if (gap > 0) add(DiffRow.Gap(i, gap))
                        }
                    }
                    add(DiffRow.Header(i, h))
                    if (collapsed[i] != true) h.lines.forEach { add(DiffRow.Line(i, it)) }
                }
            }
        }
    }
    // highlight memoized per distinct line text (blanks/braces repeat a lot), stable across
    // collapse toggles and scroll-backs — without the map every viewport entry re-tokenizes
    val highlight: (String) -> AnnotatedString = remember(ext) {
        val cache = HashMap<String, AnnotatedString>()
        val fn: (String) -> AnnotatedString = { text -> cache.getOrPut(text) { highlightCodeOrNull(text, ext) ?: AnnotatedString(text) } }
        fn
    }
    // SelectionContainer wraps the list so diff text is selectable/copyable. When wrap is OFF the code
    // still pans on [hScroll]; on touch, selection is a long-press (then drag), so panning and selecting
    // coexist — verify that interaction on device (issue #95).
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().background(DiffTok.codeBg)) {
            items(rows.size, key = { rows[it].key }) { i ->
                when (val row = rows[i]) {
                    is DiffRow.Header -> HunkHeader(
                        row.hunk, dense,
                        isCollapsed = collapsed[row.idx] == true,
                        firstHunk = row.idx == 0,
                    ) { collapsed[row.idx] = collapsed[row.idx] != true }
                    is DiffRow.Line -> DiffLineRow(row.line, dense, wrap, hScroll, highlight)
                    is DiffRow.Gap -> GapRow(row.lines)
                }
            }
        }
    }
}

@Composable
private fun HunkHeader(hunk: DiffHunk, dense: Boolean, isCollapsed: Boolean, firstHunk: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        if (!firstHunk) HairlineRow()
        Row(
            Modifier.fillMaxWidth().background(Tok.surface)
                .padding(start = 10.dp, end = 12.dp, top = if (dense) 4.dp else 5.dp, bottom = if (dense) 4.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("▾", color = Tok.muted, fontSize = 10.sp, modifier = Modifier.rotate(if (isCollapsed) -90f else 0f))
            val header = if (hunk.numbered) "@@ −${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@" else "@@ ⋯ @@"
            Text(header, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            Box(Modifier.weight(1f))
            if (isCollapsed) Text(
                stringResource(Res.string.diff_collapsed_lines, hunk.lines.size),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
        HairlineRow()
    }
}

@Composable
private fun HairlineRow() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

@Composable
private fun GapRow(lines: Int) {
    Column(Modifier.fillMaxWidth()) {
        HairlineRow()
        Text(
            stringResource(Res.string.diff_unchanged_lines, lines),
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        HairlineRow()
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, dense: Boolean, wrap: Boolean, hScroll: ScrollState, highlight: (String) -> AnnotatedString) {
    val rowBg = when (line.kind) {
        DiffLineKind.ADD -> DiffTok.addBg
        DiffLineKind.DEL -> DiffTok.delBg
        DiffLineKind.CTX -> Color.Transparent
    }
    val gutBg = when (line.kind) {
        DiffLineKind.ADD -> DiffTok.addGut
        DiffLineKind.DEL -> DiffTok.delGut
        DiffLineKind.CTX -> Color.Transparent
    }
    val gutText = when (line.kind) {
        DiffLineKind.ADD -> DiffTok.addGutText
        DiffLineKind.DEL -> DiffTok.delGutText
        DiffLineKind.CTX -> Tok.muted
    }
    val codeColor = if (line.kind == DiffLineKind.ADD) Tok.tx else Tok.tx2
    val fontSize = if (dense) 12.sp else 12.5.sp
    val lineHeight = if (dense) 19.sp else 21.sp
    val gutWidth = if (dense) 30.dp else 38.dp
    val gutPad = if (dense) 7.dp else 8.dp

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun Gutter(no: Int?) = Box(
            Modifier.widthIn(min = gutWidth).fillMaxHeight().background(gutBg).padding(horizontal = gutPad),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(no?.toString() ?: "", color = gutText, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = lineHeight, maxLines = 1)
        }
        if (dense) Gutter(line.oldNo) // desktop: old | new pair; mobile keeps the single new-side gutter
        Gutter(line.newNo)
        Box(Modifier.width(1.dp).fillMaxHeight().background(DiffTok.gutBorder))
        Text(
            when (line.kind) { DiffLineKind.ADD -> "+"; DiffLineKind.DEL -> "−"; DiffLineKind.CTX -> "" },
            color = when (line.kind) { DiffLineKind.ADD -> Tok.ok; DiffLineKind.DEL -> Tok.danger; else -> Color.Transparent },
            fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = lineHeight, textAlign = TextAlign.Center,
            modifier = Modifier.width(16.dp),
        )
        val body = remember(line.text, highlight) { highlight(line.text) }
        Text(
            body, color = codeColor, fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = lineHeight,
            softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                .padding(start = if (dense) 9.dp else 8.dp, end = if (dense) 20.dp else 16.dp),
        )
    }
}

// ── the shared viewer surface (mobile full-screen viewer + desktop Changes pane) ──

/** The [ Diff | File ] selection (true = Diff), with the shared default + auto-flip policy: images
 *  land on File (no text diff), everything else on Diff; a diff that comes back empty-for-real
 *  (not the stale-daemon state) flips to File — except deleted files, where the diff is the only
 *  thing left to show. */
@Composable
fun rememberDiffTab(path: String, isImage: Boolean, deleted: Boolean, diff: FileDiff?): MutableState<Boolean> {
    val tab = remember(path) { mutableStateOf(!isImage) }
    LaunchedEffect(diff) {
        if (tab.value && diff != null && !diff.ok && !diff.staleDaemon && !deleted) tab.value = false
    }
    return tab
}

/** Header stats: the loaded diff's exact counts when available, else the list row's transcript totals. */
fun shownStats(file: ChangedFile?, diff: FileDiff?): Pair<Int?, Int?> =
    if (diff?.ok == true) diff.adds to diff.dels else (file?.adds to file?.dels)

/** The Diff tab's whole body: spinner → stale-daemon state → no-diff state → banner + [DiffView]. */
@Composable
fun DiffPaneBody(diff: FileDiff?, ext: String?, dense: Boolean, wrap: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        when {
            diff == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Tok.tx2, strokeWidth = 2.dp)
            }
            diff.staleDaemon -> DiffEmptyState(
                glyph = ">_",
                title = stringResource(Res.string.diff_stale_title),
                caption = stringResource(Res.string.diff_stale_hint),
            )
            !diff.ok -> DiffEmptyState(glyph = "±", title = stringResource(Res.string.diff_none), caption = null)
            else -> Column(Modifier.fillMaxSize()) {
                if (diff.truncated) TruncatedBanner(shownKb = (diff.diff?.length ?: 0) / 1024)
                val hunks = remember(diff.diff) { parseUnifiedDiff(diff.diff ?: "") }
                DiffView(hunks, ext = ext, dense = dense, wrap = wrap, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Amber slim banner above a capped diff (design: .trunc). The daemon doesn't know the un-capped
 *  total, so this reports what IS shown rather than inventing an "of N" figure. */
@Composable
fun TruncatedBanner(shownKb: Int) {
    Row(
        Modifier.fillMaxWidth().background(Tok.warn.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = Tok.warn, fontSize = 11.sp)
        Text(
            stringResource(Res.string.diff_truncated, shownKb),
            color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
}

/** Centered empty state inside the diff pane (design: .empty — glyph square, text, mono caption). */
@Composable
fun DiffEmptyState(glyph: String, title: String, caption: String?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Tok.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        }
        Text(
            title, color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (caption != null) Text(
            caption, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The File tab's whole body — the original full-content view: markdown via [MarkdownText] (selectable,
 *  issue #95), base64 images, everything else as selectable highlighted monospace that reflows when
 *  [wrap] is on (else it pans horizontally). [dense] = desktop metrics. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun FileTabBody(content: FileContent?, ext: String, dense: Boolean = false, path: String? = null, wrap: Boolean = true) {
    Box(Modifier.fillMaxSize()) {
        when {
            // documents ride the binary channel whole-or-nothing — while the bytes are in flight the
            // card skeleton stands in (chat-cards handoff, loading); other types keep the spinner
            content == null ->
                if (path != null && ext in documentExts) DocumentLoadingCard(path, dense)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tok.tx2, strokeWidth = 2.dp)
                }
            !content.ok -> {
                // the daemon's binary size cap is the one refusal worth a card that explains the
                // limit in place (its error text is the only wire signal — no structured kind)
                val err = content.error ?: "?"
                if ("too large" in err) DocumentTooLargeCard(content.path, err, dense)
                else Text(
                    err, color = Tok.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                )
            }
            content.base64 != null -> {
                val bytes = remember(content.base64) { runCatching { Base64.Default.decode(content.base64!!) }.getOrNull() }
                val bmp = bytes?.let { rememberImageBitmap(it) }
                when {
                    bmp != null -> Image(
                        bmp, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                    // documents & other binaries (issues #67/#79): no inline rendering — hand the
                    // bytes to the platform's native preview / share-save gesture instead
                    bytes != null -> DocumentCard(content.path, bytes, content.mediaType, content.totalBytes, dense)
                    else -> Text(
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
                if (ext in markdownExts) {
                    // SelectionContainer so rendered markdown selects/copies (issue #95); it already reflows
                    SelectionContainer { MarkdownText(text, Tok.tx) }
                } else {
                    // tint by file extension (issue #51); unknown or oversize files come back as
                    // the plain single-color string
                    val body = remember(text, ext) { highlightCode(text, ext) }
                    val hScroll = rememberScrollState()
                    SelectionContainer {
                        Text(
                            body, color = Tok.tx2, fontFamily = FontFamily.Monospace,
                            fontSize = if (dense) 12.sp else 12.5.sp, lineHeight = if (dense) 19.sp else 21.sp,
                            softWrap = wrap,
                            modifier = Modifier.fillMaxWidth()
                                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
                        )
                    }
                }
            }
        }
    }
}

// ── document export card (issues #67/#79) ───────────────────────────

/** "12.3 KB" / "1.2 MB" — the one place export sizes are formatted. */
fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${(bytes * 10 / 1_048_576).toDouble() / 10} MB"
    else -> "${(bytes + 1023) / 1024} KB"
}

/** The six handoff document families (chat-cards, §2.4). Colours reference the live [Tok] palette
 *  (getters, like [DiffTok] — no new hex constants): spreadsheet→ok green, document→info blue,
 *  slides→accent terracotta, pdf→danger red at a LOWER tint so red never reads as an error,
 *  archive→warn amber, binary→neutral grey. Tint alphas are the handoff's exact values. */
private enum class DocFamily {
    SHEET, DOC, SLIDE, PDF, ZIP, BIN;

    val fg get() = when (this) {
        SHEET -> Tok.ok; DOC -> Tok.info; SLIDE -> Tok.accent
        PDF -> Tok.danger; ZIP -> Tok.warn; BIN -> Tok.tx2
    }
    val bg get() = when (this) {
        PDF, ZIP -> fg.copy(alpha = 0.12f)
        BIN -> Tok.muted.copy(alpha = 0.16f)
        else -> fg.copy(alpha = 0.13f)
    }
    val bd get() = when (this) {
        SLIDE -> fg.copy(alpha = 0.36f)
        BIN -> Tok.muted.copy(alpha = 0.42f)
        else -> fg.copy(alpha = 0.34f)
    }
}

private fun docFamilyOf(ext: String) = when (ext) {
    "xlsx", "xls", "csv", "tsv", "numbers" -> DocFamily.SHEET
    "docx", "doc", "pages", "rtf", "odt" -> DocFamily.DOC
    "pptx", "ppt", "key" -> DocFamily.SLIDE
    "pdf" -> DocFamily.PDF
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar" -> DocFamily.ZIP
    else -> DocFamily.BIN
}

/** Extensions the daemon serves as whole-or-nothing binary documents (SessionFilesService.documentTypes)
 *  — the set whose in-flight bytes earn the loading-card treatment instead of a bare spinner. */
private val documentExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip")

/** Family-tinted type tile with a mono extension and a folded top-right corner — the paper cue that
 *  reads "document object" rather than "sorry, can't show this". */
@Composable
private fun DocTypeBadge(ext: String, dense: Boolean) {
    val fam = docFamilyOf(ext)
    val shape = RoundedCornerShape(if (dense) 10.dp else 11.dp)
    Box(
        Modifier.size(if (dense) 44.dp else 52.dp).clip(shape).background(fam.bg).border(1.dp, fam.bd, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ext.take(4).uppercase().ifEmpty { "BIN" },
            color = fam.fg, fontFamily = FontFamily.Monospace,
            fontSize = if (dense) 11.sp else 12.5.sp, fontWeight = FontWeight.Bold,
        )
        // folded corner: base-coloured cut over a faint family-tint square (handoff ::before/::after)
        Canvas(Modifier.size(11.dp).align(Alignment.TopEnd)) {
            drawPath(
                Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); close() },
                Tok.base, alpha = 0.55f,
            )
            drawRect(fam.fg, alpha = 0.14f)
        }
    }
}

/** The document card's shared chrome — badge + single-line name over a state-specific body — centered
 *  in the viewer pane at the handoff's 352dp column width. */
@Composable
private fun DocCardFrame(path: String, dense: Boolean, body: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(if (dense) 11.dp else 13.dp)
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 352.dp).clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
                .padding(if (dense) 11.dp else 13.dp),
            horizontalArrangement = Arrangement.spacedBy(if (dense) 11.dp else 13.dp),
        ) {
            DocTypeBadge(path.substringAfterLast('.', "").lowercase(), dense)
            Column(Modifier.weight(1f)) {
                Text(
                    fileNameOf(path), color = Tok.tx, fontSize = if (dense) 13.5.sp else 14.5.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                body()
            }
        }
    }
}

/**
 * Card for a file with no inline rendering (xlsx/docx/pptx/pdf, arbitrary binaries): family-coded
 * badge, name, size, and the two platform gestures — native preview (QuickLook / default app) and
 * share/save. No home-grown office viewer, deliberately.
 */
@Composable
private fun DocumentCard(path: String, bytes: ByteArray, mediaType: String?, totalBytes: Long, dense: Boolean = false) {
    val name = fileNameOf(path)
    DocCardFrame(path, dense) {
        Text(
            "${formatFileSize(totalBytes)} · ${stringResource(Res.string.file_no_inline_preview)}",
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            Modifier.padding(top = if (dense) 9.dp else 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExportActionChip(
                stringResource(if (exportIsSaveDialog) Res.string.file_open else Res.string.file_preview),
                if (exportIsSaveDialog) Icons.Rounded.OpenInNew else Icons.Rounded.Visibility,
                primary = true, dense = dense,
            ) {
                // no native previewer for this type -> the share sheet still gets it somewhere useful
                if (!previewFile(name, bytes, mediaType)) shareFile(name, bytes, mediaType)
            }
            ExportActionChip(
                stringResource(if (exportIsSaveDialog) Res.string.file_save_as else Res.string.file_share),
                if (exportIsSaveDialog) Icons.Rounded.Download else Icons.Rounded.IosShare,
                primary = false, dense = dense,
            ) { shareFile(name, bytes, mediaType) }
        }
    }
}

/** The daemon refused the transfer over its binary cap: actions give way to a plain-language cap
 *  explanation that names the exact limit. Both sizes are parsed out of the error text — the only
 *  wire signal — so the note stays true to whatever cap THAT daemon runs (fallback: today's 1800 KB). */
@Composable
private fun DocumentTooLargeCard(path: String, error: String, dense: Boolean) {
    val sizes = remember(error) { Regex("(\\d+) KB").findAll(error).map { it.groupValues[1].toLong() }.toList() }
    DocCardFrame(path, dense) {
        Text(
            listOfNotNull(sizes.firstOrNull()?.let { formatFileSize(it * 1024) }, stringResource(Res.string.file_exceeds_cap))
                .joinToString(" · "),
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
        val noteShape = RoundedCornerShape(9.dp)
        Row(
            Modifier.padding(top = if (dense) 9.dp else 11.dp).fillMaxWidth().clip(noteShape)
                .background(Tok.warn.copy(alpha = 0.07f)).border(1.dp, Tok.warn.copy(alpha = 0.28f), noteShape)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⚠", color = Tok.warn, fontSize = 12.sp)
            Text(
                stringResource(Res.string.file_too_large_note, "${sizes.getOrNull(1) ?: 1800L} KB"),
                color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

/** Bytes in flight. The transfer is a single relay frame — no true progress crosses the wire — so
 *  the bar sweeps instead of pretending to a byte count. */
@Composable
private fun DocumentLoadingCard(path: String, dense: Boolean) {
    DocCardFrame(path, dense) {
        Text(
            stringResource(Res.string.file_transferring),
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        val sweep by rememberInfiniteTransition().animateFloat(
            initialValue = 0.18f, targetValue = 0.82f,
            animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        )
        Box(
            Modifier.padding(top = 12.dp).fillMaxWidth().height(5.dp)
                .clip(RoundedCornerShape(999.dp)).background(Tok.raised),
        ) {
            Box(Modifier.fillMaxWidth(sweep).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(Tok.accent))
        }
    }
}

/** Preview/Open (accent-filled) and Save As/Share (hairline ghost) — 34dp chips, icon + label, with
 *  a desktop hover lift (pressed accent / raised border+ink). */
@Composable
private fun ExportActionChip(label: String, icon: ImageVector, primary: Boolean, dense: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val fg = if (primary) Tok.base else if (hovered) Tok.tx else Tok.tx2
    Row(
        Modifier.height(if (dense) 30.dp else 34.dp).clip(shape)
            .then(
                if (primary) Modifier.background(if (hovered) Tok.accentPressed else Tok.accent)
                else Modifier.border(1.dp, if (hovered) Tok.muted else Tok.hair, shape),
            )
            .hoverable(src).clickable(onClick = onClick)
            .padding(horizontal = if (dense) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(if (dense) 13.dp else 14.dp))
        Text(label, color = fg, fontSize = if (dense) 12.sp else 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
