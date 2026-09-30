package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoListStatus
import dev.ccpocket.app.memo.MemoNewSessionOptions
import dev.ccpocket.app.memo.MemoProjectRow
import dev.ccpocket.app.memo.MemoProjectSessions
import dev.ccpocket.app.memo.MemoTarget
import dev.ccpocket.app.memo.MemoTargetRow
import dev.ccpocket.app.memo.MemoTargetStatus
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.protocol.AgentKind
import org.jetbrains.compose.resources.stringResource

// ── target labels ───────────────────────────────────────────────────────────────────────────────────

/** A target's project: the recorded label, else the working directory's last segment. */
internal fun targetProject(t: MemoTarget): String =
    t.project.ifBlank { t.workdir.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { t.workdir } }

/** A session's name; "新会话" for one that has none yet (just created), "新建会话" for one still to be created. */
@Composable
internal fun targetTitle(t: MemoTarget): String = when {
    t.newSession -> stringResource(Res.string.memo_target_new_session)
    else -> t.title.ifBlank { stringResource(Res.string.memo_target_untitled) }
}

/** "项目 · 电脑" — the mono line above a session name. */
internal fun targetProjectLine(t: MemoTarget, computer: String): String = listOf(targetProject(t), computer).joinToString(MEMO_SEP)

/** "项目 · 会话 · Agent", or "项目 · 新建会话 · Agent" — the result page's target entry. */
@Composable
internal fun targetSummary(t: MemoTarget): String = listOf(targetProject(t), targetTitle(t), agentName(t.agent)).joinToString(MEMO_SEP)

/** "项目 · 会话" — the compact line. */
@Composable
internal fun targetShort(t: MemoTarget): String = listOf(targetProject(t), targetTitle(t)).joinToString(MEMO_SEP)

// ── picker ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * Target picker v3 (TARGET_PICKER_BRIEF.md): level 1 = recent sessions + the bound computer's projects,
 * level 2 = one project's "new session here" plus its sessions. Rendered from [MemoUiState.catalog] alone —
 * which level shows is [dev.ccpocket.app.memo.MemoTargetCatalog.project], so the data layer remembers where
 * the user was. There is no default target: nothing is selected until the user taps. Opening the page asks
 * for a fresh catalog.
 */
@Composable
internal fun MemoTargetPicker(state: MemoUiState, onAction: (MemoAction) -> Unit, onClose: () -> Unit) {
    val gutter = LocalMemoGutter.current
    val act by rememberUpdatedState(onAction)
    LaunchedEffect(Unit) { act(MemoAction.RefreshTargets) }
    val catalog = state.catalog
    val project = catalog.project
    val computer = computerName(state.readiness)
    val back: () -> Unit = if (project != null) ({ onAction(MemoAction.CloseTargetProject) }) else onClose
    SystemBackHandler(enabled = true) { back() }
    val selected = state.selection.target?.target
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = gutter)) {
            MemoBackRow(stringResource(if (project != null) Res.string.memo_targets_back_projects_cd else Res.string.memo_target_back_cd), back)
            MemoText(
                project?.name ?: stringResource(Res.string.memo_target_choose),
                MemoType.title, Tok.tx, Modifier.padding(top = 2.dp).semantics { heading() },
            )
            val sub = if (project != null) {
                val count = project.sessions.size
                listOf(
                    computer,
                    if (project.status == MemoListStatus.READY && count == 0) stringResource(Res.string.memo_project_no_sessions)
                    else stringResource(Res.string.memo_project_sessions, count),
                    stringResource(Res.string.memo_project_recent_first),
                ).let { if (project.status == MemoListStatus.READY) it else it.take(1) }.joinToString(MEMO_SEP)
            } else stringResource(Res.string.memo_targets_sub_projects, computer)
            MemoText(sub, MemoType.caption, Tok.tx2, Modifier.padding(top = 8.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 16.dp)) {
            if (project == null) RootLevel(state, computer, selected, onAction, onClose)
            else ProjectLevel(project, catalog.newSession, computer, selected, onAction, onClose)
            Spacer(Modifier.height(20.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Column {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.height(lineHeightDp(MemoType.caption)), contentAlignment = Alignment.Center) { MemoSpinner() }
            MemoText(text, MemoType.caption, Tok.tx2, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FailedBlock(title: String, body: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    MemoNoticeBlock(MemoMark.SQUARE, Tok.danger, title, body, modifier) {
        // pulled onto the text's left edge: its 10 dp padding is touch area, not indent
        MemoTextAction(stringResource(Res.string.memo_rec_retry), modifier = Modifier.padding(top = 6.dp).offset(x = (-10).dp), onClick = onRetry)
    }
}

@Composable
private fun RootLevel(state: MemoUiState, computer: String, selected: MemoTarget?, onAction: (MemoAction) -> Unit, onClose: () -> Unit) {
    val catalog = state.catalog
    when (catalog.status) {
        MemoListStatus.LOADING -> LoadingRow(stringResource(Res.string.memo_targets_loading, computer))
        MemoListStatus.FAILED -> FailedBlock(
            stringResource(Res.string.memo_targets_failed_title),
            stringResource(Res.string.memo_targets_failed_body, computer),
        ) { onAction(MemoAction.RefreshTargets) }
        MemoListStatus.READY -> {
            if (catalog.recent.isEmpty() && catalog.projects.isEmpty()) {
                Hairline()
                MemoText(stringResource(Res.string.memo_targets_no_projects, computer), MemoType.caption, Tok.tx2, Modifier.padding(vertical = 14.dp))
                return
            }
            if (catalog.recent.isNotEmpty()) {
                MemoSectionLabel(stringResource(Res.string.memo_targets_recent))
                Column(Modifier.padding(top = 10.dp)) {
                    Hairline()
                    catalog.recent.forEach { row ->
                        SessionOption(row, computer, showProject = true, current = selected.isSameSession(row.target)) {
                            onAction(MemoAction.SelectTarget(row.target))
                            onClose()
                        }
                    }
                }
            }
            if (catalog.projects.isNotEmpty()) {
                MemoSectionLabel(stringResource(Res.string.memo_targets_projects), Modifier.padding(top = if (catalog.recent.isEmpty()) 0.dp else 22.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    Hairline()
                    catalog.projects.forEach { p ->
                        ProjectOption(p, holdsTarget = selected != null && selected.workdir == p.workdir) {
                            onAction(MemoAction.OpenTargetProject(p.workdir))
                        }
                    }
                }
            }
        }
    }
}

/** The current target is this very session (never a still-to-be-created one). */
private fun MemoTarget?.isSameSession(t: MemoTarget): Boolean = this != null && !newSession && this == t

@Composable
private fun ProjectLevel(
    project: MemoProjectSessions,
    options: MemoNewSessionOptions,
    computer: String,
    selected: MemoTarget?,
    onAction: (MemoAction) -> Unit,
    onClose: () -> Unit,
) {
    Hairline()
    NewSessionEntry(project.workdir, options, selected, onAction, onClose)
    when (project.status) {
        MemoListStatus.LOADING -> LoadingRow(stringResource(Res.string.memo_project_loading))
        MemoListStatus.FAILED -> FailedBlock(
            stringResource(Res.string.memo_project_failed_title),
            stringResource(Res.string.memo_project_failed_body),
            Modifier.padding(top = 16.dp),
        ) { onAction(MemoAction.OpenTargetProject(project.workdir)) }
        MemoListStatus.READY -> if (project.sessions.isEmpty()) {
            MemoText(stringResource(Res.string.memo_project_empty), MemoType.caption, Tok.tx2, Modifier.padding(vertical = 16.dp))
        } else {
            MemoSectionLabel(
                listOf(stringResource(Res.string.memo_back_sessions), stringResource(Res.string.memo_project_recent_first)).joinToString(MEMO_SEP),
                Modifier.padding(top = 20.dp),
            )
            Column(Modifier.padding(top = 10.dp)) {
                Hairline()
                project.sessions.forEach { row ->
                    SessionOption(row, computer, showProject = false, current = selected.isSameSession(row.target)) {
                        onAction(MemoAction.SelectTarget(row.target))
                        onClose()
                    }
                }
            }
        }
    }
}

// ── rows ────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionOption(row: MemoTargetRow, computer: String, showProject: Boolean, current: Boolean, onPick: () -> Unit) {
    val ok = row.selectable
    val running = row.status == MemoTargetStatus.RUNNING
    val (mark, tint) = when {
        !ok -> MemoMark.RING to Tok.muted
        running -> MemoMark.DOT to Tok.ok
        else -> MemoMark.RING to Tok.tx2
    }
    val status = if (ok) targetStatusLabel(row.status) else stringResource(Res.string.memo_target_unselectable, targetStatusLabel(row.status))
    val reason = targetStatusReason(row.status)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                // guarded as well as disabled: an unselectable row never selects, whatever activates it
                .selectable(selected = current, enabled = ok, role = Role.RadioButton) { if (ok) onPick() }
                .heightIn(min = Metric.touch)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            LineMark(mark, tint, MemoType.row)
            Column(Modifier.weight(1f)) {
                MemoText(targetTitle(row.target), MemoType.row, if (ok) Tok.tx else Tok.tx2)
                // mono / caption / strong caption side by side: all tightCenter-based, wrapping as a flow
                FlowRow(
                    Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showProject) MemoText(targetProjectLine(row.target, computer), MemoType.mono, Tok.muted)
                    MemoText(agentName(row.target.agent), MemoType.caption, Tok.tx2)
                    MemoText(status, MemoType.captionStrong, if (!ok) Tok.muted else if (running) Tok.ok else Tok.tx2)
                    if (row.lastModifiedMs > 0) MemoText(memoTimeLabel(row.lastModifiedMs), MemoType.mono, Tok.muted)
                    if (current) MemoText(stringResource(Res.string.memo_target_current), MemoType.captionStrong, Tok.accent)
                }
                if (reason != null) MemoText(reason, MemoType.caption, Tok.tx2, Modifier.padding(top = 5.dp))
            }
            if (current) Box(Modifier.height(lineHeightDp(MemoType.row)), contentAlignment = Alignment.Center) { CheckGlyph() }
        }
        Hairline()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectOption(p: MemoProjectRow, holdsTarget: Boolean, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.height(lineHeightDp(MemoType.row)), contentAlignment = Alignment.Center) { FolderGlyph() }
            Column(Modifier.weight(1f)) {
                MemoText(p.name, MemoType.row, Tok.tx)
                FlowRow(
                    Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (val n = p.sessionCount) {
                        null -> Unit // not counted: say nothing rather than guess
                        0 -> MemoText(stringResource(Res.string.memo_project_no_sessions), MemoType.caption, Tok.tx2)
                        else -> MemoText(stringResource(Res.string.memo_project_sessions, n), MemoType.caption, Tok.tx2)
                    }
                    if (p.running) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LineMark(MemoMark.DOT, Tok.ok, MemoType.captionStrong)
                        MemoText(stringResource(Res.string.memo_project_running), MemoType.captionStrong, Tok.ok)
                    }
                    if (holdsTarget) MemoText(stringResource(Res.string.memo_project_holds_target), MemoType.caption, Tok.accent)
                }
            }
            Box(Modifier.height(lineHeightDp(MemoType.row)), contentAlignment = Alignment.Center) { ForwardChevron() }
        }
        Hairline()
    }
}

/**
 * "在这个项目新建会话" — always the first row of level 2. Expands to the agent choice (the computer's agents,
 * default marked and preselected), the app's default permission mode read-only, and "选为目标会话". Nothing
 * is created here: the session is created by the dispatch.
 */
@Composable
private fun NewSessionEntry(
    workdir: String,
    options: MemoNewSessionOptions,
    selected: MemoTarget?,
    onAction: (MemoAction) -> Unit,
    onClose: () -> Unit,
) {
    val available = options.agents.isNotEmpty()
    val isTarget = selected != null && selected.newSession && selected.workdir == workdir
    var open by remember(workdir) { mutableStateOf(isTarget) }
    var agent by remember(workdir, options.agents) {
        mutableStateOf(
            when {
                isTarget && selected!!.agent in options.agents -> selected.agent
                options.defaultAgent in options.agents -> options.defaultAgent
                else -> options.agents.firstOrNull() ?: options.defaultAgent
            },
        )
    }
    val expandedState = stringResource(if (open) Res.string.memo_expanded else Res.string.memo_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .clickable(enabled = available, role = Role.Button) { if (available) open = !open }
                .semantics { if (available) stateDescription = expandedState }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.height(lineHeightDp(MemoType.row)), contentAlignment = Alignment.Center) { DashedPlus(if (available) Tok.accent else Tok.muted) }
            Column(Modifier.weight(1f)) {
                MemoText(stringResource(Res.string.memo_new_session_in_project), MemoType.row, if (available) Tok.accent else Tok.muted)
                when {
                    !available -> MemoText(stringResource(Res.string.memo_new_session_offline), MemoType.caption, Tok.tx2, Modifier.padding(top = 4.dp))
                    isTarget -> MemoText(
                        stringResource(Res.string.memo_new_session_selected, agentName(selected!!.agent)),
                        MemoType.caption, Tok.tx2, Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (available) Box(Modifier.height(lineHeightDp(MemoType.row)), contentAlignment = Alignment.Center) { DisclosureChevron(open) }
        }
        Hairline()
        if (open && available) Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp)) {
            MemoSectionLabel(stringResource(Res.string.memo_agent_label))
            Column(Modifier.padding(top = 8.dp)) {
                Hairline()
                options.agents.forEach { a -> AgentOption(a, isDefault = a == options.defaultAgent, chosen = a == agent) { agent = a } }
            }
            MemoSectionLabel(stringResource(Res.string.memo_permission_mode), Modifier.padding(top = 16.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineMark(MemoMark.RING, Tok.muted, MemoType.body)
                Column(Modifier.weight(1f)) {
                    MemoText(
                        options.modeFor(agent)?.let { permissionModeName(it) } ?: stringResource(Res.string.memo_permission_app_default),
                        MemoType.body, Tok.tx,
                    )
                    MemoText(stringResource(Res.string.memo_permission_readonly_note), MemoType.caption, Tok.tx2, Modifier.padding(top = 3.dp))
                }
            }
            MemoOutlineButton(
                stringResource(Res.string.memo_pick_new_session), tint = Tok.accent,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                onAction(MemoAction.SelectNewSession(workdir, agent))
                onClose()
            }
        }
    }
}

@Composable
private fun AgentOption(agent: AgentKind, isDefault: Boolean, chosen: Boolean, onPick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // the agent's identity dot — decorative, the name carries it
            Box(Modifier.size(10.dp).clip(CircleShape).background(agentColor(agent)))
            // name and the bordered "默认" tag share a row with geometry: both tightCenter
            MemoText(
                agentName(agent),
                tightCenter(15.sp).merge(TextStyle(fontSize = 15.sp, fontWeight = if (chosen) androidx.compose.ui.text.font.FontWeight.SemiBold else null)),
                Tok.tx, Modifier.weight(1f),
            )
            if (isDefault) {
                val shape = RoundedCornerShape(5.dp)
                MemoText(
                    stringResource(Res.string.memo_default), tightCenter(10.5.sp).merge(TextStyle(fontSize = 10.5.sp)), Tok.tx2,
                    Modifier.clip(shape).border(Metric.hairline, Tok.tx2.copy(alpha = 0.45f), shape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { if (chosen) CheckGlyph() }
        }
        Hairline()
    }
}

// ── glyphs ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CheckGlyph() {
    val tint = Tok.accent
    Canvas(Modifier.size(18.dp).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val p = Path().apply { moveTo(w * 0.19f, h * 0.53f); lineTo(w * 0.39f, h * 0.73f); lineTo(w * 0.84f, h * 0.28f) }
        drawPath(p, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FolderGlyph() {
    val tint = Tok.tx2
    Canvas(Modifier.size(width = 19.dp, height = 16.dp).clearAndSetSemantics { }) {
        val s = 1.3.dp.toPx()
        val tab = Path().apply {
            moveTo(s, size.height * 0.28f); lineTo(s, s * 1.5f); lineTo(size.width * 0.42f, s * 1.5f); lineTo(size.width * 0.52f, size.height * 0.28f)
        }
        drawPath(tab, tint, style = Stroke(s, join = StrokeJoin.Round))
        drawRoundRect(
            tint, topLeft = Offset(s / 2, size.height * 0.28f), size = Size(size.width - s, size.height * 0.72f - s / 2),
            cornerRadius = CornerRadius(2.5.dp.toPx()), style = Stroke(s),
        )
    }
}

@Composable
private fun DashedPlus(tint: Color) {
    Canvas(Modifier.size(22.dp).clearAndSetSemantics { }) {
        val s = 1.6.dp.toPx()
        drawRoundRect(
            tint, topLeft = Offset(s / 2, s / 2), size = Size(size.width - s, size.height - s),
            cornerRadius = CornerRadius(7.dp.toPx()),
            style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        )
        val c = size.width / 2
        val arm = size.width * 0.24f
        drawLine(tint, Offset(c - arm, c), Offset(c + arm, c), s, cap = StrokeCap.Round)
        drawLine(tint, Offset(c, c - arm), Offset(c, c + arm), s, cap = StrokeCap.Round)
    }
}
