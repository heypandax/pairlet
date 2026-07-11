package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.ui.share.DEFAULT_TIER
import dev.ccpocket.app.ui.share.SHARE_TIERS
import dev.ccpocket.app.ui.share.ShareExpiryOption
import dev.ccpocket.app.ui.share.ShareStatus
import dev.ccpocket.app.ui.share.countdown
import dev.ccpocket.app.ui.share.expiryOptionLabel
import dev.ccpocket.app.ui.share.groupShares
import dev.ccpocket.app.ui.share.shareStatus
import dev.ccpocket.app.ui.share.tierLabel
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.CLAUDE_MODEL_OPTIONS
import kotlinx.coroutines.delay
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentTintFill
import dev.ccpocket.app.ui.agentTintBorder
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.PresetEnv
import dev.ccpocket.protocol.PresetSummary
import dev.ccpocket.protocol.PresetsState

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    GENERAL("General", Icons.Outlined.Tune),
    ACCOUNT("Account", Icons.Rounded.Person),
    COMPUTERS("Computers", Icons.Rounded.Devices),
    SHARES("Shared", Icons.Rounded.Share),
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
                    SettingsTab.ACCOUNT -> AccountPane(model)
                    SettingsTab.COMPUTERS -> ComputersPane(model)
                    SettingsTab.SHARES -> SharesPane(model)
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
        Group("Appearance", "Light or dark theme for this app.") {
            AppearanceRow(model)
        }
        Group("Default agent", "Which backend new sessions start with.") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentCardRow(AgentKind.CLAUDE, model.defaultAgent == AgentKind.CLAUDE, Modifier.weight(1f)) { model.defaultAgent = AgentKind.CLAUDE }
                AgentCardRow(AgentKind.CODEX, model.defaultAgent == AgentKind.CODEX, Modifier.weight(1f)) { model.defaultAgent = AgentKind.CODEX }
            }
        }
        Group("Default model", "Which model new Claude sessions start on (Codex sessions keep their own).") {
            // null = follow the CLI's own default; the rest are Claude aliases shared with the ⋯ picker
            PrefRow("Default", "cli default", selected = model.defaultModel == null) { model.defaultModel = null }
            CLAUDE_MODEL_OPTIONS.forEach { (label, alias) ->
                PrefRow(label, alias, selected = model.defaultModel == alias) { model.defaultModel = alias }
            }
        }
        Group("Context window", "The usage statusline's 100% mark. Set this when a custom model's real window isn't 200K — the CLI can't report it.") {
            ContextWindowRows(model)
        }
        Group("Default permission mode", "How much a new session may do before it asks.") {
            CLAUDE_MODES.forEach { m -> ModeRow(m, selected = m.mode == model.defaultMode) { model.defaultMode = m.mode } }
        }
        // only terminals actually present on this machine are offered (issue #44)
        Group("Terminal", "Which app the chat header's >_ button opens at the session's folder.") {
            TerminalApp.entries.filter(TerminalLauncher::installed).forEach { t ->
                TerminalRow(t, selected = t == model.terminalApp) { model.terminalApp = t }
            }
        }
        // daemon-side switch: silence phone alerts while working at the computer. Null = old daemon.
        LaunchedEffect(Unit) { model.refreshPushPrefs() }
        Group("Notifications", "Turn-complete alerts pushed to your phone by this computer.") {
            when (val on = model.phonePush) {
                null -> Text(
                    "Phone alerts need the computer's daemon updated first.",
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                )
                else -> ToggleRow("Notify my phone when a turn finishes", on) { model.setPhonePush(!on) }
            }
        }
    }
}

// System / Light / Dark segmented control (issue #63) — a three-way toggle mirroring the mobile Appearance
// picker. Wired to the shared repo via model.themeMode, so a pick persists and the window root re-themes live.
@Composable
private fun AppearanceRow(model: DesktopModel) {
    val modes = listOf(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        modes.forEach { (mode, label) ->
            val sel = model.themeMode == mode
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) Tok.accent else Color.Transparent)
                    .clickable { model.themeMode = mode }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (sel) Tok.base else Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
            .background(if (on) Tok.surface else Color.Transparent)
            .border(1.5.dp, if (on) Tok.accent else Tok.hair, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(if (on) Tok.ok else Tok.muted, 8.dp)
        Text(label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(if (on) "on" else "off", color = if (on) Tok.ok else Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
    }
}

@Composable
private fun TerminalRow(t: TerminalApp, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Tok.surface else Color.Transparent)
            .border(1.5.dp, if (selected) Tok.accent else Tok.hair, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(">_", color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp)
        Text(t.label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(t.id, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
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

// a selectable pill row: label left, a monospace hint/alias right. Shared by the default-model and
// context-window pickers (issue #60 folded two byte-identical copies into one).
@Composable
private fun PrefRow(label: String, trailing: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Tok.surface else Color.Transparent)
            .border(1.5.dp, if (selected) Tok.accent else Tok.hair, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
    }
}

// context-window denominator: the two standard presets + a free-form token count for a custom model whose
// real window is neither (the case #60 exists for). null = follow the model-derived / daemon-reported window.
@Composable
private fun ContextWindowRows(model: DesktopModel) {
    val current = model.contextWindowOverride
    val custom = current != null && current != DEFAULT_CONTEXT_WINDOW && current != LARGE_CONTEXT_WINDOW
    PrefRow("Default", "follow model", selected = current == null) { model.contextWindowOverride = null }
    PrefRow("200K", "200,000", selected = current == DEFAULT_CONTEXT_WINDOW) { model.contextWindowOverride = DEFAULT_CONTEXT_WINDOW }
    PrefRow("1M", "1,000,000", selected = current == LARGE_CONTEXT_WINDOW) { model.contextWindowOverride = LARGE_CONTEXT_WINDOW }
    var draft by remember { mutableStateOf(if (custom) current.toString() else "") }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
            .background(if (custom) Tok.surface else Color.Transparent)
            .border(1.5.dp, if (custom) Tok.accent else Tok.hair, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Custom", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.width(108.dp).clip(RoundedCornerShape(7.dp)).background(Tok.base)
                .border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            if (draft.isEmpty()) Text("tokens", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
            BasicTextField(
                draft,
                { new -> draft = new.filter(Char::isDigit).take(9); model.contextWindowOverride = draft.toLongOrNull()?.takeIf { it > 0 } },
                singleLine = true,
                textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp),
                cursorBrush = SolidColor(Tok.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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

/**
 * The active computer's Claude CLI auth (OAuth account switch, issue #73 lineage) PLUS API presets
 * (issue #113): named env overrides (base URL / token / model routing) a third-party API user switches
 * between — the API-key counterpart of the OAuth account switch, sharing one pane and one refusal
 * treatment. All state is the daemon's latest AuthState/PresetsState push; tokens ride up write-only
 * and only ever come back masked, so nothing here can even render a plaintext secret.
 */
@Composable
private fun AccountPane(model: DesktopModel) {
    // an old daemon silently drops pocket/auth.fetch AND pocket/presets.fetch — flip to explicit
    // "update it" lines instead of loading forever (and never offer the token-bearing preset form)
    var timedOut by remember { mutableStateOf(false) }
    // key on connGen (bumps on every (re)attach), not Unit: a pane left open across a daemon restart/reconnect
    // must re-fetch — otherwise it strands the pre-restart account or a transient "claude CLI not found" until
    // a manual close/reopen re-runs this. Reset the 4s "update the daemon" grace at the start of each run.
    LaunchedEffect(model.connGen) { timedOut = false; model.refreshAuth(); model.refreshPresets(); delay(4_000); timedOut = true }
    val s = model.authState
    val ps = model.presetsState
    val activePreset = ps?.activeId?.let { id -> ps.presets.firstOrNull { it.id == id } }
    var confirmSwitch by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    // One preset op in flight at a time: drives the tapped row's spinner (design 3a) and the local
    // reach timeout (3c's inline error). lastOp survives the refusal reply — the blockers card needs
    // to know what "Stop" / "Stop all & switch" retries; inFlight clears on ANY reply.
    var inFlight by remember { mutableStateOf<PresetOp?>(null) }
    var lastOp by remember { mutableStateOf<PresetOp?>(null) }
    var reachError by remember { mutableStateOf(false) }
    // keyed on the reply REV, not the state value: an equal-content reply (no-change save) must
    // still settle the spinner — success and refusal both answer with a state
    LaunchedEffect(model.presetsRev) { if (model.presetsRev > 0) inFlight = null }
    LaunchedEffect(inFlight) {
        if (inFlight == null) return@LaunchedEffect
        delay(8_000)
        if (inFlight != null) { inFlight = null; reachError = true }
    }
    val runOp: (PresetOp) -> Unit = { op ->
        reachError = false; lastOp = op; inFlight = op
        when (op) {
            is PresetOp.Activate -> model.activatePreset(op.id)
            is PresetOp.Delete -> model.deletePreset(op.id)
        }
    }
    val stopOne: (String) -> Unit = { convoId ->
        lastOp?.let { op ->
            reachError = false; inFlight = op
            when (op) {
                is PresetOp.Activate -> model.stopPresetBlocker(convoId, op.id)
                is PresetOp.Delete -> model.stopPresetDeleteBlocker(convoId, op.id)
            }
        }
    }
    val runForce: () -> Unit = {
        lastOp?.let { op ->
            reachError = false; inFlight = op
            when (op) {
                is PresetOp.Activate -> model.activatePreset(op.id, force = true)
                is PresetOp.Delete -> model.deletePreset(op.id, force = true)
            }
        }
    }

    // form mode replaces the pane content in place (design 2a — same undecorated window, ‹ Presets back)
    var editing by remember { mutableStateOf<PresetFormTarget?>(null) }
    editing?.let { target ->
        PresetForm(
            model, ps, target,
            onDelete = { id -> runOp(PresetOp.Delete(id)); editing = null },
            onClose = { editing = null },
        )
        return
    }

    Column {
        Group("Authentication", "How this computer's Claude CLI signs in.") {
            when {
                s == null -> Text(
                    when {
                        !model.connected -> "Connect a computer to see its account."
                        timedOut -> "No reply — the daemon on this computer predates account management. Update it to switch accounts from here."
                        else -> "Loading account…"
                    },
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, lineHeight = 19.sp,
                )

                s.loginPending -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text("Finish signing in", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "A browser window opened on the computer. Authorize there, copy the code, and paste it below.",
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    s.loginUrl?.let { url ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                            Text(
                                url, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            TextBtn("Open here", Tok.accent) { runCatching { uriHandler.openUri(url) } }
                            TextBtn("Copy", Tok.tx2) { clipboard.setText(AnnotatedString(url)) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(Tok.base)
                                .border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            if (code.isEmpty()) Text("Paste authorization code", color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
                            BasicTextField(
                                code, { code = it }, singleLine = true,
                                textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp),
                                cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextBtn("Submit", Tok.accent) { if (code.isNotBlank()) { model.submitAuthCode(code); code = "" } }
                        TextBtn("Cancel", Tok.muted) { code = ""; model.cancelAuthLogin() }
                    }
                }

                // a preset drives new sessions: show ITS truth (masked) as the authentication card —
                // design 1a/3b. The daemon's own login/env still exists underneath; Deactivate returns to it.
                activePreset != null -> PresetAuthCard(activePreset) { runOp(PresetOp.Activate(null)) }

                // an API key / forwarding endpoint authenticates via env var, not an OAuth login: the CLI
                // still reports loggedIn + authMethod "claude.ai", but email/plan are null and `claude auth
                // login/logout` can't override the key (#73). Presets below are the actionable path now.
                s.loggedIn && !s.apiKeySource.isNullOrBlank() -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    val keySource = s.apiKeySource.orEmpty() // non-blank per the branch guard (a protocol prop can't smart-cast cross-module)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
                        Text("API key", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        MonoPill("env · $keySource", accent = false)
                    }
                    Hairline(vertical = 13.dp)
                    Text(
                        "Set in the computer's environment ($keySource / ANTHROPIC_BASE_URL), not a Claude login. " +
                            if (ps != null) "Activate a preset below to run new sessions on a saved endpoint instead, or edit the variables on the computer."
                            else "To change the key or endpoint, edit the variables on the computer and reconnect.",
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }

                s.loggedIn -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Person, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.email ?: "Signed in", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            s.orgName?.let { Text(it, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        s.subscriptionType?.let { plan ->
                            Text(
                                plan.uppercase(), color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (confirmSwitch) {
                        Text(
                            "Switching signs this account out first. Idle sessions on that computer are closed (resume them anytime); a session still working on a task blocks the switch.",
                            color = Tok.warn, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextBtn("Continue", Tok.accent) { confirmSwitch = false; model.switchAccount() }
                            TextBtn("Cancel", Tok.muted) { confirmSwitch = false }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextBtn("Switch account…", Tok.accent) { confirmSwitch = true }
                        TextBtn("Log out", Tok.danger) { model.logoutAccount() }
                    }
                }

                // design 1b (unconfigured) merged with the OAuth entry point: no login, no env key,
                // no active preset — offer both ways in (sign in, or save a preset below)
                else -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
                        Text("No account or API key set on this computer", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        MonoPill("unconfigured", accent = false)
                    }
                    Hairline(vertical = 13.dp)
                    Text(
                        "This computer's Claude CLI has no login and no base URL or auth token in its environment. " +
                            if (ps != null) "Sign in with Claude, or save a preset below to point new sessions at an endpoint."
                            else "Sign in with Claude to get started.",
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row { TextBtn("Sign in…", Tok.accent) { model.switchAccount() } }
                }
            }
            // mid-task refusal with structure: name each blocker and offer to stop it — per row or all at
            // once — instead of the dead-end string (which stays as the fallback for pre-blockers daemons)
            if (s?.blockers?.isNotEmpty() == true) WorkingBlockersCard(
                s.blockers,
                onStopOne = { model.stopAuthBlocker(it) },
                onForce = { model.switchAccount(force = true) },
            )
            else s?.error?.let { ErrorRow(it) }
        }

        // design 1a-1d: the presets group lives on the SAME pane for every auth flavor — OAuth users
        // see it too (1c), which is exactly how a subscription user discovers third-party endpoints
        Group("API presets", "Saved endpoints you can switch between. New sessions use the active one.") {
            PresetsSection(
                ps = ps,
                timedOut = timedOut,
                connected = model.connected,
                oauthActive = activePreset == null && s?.loggedIn == true && s.apiKeySource.isNullOrBlank(),
                computerName = model.activeComputer?.name,
                inFlight = inFlight,
                reachError = reachError,
                onActivate = { runOp(PresetOp.Activate(it)) },
                onEdit = { editing = PresetFormTarget.Edit(it) },
                onDelete = { runOp(PresetOp.Delete(it)) },
                onNew = { editing = PresetFormTarget.New },
                onStopOne = stopOne,
                onForce = runForce,
            )
        }
    }
}

// ── API presets (issue #113) ─────────────────────────────────────────────

/** One preset op awaiting its PresetsState reply — what the spinner shows and the blockers card retries. */
private sealed interface PresetOp {
    data class Activate(val id: String?) : PresetOp // null = deactivate (back to the computer's own auth)
    data class Delete(val id: String) : PresetOp
}

private sealed interface PresetFormTarget {
    data object New : PresetFormTarget
    data class Edit(val preset: PresetSummary) : PresetFormTarget
}

/** The Authentication card while a preset drives new sessions (design 1a/3b): its truth, masked. */
@Composable
private fun PresetAuthCard(p: PresetSummary, onDeactivate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
            Text("API key", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            MonoPill("preset · ${p.name}", accent = true)
        }
        Hairline(vertical = 13.dp)
        CapLabel("Base URL")
        Text(p.baseUrl, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        CapLabel("Token", topPad = 12.dp)
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Text(p.tokenVar, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp)
            Text(" · ${p.tokenMask}", color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp)
        }
        if (p.model != null || p.smallFastModel != null) {
            CapLabel("Model route", topPad = 12.dp)
            val route = listOfNotNull(
                p.model?.let { "model → $it" },
                p.smallFastModel?.let { "fast → $it" },
            ).joinToString(" · ")
            Text(route, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        // the way back to the computer's own login/env — same switch semantics as activating (blockers may refuse)
        Row { TextBtn("Deactivate", Tok.muted, onDeactivate) }
    }
}

/** The "API presets" group body: list + new-row + settle note + refusal/error surfaces (design 1a-3d). */
@Composable
private fun PresetsSection(
    ps: PresetsState?,
    timedOut: Boolean,
    connected: Boolean,
    oauthActive: Boolean,
    computerName: String?,
    inFlight: PresetOp?,
    reachError: Boolean,
    onActivate: (String?) -> Unit,
    onEdit: (PresetSummary) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onStopOne: (String) -> Unit,
    onForce: () -> Unit,
) {
    if (ps == null) {
        Text(
            when {
                !connected -> "Connect a computer to manage API presets."
                timedOut -> "Presets need the computer's daemon updated first."
                else -> "Loading presets…"
            },
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, lineHeight = 19.sp,
        )
        return
    }
    Column {
        if (ps.presets.isEmpty()) {
            // 1b empty vs 1c OAuth-coexist: same slot, different explanation
            InfoBox(
                withIcon = oauthActive,
                text = if (oauthActive) {
                    "Presets are for third-party API endpoints. Activating one runs new sessions on that key instead of your Claude login."
                } else {
                    "No presets yet. Save an endpoint to switch base URLs and keys without editing the computer's environment."
                },
            )
        } else {
            val activating = (inFlight as? PresetOp.Activate)?.id
            ps.presets.forEach { p ->
                PresetRow(
                    p = p,
                    active = p.id == ps.activeId,
                    // 3a: while another row activates, the old active row's accent fades out
                    dimmedActive = inFlight != null && p.id == ps.activeId,
                    activating = activating == p.id,
                    onActivate = { if (inFlight == null) onActivate(p.id) },
                    onEdit = { onEdit(p) },
                    onDelete = { onDelete(p.id) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).dashedBorder(Tok.hair, 11.dp).hoverFill(RoundedCornerShape(11.dp))
                .clickable(onClick = onNew).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text("New preset", color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        if (ps.activeId != null) Text(
            "New sessions on ${computerName ?: "this computer"} use this preset. Sessions already open keep the endpoint they started with.",
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp),
        )
        if (ps.blockers.isNotEmpty()) WorkingBlockersCard(ps.blockers, onStopOne = onStopOne, onForce = onForce)
        else if (reachError) ErrorRow("Couldn't reach the computer — try again.")
        else ps.error?.let { ErrorRow(it) }
        // the secrets red line, stated where the secrets are handled
        Text(
            "Tokens are stored on the computer and never sent back to this app.",
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** One preset row: dot + name + host; trailing = active tag / Activating… spinner / hover Edit·Delete. */
@Composable
private fun PresetRow(
    p: PresetSummary,
    active: Boolean,
    dimmedActive: Boolean,
    activating: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val border = when {
        activating -> Tok.hair
        active -> Tok.accent.copy(alpha = if (dimmedActive) 0.22f else 0.55f)
        else -> Tok.hair
    }
    val fill = when {
        activating -> Tok.accent.copy(alpha = 0.04f)
        active -> Tok.accent.copy(alpha = if (dimmedActive) 0.03f else 0.09f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(9.dp)).background(fill)
            .border(1.5.dp, border, RoundedCornerShape(9.dp)).hoverable(src)
            .clickable(enabled = !active && !activating, onClick = onActivate)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Dot(if (active) Tok.accent.copy(alpha = if (dimmedActive) 0.35f else 1f) else Tok.muted, 8.dp)
        Text(
            p.name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp,
            fontWeight = if (active && !dimmedActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            presetHost(p.baseUrl), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        when {
            activating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CircularProgressIndicator(Modifier.size(11.dp), color = Tok.accent, strokeWidth = 1.5.dp)
                Text("Activating…", color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.5.sp)
            }
            active -> Text(
                "active", color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.accent.copy(alpha = 0.13f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
            hovered -> Row {
                TextBtn("Edit", Tok.tx2, onEdit)
                TextBtn("Delete", Tok.danger, onDelete)
            }
        }
    }
}

/**
 * Create/edit form (design 2a-2c), replacing the pane content in place. The token field is WRITE-ONLY:
 * masked while typing (eye toggle reveals), never prefilled on edit — "•••• stored — leave blank to
 * keep" is the placeholder, and leaving it blank keeps the daemon-stored secret. Save stays disabled
 * while locally invalid (2b); a daemon-side refusal comes back inline via PresetsState.fieldError.
 */
@Composable
private fun PresetForm(
    model: DesktopModel,
    ps: PresetsState?,
    target: PresetFormTarget,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    val initial = (target as? PresetFormTarget.Edit)?.preset
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var tokenVar by remember { mutableStateOf(initial?.tokenVar ?: PresetEnv.AUTH_TOKEN) }
    var token by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var routingOpen by remember { mutableStateOf(initial?.model != null || initial?.smallFastModel != null) }
    var routeModel by remember { mutableStateOf(initial?.model ?: "") }
    var routeFast by remember { mutableStateOf(initial?.smallFastModel ?: "") }
    var nameTouched by remember { mutableStateOf(false) }
    var urlTouched by remember { mutableStateOf(false) }
    var tokenTouched by remember { mutableStateOf(false) }
    var awaitingSave by remember { mutableStateOf(false) }
    var daemonError by remember { mutableStateOf<Pair<String?, String>?>(null) } // fieldError → message

    // the save reply (keyed on the reply rev — an equal-content reply must still settle the form):
    // success closes; a refusal surfaces inline on the named field
    LaunchedEffect(model.presetsRev) {
        if (!awaitingSave || ps == null) return@LaunchedEffect
        awaitingSave = false
        val err = ps.error
        if (err == null) onClose() else daemonError = ps.fieldError to err
    }

    val others = ps?.presets.orEmpty().filter { it.id != initial?.id }
    val nameError = when {
        name.isNotBlank() && others.any { it.name.equals(name.trim(), ignoreCase = true) } -> "A preset named '${name.trim()}' already exists."
        nameTouched && name.isBlank() -> "Name is required."
        else -> daemonError?.takeIf { it.first == "name" }?.second
    }
    val urlError = when {
        baseUrl.isNotBlank() && !isHttpUrl(baseUrl.trim()) -> "Enter a valid http(s) URL."
        urlTouched && baseUrl.isBlank() -> "Enter a valid http(s) URL."
        else -> daemonError?.takeIf { it.first == "baseUrl" }?.second
    }
    val tokenError =
        if (initial == null && tokenTouched && token.isBlank()) "Paste the API key or token."
        else daemonError?.takeIf { it.first == "token" }?.second
    val valid = name.isNotBlank() && nameError == null && isHttpUrl(baseUrl.trim()) && (initial != null || token.isNotBlank())

    Column {
        Text(
            "‹ Presets", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).hoverFill(RoundedCornerShape(6.dp)).clickable(onClick = onClose).padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            if (initial == null) "New preset" else "Edit preset",
            color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )

        Text("Name", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        FormInput(name, { name = it; nameTouched = true; daemonError = null }, mono = false, error = nameError != null, placeholder = "Work proxy")
        FieldError(nameError)

        Text("Base URL", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(6.dp))
        FormInput(baseUrl, { baseUrl = it; urlTouched = true; daemonError = null }, mono = true, error = urlError != null, placeholder = "https://api.example-proxy.com/v1")
        FieldError(urlError)
        if (urlError == null) HelperLine(PresetEnv.BASE_URL, "— where the CLI sends requests.")

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
            Text("Auth token", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            // which env var carries the secret — AUTH_TOKEN (forwarding proxies) vs API_KEY (direct keys)
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(2.dp),
            ) {
                SegChip("AUTH_TOKEN", tokenVar == PresetEnv.AUTH_TOKEN) { tokenVar = PresetEnv.AUTH_TOKEN }
                SegChip("API_KEY", tokenVar == PresetEnv.API_KEY) { tokenVar = PresetEnv.API_KEY }
            }
        }
        Spacer(Modifier.height(6.dp))
        FormInput(
            token, { token = it; tokenTouched = true; daemonError = null }, mono = true, error = tokenError != null,
            placeholder = if (initial == null) "Paste token" else "•••• stored — leave blank to keep",
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation('•'),
            accent = reveal,
            trailing = {
                Icon(
                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide token" else "Reveal token",
                    tint = if (reveal) Tok.accent else Tok.tx2,
                    modifier = Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).clickable { reveal = !reveal },
                )
            },
        )
        FieldError(tokenError)
        if (tokenError == null) HelperLine(tokenVar, "— stored on the computer, never shown here again.")

        // model routing (optional): the two env vars the CLI reads for model steering
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
                .clickable { routingOpen = !routingOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (routingOpen) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, tint = Tok.muted, modifier = Modifier.size(14.dp))
            Text("Model routing", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp)
            Text(
                if (routeModel.isBlank() && routeFast.isBlank()) "(optional)" else "(set)",
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            )
        }
        if (routingOpen) {
            Text("Model", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(6.dp))
            FormInput(routeModel, { routeModel = it }, mono = true, error = false, placeholder = "gpt-4o")
            HelperLine(PresetEnv.MODEL, "— what new sessions run on.")
            Text("Small / fast model", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(6.dp))
            FormInput(routeFast, { routeFast = it }, mono = true, error = false, placeholder = "gpt-4o-mini")
            HelperLine(PresetEnv.SMALL_FAST_MODEL, "— for the CLI's quick internal calls.")
        }

        daemonError?.takeIf { it.first !in listOf("name", "baseUrl", "token") }?.let { ErrorRow(it.second) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 20.dp)) {
            if (initial != null) TextBtn("Delete", Tok.danger) { onDelete(initial.id) } // pinned left (2b)
            Spacer(Modifier.weight(1f))
            Text(
                "Cancel", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp))
                    .hoverFill(RoundedCornerShape(7.dp)).clickable(onClick = onClose).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            FilledBtn("Save preset", enabled = valid && !awaitingSave) {
                awaitingSave = true
                daemonError = null
                model.savePreset(
                    id = initial?.id,
                    name = name.trim(),
                    baseUrl = baseUrl.trim(),
                    tokenVar = tokenVar,
                    token = token.takeIf { it.isNotBlank() },
                    model = routeModel.trim().takeIf { it.isNotBlank() },
                    smallFastModel = routeFast.trim().takeIf { it.isNotBlank() },
                )
            }
        }
    }
}

/** Mid-task refusal card shared by the OAuth account switch and preset switching (design 3c):
 *  name each working session, offer per-row Stop and "Stop all & switch". */
@Composable
private fun WorkingBlockersCard(
    blockers: List<dev.ccpocket.protocol.AuthBlocker>,
    onStopOne: (String) -> Unit,
    onForce: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.warn.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
            Text("These sessions are still working and block the switch:", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        blockers.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        b.cwd.substringAfterLast('/').substringAfterLast('\\').ifBlank { b.cwd },
                        color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (b.reason) {
                            dev.ccpocket.protocol.AuthBlockReason.EXECUTING -> "Mid-turn right now"
                            dev.ccpocket.protocol.AuthBlockReason.BACKGROUND_JOBS ->
                                "${b.jobLabels.size.coerceAtLeast(1)} background task${if (b.jobLabels.size == 1) "" else "s"}" +
                                    (b.jobLabels.firstOrNull()?.let { ": $it" } ?: "")
                            dev.ccpocket.protocol.AuthBlockReason.UNKNOWN -> "Still working" // newer daemon's reason
                        },
                        color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // stops this one (background shells die with it; transcript persists) and retries the switch
                TextBtn("Stop", Tok.danger) { onStopOne(b.convoId) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn("Stop all & switch", Tok.danger, onForce)
            Text("Sessions can be resumed afterwards; their background tasks end.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
        }
    }
}

// small form/display primitives for the pane

@Composable
private fun FormInput(
    value: String,
    onChange: (String) -> Unit,
    mono: Boolean,
    error: Boolean,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    accent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Tok.base)
            .border(1.dp, if (error) Tok.danger else if (accent) Tok.accent.copy(alpha = 0.4f) else Tok.hair, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Tok.muted, fontFamily = if (mono) Dk.mono else Dk.ui, fontSize = if (mono) 11.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = Tok.tx, fontFamily = if (mono) Dk.mono else Dk.ui, fontSize = if (mono) 11.sp else 13.sp),
                cursorBrush = SolidColor(Tok.accent),
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun FieldError(msg: String?) {
    if (msg != null) Text(msg, color = Tok.danger, fontFamily = Dk.ui, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
}

@Composable
private fun HelperLine(mono: String, rest: String) {
    Row(modifier = Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(mono, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp)
        Text(rest, color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.5.sp)
    }
}

@Composable
private fun SegChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Tok.tx else Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (selected) Tok.hair else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 2.5.dp),
    )
}

@Composable
private fun FilledBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) Tok.base else Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (enabled) Tok.accent else Tok.surface)
            .border(1.dp, if (enabled) Tok.accent else Tok.hair, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MonoPill(label: String, accent: Boolean) {
    Text(
        label, color = if (accent) Tok.accent else Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (accent) Tok.accent.copy(alpha = 0.13f) else Tok.base)
            .border(1.dp, if (accent) Tok.accent.copy(alpha = 0.3f) else Tok.hair, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 2.5.dp),
    )
}

@Composable
private fun CapLabel(label: String, topPad: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        label.uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, modifier = Modifier.padding(top = topPad),
    )
}

@Composable
private fun Hairline(vertical: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().padding(vertical = vertical).height(1.dp).background(Tok.hair))
}

@Composable
private fun InfoBox(withIcon: Boolean, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (withIcon) Icon(Icons.Outlined.Info, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
        Text(text, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ErrorRow(msg: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(9.dp))
            .background(Tok.danger.copy(alpha = 0.08f)).border(1.dp, Tok.danger.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Warning, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
        Text(msg, color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Row display form of a base URL: scheme and path stripped (design 1a: `api.example-proxy.com`). */
private fun presetHost(url: String): String = url.substringAfter("://").substringBefore("/").ifBlank { url }

private fun isHttpUrl(s: String): Boolean = runCatching {
    val u = java.net.URI(s)
    (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
}.getOrDefault(false)

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

// ── folder-share (issue #115): the desktop owner management + invite pane ──

@Composable
private fun SharesPane(model: DesktopModel) {
    LaunchedEffect(Unit) { model.refreshShares() }
    val now = epochMillis()
    val invite = model.lastShareInvite
    Column {
        Text("Shared folders", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        if (invite != null) {
            InviteResultCard(invite.folderName, tierLabel(invite.tier), invite.encode()) { model.clearLastShare() }
        } else {
            ShareCreateForm(model)
        }
        Spacer(Modifier.height(16.dp))

        val groups = groupShares(model.shares, now)
        if (model.shares.isEmpty()) {
            Text("You haven't shared any folders yet.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
        }
        groups.active.forEach { s ->
            ShareCard(
                path = s.path, guest = s.guestLabel ?: "someone", tier = tierLabel(s.tier),
                expires = "expires in " + countdown(s.expiresAt, now),
                active = shareStatus(s, now) == ShareStatus.ACTIVE_NOW,
                onRevoke = { model.revokeShare(s.deviceId) },
            )
        }
        if (groups.history.isNotEmpty()) {
            Text("History", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            groups.history.forEach { s ->
                ShareCard(
                    path = s.path, guest = s.guestLabel ?: "someone", tier = if (s.revoked) "Revoked" else "Expired",
                    expires = "", active = false, ended = true,
                    onRevoke = { model.createShare(s.path, s.tier, s.expiresAt - s.createdAt) }, revokeLabel = "Share again", revokeColor = Tok.tx2,
                )
            }
        }
    }
}

@Composable
private fun ShareCreateForm(model: DesktopModel) {
    var path by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf(DEFAULT_TIER) }
    var expiry by remember { mutableStateOf(ShareExpiryOption.DEFAULT) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text("Share a folder", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Type the absolute path to a folder on this computer.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (path.isEmpty()) Text("/Users/me/project", color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
            BasicTextField(path, { path = it }, singleLine = true, textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp), cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SHARE_TIERS.forEach { t -> SegPill(tierLabel(t), tier == t) { tier = t } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShareExpiryOption.entries.forEach { o -> SegPill(expiryOptionLabel(o), expiry == o) { expiry = o } }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Create invite", color = if (path.isBlank()) Tok.muted else Tok.base, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (path.isBlank()) Tok.surface else Tok.accent)
                .then(if (path.isBlank()) Modifier else Modifier.clickable { model.createShare(path.trim(), tier, expiry.seconds) }).padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun SegPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Tok.base else Tok.tx2, fontFamily = Dk.ui, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).then(if (selected) Modifier.background(Tok.accent) else Modifier.border(1.dp, Tok.hair, RoundedCornerShape(7.dp)))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun InviteResultCard(folder: String, tier: String, code: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.accent.copy(alpha = 0.06f)).border(1.dp, Tok.accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text("Invite ready", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("$folder · $tier — paste this code into CC Pocket ▸ Connect to join. Works once.", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(code, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 9.dp))
            TextBtn("Copy", Tok.accent) { clipboard.setText(AnnotatedString(code)) }
            TextBtn("Done", Tok.tx2, onClick = onDone)
        }
    }
}

@Composable
private fun ShareCard(
    path: String, guest: String, tier: String, expires: String, active: Boolean,
    ended: Boolean = false, revokeLabel: String = "Revoke", revokeColor: androidx.compose.ui.graphics.Color = Tok.danger, onRevoke: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (ended) Tok.base else Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (active) PulseDot(Tok.ok, 6.dp) else Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Tok.muted))
        Column(Modifier.weight(1f)) {
            Text(path, color = if (ended) Tok.tx2 else Tok.tx, fontFamily = Dk.mono, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString { append(guest); append(" · "); append(tier); if (expires.isNotEmpty()) { append(" · "); append(expires) } },
                color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextBtn(revokeLabel, revokeColor, onClick = onRevoke)
    }
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
        UpdatesSection(model)
    }
}

// "Check for updates" (issue #87): reads model.updateState and offers the right action per install source —
// a standalone dmg/msi self-updates in place, a brew/scoop copy shows its upgrade command to run, and an
// unrecognized/dev build opens the releases page. Button-triggered (never on open) so the pane renders offline.
@Composable
private fun UpdatesSection(model: DesktopModel) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.padding(top = 18.dp)) {
        when (val s = model.updateState) {
            DkUpdateState.Idle ->
                UpdateActionRow("Check for updates", "Check", Tok.accent) { model.checkForUpdates() }

            DkUpdateState.Checking -> UpdateBusy("Checking for updates…")

            is DkUpdateState.UpToDate ->
                UpdateActionRow("You're on the latest version (v${s.current})", "Check again", Tok.tx2) { model.checkForUpdates() }

            is DkUpdateState.Available -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(Tok.accent, 8.dp)
                    Text("Update available", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("v${model.appVersion} → v${s.latest}", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                when (s.source) {
                    // standalone: one click downloads, verifies, replaces this app and relaunches
                    DkInstallSource.STANDALONE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn("Download & restart", Tok.accent) { model.applyUpdate() }
                        Text("Replaces this app and relaunches it.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp)
                    }
                    // package-manager copies never self-overwrite (two updaters, one tree) — show the command
                    DkInstallSource.BREW, DkInstallSource.SCOOP -> Column {
                        Text(
                            "Installed with ${if (s.source == DkInstallSource.BREW) "Homebrew" else "Scoop"} — update it from a terminal:",
                            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        CommandBox(model.updateCommand.orEmpty(), clipboard)
                    }
                    // can't tell how this was installed (dev run / unusual layout) — hand off to the web
                    DkInstallSource.UNKNOWN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn("View release", Tok.accent) { runCatching { uriHandler.openUri(model.updateReleasesUrl) } }
                        Text("Open the releases page to update.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp)
                    }
                }
            }

            is DkUpdateState.Downloading -> UpdateBusy("Downloading v${s.latest}… the app restarts when it's ready.")

            is DkUpdateState.Failed -> Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Tok.danger, modifier = Modifier.size(13.dp))
                    Text(s.message, color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row { TextBtn("Retry", Tok.accent) { model.checkForUpdates() } }
            }
        }
    }
}

@Composable
private fun UpdateActionRow(label: String, action: String, actionColor: Color, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextBtn(action, actionColor, onAction)
    }
}

@Composable
private fun UpdateBusy(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        CircularProgressIndicator(Modifier.size(12.dp), color = Tok.accent, strokeWidth = 1.5.dp)
        Text(label, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp)
    }
}

// mono command + Copy — the brew/scoop upgrade line the user runs in a terminal
@Composable
private fun CommandBox(cmd: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(cmd, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextBtn("Copy", Tok.accent) { clipboard.setText(AnnotatedString(cmd)) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
