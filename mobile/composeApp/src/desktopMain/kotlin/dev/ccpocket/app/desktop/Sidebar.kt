package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LaptopWindows
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.protocol.AgentKind

private fun osIcon(os: DkOs): ImageVector = when (os) {
    DkOs.MAC -> Icons.Rounded.LaptopMac
    DkOs.LINUX -> Icons.Rounded.Terminal
    DkOs.WIN -> Icons.Rounded.LaptopWindows
}

@Composable
fun Sidebar(model: DesktopModel, modifier: Modifier = Modifier) {
    Column(modifier.width(Dk.sidebarWidth).fillMaxHeight().background(Tok.surface)) {
        ComputerSwitcher(model)
        // PROJECTS — scrolls in its own pane so a long list never buries the sessions below
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            SectionLabel("Projects")
            model.projects.forEach { ProjectRow(it) { model.openProject(it) } }
            NewSessionRow { model.showNewSession = true }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        // SESSIONS — a bounded, always-visible pane at the bottom (its own scroll)
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            SectionLabel("Sessions")
            if (model.sessions.isEmpty()) {
                Text(
                    "Open a project to see its sessions",
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            model.sessions.forEach { s ->
                SessionRow(
                    s,
                    selected = s.sessionId == model.selectedSessionId,
                    onClick = { model.selectSession(s) },
                    onPendingClick = { model.selectSession(s) },
                )
            }
        }
        SettingsFooter()
    }
}

@Composable
private fun ComputerSwitcher(model: DesktopModel) {
    val c = model.activeComputer ?: return
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().hoverFill().clickable { model.switcherOpen = !model.switcherOpen }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(osIcon(c.os), null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
            Text(c.name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (c.online) PulseDot(Tok.ok, 6.dp) else Dot(Tok.muted, 6.dp)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
                modifier = Modifier.size(16.dp).rotate(if (model.switcherOpen) 180f else 0f),
            )
        }
        if (model.switcherOpen) {
            Column(Modifier.fillMaxWidth().background(Tok.base)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                model.computers.forEach { comp ->
                    Row(
                        Modifier.fillMaxWidth().hoverFill().clickable { model.selectComputer(comp) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(osIcon(comp.os), null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(comp.name, color = if (comp.online) Tok.tx else Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp)
                            Text(comp.meta, color = Tok.muted, fontFamily = Dk.mono, fontSize = 9.5.sp)
                        }
                        Dot(if (comp.online) Tok.ok else Tok.muted, 6.dp)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().hoverFill().clickable { model.addComputer() }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(14.dp))
                    Text("Add computer", color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun ProjectRow(p: DkProject, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
        Text(
            p.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (p.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PulseDot(Tok.accent, 6.dp)
                Text("running", color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.sp)
            }
        } else if (p.history) {
            OutlinePill("history", Tok.accent)
        }
    }
}

@Composable
private fun NewSessionRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).hoverFill().clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
        Text("New session", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Key("⌘N")
    }
}

@Composable
private fun SessionRow(s: DkSession, selected: Boolean, onClick: () -> Unit, onPendingClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (selected || hovered) Tok.raised else Color.Transparent
    Box(Modifier.fillMaxWidth().height(34.dp).hoverable(src).clickable(onClick = onClick).background(bg)) {
        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).padding(vertical = 5.dp).width(2.dp).fillMaxHeight().background(Tok.accent, RoundedCornerShape(2.dp)))
        }
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.running) PulseDot(Tok.ok, 6.dp) else Spacer(Modifier.width(6.dp))
            Text(
                s.title,
                color = if (selected) Tok.tx else Tok.tx2,
                fontFamily = Dk.ui, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (s.agent == AgentKind.CODEX) AgentTag(AgentKind.CODEX)
            if (s.pending > 0) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent).clickable(onClick = onPendingClick)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Rounded.PriorityHigh, null, tint = Tok.base, modifier = Modifier.size(10.dp))
                    Text("${s.pending}", color = Tok.base, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Icon(Icons.Rounded.Close, null, tint = Tok.muted, modifier = Modifier.size(13.dp).alpha(if (hovered) 1f else 0f))
        }
    }
}

@Composable
private fun SettingsFooter() {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().hoverFill().clickable { }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Outlined.Settings, null, tint = Tok.tx2, modifier = Modifier.size(16.dp))
            Text("Settings", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            Text("v$APP_VERSION", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
        }
    }
}
