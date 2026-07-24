package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind

/**
 * Agent identity — the consistent visual language for "which backend drives this session".
 * Claude keeps the app accent (terracotta); Codex gets a calm teal. Per the design, the common Claude
 * case stays unmarked and ONLY Codex is tagged in lists/headers; the agent is always explicit in session info.
 */
fun agentColor(agent: AgentKind): Color = when (agent) {
    AgentKind.CODEX -> Tok.codex
    AgentKind.OPENCODE -> Tok.opencode
    else -> Tok.accent
}
fun agentName(agent: AgentKind): String = when (agent) {
    AgentKind.CODEX -> "Codex"
    AgentKind.OPENCODE -> "OpenCode"
    else -> "Claude"
}
fun agentTagline(agent: AgentKind): String = when (agent) {
    AgentKind.CODEX -> "Codex · OpenAI"
    AgentKind.OPENCODE -> "OpenCode · Open Source"
    else -> "Claude Code · Anthropic"
}

/** The two standard agent-color tints — a 12% fill + a 42% border — shared by the chip and the selection cards. */
internal fun Color.agentTintFill(): Color = copy(alpha = 0.12f)
internal fun Color.agentTintBorder(): Color = copy(alpha = 0.42f)

/** A 1.5pt line glyph per agent: Claude = shell-prompt chevron; Codex = orbit/concentric mark. Drawn in a 20×20 grid. */
@Composable
fun AgentGlyph(agent: AgentKind, color: Color = agentColor(agent), size: Int = 18) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.minDimension / 20f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        if (agent == AgentKind.CODEX) {
            val w = 1.6f * s
            // central node
            drawCircle(color, radius = 2.3f * s, center = p(10f, 10f), style = Stroke(width = w))
            // two orbit arcs (top-right + bottom-left), radius 6.8 around center
            val box = Offset((10f - 6.8f) * s, (10f - 6.8f) * s)
            val d = Size(13.6f * s, 13.6f * s)
            drawArc(color, startAngle = -90f, sweepAngle = 90f, useCenter = false, topLeft = box, size = d, style = Stroke(width = w, cap = StrokeCap.Round))
            drawArc(color, startAngle = 90f, sweepAngle = 90f, useCenter = false, topLeft = box, size = d, style = Stroke(width = w, cap = StrokeCap.Round))
        } else if (agent == AgentKind.OPENCODE) {
            val w = 1.6f * s
            val innerColor = color.copy(alpha = 0.3f)
            drawRoundRect(
                color = color, topLeft = p(3f, 2f), size = Size(14f * s, 16f * s),
                cornerRadius = CornerRadius(1.5f * s, 1.5f * s), style = Stroke(width = w)
            )
            drawRoundRect(
                color = innerColor, topLeft = p(5f, 10f), size = Size(10f * s, 7f * s),
                cornerRadius = CornerRadius(1f * s, 1f * s)
            )
        } else {
            val w = 1.8f * s
            // chevron ">"
            drawLine(color, p(5f, 5f), p(9.2f, 9.2f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, p(9.2f, 9.2f), p(5f, 13.4f), strokeWidth = w, cap = StrokeCap.Round)
            // prompt underline
            drawLine(color, p(11f, 14f), p(15f, 14f), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/** The agent chip used app-wide: a rounded tinted pill with the glyph + name in the agent's color. */
@Composable
fun AgentTag(agent: AgentKind, small: Boolean = true) {
    val c = agentColor(agent)
    Row(
        Modifier
            .background(c.agentTintFill(), RoundedCornerShape(999.dp))
            .border(1.dp, c.agentTintBorder(), RoundedCornerShape(999.dp))
            .padding(horizontal = if (small) 7.dp else 9.dp, vertical = if (small) 2.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentGlyph(agent, c, if (small) 12 else 14)
        // trim the text's asymmetric ascent/descent so the glyph optically centers against the letters
        Text(agentName(agent), color = c, fontSize = if (small) 10.5.sp else 12.sp, fontWeight = FontWeight.SemiBold, style = TightCenter)
    }
}

/** Header / list-row badge: the DEFAULT Claude stays unmarked; every other agent (Codex, OpenCode, …) shows its tag, with a leading [gap]. */
@Composable
fun AgentBadge(agent: AgentKind?, gap: Dp = 6.dp) {
    if (agent != null && agent != AgentKind.CLAUDE) {
        Spacer(Modifier.width(gap))
        AgentTag(agent)
    }
}

private const val AUTOSIZE_STEP_SP = 0.5f

/**
 * A single-line label that shrinks its font — never wraps, never ellipsizes — to fit the width it's
 * handed, then holds [fontSize] once it fits. The New-session agent picker is three equal columns, and
 * the longest name ("OpenCode") overflowed its third: it wrapped to "OpenCod / e" on phones and, with
 * Windows' wider UI font, in the desktop Open-folder popover too (#178). CMP 1.7.3 has no BasicText
 * autoSize, so we measure-then-shrink — render with softWrap off + maxLines 1, and on every layout that
 * still overflows, step the size down by 0.5sp until it fits or reaches [minFontSize] (the readable
 * floor). Drawing is gated until the size settles so the oversized first pass never flashes. Shorter
 * names (Claude/Codex) already fit at [fontSize] and never shrink; keying the state on the width ceiling
 * lets a card that regained room (e.g. its check mark went away) grow back to base rather than stay small.
 */
@Composable
fun AutoSizeSingleLineText(
    text: String,
    fontSize: TextUnit,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalTextStyle.current,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val ceiling = maxWidth
        var size by remember(text, fontSize, minFontSize, ceiling) { mutableStateOf(fontSize) }
        var settled by remember(text, fontSize, minFontSize, ceiling) { mutableStateOf(false) }
        Text(
            text = text,
            color = color,
            fontSize = size,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip, // shrink to fit — an ellipsis "OpenCo…" is not acceptable (#178)
            style = style,
            modifier = Modifier.drawWithContent { if (settled) drawContent() },
            onTextLayout = { result ->
                if (result.hasVisualOverflow && size.value - AUTOSIZE_STEP_SP >= minFontSize.value) {
                    size = (size.value - AUTOSIZE_STEP_SP).sp
                } else {
                    settled = true
                }
                onTextLayout(result)
            },
        )
    }
}
