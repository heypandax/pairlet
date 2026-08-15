package dev.ccpocket.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.new_task_agent
import dev.ccpocket.app.resources.new_task_agent_unavailable
import dev.ccpocket.app.resources.new_task_all_projects
import dev.ccpocket.app.resources.new_task_browse_other
import dev.ccpocket.app.resources.new_task_close
import dev.ccpocket.app.resources.new_task_failed
import dev.ccpocket.app.resources.new_task_no_projects
import dev.ccpocket.app.resources.new_task_opening
import dev.ccpocket.app.resources.new_task_pick_agent
import dev.ccpocket.app.resources.new_task_pick_project
import dev.ccpocket.app.resources.new_task_placeholder
import dev.ccpocket.app.resources.new_task_project
import dev.ccpocket.app.resources.new_task_recent
import dev.ccpocket.app.resources.new_task_search_projects
import dev.ccpocket.app.resources.new_task_send
import dev.ccpocket.app.resources.new_task_send_failed
import dev.ccpocket.app.resources.new_task_timeout
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import org.jetbrains.compose.resources.stringResource

/**
 * The new-task composer (issue #260) — what the Projects FAB opens.
 *
 * The screen's ONE way to start work: a multi-line prompt on top, and under it the two decisions that
 * creating a session actually needs — which project, which agent — as chips that are always visible and
 * always one tap from changing. Mode and model are deliberately absent: they follow their persisted
 * defaults here, and the full [dev.ccpocket.app.ui.entry.ConfigureSessionSheet] still owns the case where
 * someone wants to decide them (issue #260's boundary).
 *
 * Everything the user has put in — the draft and both chip picks — lives on [PocketRepository], never in a
 * `remember` here. Two reasons, and the second is the load-bearing one: a dismissed sheet must re-open with
 * the same text (the sheet is a scratchpad, not a form), and a queued prompt that fails to deliver has to
 * hand the text back to a sheet that no longer exists at the moment of failure (see #256's note — the same
 * lesson, from the surface that learned it first).
 */
@Composable
internal fun NewTaskSheet(
    repo: PocketRepository,
    dirs: List<DirectoryEntry>,
    onBrowseOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val recents = remember(dirs) { recentProjects(dirs) }
    // Prefill, resolved every recomposition rather than latched: the chips hold a USER pick (nullable), so
    // "not chosen yet" keeps tracking the newest recent instead of freezing whatever was on top the first
    // time the sheet opened. A pick that names a project the daemon no longer reports falls back the same way.
    val dir = repo.newTaskDir.value ?: recents.firstOrNull()?.path
    val agent = repo.newTaskAgent.value ?: repo.sessionDefaultAgent
    var panel by remember { mutableStateOf(NewTaskPanel.NONE) }
    val busy = repo.newTaskStarting.value
    val draft = repo.newTaskDraft.value

    val send = {
        val text = repo.newTaskDraft.value
        if (text.isNotBlank() && dir != null && !busy) {
            // The sheet closes on the ATTEMPT, not on delivery. That is what makes "send → you are in the
            // conversation" true without a navigation call: the root router renders the chat the moment
            // convoId lands, and this sheet is already out of the way. A failure re-opens it (the caller
            // watches newTaskError) with the draft and both chip picks exactly as they were.
            if (repo.startTaskWithPrompt(dir, text, agent)) onDismiss()
        }
    }

    PocketSheet(onDismiss, dropKeyboard = false) {
        Column(Modifier.padding(horizontal = Metric.gutter).padding(bottom = Metric.gap, top = Metric.gapXs)) {
            when (panel) {
                NewTaskPanel.PROJECT -> ProjectPanel(
                    recents = recents,
                    all = dirs,
                    selected = dir,
                    onPick = { repo.newTaskDir.value = it; panel = NewTaskPanel.NONE },
                    onBrowseOther = onBrowseOther,
                    onClose = { panel = NewTaskPanel.NONE },
                )
                NewTaskPanel.AGENT -> AgentPanel(
                    selected = agent,
                    available = repo.availableAgents,
                    onPick = { repo.newTaskAgent.value = it; panel = NewTaskPanel.NONE },
                    onClose = { panel = NewTaskPanel.NONE },
                )
                NewTaskPanel.NONE -> Unit
            }

            NewTaskField(
                value = draft,
                enabled = !busy,
                // the field takes focus only while no picker is over it — otherwise the keyboard fights the
                // panel for the same bottom inset, the exact wedge PocketSheet's clearFocus exists to avoid
                autoFocus = panel == NewTaskPanel.NONE,
                onValueChange = { repo.newTaskDraft.value = it; repo.newTaskError.value = null },
            )

            Spacer(Modifier.height(Metric.gapS + 2.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TaskChip(
                    // middle-truncated, like the model chip's ids: a project's identity is its head AND its
                    // tail (…-site vs …-server), so a tail-ellipsis would collapse siblings into one label
                    label = dir?.let { midTruncateModel(folderLeaf(it), head = 8, tail = 5) }
                        ?: stringResource(Res.string.new_task_pick_project),
                    contentDescription = stringResource(Res.string.new_task_project),
                    open = panel == NewTaskPanel.PROJECT,
                    mono = true,
                    leading = { FolderGlyph(it) },
                ) { panel = if (panel == NewTaskPanel.PROJECT) NewTaskPanel.NONE else NewTaskPanel.PROJECT }
                Spacer(Modifier.width(Metric.gapS))
                TaskChip(
                    label = agentName(agent),
                    contentDescription = stringResource(Res.string.new_task_agent),
                    open = panel == NewTaskPanel.AGENT,
                    mono = false,
                    leading = { AgentDot(agent) },
                ) { panel = if (panel == NewTaskPanel.AGENT) NewTaskPanel.NONE else NewTaskPanel.AGENT }
                Spacer(Modifier.weight(1f))
                SendButton(enabled = draft.isNotBlank() && dir != null && !busy, onClick = send)
            }

            // ONE inline line, never a dialog: whatever went wrong, the draft is still in the field above it
            // and pressing send again is the fix.
            val status: Pair<Color, String>? = when {
                busy -> Tok.tx2 to stringResource(Res.string.new_task_opening)
                dir == null -> Tok.warn to stringResource(Res.string.new_task_no_projects)
                repo.newTaskError.value == PocketRepository.NewTaskError.TIMEOUT ->
                    Tok.danger to stringResource(Res.string.new_task_timeout)
                repo.newTaskError.value == PocketRepository.NewTaskError.SEND_REFUSED ->
                    Tok.danger to stringResource(Res.string.new_task_send_failed)
                repo.newTaskError.value != null -> Tok.danger to stringResource(Res.string.new_task_failed)
                else -> null
            }
            status?.let { (color, text) ->
                Text(text, color = color, style = TypeRole.caption, modifier = Modifier.padding(top = Metric.gapS))
            }
        }
    }
}

/** Which secondary picker is over the composer. Only one at a time — they share the sheet's upper half. */
internal enum class NewTaskPanel { NONE, PROJECT, AGENT }

/** The prompt field: multi-line, self-focusing, and the only place in the sheet that takes text. */
@Composable
private fun NewTaskField(value: String, enabled: Boolean, autoFocus: Boolean, onValueChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    // One best-effort request per (re)arm — not a retry loop: a delay-driven loop is exactly what stalls the
    // UI test clock, and nothing else in an open sheet competes for the keyboard.
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(Metric.gap)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 82.dp).clip(shape)
            .background(Tok.base).border(Metric.hairline, Tok.hair, shape)
            .padding(horizontal = Metric.gap, vertical = Metric.gap),
    ) {
        if (value.isEmpty()) {
            Text(stringResource(Res.string.new_task_placeholder), color = Tok.muted, style = TypeRole.preview)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = TypeRole.preview.copy(color = Tok.tx),
            cursorBrush = SolidColor(Tok.accent),
            modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp)
                .focusRequester(focus).testTag("new-task-prompt"),
        )
    }
}

/**
 * The composer's project / agent chip — the #157 model-chip language with a leading identity mark.
 *
 * Same 30dp raised pill, same hairline that warms to accent while its picker is open, same chevron flip, so
 * the three chips a user meets across the app (model, project, agent) read as one control with three jobs.
 */
@Composable
private fun TaskChip(
    label: String,
    contentDescription: String,
    open: Boolean,
    mono: Boolean,
    labelMax: Dp = 108.dp,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val chev by animateFloatAsState(if (open) 180f else 0f, label = "taskChipChevron")
    val ink = if (open) Tok.accent else Tok.tx2
    val cd = contentDescription
    Box(
        Modifier.sizeIn(minHeight = Metric.touch).clip(RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).background(Tok.raised)
                .border(Metric.hairline, if (open) Tok.accent else Tok.hair, RoundedCornerShape(999.dp))
                .padding(start = 9.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading(ink)
            Spacer(Modifier.width(6.dp))
            Text(
                label, color = ink,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (mono) 11.sp else 12.sp, fontWeight = FontWeight.Medium, style = TightCenter,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = labelMax),
            )
            Spacer(Modifier.width(5.dp))
            TaskChevron(if (open) Tok.accent else Tok.muted, Modifier.size(12.dp).rotate(chev))
        }
    }
}

@Composable
private fun FolderGlyph(tint: Color) = Icon(Icons.Outlined.Folder, null, tint = tint, modifier = Modifier.size(13.dp))

@Composable
private fun AgentDot(agent: AgentKind) =
    Box(Modifier.size(10.dp).clip(CircleShape).background(agentColor(agent)))

/** 12dp chevron-down (drawn, not a text glyph, so the open-state 180° flip stays optically centered —
 *  the same construction as the model chip's, pointing the other way). */
@Composable
private fun TaskChevron(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.21f, size.height * 0.36f)
            lineTo(size.width * 0.50f, size.height * 0.64f)
            lineTo(size.width * 0.79f, size.height * 0.36f)
        }
        drawPath(p, tint, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The terracotta send button: the loudest control in the sheet, and disabled until it can actually send. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(Res.string.new_task_send)
    Box(
        Modifier.sizeIn(minWidth = 64.dp, minHeight = Metric.touch)
            .clip(RoundedCornerShape(Metric.radiusS))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("new-task-send"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 40.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(Metric.radiusS))
                .then(
                    if (enabled) Modifier.background(Tok.accent)
                    else Modifier.border(Metric.hairline, Tok.hair, RoundedCornerShape(Metric.radiusS)),
                )
                .padding(horizontal = Metric.gapL),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (enabled) Tok.base else Tok.muted, style = TypeRole.action, maxLines = 1)
        }
    }
}

// ── the two pickers ─────────────────────────────────────────────────────────────────────────────────
// Both are panels INSIDE the sheet rather than sheets of their own: a second bottom sheet over the first
// would push the composer off screen and lose the draft's context, and the design's picker deliberately
// keeps the chips it changes visible underneath.

@Composable
private fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Metric.radius)
    Column(
        Modifier.fillMaxWidth().height(342.dp).padding(bottom = Metric.gap)
            .clip(shape).background(Tok.surface).border(Metric.hairline, Tok.hair, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch).padding(start = Metric.gapL, end = Metric.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Tok.tx, style = TypeRole.rowTitle, modifier = Modifier.weight(1f), maxLines = 1)
            Box(
                Modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close, stringResource(Res.string.new_task_close),
                    tint = Tok.tx2, modifier = Modifier.size(18.dp),
                )
            }
        }
        TaskHairline()
        content()
    }
}

/**
 * Recent projects (the current one ticked) over every project the computer reports, searchable, with the
 * "browse other folders" doorway pinned to the bottom — this is where the old standalone open-folder row's
 * capability went (issue #260 §C); it is the SAME [onBrowseOther] flow, not a second implementation.
 */
@Composable
private fun ProjectPanel(
    recents: List<DirectoryEntry>,
    all: List<DirectoryEntry>,
    selected: String?,
    onPick: (String) -> Unit,
    onBrowseOther: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matched = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all
        else all.filter { it.name.contains(q, ignoreCase = true) || it.path.contains(q, ignoreCase = true) }
    }
    PanelFrame(stringResource(Res.string.new_task_pick_project), onClose) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Metric.gapL, vertical = Metric.gapS),
            ) {
                if (query.isBlank() && recents.isNotEmpty()) {
                    item { PanelLabel(stringResource(Res.string.new_task_recent)) }
                    items(recents, key = { "r:" + it.path }) { e ->
                        ProjectPickRow(e, checked = e.path == selected) { onPick(e.path) }
                    }
                }
                item {
                    PanelLabel(stringResource(Res.string.new_task_all_projects), top = if (query.isBlank() && recents.isNotEmpty()) Metric.gapL else 0.dp)
                }
                item {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(Res.string.new_task_search_projects), style = TypeRole.body) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = Metric.gapS).testTag("new-task-project-search"),
                    )
                }
                items(matched, key = { "a:" + it.path }) { e ->
                    ProjectPickRow(e, checked = e.path == selected) { onPick(e.path) }
                }
            }
        }
        TaskHairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Tok.raised)
                .clickable(role = Role.Button, onClick = onBrowseOther)
                .padding(horizontal = Metric.gapL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Metric.gap))
            Text(stringResource(Res.string.new_task_browse_other), color = Tok.tx, style = TypeRole.body, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProjectPickRow(e: DirectoryEntry, checked: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onPick)
            .padding(vertical = Metric.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Metric.gap))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, color = if (checked) Tok.tx else Tok.tx2,
                style = TypeRole.body.copy(fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                e.path, color = Tok.muted, style = TypeRole.captionMono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (checked) {
            Spacer(Modifier.width(Metric.gapS))
            CheckMark()
        }
    }
}

/**
 * Every backend, always — the ones this daemon never advertised are shown dimmed and labelled "unavailable"
 * rather than hidden (issue #260 §D). A missing row reads as "cc-pocket doesn't support DeepSeek"; a dimmed
 * one reads as "this computer doesn't, yet", which is the true statement and the actionable one.
 */
@Composable
private fun AgentPanel(
    selected: AgentKind,
    available: List<AgentKind>,
    onPick: (AgentKind) -> Unit,
    onClose: () -> Unit,
) {
    PanelFrame(stringResource(Res.string.new_task_pick_agent), onClose) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = Metric.gapL, vertical = Metric.gapS),
        ) {
            items(AgentKind.entries, key = { it.name }) { a ->
                val usable = a in available
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(enabled = usable, role = Role.Button) { onPick(a) }
                        .alpha(if (usable) 1f else 0.42f)
                        .semantics { role = Role.RadioButton }
                        .padding(vertical = Metric.gapS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(agentColor(a)))
                    Spacer(Modifier.width(Metric.gap))
                    Text(
                        agentName(a), color = if (a == selected && usable) Tok.tx else Tok.tx2,
                        style = TypeRole.body.copy(
                            fontWeight = if (a == selected && usable) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    if (!usable) {
                        Text(stringResource(Res.string.new_task_agent_unavailable), color = Tok.muted, style = TypeRole.caption)
                    } else if (a == selected) {
                        CheckMark()
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelLabel(text: String, top: Dp = 0.dp) {
    Text(
        text.uppercase(), color = Tok.muted, style = TypeRole.label,
        modifier = Modifier.padding(top = top, bottom = Metric.gapS),
    )
}

@Composable
private fun CheckMark() = Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(16.dp))

@Composable
private fun TaskHairline() = Box(Modifier.fillMaxWidth().height(Metric.hairline).background(Tok.hair))

/** The last path segment — what a project is called, sep-aware so a Windows daemon's paths read right. */
internal fun folderLeaf(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return path
    val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (cut < 0) trimmed else trimmed.substring(cut + 1).ifEmpty { trimmed }
}
