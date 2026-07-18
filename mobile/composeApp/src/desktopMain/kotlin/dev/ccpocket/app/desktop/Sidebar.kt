package dev.ccpocket.app.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LaptopWindows
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.group_delete
import dev.ccpocket.app.resources.group_delete_confirm
import dev.ccpocket.app.resources.group_move_out
import dev.ccpocket.app.resources.group_move_to
import dev.ccpocket.app.resources.group_name_hint
import dev.ccpocket.app.resources.group_new
import dev.ccpocket.app.resources.group_rename
import dev.ccpocket.app.resources.group_ungrouped
import dev.ccpocket.app.resources.session_rename
import dev.ccpocket.app.resources.session_rename_hint
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentBadge
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.fleet.AttentionBadge
import dev.ccpocket.app.ui.modelAlias
import dev.ccpocket.app.ui.share.SharedPill
import dev.ccpocket.app.ui.share.expiryLeft
import dev.ccpocket.app.ui.share.expiryLeftText
import dev.ccpocket.protocol.AgentKind
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

internal fun osIcon(os: DkOs): ImageVector = when (os) {
    DkOs.MAC -> Icons.Rounded.LaptopMac
    DkOs.LINUX -> Icons.Rounded.Terminal
    DkOs.WIN -> Icons.Rounded.LaptopWindows
}

/**
 * The sidebar answers "where is my work right now" — nothing else. ① header owns machines (the fleet
 * lives in its ⌘0 dropdown) · ② one New-session entry point · ③ PINNED (⌘1–9) + RUNNING own fast
 * switching, and every live thing appears exactly once (a running project already represented by a
 * running pin is not repeated) · ④ RECENT — the visited projects' sessions, grouped under collapsible
 * project headers, ONE scroll. Browsing the full project list is a search problem, not a scroll
 * problem: it lives in the ⌘K palette, reachable via "All projects…" docked above Settings.
 */
@Composable
fun Sidebar(model: DesktopModel, width: Dp = Dk.sidebarWidth, modifier: Modifier = Modifier) {
    Column(modifier.width(width).fillMaxHeight().background(Tok.surface)) {
        SwitcherHeader(model)
        NewSessionRow { model.openNewSession() }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        PinnedZone(model)
        RunningZone(model)
        RecentZone(model, Modifier.weight(1f))
        AllProjectsRow { model.browseProjects() }
        SettingsFooter { model.showSettings = true }
    }
}

// ── zone 1: machine switcher header ─────────────────────────────────────────────────────────────

/** Current machine + status, click (or ⌘0) opens the fleet dropdown; the attention bell rides right. */
@Composable
private fun SwitcherHeader(model: DesktopModel) {
    val c = model.activeComputer
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).hoverFill(RoundedCornerShape(8.dp))
                    .clickable { model.switcherOpen = !model.switcherOpen }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (c != null) {
                    Icon(osIcon(c.os), null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
                    Text(
                        c.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.5.sp, lineHeight = 12.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (c.online) PulseDot(Tok.ok, 6.dp)
                    else {
                        Dot(Tok.muted, 6.dp)
                        Text("reconnecting…", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
                    }
                } else {
                    Text("No computer", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f, fill = false))
                }
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
            }
            Key("⌘0")
            val waiting = model.attention.size
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                    .clickable { model.showAttention = !model.showAttention }.padding(4.dp),
            ) {
                Icon(Icons.Outlined.Notifications, null, tint = if (waiting > 0) Tok.tx else Tok.tx2, modifier = Modifier.size(16.dp))
                if (waiting > 0) AttentionBadge(waiting, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

// ── zone 2: pinned sessions (⌘1–9, drag to reorder) ─────────────────────────────────────────────

@Composable
private fun PinnedZone(model: DesktopModel) {
    val pins = model.pins
    if (pins.isEmpty()) return // pinning is discoverable from the hover pin on any session row
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Pinned", trailing = { Key("⌘1–9") })
        var dragFrom by remember(pins.size) { mutableStateOf(-1) }
        var dragDy by remember { mutableStateOf(0f) }
        val rowPx = with(LocalDensity.current) { 32.dp.toPx() }
        fun target() = if (dragFrom < 0) -1 else (dragFrom + (dragDy / rowPx).roundToInt()).coerceIn(0, pins.lastIndex)
        // computers is a computed getter that rebuilds the whole list — resolve once per pass, not per row
        val computers = model.computers
        val t = target() // -1 unless dragging; t < dragFrom / t > dragFrom below imply a real, moved drag
        pins.forEachIndexed { i, p ->
            // the terracotta slot marker showing where the lifted row will land
            if (i == t && t < dragFrom) SlotIndicator()
            PinRow(
                model, p, computers.firstOrNull { it.accountId == p.accountId }, i,
                dragging = i == dragFrom, dragDy = dragDy,
                onDragStart = { dragFrom = i; dragDy = 0f },
                onDrag = { dy -> dragDy += dy },
                onDragEnd = {
                    val end = target()
                    if (dragFrom >= 0 && end != dragFrom) model.movePin(dragFrom, end)
                    dragFrom = -1; dragDy = 0f
                },
            )
            if (i == t && t > dragFrom) SlotIndicator()
        }
        if (model.pinsFull) {
            Text(
                "Pinned is full (${DesktopModel.MAX_PINS}) — unpin a session to add another.",
                color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Tok.base)
                    .border(1.dp, Tok.hair, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SlotIndicator() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp).background(Tok.accent, RoundedCornerShape(2.dp)))
}

/** One pinned row: grip on hover · title · machine suffix when remote · badges · unpin · ⌘n keycap. */
@Composable
private fun PinRow(
    model: DesktopModel,
    p: DkPin,
    computer: DkComputer?,
    index: Int,
    dragging: Boolean,
    dragDy: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val remote = p.accountId != model.activeComputer?.accountId
    // live state is only knowable for the current machine's loaded session lists
    val live = if (remote) null else model.liveSession(p.sessionId)
    val running = live?.running ?: (!remote && p.sessionId == model.selectedSessionId && model.streaming)
    val pending = live?.pending ?: 0
    val dim = !remote && model.activeComputer?.online == false
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().height(32.dp)
            .then(
                if (dragging) {
                    Modifier.zIndex(3f)
                        .graphicsLayer { translationY = dragDy; scaleX = 1.02f; scaleY = 1.02f }
                        .shadow(14.dp, shape).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape)
                } else {
                    Modifier.hoverable(src).hoverFill().alpha(if (dim) 0.55f else 1f)
                },
            )
            .clickable { model.openPin(p) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when {
            // reorder-drag lives on the GRIP only. Mouse drag slop is ~0.125dp (vs 18dp touch), so a
            // whole-row detectDragGestures turns the 1px jitter of any real click into a drag, consumes
            // the events, and cancels the row's clickable — pins looked tappable but never opened.
            hovered || dragging -> Icon(
                Icons.Rounded.DragIndicator, null, tint = Tok.muted,
                modifier = Modifier.size(12.dp).pointerInput(index, model.pins.size) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amt -> change.consume(); onDrag(amt.y) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
            )
            running -> PulseDot(Tok.ok, 5.dp)
            else -> Spacer(Modifier.width(5.dp))
        }
        Text(
            p.title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (remote && computer != null) {
            Icon(osIcon(computer.os), null, tint = Tok.muted, modifier = Modifier.size(11.dp))
            Text(
                computer.name, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp),
            )
        }
        AgentBadge(p.agent)
        if (pending > 0) AttentionBadge(pending)
        if (hovered || dragging) {
            Icon(
                PinSlashIcon, null, tint = Tok.tx2,
                modifier = Modifier.size(13.dp).clickable { model.unpin(p) },
            )
            Key("⌘${index + 1}") // keycap on hover only — at rest the row is just title + state
        }
    }
}

// ── zone 3: running (flat, cross-machine) ───────────────────────────────────────────────────────

@Composable
private fun RunningZone(model: DesktopModel) {
    val running = model.runningVisible
    if (running.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Running")
        running.forEach { (m, p) -> RunningRow(m, p, onBrowse = { model.browseRunning(m, p) }) { model.openRunning(m, p) } }
    }
}

/** One cross-machine RUNNING row: accent pulse · project (mono) · which machine, right-aligned muted.
 *  Click = jump to the live session; the hover ≡ = the project's session LIST instead (issue #49 —
 *  the direct jump made the dir's other/historical sessions look unreachable). */
@Composable
private fun RunningRow(m: DkMachine, p: DkProject, onBrowse: () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverable(src).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.accent, 5.dp)
        Text(
            p.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (p.sharedBy != null) SharedPill() // a guest's shared folder (issue #115) — provenance at a glance
        if (hovered) Text(
            "≡", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onBrowse).padding(horizontal = 3.dp),
        ) else Text(
            m.computer.name, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── zone 4: RECENT — the visited projects' sessions, grouped, one scroll ────────────────────────

// THE render predicate for RECENT groups — the reveal effect's recentRowIndex mirrors the LazyColumn
// layout exactly, so every consumer must filter through this one definition or the scroll index drifts
private fun renderedGroups(model: DesktopModel) = model.sessionGroups.filter { it.current || it.sessions.isNotEmpty() }

/** RECENT's section label with the hover clear-all affordance (issue #102): "clear" arms to "sure?",
 *  a second click forgets every visited project (pins / hidden rows untouched); moving the pointer off
 *  the header disarms. Mirrors [SectionLabel]'s metrics so the header is identical at rest, and the
 *  GroupHeader hover-action precedent for the reveal. */
@Composable
private fun RecentHeader(model: DesktopModel) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var arm by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) { if (!hovered) arm = false } // pointer left — disarm the pending clear
    Row(
        Modifier.fillMaxWidth().hoverable(src)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "RECENT", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp)) // keep the row baseline stable pre-hover (SectionLabel parity)
        Key("⌘R")
        if (hovered && model.sessionGroups.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (arm) "sure?" else "clear",
                color = if (arm) Tok.accent else Tok.tx2,
                fontFamily = Dk.ui, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable { if (arm) { arm = false; model.clearRecent() } else arm = true }
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun RecentZone(model: DesktopModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        RecentHeader(model)
        val groups = renderedGroups(model)
        if (groups.isEmpty()) {
            Text(
                "No sessions yet — open a project from All projects below, or start a new session.",
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            return@Column
        }
        // collapse set + scroll position hoisted out of the LazyColumn so the reveal effect below can
        // drive them (expand a folded group, scroll it in) without collapsing the others (#83)
        val collapsed = remember { mutableStateListOf<String>() }
        val listState = rememberLazyListState()
        // which header's refresh icon spins: the clicked group's; ⌘R has no click, so the current one's
        var refreshTarget by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(model.sessionsRefreshing) { if (!model.sessionsRefreshing) refreshTarget = null }
        val spinningPath = if (model.sessionsRefreshing) refreshTarget ?: groups.firstOrNull { it.current }?.path else null
        val selectedId = model.selectedSessionId // resolved by scanning the session list — once, not per row
        // Reveal the selected session's group when the selection changes — e.g. clicking a RUNNING project
        // resumes its live session (#83). Expand that group if the user had folded it and scroll it into
        // view, but only the TARGET group is touched (multi-expand is intentional) and only when the row
        // isn't already on screen, so we never refold others or yank a session the user can already see.
        LaunchedEffect(selectedId) {
            if (selectedId == null) return@LaunchedEffect
            // openRunning lists then resumes asynchronously, so the target group can land a beat after the
            // id resolves — observe the groups until the selected session surfaces, then act exactly once.
            // Time-boxed: an unlisted session (cross-machine resume, hidden row) never surfaces, and an
            // unbounded collector would keep re-scanning every group on each snapshot change until the
            // NEXT selection.
            val targetPath = withTimeoutOrNull(5_000) {
                snapshotFlow {
                    renderedGroups(model).firstOrNull { g -> g.sessions.any { it.sessionId == selectedId } }?.path
                }.filterNotNull().first()
            } ?: return@LaunchedEffect
            collapsed.remove(targetPath) // expand a folded target; a no-op otherwise — others left as-is
            val rowKey = "s:$targetPath:$selectedId"
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == rowKey }) {
                val index = recentRowIndex(renderedGroups(model), collapsed, targetPath, selectedId)
                if (index >= 0) listState.animateScrollToItem(index)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) { // lazy: a visited project can hold hundreds of sessions
            groups.forEach { g ->
                val closed = g.path in collapsed
                item(key = "h:${g.path}") {
                    GroupHeader(
                        g, closed,
                        refreshing = g.path == spinningPath,
                        onRefresh = { refreshTarget = g.path; model.refresh(g) },
                        onToggle = { if (closed) collapsed.remove(g.path) else collapsed.add(g.path) },
                    )
                }
                if (!closed) {
                    // issue #119: only the live-listed project carries custom-group data (the daemon lists
                    // groups per dir) — a RECENT snapshot has none and renders FLAT, which is also the
                    // degrade path for an older daemon that omits groups entirely.
                    val custom = if (g.current) model.customGroups else emptyList()
                    // sessions the current project can be moved between (owner + has groups) — drives the row
                    // right-click "move to group" menu; empty everywhere else so no menu appears.
                    val menuGroups = if (g.current && model.canEditGroups) custom else emptyList()
                    // right-click "Rename session" (issue #158): the live-listed project's rows on an owner +
                    // rename-capable daemon — same scoping as the group menu (a RECENT snapshot's dir isn't
                    // the one the daemon would resolve the rename against).
                    val renameable = g.current && model.canRenameSessions
                    // "+ New group" sits at the TOP of the project's sessions (matches mobile) — a bottom
                    // entry forces scrolling past a long session list to create a group. Current + group-aware
                    // + owner only (canEditGroups folds in groupsSupported), so it also creates the FIRST group
                    // from a still-flat list; an older daemon / guest / RECENT snapshot shows nothing.
                    if (g.current && model.canEditGroups) item(key = "ng:${g.path}") { NewGroupRow(model) }
                    if (custom.isEmpty()) {
                        if (g.sessions.isEmpty()) {
                            item(key = "e:${g.path}") {
                                Text(
                                    "No sessions here yet",
                                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                                    modifier = Modifier.padding(start = 32.dp, top = 2.dp, bottom = 6.dp),
                                )
                            }
                        }
                        items(g.sessions, key = { "s:${g.path}:${it.sessionId}" }) { s ->
                            SessionRow(model, s, selected = s.sessionId == selectedId, menuGroups = menuGroups, renameable = renameable) { model.selectSession(s) }
                        }
                    } else {
                        sessionSections(g.sessions, custom).forEach { sec ->
                            item(key = "gh:${g.path}:${sec.id}") { CustomGroupHeader(model, g.path, sec) }
                            if (!model.groupCollapsed(g.path, sec.id)) {
                                items(sec.sessions, key = { "s:${g.path}:${it.sessionId}" }) { s ->
                                    SessionRow(model, s, selected = s.sessionId == selectedId, indented = true, menuGroups = menuGroups, renameable = renameable) { model.selectSession(s) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Flat LazyColumn index of a RECENT session row, honoring which groups are collapsed — so the reveal
 *  effect (#83) can scroll a just-selected session into view. Mirrors the LazyColumn's own layout: one
 *  header per group, then (when open) either the empty placeholder or one item per session. Falls back
 *  to the group's header index when the row itself isn't laid out (collapsed / empty group); -1 = absent. */
private fun recentRowIndex(
    groups: List<DkSessionGroup>,
    collapsed: List<String>,
    path: String,
    sessionId: String,
): Int {
    var idx = 0
    for (g in groups) {
        val header = idx
        idx++ // the group header is always emitted
        val closed = g.path in collapsed
        if (g.path == path) {
            if (closed || g.sessions.isEmpty()) return header
            val pos = g.sessions.indexOfFirst { it.sessionId == sessionId }
            return if (pos >= 0) header + 1 + pos else header
        }
        if (!closed) idx += if (g.sessions.isEmpty()) 1 else g.sessions.size
    }
    return -1
}

/** A RECENT group header: folder + project name (mono, muted) · hover refresh · running pulse · collapse chevron. */
@Composable
private fun GroupHeader(g: DkSessionGroup, closed: Boolean, refreshing: Boolean, onRefresh: () -> Unit, onToggle: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(28.dp).hoverable(src).hoverFill().clickable(onClick = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
        Text(
            g.name, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (g.sharedBy != null) {
            // a guest's shared folder (issue #115): the same neutral hairline pill as mobile — provenance,
            // not attention — plus "who · how long" at rest. Hover hands that space to the refresh icon
            // (the SessionRow model-label precedent), so the affordances never fight over 28dp.
            SharedPill()
            if (!hovered && !refreshing) {
                val left = g.shareExpiresAt?.let { expiryLeftText(expiryLeft(it, epochMillis())) }
                Text(
                    listOfNotNull(g.sharedBy, left).joinToString(" · "),
                    color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp),
                )
            }
        }
        when {
            refreshing -> {
                val angle by rememberInfiniteTransition().animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                )
                Icon(Icons.Rounded.Refresh, null, tint = Tok.tx2, modifier = Modifier.size(13.dp).rotate(angle))
            }
            hovered -> Icon(
                Icons.Rounded.Refresh, null, tint = Tok.tx2,
                modifier = Modifier.size(13.dp).clickable(onClick = onRefresh),
            )
        }
        if (closed && g.sessions.any { it.running }) PulseDot(Tok.ok, 5.dp) // running stays visible when folded
        Icon(
            Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
            modifier = Modifier.size(13.dp).rotate(if (closed) -90f else 0f),
        )
    }
}

// ── issue #119: custom session-group sections inside the current project ────────────────────────

private const val UNGROUPED_SECTION = "__ungrouped__"

/** A rendered slice of the current project's session list: a named custom group, or the Ungrouped
 *  fallback ([name] null / [editable] false). Named groups always show — even empty, they're move targets;
 *  Ungrouped shows only when it actually holds rows. */
private data class SessionSection(val id: String, val name: String?, val editable: Boolean, val sessions: List<DkSession>)

private fun sessionSections(sessions: List<DkSession>, custom: List<DkGroup>): List<SessionSection> {
    val ids = custom.mapTo(HashSet()) { it.id }
    val named = custom.sortedBy { it.order }.map { grp ->
        SessionSection(grp.id, grp.name, editable = true, sessions = sessions.filter { it.group == grp.id })
    }
    // a session whose group id no longer exists (just-deleted group, cross-version) also falls to Ungrouped
    val ungrouped = sessions.filter { it.group == null || it.group !in ids }
    return if (ungrouped.isEmpty()) named else named + SessionSection(UNGROUPED_SECTION, name = null, editable = false, sessions = ungrouped)
}

/** A custom-group sub-header (issue #119): collapse chevron · name · session-count badge, with hover
 *  rename/delete for editable groups (Ungrouped is a fallback and can't be edited). Rename swaps in an
 *  inline field; delete arms a confirm bar (group_delete_confirm). */
@Composable
private fun CustomGroupHeader(model: DesktopModel, projectPath: String, sec: SessionSection) {
    var editing by remember(sec.id) { mutableStateOf(false) }
    var confirming by remember(sec.id) { mutableStateOf(false) }
    if (editing) {
        GroupNameInput(
            initial = sec.name ?: "", hint = stringResource(Res.string.group_name_hint),
            onCommit = { model.renameGroup(sec.id, it); editing = false }, onCancel = { editing = false },
        )
        return
    }
    if (confirming) {
        GroupDeleteConfirm(onConfirm = { model.deleteGroup(sec.id); confirming = false }, onCancel = { confirming = false })
        return
    }
    val collapsed = model.groupCollapsed(projectPath, sec.id)
    val canEdit = model.canEditGroups && sec.editable
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(26.dp).hoverable(src).hoverFill()
            .clickable { model.setGroupCollapsed(projectPath, sec.id, !collapsed) }
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted, modifier = Modifier.size(12.dp).rotate(if (collapsed) -90f else 0f))
        Text(
            sec.name ?: stringResource(Res.string.group_ungrouped),
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text("${sec.sessions.size}", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        if (canEdit && hovered) {
            Text(
                stringResource(Res.string.group_rename), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { editing = true }.padding(horizontal = 3.dp),
            )
            Text(
                stringResource(Res.string.group_delete), color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { confirming = true }.padding(horizontal = 3.dp),
            )
        }
    }
}

/** The delete-group confirm bar (issue #119): group_delete_confirm + a Delete verb and an ✕ to back out. */
@Composable
private fun GroupDeleteConfirm(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.group_delete_confirm), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(Res.string.group_delete), color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onConfirm).padding(horizontal = 3.dp),
        )
        Icon(Icons.Rounded.Close, null, tint = Tok.muted, modifier = Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onCancel))
    }
}

/** "+ New group" at the foot of the current project's grouped list (issue #119): click reveals the inline
 *  name field; committing creates the group (the daemon re-pushes Sessions, which refreshes the sections). */
@Composable
private fun NewGroupRow(model: DesktopModel) {
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        GroupNameInput(
            initial = "", hint = stringResource(Res.string.group_name_hint),
            onCommit = { model.createGroup(it); adding = false }, onCancel = { adding = false },
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().height(28.dp).hoverFill().clickable { adding = true }.padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.tx2, modifier = Modifier.size(12.dp))
        Text(stringResource(Res.string.group_new), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Inline name field shared by "New group" / group rename (issue #119) and the session-row rename
 *  (issue #158): auto-focused, Enter commits a non-blank trimmed name, Esc cancels. */
@Composable
private fun GroupNameInput(initial: String, hint: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(hint, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 11.sp),
                cursorBrush = SolidColor(Tok.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { if (text.isNotBlank()) onCommit(text.trim()); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
            )
        }
    }
}

/** The browse escape hatch, docked above Settings — opens the ⌘K palette scoped to every project. */
@Composable
private fun AllProjectsRow(onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().height(34.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
            Text("All projects…", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NewSessionRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(13.dp))
        Text("New session", color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Key("⌘N")
    }
}

/** A session row, optionally wrapped in a right-click menu: the current project's rows offer "Rename
 *  session" (issue #158, Claude rows on a rename-capable owner connection) and — when the project has
 *  custom groups (issue #119) — "move to <group>" per group + "remove from group" when already grouped. */
@Composable
private fun SessionRow(
    model: DesktopModel,
    s: DkSession,
    selected: Boolean,
    indented: Boolean = false,
    menuGroups: List<DkGroup> = emptyList(),
    renameable: Boolean = false,
    onClick: () -> Unit,
) {
    // rename entry (issue #158): Claude rows only — a Codex rename write path is out of scope
    val canRename = renameable && s.agent != AgentKind.CODEX
    // inline rename swaps the row for a prefilled title field (the group header's rename pattern);
    // committing sends the rename — the daemon re-pushes Sessions, which refreshes the row title.
    // A REFUSED rename (rename_failed) re-opens the editor with the daemon's reason inline: the ask
    // came from THIS row, so the feedback lands here — the chat transcript is the wrong surface (the
    // common refusal, a terminal-held session, is renamed with no chat open at all). Esc dismisses.
    var renaming by remember(s.sessionId) { mutableStateOf(false) }
    val renameError = model.renameError(s.sessionId)
    if (renaming || renameError != null) {
        Column {
            GroupNameInput(
                initial = s.title,
                hint = stringResource(Res.string.session_rename_hint),
                onCommit = { model.renameSession(s.sessionId, it); renaming = false },
                onCancel = { renaming = false; model.dismissRenameError() },
            )
            if (renameError != null) {
                Text(
                    renameError, color = Tok.danger, fontFamily = Dk.ui, fontSize = 10.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(start = 22.dp, end = 12.dp, top = 2.dp, bottom = 3.dp),
                )
            }
        }
        return
    }
    if (menuGroups.isEmpty() && !canRename) { SessionRowBody(model, s, selected, indented, onClick); return }
    val rename = stringResource(Res.string.session_rename)
    val moveTo = stringResource(Res.string.group_move_to)
    val moveOut = stringResource(Res.string.group_move_out)
    ContextMenuArea(
        items = {
            buildList {
                if (canRename) add(ContextMenuItem(rename) { renaming = true })
                menuGroups.filter { it.id != s.group }.forEach { grp ->
                    add(ContextMenuItem("$moveTo · ${grp.name}") { model.assignGroup(s.sessionId, grp.id) })
                }
                if (s.group != null) add(ContextMenuItem(moveOut) { model.assignGroup(s.sessionId, null) })
            }
        },
        content = { SessionRowBody(model, s, selected, indented, onClick) },
    )
}

@Composable
private fun SessionRowBody(model: DesktopModel, s: DkSession, selected: Boolean, indented: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (selected || hovered) Tok.raised else Color.Transparent
    Box(Modifier.fillMaxWidth().height(32.dp).hoverable(src).clickable(onClick = onClick).background(bg)) {
        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).padding(vertical = 4.dp).width(2.dp).fillMaxHeight().background(Tok.accent, RoundedCornerShape(2.dp)))
        }
        Row(
            Modifier.fillMaxSize().padding(start = if (indented) 26.dp else 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (s.running) PulseDot(Tok.ok, 5.dp) else Spacer(Modifier.width(5.dp))
            Text(
                s.title,
                color = if (selected) Tok.tx else Tok.tx2,
                fontFamily = Dk.ui, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            // which model the session last ran, as its alias ("sonnet") — muted so the title leads;
            // hidden while hovered (the pin/close affordances need that space more than a static label)
            if (!hovered) {
                s.model?.let { m ->
                    modelAlias(m).takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            AgentBadge(s.agent)
            if (s.pending > 0) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent).clickable(onClick = onClick)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Rounded.PriorityHigh, null, tint = Tok.base, modifier = Modifier.size(10.dp))
                    Text("${s.pending}", color = Tok.base, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (hovered) {
                val pinned = model.isPinned(s.sessionId)
                Icon(
                    if (pinned) PinSlashIcon else PinIcon, null,
                    tint = if (pinned) Tok.tx2 else Tok.accent,
                    modifier = Modifier.size(13.dp).clickable {
                        if (pinned) model.pins.firstOrNull { it.sessionId == s.sessionId }?.let(model::unpin)
                        else model.pin(s)
                    },
                )
                // ✕ removes the row from RECENT (issue #62) — non-destructive: the transcript stays on the
                // host, and reopening this project resurfaces it. (Previously a dead, unclickable glyph.)
                Icon(
                    Icons.Rounded.Close, null, tint = Tok.muted,
                    modifier = Modifier.size(13.dp).clip(RoundedCornerShape(4.dp)).clickable { model.hideSession(s) },
                )
            }
        }
    }
}

@Composable
private fun SettingsFooter(onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Outlined.Settings, null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
            Text("Settings", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("v$APP_VERSION", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
        }
    }
}

// ── the machine switcher dropdown (⌘0 / header click; rendered as a DesktopApp overlay) ─────────

@Composable
fun MachineSwitcher(model: DesktopModel) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(280.dp).shadow(24.dp, shape).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            model.machines.forEachIndexed { i, m ->
                SwitcherRow(m, keyHint = "${i + 1}") { model.selectComputer(m.computer) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Box(Modifier.fillMaxWidth().padding(6.dp)) {
            Row(
                Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                    .dashedBorder(Tok.hair, 7.dp).clickable { model.addComputer() }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(13.dp))
                Text("Add computer", color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SwitcherRow(m: DkMachine, keyHint: String, onClick: () -> Unit) {
    val offline = !m.computer.online && !m.active
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .background(if (m.active) Tok.surface else Color.Transparent)
            .then(if (m.active) Modifier else Modifier.hoverFill())
            .clickable(onClick = onClick)
            .alpha(if (offline) 0.55f else 1f),
    ) {
        if (m.active) {
            Box(Modifier.align(Alignment.CenterStart).padding(vertical = 5.dp).width(2.dp).fillMaxHeight().background(Tok.accent, RoundedCornerShape(2.dp)))
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(osIcon(m.computer.os), null, tint = Tok.tx2, modifier = Modifier.size(13.dp))
            Text(
                m.computer.name, color = if (m.active) Tok.tx else Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (m.computer.online) PulseDot(Tok.ok, 5.dp) else Dot(Tok.muted, 5.dp)
            if (m.thisMachine) OutlinePill("this Mac", Tok.muted)
            Spacer(Modifier.weight(1f))
            if (m.pending > 0) AttentionBadge(m.pending)
            Key(keyHint)
        }
    }
}

// ── pin glyphs (stroke-based, from the design board's PI set) ───────────────────────────────────

private fun pinBuilder(name: String) = ImageVector.Builder(
    name = name, defaultWidth = 16.dp, defaultHeight = 16.dp, viewportWidth = 16f, viewportHeight = 16f,
)

private fun ImageVector.Builder.stroked(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(
        stroke = SolidColor(Color.White), strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null,
        pathBuilder = block,
    )

/** Pin outline — the "pin this session" hover affordance on session rows. */
internal val PinIcon: ImageVector by lazy {
    pinBuilder("DkPinIcon").apply {
        stroked {
            moveTo(6.2f, 2f); lineTo(9.8f, 2f); lineTo(9.4f, 5.5f); lineTo(11.7f, 8.2f)
            lineTo(4.3f, 8.2f); lineTo(6.6f, 5.5f); close()
        }
        stroked { moveTo(8f, 8.2f); lineTo(8f, 14f) }
    }.build()
}

/** Pin-slash — unpin, shown on hover over pinned rows (and already-pinned session rows). */
internal val PinSlashIcon: ImageVector by lazy {
    pinBuilder("DkPinSlashIcon").apply {
        stroked {
            moveTo(6.2f, 2f); lineTo(9.8f, 2f); lineTo(9.4f, 5.5f); lineTo(11.7f, 8.2f); lineTo(8.6f, 8.2f)
            moveTo(4.9f, 5.7f); lineTo(4.3f, 8.2f); lineTo(7.4f, 8.2f)
            moveTo(8f, 8.2f); lineTo(8f, 14f)
        }
        stroked { moveTo(2.5f, 2.5f); lineTo(13.5f, 13.5f) }
    }.build()
}
