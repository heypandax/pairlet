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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
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

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    GENERAL("General", Icons.Outlined.Tune),
    ACCOUNT("Account", Icons.Rounded.Person),
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
                    SettingsTab.ACCOUNT -> AccountPane(model)
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
 * The active computer's Claude CLI account (issue: switch accounts without a terminal). One account at a
 * time, mirroring the official desktop app: "Switch account" signs the CLI out and starts `claude auth
 * login` on the daemon host; the OAuth page yields a code the user pastes back here. All state is the
 * daemon's latest AuthState push — this pane holds no login state machine of its own.
 */
@Composable
private fun AccountPane(model: DesktopModel) {
    // an old daemon silently drops pocket/auth.fetch — flip to an explicit "update it" line instead of loading forever
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshAuth(); delay(4_000); timedOut = true }
    val s = model.authState
    var confirmSwitch by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    Column {
        Group("Claude account", "The account the ${model.activeComputer?.name ?: "active computer"}'s Claude CLI is signed in with.") {
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

                // an API key / forwarding endpoint authenticates via env var, not an OAuth login: the CLI
                // still reports loggedIn + authMethod "claude.ai", but email/plan are null and `claude auth
                // login/logout` can't override the key. Explain that instead of dangling a dead switch (#73).
                s.loggedIn && !s.apiKeySource.isNullOrBlank() -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    val keySource = s.apiKeySource.orEmpty() // non-blank per the branch guard (a protocol prop can't smart-cast cross-module)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Authenticated with an API key", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(keySource, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This computer's Claude CLI runs on an API key — a key or forwarding endpoint set in its " +
                            "environment, not a Claude subscription login. Switching accounts and signing out here only " +
                            "apply to claude.ai logins; to change the key or endpoint, edit $keySource / " +
                            "ANTHROPIC_BASE_URL on the computer and reconnect.",
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

                else -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text("Not signed in", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The Claude CLI on this computer has no active account.",
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    Row { TextBtn("Sign in…", Tok.accent) { model.switchAccount() } }
                }
            }
            // mid-task refusal with structure: name each blocker and offer to stop it — per row or all at
            // once — instead of the dead-end string (which stays as the fallback for pre-blockers daemons)
            if (s?.blockers?.isNotEmpty() == true) Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
                    Text("These sessions are still working and block the switch:", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
                s.blockers.forEach { b ->
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
                        TextBtn("Stop", Tok.danger) { model.stopAuthBlocker(b.convoId) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBtn("Stop all & switch", Tok.danger) { model.switchAccount(force = true) }
                    Text("Sessions can be resumed afterwards; their background tasks end.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                }
            }
            else s?.error?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Tok.danger, modifier = Modifier.size(13.dp))
                    Text(it, color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp)
                }
            }
        }
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
