package dev.ccpocket.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ImgState
import dev.ccpocket.app.data.PendingImage
import dev.ccpocket.app.media.decodeImageBitmap
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/** Decode once per distinct byte array; cached across recompositions. */
@Composable
fun rememberImageBitmap(bytes: ByteArray): ImageBitmap? = remember(bytes) { decodeImageBitmap(bytes) }

/** The attached-images tray above the composer input — thumbnails + remove + rejected state + counter. */
@Composable
fun AttachTray(pending: List<PendingImage>, onRemove: (Long) -> Unit) {
    if (pending.isEmpty()) return
    val rejected = pending.count { it.state == ImgState.Rejected }
    val valid = pending.size - rejected // ready + still-compressing
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 11.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            pending.forEach { img -> TrayThumb(img) { onRemove(img.id) } }
        }
        Row(
            Modifier.padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.img_counter, valid), color = Tok.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            if (rejected > 0) Text(stringResource(Res.string.img_excluded, rejected), color = Tok.danger, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TrayThumb(img: PendingImage, onRemove: () -> Unit) {
    val rejected = img.state == ImgState.Rejected
    val compressing = img.state == ImgState.Compressing
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp)) {
            val shape = RoundedCornerShape(10.dp)
            val bmp = rememberImageBitmap(img.bytes)
            Box(
                Modifier.matchParentSize().clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape)
                    .alpha(if (rejected) 0.4f else 1f),
            ) {
                if (bmp != null) Image(bmp, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            if (compressing) {
                Box(
                    Modifier.matchParentSize().clip(shape).background(Color(0x52080A0A)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp), color = Tok.accent, strokeWidth = 2.4.dp) }
            }
            if (rejected) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp).clip(CircleShape).background(Tok.danger),
                    contentAlignment = Alignment.Center,
                ) { BangGlyph(Tok.base, 9.dp) }
            }
            if (!compressing) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Tok.raised)
                        .border(1.dp, Tok.hair, CircleShape).clickable { onRemove() },
                    contentAlignment = Alignment.Center,
                ) { XGlyph(Tok.tx2, 9.dp) }
            }
        }
        if (rejected) {
            Text(stringResource(Res.string.img_too_large, img.bytes.size / 1024), color = Tok.danger, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Image group inside a sent user turn: 1 natural-aspect tile, or a 2-up / 2×2 grid. Tapping opens the viewer. */
@Composable
fun SentImages(images: List<ByteArray>, onOpen: (Int) -> Unit) {
    val n = images.size
    if (n == 0) return
    if (n == 1) {
        val bmp = rememberImageBitmap(images[0])
        val ar = bmp?.let { if (it.height > 0) it.width.toFloat() / it.height else 4f / 3f } ?: 4f / 3f
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.fillMaxWidth(0.62f).widthIn(max = 240.dp).aspectRatio(ar).clip(shape)
                .background(Tok.raised).border(1.dp, Tok.hair, shape).clickable { onOpen(0) },
        ) { if (bmp != null) Image(bmp, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        return
    }
    val frac = if (n == 2) 0.78f else 0.70f
    val maxW = if (n == 2) 300.dp else 260.dp
    Column(Modifier.fillMaxWidth(frac).widthIn(max = maxW), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.chunked(2).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, bytes ->
                    val idx = rowIdx * 2 + i
                    val shape = RoundedCornerShape(10.dp)
                    val bmp = rememberImageBitmap(bytes)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(shape).background(Tok.raised)
                            .border(1.dp, Tok.hair, shape).clickable { onOpen(idx) },
                    ) { if (bmp != null) Image(bmp, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Full-screen image viewer: swipe between, swipe down to dismiss, tap to zoom. */
@Composable
fun ImageViewer(images: List<ByteArray>, startIndex: Int, onClose: () -> Unit) {
    if (images.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.size - 1)) { images.size }
    var dragY by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF08090A))
            .graphicsLayer { translationY = dragY; alpha = 1f - (dragY / 900f).coerceIn(0f, 0.55f) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, d -> dragY = (dragY + d).coerceAtLeast(0f) },
                    onDragEnd = { if (dragY > 90f) onClose() else dragY = 0f },
                    onDragCancel = { dragY = 0f },
                )
            },
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            var zoom by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (zoom) 1.25f else 1f, label = "zoom")
            val bmp = rememberImageBitmap(images[page])
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    val ar = if (bmp.height > 0) bmp.width.toFloat() / bmp.height else 4f / 3f
                    val shape = RoundedCornerShape(14.dp)
                    Image(
                        bmp, null,
                        Modifier.fillMaxWidth(0.92f).aspectRatio(ar).graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(shape).border(1.dp, Tok.hair, shape).clickable { zoom = !zoom },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 50.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                XGlyph(Tok.tx, 18.dp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (images.size > 1) {
                    Text("${pager.currentPage + 1} / ${images.size}", color = Tok.tx2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.size(44.dp))
        }
        if (images.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.indices.forEach { i ->
                    val cur = i == pager.currentPage
                    Box(Modifier.size(width = if (cur) 18.dp else 6.dp, height = 6.dp).clip(CircleShape).background(if (cur) Tok.accent else Tok.hair))
                }
            }
        }
        Text(
            stringResource(Res.string.viewer_hint),
            color = Tok.muted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

/** A crisp, geometrically-centered ✕ (two diagonal strokes) — a font glyph sits off-center in a small circle. */
@Composable
private fun XGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val p = w * 0.22f
        val sw = (w * 0.15f).coerceAtLeast(1f)
        drawLine(color, Offset(p, p), Offset(w - p, w - p), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w - p, p), Offset(p, w - p), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** A centered exclamation mark (stem + dot), matching the spec's warn badge. */
@Composable
private fun BangGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 10f
        drawLine(color, Offset(5 * u, 2 * u), Offset(5 * u, 5.6f * u), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
        drawCircle(color, radius = 0.95f * u, center = Offset(5 * u, 7.7f * u))
    }
}
