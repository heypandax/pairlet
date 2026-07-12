package dev.ccpocket.app.desktop

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.WorkflowPhaseGroup
import dev.ccpocket.app.data.WorkflowPhaseStatus
import dev.ccpocket.app.data.WorkflowUi
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentStateGlyph
import dev.ccpocket.app.ui.ChevronRight
import dev.ccpocket.app.ui.FailChip
import dev.ccpocket.app.ui.FinalReturnCard
import dev.ccpocket.app.ui.HollowDot
import dev.ccpocket.app.ui.MarkdownText
import dev.ccpocket.app.ui.PhaseBar
import dev.ccpocket.app.ui.SyncPulseDot
import dev.ccpocket.app.ui.WfCheckGlyph
import dev.ccpocket.app.ui.WfCrossGlyph
import dev.ccpocket.app.ui.WorkflowStatusPill
import dev.ccpocket.app.ui.WorkflowTile
import dev.ccpocket.app.ui.workflowElapsed
import dev.ccpocket.protocol.WorkflowAgentSnap
import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus

// ════════════════════════════════════════════════════════════════════
//  Desktop docked workflow panel (issue #106) — the persistent ~360dp
//  right column (scene-only.jsx DeskWorkflowPanel): header with the
//  full-width phase bar, a 28dp-row density tree, failures pinned,
//  agent rows expanding IN PLACE into their report (no third nav level
//  inside the panel), and — once terminal — Final return pinned at the
//  bottom. Docked beats overlay: the chat stays fully usable beside it.
// ════════════════════════════════════════════════════════════════════

@Composable
internal fun WorkflowPanel(model: DesktopModel, run: WorkflowRun, modifier: Modifier = Modifier) {
    val groups = remember(run) { WorkflowUi.phaseGroups(run) }
    val terminal = run.status != WorkflowRunStatus.RUNNING && run.status != WorkflowRunStatus.UNKNOWN

    Column(modifier.width(360.dp).fillMaxHeight().background(Tok.base)) {
        // ── header ──
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                WorkflowTile(WorkflowUi.variant(run), 24.dp, 6.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        run.name, color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        run.runId.take(10) + "…" + if (terminal) " · ${run.agents.size} agents" else "",
                        color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                WorkflowStatusPill(run)
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable { model.closeWorkflowPanel() },
                    contentAlignment = Alignment.Center,
                ) { WfCrossGlyph(Tok.muted, 12.dp) }
            }
            PhaseBar(groups.map { it.status }, Modifier.padding(top = 11.dp))
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val failed = WorkflowUi.failedCount(run)
                if (terminal) {
                    Text("${groups.size} phases · ${run.agents.size} agents", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp)
                } else {
                    val cur = WorkflowUi.currentPhase(groups)
                    if (cur != null) {
                        val (pos, total) = WorkflowUi.phasePosition(groups, cur)
                        Text("phase $pos/$total · ${cur.title}", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("·", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp)
                    }
                    Text("${WorkflowUi.doneCount(run)}/${run.agents.size}", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp)
                }
                if (failed > 0) FailChip(failed, dense = true)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        // ── tree ──
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            groups.forEach { g ->
                DeskPhaseSection(model, run, g, defaultOpen = g.status == WorkflowPhaseStatus.ACTIVE || (terminal && g.failed > 0))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Box(Modifier.height(12.dp))
        }

        // ── terminal: pinned Final return ──
        if (terminal && run.finalResult != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                FinalReturnCard(run.finalResult!!)
            }
        }
    }
}

/** Phase disclosure at panel density: 38dp header, 28dp rows, failures pinned when collapsed. */
@Composable
private fun DeskPhaseSection(model: DesktopModel, run: WorkflowRun, g: WorkflowPhaseGroup, defaultOpen: Boolean) {
    var open by remember(g.index) { mutableStateOf(defaultOpen) }
    val pending = g.status == WorkflowPhaseStatus.PENDING
    val done = g.status == WorkflowPhaseStatus.DONE

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 38.dp)
                .then(if (!pending) Modifier.clickable { open = !open } else Modifier)
                .alpha(if (pending) 0.6f else 1f).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.alpha(if (pending) 0f else 1f).rotate(if (open) 90f else 0f)) { ChevronRight(Tok.muted, 12.dp) }
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                when (g.status) {
                    WorkflowPhaseStatus.ACTIVE -> SyncPulseDot(6.dp)
                    WorkflowPhaseStatus.DONE -> WfCheckGlyph(Tok.ok, 12.dp)
                    WorkflowPhaseStatus.PENDING -> HollowDot(7.dp)
                }
            }
            Text(g.title, color = if (pending) Tok.muted else Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (g.failed > 0) FailChip(g.failed, dense = true)
            Box(Modifier.weight(1f))
            Text("${g.done}/${g.total}", color = if (pending) Tok.muted else Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp)
            Text(
                if (pending) "—" else WorkflowUi.formatDuration(g.durationMs),
                color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
            )
        }
        when {
            done && !open -> Column(Modifier.padding(bottom = 8.dp)) {
                Row(
                    Modifier.padding(start = 42.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WfCheckGlyph(Tok.ok, 11.dp)
                    Text(
                        if (g.failed > 0) "${g.done} passed · ${g.failed} failed" else "${g.total} agents · ${WorkflowUi.formatDuration(g.durationMs)}",
                        color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
                    )
                }
                if (g.failed > 0) Column(Modifier.padding(start = 22.dp)) {
                    g.agents.filter { it.state == WorkflowAgentState.FAILED }.forEach { DeskAgentRow(model, run, it) }
                }
            }
            pending -> Text(
                if (g.total > 0) "${g.total} agents queued" else "waiting",
                color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
                modifier = Modifier.alpha(0.6f).padding(start = 42.dp, bottom = 9.dp),
            )
            open -> Column(Modifier.padding(start = 22.dp, bottom = 9.dp)) {
                val (queued, rest) = g.agents.partition { it.state == WorkflowAgentState.QUEUED }
                rest.forEach { DeskAgentRow(model, run, it) }
                if (queued.size > 3) DeskQueuedRow(queued) else queued.forEach { DeskAgentRow(model, run, it) }
            }
        }
    }
}

/** 28dp agent row: hover raises it; DONE/FAILED rows expand IN PLACE into their report block
 *  (≤150dp scroll) — the full return is fetched on first open. */
@Composable
private fun DeskAgentRow(model: DesktopModel, run: WorkflowRun, a: WorkflowAgentSnap) {
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    var open by remember(a.index) { mutableStateOf(false) }
    val canOpen = a.state == WorkflowAgentState.DONE || a.state == WorkflowAgentState.FAILED
    val queued = a.state == WorkflowAgentState.QUEUED

    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(7.dp))
                .background(if (hovered) Tok.raised else Tok.base.copy(alpha = 0f))
                .hoverable(hoverSrc)
                .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { AgentStateGlyph(a.state) }
            Text(
                a.label, color = if (queued) Tok.muted else Tok.tx, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (canOpen && (hovered || open)) {
                Box(Modifier.rotate(if (open) 90f else 0f)) { ChevronRight(Tok.muted, 12.dp) }
            }
            Text(
                when (a.state) {
                    WorkflowAgentState.RUNNING -> a.startedAt?.let { workflowElapsed(it) } ?: "…"
                    WorkflowAgentState.QUEUED -> "queued"
                    else -> WorkflowUi.formatDuration(a.durationMs)
                },
                color = if (queued) Tok.muted else Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
            )
        }
        if (open && canOpen) {
            LaunchedEffect(run.runId, a.index) { model.fetchWorkflowAgentDetail(run.runId, a.index, a.agentId) }
            val failed = a.state == WorkflowAgentState.FAILED
            val body = model.workflowAgentDetails["${run.runId}#${a.index}"]?.result
                ?: (if (failed) a.error else a.resultPreview)
            val shape = RoundedCornerShape(9.dp)
            Column(
                Modifier.padding(start = 22.dp, top = 5.dp, bottom = 8.dp, end = 4.dp)
                    .clip(shape).background(Tok.base)
                    .border(1.dp, if (failed) Tok.danger.copy(alpha = 0.4f) else Tok.hair, shape),
            ) {
                Text(
                    if (failed) "ERROR" else "RETURN",
                    color = if (failed) Tok.danger else Tok.muted, fontFamily = Dk.mono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(start = 11.dp, top = 7.dp),
                )
                Box(
                    Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                ) {
                    when {
                        body == null -> Text("…", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp)
                        failed -> SelectionContainer {
                            Text(body, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp, lineHeight = 18.sp)
                        }
                        else -> SelectionContainer { MarkdownText(body, Tok.tx2) }
                    }
                }
            }
        }
    }
}

/** "+ N queued" collapse, expanding in place (panel density). */
@Composable
private fun DeskQueuedRow(queued: List<WorkflowAgentSnap>) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(7.dp))
                .clickable { open = !open }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { HollowDot(7.dp) }
            Text("+ ${queued.size} queued", color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.rotate(if (open) 180f else 90f)) { ChevronRight(Tok.muted, 12.dp) }
        }
        if (open) queued.forEach { a ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { HollowDot(7.dp) }
                Text(a.label, color = Tok.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("queued", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
            }
        }
    }
}
