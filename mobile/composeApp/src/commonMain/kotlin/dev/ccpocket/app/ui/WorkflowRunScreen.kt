package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.WorkflowPhaseGroup
import dev.ccpocket.app.data.WorkflowPhaseStatus
import dev.ccpocket.app.data.WorkflowUi
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.WorkflowAgentSnap
import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus

// ════════════════════════════════════════════════════════════════════
//  Full-screen Workflow run view (issue #106): phase-grouped progress
//  tree while live; Final return + [Phases | Journal] once terminal;
//  tapping any agent opens the detail sheet (Prompt / Return zones).
//  Pixel spec: workflow-view/scene-only.jsx Frames B (run + journal)
//  and the AgentDetailSheet. Failures never fold: a collapsed phase
//  pins its failed rows under the header.
// ════════════════════════════════════════════════════════════════════

@Composable
internal fun WorkflowRunScreen(repo: PocketRepository, onBack: () -> Unit) {
    val runId = repo.viewedWorkflowRunId.value ?: return
    val run = repo.workflowRuns[runId] ?: run { onBack(); return }
    val groups = remember(run) { WorkflowUi.phaseGroups(run) }
    val terminal = run.status != WorkflowRunStatus.RUNNING && run.status != WorkflowRunStatus.UNKNOWN
    var tab by remember(runId, terminal) { mutableStateOf(if (terminal) WfTab.Journal else WfTab.Phases) }
    var sheetAgent by remember(runId) { mutableStateOf<WorkflowAgentSnap?>(null) }
    val scroll = rememberScrollState()
    // Android back closes the sheet first, then the run view — never the whole chat (same as TerminalScreen)
    dev.ccpocket.app.SystemBackHandler(enabled = true) { if (sheetAgent != null) sheetAgent = null else onBack() }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        // ── header: back · tile · name + runId · status pill ──
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                ChevronLeft(Tok.tx2, 17.dp)
            }
            WorkflowTile(WorkflowUi.variant(run), 26.dp, 7.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    run.name, color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    run.runId.take(10) + "…", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            WorkflowStatusPill(run)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {
                if (terminal) {
                    run.finalResult?.let { fr ->
                        Box(Modifier.padding(top = 14.dp)) { FinalReturnCard(fr) }
                    }
                    Box(Modifier.padding(top = 16.dp)) {
                        WfSegmented(tab) { tab = it }
                    }
                    Box(Modifier.height(12.dp))
                }
                when (tab) {
                    WfTab.Phases -> {
                        groups.forEach { g ->
                            PhaseSection(g, defaultOpen = g.status == WorkflowPhaseStatus.ACTIVE, onOpenAgent = { sheetAgent = it })
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    }
                    WfTab.Journal -> {
                        val rows = WorkflowUi.journalRows(run)
                        Text(
                            "${rows.size} agent() calls · chronological",
                            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, letterSpacing = 0.6.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        rows.forEach { a -> JournalRow(a) { sheetAgent = a } }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    }
                }
                Box(Modifier.height(if (terminal) 40.dp else 120.dp)) // tail spacer
            }
            // jump-to-active pill — live tree only, after scrolling away
            if (!terminal && scroll.value > 400) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
                    val shape = RoundedCornerShape(999.dp)
                    Row(
                        Modifier.clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape)
                            .clickable { /* scroll home = where the active phase expands */ }
                            .padding(start = 13.dp, end = 15.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.rotate(90f)) { ChevronRight(Tok.accent, 14.dp) }
                        Text("Jump to active", color = Tok.tx, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    sheetAgent?.let { agent ->
        WorkflowAgentSheet(repo, run, agent) { sheetAgent = null }
    }
}

private enum class WfTab { Phases, Journal }

/** [Phases | Journal] segmented control (scene-only.jsx Segmented). */
@Composable
private fun WfSegmented(value: WfTab, onChange: (WfTab) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        WfTab.entries.forEach { t ->
            val selected = t == value
            val innerShape = RoundedCornerShape(7.dp)
            Box(
                Modifier.weight(1f).clip(innerShape)
                    .background(if (selected) Tok.raised else Tok.base.copy(alpha = 0f))
                    .then(if (selected) Modifier.border(1.dp, Tok.hair, innerShape) else Modifier)
                    .clickable { onChange(t) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.name, color = if (selected) Tok.tx else Tok.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Header status pill: live = accent tint + shared pulse + elapsed; ✓ green / ✗ danger once settled. */
@Composable
internal fun WorkflowStatusPill(run: WorkflowRun) {
    val shape = RoundedCornerShape(999.dp)
    when (run.status) {
        WorkflowRunStatus.RUNNING, WorkflowRunStatus.UNKNOWN -> Row(
            Modifier.clip(shape).background(Tok.accent.copy(alpha = 0.14f)).border(1.dp, Tok.accent.copy(alpha = 0.4f), shape)
                .padding(start = 10.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SyncPulseDot(6.dp)
            Text(workflowElapsed(run.startedAt), color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        WorkflowRunStatus.COMPLETED -> Row(
            Modifier.clip(shape).background(Tok.ok.copy(alpha = 0.15f)).border(1.dp, Tok.ok.copy(alpha = 0.38f), shape)
                .padding(start = 9.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WfCheckGlyph(Tok.ok, 13.dp)
            Text(WorkflowUi.formatDuration(run.durationMs), color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        WorkflowRunStatus.FAILED, WorkflowRunStatus.KILLED -> Row(
            Modifier.clip(shape).background(Tok.danger.copy(alpha = 0.13f)).border(1.dp, Tok.danger.copy(alpha = 0.4f), shape)
                .padding(start = 9.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WfCrossGlyph(Tok.danger, 12.dp)
            Text(WorkflowUi.formatDuration(run.durationMs), color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** One agent's status glyph in its 16dp slot. */
@Composable
internal fun AgentStateGlyph(state: WorkflowAgentState) {
    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
        when (state) {
            WorkflowAgentState.RUNNING -> SyncPulseDot(7.dp)
            WorkflowAgentState.DONE -> WfCheckGlyph(Tok.ok, 14.dp)
            WorkflowAgentState.FAILED -> WfCrossGlyph(Tok.danger, 13.dp)
            WorkflowAgentState.QUEUED -> HollowDot(8.dp)
            WorkflowAgentState.UNKNOWN -> HollowDot(8.dp)
        }
    }
}

/** One agent row of the tree — 40dp: glyph slot · label · duration. */
@Composable
private fun AgentRow(a: WorkflowAgentSnap, onOpen: (WorkflowAgentSnap) -> Unit) {
    val queued = a.state == WorkflowAgentState.QUEUED || a.state == WorkflowAgentState.UNKNOWN
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onOpen(a) }.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AgentStateGlyph(a.state)
        Text(
            a.label, color = if (queued) Tok.muted else Tok.tx, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(
            when (a.state) {
                WorkflowAgentState.RUNNING -> a.startedAt?.let { workflowElapsed(it) } ?: "…"
                WorkflowAgentState.QUEUED -> "queued"
                else -> WorkflowUi.formatDuration(a.durationMs)
            },
            color = if (queued) Tok.muted else Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
    }
}

/** The queued-overflow row: "+ N queued", expanding in place. */
@Composable
private fun QueuedRow(queued: List<WorkflowAgentSnap>, onOpen: (WorkflowAgentSnap) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { open = !open }.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { HollowDot(8.dp) }
            Text("+ ${queued.size} queued", color = Tok.tx2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Box(Modifier.rotate(if (open) 180f else 0f)) { ChevronDown(Tok.muted, 15.dp) }
        }
        if (open) queued.forEach { AgentRow(it, onOpen) }
    }
}

/**
 * A phase disclosure section. Completed phases collapse to one summary line; failures NEVER fold —
 * a collapsed phase pins its failed rows. Pending phases are a dimmed one-liner. When the active
 * phase has many queued agents they collapse into one "+ N queued" row after the first few.
 */
@Composable
internal fun PhaseSection(
    g: WorkflowPhaseGroup,
    defaultOpen: Boolean,
    dense: Boolean = false,
    onOpenAgent: (WorkflowAgentSnap) -> Unit,
) {
    var open by remember(g.index) { mutableStateOf(defaultOpen) }
    val pending = g.status == WorkflowPhaseStatus.PENDING
    val done = g.status == WorkflowPhaseStatus.DONE

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        // header
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (dense) 38.dp else 46.dp)
                .then(if (!pending) Modifier.clickable { open = !open } else Modifier)
                .alpha(if (pending) 0.62f else 1f)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.alpha(if (pending) 0f else 1f).rotate(if (open) 90f else 0f)) { ChevronRight(Tok.muted, 14.dp) }
            Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                when (g.status) {
                    WorkflowPhaseStatus.ACTIVE -> SyncPulseDot(7.dp)
                    WorkflowPhaseStatus.DONE -> WfCheckGlyph(Tok.ok, 14.dp)
                    WorkflowPhaseStatus.PENDING -> HollowDot(8.dp)
                }
            }
            Text(
                g.title, color = if (pending) Tok.muted else Tok.tx,
                fontSize = if (dense) 13.sp else 14.sp, fontWeight = FontWeight.Medium,
            )
            if (g.failed > 0) FailChip(g.failed, dense)
            Box(Modifier.weight(1f))
            Text(
                "${g.done}/${g.total}", color = if (pending) Tok.muted else Tok.tx2,
                fontFamily = FontFamily.Monospace, fontSize = if (dense) 11.sp else 12.sp,
            )
            Text(
                if (pending) "—" else WorkflowUi.formatDuration(g.durationMs),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = if (dense) 11.sp else 12.sp,
                modifier = Modifier.widthIn(min = 44.dp), maxLines = 1,
            )
        }

        when {
            // collapsed completed → one summary line + pinned failures (never folded)
            done && !open -> Column(Modifier.padding(bottom = 10.dp)) {
                Row(
                    Modifier.padding(start = 42.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    WfCheckGlyph(Tok.ok, 12.dp)
                    Text(
                        if (g.failed > 0) "${g.done} passed · ${g.failed} failed · ${WorkflowUi.formatDuration(g.durationMs)}"
                        else "${g.total} agents · ${WorkflowUi.formatDuration(g.durationMs)}",
                        color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
                if (g.failed > 0) Column(Modifier.padding(start = 42.dp, top = 4.dp)) {
                    g.agents.filter { it.state == WorkflowAgentState.FAILED }.forEach { AgentRow(it) { a -> onOpenAgent(a) } }
                }
            }
            // pending → dimmed one-liner
            pending -> Text(
                if (g.total > 0) "${g.total} agents queued" else "waiting",
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.alpha(0.62f).padding(start = 42.dp, bottom = 12.dp),
            )
            // expanded body: running first, then settled, queued collapsed past the first few
            open -> Column(Modifier.padding(start = 42.dp, bottom = 12.dp)) {
                val (queued, rest) = g.agents.partition { it.state == WorkflowAgentState.QUEUED }
                rest.forEach { AgentRow(it, onOpenAgent) }
                when {
                    queued.size > 3 -> QueuedRow(queued, onOpenAgent)
                    else -> queued.forEach { AgentRow(it, onOpenAgent) }
                }
            }
        }
    }
}

/** Journal row — "#07 · glyph · label + one-line return preview · duration" (52dp). */
@Composable
internal fun JournalRow(a: WorkflowAgentSnap, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen).padding(horizontal = 2.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                "#${a.index.toString().padStart(2, '0')}",
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.width(26.dp),
            )
            AgentStateGlyph(a.state)
            Column(Modifier.weight(1f)) {
                Text(a.label, color = Tok.tx, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val failed = a.state == WorkflowAgentState.FAILED
                val preview = if (failed) (a.error?.lineSequence()?.firstOrNull() ?: "failed") else a.resultPreview
                if (!preview.isNullOrBlank()) Text(
                    preview, color = if (failed) Tok.danger else Tok.muted, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                WorkflowUi.formatDuration(a.durationMs), color = Tok.tx2,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
    }
}

/** Pinned Final return card — mono, collapsed to ~6 lines, Expand + Copy (FinalReturnCard in the spec). */
@Composable
internal fun FinalReturnCard(text: String) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape)) {
        Text(
            "FINAL RETURN", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 1.3.sp,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp),
        )
        Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp).heightIn(max = if (open) 400.dp else 130.dp)) {
            SelectionContainer {
                Text(
                    text, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 21.sp,
                    maxLines = if (open) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                    modifier = if (open) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) "Collapse" else "Expand",
                color = Tok.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { open = !open },
            )
            CopyChip(text)
        }
    }
}

/**
 * The agent detail sheet — Prompt (collapsed quote block) + Return (scrollable report zone with a
 * copy footer; error text behind a thin danger hairline). Fetches the FULL prompt/return on open;
 * previews stand in until (or if — old daemon) it lands.
 */
@Composable
private fun WorkflowAgentSheet(repo: PocketRepository, run: WorkflowRun, a: WorkflowAgentSnap, onDismiss: () -> Unit) {
    LaunchedEffect(run.runId, a.index) { repo.fetchWorkflowAgentDetail(run.runId, a.index, a.agentId) }
    val detail = repo.workflowAgentDetails["${run.runId}#${a.index}"]
    val failed = a.state == WorkflowAgentState.FAILED
    val prompt = detail?.prompt ?: a.promptPreview
    val result = detail?.result ?: a.resultPreview

    PocketSheet(onDismiss) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            val shape = RoundedCornerShape(8.dp)
            val (tint, bd, ink) = when {
                failed -> Triple(Tok.danger.copy(alpha = 0.13f), Tok.danger.copy(alpha = 0.4f), Tok.danger)
                a.state == WorkflowAgentState.DONE -> Triple(Tok.ok.copy(alpha = 0.15f), Tok.ok.copy(alpha = 0.38f), Tok.ok)
                else -> Triple(Tok.accent.copy(alpha = 0.14f), Tok.accent.copy(alpha = 0.4f), Tok.accent)
            }
            Box(Modifier.size(30.dp).clip(shape).background(tint).border(1.dp, bd, shape), contentAlignment = Alignment.Center) {
                when {
                    failed -> WfCrossGlyph(ink, 14.dp)
                    a.state == WorkflowAgentState.DONE -> WfCheckGlyph(ink, 15.dp)
                    a.state == WorkflowAgentState.RUNNING -> SyncPulseDot(7.dp)
                    else -> HollowDot(8.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    a.label, color = Tok.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        a.phaseIndex?.let { pi -> run.phases.firstOrNull { it.index == pi }?.let { "phase · ${it.title}" } },
                        a.durationMs?.let { WorkflowUi.formatDuration(it) },
                        "agent-${a.index.toString().padStart(2, '0')}",
                    ).joinToString(" — "),
                    color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!prompt.isNullOrBlank()) PromptBlock(prompt)
            ReturnZone(failed, a, result)
        }
    }
}

/** Prompt as a raised quote block, collapsed to 2 lines behind "more". */
@Composable
private fun PromptBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            "PROMPT", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 1.3.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape).padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.5.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Tok.hair))
                SelectionContainer(Modifier.padding(start = 11.dp)) {
                    Text(
                        text, color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp,
                        maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (open) "less" else "more",
                color = Tok.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 13.5.dp, top = 6.dp).clickable { open = !open },
            )
        }
    }
}

/** Return / Error zone — SubagentCard's report language: eyebrow, capped scroll, copy footer.
 *  Danger only on the hairline + eyebrow + text, the sheet stays calm. */
@Composable
private fun ReturnZone(failed: Boolean, a: WorkflowAgentSnap, result: String?) {
    Column {
        Text(
            if (failed) "ERROR" else "RETURN",
            color = if (failed) Tok.danger else Tok.muted,
            fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 1.3.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val shape = RoundedCornerShape(12.dp)
        val body = if (failed) (a.error ?: result ?: "failed") else result
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Tok.base)
                .border(1.dp, if (failed) Tok.danger.copy(alpha = 0.4f) else Tok.hair, shape),
        ) {
            Box(
                Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                when {
                    body == null -> Text("running…", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    failed -> SelectionContainer {
                        Text(body, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.5.sp)
                    }
                    else -> SelectionContainer { MarkdownText(body, Tok.tx2) }
                }
            }
            if (body != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        listOfNotNull(a.model, a.durationMs?.let { WorkflowUi.formatDuration(it) }, if (a.cached) "cached" else null)
                            .joinToString(" · "),
                        color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    CopyChip(body)
                }
            }
        }
    }
}

/** Left chevron (handoff viewBox 18). */
@Composable
internal fun ChevronLeft(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) =
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val u = this.size.width / 18f
        drawPath(
            androidx.compose.ui.graphics.Path().apply { moveTo(11 * u, 3 * u); lineTo(5 * u, 9 * u); lineTo(11 * u, 15 * u) },
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }

/** Down chevron (handoff viewBox 18). */
@Composable
internal fun ChevronDown(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) =
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val u = this.size.width / 18f
        drawPath(
            androidx.compose.ui.graphics.Path().apply { moveTo(3 * u, 6 * u); lineTo(9 * u, 12 * u); lineTo(15 * u, 6 * u) },
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.9f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
