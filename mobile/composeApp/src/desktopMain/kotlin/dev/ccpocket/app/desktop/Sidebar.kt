package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.fleet.AttentionBadge
import dev.ccpocket.app.ui.folderName
import dev.ccpocket.app.ui.modelAlias
import dev.ccpocket.protocol.AgentKind
import kotlin.math.roundToInt

internal fun osIcon(os: DkOs): ImageVector = when (os) {
    DkOs.MAC -> Icons.Rounded.LaptopMac
    DkOs.LINUX -> Icons.Rounded.Terminal
    DkOs.WIN -> Icons.Rounded.LaptopWindows
}

/**
 * The "Sidebar Redesign" board: three stable zones so each vertical region has ONE job.
 * ① header owns machines (remote machines live in its ⌘0 dropdown, not the body) · ② PINNED (⌘1–9)
 * + RUNNING own fast switching · ③ the browse zone below is only ever the current machine, with the
 * open project's sessions docked at the bottom so a 100-item project list can never bury them.
 */
@Composable
fun Sidebar(model: DesktopModel, modifier: Modifier = Modifier) {
    Column(modifier.width(Dk.sidebarWidth).fillMaxHeight().background(Tok.surface)) {
        SwitcherHeader(model)
        PinnedZone(model)
        RunningZone(model)
        BrowseZone(model, Modifier.weight(1f))
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
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Pinned", trailing = { Key("⌘1–9") })
        if (pins.isEmpty()) {
            Text(
                "Pin a session to keep it here — hover any session and hit the pin.",
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
            return@Column
        }
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
    // live state is only knowable for the current machine's loaded session list
    val live = if (remote) null else model.sessions.firstOrNull { it.sessionId == p.sessionId }
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
        if (p.agent == AgentKind.CODEX) AgentTag(AgentKind.CODEX)
        if (pending > 0) AttentionBadge(pending)
        if (hovered || dragging) {
            Icon(
                PinSlashIcon, null, tint = Tok.tx2,
                modifier = Modifier.size(13.dp).clickable { model.unpin(p) },
            )
        }
        Key("⌘${index + 1}")
    }
}

// ── zone 3: running (flat, cross-machine) ───────────────────────────────────────────────────────

@Composable
private fun RunningZone(model: DesktopModel) {
    val running = model.running
    if (running.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Running")
        running.forEach { (m, p) -> RunningRow(m, p) { model.openRunning(m, p) } }
    }
}

/** One cross-machine RUNNING row: accent pulse · project (mono) · which machine, right-aligned muted. */
@Composable
private fun RunningRow(m: DkMachine, p: DkProject, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.accent, 5.dp)
        Text(
            p.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(
            m.computer.name, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── zone 4: browse — the current machine's projects, with its sessions docked at the bottom ─────

@Composable
private fun BrowseZone(model: DesktopModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        SectionLabel("Projects")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // leads the group (after the project list it would sit below a hundred rows) — type any
            // path, the daemon creates a missing leaf folder
            NewSessionRow("New session at path…") { model.openNewSession("~/") }
            model.projects.forEach { ProjectRow(it) { model.openProject(it) } }
        }
        // SESSIONS — bounded, ALWAYS-VISIBLE, its own scroll: a long projects list (119 real ones)
        // must never bury the open project's sessions off-screen
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(Modifier.fillMaxWidth().heightIn(max = 216.dp)) {
            val project = folderName(model.newSessionDir).takeIf { it.isNotBlank() }
            SectionLabel(if (project != null) "Sessions · $project" else "Sessions")
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                NewSessionRow("New session", keyHint = "⌘N") { model.openNewSession() }
                if (model.sessions.isEmpty()) {
                    Text(
                        "Open a project to see its sessions",
                        color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                model.sessions.forEach { s ->
                    SessionRow(model, s, selected = s.sessionId == model.selectedSessionId) { model.selectSession(s) }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(p: DkProject, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
        Text(
            p.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (p.running) PulseDot(Tok.accent, 5.dp)
        else if (p.history) OutlinePill("history", Tok.muted)
    }
}

@Composable
private fun NewSessionRow(label: String, keyHint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(13.dp))
        Text(label, color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        keyHint?.let { Key(it) }
    }
}

@Composable
private fun SessionRow(model: DesktopModel, s: DkSession, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (selected || hovered) Tok.raised else Color.Transparent
    Box(Modifier.fillMaxWidth().height(32.dp).hoverable(src).clickable(onClick = onClick).background(bg)) {
        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).padding(vertical = 4.dp).width(2.dp).fillMaxHeight().background(Tok.accent, RoundedCornerShape(2.dp)))
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
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
            if (s.agent == AgentKind.CODEX) AgentTag(AgentKind.CODEX)
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
                Icon(Icons.Rounded.Close, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
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
