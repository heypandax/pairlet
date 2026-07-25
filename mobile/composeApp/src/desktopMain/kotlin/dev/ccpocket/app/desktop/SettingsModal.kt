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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.SUPPORT_URL
import dev.ccpocket.app.USER_MANUAL_URL
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.ui.share.DEFAULT_TIER
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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
import dev.ccpocket.app.ui.CODEX_MODEL_OPTIONS
import kotlinx.coroutines.delay
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.agentTintFill
import dev.ccpocket.app.ui.agentTintBorder
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.PresetEnv
import dev.ccpocket.protocol.PresetSummary
import dev.ccpocket.protocol.PresetsState

private enum class SettingsTab(val label: StringResource, val icon: ImageVector) {
    GENERAL(Res.string.settings_tab_general, Icons.Outlined.Tune),
    ACCOUNT(Res.string.settings_tab_account, Icons.Rounded.Person),
    COMPUTERS(Res.string.settings_tab_computers, Icons.Rounded.Devices),
    SCHEDULES(Res.string.settings_tab_schedules, Icons.Rounded.Schedule),
    SHARES(Res.string.settings_tab_shared, Icons.Rounded.Share),
    BRIDGES(Res.string.settings_bridges, Icons.Rounded.SmartToy),
    SHORTCUTS(Res.string.settings_tab_shortcuts, Icons.Rounded.Keyboard),
    HELP(Res.string.settings_tab_help, Icons.AutoMirrored.Outlined.HelpOutline),
    ABOUT(Res.string.settings_tab_about, Icons.Outlined.Info),
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
            Text(stringResource(Res.string.settings_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Close, stringResource(Res.string.close), tint = Tok.tx2, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(2.dp))
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
                    SettingsTab.SCHEDULES -> SchedulesPane(model)
                    SettingsTab.SHARES -> SharesPane(model)
                    SettingsTab.BRIDGES -> BridgesPane(model)
                    SettingsTab.SHORTCUTS -> ShortcutsPane()
                    SettingsTab.HELP -> HelpPane()
                    SettingsTab.ABOUT -> AboutPane(model)
                }
            }
        }
    }
}

@Composable
private fun HelpPane() {
    val clipboard = LocalClipboardManager.current
    val aiPrompt = stringResource(Res.string.settings_help_ai_prompt)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2400)
            copied = false
        }
    }
    Column {
        Text(
            stringResource(Res.string.settings_help_title),
            color = Tok.tx, fontFamily = Dk.ui, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(Res.string.settings_help_body),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
        )
        HelpActionRow(
            icon = Icons.Rounded.SmartToy,
            title = stringResource(Res.string.support_open),
        ) { openWebUrl(SUPPORT_URL) }
        Spacer(Modifier.height(7.dp))
        HelpActionRow(
            icon = Icons.Rounded.Search,
            title = stringResource(Res.string.settings_help_search),
        ) { openWebUrl(USER_MANUAL_URL) }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(Res.string.settings_help_popular),
            color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        HelpActionRow(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            title = stringResource(Res.string.settings_help_takeover),
        ) { openWebUrl("${USER_MANUAL_URL}?q=take%20over%20terminal") }
        Spacer(Modifier.height(7.dp))
        HelpActionRow(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            title = stringResource(Res.string.settings_help_approvals),
        ) { openWebUrl("${USER_MANUAL_URL}?q=approve") }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HelpButton(
                label = stringResource(Res.string.settings_help_open),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                primary = true,
            ) { openWebUrl(USER_MANUAL_URL) }
            HelpButton(
                label = if (copied) stringResource(Res.string.settings_help_copied) else stringResource(Res.string.settings_help_copy_ai),
                icon = Icons.Rounded.ContentCopy,
                primary = false,
            ) {
                clipboard.setText(AnnotatedString("$USER_MANUAL_URL\n$aiPrompt"))
                copied = true
            }
        }
    }
}

@Composable
private fun HelpActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (hovered) Tok.surface else Color.Transparent)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = Tok.accent, modifier = Modifier.size(16.dp))
        Text(
            title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ChevronRight, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun HelpButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (primary) Tok.accent else Color.Transparent)
            .border(1.dp, if (primary) Tok.accent else Tok.hair, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, tint = if (primary) Tok.base else Tok.tx2, modifier = Modifier.size(14.dp))
        Text(
            label, color = if (primary) Tok.base else Tok.tx2,
            fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RailItem(tab: SettingsTab, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectableRow(selected).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(tab.icon, null, tint = if (selected) Tok.accent else Tok.tx2, modifier = Modifier.size(16.dp))
        Text(stringResource(tab.label), color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
        Group(stringResource(Res.string.settings_appearance), stringResource(Res.string.settings_appearance_sub)) {
            AppearanceRow(model)
        }
        Group(stringResource(Res.string.settings_default_agent), stringResource(Res.string.settings_default_agent_sub)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentCardRow(AgentKind.CLAUDE, model.defaultAgent == AgentKind.CLAUDE, Modifier.weight(1f)) { model.defaultAgent = AgentKind.CLAUDE }
                AgentCardRow(AgentKind.CODEX, model.defaultAgent == AgentKind.CODEX, Modifier.weight(1f)) { model.defaultAgent = AgentKind.CODEX }
                AgentCardRow(AgentKind.OPENCODE, model.defaultAgent == AgentKind.OPENCODE, Modifier.weight(1f)) { model.defaultAgent = AgentKind.OPENCODE }
            }
        }
        val defaultAgent = model.defaultAgent
        val defaultModel = model.defaultModelFor(defaultAgent)
        LaunchedEffect(defaultAgent) { model.fetchModels(defaultAgent) }
        Group(
            stringResource(Res.string.settings_default_model),
            stringResource(Res.string.settings_default_model_sub, agentName(defaultAgent)),
        ) {
            PrefRow(
                stringResource(Res.string.settings_option_default),
                stringResource(Res.string.settings_option_cli_default),
                selected = defaultModel == null,
            ) { model.setDefaultModelFor(defaultAgent, null) }
            val discovered = model.modelsForAgent(defaultAgent).filter { it.isNotBlank() }
            val options = when (defaultAgent) {
                AgentKind.CLAUDE -> CLAUDE_MODEL_OPTIONS
                AgentKind.CODEX -> (listOfNotNull(defaultModel) + discovered.ifEmpty { CODEX_MODEL_OPTIONS })
                    .distinct().map { codexModelLabel(it) to it }
                // OpenCode's provider/model catalog is installation-specific, so only daemon-reported ids
                // are valid choices. The selected value leads in case a later catalog no longer lists it.
                AgentKind.OPENCODE -> (listOfNotNull(defaultModel) + discovered).distinct().map { it to it }
            }
            options.forEach { (label, id) ->
                PrefRow(label, id, selected = defaultModel == id) { model.setDefaultModelFor(defaultAgent, id) }
            }
            if (defaultAgent == AgentKind.OPENCODE && options.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_opencode_local_default),
                    color = Tok.muted,
                    fontFamily = Dk.ui,
                    fontSize = 11.5.sp,
                )
            }
        }
        Group(stringResource(Res.string.settings_context_window), stringResource(Res.string.settings_context_window_sub)) {
            ContextWindowRows(model)
        }
        val reasoningOptions = model.effortOptionsFor(defaultAgent, defaultModel)
        if (reasoningOptions.isNotEmpty()) {
            Group(stringResource(Res.string.default_effort_section)) {
                PrefRow(
                    stringResource(Res.string.settings_option_default),
                    stringResource(Res.string.settings_option_cli_default),
                    selected = model.defaultEffort == null,
                ) { model.defaultEffort = null }
                reasoningOptions.forEach { effort ->
                    PrefRow(effort, "--effort $effort", selected = model.defaultEffort == effort) {
                        model.defaultEffort = effort
                    }
                }
            }
        }
        if (
            defaultAgent == AgentKind.CODEX &&
            model.serviceTierOptionsFor(defaultAgent, defaultModel).any { it.id == "priority" }
        ) {
            Group(stringResource(Res.string.fast_mode), stringResource(Res.string.fast_mode_detail)) {
                PrefRow(
                    stringResource(Res.string.value_off),
                    stringResource(Res.string.settings_option_cli_default),
                    selected = model.defaultServiceTier == null,
                ) { model.defaultServiceTier = null }
                PrefRow(
                    stringResource(Res.string.value_on),
                    "serviceTier = priority",
                    selected = model.defaultServiceTier == "priority",
                ) { model.defaultServiceTier = "priority" }
            }
        }
        Group(stringResource(Res.string.settings_default_mode), stringResource(Res.string.settings_default_mode_sub)) {
            val modes = CLAUDE_MODES + if (
                defaultAgent == AgentKind.CLAUDE &&
                model.permissionModeAvailable(dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO)
            ) listOf(CLAUDE_AUTO_MODE) else emptyList()
            modes.forEach { m ->
                ModeRow(
                    m,
                    selected = m.mode == model.defaultMode && m.nativeMode == model.defaultPermissionMode,
                ) { model.setDefaultMode(m.mode, m.nativeMode) }
            }
        }
        // how a terminal opens (issue #153: embedded dock is the default) + which external app (issue #44 —
        // only terminals actually present on this machine are offered)
        Group(stringResource(Res.string.settings_terminal), stringResource(Res.string.settings_terminal_sub)) {
            PrefRow(stringResource(Res.string.settings_term_embedded), stringResource(Res.string.settings_term_embedded_hint), selected = model.terminalDefaultEmbedded) {
                model.terminalDefaultEmbedded = true
            }
            PrefRow(stringResource(Res.string.settings_term_external), stringResource(Res.string.settings_term_external_hint), selected = !model.terminalDefaultEmbedded) {
                model.terminalDefaultEmbedded = false
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.settings_term_external_app), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 7.dp))
            TerminalApp.entries.filter(TerminalLauncher::installed).forEach { t ->
                TerminalRow(t, selected = t == model.terminalApp) { model.terminalApp = t }
            }
        }
        // menu-bar presence (issue #151): the OS status glyph + anchored popover, on by default
        Group(stringResource(Res.string.settings_menu_bar), stringResource(Res.string.settings_menu_bar_sub)) {
            ToggleRow(stringResource(Res.string.settings_menu_bar_toggle), model.menuBarEnabled) { model.menuBarEnabled = !model.menuBarEnabled }
        }
        // daemon-side switch: silence phone alerts while working at the computer. Null = old daemon.
        LaunchedEffect(Unit) { model.refreshPushPrefs() }
        Group(stringResource(Res.string.settings_notifications), stringResource(Res.string.settings_notifications_sub)) {
            when (val on = model.phonePush) {
                null -> Text(
                    stringResource(Res.string.settings_push_stale),
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                )
                else -> ToggleRow(stringResource(Res.string.settings_push_toggle), on) { model.setPhonePush(!on) }
            }
        }
    }
}

private fun codexModelLabel(id: String): String = id.split('-').joinToString(" ") { part ->
    when (part.lowercase()) {
        "gpt" -> "GPT"
        "codex" -> "Codex"
        "mini" -> "Mini"
        else -> part
    }
}

// System / Light / Dark segmented control (issue #63) — a three-way toggle mirroring the mobile Appearance
// picker. Wired to the shared repo via model.themeMode, so a pick persists and the window root re-themes live.
@Composable
private fun AppearanceRow(model: DesktopModel) {
    val modes = listOf(
        ThemeMode.SYSTEM to stringResource(Res.string.appearance_system),
        ThemeMode.LIGHT to stringResource(Res.string.appearance_light),
        ThemeMode.DARK to stringResource(Res.string.appearance_dark),
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
        Text(stringResource(if (on) Res.string.toggle_on else Res.string.toggle_off), color = if (on) Tok.ok else Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
    }
}

/** The user-facing name of a terminal choice — SYSTEM localizes; Ghostty is a product name and stays. */
@Composable
internal fun terminalAppLabel(t: TerminalApp): String = when (t) {
    TerminalApp.SYSTEM -> stringResource(Res.string.term_app_system)
    TerminalApp.GHOSTTY -> t.label
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
        Text(terminalAppLabel(t), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
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
        Text(agentName(agent), color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    PrefRow(stringResource(Res.string.settings_option_default), "follow model", selected = current == null) { model.contextWindowOverride = null }
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
        Text(stringResource(Res.string.context_window_custom), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.width(108.dp).clip(RoundedCornerShape(7.dp)).background(Tok.base)
                .border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            if (draft.isEmpty()) Text(stringResource(Res.string.context_window_tokens), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
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
        Text(stringResource(m.label), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp)
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
        Group(stringResource(Res.string.settings_auth), stringResource(Res.string.settings_auth_sub)) {
            when {
                s == null -> Text(
                    stringResource(
                        when {
                            !model.connected -> Res.string.settings_auth_disconnected
                            timedOut -> Res.string.settings_auth_stale
                            else -> Res.string.settings_auth_loading
                        },
                    ),
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, lineHeight = 19.sp,
                )

                s.loginPending -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text(stringResource(Res.string.settings_login_finish), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(Res.string.settings_login_browser),
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    s.loginUrl?.let { url ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                            Text(
                                url, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            TextBtn(stringResource(Res.string.settings_login_open_here), Tok.accent) { runCatching { uriHandler.openUri(url) } }
                            TextBtn(stringResource(Res.string.path_copy), Tok.tx2) { clipboard.setText(AnnotatedString(url)) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(Tok.base)
                                .border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            if (code.isEmpty()) Text(stringResource(Res.string.settings_login_paste_code), color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
                            BasicTextField(
                                code, { code = it }, singleLine = true,
                                textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp),
                                cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextBtn(stringResource(Res.string.settings_login_submit), Tok.accent) { if (code.isNotBlank()) { model.submitAuthCode(code); code = "" } }
                        TextBtn(stringResource(Res.string.cancel), Tok.muted) { code = ""; model.cancelAuthLogin() }
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
                        Text(stringResource(Res.string.settings_api_key), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        MonoPill("env · $keySource", accent = false)
                    }
                    Hairline(vertical = 13.dp)
                    Text(
                        stringResource(Res.string.settings_env_key_body, keySource) + " " +
                            stringResource(if (ps != null) Res.string.settings_env_key_presets else Res.string.settings_env_key_computer),
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
                            Text(s.email ?: stringResource(Res.string.settings_signed_in), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            stringResource(Res.string.settings_switch_warning),
                            color = Tok.warn, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextBtn(stringResource(Res.string.action_continue), Tok.accent) { confirmSwitch = false; model.switchAccount() }
                            TextBtn(stringResource(Res.string.cancel), Tok.muted) { confirmSwitch = false }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // only a real OAuth login (which always carries an email) can actually be switched or
                        // logged out. An env-token / gateway auth reports loggedIn=true with a null email and no
                        // apiKeySource (so it lands here, not the API-key branch), yet `claude auth login/logout`
                        // can't touch it — grey these out rather than leave them as no-ops the user keeps poking.
                        val canManage = s.email != null
                        TextBtn(stringResource(Res.string.settings_switch_account), Tok.accent, enabled = canManage) { confirmSwitch = true }
                        TextBtn(stringResource(Res.string.settings_log_out), Tok.danger, enabled = canManage) { model.logoutAccount() }
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
                        Text(stringResource(Res.string.settings_no_auth_title), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        MonoPill("unconfigured", accent = false)
                    }
                    Hairline(vertical = 13.dp)
                    Text(
                        stringResource(Res.string.settings_no_auth_body) + " " +
                            stringResource(if (ps != null) Res.string.settings_no_auth_presets else Res.string.settings_no_auth_signin),
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row { TextBtn(stringResource(Res.string.settings_sign_in), Tok.accent) { model.switchAccount() } }
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
        Group(stringResource(Res.string.settings_presets), stringResource(Res.string.settings_presets_sub)) {
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
            Text(stringResource(Res.string.settings_api_key), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            CapLabel(stringResource(Res.string.settings_model_route), topPad = 12.dp)
            val route = listOfNotNull(
                p.model?.let { "model → $it" },
                p.smallFastModel?.let { "fast → $it" },
            ).joinToString(" · ")
            Text(route, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        // the way back to the computer's own login/env — same switch semantics as activating (blockers may refuse)
        Row { TextBtn(stringResource(Res.string.settings_preset_deactivate), Tok.muted, onClick = onDeactivate) }
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
            stringResource(
                when {
                    !connected -> Res.string.settings_presets_disconnected
                    timedOut -> Res.string.settings_presets_stale
                    else -> Res.string.settings_presets_loading
                },
            ),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, lineHeight = 19.sp,
        )
        return
    }
    Column {
        if (ps.presets.isEmpty()) {
            // 1b empty vs 1c OAuth-coexist: same slot, different explanation
            InfoBox(
                withIcon = oauthActive,
                text = stringResource(if (oauthActive) Res.string.settings_presets_info_oauth else Res.string.settings_presets_empty),
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
            Text(stringResource(Res.string.settings_preset_new), color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        if (ps.activeId != null) Text(
            stringResource(Res.string.settings_presets_active_note, computerName ?: stringResource(Res.string.this_computer)),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp),
        )
        if (ps.blockers.isNotEmpty()) WorkingBlockersCard(ps.blockers, onStopOne = onStopOne, onForce = onForce)
        else if (reachError) ErrorRow(stringResource(Res.string.settings_reach_error))
        else ps.error?.let { ErrorRow(it) }
        // the secrets red line, stated where the secrets are handled
        Text(
            stringResource(Res.string.settings_presets_secret_note),
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
                Text(stringResource(Res.string.settings_preset_activating), color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.5.sp)
            }
            active -> Text(
                stringResource(Res.string.settings_preset_active), color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.accent.copy(alpha = 0.13f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
            hovered -> Row {
                TextBtn(stringResource(Res.string.bridge_edit), Tok.tx2, onClick = onEdit)
                TextBtn(stringResource(Res.string.per_model_delete), Tok.danger, onClick = onDelete)
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
        name.isNotBlank() && others.any { it.name.equals(name.trim(), ignoreCase = true) } -> stringResource(Res.string.settings_preset_name_dup, name.trim())
        nameTouched && name.isBlank() -> stringResource(Res.string.settings_preset_name_required)
        else -> daemonError?.takeIf { it.first == "name" }?.second
    }
    val urlError = when {
        baseUrl.isNotBlank() && !isHttpUrl(baseUrl.trim()) -> stringResource(Res.string.settings_preset_url_invalid)
        urlTouched && baseUrl.isBlank() -> stringResource(Res.string.settings_preset_url_invalid)
        else -> daemonError?.takeIf { it.first == "baseUrl" }?.second
    }
    val tokenError =
        if (initial == null && tokenTouched && token.isBlank()) stringResource(Res.string.settings_preset_token_required)
        else daemonError?.takeIf { it.first == "token" }?.second
    val valid = name.isNotBlank() && nameError == null && isHttpUrl(baseUrl.trim()) && (initial != null || token.isNotBlank())

    Column {
        Text(
            stringResource(Res.string.settings_presets_back), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).hoverFill(RoundedCornerShape(6.dp)).clickable(onClick = onClose).padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            stringResource(if (initial == null) Res.string.settings_preset_new else Res.string.settings_preset_edit),
            color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )

        Text(stringResource(Res.string.form_name), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        FormInput(name, { name = it; nameTouched = true; daemonError = null }, mono = false, error = nameError != null, placeholder = stringResource(Res.string.settings_preset_name_ph))
        FieldError(nameError)

        Text("Base URL", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(6.dp))
        FormInput(baseUrl, { baseUrl = it; urlTouched = true; daemonError = null }, mono = true, error = urlError != null, placeholder = "https://api.example-proxy.com/v1")
        FieldError(urlError)
        if (urlError == null) HelperLine(PresetEnv.BASE_URL, stringResource(Res.string.settings_helper_baseurl))

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
            placeholder = stringResource(if (initial == null) Res.string.settings_preset_token_ph else Res.string.settings_preset_token_stored),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation('•'),
            accent = reveal,
            trailing = {
                Icon(
                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    stringResource(if (reveal) Res.string.settings_token_hide else Res.string.settings_token_reveal),
                    tint = if (reveal) Tok.accent else Tok.tx2,
                    modifier = Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).clickable { reveal = !reveal },
                )
            },
        )
        FieldError(tokenError)
        if (tokenError == null) HelperLine(tokenVar, stringResource(Res.string.settings_helper_token))

        // model routing (optional): the two env vars the CLI reads for model steering
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
                .clickable { routingOpen = !routingOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (routingOpen) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, tint = Tok.muted, modifier = Modifier.size(14.dp))
            Text(stringResource(Res.string.settings_model_routing), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp)
            Text(
                stringResource(if (routeModel.isBlank() && routeFast.isBlank()) Res.string.settings_optional else Res.string.settings_set),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            )
        }
        if (routingOpen) {
            Text(stringResource(Res.string.label_model), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(6.dp))
            FormInput(routeModel, { routeModel = it }, mono = true, error = false, placeholder = "gpt-4o")
            HelperLine(PresetEnv.MODEL, stringResource(Res.string.settings_helper_model))
            Text(stringResource(Res.string.settings_small_fast_model), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(6.dp))
            FormInput(routeFast, { routeFast = it }, mono = true, error = false, placeholder = "gpt-4o-mini")
            HelperLine(PresetEnv.SMALL_FAST_MODEL, stringResource(Res.string.settings_helper_fast))
        }

        daemonError?.takeIf { it.first !in listOf("name", "baseUrl", "token") }?.let { ErrorRow(it.second) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 20.dp)) {
            if (initial != null) TextBtn(stringResource(Res.string.per_model_delete), Tok.danger) { onDelete(initial.id) } // pinned left (2b)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.cancel), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp))
                    .hoverFill(RoundedCornerShape(7.dp)).clickable(onClick = onClose).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            FilledBtn(stringResource(Res.string.settings_preset_save), enabled = valid && !awaitingSave) {
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
            Text(stringResource(Res.string.settings_blockers_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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
                            dev.ccpocket.protocol.AuthBlockReason.EXECUTING -> stringResource(Res.string.settings_blocker_midturn)
                            dev.ccpocket.protocol.AuthBlockReason.BACKGROUND_JOBS ->
                                stringResource(
                                    if (b.jobLabels.size == 1) Res.string.settings_blocker_bg_one else Res.string.settings_blocker_bg_many,
                                    b.jobLabels.size.coerceAtLeast(1),
                                ) + (b.jobLabels.firstOrNull()?.let { ": $it" } ?: "")
                            dev.ccpocket.protocol.AuthBlockReason.UNKNOWN -> stringResource(Res.string.settings_blocker_working) // newer daemon's reason
                        },
                        color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // stops this one (background shells die with it; transcript persists) and retries the switch
                TextBtn(stringResource(Res.string.bridge_runner_stop), Tok.danger) { onStopOne(b.convoId) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn(stringResource(Res.string.settings_stop_all_switch), Tok.danger, onClick = onForce)
            Text(stringResource(Res.string.settings_stop_all_note), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
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
        Text(stringResource(Res.string.settings_paired_computers), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        if (model.computers.isEmpty()) {
            Text(stringResource(Res.string.settings_no_computers), color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
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
                    TextBtn(stringResource(Res.string.device_save), Tok.accent) { model.renameComputer(c, draft.ifBlank { null }); editingId = null }
                    TextBtn(stringResource(Res.string.cancel), Tok.muted) { editingId = null }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.accountId, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.online) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PulseDot(Tok.ok, 6.dp); Text(stringResource(Res.string.presence_online), color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.sp)
                    } else Text(stringResource(Res.string.presence_offline), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
                    TextBtn(stringResource(Res.string.device_rename), Tok.tx2) { editingId = c.accountId; draft = "" }
                    TextBtn(stringResource(Res.string.share_revoke), Tok.danger) { model.revokeComputer(c) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).dashedBorder(Tok.hair, 11.dp).hoverFill(RoundedCornerShape(11.dp))
                .clickable { model.addComputer() }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
            Text(stringResource(Res.string.add_device), color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// enabled=false greys the label to the palette's weakest text (Tok.muted), drops the click, and skips the
// hover-fill so a dead action reads as dead instead of a no-op the user keeps poking (env-token account case)
@Composable
private fun TextBtn(label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) color else Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(7.dp))
            .then(if (enabled) Modifier.hoverFill(RoundedCornerShape(7.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

// ── folder-share (issue #115): the desktop owner management + invite pane ──

@Composable
private fun SchedulesPane(model: DesktopModel) {
    // scheduled tasks (issue #137): the management list — cancel here; the creation gesture lives on
    // mobile's composer long-press (and the chat's usage-limit auto-continue banner).
    LaunchedEffect(Unit) { model.refreshSchedules() }
    val now = epochMillis()
    Column {
        Text(stringResource(Res.string.schedule_tasks_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        when {
            model.schedulesStale -> Text(
                stringResource(Res.string.settings_schedules_stale),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp,
            )
            model.schedulesLoaded && model.schedules.isEmpty() -> Text(
                stringResource(Res.string.settings_schedules_empty),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp,
            )
            else -> model.schedules.forEach { s ->
                val next = s.nextRunAtMs
                val status = when {
                    next != null && next <= now -> stringResource(Res.string.schedule_due_now)
                    next != null -> stringResource(Res.string.schedule_next_run, dev.ccpocket.app.ui.etaShort(next - now))
                    s.lastOutcome == "missed" -> stringResource(Res.string.schedule_missed)
                    s.lastOutcome != null && s.lastOutcome != "ok" -> s.lastOutcome!!
                    else -> stringResource(Res.string.schedule_done)
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.label ?: s.prompt, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (s.repeat != null) {
                                Text("  ·  " + stringResource(Res.string.schedule_repeats), color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.5.sp)
                            }
                        }
                        Text(
                            s.workdir.substringAfterLast('/').ifEmpty { s.workdir } + "  ·  " + status,
                            color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                    Text(
                        stringResource(Res.string.schedule_remove), color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.cancelSchedule(s.id) }.padding(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SharesPane(model: DesktopModel) {
    LaunchedEffect(Unit) { model.refreshShares() }
    val now = epochMillis()
    val invite = model.lastShareInvite
    Column {
        Text(stringResource(Res.string.shared_folders_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        if (invite != null) {
            InviteResultCard(invite.folderName, tierLabel(invite.tier), invite.encode()) { model.clearLastShare() }
        } else {
            ShareCreateForm(model)
        }
        Spacer(Modifier.height(16.dp))

        val groups = groupShares(model.shares, now)
        if (model.shares.isEmpty()) {
            Text(stringResource(Res.string.shared_folders_empty), color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
        }
        groups.active.forEach { s ->
            ShareCard(
                path = s.path, guest = s.guestLabel ?: stringResource(Res.string.share_guest_someone), tier = tierLabel(s.tier),
                expires = stringResource(Res.string.share_expires_in, countdown(s.expiresAt, now)),
                active = shareStatus(s, now) == ShareStatus.ACTIVE_NOW,
                onRevoke = { model.revokeShare(s.deviceId) },
            )
        }
        if (groups.history.isNotEmpty()) {
            Text(stringResource(Res.string.share_history_label), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            groups.history.forEach { s ->
                ShareCard(
                    path = s.path, guest = s.guestLabel ?: stringResource(Res.string.share_guest_someone),
                    tier = stringResource(if (s.revoked) Res.string.share_revoked_label else Res.string.share_expired_label),
                    expires = "", active = false, ended = true,
                    onRevoke = { model.createShare(s.path, s.tier, s.expiresAt - s.createdAt) }, revokeLabel = stringResource(Res.string.share_share_again), revokeColor = Tok.tx2,
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
        Text(stringResource(Res.string.share_composer_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.share_path_hint), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
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
            stringResource(Res.string.share_create), color = if (path.isBlank()) Tok.muted else Tok.base, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
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
        Text(stringResource(Res.string.share_invite_ready), color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("$folder · $tier — " + stringResource(Res.string.share_invite_hint), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(code, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 9.dp))
            TextBtn(stringResource(Res.string.path_copy), Tok.accent) { clipboard.setText(AnnotatedString(code)) }
            TextBtn(stringResource(Res.string.share_done), Tok.tx2, onClick = onDone)
        }
    }
}

@Composable
private fun ShareCard(
    path: String, guest: String, tier: String, expires: String, active: Boolean,
    ended: Boolean = false, revokeLabel: String? = null, revokeColor: androidx.compose.ui.graphics.Color = Tok.danger, onRevoke: () -> Unit,
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
        TextBtn(revokeLabel ?: stringResource(Res.string.share_revoke), revokeColor, onClick = onRevoke)
    }
}

@Composable
private fun ShortcutsPane() {
    val rows = listOf(
        stringResource(Res.string.shortcut_palette) to listOf("⌘", "K"),
        stringResource(Res.string.new_session_title) to listOf("⌘", "N"),
        stringResource(Res.string.shortcut_send) to listOf("⏎"),
        stringResource(Res.string.shortcut_newline) to listOf("⇧", "⏎"),
        stringResource(Res.string.shortcut_approve) to listOf("⌘", "⏎"),
        stringResource(Res.string.shortcut_open_settings) to listOf("⌘", ","),
        stringResource(Res.string.shortcut_close) to listOf("esc"),
    )
    Column {
        Text(stringResource(Res.string.settings_shortcuts_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
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
            stringResource(Res.string.about_desktop_blurb),
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.width(380.dp).padding(bottom = 18.dp),
        )
        val info = listOf(
            stringResource(Res.string.about_version) to model.appVersion,
            stringResource(Res.string.about_relay) to model.relayUrl.ifBlank { "—" },
            stringResource(Res.string.about_license) to "MIT",
        )
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
                UpdateActionRow(stringResource(Res.string.update_check_row), stringResource(Res.string.update_check_action), Tok.accent) { model.checkForUpdates() }

            DkUpdateState.Checking -> UpdateBusy(stringResource(Res.string.update_checking))

            is DkUpdateState.UpToDate ->
                UpdateActionRow(stringResource(Res.string.update_latest, s.current), stringResource(Res.string.update_check_again), Tok.tx2) { model.checkForUpdates() }

            is DkUpdateState.Available -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(Tok.accent, 8.dp)
                    Text(stringResource(Res.string.update_available), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("v${model.appVersion} → v${s.latest}", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                when (s.source) {
                    // standalone: one click downloads, verifies, replaces this app and relaunches
                    DkInstallSource.STANDALONE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn(stringResource(Res.string.update_download_restart), Tok.accent) { model.applyUpdate() }
                        Text(stringResource(Res.string.update_replace_note), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp)
                    }
                    // package-manager copies never self-overwrite (two updaters, one tree) — show the command
                    DkInstallSource.BREW, DkInstallSource.SCOOP -> Column {
                        Text(
                            stringResource(Res.string.update_installed_with, if (s.source == DkInstallSource.BREW) "Homebrew" else "Scoop"),
                            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        CommandBox(model.updateCommand.orEmpty(), clipboard)
                    }
                    // can't tell how this was installed (dev run / unusual layout) — hand off to the web
                    DkInstallSource.UNKNOWN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn(stringResource(Res.string.update_view_release), Tok.accent) { runCatching { uriHandler.openUri(model.updateReleasesUrl) } }
                        Text(stringResource(Res.string.update_open_releases), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp)
                    }
                }
            }

            is DkUpdateState.Downloading -> UpdateBusy(stringResource(Res.string.update_downloading, s.latest))

            is DkUpdateState.Failed -> Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Tok.danger, modifier = Modifier.size(13.dp))
                    Text(s.message, color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row { TextBtn(stringResource(Res.string.action_retry), Tok.accent) { model.checkForUpdates() } }
            }
        }
    }
}

@Composable
private fun UpdateActionRow(label: String, action: String, actionColor: Color, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextBtn(action, actionColor, onClick = onAction)
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
        TextBtn(stringResource(Res.string.path_copy), Tok.accent) { clipboard.setText(AnnotatedString(cmd)) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
