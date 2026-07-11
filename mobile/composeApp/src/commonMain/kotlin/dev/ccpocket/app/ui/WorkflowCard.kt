package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.ccpocket.app.data.WorkflowCardVariant
import dev.ccpocket.app.data.WorkflowPhaseStatus
import dev.ccpocket.app.data.WorkflowUi
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus
import kotlinx.coroutines.delay

// ════════════════════════════════════════════════════════════════════
//  Workflow run chat card (issue #106) — the orchestration-level
//  sibling of SubagentCard: same family (status tile · hairline card ·
//  pulse/✓/✗), but it reads as a FLEET, never one more agent. Pixel
//  spec: docs/design/claude-design-handoff/workflow-view/
//  (workflow-core.jsx WorkflowCard + scene-only.jsx DenseWorkflowCard).
//  Danger only ever lands in the tile/chip — never a red card border.
// ════════════════════════════════════════════════════════════════════

/** Stacked lanes — three offset horizontal bars = orchestration, NOT one agent (handoff viewBox 18). */
@Composable
internal fun LanesGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 18f
    fun bar(x: Float, y: Float, w: Float) = drawRoundRect(
        color, topLeft = androidx.compose.ui.geometry.Offset(x * u, y * u),
        size = androidx.compose.ui.geometry.Size(w * u, 2.6f * u),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.3f * u),
    )
    bar(1f, 3.2f, 11f)
    bar(4.5f, 7.7f, 12.5f)
    bar(2.5f, 12.2f, 9.5f)
}

/** Check ✓ (handoff viewBox 18) — same stroke language as SubagentCard's. */
@Composable
internal fun WfCheckGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 18f
    drawPath(
        Path().apply { moveTo(3.5f * u, 9.4f * u); lineTo(7.1f * u, 13f * u); lineTo(14.5f * u, 5f * u) },
        color, style = Stroke(width = 2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Cross ✗ (handoff viewBox 16). */
@Composable
internal fun WfCrossGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 16f
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round)
    drawPath(Path().apply { moveTo(4 * u, 4 * u); lineTo(12 * u, 12 * u) }, color, style = stroke)
    drawPath(Path().apply { moveTo(12 * u, 4 * u); lineTo(4 * u, 12 * u) }, color, style = stroke)
}

/**
 * The one synchronized heartbeat every RUNNING row shares (design stance: one pulse, never a
 * spinner sea). Phase comes from the WALL CLOCK, so every dot on screen breathes together no
 * matter when it composed — a per-instance infiniteTransition would drift.
 */
@Composable
internal fun syncPulseAlpha(): Float {
    var now by remember { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(48) // ~20fps is plenty for a breath
            now = epochMillis()
        }
    }
    val phase = (now % PULSE_PERIOD_MS).toFloat() / PULSE_PERIOD_MS // 0..1 sawtooth
    val tri = if (phase < 0.5f) phase * 2f else 2f - phase * 2f     // 0..1..0 triangle
    return 0.45f + 0.55f * tri
}

private const val PULSE_PERIOD_MS = 1250L

/** A running row's dot on the shared heartbeat. */
@Composable
internal fun SyncPulseDot(size: Dp = 7.dp, color: Color = Tok.accent) {
    val a = syncPulseAlpha()
    Box(Modifier.size(size).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

/** Hollow muted dot — queued. */
@Composable
internal fun HollowDot(size: Dp = 8.dp) {
    Box(Modifier.size(size).graphicsLayer { alpha = 0.7f }.border(1.5.dp, Tok.muted, CircleShape))
}

/** Small danger failure chip — "✗ N". The ONLY red allowed at card level. */
@Composable
internal fun FailChip(n: Int, dense: Boolean = false) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(Tok.danger.copy(alpha = 0.13f)).border(1.dp, Tok.danger.copy(alpha = 0.4f), shape)
            .padding(start = 6.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WfCrossGlyph(Tok.danger, 9.dp)
        Text("$n", color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = if (dense) 10.5.sp else 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Segmented phase progress bar — one segment per phase, 3dp pills; done solid, active breathing,
 *  pending a 1dp hairline. */
@Composable
internal fun PhaseBar(statuses: List<WorkflowPhaseStatus>, modifier: Modifier = Modifier) {
    val breathe = if (statuses.any { it == WorkflowPhaseStatus.ACTIVE }) syncPulseAlpha() else 1f
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        statuses.forEach { st ->
            when (st) {
                WorkflowPhaseStatus.DONE -> Box(
                    Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent),
                )
                WorkflowPhaseStatus.ACTIVE -> Box(
                    Modifier.weight(1f).height(3.dp).graphicsLayer { alpha = 0.35f + 0.35f * breathe }
                        .clip(RoundedCornerShape(999.dp)).background(Tok.accent),
                )
                WorkflowPhaseStatus.PENDING -> Box(
                    Modifier.weight(1f).height(3.dp), contentAlignment = Alignment.Center,
                ) { Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair)) }
            }
        }
    }
}

/** Live elapsed "3m 12s", ticking every second from [startedAt] (wall-clock epoch millis). */
@Composable
internal fun workflowElapsed(startedAt: Long): String {
    var now by remember { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            now = epochMillis()
        }
    }
    return WorkflowUi.formatDuration((now - startedAt).coerceAtLeast(0))
}

/** The status tile — the card's one saturated element. */
@Composable
internal fun WorkflowTile(variant: WorkflowCardVariant, size: Dp, radius: Dp) {
    val shape = RoundedCornerShape(radius)
    val (tint, bd, ink) = when (variant) {
        WorkflowCardVariant.OK, WorkflowCardVariant.OK_FAIL ->
            Triple(Tok.ok.copy(alpha = 0.15f), Tok.ok.copy(alpha = 0.38f), Tok.ok)
        WorkflowCardVariant.ABORTED ->
            Triple(Tok.danger.copy(alpha = 0.13f), Tok.danger.copy(alpha = 0.4f), Tok.danger)
        else -> Triple(Tok.accent.copy(alpha = 0.14f), Tok.accent.copy(alpha = 0.4f), Tok.accent)
    }
    Box(Modifier.size(size).clip(shape).background(tint).border(1.dp, bd, shape), contentAlignment = Alignment.Center) {
        when (variant) {
            WorkflowCardVariant.OK, WorkflowCardVariant.OK_FAIL -> WfCheckGlyph(ink, size * 15f / 28f)
            WorkflowCardVariant.ABORTED -> WfCrossGlyph(ink, size * 14f / 28f)
            else -> LanesGlyph(ink, size * 16f / 28f)
        }
    }
}

/**
 * The Workflow run chat card. [dense] = desktop metrics (scene-only.jsx DenseWorkflowCard):
 * 24dp tile, tighter paddings. Tapping opens the run view (mobile: full-screen tree; desktop:
 * the docked panel).
 */
@Composable
internal fun WorkflowCard(run: WorkflowRun, dense: Boolean = false, onOpen: () -> Unit) {
    val variant = WorkflowUi.variant(run)
    val groups = remember(run) { WorkflowUi.phaseGroups(run) }
    val terminal = run.status != WorkflowRunStatus.RUNNING && run.status != WorkflowRunStatus.UNKNOWN
    val shape = RoundedCornerShape(if (dense) 11.dp else 14.dp)

    Column(
        Modifier.fillMaxWidth().clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
            .clickable(onClick = onOpen)
            .padding(
                start = if (dense) 11.dp else 13.dp, end = if (dense) 11.dp else 13.dp,
                top = if (dense) 10.dp else 12.dp, bottom = if (dense) 10.dp else 13.dp,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 10.dp else 11.dp),
        ) {
            WorkflowTile(variant, if (dense) 24.dp else 28.dp, if (dense) 6.dp else 7.dp)
            Text(
                run.name,
                color = Tok.tx, fontSize = if (dense) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (!terminal) {
                SyncPulseDot(if (dense) 6.dp else 7.dp)
                Text(
                    workflowElapsed(run.startedAt),
                    color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            } else {
                Text(
                    WorkflowUi.formatDuration(run.durationMs),
                    color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
        }

        PhaseBar(groups.map { it.status }, Modifier.padding(top = if (dense) 10.dp else 11.dp))

        // meta caption row
        Row(
            Modifier.padding(top = if (dense) 9.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val mono = FontFamily.Monospace
            val fs = if (dense) 11.5.sp else 12.sp
            val failed = WorkflowUi.failedCount(run)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                when (variant) {
                    WorkflowCardVariant.LIVE, WorkflowCardVariant.LIVE_UNKNOWN -> {
                        val cur = WorkflowUi.currentPhase(groups)
                        if (cur != null) {
                            val (pos, total) = WorkflowUi.phasePosition(groups, cur)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = Tok.tx2)) { append("phase $pos/$total · ") }
                                    withStyle(SpanStyle(color = Tok.tx)) { append(cur.title) }
                                },
                                fontFamily = mono, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (variant == WorkflowCardVariant.LIVE_UNKNOWN) {
                                "${WorkflowUi.doneCount(run)} done · ${WorkflowUi.runningCount(run)} running"
                            } else {
                                "${WorkflowUi.doneCount(run)}/${run.agents.size} agents"
                            },
                            color = Tok.muted, fontFamily = mono, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (failed > 0) FailChip(failed, dense)
                    }
                    WorkflowCardVariant.OK, WorkflowCardVariant.OK_FAIL -> {
                        Text(
                            "${run.agents.size} agents · ${groups.size} phases · ${WorkflowUi.formatDuration(run.durationMs)}",
                            color = Tok.muted, fontFamily = mono, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (failed > 0) FailChip(failed, dense)
                    }
                    WorkflowCardVariant.ABORTED -> {
                        val cur = WorkflowUi.currentPhase(groups)
                        val where = cur?.let { c -> "aborted in phase ${WorkflowUi.phasePosition(groups, c).first}" } ?: "aborted"
                        Text(
                            "$where · ${WorkflowUi.formatDuration(run.durationMs)}",
                            color = Tok.muted, fontFamily = mono, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ChevronRight(Tok.muted, 15.dp)
        }
    }
}

/** Right chevron (handoff viewBox 18). */
@Composable
internal fun ChevronRight(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 18f
    drawPath(
        Path().apply { moveTo(6 * u, 3 * u); lineTo(12 * u, 9 * u); lineTo(6 * u, 15 * u) },
        color, style = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
