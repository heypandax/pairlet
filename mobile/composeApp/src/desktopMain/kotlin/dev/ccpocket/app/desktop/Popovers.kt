package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.LaptopMac
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentTintBorder
import dev.ccpocket.app.ui.agentTintFill
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode

internal data class DkMode(val label: String, val token: String, val mode: PermissionMode, val dot: Color, val danger: Boolean = false)

internal val CLAUDE_MODES = listOf(
    DkMode("Ask each step", "default", PermissionMode.DEFAULT, Tok.tx2),
    DkMode("Accept edits", "acceptEdits", PermissionMode.ACCEPT_EDITS, Tok.ok),
    DkMode("Plan", "plan", PermissionMode.PLAN, Tok.info),
    DkMode("Full auto", "bypass", PermissionMode.BYPASS_PERMISSIONS, Tok.warn, danger = true),
)

/**
 * Agent + mode picker with an EDITABLE path field seeded by whoever opened it (the current project from
 * ⌘N / the Sessions-pane row, "~/" from the Projects-group row). A path whose leaf folder doesn't exist
 * yet is created by the daemon (one level, under an existing parent) — same contract as mobile's NewPathSheet.
 */
@Composable
fun NewSessionPopover(initialPath: String, onStart: (String, AgentKind, PermissionMode) -> Unit) {
    var agent by remember { mutableStateOf(AgentKind.CLAUDE) }
    var modeIdx by remember { mutableStateOf(0) }
    var path by remember(initialPath) { mutableStateOf(TextFieldValue(initialPath, selection = TextRange(initialPath.length))) }
    val trimmed = path.text.trim()
    // light client check; the daemon is the authority (rejects a non-readable dir with a clear error)
    val looksAbsolute = trimmed.startsWith("/") || trimmed.startsWith("~") || Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed)
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        Text("New session", color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 13.dp))
        Column(Modifier.padding(15.dp)) {
            PopoverLabel("Where")
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Outlined.Folder, null, tint = Tok.muted, modifier = Modifier.size(12.dp))
                BasicTextField(
                    path, { path = it }, singleLine = true,
                    textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp),
                    cursorBrush = SolidColor(Tok.accent),
                    modifier = Modifier.weight(1f),
                )
            }
            PopoverLabel("Agent")
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AgentCard(AgentKind.CLAUDE, agent == AgentKind.CLAUDE, Modifier.weight(1f)) { agent = AgentKind.CLAUDE }
                AgentCard(AgentKind.CODEX, agent == AgentKind.CODEX, Modifier.weight(1f)) { agent = AgentKind.CODEX }
            }
            PopoverLabel("Mode")
            CLAUDE_MODES.forEachIndexed { i, m ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (i == modeIdx) Tok.surface else Color.Transparent)
                        .border(1.dp, if (i == modeIdx) Tok.accent else Tok.hair, RoundedCornerShape(8.dp))
                        .clickable { modeIdx = i }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dot(m.dot, 7.dp)
                    Text(m.label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp)
                    if (m.danger) Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.weight(1f))
                    Text(m.token, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
                }
            }
            Text(
                "Start session", color = Tok.base, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (looksAbsolute) 1f else 0.45f)
                    .clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                    .clickable(enabled = looksAbsolute) { onStart(trimmed, agent, CLAUDE_MODES[modeIdx].mode) }.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PopoverLabel(text: String) {
    Text(text.uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 9.dp))
}

@Composable
internal fun AgentCard(agent: AgentKind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = agentColor(agent)
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.agentTintFill() else Tok.surface)
            .border(1.5.dp, if (selected) c else Tok.hair, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentGlyph(agent, size = 17)
        Text(if (agent == AgentKind.CODEX) "Codex" else "Claude", color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The sidebar collapsed to a 56px icon strip for narrow windows. */
@Composable
fun CollapsedSidebar(modifier: Modifier = Modifier) {
    Column(
        modifier.width(56.dp).fillMaxHeight().background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(0.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.LaptopMac, null, tint = Tok.tx2, modifier = Modifier.size(16.dp))
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp).size(8.dp).clip(RoundedCornerShape(999.dp)).background(Tok.ok).border(2.dp, Tok.surface, RoundedCornerShape(999.dp)))
        }
        Box(Modifier.padding(vertical = 6.dp).width(24.dp).height(1.dp).background(Tok.hair))
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(17.dp)) }
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Tok.raised), contentAlignment = Alignment.Center) {
            AgentGlyph(AgentKind.CLAUDE, size = 17)
            Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(15.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent), contentAlignment = Alignment.Center) {
                Text("1", color = Tok.base, fontFamily = Dk.mono, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Settings, null, tint = Tok.tx2, modifier = Modifier.size(17.dp)) }
    }
}
