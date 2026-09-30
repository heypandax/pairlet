package dev.ccpocket.app.ui.memo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.localClock
import dev.ccpocket.app.memo.MemoReadiness
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.BackTarget
import dev.ccpocket.app.ui.LocalReduceMotion
import dev.ccpocket.app.ui.entry.EntryPrimaryButton
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateMarkGlyph
import org.jetbrains.compose.resources.stringResource

// Shared chrome for the voice-memo surfaces (docs/design/claude-design-handoff/voice-memo-tasks/README.md).
//
// Colours are the app's own Tok slots — the handoff's palette table is a cross-check, not a second palette:
// ink → tx, ink3/ink4 → tx2, ink5 → muted, line → hair, surf → surface, acc → accent, ok/attention/danger →
// ok/warn/danger. Every text style is built on [tightCenter] (AGENTS.md): each keeps LineHeightStyle
// Center + Trim.None and only widens the line box, so a state mark placed in a box one line tall lines up
// with the first line of the text beside it on every platform font.

/** Page gutter: 20 dp, 16 dp on a 320-wide phone (handoff "边距"). Provided once by [VoiceMemoScreen]. */
internal val LocalMemoGutter = staticCompositionLocalOf { 20.dp }

private fun memoText(size: Float, leading: Float, weight: FontWeight = FontWeight.Normal, mono: Boolean = false): TextStyle =
    tightCenter(size.sp).merge(
        TextStyle(
            fontSize = size.sp,
            lineHeight = (size * leading).sp,
            fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else null,
        ),
    )

/** The handoff's type scale (标题 28 · 表单标题 21 · 行标题 17 · 正文 15 · 说明 13 · 等宽 12.5 · 小标签 10.5 · 按钮 16 · 计时 52). */
internal object MemoType {
    val title = memoText(28f, 1.14f, FontWeight.SemiBold).merge(TextStyle(letterSpacing = (-0.02).em))
    val sheetTitle = memoText(21f, 1.25f, FontWeight.SemiBold)
    val row = memoText(17f, 1.3f, FontWeight.SemiBold)
    val body = memoText(15f, 1.5f)
    val bodyStrong = memoText(15f, 1.45f, FontWeight.SemiBold)
    val caption = memoText(13f, 1.45f)
    val captionStrong = memoText(13f, 1.35f, FontWeight.SemiBold)
    val mono = memoText(12.5f, 1.4f, mono = true)
    val label = memoText(10.5f, 1.3f, FontWeight.Medium).merge(TextStyle(letterSpacing = 0.13.em))
    val timer = memoText(52f, 1.1f, FontWeight.Medium, mono = true)
}

/** A line box's height in dp for [style] — the box a mark is centred in so it sits on the first text line. */
@Composable
internal fun lineHeightDp(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }

/** True at 150 % type and above — the handoff's "compact" arrangement for status strip and dispatch bar. */
@Composable
internal fun memoLargeType(): Boolean = LocalDensity.current.fontScale >= 1.5f

/** The state mark size: 9 dp, growing to 13 dp at large type so it stays discernible beside bigger text. */
@Composable
internal fun memoMarkSize(): Dp = if (LocalDensity.current.fontScale >= 1.5f) 13.dp else 9.dp

// ── state marks ─────────────────────────────────────────────────────────────────────────────────────

/** 实心圆＝已送达/在线 · 空心圆＝草稿/完成/等待 · 菱形＝待核对 · 方块＝失败 · 脉冲＝发送中/录音中. */
internal enum class MemoMark { RING, DOT, PULSE, DIAMOND, SQUARE }

/** Breathing alpha for a live mark (1.2 s cycle); constant when the system asks for reduced motion. */
@Composable
internal fun rememberPulseAlpha(): Float {
    if (LocalReduceMotion.current) return 1f
    val alpha by rememberInfiniteTransition(label = "memoPulse").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "memoPulseAlpha",
    )
    return alpha
}

@Composable
internal fun MemoMarkGlyph(mark: MemoMark, color: Color, size: Dp = memoMarkSize()) {
    when (mark) {
        MemoMark.RING -> StateMarkGlyph(StateMark.RING, color, size, 1.6.dp)
        MemoMark.DOT -> StateMarkGlyph(StateMark.DOT, color, size)
        MemoMark.PULSE -> Box(Modifier.alpha(rememberPulseAlpha())) { StateMarkGlyph(StateMark.DOT, color, size) }
        MemoMark.DIAMOND -> StateMarkGlyph(StateMark.DIAMOND, color, size * 0.88f)
        MemoMark.SQUARE -> StateMarkGlyph(StateMark.SQUARE, color, size)
    }
}

/** A mark centred in one line box of [style], for a Row aligned to Top next to (possibly wrapping) text. */
@Composable
internal fun LineMark(mark: MemoMark, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Box(modifier.height(lineHeightDp(style)), contentAlignment = Alignment.Center) { MemoMarkGlyph(mark, color) }
}

/** The mark + colour of one delivery state. Unknown words read as "to check" — never as delivered. */
internal fun todoMark(todoState: String): Pair<MemoMark, Color> = when (todoState) {
    MemoTodoState.DRAFT -> MemoMark.RING to Tok.muted
    MemoTodoState.SENDING -> MemoMark.PULSE to Tok.tx2
    MemoTodoState.DELIVERED -> MemoMark.DOT to Tok.ok
    MemoTodoState.FAILED -> MemoMark.SQUARE to Tok.danger
    else -> MemoMark.DIAMOND to Tok.warn
}

/** Spinner (0.9 s turn); a still arc under reduced motion. Decorative — the words beside it carry the state. */
@Composable
internal fun MemoSpinner(size: Dp = 15.dp, modifier: Modifier = Modifier) {
    val angle = if (LocalReduceMotion.current) 0f else {
        val a by rememberInfiniteTransition(label = "memoSpin").animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "memoSpinAngle",
        )
        a
    }
    val track = Tok.hair
    val arc = Tok.accent
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val d = this.size.minDimension - stroke
        drawCircle(track, radius = d / 2, style = Stroke(stroke))
        drawArc(
            arc, startAngle = angle - 90f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(d, d),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

// ── chrome ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * The page's back row: the phone's shared [BackTarget], pulled out of the page gutter onto the common 4 dp
 * inset. [description] says where back leads. Null [onBack] keeps the row's height but offers no way back.
 */
@Composable
internal fun MemoBackRow(description: String, onBack: (() -> Unit)?) {
    val gutter = LocalMemoGutter.current
    Box(Modifier.fillMaxWidth().heightIn(min = Metric.touch)) {
        if (onBack != null) BackTarget(onBack, Modifier.offset(x = 4.dp - gutter), description = description)
    }
}

/** A disclosure chevron: down when closed, up when open. */
@Composable
internal fun DisclosureChevron(open: Boolean) {
    val tint = Tok.muted
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val p = if (open) Path().apply { moveTo(w * 0.15f, h * 0.68f); lineTo(w * 0.5f, h * 0.32f); lineTo(w * 0.85f, h * 0.68f) }
        else Path().apply { moveTo(w * 0.15f, h * 0.32f); lineTo(w * 0.5f, h * 0.68f); lineTo(w * 0.85f, h * 0.32f) }
        drawPath(p, tint, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A forward chevron for a row that opens another page. */
@Composable
internal fun ForwardChevron() {
    val tint = Tok.muted
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply { moveTo(w * 0.32f, h * 0.1f); lineTo(w * 0.72f, h * 0.5f); lineTo(w * 0.32f, h * 0.9f) }
        drawPath(p, tint, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** An uppercase section label. */
@Composable
internal fun MemoSectionLabel(text: String, modifier: Modifier = Modifier) {
    MemoText(text.uppercase(), style = MemoType.label, color = Tok.muted, modifier = modifier)
}

/** A written notice block: mark on the first line, a title, a body, then its own content. */
@Composable
internal fun MemoNoticeBlock(
    mark: MemoMark,
    tint: Color,
    title: String?,
    body: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = tint,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Tok.surface).border(Metric.hairline, Tok.hair, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val lead = if (title != null) MemoType.bodyStrong else MemoType.caption
        LineMark(mark, tint, lead)
        Column(Modifier.weight(1f)) {
            if (title != null) MemoText(title, style = MemoType.bodyStrong, color = titleColor)
            if (body != null) MemoText(
                body, style = MemoType.caption,
                color = if (title != null) Tok.tx2 else Tok.tx,
                modifier = Modifier.padding(top = if (title != null) 6.dp else 0.dp),
            )
            extra()
        }
    }
}

/** THE filled action — the shared entry-flow button (min 52 dp, wraps, disabled look written in). */
@Composable
internal fun MemoPrimaryButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    EntryPrimaryButton(label, modifier = modifier, enabled = enabled, onClick = onClick)
}

/** A hairline-bordered action (secondary / destructive), min 52 dp, with a written disabled state. */
@Composable
internal fun MemoOutlineButton(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Tok.tx,
    enabled: Boolean = true,
    leading: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metric.radius)
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .then(if (enabled) Modifier else Modifier.background(Tok.surface))
            .border(Metric.hairline, if (enabled) Tok.tx2.copy(alpha = 0.45f) else Tok.hair, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapL, vertical = Metric.gap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = memoText(16f, 1.25f, FontWeight.SemiBold)
        if (leading != null) MemoText(
            leading, style = tightCenter(20.sp).merge(TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Light)),
            color = if (enabled) tint else Tok.muted, modifier = Modifier.padding(end = 9.dp),
        )
        MemoText(label, style = style, color = if (enabled) tint else Tok.muted, textAlign = TextAlign.Center)
    }
}

/** An in-row text action: 44 dp tall, accent or quiet. */
@Composable
internal fun MemoTextAction(label: String, color: Color = Tok.accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) { MemoText(label, style = MemoType.captionStrong, color = color) }
}

/** A tappable 48 dp quiet action, centred (used under a stack of buttons). */
@Composable
internal fun MemoQuietAction(label: String, color: Color = Tok.tx2, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MemoText(label, style = MemoType.captionStrong, color = color, textAlign = TextAlign.Center) }
}

/** The fixed bottom action area: hairline, gutter, stacked content; rides above the keyboard and nav bar. */
@Composable
internal fun MemoBottomBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val gutter = LocalMemoGutter.current
    Column(modifier.fillMaxWidth().background(Tok.base).navigationBarsPadding().imePadding()) {
        Hairline()
        Column(
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * Lets a row reach [horizontal] past its parent's gutter on both sides, so a 48 dp target (checkbox,
 * back chevron, remove ×) sits with its glyph on the gutter line instead of indenting the text beside it.
 */
internal fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val wide = if (constraints.hasBoundedWidth) constraints.copy(
        minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra,
    ) else constraints
    val placeable = measurable.measure(wide)
    val width = if (constraints.hasBoundedWidth) placeable.width - extra else placeable.width
    layout(width.coerceAtLeast(0), placeable.height) { placeable.place(if (constraints.hasBoundedWidth) -extra / 2 else 0, 0) }
}

// ── formatting ──────────────────────────────────────────────────────────────────────────────────────

/** "m:ss" for a duration. */
internal fun fmtClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Whole-second clock for a running timer (never rounds up past what was recorded). */
internal fun fmtClockFloor(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** "8.4" — one decimal of seconds, for a measured step duration. */
internal fun fmtSecondsTenths(ms: Long): String {
    val t = (ms.coerceAtLeast(0) + 50) / 100
    return "${t / 10}.${t % 10}"
}

/** The computer's name for sentences, or a neutral "your computer" when the pairing has none. */
@Composable
internal fun computerName(readiness: MemoReadiness): String =
    readiness.computerName.ifBlank { stringResource(Res.string.memo_computer_fallback) }

/** A memo's time: "今天 09:12" / "昨天 18:40" / "9月26日 21:05" / with the year when it is not this year. */
@Composable
internal fun memoTimeLabel(epochMs: Long): String {
    val at = localClock(epochMs)
    val now = localClock(epochMillis())
    val yesterday = localClock(epochMillis() - 24L * 60 * 60 * 1000)
    val hm = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    fun sameDay(a: dev.ccpocket.app.LocalClock, b: dev.ccpocket.app.LocalClock) =
        a.year == b.year && a.monthOfYear == b.monthOfYear && a.dayOfMonth == b.dayOfMonth
    return when {
        sameDay(at, now) -> stringResource(Res.string.memo_time_today, hm)
        sameDay(at, yesterday) -> stringResource(Res.string.memo_time_yesterday, hm)
        at.year == now.year -> stringResource(Res.string.memo_time_date, at.monthOfYear, at.dayOfMonth, hm)
        else -> stringResource(Res.string.memo_time_date_year, at.year, at.monthOfYear, at.dayOfMonth, hm)
    }
}

/** A thin [androidx.compose.material3.Text] wrapper (style + colour first) so this package's call sites read uniformly. */
@Composable
internal fun MemoText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) = androidx.compose.material3.Text(text, modifier = modifier, color = color, style = style, textAlign = textAlign, maxLines = maxLines)
