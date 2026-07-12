package dev.ccpocket.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.SentFile
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

// ============================================================================================
//  File attachments (issue #90) — shared bits of the composer upload flow + sent-message chips.
//  Pixel source: docs/design/claude-design-handoff/attachments/ (file-attach.jsx / sent-attach.jsx).
// ============================================================================================

// ---- helpers -------------------------------------------------------------------------------

/** "Q3-…rt.pdf" — middle truncation that keeps the extension end readable (design: head 3 / tail 5). */
fun middleTrunc(name: String, head: Int = 3, tail: Int = 5): String =
    if (name.length <= head + tail + 1) name else name.take(head) + "…" + name.takeLast(tail)

/** "2.4 MB" (1 decimal under 10 MB) / "812 KB" — the design's fmtSize, from raw bytes. */
fun fmtSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 10 -> "${mb.toInt()} MB"
        mb >= 1 -> "${(kotlin.math.round(mb * 10) / 10)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(if (bytes > 0) 1 else 0)} KB"
    }
}

/** Which glyph a file gets: video (film strip), table (csv/sheets), code, or the generic document. */
enum class FileGlyphKind { Doc, Table, Code, Video }

/** Video extensions that render as the film glyph / video card (issue #98). Kept beside [fileGlyphKind]
 *  so the sent-card branch and the glyph pick can't drift on the list. */
private val VIDEO_EXTS = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "hevc", "3gp", "3g2", "mpg", "mpeg", "wmv", "flv", "ogv")

fun fileGlyphKind(name: String): FileGlyphKind = when (name.substringAfterLast('.', "").lowercase()) {
    in VIDEO_EXTS -> FileGlyphKind.Video
    "csv", "tsv", "xls", "xlsx", "numbers", "parquet" -> FileGlyphKind.Table
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "rs", "go", "rb",
    "sh", "zsh", "swift", "json", "yaml", "yml", "toml", "xml", "html", "css", "sql", "gradle", "log",
    -> FileGlyphKind.Code
    else -> FileGlyphKind.Doc
}

/** Whether a delivered file should render as a video card (issue #98): the daemon-reported MIME wins,
 *  falling back to the extension so a blank/`application/octet-stream` mediaType still routes `.mov`
 *  correctly. One predicate for every render site (mobile card, desktop thumb) so they can't diverge. */
fun isVideoAttachment(mediaType: String, name: String): Boolean =
    mediaType.startsWith("video/") || fileGlyphKind(name) == FileGlyphKind.Video

/** "0:42" / "1:05:07" — mm:ss (hh:mm:ss past an hour) for the duration pill (design: sent-attach.jsx mmss). */
fun mmss(totalSecs: Int): String {
    val s = totalSecs.coerceAtLeast(0)
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return if (hh > 0) "$hh:${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
    else "$mm:${ss.toString().padStart(2, '0')}"
}

@Composable
fun glyphFor(kind: FileGlyphKind): ImageVector = when (kind) {
    FileGlyphKind.Doc -> DocFileGlyph
    FileGlyphKind.Table -> TableFileGlyph
    FileGlyphKind.Code -> CodeFileGlyph
    FileGlyphKind.Video -> FilmFileGlyph
}

// ---- glyphs (1.5pt line, verbatim from the design SVGs) --------------------------------------

private fun strokedIcon(name: String, viewport: Float, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = viewport.dp, defaultHeight = viewport.dp, viewportWidth = viewport, viewportHeight = viewport)
        .apply(block).build()

private fun ImageVector.Builder.strokedLine(width: Float = 1.5f, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(stroke = SolidColor(Color.White), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

/** Generic document: page with folded corner + two text lines. */
val DocFileGlyph: ImageVector by lazy {
    strokedIcon("DocFile", 24f) {
        strokedLine {
            moveTo(14f, 3f); horizontalLineTo(7f)
            arcTo(2f, 2f, 0f, false, false, 5f, 5f); verticalLineTo(19f)
            arcTo(2f, 2f, 0f, false, false, 7f, 21f); horizontalLineTo(17f)
            arcTo(2f, 2f, 0f, false, false, 19f, 19f); verticalLineTo(8f); close()
        }
        strokedLine { moveTo(14f, 3f); verticalLineTo(8f); horizontalLineTo(19f) }
        strokedLine { moveTo(8.5f, 13f); horizontalLineTo(15.5f); moveTo(8.5f, 16.5f); horizontalLineTo(13.5f) }
    }
}

/** Spreadsheet: rounded table with a header row + first column. */
val TableFileGlyph: ImageVector by lazy {
    strokedIcon("TableFile", 24f) {
        strokedLine {
            moveTo(6f, 5f); horizontalLineTo(18f)
            arcTo(2f, 2f, 0f, false, true, 20f, 7f); verticalLineTo(17f)
            arcTo(2f, 2f, 0f, false, true, 18f, 19f); horizontalLineTo(6f)
            arcTo(2f, 2f, 0f, false, true, 4f, 17f); verticalLineTo(7f)
            arcTo(2f, 2f, 0f, false, true, 6f, 5f); close()
        }
        strokedLine { moveTo(4f, 9.5f); horizontalLineTo(20f); moveTo(4f, 14f); horizontalLineTo(20f); moveTo(9.5f, 5f); verticalLineTo(19f) }
    }
}

/** Code: chevrons. */
val CodeFileGlyph: ImageVector by lazy {
    strokedIcon("CodeFile", 24f) {
        strokedLine { moveTo(9f, 8f); lineTo(5f, 12f); lineTo(9f, 16f); moveTo(15f, 8f); lineTo(19f, 12f); lineTo(15f, 16f) }
    }
}

/** Video: film strip with sprocket rows (design: sent-attach.jsx FilmGlyph). */
val FilmFileGlyph: ImageVector by lazy {
    strokedIcon("FilmFile", 24f) {
        strokedLine {
            moveTo(6f, 5f); horizontalLineTo(18f)
            arcTo(2.5f, 2.5f, 0f, false, true, 20.5f, 7.5f); verticalLineTo(16.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 18f, 19f); horizontalLineTo(6f)
            arcTo(2.5f, 2.5f, 0f, false, true, 3.5f, 16.5f); verticalLineTo(7.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6f, 5f); close()
        }
        strokedLine {
            moveTo(8f, 5f); verticalLineTo(19f); moveTo(16f, 5f); verticalLineTo(19f)
            moveTo(3.5f, 9.5f); horizontalLineTo(8f); moveTo(16f, 9.5f); horizontalLineTo(20.5f)
            moveTo(3.5f, 14.5f); horizontalLineTo(8f); moveTo(16f, 14.5f); horizontalLineTo(20.5f)
        }
    }
}

/** Solid play triangle for the video card's center glyph + the player controls (design: PlayTri). */
val PlayTriangleGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "PlayTri", defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f)
        .apply { path(fill = SolidColor(Color.White)) { moveTo(6f, 3.5f); lineTo(17f, 10f); lineTo(6f, 16.5f); close() } }
        .build()
}

/** Two bars — pause, for the full-screen player's toggle (design: PauseGlyph). */
val PauseBarsGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "PauseBars", defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f)
        .apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(4.5f, 3.5f); horizontalLineTo(8.7f); verticalLineTo(16.5f); horizontalLineTo(4.5f); close()
                moveTo(11.3f, 3.5f); horizontalLineTo(15.5f); verticalLineTo(16.5f); horizontalLineTo(11.3f); close()
            }
        }.build()
}

/** Attach-sheet "Video" option: framed landscape with a center play triangle (26-grid, issue #98). */
val VideoOptGlyph: ImageVector by lazy {
    strokedIcon("VideoOpt", 26f) {
        strokedLine {
            moveTo(6.5f, 5f); horizontalLineTo(19.5f)
            arcTo(3f, 3f, 0f, false, true, 22.5f, 8f); verticalLineTo(18f)
            arcTo(3f, 3f, 0f, false, true, 19.5f, 21f); horizontalLineTo(6.5f)
            arcTo(3f, 3f, 0f, false, true, 3.5f, 18f); verticalLineTo(8f)
            arcTo(3f, 3f, 0f, false, true, 6.5f, 5f); close()
        }
        path(fill = SolidColor(Color.White)) { moveTo(11f, 9.5f); lineTo(16.5f, 13f); lineTo(11f, 16.5f); close() }
    }
}

/** Attach-sheet "File" option: page with folded corner (26-grid like the photo option). */
val FileOptGlyph: ImageVector by lazy {
    strokedIcon("FileOpt", 26f) {
        strokedLine {
            moveTo(15f, 3.5f); horizontalLineTo(8f)
            arcTo(2.5f, 2.5f, 0f, false, false, 5.5f, 6f); verticalLineTo(20f)
            arcTo(2.5f, 2.5f, 0f, false, false, 8f, 22.5f); horizontalLineTo(18f)
            arcTo(2.5f, 2.5f, 0f, false, false, 20.5f, 20f); verticalLineTo(9f); close()
        }
        strokedLine { moveTo(15f, 3.5f); verticalLineTo(9f); horizontalLineTo(20.5f) }
    }
}

/** Attach-sheet "Photo" option (26-grid framed landscape). */
val PhotoOptGlyph: ImageVector by lazy {
    strokedIcon("PhotoOpt", 26f) {
        strokedLine {
            moveTo(6.5f, 4.5f); horizontalLineTo(19.5f)
            arcTo(3f, 3f, 0f, false, true, 22.5f, 7.5f); verticalLineTo(18.5f)
            arcTo(3f, 3f, 0f, false, true, 19.5f, 21.5f); horizontalLineTo(6.5f)
            arcTo(3f, 3f, 0f, false, true, 3.5f, 18.5f); verticalLineTo(7.5f)
            arcTo(3f, 3f, 0f, false, true, 6.5f, 4.5f); close()
        }
        strokedLine {
            moveTo(7.5f, 10f)
            arcTo(1.7f, 1.7f, 0f, false, true, 10.9f, 10f)
            arcTo(1.7f, 1.7f, 0f, false, true, 7.5f, 10f); close()
        }
        strokedLine { moveTo(4f, 18.5f); lineTo(9f, 13.5f); lineTo(12.3f, 16.8f); lineTo(15.8f, 13f); lineTo(21.5f, 19f) }
    }
}

/** Small inbox/tray glyph for the sheet caption. */
val InboxGlyph: ImageVector by lazy {
    strokedIcon("Inbox", 16f) {
        strokedLine(1.4f) { moveTo(2.5f, 9.5f); lineTo(4f, 3.5f); horizontalLineTo(12f); lineTo(13.5f, 9.5f) }
        strokedLine(1.4f) {
            moveTo(2.5f, 9.5f); verticalLineTo(12.5f); horizontalLineTo(13.5f); verticalLineTo(9.5f)
            horizontalLineTo(10.5f)
            arcTo(2f, 2f, 0f, false, true, 6.5f, 9.5f); close()
        }
    }
}

/** Retry arrow (design's ↻, 16-grid). */
val RetryGlyph: ImageVector by lazy {
    strokedIcon("Retry", 16f) {
        strokedLine(1.6f) {
            moveTo(13f, 8f)
            arcTo(5f, 5f, 0f, true, true, 11.4f, 4.3f)
        }
        strokedLine(1.6f) { moveTo(13f, 2.4f); verticalLineTo(5.2f); horizontalLineTo(10.2f) }
    }
}

/** ✓ (11-grid mini check for the delivered caption). */
val CheckMiniGlyph: ImageVector by lazy {
    strokedIcon("CheckMini", 12f) {
        strokedLine(1.6f) { moveTo(2.5f, 6.3f); lineTo(4.9f, 8.7f); lineTo(9.6f, 3.5f) }
    }
}

// ---- progress ring around a file-type glyph (pending chip) ----------------------------------

/** Indeterminate spinner arc (30% sweep, rotating) — the strip header + waiting send button. */
@Composable
fun SpinnerRing(size: androidx.compose.ui.unit.Dp, stroke: androidx.compose.ui.unit.Dp = 2.dp, color: Color = Tok.accent) {
    val angle by rememberInfiniteTransition().animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        val inset = sw / 2
        val arc = Size(this.size.width - sw, this.size.height - sw)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(sw))
        drawArc(color, angle, 108f, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Determinate progress ring wrapping the file-type glyph: solid track (dashed while queued),
 *  accent arc for progress, danger + retry glyph when failed. Design: 30px ring, 2.4 stroke. */
@Composable
fun ChipRing(f: PendingFile, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val failed = f.state == FileUpState.Failed
    val queued = f.state == FileUpState.Queued
    val sweep by animateFloatAsState(
        when (f.state) {
            FileUpState.Uploading -> f.progress
            FileUpState.Landed -> 1f
            FileUpState.Failed -> 1f
            FileUpState.Queued -> 0f
        },
        label = "chipRing",
    )
    val on = if (failed) Tok.danger else Tok.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = 2.4.dp.toPx()
            val inset = sw / 2
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(
                Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arc,
                style = Stroke(sw, pathEffect = if (queued) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())) else null),
            )
            if (sweep > 0f) {
                drawArc(on, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        if (failed) {
            Icon(RetryGlyph, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
        } else {
            Icon(
                glyphFor(fileGlyphKind(f.name)), null,
                tint = if (queued) Tok.muted else Tok.tx,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// ---- one pending chip + the strip (mobile composer) ------------------------------------------

/** One staged-file chip: ring + middle-truncated name + state caption; ✕ badge cancels/dismisses,
 *  tapping a failed chip retries. Design: 117px wide, 12 radius, danger tint on failure. */
@Composable
fun PendingFileChip(f: PendingFile, onCancel: () -> Unit, onRetry: () -> Unit) {
    val failed = f.state == FileUpState.Failed
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.padding(top = 5.dp, end = 4.dp)) {
        Row(
            Modifier.width(117.dp).clip(shape)
                .background(if (failed) Tok.danger.copy(alpha = 0.08f) else Tok.raised)
                .border(1.dp, if (failed) Tok.danger.copy(alpha = 0.4f) else Tok.hair, shape)
                .clickable(enabled = failed) { onRetry() }
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChipRing(f)
            Column(Modifier.weight(1f)) {
                Text(
                    middleTrunc(f.name),
                    color = if (failed) Tok.danger else Tok.tx,
                    fontSize = 11.5.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                )
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (failed) Icon(RetryGlyph, null, tint = Tok.danger, modifier = Modifier.size(10.dp))
                    Text(
                        when (f.state) {
                            FileUpState.Uploading -> "${(f.progress * 100).toInt()}% · ${fmtSize(f.size)}"
                            FileUpState.Queued -> "${stringResource(Res.string.file_queued)} · ${fmtSize(f.size)}"
                            FileUpState.Landed -> "✓ ${fmtSize(f.size)}"
                            FileUpState.Failed -> stringResource(Res.string.file_retry)
                        },
                        color = if (failed) Tok.danger else Tok.muted,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false,
                    )
                }
            }
        }
        // cancel / dismiss badge, floated over the top-right corner
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).size(22.dp)
                .clip(CircleShape).background(Tok.raised).border(1.dp, Tok.hair, CircleShape)
                .clickable { onCancel() },
            contentAlignment = Alignment.Center,
        ) { XGlyphSmall(Tok.tx2) }
    }
}

@Composable
private fun XGlyphSmall(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val w = size.minDimension
        val p = w * 0.18f
        drawLine(color, Offset(p, p), Offset(w - p, w - p), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(w - p, p), Offset(p, w - p), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** The staged-files strip above the input row: "uploading N of M…" header + scrollable chips. */
@Composable
fun PendingFilesStrip(files: List<PendingFile>, onCancel: (Long) -> Unit, onRetry: (Long) -> Unit) {
    if (files.isEmpty()) return
    val active = files.count { it.state == FileUpState.Uploading || it.state == FileUpState.Queued }
    val landed = files.count { it.state == FileUpState.Landed }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(start = 2.dp, bottom = 9.dp)) {
            if (active > 0) {
                SpinnerRing(14.dp)
                Text(
                    stringResource(Res.string.file_strip_uploading, active, files.size),
                    color = Tok.tx2, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            } else if (landed > 0) {
                Icon(CheckMiniGlyph, null, tint = Tok.ok, modifier = Modifier.size(11.dp))
                Text(
                    stringResource(Res.string.file_strip_ready, landed),
                    color = Tok.tx2, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(stringResource(Res.string.file_upload_failed), color = Tok.danger, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            files.forEach { f -> PendingFileChip(f, onCancel = { onCancel(f.id) }, onRetry = { onRetry(f.id) }) }
        }
    }
}

// ---- attach sheet (anchored above the composer) ----------------------------------------------

/** The composer's attach sheet: Photo / File / Video options + the workspace caption. Video (issue
 *  #98) picks movie files that ride the SAME chunk-upload as any file — they land in the workspace
 *  inbox, not the model message; the row is data-driven so the three sit evenly. */
@Composable
fun AttachSheet(onPhoto: () -> Unit, onFile: () -> Unit, onVideo: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)
            .clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AttachOption(PhotoOptGlyph, stringResource(Res.string.attach_sheet_photo), Modifier.weight(1f), onPhoto)
            AttachOption(FileOptGlyph, stringResource(Res.string.attach_sheet_file), Modifier.weight(1f), onFile)
            AttachOption(VideoOptGlyph, stringResource(Res.string.attach_sheet_video), Modifier.weight(1f), onVideo)
        }
        Row(
            Modifier.padding(top = 12.dp, start = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(InboxGlyph, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
            Text(
                stringResource(Res.string.attach_sheet_caption),
                color = Tok.tx2, fontSize = 11.5.sp, lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun AttachOption(glyph: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.height(80.dp).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(glyph, null, tint = Tok.tx, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(9.dp))
        Text(label, color = Tok.tx, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- sent-message file chip (message stream) --------------------------------------------------

/** The `@path` reference line: accent head segment (`.ccpocket/`), secondary rest. */
@Composable
fun PathRefText(path: String, fontSize: androidx.compose.ui.unit.TextUnit = 11.sp) {
    val slash = path.indexOf('/').takeIf { it >= 0 } ?: path.indexOf('\\')
    val head = if (slash >= 0) path.take(slash + 1) else path
    val rest = if (slash >= 0) path.drop(slash + 1) else ""
    Row {
        Text("@$head", color = Tok.accent, fontSize = fontSize, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
        Text(rest, color = Tok.tx2, fontSize = fontSize, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
    }
}

/** A delivered file inside a sent user turn: glyph tile + name + "✓ size · in workspace" + the
 *  mono `@inbox` reference footer the agent reads it by. Design: sent-attach.jsx FileChipSent. */
@Composable
fun SentFileChip(file: SentFile) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.widthIn(max = 280.dp).clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)) {
        Row(
            Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(glyphFor(fileGlyphKind(file.name)), null, tint = Tok.tx2, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    file.name, color = Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(CheckMiniGlyph, null, tint = Tok.ok, modifier = Modifier.size(11.dp))
                    Text(
                        "${fmtSize(file.size)} · ${stringResource(Res.string.file_in_workspace)}",
                        color = Tok.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().background(Tok.tx.copy(alpha = 0.02f)).padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(Res.string.file_agent_reads), color = Tok.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            PathRefText(file.path)
        }
    }
}

// ---- sent-message video card + poster frame (issue #98) --------------------------------------

/** The warm 16:9 poster gradient the design uses in place of a real thumbnail. v1 ships this
 *  placeholder (no client-side frame extraction) — see the video card doc for the rationale. */
val videoPosterBrush: Brush
    get() = Brush.linearGradient(listOf(Color(0xFF1B1410), Color(0xFF3A2A20), Color(0xFF7A5238)))

/** 16:9 poster frame: gradient placeholder + a centered translucent play button + an optional
 *  duration pill. Shared by the sent card and the full-screen player. [glyphSize]/[buttonSize] scale
 *  it up for the player. */
@Composable
fun VideoPoster(
    modifier: Modifier = Modifier,
    durationSecs: Int? = null,
    buttonSize: androidx.compose.ui.unit.Dp = 52.dp,
    glyphSize: androidx.compose.ui.unit.Dp = 22.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(cornerRadius)).background(videoPosterBrush)) {
        Box(
            Modifier.align(Alignment.Center).size(buttonSize).clip(CircleShape)
                .background(Color(0xFF0C0A09).copy(alpha = 0.5f)).border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(PlayTriangleGlyph, null, tint = Color.White, modifier = Modifier.size(glyphSize)) }
        if (durationSecs != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0C0A09).copy(alpha = 0.66f)).padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(mmss(durationSecs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** A delivered VIDEO inside a sent user turn (design: sent-attach.jsx VideoCardSent): a tappable 16:9
 *  poster (placeholder thumbnail + play glyph + optional duration), then the mono filename +
 *  "✓ size · in workspace", then the `@inbox` path the agent reads it by. Tap opens the player. The
 *  video never enters the model message — like every upload it lands in the session workspace, so the
 *  caption grammar and `@inbox` footer match the file chip exactly. */
@Composable
fun SentVideoCard(file: SentFile, onOpen: () -> Unit) {
    Column(Modifier.widthIn(max = 280.dp)) {
        VideoPoster(
            Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable { onOpen() },
            durationSecs = file.durationSecs,
        )
        Row(
            Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                file.name, color = Tok.tx, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(CheckMiniGlyph, null, tint = Tok.ok, modifier = Modifier.size(11.dp))
            Text(
                "${fmtSize(file.size)} · ${stringResource(Res.string.file_in_workspace)}",
                color = Tok.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            )
        }
        Box(Modifier.padding(top = 4.dp)) { PathRefText(file.path) }
    }
}

/** The composer "+" that rotates to "×" while the attach sheet is open (design: 45° spin). */
@Composable
fun AttachPlusGlyph(open: Boolean, tint: Color) {
    val rotation by animateFloatAsState(if (open) 45f else 0f, label = "attachPlus")
    Canvas(Modifier.size(24.dp).rotate(rotation)) {
        val w = size.minDimension
        val c = w / 2
        val arm = w * 0.29f
        val sw = 1.9.dp.toPx()
        drawLine(tint, Offset(c, c - arm), Offset(c, c + arm), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(c - arm, c), Offset(c + arm, c), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
