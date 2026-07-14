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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.app.ui.CLAUDE_MODEL_OPTIONS
import dev.ccpocket.app.ui.CODEX_MODEL_OPTIONS
import dev.ccpocket.app.ui.EFFORT_OPTIONS
import dev.ccpocket.app.ui.gatewayHostLabel
import dev.ccpocket.app.ui.recommendedGatewayPresets
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
fun NewSessionPopover(
    initialPath: String,
    defaultAgent: AgentKind = AgentKind.CLAUDE,
    defaultMode: PermissionMode = PermissionMode.DEFAULT,
    onStart: (String, AgentKind, PermissionMode) -> Unit,
) {
    var agent by remember { mutableStateOf(defaultAgent) }
    var modeIdx by remember { mutableStateOf(CLAUDE_MODES.indexOfFirst { it.mode == defaultMode }.coerceAtLeast(0)) }
    var path by remember(initialPath) { mutableStateOf(TextFieldValue(initialPath, selection = TextRange(initialPath.length))) }
    val trimmed = path.text.trim()
    // light client check; the daemon is the authority (rejects a non-readable dir with a clear error)
    val looksAbsolute = trimmed.startsWith("/") || trimmed.startsWith("~") || Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed)
    val pathFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { pathFocus.requestFocus() }
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            // Enter anywhere in the popover = the Start button (the path field holds focus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && looksAbsolute) {
                    onStart(trimmed, agent, CLAUDE_MODES[modeIdx].mode); true
                } else false
            },
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
                    modifier = Modifier.weight(1f).focusRequester(pathFocus),
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

// ── quick actions (the chat-header ⋯): model / effort / mode / terminal / compact / clear ──
// Mirrors mobile's QuickActionsSheet so the two shells stay in sync (mobile moved the mode
// switch here off the top bar); drives the same repo verbs via DesktopModel.
private enum class QaPage { MAIN, MODEL, EFFORT, MODE }

@Composable
fun QuickActionsPopover(model: DesktopModel, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(QaPage.MAIN) }
    var clearArmed by remember { mutableStateOf(false) }
    Column(
        Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(14.dp)).padding(15.dp),
    ) {
        when (page) {
            QaPage.MAIN -> {
                PopoverLabel("Quick actions")
                QaRow("Model", value = model.chatModel.ifBlank { "default" }, chevron = true) { page = QaPage.MODEL }
                QaRow("Effort", value = model.chatEffort ?: "default", chevron = true) { page = QaPage.EFFORT }
                QaRow("Mode", value = CLAUDE_MODES.first { it.mode == model.chatMode }.token, chevron = true) { page = QaPage.MODE }
                // canOpen() stats the filesystem — key it on the workdir so it isn't re-run every
                // recomposition (this popover recomposes on every page/arm toggle); same as ChatSubHeader
                val canOpenTerminal = remember(model.chatWorkdir) { TerminalLauncher.canOpen(model.chatWorkdir) }
                if (canOpenTerminal) {
                    QaRow("Open terminal") { TerminalLauncher.open(model.terminalApp, model.chatWorkdir); onDismiss() }
                }
                QaRow("Compact context") { model.compactConversation(); onDismiss() }
                QaRow(
                    if (clearArmed) "Clear chat — tap again" else "Clear chat", danger = true,
                ) { if (clearArmed) { model.clearConversation(); onDismiss() } else clearArmed = true }
            }
            QaPage.MODEL -> {
                QaBack("Model") { page = QaPage.MAIN }
                val options = if (model.chatAgent == AgentKind.CODEX) CODEX_MODEL_OPTIONS.map { it to it } else CLAUDE_MODEL_OPTIONS
                fun isActive(pick: String) = model.chatModelId.equals(pick, true) || model.chatModel.equals(pick, true)
                // gateway model presets (issue #139): mirrors mobile's ModelPicker off the same shared
                // table. Gateway reported by the daemon → the section LEADS (those users pick vendor
                // ids, not Claude aliases); official endpoint → it waits behind a collapsed toggle.
                val gatewayUrl = if (model.chatAgent == AgentKind.CODEX) null else model.gatewayBaseUrl
                @Composable
                fun gatewayRows() = recommendedGatewayPresets(gatewayUrl).forEach { p ->
                    QaOption(p.vendor, isActive(p.id), token = p.id) { model.switchModel(p.id); onDismiss() }
                }
                if (gatewayUrl != null) {
                    PopoverLabel("Gateway · ${gatewayHostLabel(gatewayUrl) ?: "?"}")
                    gatewayRows()
                    PopoverLabel("Claude")
                }
                options.forEach { (label, pick) ->
                    QaOption(label, isActive(pick)) { model.switchModel(pick); onDismiss() }
                }
                if (gatewayUrl == null && model.chatAgent != AgentKind.CODEX) {
                    var showGateway by remember { mutableStateOf(false) }
                    QaRow("Gateway presets", chevron = !showGateway) { showGateway = !showGateway }
                    if (showGateway) gatewayRows()
                }
                // custom id (issue #54): third-party gateways route ids the preset list can't know;
                // `--model` takes any string, so pass it through. Enter submits. Prefilled when the
                // session already runs a non-preset id.
                val presetActive = options.any { (_, pick) -> isActive(pick) }
                var custom by remember {
                    mutableStateOf(if (!presetActive) model.chatModelId.ifBlank { model.chatModel } else "")
                }
                PopoverLabel("Custom")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    BasicTextField(
                        custom, { custom = it }, singleLine = true,
                        textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp),
                        cursorBrush = SolidColor(Tok.accent),
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && custom.isNotBlank()) {
                                model.switchModel(custom.trim()); onDismiss(); true
                            } else false
                        },
                    )
                    if (custom.isNotBlank()) Text(
                        "→", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { model.switchModel(custom.trim()); onDismiss() }.padding(horizontal = 4.dp),
                    )
                }
            }
            QaPage.EFFORT -> {
                QaBack("Effort") { page = QaPage.MAIN }
                EFFORT_OPTIONS.forEach { opt ->
                    QaOption(opt, opt.equals(model.chatEffort, true)) { model.switchEffort(opt); onDismiss() }
                }
            }
            QaPage.MODE -> {
                QaBack("Mode") { page = QaPage.MAIN }
                CLAUDE_MODES.forEach { m ->
                    QaOption(m.label, m.mode == model.chatMode, dot = m.dot, danger = m.danger, token = m.token) {
                        model.switchMode(m.mode); onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun QaRow(label: String, value: String? = null, danger: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = if (danger) Tok.danger else Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp) }
        if (chevron) Text("›", color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
    }
}

@Composable
private fun QaBack(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 9.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("‹", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 15.sp)
        Text(title.uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun QaOption(label: String, selected: Boolean, dot: Color? = null, danger: Boolean = false, token: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Tok.surface else Color.Transparent)
            .border(1.dp, if (selected) Tok.accent else Tok.hair, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dot?.let { Dot(it, 7.dp) }
        Text(label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp)
        if (danger) Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
        Spacer(Modifier.weight(1f))
        token?.let { Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp) }
        if (selected && token == null) Text("✓", color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.sp)
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
