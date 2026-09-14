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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LaptopWindows
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.SUPPORT_URL
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rewind_group_rewound
import dev.ccpocket.app.resources.add_device
import dev.ccpocket.app.resources.archive_remove_from_recents
import dev.ccpocket.app.resources.archive_session
import dev.ccpocket.app.resources.sidebar_archived
import dev.ccpocket.app.resources.dir_pinned
import dev.ccpocket.app.resources.group_current_dir
import dev.ccpocket.app.resources.group_delete
import dev.ccpocket.app.resources.group_delete_confirm
import dev.ccpocket.app.resources.group_move_out
import dev.ccpocket.app.resources.group_move_to
import dev.ccpocket.app.resources.group_name_hint
import dev.ccpocket.app.resources.group_new
import dev.ccpocket.app.resources.group_rename
import dev.ccpocket.app.resources.group_ungrouped
import dev.ccpocket.app.resources.new_session_title
import dev.ccpocket.app.resources.open_folder
import dev.ccpocket.app.resources.running
import dev.ccpocket.app.resources.session_rename
import dev.ccpocket.app.resources.split_open
import dev.ccpocket.app.resources.session_rename_hint
import dev.ccpocket.app.resources.settings_title
import dev.ccpocket.app.resources.support_title
import dev.ccpocket.app.resources.sidebar_clear
import dev.ccpocket.app.resources.sidebar_clear_confirm
import dev.ccpocket.app.resources.sidebar_no_computer
import dev.ccpocket.app.resources.sidebar_no_sessions_here
import dev.ccpocket.app.resources.sidebar_pins_full
import dev.ccpocket.app.resources.sidebar_recent_empty
import dev.ccpocket.app.resources.sidebar_recent_show_all
import dev.ccpocket.app.resources.sidebar_recent_show_less
import dev.ccpocket.app.resources.status_reconnecting
import dev.ccpocket.app.resources.switcher_all_projects
import dev.ccpocket.app.resources.switcher_recent
import dev.ccpocket.app.resources.new_session_here
import dev.ccpocket.app.resources.pin_project
import dev.ccpocket.app.resources.recent_forget_project
import dev.ccpocket.app.resources.this_machine
import dev.ccpocket.app.resources.unpin_project
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentBadge
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.fleet.AttentionBadge
import dev.ccpocket.app.ui.modelAlias
import dev.ccpocket.app.ui.sameDirPath
import dev.ccpocket.app.ui.tilde
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
        SidebarControlRow(model)
        SwitcherHeader(model)
        NewSessionRow { model.openNewSession() }
        // issue #163: the sibling entry for "I don't remember the path" — browse to it instead
        val folderScope = rememberCoroutineScope()
        OpenFolderRow { openFolderAction(folderScope, model) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        PinnedZone(model)
        RunningZone(model)
        RecentZone(model, Modifier.weight(1f))
        AllProjectsRow { model.browseProjects() }
        if (model.canArchiveSessions) ArchivedRow(model.archivedSessions.size) { model.browseArchived() }
        // The Review Center row came off (demoted 08-16, with the mobile header entry): the P2P review
        // flow saw no real use. The centre itself still opens via ⌘⇧R while its future form is decided.
        FooterActions(
            model = model,
            updateAvailable = model.updateState is DkUpdateState.Available,
            onHelp = { openWebUrl(SUPPORT_URL) },
            onSettings = { model.showSettings = true },
        )
    }
}

// ── zone 0: the window's control row (desktop chrome v2) ────────────────────────────────────────

/**
 * The sidebar's own 38dp top row — the surface that replaced the window-wide title bar.
 *
 * Exactly four operations, in the order the design fixed: traffic lights (macOS, windowed) · hide the
 * sidebar · ‹ back · › forward · search, which takes whatever width the four buttons leave and stops at
 * the sidebar's right edge. It carries no bottom hairline on purpose: the row and the device line under
 * it are one block of chrome sitting on the sidebar's own fill, and a rule between them would read as a
 * title bar again.
 *
 * The cluster's buttons are the SAME composables the leftmost chat sub-header adopts once the sidebar is
 * collapsed (see [SidebarToggleButton] / [SessionNavButtons]) — they move, they are not duplicated.
 */
@Composable
private fun SidebarControlRow(model: DesktopModel) {
    val chrome = LocalWindowChrome.current
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // fullscreen hides them: macOS moves the real ones into its auto-revealing menu bar, and a second
        // set painted here would be a decoration that no longer matches the window (issue #94)
        if (chrome.mac && !chrome.fullscreen) {
            TrafficLights(chrome.onClose, chrome.onMinimize, chrome.onToggleFullscreen)
            Spacer(Modifier.width(10.dp))
        }
        SidebarToggleButton(model)
        SessionNavButtons(model)
        Spacer(Modifier.width(4.dp)) // the mock's 6dp gap, minus the row's own 2dp spacing
        ChromeSearchField(onClick = { model.palette = PaletteScope.ALL }, modifier = Modifier.weight(1f))
    }
}

// ── zone 1: machine switcher header ─────────────────────────────────────────────────────────────

/**
 * Current machine + status, click (or ⌘0) opens the fleet dropdown; the attention bell rides right.
 *
 * A 26dp THIN LINE since the chrome-v2 redesign: search moved up into [SidebarControlRow], so what is
 * left here is one fact ("which computer am I driving") and one affordance (the bell). Shrinking it is
 * what buys the session list its ~96dp — the list now starts at 136dp instead of 232dp. The ⌘0 keycap
 * came off with the height; the shortcut itself is unchanged.
 */
@Composable
private fun SwitcherHeader(model: DesktopModel) {
    val c = model.activeComputer
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                    .clickable { model.switcherOpen = !model.switcherOpen }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (c != null) {
                    Icon(osIcon(c.os), null, tint = Tok.tx2, modifier = Modifier.size(12.dp))
                    // tightCenter: this row is now nothing BUT a text sharing a centre line with an icon,
                    // a dot and a chevron — the exact case where font-driven line boxes drift (#293)
                    Text(
                        c.name, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (c.online) PulseDot(Tok.ok, 5.dp)
                    else {
                        Dot(Tok.muted, 5.dp)
                        Text(
                            stringResource(Res.string.status_reconnecting), color = Tok.muted,
                            fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        stringResource(Res.string.sidebar_no_computer), color = Tok.muted, fontFamily = Dk.ui,
                        fontSize = 11.sp, style = tightCenter(11.sp), modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted, modifier = Modifier.size(11.dp))
            }
            val waiting = model.attention.size
            // Badge rides INLINE, not as a corner overlay: the hover pill's own clip() truncated an
            // offset badge, and a TopEnd anchor sits above the row's centre line. Same shape the
            // pinned/session rows already use.
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).hoverFill(RoundedCornerShape(6.dp))
                    .clickable { model.showAttention = !model.showAttention }.padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Notifications, null, tint = if (waiting > 0) Tok.tx else Tok.tx2, modifier = Modifier.size(13.dp))
                if (waiting > 0) AttentionBadge(waiting)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

// ── zone 2: pinned sessions (⌘1–9, drag to reorder) ─────────────────────────────────────────────

@Composable
private fun PinnedZone(model: DesktopModel) {
    val pins = model.pins
    val projectPins = model.projectPins
    if (pins.isEmpty() && projectPins.isEmpty()) return // pinning is discoverable from the hover pin on any session/project row
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(Res.string.dir_pinned), trailing = { Key("⌘1–9") })
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
        // pinned PROJECTS (issue #199) sit BELOW the session pins: they are entries, not live work, and
        // appending them keeps every session pin's ⌘n keycap exactly where it was.
        projectPins.forEachIndexed { i, p -> ProjectPinRow(model, p, index = pins.size + i) }
        if (model.pinsFull) {
            Text(
                stringResource(Res.string.sidebar_pins_full, DesktopModel.MAX_PINS),
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
    // live wins the label: a rename (from this row's menu or anywhere else) lands here immediately,
    // instead of the stored pin-time snapshot going stale until the session is re-pinned
    val title = live?.title ?: p.title
    // Right-click parity with the RECENT rows (the #119/#158/#202/#311 verbs), scoped the same way:
    // rename / groups / archive only when the pin's session is in the LIVE-LISTED project — liveSession
    // is the same "current machine's loaded list" fact RECENT's g.current encodes — and split stays a
    // this-machine gesture. RECENT's "Remove from recents" maps to this row's own removal verb: Unpin.
    var renaming by remember(p.sessionId) { mutableStateOf(false) }
    // renameError is keyed by sessionId alone, and a pinned session usually ALSO has a RECENT row: on a
    // refused rename both surfaces would swap into edit state at once. Only the row that actually sent
    // the rename shows the error editor — that's what this flag records.
    var renamedHere by remember(p.sessionId) { mutableStateOf(false) }
    val renameError = model.renameError(p.sessionId).takeIf { renamedHere }
    if (renaming || renameError != null) {
        Column {
            GroupNameInput(
                initial = title,
                hint = stringResource(Res.string.session_rename_hint),
                onCommit = { model.renameSession(p.sessionId, it, p.cwd); renaming = false; renamedHere = true },
                onCancel = { renaming = false; renamedHere = false; model.dismissRenameError() },
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
    val asSession = live ?: DkSession(p.sessionId, p.cwd, title, agent = p.agent)
    val splittable = !remote && splittableNow(model, asSession)
    val openInSplit = stringResource(Res.string.split_open)
    val rename = stringResource(Res.string.session_rename)
    val moveTo = stringResource(Res.string.group_move_to)
    val moveOut = stringResource(Res.string.group_move_out)
    val archive = stringResource(Res.string.archive_session)
    val unpinLabel = stringResource(Res.string.unpin_project)
    ContextMenuArea(
        items = {
            // same families as SessionRow: navigate → edit → file → remove, so the two menus read as
            // one. Capability reads live INSIDE this lambda — it runs when the menu opens, so N pin
            // rows don't each rescan model.sessions on every Sessions push for a menu opened rarely.
            // rename/archive carry the pin's OWN cwd (same lift as the RECENT rows); groups stay
            // current-list only — the daemon lists groups per listed directory.
            val inCurrentList = !remote && model.sessions.any { it.sessionId == p.sessionId }
            val canRename = !remote && model.canRenameSessions && asSession.agent == AgentKind.CLAUDE
            val menuGroups = if (inCurrentList && model.canEditGroups) model.customGroups else emptyList()
            val canArchive = !remote && model.canArchiveSessions
            joinMenuFamilies(
                buildList { if (splittable) add(PocketMenuItem(openInSplit) { model.openInSplit(asSession) }) },
                buildList { if (canRename) add(PocketMenuItem(rename) { renaming = true }) },
                buildList {
                    menuGroups.filter { it.id != live?.group }.forEach { grp ->
                        add(PocketMenuItem(grp.name, mutedPrefix = "$moveTo ·") { model.assignGroup(p.sessionId, grp.id) })
                    }
                    // "out" only beside "to", as on the RECENT rows: a pin into a project that isn't listed resolves
                    // [live] from that project's RECENT copy, whose groups aren't the listing's to edit (#360)
                    if (live?.group != null && menuGroups.isNotEmpty()) add(PocketMenuItem(moveOut) { model.assignGroup(p.sessionId, null) })
                },
                buildList {
                    if (canArchive) add(PocketMenuItem(archive) { model.archiveSession(asSession) })
                    add(PocketMenuItem(unpinLabel, removal = true) { model.unpin(p) })
                },
            )
        },
    ) {
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
            title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            style = tightCenter(13.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (remote && computer != null) {
            Icon(osIcon(computer.os), null, tint = Tok.muted, modifier = Modifier.size(11.dp))
            Text(
                computer.name, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
                style = tightCenter(10.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp),
            )
        }
        AgentBadge(p.agent, compact = true)
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
}

/**
 * One pinned PROJECT row (issue #199): folder glyph · project name (mono, the same type the RECENT and
 * RUNNING rows give a project) · hover ＋ (new session here) · hover unpin · ⌘n keycap while the numbering
 * still reaches. Click opens the project's session list — the pinned thing is the project, not a session.
 * No drag grip: reordering belongs to the session pins' ⌘1–9 ladder, and these always follow them.
 */
@Composable
private fun ProjectPinRow(model: DesktopModel, p: DkProjectPin, index: Int) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(32.dp).hoverable(src).hoverFill()
            .clickable { model.openProjectPin(p) }.testTag("project-pin:${p.path}").padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.muted, modifier = Modifier.size(12.dp))
        Text(
            p.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, style = tightCenter(12.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (hovered) {
            NewSessionHere { model.openNewSession(tilde(p.path)) }
            Icon(
                PinSlashIcon, stringResource(Res.string.unpin_project), tint = Tok.tx2,
                modifier = Modifier.size(13.dp).clickable { model.unpinProject(p.path) },
            )
            if (index < DesktopModel.MAX_PINS) Key("⌘${index + 1}")
        }
    }
}

/** The ＋ that starts a session in the project the row names (issue #199) — accent, so it reads as the
 *  same call to action the sidebar's own "New session" row is, just scoped to one project. */
@Composable
private fun NewSessionHere(onClick: () -> Unit) {
    Icon(
        Icons.Rounded.Add, stringResource(Res.string.new_session_here), tint = Tok.accent,
        modifier = Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
    )
}

// ── zone 3: running (flat, cross-machine) ───────────────────────────────────────────────────────

@Composable
private fun RunningZone(model: DesktopModel) {
    val rows = model.runningRows
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(Res.string.running))
        rows.forEach { r ->
            RunningRow(
                r,
                onBrowse = { model.browseRunning(r.machine, r.project) },
                // a named session opens itself — the SAME verb the RECENT rows send (issue #358). Only the
                // project fallback still goes through openRunning, which lists and resumes whatever is live.
                onClick = { r.session?.let { model.selectSession(it) } ?: model.openRunning(r.machine, r.project) },
            )
        }
    }
}

/** One cross-machine RUNNING row: accent pulse · the running SESSION's title (mono) · its project as a
 *  muted second fact · which machine, right-aligned muted.
 *
 *  Issue #358 moved the title into the lead: the project name alone left two turns in one folder as two
 *  identical rows. The project keeps the SessionRow model-label shape — muted mono 10sp, bounded, and
 *  handed back to the hover affordances the moment the pointer arrives, so nothing fights over 30dp.
 *  Rows we cannot name a session for (a satellite machine's project flag) still lead with the project.
 *
 *  Click = open that session (or, on a project row, jump to the live one); the hover ≡ = the project's
 *  session LIST instead (issue #49 — the direct jump made the dir's other sessions look unreachable). */
@Composable
private fun RunningRow(r: DkRunningRow, onBrowse: () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val p = r.project
    val s = r.session
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverable(src).hoverFill().clickable(onClick = onClick)
            .testTag("running:${s?.sessionId ?: p.path}").padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.accent, 5.dp)
        // an untitled session (a brand-new one whose first turn hasn't named it) has nothing to lead with —
        // the project name is still the truest thing we can say about it
        Text(
            s?.title?.takeIf { it.isNotBlank() } ?: p.name,
            color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, style = tightCenter(12.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (s != null && !hovered) Text(
            p.name, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp),
        )
        if (p.sharedBy != null) SharedPill() // a guest's shared folder (issue #115) — provenance at a glance
        if (hovered) Text(
            "≡", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, style = tightCenter(13.sp),
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onBrowse).padding(horizontal = 3.dp),
        ) else Text(
            r.machine.computer.name, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── zone 4: RECENT — the visited projects' sessions, grouped, one scroll ────────────────────────

// THE render predicate for RECENT groups — [recentRows] and the reveal effects both filter through this one
// definition, so the row a reveal resolves is a row the list actually draws. Named groups count as content (#360):
// a project whose groups are still empty holds structure the user made, not an empty listing.
private fun renderedGroups(model: DesktopModel) =
    model.sessionGroups.filter { it.current || it.sessions.isNotEmpty() || it.customGroups.isNotEmpty() }

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
            stringResource(Res.string.switcher_recent).uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, style = tightCenter(11.sp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp)) // keep the row baseline stable pre-hover (SectionLabel parity)
        Key("⌘R")
        if (hovered && model.sessionGroups.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (arm) Res.string.sidebar_clear_confirm else Res.string.sidebar_clear),
                color = if (arm) Tok.accent else Tok.tx2,
                fontFamily = Dk.ui, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(10.5.sp),
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
        // #373: the reveal request (see below) an effect last took up. Remembered ABOVE the empty-list return, so it
        // outlives everything under it: a machine switch or an emptied list restarts that effect, and the restart must
        // neither finish an old request on the next machine nor replay one on the way back. Seeded with the request
        // already there, which belongs to an earlier sidebar — from before this one was on screen.
        val taken = remember { TakenReveal(model.projectListReveal) }
        val groups = renderedGroups(model)
        if (groups.isEmpty()) {
            Text(
                stringResource(Res.string.sidebar_recent_empty),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            return@Column
        }
        // collapse set + scroll position hoisted out of the LazyColumn so the reveal effect below can
        // drive them (expand a folded group, scroll it in) without collapsing the others (#83)
        val collapsed = remember { mutableStateListOf<String>() }
        // #282: the rewound bucket's fold, collapsed by default (that default IS the feature's promise)
        var rewoundOpen by remember { mutableStateOf(false) }
        // #373: the project-count fold. Lives as long as this UI does (no stored preference) and is keyed on
        // the machine, so switching computers starts folded again. The reveal effects below are keyed on it too:
        // one still waiting when the machine changes ends there, instead of unfolding the next machine's list.
        val machine = model.activeComputer?.accountId
        val limit = remember(machine) { RecentLimit() }
        val listState = rememberLazyListState()
        // which header's refresh icon spins: the clicked group's; ⌘R has no click, so the current one's
        var refreshTarget by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(model.sessionsRefreshing) { if (!model.sessionsRefreshing) refreshTarget = null }
        val spinningPath = if (model.sessionsRefreshing) refreshTarget ?: groups.firstOrNull { it.current }?.path else null
        val selectedId = model.selectedSessionId // resolved by scanning the session list — once, not per row
        val rows = recentRows(model, groups, collapsed, limit.expanded, rewoundOpen)
        val shownRows by rememberUpdatedState(rows)
        // Unfold [path]'s group — lifting the #373 fold first when the group sits past it — and scroll the row
        // [revealIndex] picks into view, unless that row is already on screen. Only the effects keyed on [limit] call
        // this, so the fold it lifts is always the one of the machine that asked.
        suspend fun reveal(path: String, sessionId: String?) {
            if (renderedGroups(model).indexOfFirst { it.path == path } >= RECENT_VISIBLE_LIMIT) limit.expanded = true
            collapsed.remove(path) // a no-op when the group is already open
            // Both changes land on a later composition, and its layout after that. Wait until the list holds the
            // target group open AND has measured exactly those rows: the index and the on-screen test below have
            // to describe what is drawn, not the rows from before the unfold.
            val laidOut = withTimeoutOrNull(1_000) {
                snapshotFlow {
                    shownRows.takeIf { r ->
                        listState.layoutInfo.totalItemsCount == r.size &&
                            r.any { it is RecentRow.Header && it.group.path == path && !it.closed }
                    }
                }.filterNotNull().first()
            } ?: return
            val index = laidOut.revealIndex(path, sessionId)
            if (index < 0) return
            val key = laidOut[index].key
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) listState.animateScrollToItem(index)
        }
        val request = model.projectListReveal
        // An explicit navigation's reveal. A project pin represents the LIST, not a single session; a session
        // row, pin or history step (#373) one row in it. Re-listing updates the model but cannot touch this
        // composable-local fold state, which made a folded target look inert — and the session the user goes
        // back to can still be the selected one, a click the selection effect below cannot see. Observe the
        // explicit, repeatable request, wait until its target is listed, then unfold it and bring it into view.
        // Other groups keep their current fold state.
        LaunchedEffect(limit, request) {
            // Taken up once, on the machine it names: a request for another machine's list is left to the restart
            // that machine's arrival brings, and one already taken up is never taken again — see [taken].
            val r = request?.takeIf { it != taken.request && (it.accountId == null || it.accountId == machine) }
                ?: return@LaunchedEffect
            taken.request = r
            val targetPath = withTimeoutOrNull(5_000) {
                snapshotFlow {
                    val listed = renderedGroups(model)
                    val session = r.sessionId
                    if (session == null) listed.firstOrNull { sameDirPath(it.path, r.path) }?.path
                    else listed.firstOrNull { g -> g.sessions.any { it.sessionId == session } }?.path
                }.filterNotNull().first()
            } ?: return@LaunchedEffect
            // openProject makes a pinned project the most-recent (first) group in the live model, but a session
            // navigation leaves its project where it is, and a fixed-order model (preview/test) moves nothing —
            // either can sit past the #373 fold, which reveal lifts
            if (r.sessionId != null) limit.revealedSelection = r.sessionId
            reveal(targetPath, r.sessionId)
        }
        // Reveal the selected session's group when the selection changes — e.g. clicking a RUNNING project
        // resumes its live session (#83). Expand that group if the user had folded it and scroll it into
        // view, but only the TARGET group is touched (multi-expand is intentional) and only when the row
        // isn't already on screen, so we never refold others or yank a session the user can already see.
        LaunchedEffect(limit, selectedId) {
            // #373: only a selection no reveal has acted on yet. The id blinks (null while an open settles, then
            // the same id again), and re-revealing on that would undo the user's own "Show less" with no
            // navigation behind it. Going back to a session on purpose is a navigation: its request reveals it.
            if (selectedId == null || selectedId == limit.revealedSelection) return@LaunchedEffect
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
            limit.revealedSelection = selectedId
            reveal(targetPath, selectedId)
        }
        // testTag: the RECENT list overflows the default test viewport (it grows with every seed
        // session) — UI tests scroll it to their target instead of assuming everything fits
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().testTag("sidebar-list")) { // lazy: a visited project can hold hundreds of sessions
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is RecentRow.Header -> {
                        val g = row.group
                        GroupHeader(
                            g, row.closed,
                            current = g.current,
                            refreshing = g.path == spinningPath,
                            pinned = model.isProjectPinned(g.path),
                            onRefresh = { refreshTarget = g.path; model.refresh(g) },
                            onTogglePin = { if (model.isProjectPinned(g.path)) model.unpinProject(g.path) else model.pinProject(g.path, g.name) },
                            onNewSession = { model.openNewSession(tilde(g.path)) },
                            onForget = { model.forgetProject(g) },
                            onToggle = { if (row.closed) collapsed.remove(g.path) else collapsed.add(g.path) },
                        )
                    }
                    is RecentRow.NewGroup -> NewGroupRow(model)
                    is RecentRow.Empty -> Text(
                        stringResource(Res.string.sidebar_no_sessions_here),
                        color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                        modifier = Modifier.padding(start = 32.dp, top = 2.dp, bottom = 6.dp),
                    )
                    is RecentRow.Section -> CustomGroupHeader(model, row.path, row.section)
                    is RecentRow.Session -> SessionRow(
                        model, row.session, selected = row.session.sessionId == selectedId, indented = row.indented,
                        menuGroups = row.menuGroups, renameable = row.renameable, canArchive = row.canArchive,
                    ) { model.selectSession(row.session) }
                    is RecentRow.LimitToggle -> RecentLimitToggle(row.expanded) { limit.expanded = !row.expanded }
                    is RecentRow.RewoundHeader -> Row(
                        Modifier.fillMaxWidth().height(26.dp).hoverFill()
                            .clickable { rewoundOpen = !rewoundOpen }
                            .padding(start = 14.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
                            modifier = Modifier.size(12.dp).rotate(if (rewoundOpen) 0f else -90f),
                        )
                        Text(
                            stringResource(Res.string.rewind_group_rewound, row.count),
                            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    is RecentRow.Rewound -> SessionRow(model, row.session, selected = row.session.sessionId == selectedId, indented = true) {
                        model.selectSession(row.session)
                    }
                }
            }
        }
    }
}

/** How many RECENT projects show before the rest fold behind "Show all" (#373). */
private const val RECENT_VISIBLE_LIMIT = 5

/**
 * RECENT's project-count fold (#373) for one machine's list — see [RecentZone].
 *
 * [revealedSelection] is the selection a reveal last acted on, so the selection effect restarting on that same id
 * is recognised as no navigation at all; going back to that session on purpose arrives as a reveal request
 * instead. Plain, not state: nothing is drawn from it.
 */
private class RecentLimit {
    var expanded by mutableStateOf(false)
    var revealedSelection: String? = null
}

/** The RECENT reveal request an effect last took up (#373) — see [RecentZone]. Plain, not state, for the same reason. */
private class TakenReveal(var request: DkProjectListReveal?)

/**
 * One row of the RECENT list, keyed exactly as the LazyColumn keys it.
 *
 * [recentRows] is THE definition of what RECENT emits and in which order: the list draws these rows, and the
 * reveals (#83, #373) look their target up in the very same list. The index it replaced was counted by hand and
 * missed "+ New group" and the custom-group sections, so a reveal below the current project scrolled short.
 */
private sealed interface RecentRow {
    val key: String

    data class Header(val group: DkSessionGroup, val closed: Boolean) : RecentRow {
        override val key = "h:${group.path}"
    }

    data class NewGroup(val path: String) : RecentRow {
        override val key = "ng:$path"
    }

    data class Empty(val path: String) : RecentRow {
        override val key = "e:$path"
    }

    data class Section(val path: String, val section: SessionSection) : RecentRow {
        override val key = "gh:$path:${section.id}"
    }

    data class Session(
        val path: String,
        val session: DkSession,
        val indented: Boolean,
        val menuGroups: List<DkGroup>,
        val renameable: Boolean,
        val canArchive: Boolean,
    ) : RecentRow {
        override val key = "s:$path:${session.sessionId}"
    }

    /** "Show all" / "Show less" (#373): after the last listed project, ahead of the rewound bucket. */
    data class LimitToggle(val expanded: Boolean) : RecentRow {
        override val key = "recent-limit"
    }

    data class RewoundHeader(val count: Int) : RecentRow {
        override val key = "rewound-header"
    }

    data class Rewound(val path: String, val session: DkSession) : RecentRow {
        override val key = "rw:${session.sessionId}"
    }
}

/** Every row RECENT emits, in order — see [RecentRow]. [expanded] lifts the [RECENT_VISIBLE_LIMIT] fold. */
private fun recentRows(
    model: DesktopModel,
    groups: List<DkSessionGroup>,
    collapsed: List<String>,
    expanded: Boolean,
    rewoundOpen: Boolean,
): List<RecentRow> = buildList {
    // #373: past the limit the rest wait behind "Show all" — a cut of the list as it stands, never a re-sort
    val listed = if (expanded) groups else groups.take(RECENT_VISIBLE_LIMIT)
    for (g in listed) {
        val closed = g.path in collapsed
        add(RecentRow.Header(g, closed))
        if (closed) continue
        // issue #119: the daemon lists groups per dir. The live-listed project's come from the model, whose
        // group verbs all act on that listing; any other project renders the copy its RECENT snapshot kept
        // (#360) — the same sections in the same order, READ-ONLY, since a verb issued there would land on
        // the listed project. No groups (an older daemon, a guest, none made yet) renders FLAT.
        // #282: the rewound originals leave the visible list before anything else groups it,
        // so the fold holds across custom groups and the flat fallback alike.
        val shown = visibleSessions(g.sessions)
        val custom = if (g.current) model.customGroups else g.customGroups
        // group editing — "+ New group", section rename/delete, moving a session — is the listed project's alone
        val editable = g.current && model.canEditGroups
        // sessions the current project can be moved between (owner + has groups) — drives the row
        // right-click "move to / remove from group" entries; empty everywhere else so neither appears.
        val menuGroups = if (editable) custom else emptyList()
        // right-click "Rename session" (issue #158) — EVERY group's rows now: the row hands its
        // own dir to the rename, so the frame resolves against the right project wherever the
        // listing points (the old current-only gate existed because the UI defaulted the dir).
        // A guest's shared project stays out — its rename would be refused daemon-side anyway.
        val renameable = model.canRenameSessions && g.sharedBy == null
        // Archive too (#202's gate lifted): the verb always carried the row's own cwd, and its
        // Sessions(thatProject) echo repointing the listing now MATCHES the convention that the
        // listed project follows wherever the user acts, instead of contradicting it.
        // guest-shared rows keep archive off too (same asymmetry rename already closed): a
        // guest's SetSessionArchived is a silent daemon-side no-op with no error surface.
        val canArchive = model.canArchiveSessions && g.sharedBy == null
        // "+ New group" sits at the TOP of the project's sessions (matches mobile) — a bottom
        // entry forces scrolling past a long session list to create a group. Current + group-aware
        // + owner only (canEditGroups folds in groupsSupported), so it also creates the FIRST group
        // from a still-flat list; an older daemon / guest / RECENT snapshot shows nothing.
        if (editable) add(RecentRow.NewGroup(g.path))
        if (custom.isEmpty()) {
            if (shown.isEmpty()) add(RecentRow.Empty(g.path))
            shown.forEach { add(RecentRow.Session(g.path, it, indented = false, menuGroups, renameable, canArchive)) }
        } else {
            sessionSections(shown, custom, editable).forEach { sec ->
                add(RecentRow.Section(g.path, sec))
                if (!model.groupCollapsed(g.path, sec.id)) {
                    sec.sessions.forEach { add(RecentRow.Session(g.path, it, indented = true, menuGroups, renameable, canArchive)) }
                }
            }
        }
    }
    if (groups.size > RECENT_VISIBLE_LIMIT) add(RecentRow.LimitToggle(expanded))
    // ── Rewound sessions (issue #282, design frame D) ──────────────────────────────────────
    // After every project group and the fold, never inside a group. Collapsed by default — the whole point of
    // the group is that a rewind leaves the visible list the length it was. Gathered from EVERY group, folded-away
    // projects included: the #373 fold hides projects, it must not take their rewound originals out of reach.
    val rewound = groups.flatMap { g ->
        val gone = supersededIds(g.sessions)
        g.sessions.filter { it.sessionId in gone }.map { RecentRow.Rewound(g.path, it) }
    }
    if (rewound.isNotEmpty()) {
        add(RecentRow.RewoundHeader(rewound.size))
        if (rewoundOpen) addAll(rewound)
    }
}

/**
 * The row a reveal lands on (#83, #373): the selected session's own row wherever the list emits it — under its
 * project, or in the open rewound bucket — else the custom-group header folding it, else its project's header,
 * which is also where a project reveal ([sessionId] null) lands. -1 when the project isn't listed.
 */
private fun List<RecentRow>.revealIndex(path: String, sessionId: String?): Int {
    if (sessionId != null) {
        val own = indexOfFirst {
            it is RecentRow.Session && it.path == path && it.session.sessionId == sessionId ||
                it is RecentRow.Rewound && it.path == path && it.session.sessionId == sessionId
        }
        if (own >= 0) return own
        val section = indexOfFirst { r ->
            r is RecentRow.Section && r.path == path && r.section.sessions.any { it.sessionId == sessionId }
        }
        if (section >= 0) return section
    }
    return indexOfFirst { it is RecentRow.Header && it.group.path == path }
}

/**
 * RECENT's project-count fold (#373): "Show all" while more than [RECENT_VISIBLE_LIMIT] projects wait, "Show less"
 * once they all show. It wears the rewound bucket's quiet 26dp row, so the list's own controls read as one family.
 */
@Composable
private fun RecentLimitToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).hoverFill().clickable(onClick = onToggle)
            .testTag("recent-limit").padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // points where the list goes next: down to bring the rest in, up to fold them away again
        Icon(
            Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
            modifier = Modifier.size(12.dp).rotate(if (expanded) 180f else 0f),
        )
        Text(
            stringResource(if (expanded) Res.string.sidebar_recent_show_less else Res.string.sidebar_recent_show_all),
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            style = tightCenter(11.sp), maxLines = 1,
        )
    }
}

/**
 * A RECENT group header: folder + project name (mono, muted) · ＋ new session here · hover pin/refresh ·
 * running pulse · collapse chevron.
 *
 * The ＋ (issue #199) is the one affordance here that does NOT hide at rest: it is the reason to look at
 * this list ("that project — start something there"), and a hover-only entry would leave the path from
 * RECENT to a new session as invisible as it was before. Pin joins the hover cluster instead, next to
 * refresh — it's a preference, not a call to action, and it wears the same glyphs the session rows use.
 *
 * Right-click reaches the two verbs a PROJECT owns (issue #359), in the same navigate → edit → file →
 * remove order the session rows use: pin/unpin (edit) and "Remove from recents" (remove). The session
 * rows' identically-worded entry forgets ONE row; this one forgets the whole folder — the verb is the
 * same promise at a different scope, so it is deliberately the same words.
 */
@Composable
private fun GroupHeader(
    g: DkSessionGroup,
    closed: Boolean,
    current: Boolean,
    refreshing: Boolean,
    pinned: Boolean,
    onRefresh: () -> Unit,
    onTogglePin: () -> Unit,
    onNewSession: () -> Unit,
    onForget: () -> Unit,
    onToggle: () -> Unit,
) {
    val pinLabel = stringResource(if (pinned) Res.string.unpin_project else Res.string.pin_project)
    val forget = stringResource(Res.string.recent_forget_project)
    ContextMenuArea(
        items = {
            joinMenuFamilies(
                listOf(PocketMenuItem(pinLabel, onClick = onTogglePin)),
                // the CURRENT dir is exempt: it is not a RECENT entry at all but the synthetic live group
                // (#211's chip says so), so it would come straight back and the verb would read as broken
                buildList { if (!current) add(PocketMenuItem(forget, removal = true, onClick = onForget)) },
            )
        },
        content = { GroupHeaderBody(g, closed, current, refreshing, pinned, onRefresh, onTogglePin, onNewSession, onToggle) },
    )
}

@Composable
private fun GroupHeaderBody(
    g: DkSessionGroup,
    closed: Boolean,
    current: Boolean,
    refreshing: Boolean,
    pinned: Boolean,
    onRefresh: () -> Unit,
    onTogglePin: () -> Unit,
    onNewSession: () -> Unit,
    onToggle: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(28.dp).hoverable(src).hoverFill().clickable(onClick = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                g.name, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                style = tightCenter(11.5.sp),
            )
            // #211: the currently-listed dir is always present (it re-enters as the synthetic live group
            // even after "clear"), which read as "the last row won't clear". This quiet chip names it as
            // the open directory instead — a distinct affordance, not a leftover RECENT entry.
            if (current) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(Res.string.group_current_dir), color = Tok.accent, fontFamily = Dk.ui,
                    fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = tightCenter(9.sp),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Tok.accent.copy(alpha = 0.12f))
                        .border(1.dp, Tok.accent.copy(alpha = 0.32f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        NewSessionHere(onNewSession)
        // Keep hover actions' slots stable: otherwise entering the row moves the ＋ out from
        // under the pointer before its click is dispatched (Compose 1.12 mouse input).
        Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
            if (hovered) Icon(
                if (pinned) PinSlashIcon else PinIcon,
                stringResource(if (pinned) Res.string.unpin_project else Res.string.pin_project),
                tint = if (pinned) Tok.tx2 else Tok.accent,
                modifier = Modifier.size(13.dp).clickable(onClick = onTogglePin),
            ) else if (pinned) Icon(PinIcon, null, tint = Tok.muted, modifier = Modifier.size(11.dp))
        }
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
        Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
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
        }
        if (closed && g.sessions.any { it.running }) PulseDot(Tok.ok, 5.dp) // running stays visible when folded
        Icon(
            Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
            modifier = Modifier.size(13.dp).rotate(if (closed) -90f else 0f),
        )
    }
}

// ── issue #119: custom session-group sections inside a project ──────────────────────────────────────

private const val UNGROUPED_SECTION = "__ungrouped__"

/** A rendered slice of a project's session list: a named custom group, or the Ungrouped fallback ([name] null).
 *  [editable] = its header offers rename/delete: a named group the listed project may edit — never Ungrouped, never
 *  a RECENT snapshot's copy (#360). Named groups always show, even empty (structure the user made, and in the
 *  listed project a move target); Ungrouped shows only when it actually holds rows. */
private data class SessionSection(val id: String, val name: String?, val editable: Boolean, val sessions: List<DkSession>)

/**
 * Rewind fold (issue #282), the desktop twin of the mobile list's [dev.ccpocket.app.ui.session.splitRewound].
 *
 * A session another row in the SAME list declares it rewound is superseded: it leaves the default list so
 * the branch that replaced it takes its place, one out for one in. Scoped to the list it is given, so an
 * original whose successor isn't here (a different project's snapshot) stays visible rather than
 * disappearing with nothing to point at.
 */
private fun supersededIds(sessions: List<DkSession>): Set<String> =
    sessions.mapNotNullTo(HashSet()) { s -> s.rewindOf?.takeIf { it != s.sessionId } }

/** The rows the default list shows — everything a peer has not rewound. Applied once, in [recentRows], which
 *  the list and its reveals both read. */
private fun visibleSessions(sessions: List<DkSession>): List<DkSession> {
    val gone = supersededIds(sessions)
    return if (gone.isEmpty()) sessions else sessions.filter { it.sessionId !in gone }
}

private fun sessionSections(sessions: List<DkSession>, custom: List<DkGroup>, editable: Boolean): List<SessionSection> {
    val ids = custom.mapTo(HashSet()) { it.id }
    val named = custom.sortedBy { it.order }.map { grp ->
        SessionSection(grp.id, grp.name, editable, sessions = sessions.filter { it.group == grp.id })
    }
    // a session whose group id no longer exists (just-deleted group, cross-version) also falls to Ungrouped
    val ungrouped = sessions.filter { it.group == null || it.group !in ids }
    return if (ungrouped.isEmpty()) named else named + SessionSection(UNGROUPED_SECTION, name = null, editable = false, sessions = ungrouped)
}

/** A custom-group sub-header (issue #119): collapse chevron · name · session-count badge, with hover
 *  rename/delete for editable groups (Ungrouped is a fallback and can't be edited, nor can another project's
 *  copy, #360). Rename swaps in an inline field; delete arms a confirm bar (group_delete_confirm). */
@Composable
private fun CustomGroupHeader(model: DesktopModel, projectPath: String, sec: SessionSection) {
    val canEdit = model.canEditGroups && sec.editable
    // keyed on [canEdit] too: a header that stops being editable with its field or confirm bar open — its project
    // stopped being the listed one (#360) — drops them, because committing either acts on the LISTED project's
    // group of this id
    var editing by remember(sec.id, canEdit) { mutableStateOf(false) }
    var confirming by remember(sec.id, canEdit) { mutableStateOf(false) }
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
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(11.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text("${sec.sessions.size}", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp))
        Spacer(Modifier.weight(1f))
        if (canEdit && hovered) {
            Text(
                stringResource(Res.string.group_rename), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, style = tightCenter(10.sp),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { editing = true }.padding(horizontal = 3.dp),
            )
            Text(
                stringResource(Res.string.group_delete), color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, style = tightCenter(10.sp),
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
            style = tightCenter(10.5.sp),
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
            Text(stringResource(Res.string.switcher_all_projects) + "…", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        }
    }
}

/** The cross-project archive entry (issue #202). Its right edge carries a COUNT rather than a keycap:
 *  on desktop the count ticking is the receipt for an archive action, which is why this shell shows no
 *  toast for one (the phone, having no such always-visible counter, does). */
@Composable
private fun ArchivedRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Inventory2, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
        Text(stringResource(Res.string.sidebar_archived), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
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
        Text(stringResource(Res.string.new_session_title), color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Key("⌘N")
    }
}

/** "Open Folder…" (issue #163) — the browse-to-it twin of [NewSessionRow], deliberately quieter: it is
 *  a way IN to an existing folder, not a call to action, so it wears the muted tint rather than accent. */
@Composable
private fun OpenFolderRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = Tok.tx2, modifier = Modifier.size(13.dp))
        Text(stringResource(Res.string.open_folder), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Key("⌘O")
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
    canArchive: Boolean = false,
    onClick: () -> Unit,
) {
    // rename entry (issue #158): Claude rows only — a Codex rename write path is out of scope
    // Claude only: rename lands a record in the session's transcript FILE — codex rollouts are
    // self-managed and opencode sessions live in SQLite (no file), so the daemon's rename path
    // fails for both; don't offer an entry that can only end in rename_failed.
    // (agent is non-null on DkSession — the same predicate PinRow uses, kept identical on purpose)
    val canRename = renameable && s.agent == AgentKind.CLAUDE
    // inline rename swaps the row for a prefilled title field (the group header's rename pattern);
    // committing sends the rename — the daemon re-pushes Sessions, which refreshes the row title.
    // A REFUSED rename (rename_failed) re-opens the editor with the daemon's reason inline: the ask
    // came from THIS row, so the feedback lands here — the chat transcript is the wrong surface (the
    // common refusal, a terminal-held session, is renamed with no chat open at all). Esc dismisses.
    var renaming by remember(s.sessionId) { mutableStateOf(false) }
    // The refusal shows here UNCONDITIONALLY (the #158 contract, pinned by DesktopUiTest): the error is
    // a daemon push that can land after this row recomposed or scrolled back in, so a "did *I* ask"
    // gate would drop it. When the same session is also PINNED, the pin row's gated copy stays closed —
    // this row is the one surface the refusal is guaranteed to reach.
    val renameError = model.renameError(s.sessionId)
    if (renaming || renameError != null) {
        Column {
            GroupNameInput(
                initial = s.title,
                hint = stringResource(Res.string.session_rename_hint),
                onCommit = { model.renameSession(s.sessionId, it, s.cwd); renaming = false },
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
    // #202 widened this short-circuit: archiving is available on rows that can neither be renamed nor
    // grouped, so "no groups and no rename" no longer means "no menu".
    // #311 widened it again: "Open in split" is available on any row while the split has room, so a row
    // with no rename/group/archive verbs still earns a menu.
    val splittable = splittableNow(model, s)
    if (menuGroups.isEmpty() && !canRename && !canArchive && !splittable) { SessionRowBody(model, s, selected, indented, onClick); return }
    val openInSplit = stringResource(Res.string.split_open)
    val rename = stringResource(Res.string.session_rename)
    val moveTo = stringResource(Res.string.group_move_to)
    val moveOut = stringResource(Res.string.group_move_out)
    val archive = stringResource(Res.string.archive_session)
    val removeRecents = stringResource(Res.string.archive_remove_from_recents)
    ContextMenuArea(
        items = {
            // verb families in the fixed navigate → edit → file → remove order ("Context Menu v1"), a
            // separator at each boundary. Archive sits directly ABOVE "Remove from recents" on purpose:
            // the two are the pair users most need to tell apart, and reading them adjacently is what
            // teaches the difference (persistent+shared vs local+temporary).
            joinMenuFamilies(
                // #311: the sidebar is where a session is picked, so it is also where one is sent to its
                // own column. First — it navigates, and navigation outranks editing.
                buildList { if (splittable) add(PocketMenuItem(openInSplit) { model.openInSplit(s) }) },
                buildList { if (canRename) add(PocketMenuItem(rename) { renaming = true }) },
                buildList {
                    menuGroups.filter { it.id != s.group }.forEach { grp ->
                        add(PocketMenuItem(grp.name, mutedPrefix = "$moveTo ·") { model.assignGroup(s.sessionId, grp.id) })
                    }
                    // "out" only beside "to": [menuGroups] is empty wherever this row's groups aren't the listing's to
                    // edit — another project's RECENT copy (#360), a guest, an older daemon — and moving out is a verb
                    // on that same listing
                    if (s.group != null && menuGroups.isNotEmpty()) add(PocketMenuItem(moveOut) { model.assignGroup(s.sessionId, null) })
                },
                buildList {
                    if (canArchive) {
                        add(PocketMenuItem(archive) { model.archiveSession(s) })
                        add(PocketMenuItem(removeRecents, removal = true) { model.hideSession(s) })
                    }
                },
            )
        },
        content = { SessionRowBody(model, s, selected, indented, onClick) },
    )
}

@Composable
private fun SessionRowBody(model: DesktopModel, s: DkSession, selected: Boolean, indented: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (selected || hovered) Tok.raised else Color.Transparent
    // drag-to-split: press a session row and drag it over the chat area — the hovered column splits
    // into two drop halves (left = new column to its left, right = to its right; issue #336). NOT
    // detectDragGestures: its mouse slop (~0.125dp) turns any real click's jitter into a consumed drag
    // (the pin rows' documented trap), and its first vertical touch move steals the list's scroll —
    // sessionRowSplitDrag watches without consuming until a real horizontal drag declares itself.
    // Coordinates ride a plain Ref (not snapshot state): every layout pass repositions rows, and only
    // the drag ever reads it.
    val drag = LocalSplitDrag.current
    val rowCoords = remember(s.sessionId) { androidx.compose.ui.node.Ref<androidx.compose.ui.layout.LayoutCoordinates>() }
    // A row can leave the composition MID-DRAG (a Sessions push archives it / drops it from RECENTS):
    // the gesture coroutine dies with it and neither end nor cancel ever runs, freezing the highlight
    // over the chat area. Disposal is the one hook that still fires — release the drag it owned.
    DisposableEffect(s.sessionId) {
        onDispose { if (drag.session?.sessionId == s.sessionId) drag.clear() }
    }
    Box(
        Modifier.fillMaxWidth().height(32.dp).hoverable(src).clickable(onClick = onClick)
            .onGloballyPositioned { rowCoords.value = it }
            .sessionRowSplitDrag(
                key = s.sessionId,
                coords = { rowCoords.value },
                begin = { drag.begin(s, it) },
                move = { drag.moveTo(it) },
                end = { performDrop(model, s, drag); drag.clear() },
                cancel = { drag.clear() },
            )
            .background(bg),
    ) {
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
                        // bounded like the pinned row's machine name so a long alias can't steal the title's room (#179)
                        Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp))
                    }
                }
            }
            AgentBadge(s.agent, compact = true)
            if (s.pending > 0) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent).clickable(onClick = onClick)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Rounded.PriorityHigh, null, tint = Tok.base, modifier = Modifier.size(10.dp))
                    Text("${s.pending}", color = Tok.base, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(10.sp))
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

/** [updateAvailable] tints the footer version accent (issue #200) — the startup check runs silently, so
 *  this dim mono line is the only place a waiting update would otherwise be invisible. Settings ▸ About
 *  is one click away behind the same row. */
@Composable
private fun FooterActions(model: DesktopModel, updateAvailable: Boolean, onHelp: () -> Unit, onSettings: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        // The Claude allowance strip rides INSIDE the footer, under the same single hairline, so it reads
        // as the footer's top line rather than as a third docked row with its own divider. Zero height
        // when there is nothing to say (no daemon, API-key account, no snapshot yet) — see [QuotaBar].
        QuotaBar(model)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                    .clickable(onClick = onHelp).padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
                Text(
                    stringResource(Res.string.support_title), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                    .clickable(onClick = onSettings).padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.Settings, stringResource(Res.string.settings_title),
                    tint = Tok.tx2, modifier = Modifier.size(15.dp),
                )
                if (updateAvailable) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Tok.accent))
                Text(
                    "v$APP_VERSION", color = if (updateAvailable) Tok.accent else Tok.muted,
                    fontFamily = Dk.mono, fontSize = 10.sp,
                )
            }
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
                Text(stringResource(Res.string.add_device), color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
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
            if (m.thisMachine) OutlinePill(stringResource(Res.string.this_machine), Tok.muted)
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
