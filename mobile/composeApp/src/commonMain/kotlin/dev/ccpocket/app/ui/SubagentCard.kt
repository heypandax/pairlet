package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.subagent_report
import dev.ccpocket.app.resources.subagent_tools
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Sub-agent (Task/Agent) card — one visual language for the mobile chat
//  stream and the desktop chat pane, per the design handoff
//  (docs/design/claude-design-handoff/chat-cards/). An autonomous run is
//  an ASIDE in the main thread: a raised card whose status tile carries
//  agent identity + state colour, alive but calm while running.
// ════════════════════════════════════════════════════════════════════

/** When the running card first appeared on THIS device, keyed by taskId — the wire carries no start
 *  timestamp, so the elapsed readout counts from first sight (an attach mid-run under-counts). Kept
 *  outside composition so LazyColumn recycling can't reset a run's clock. */
private val subagentFirstSeen = mutableMapOf<String, Long>()

/**
 * A sub-agent run as one grouped card (issue #77): status tile + "Type · description" header, the
 * inner tool calls folded into an ECG progress line, a pulse-dot + elapsed clock while running, and
 * — once the run settles — ✓/✗ state colour with the final report (markdown, capped scroll) behind
 * a tap. With an old daemon the extras never arrive (taskId/ok stay null) and this renders as the
 * neutral header alone. [dense] = desktop metrics: tighter paddings, hover-revealed chevron + lift.
 */
@Composable
internal fun SubagentCard(m: ChatItem.Tool, dense: Boolean = false) {
    val running = m.taskId != null && m.ok == null
    // keyed on the run, not the item: progress copies() must not reset the disclosure
    var expanded by remember(m.taskId ?: m.preview) { mutableStateOf(false) }
    val expandable = m.output != null
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (dense) 11.dp else 13.dp)

    Column(
        Modifier.fillMaxWidth().clip(shape)
            // dense (desktop): the card lifts to a raised surface on hover; at rest the stream stays quiet
            .background(if (dense && hovered) Tok.raised else Tok.surface)
            .border(1.dp, Tok.hair, shape)
            .hoverable(hoverSrc),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(
                    horizontal = if (dense) 11.dp else 12.dp,
                    vertical = if (dense) 9.dp else 11.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 10.dp else 11.dp),
        ) {
            SubagentTile(running, m.ok, dense)
            Column(Modifier.weight(1f)) {
                // "Type · description" — bold identity, the sub-agent's task in secondary ink
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Tok.tx, fontWeight = FontWeight.SemiBold)) { append(m.tool) }
                        if (m.preview.isNotBlank()) {
                            withStyle(SpanStyle(color = Tok.muted)) { append(" · ") }
                            withStyle(SpanStyle(color = Tok.tx2)) { append(m.preview.lineSequence().first()) }
                        }
                    },
                    fontSize = if (dense) 13.sp else 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                SubagentSubline(m, running, dense)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (running) {
                    PulseDot(Tok.accent, 7.dp)
                    Text(
                        subagentElapsed(m.taskId!!),
                        color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    )
                }
                if (expandable) Icon(
                    Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
                    modifier = Modifier.size(15.dp).rotate(if (expanded) 180f else 0f)
                        // dense: the chevron is a hover affordance — invisible at rest, like the design's .dense
                        .alpha(if (!dense || hovered || expanded) 1f else 0f),
                )
            }
        }
        if (expandable && expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            if (m.ok == false) SubagentErrorBody(m.output!!) else SubagentReportBody(m, dense)
        }
    }
}

/** The status/progress sub-line under the label: live "⌁ N tools · latest" while running, the run
 *  summary once succeeded, the error headline in danger ink on failure. History replays drop the
 *  child counters (childCount == 0) — then a settled card carries no sub-line at all. */
@Composable
private fun SubagentSubline(m: ChatItem.Tool, running: Boolean, dense: Boolean) {
    val fontSize = if (dense) 10.5.sp else 11.sp
    val topPad = if (dense) 2.dp else 3.dp
    when {
        running -> Row(
            Modifier.padding(top = topPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EcgGlyph(Tok.muted, 11.dp) // activity, not a spinner — the run has no known endpoint
            Text(
                stringResource(Res.string.subagent_tools, m.childCount),
                color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = fontSize,
            )
            m.lastChild?.let {
                Text(
                    "· $it", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = fontSize,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        m.ok == false -> Text(
            (m.output?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: m.preview),
            color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = fontSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = topPad),
        )
        m.ok == true && m.childCount > 0 -> Text(
            stringResource(Res.string.subagent_tools, m.childCount),
            color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = fontSize,
            modifier = Modifier.padding(top = topPad),
        )
    }
}

/** Expanded final report: quiet eyebrow, markdown in SECONDARY ink (one step quieter than the main
 *  thread) inside a capped scroll, and a footer restating the run summary beside a copy affordance. */
@Composable
private fun SubagentReportBody(m: ChatItem.Tool, dense: Boolean) {
    Column(Modifier.fillMaxWidth().background(Tok.base.copy(alpha = 0.45f))) {
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EcgGlyph(Tok.muted, 11.dp)
            Text(
                stringResource(Res.string.subagent_report).uppercase(),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 1.2.sp,
            )
        }
        Box(
            Modifier.heightIn(max = if (dense) 150.dp else 210.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        ) {
            SelectionContainer { MarkdownText(m.output!!, Tok.tx2) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (m.childCount > 0) stringResource(Res.string.subagent_tools, m.childCount) else m.tool,
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            CopyChip(m.output!!)
        }
    }
}

/** Expanded failure detail: the captured error text as selectable monospace on the recessed ground. */
@Composable
private fun SubagentErrorBody(output: String) {
    Box(Modifier.fillMaxWidth().background(Tok.base.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        SelectionContainer {
            Text(output, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 18.sp)
        }
    }
}

/** Elapsed as "0:42" / "3:07", ticking once a second while composed. Counts from when this device
 *  first saw the run (see [subagentFirstSeen]) — good enough for "is it stuck?" glances. */
@Composable
private fun subagentElapsed(taskId: String): String {
    val startedAt = remember(taskId) { subagentFirstSeen.getOrPut(taskId) { epochMillis() } }
    var seconds by remember(taskId) { mutableStateOf((epochMillis() - startedAt) / 1000) }
    LaunchedEffect(taskId) {
        while (true) {
            delay(1000)
            seconds = (epochMillis() - startedAt) / 1000
        }
    }
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

// ── status tile + glyphs ─────────────────────────────────────────────
//  The tile is the card's one saturated element: identity colour while
//  running (accent), success/danger tint once settled, neutral when an
//  old daemon sent no run state. Glyphs are stroked vector paths (no
//  emoji) matching the handoff SVGs: prompt ❯_ / check / cross.

@Composable
private fun SubagentTile(running: Boolean, ok: Boolean?, dense: Boolean) {
    val size = if (dense) 28.dp else 32.dp
    val shape = RoundedCornerShape(if (dense) 8.dp else 9.dp)
    val (bg, bd, fg) = when {
        running -> Triple(Tok.accent.copy(alpha = 0.12f), Tok.accent.copy(alpha = 0.38f), Tok.accent)
        ok == true -> Triple(Tok.ok.copy(alpha = 0.14f), Tok.ok.copy(alpha = 0.36f), Tok.ok)
        ok == false -> Triple(Tok.danger.copy(alpha = 0.12f), Tok.danger.copy(alpha = 0.36f), Tok.danger)
        else -> Triple(Tok.raised, Tok.hair, Tok.tx2)
    }
    Box(Modifier.size(size).clip(shape).background(bg).border(1.dp, bd, shape), contentAlignment = Alignment.Center) {
        when (ok) {
            true -> CheckGlyph(fg, if (dense) 15.dp else 16.dp)
            false -> CrossGlyph(fg, if (dense) 14.dp else 15.dp)
            null -> PromptGlyph(fg, if (dense) 15.dp else 17.dp)
        }
    }
}

/** Terminal prompt "❯_" — the agent-at-work identity mark (handoff viewBox 20). */
@Composable
private fun PromptGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 20f
    val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(5 * u, 5 * u); lineTo(9.2f * u, 9.2f * u); lineTo(5 * u, 13.4f * u) }, color, style = stroke)
    drawPath(Path().apply { moveTo(11 * u, 14 * u); lineTo(15 * u, 14 * u) }, color, style = stroke)
}

/** Terminal ✓ (handoff viewBox 18). */
@Composable
private fun CheckGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 18f
    drawPath(
        Path().apply { moveTo(3.5f * u, 9.5f * u); lineTo(7 * u, 13 * u); lineTo(14.5f * u, 4.5f * u) },
        color, style = Stroke(width = 2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Failure ✗ (handoff viewBox 18). */
@Composable
private fun CrossGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 18f
    val stroke = Stroke(width = 2 * u, cap = StrokeCap.Round)
    drawPath(Path().apply { moveTo(5 * u, 5 * u); lineTo(13 * u, 13 * u) }, color, style = stroke)
    drawPath(Path().apply { moveTo(13 * u, 5 * u); lineTo(5 * u, 13 * u) }, color, style = stroke)
}

/** ECG activity trace "⌁" for the progress line — replaces the old ⚒ emoji (handoff viewBox 14). */
@Composable
private fun EcgGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 14f
    drawPath(
        Path().apply {
            moveTo(1 * u, 8 * u); lineTo(3.4f * u, 8 * u); lineTo(5 * u, 4 * u)
            lineTo(7.2f * u, 12 * u); lineTo(8.7f * u, 8 * u); lineTo(13 * u, 8 * u)
        },
        color, style = Stroke(width = 1.3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
