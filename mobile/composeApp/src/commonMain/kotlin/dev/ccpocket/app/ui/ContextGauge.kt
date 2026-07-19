package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_critical_caption
import dev.ccpocket.app.resources.qa_context_gauge
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/** Ring geometry, straight from the design (context-occupancy.jsx `Ring`). */
private val RING_SIZE = 15.dp
private val RING_STROKE = 2.2.dp

/**
 * Width the percent needs before it earns its place: ring 15 + gap 5 + four mono-11 glyphs (~26)
 * + the gauge's own 6 of horizontal padding. Below this the number is shed and the ring carries on.
 */
private val NUMBER_ROOM = 54.dp

/** The escalation step where occupancy stops being ambient (mirrors [contextColor]'s warn stop). */
private const val WARN_AT = 0.80f

/** Where "you are about to lose turns" becomes true — the only step loud enough to earn a caption. */
const val CONTEXT_CRITICAL_AT = 0.95f

/**
 * Context-window occupancy, inline on the composer's accessory row (design: `context-occupancy.jsx`,
 * Option C). It replaces the floating `Context 42%` pill that used to hover over the message tail —
 * that one cost no layout height but covered content, could not be tapped, and read like a debug
 * overlay.
 *
 * A **chrome-less** gauge: no border, no fill. That is what keeps ambient information from reading as
 * a fourth control one thumb from Send. It escalates by GROWING rather than shouting — calm is a bare
 * ring, >=80% grows an amber percent, >=95% turns red (thresholds come from [contextColor], so the
 * session sheet's ContextBar stays in lockstep). Under width pressure it sheds the NUMBER first and
 * keeps the colour-carrying ring, so the model chip, the stack chip, stop and send never move.
 *
 * A null [window] means no known denominator (a backend whose window we cannot size): a hollow ring
 * plus raw occupancy `~84k` — never a fake percentage.
 *
 * [reserveEnd] is the width of the action buttons sitting after the elastic gap. Row measures a
 * non-weighted child against the space left by its PREDECESSORS only, so those trailing buttons are
 * invisible in our constraints; subtracting them is what makes the shed-the-number call honest.
 */
@Composable
fun ContextGauge(
    used: Long?,
    window: Long?,
    reserveEnd: Dp,
    modifier: Modifier = Modifier,
    onOpenInfo: () -> Unit,
) {
    used ?: return // no turn yet / older daemon — nothing to show
    val known = window != null && window > 0L
    val frac = if (known) (used.toFloat() / window!!).coerceIn(0f, 1f) else 0f
    val color = contextColor(frac, Tok.tx2)
    val track = Tok.hair
    val a11y = stringResource(Res.string.qa_context_gauge)
    BoxWithConstraints(
        modifier
            // the glyph stays the design's 30dp, but the target fills the 44dp row — a chrome-less
            // readout should not also be a small tap target
            .height(44.dp)
            .widthIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenInfo)
            .semantics { contentDescription = a11y }
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        // the number appears only once it means something, and only while there is room for it
        val showNumber = (!known || frac >= WARN_AT) && (maxWidth - reserveEnd) >= NUMBER_ROOM
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(RING_SIZE)) {
                val stroke = RING_STROKE.toPx()
                val d = size.minDimension - stroke
                val arc = Size(d, d)
                val at = Offset(stroke / 2, stroke / 2)
                drawArc(track, 0f, 360f, false, at, arc, style = Stroke(stroke))
                // hollow ring is the stated fallback for an unknown window — never a fake fill
                if (known && frac > 0f) {
                    drawArc(color, -90f, 360f * frac, false, at, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            if (showNumber) {
                Spacer(Modifier.width(5.dp))
                Text(
                    if (known) "${(frac * 100).toInt()}%" else "~${formatTokens(used)}",
                    color = if (known) color else Tok.tx2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * What is left of the old full-width amber strip. The strip existed because the floating pill was too
 * quiet to warn with; the gauge now carries the >=80% warning itself, so the heavy block is retired and
 * its real message — "you are about to lose turns" — survives as one slim caption that fires only at
 * [CONTEXT_CRITICAL_AT]. One escalation channel, three volumes: ring -> amber number -> red + caption.
 */
@Composable
fun ContextCriticalCaption() {
    val danger = Tok.danger
    Row(
        // 16 of screen margin + the design's own 2, so the glyph sits optically on the field's edge
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val u = size.minDimension / 14f // the design drew this in a 14-unit box
            val stroke = 1.4f * u
            drawPath(
                Path().apply {
                    moveTo(7 * u, 1.6f * u); lineTo(12.6f * u, 11.4f * u); lineTo(1.4f * u, 11.4f * u); close()
                },
                danger,
                style = Stroke(stroke, join = StrokeJoin.Round),
            )
            drawLine(danger, Offset(7 * u, 5.6f * u), Offset(7 * u, 8.2f * u), stroke, StrokeCap.Round)
            drawCircle(danger, 0.8f * u, Offset(7 * u, 10f * u))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            stringResource(Res.string.context_critical_caption),
            color = danger,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
