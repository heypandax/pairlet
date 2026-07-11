package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.path_copied
import dev.ccpocket.app.resources.path_copy
import dev.ccpocket.app.resources.path_cwd_hint
import dev.ccpocket.app.resources.path_open
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
private fun ActSheet(item: LinkEntity, copyL: String, openL: String, hintL: String, onTake: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp)) {
            Text(item.copyValue, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
            if (item.kind == EntityKind.COPY && isRel(item.display))
                Text(hintL, color = Tok.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().clickable { onTake() }.padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Rounded.ContentCopy, null, tint = Tok.tx2, modifier = Modifier.size(20.dp))
            Text(copyL, color = Tok.tx, fontSize = 16.sp)
        }
        if (item.kind == EntityKind.URL) Row(
            Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Tok.tx2, modifier = Modifier.size(20.dp))
            Text(openL, color = Tok.tx, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Bubble(value: String, done: Boolean, doneLabel: String, onAct: () -> Unit, onHover: (Boolean) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hov by src.collectIsHoveredAsState()
    LaunchedEffect(hov) { onHover(hov) }
    Row(
        Modifier.hoverable(src).widthIn(max = 470.dp).background(Tok.raised, RoundedCornerShape(8.dp))
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(start = 11.dp, top = 6.dp, end = 7.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(value, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
        Row(
            Modifier.clickable { onAct() }.padding(horizontal = 3.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(14.dp))
                Text(doneLabel, color = Tok.ok, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            } else Icon(Icons.Rounded.ContentCopy, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun Pill(label: String) {
    val gap = with(LocalDensity.current) { 64.dp.roundToPx() }
    Popup(popupPositionProvider = BottomPP(gap), properties = PopupProperties(focusable = false)) {
        Row(
            Modifier.height(40.dp).background(Tok.raised, RoundedCornerShape(999.dp))
                .border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(16.dp))
            Text(label, color = Tok.ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private class AbovePP(val dx: Int, val topPx: Int, val botPx: Int, val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchor: IntRect, window: IntSize, dir: LayoutDirection, size: IntSize): IntOffset {
        val x = (anchor.left + dx).coerceIn(0, maxOf(0, window.width - size.width))
        val up = anchor.top + topPx - gap - size.height
        return IntOffset(x, if (up >= 0) up else anchor.top + botPx + gap)
    }
}

private class BottomPP(val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchor: IntRect, window: IntSize, dir: LayoutDirection, size: IntSize): IntOffset =
        IntOffset((window.width - size.width) / 2, window.height - size.height - gap)
}

private fun hitOf(lr: TextLayoutResult?, items: List<LinkEntity>, p: Offset): LinkEntity? {
    if (lr == null || p.y < 0f || p.y > lr.size.height) return null
    val o = lr.getOffsetForPosition(p)
    val e = items.firstOrNull { o >= it.start && o < it.end } ?: return null
    val r = runCatching { lr.getBoundingBox(o) }.getOrNull() ?: return e
    val ok = p.x >= r.left - 2f && p.x <= r.right + 2f && p.y >= r.top && p.y <= r.bottom
    return if (ok) e else null
}

private fun isRel(s: String): Boolean =
    !s.startsWith('/') && !s.startsWith('~') && !(s.length >= 2 && s[1] == ':')

private fun styled(src: AnnotatedString, items: List<LinkEntity>, onOpen: (LinkEntity) -> Unit, onTake: (LinkEntity) -> Unit): AnnotatedString {
    if (items.isEmpty()) return src
    val solid = TextLinkStyles(SpanStyle(color = Tok.info, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        append(src)
        for (e in items) {
            if (e.kind == EntityKind.COPY) addLink(LinkAnnotation.Clickable("p", null) { onTake(e) }, e.start, e.end)
            else addLink(LinkAnnotation.Clickable("p", solid) { onOpen(e) }, e.start, e.end)
        }
    }
}

@Composable
fun LinkifiedText(
    source: AnnotatedString, color: Color, modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null, lineHeight: TextUnit = TextUnit.Unspecified,
) {
    val opener = LocalPathOpener.current
    val cwd = LocalPathCwd.current
    val items = remember(source.text, opener, cwd) { recognizeEntities(source.text, cwd) { opener?.exists(it) == true } }
    if (items.isEmpty()) {
        Text(source, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, modifier = modifier)
        return
    }
    val (done, put) = rememberCopied()
    var mouse by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<LinkEntity?>(null) }
    var overChip by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<LinkEntity?>(null) }
    var sheet by remember { mutableStateOf<LinkEntity?>(null) }
    var lay by remember { mutableStateOf<TextLayoutResult?>(null) }
    val open: (LinkEntity) -> Unit = { e -> if (e.kind == EntityKind.URL) openWebUrl(e.copyValue) else opener?.open(e.display) }
    val take: (LinkEntity) -> Unit = { e -> put(e.copyValue); if (mouse) shown = e }
    val styledText = remember(source, items) { styled(source, items, open, take) }
    val muted = Tok.muted
    LaunchedEffect(target) { if (target != null) { delay(350); shown = target } }
    LaunchedEffect(target, overChip) { if (target == null && !overChip) { delay(150); if (target == null && !overChip) shown = null } }
    Box {
        Text(
            styledText, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight,
            onTextLayout = { lay = it },
            modifier = modifier
                .drawBehind {
                    val lr = lay ?: return@drawBehind
                    val fx = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
                    val w = 1.5.dp.toPx()
                    for (e in items) if (e.kind == EntityKind.COPY) {
                        val a = lr.getLineForOffset(e.start)
                        val b = lr.getLineForOffset(e.end)
                        for (ln in a..b) {
                            val x0 = if (ln == a) lr.getHorizontalPosition(e.start, true) else lr.getLineLeft(ln)
                            val x1 = if (ln == b) lr.getHorizontalPosition(e.end, true) else lr.getLineRight(ln)
                            val y = lr.getLineBottom(ln) - w
                            drawLine(muted, Offset(x0, y), Offset(x1, y), w, StrokeCap.Round, fx)
                        }
                    }
                }
                .pointerInput(items) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: continue
                            if (ch.type == PointerType.Mouse) {
                                mouse = true
                                target = if (ev.type == PointerEventType.Exit || ch.pressed) null else hitOf(lay, items, ch.position)
                            }
                        }
                    }
                }
                .pointerInput(items) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        if (d.type == PointerType.Touch) {
                            val e = hitOf(lay, items, d.position)
                            val lp = if (e != null) awaitLongPressOrCancellation(d.id) else null
                            if (lp != null && e != null) { lp.consume(); sheet = e }
                        }
                    }
                },
        )
        val cur = shown
        val lr = lay
        if (cur != null && lr != null) {
            val box = runCatching { lr.getBoundingBox(cur.start) }.getOrNull()
            if (box != null) {
                val g = with(LocalDensity.current) { 8.dp.roundToPx() }
                Popup(AbovePP(box.left.toInt(), box.top.toInt(), box.bottom.toInt(), g), properties = PopupProperties(focusable = false)) {
                    Bubble(cur.copyValue, done, stringResource(Res.string.path_copied), { take(cur) }, { overChip = it })
                }
            }
        }
        val sh = sheet
        if (sh != null) ActSheet(
            sh, stringResource(Res.string.path_copy), stringResource(Res.string.path_open), stringResource(Res.string.path_cwd_hint),
            { take(sh); sheet = null }, { open(sh); sheet = null }, { sheet = null },
        )
        if (done && !mouse) Pill(stringResource(Res.string.path_copied))
    }
}
