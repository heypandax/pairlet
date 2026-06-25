package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentTintFill
import dev.ccpocket.app.ui.agentTintBorder
import dev.ccpocket.protocol.AgentKind

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    GENERAL("General", Icons.Outlined.Tune),
    COMPUTERS("Computers", Icons.Rounded.Devices),
    SHORTCUTS("Shortcuts", Icons.Rounded.Keyboard),
    ABOUT("About", Icons.Outlined.Info),
}

/**
 * The desktop preferences window — a left rail of sections + a content pane, as an in-shell modal (the app is
 * one undecorated window, so this matches the palette / focused-modal idiom rather than spawning a 2nd OS
 * window). Wired live: General sets the repo defaults, Computers renames/revokes paired daemons.
 */
@Composable
fun SettingsModal(model: DesktopModel, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(SettingsTab.GENERAL) }
    Column(
        Modifier.width(700.dp).height(500.dp).shadow(30.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Close, "Close", tint = Tok.tx2, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(2.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(176.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsTab.entries.forEach { t -> RailItem(t, selected = t == tab) { tab = t } }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
            Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) {
                when (tab) {
                    SettingsTab.GENERAL -> GeneralPane(model)
                    SettingsTab.COMPUTERS -> ComputersPane(model)
                    SettingsTab.SHORTCUTS -> ShortcutsPane()
                    SettingsTab.ABOUT -> AboutPane(model)
                }
            }
        }
    }
}

@Composable
private fun RailItem(tab: SettingsTab, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectableRow(selected).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(tab.icon, null, tint = if (selected) Tok.accent else Tok.tx2, modifier = Modifier.size(16.dp))
        Text(tab.label, color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Group(title: String, sub: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(11.dp))
        content()
    }
}

@Composable
private fun GeneralPane(model: DesktopModel) {
    Column {
        Group("Default agent", "Which backend new sessions start with.") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentCardRow(AgentKind.CLAUDE, model.defaultAgent == AgentKind.CLAUDE, Modifier.weight(1f)) { model.defaultAgent = AgentKind.CLAUDE }
                AgentCardRow(AgentKind.CODEX, model.defaultAgent == AgentKind.CODEX, Modifier.weight(1f)) { model.defaultAgent = AgentKind.CODEX }
            }
        }
        Group("Default permission mode", "How much a new session may do before it asks.") {
            CLAUDE_MODES.forEach { m -> ModeRow(m, selected = m.mode == model.defaultMode) { model.defaultMode = m.mode } }
        }
    }
}

/** Horizontal agent card (glyph + name in a row) — the Settings variant; the new-session popover uses a stacked one. */
@Composable
private fun AgentCardRow(agent: AgentKind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = agentColor(agent)
    Row(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.agentTintFill() else Tok.surface)
            .border(1.5.dp, if (selected) c else Tok.hair, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        AgentGlyph(agent, size = 18)
        Text(if (agent == AgentKind.CODEX) "Codex" else "Claude", color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModeRow(m: DkMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Tok.surface else Color.Transparent)
            .border(1.5.dp, if (selected) Tok.accent else Tok.hair, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(m.dot, 8.dp)
        Text(m.label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
        if (m.danger) Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
        Spacer(Modifier.weight(1f))
        Text(m.token, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
    }
}

@Composable
private fun ComputersPane(model: DesktopModel) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    Column {
        Text("Paired computers", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        if (model.computers.isEmpty()) {
            Text("No paired computers yet.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
        }
        model.computers.forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(osIcon(c.os), null, tint = Tok.tx2, modifier = Modifier.size(16.dp))
                if (editingId == c.accountId) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
                    ) {
                        if (draft.isEmpty()) Text(c.name, color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
                        BasicTextField(draft, { draft = it }, singleLine = true, textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp), cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth())
                    }
                    TextBtn("Save", Tok.accent) { model.renameComputer(c, draft.ifBlank { null }); editingId = null }
                    TextBtn("Cancel", Tok.muted) { editingId = null }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.accountId, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.online) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PulseDot(Tok.ok, 6.dp); Text("online", color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.sp)
                    } else Text("offline", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
                    TextBtn("Rename", Tok.tx2) { editingId = c.accountId; draft = "" }
                    TextBtn("Revoke", Tok.danger) { model.revokeComputer(c) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).dashedBorder(Tok.hair, 11.dp).hoverFill(RoundedCornerShape(11.dp))
                .clickable { model.addComputer() }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
            Text("Add computer", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TextBtn(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun ShortcutsPane() {
    val rows = listOf(
        "Command palette" to listOf("⌘", "K"),
        "New session" to listOf("⌘", "N"),
        "Send message" to listOf("⏎"),
        "New line" to listOf("⇧", "⏎"),
        "Approve permission" to listOf("⌘", "⏎"),
        "Open settings" to listOf("⌘", ","),
        "Close / dismiss" to listOf("esc"),
    )
    Column {
        Text("Keyboard shortcuts", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.first, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.second.forEach { Key(it) } }
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        }
    }
}

@Composable
private fun AboutPane(model: DesktopModel) {
    Column {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { AgentGlyph(AgentKind.CLAUDE, size = 20) }
            Text("cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Desktop edition — one host driving Claude Code / Codex on another, over an end-to-end encrypted link.",
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.width(380.dp).padding(bottom = 18.dp),
        )
        val info = listOf("Version" to model.appVersion, "Relay" to model.relayUrl.ifBlank { "—" }, "License" to "MIT")
        info.forEachIndexed { i, row ->
            InfoRow(row.first, row.second)
            if (i < info.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
