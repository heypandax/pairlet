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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.ho_menu_row
import dev.ccpocket.app.resources.label_agent
import dev.ccpocket.app.resources.label_effort
import dev.ccpocket.app.resources.label_mode
import dev.ccpocket.app.resources.label_model
import dev.ccpocket.app.resources.value_model_default
import dev.ccpocket.app.resources.mode_accept_short
import dev.ccpocket.app.resources.mode_auto_short
import dev.ccpocket.app.resources.mode_bypass_short
import dev.ccpocket.app.resources.mode_default_short
import dev.ccpocket.app.resources.mode_plan_short
import dev.ccpocket.app.resources.model_custom_label
import dev.ccpocket.app.resources.model_gateway_alias_note
import dev.ccpocket.app.resources.model_gateway_note
import dev.ccpocket.app.resources.model_gateway_section
import dev.ccpocket.app.resources.model_gateway_show
import dev.ccpocket.app.resources.model_gateway_suggested
import dev.ccpocket.app.resources.model_next_turn_note
import dev.ccpocket.app.resources.model_section_anthropic
import dev.ccpocket.app.resources.new_path_start
import dev.ccpocket.app.resources.new_session_title
import dev.ccpocket.app.resources.opencode_mode_note
import dev.ccpocket.app.resources.opencode_mode_title
import dev.ccpocket.app.resources.opencode_models_loading
import dev.ccpocket.app.resources.popover_where
import dev.ccpocket.app.resources.qa_clear
import dev.ccpocket.app.resources.qa_clear_armed
import dev.ccpocket.app.resources.qa_compact
import dev.ccpocket.app.resources.qa_terminal
import dev.ccpocket.app.resources.quick_actions_title
import dev.ccpocket.app.resources.fast_mode
import dev.ccpocket.app.resources.value_off
import dev.ccpocket.app.resources.value_on
import dev.ccpocket.app.resources.value_default
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.app.ui.AutoSizeSingleLineText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import dev.ccpocket.app.ui.CLAUDE_MODEL_OPTIONS
import dev.ccpocket.app.ui.claudeRowPick
import dev.ccpocket.app.ui.CODEX_MODEL_OPTIONS
import dev.ccpocket.app.ui.GatewayModelPreset
import dev.ccpocket.app.ui.GatewayVendorMonogram
import dev.ccpocket.app.ui.gatewayRowsFrom
import dev.ccpocket.app.ui.gatewayHostLabel
import dev.ccpocket.app.ui.matchesGatewayHost
import dev.ccpocket.app.ui.ModelChoice
import dev.ccpocket.app.ui.modelChipLabel
import dev.ccpocket.app.ui.recommendedGatewayPresets
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.agentTintBorder
import dev.ccpocket.app.ui.agentTintFill
import dev.ccpocket.app.ui.handoff.canInitiateSessionHandoff
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.PermissionMode

internal data class DkMode(
    val label: StringResource,
    val token: String,
    val mode: PermissionMode,
    val dot: Color,
    val danger: Boolean = false,
    val nativeMode: String? = null,
)

internal val CLAUDE_MODES = listOf(
    DkMode(Res.string.mode_default_short, "default", PermissionMode.DEFAULT, Tok.tx2),
    DkMode(Res.string.mode_accept_short, "acceptEdits", PermissionMode.ACCEPT_EDITS, Tok.ok),
    DkMode(Res.string.mode_plan_short, "plan", PermissionMode.PLAN, Tok.info),
    DkMode(Res.string.mode_bypass_short, "bypass", PermissionMode.BYPASS_PERMISSIONS, Tok.warn, danger = true),
)
internal val CLAUDE_AUTO_MODE =
    DkMode(Res.string.mode_auto_short, CLAUDE_PERMISSION_MODE_AUTO, PermissionMode.DEFAULT, Tok.accent, nativeMode = CLAUDE_PERMISSION_MODE_AUTO)

/** Agents exposed by both desktop entry points: New session and Settings > Default agent. */
internal val DESKTOP_AGENT_CHOICES = listOf(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.ZCODE, AgentKind.DSH)

/** Desktop's rendering model for the shared permission contract. */
internal fun desktopModeChoices(agent: AgentKind, autoAvailable: Boolean = false): List<DkMode> = when (agent) {
    AgentKind.CLAUDE -> CLAUDE_MODES + if (autoAvailable) listOf(CLAUDE_AUTO_MODE) else emptyList()
    AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.KIMI, AgentKind.ZCODE, AgentKind.DSH -> CLAUDE_MODES
}

internal fun desktopDefaultModeIndex(
    agent: AgentKind,
    defaultMode: PermissionMode,
    defaultPermissionMode: String?,
    autoAvailable: Boolean = false,
): Int = desktopModeChoices(agent, autoAvailable)
    .indexOfFirst { it.mode == defaultMode && it.nativeMode == defaultPermissionMode }
    .coerceAtLeast(0)

/**
 * Agent + model + mode picker with an EDITABLE path field seeded by whoever opened it (the current project
 * from ⌘N / the Sessions-pane row, "~/" from the Projects-group row, a RECENT header's ＋). A path whose
 * leaf folder doesn't exist yet is created by the daemon (one level, under an existing parent) — same
 * contract as mobile's NewPathSheet.
 *
 * Model (issue #199) reads the same [modelChoicesFor] table the live-session [ModelPopover] does and rides
 * [onStart] for this creation only — no per-project memory, no new default. [modelsFor] is a lambda because
 * the list follows the agent picked INSIDE the popover; [onAgentPicked] lets the host refresh it.
 */
@Composable
fun NewSessionPopover(
    initialPath: String,
    defaultAgent: AgentKind = AgentKind.CLAUDE,
    availableAgents: List<AgentKind> = DESKTOP_AGENT_CHOICES,
    defaultMode: PermissionMode = PermissionMode.DEFAULT,
    defaultPermissionMode: String? = null,
    autoAvailable: Boolean = false,
    modelsFor: (AgentKind) -> List<ModelChoice> = { emptyList() },
    defaultModelFor: (AgentKind) -> String? = { null },
    onAgentPicked: (AgentKind) -> Unit = {},
    onStart: (String, AgentKind, PermissionMode, String?, String?) -> Unit,
) {
    val selectableAgents = availableAgents.ifEmpty { listOf(AgentKind.CLAUDE) }
    var agent by remember { mutableStateOf(defaultAgent.takeIf { it in selectableAgents } ?: selectableAgents.first()) }
    val availableModes = desktopModeChoices(agent, autoAvailable)
    var modeIdx by remember {
        mutableStateOf(desktopDefaultModeIndex(agent, defaultMode, defaultPermissionMode, autoAvailable))
    }
    // null = follow the per-agent default. Reset per agent: a Claude alias isn't a model Codex can run.
    var chosenModel by remember(agent) { mutableStateOf<String?>(null) }
    LaunchedEffect(agent) { onAgentPicked(agent) }
    var path by remember(initialPath) { mutableStateOf(TextFieldValue(initialPath, selection = TextRange(initialPath.length))) }
    val trimmed = path.text.trim()
    // light client check; the daemon is the authority (rejects a non-readable dir with a clear error)
    val looksAbsolute = trimmed.startsWith("/") || trimmed.startsWith("~") || Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed)
    val pathFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { pathFocus.requestFocus() }
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            // issue #209: the Overlay bounds this popover to the window height; scroll so the Start button
            // at the bottom stays reachable on a short window / high display scale instead of being clipped
            .verticalScroll(rememberScrollState())
            // Enter anywhere in the popover = the Start button (the path field holds focus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && looksAbsolute) {
                    val selected = availableModes.getOrElse(modeIdx) { CLAUDE_MODES.first() }
                    onStart(
                        trimmed,
                        agent,
                        if (agent == AgentKind.OPENCODE) PermissionMode.BYPASS_PERMISSIONS else selected.mode,
                        selected.nativeMode.takeIf { agent == AgentKind.CLAUDE },
                        chosenModel,
                    )
                    true
                } else false
            },
    ) {
        Text(stringResource(Res.string.new_session_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 13.dp))
        Column(Modifier.padding(15.dp)) {
            PopoverLabel(stringResource(Res.string.popover_where))
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
            PopoverLabel(stringResource(Res.string.label_agent))
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                selectableAgents.chunked(3).forEach { rowAgents ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        rowAgents.forEach { candidate ->
                            AgentCard(candidate, agent == candidate, Modifier.weight(1f)) { agent = candidate }
                        }
                        repeat(3 - rowAgents.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            NewSessionModelRow(modelsFor(agent), chosenModel, defaultModelFor(agent)) { chosenModel = it }
            PopoverLabel(stringResource(Res.string.label_mode))
            if (agent == AgentKind.OPENCODE) {
                // no selectable ladder: opencode has no approval protocol (daemon runs it --auto),
                // so every mode row here would promise approvals that never come — same honesty
                // rule as mobile's OpenCodeAutoApproveNotice. The stored mode is BYPASS (the truth).
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(Tok.warn.copy(alpha = 0.08f))
                        .border(1.dp, Tok.warn.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
                        Text(stringResource(Res.string.opencode_mode_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(Res.string.opencode_mode_note),
                        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else availableModes.forEachIndexed { i, m ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (i == modeIdx) Tok.surface else Color.Transparent)
                        .border(1.dp, if (i == modeIdx) Tok.accent else Tok.hair, RoundedCornerShape(8.dp))
                        .clickable { modeIdx = i }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dot(m.dot, 7.dp)
                    Text(stringResource(m.label), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp)
                    if (m.danger) Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.weight(1f))
                    Text(m.token, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
                }
            }
            Text(
                stringResource(Res.string.new_path_start), color = Tok.base, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (looksAbsolute) 1f else 0.45f)
                    .clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                    .clickable(enabled = looksAbsolute) {
                        val selected = availableModes.getOrElse(modeIdx) { CLAUDE_MODES.first() }
                        onStart(
                            trimmed,
                            agent,
                            if (agent == AgentKind.OPENCODE) PermissionMode.BYPASS_PERMISSIONS else selected.mode,
                            selected.nativeMode.takeIf { agent == AgentKind.CLAUDE },
                            chosenModel,
                        )
                    }.padding(vertical = 10.dp),
            )
        }
    }
}

/**
 * The new-session MODEL row (issue #199): one line saying what will actually run, click to reveal
 * "Default" + the agent's rows. [chosen] null = follow [fallback] (the per-agent Settings default, or the
 * CLI's own when that is null too), so the row stays honest even when the user never opens it.
 */
@Composable
private fun NewSessionModelRow(choices: List<ModelChoice>, chosen: String?, fallback: String?, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(Res.string.value_model_default)
    val summary = when {
        chosen != null -> choices.firstOrNull { it.pick.equals(chosen, ignoreCase = true) }?.name ?: modelChipLabel(chosen)
        !fallback.isNullOrBlank() -> modelChipLabel(fallback)
        else -> defaultLabel
    }
    PopoverLabel(stringResource(Res.string.label_model))
    Row(
        Modifier.fillMaxWidth().padding(bottom = if (open) 6.dp else 14.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp))
            .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(summary, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (chosen == null) Text(defaultLabel, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
        Text(if (open) "⌃" else "›", color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp)
    }
    if (open) {
        Column(Modifier.padding(bottom = 8.dp)) {
            QaOption(defaultLabel, chosen == null, token = fallback?.takeIf { it.isNotBlank() }?.let { modelChipLabel(it) }) { onChoose(null); open = false }
            choices.forEach { c ->
                QaOption(c.name, chosen.equals(c.pick, ignoreCase = true), token = c.ctx.takeIf { it.isNotEmpty() }) { onChoose(c.pick); open = false }
            }
        }
    }
}

// ── quick actions (the chat-header ⋯): model / effort / mode / terminal / compact / clear ──
// Mirrors mobile's QuickActionsSheet so the two shells stay in sync (mobile moved the mode
// switch here off the top bar); drives the same repo verbs via DesktopModel. Model is no longer
// a page here — the row shortcuts to the composer chip's anchored popover (issue #157).
private enum class QaPage { MAIN, EFFORT, MODE }

@Composable
fun QuickActionsPopover(model: DesktopModel, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(QaPage.MAIN) }
    var clearArmed by remember { mutableStateOf(false) }
    LaunchedEffect(model.chatAgent) { model.fetchModels(model.chatAgent) }
    Column(
        Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(14.dp)).padding(15.dp),
    ) {
        when (page) {
            QaPage.MAIN -> {
                PopoverLabel(stringResource(Res.string.quick_actions_title))
                // Model is a plain shortcut now (issue #157): closes this menu and opens the SAME anchored
                // popover the composer chip owns — no second-level page. Hidden while observing: the read-only
                // view has no composer (no chip to anchor at), and you can't drive that session anyway.
                if (!model.observing) {
                    QaRow(stringResource(Res.string.label_model), value = modelChipLabel(model.chatModelId).ifBlank { stringResource(Res.string.value_default) }) { onDismiss(); model.showModelPopover = true }
                }
                if (model.effortOptions().isNotEmpty()) {
                    QaRow(stringResource(Res.string.label_effort), value = model.chatEffort ?: stringResource(Res.string.value_default), chevron = true) { page = QaPage.EFFORT }
                }
                if (model.serviceTierOptions().any { it.id == "priority" }) {
                    QaRow(
                        stringResource(Res.string.fast_mode),
                        value = stringResource(if (model.chatServiceTier == "priority") Res.string.value_on else Res.string.value_off),
                    ) {
                        model.switchServiceTier(if (model.chatServiceTier == "priority") null else "priority")
                        onDismiss()
                    }
                }
                val modeChoices = desktopModeChoices(
                    model.chatAgent,
                    model.chatAgent == AgentKind.CLAUDE && model.permissionModeAvailable(CLAUDE_PERMISSION_MODE_AUTO),
                )
                val activeMode = modeChoices.firstOrNull {
                    it.mode == model.chatMode && it.nativeMode == model.chatPermissionMode
                } ?: modeChoices.first()
                QaRow(stringResource(Res.string.label_mode), value = activeMode.token, chevron = true) { page = QaPage.MODE }
                // canOpen() stats the filesystem — key it on the workdir so it isn't re-run every
                // recomposition (this popover recomposes on every page/arm toggle); same as ChatSubHeader.
                // Routes by the user's default (issue #153): embedded dock unless Settings says external.
                val canOpenTerminal = remember(model.chatWorkdir) { TerminalLauncher.canOpen(model.chatWorkdir) }
                if (canOpenTerminal) {
                    QaRow(stringResource(Res.string.qa_terminal)) { model.openTerminalPreferred(); onDismiss() }
                }
                // "Hand off to a colleague": an ordinary peer row (no NEW badge — available is not
                // recommended, design chat-quick-actions-ui-2.0), only while the session is handoff-free —
                // one non-terminal handoff per session, and the daemon refuses a second anyway
                if (!model.observing && model.activeHandoff == null && model.chatAgent.canInitiateSessionHandoff()) {
                    QaRow(stringResource(Res.string.ho_menu_row)) { onDismiss(); model.showHandoff = true }
                }
                QaRow(stringResource(Res.string.qa_compact)) { model.compactConversation(); onDismiss() }
                QaRow(
                    stringResource(if (clearArmed) Res.string.qa_clear_armed else Res.string.qa_clear), danger = true,
                ) { if (clearArmed) { model.clearConversation(); onDismiss() } else clearArmed = true }
            }
            QaPage.EFFORT -> {
                QaBack(stringResource(Res.string.label_effort)) { page = QaPage.MAIN }
                val defaultLabel = stringResource(Res.string.value_default)
                QaOption(defaultLabel, model.chatEffort == null) { model.switchEffort(null); onDismiss() }
                model.effortOptions().forEach { opt ->
                    QaOption(opt, opt.equals(model.chatEffort, true)) { model.switchEffort(opt); onDismiss() }
                }
            }
            QaPage.MODE -> {
                QaBack(stringResource(Res.string.label_mode)) { page = QaPage.MAIN }
                val choices = desktopModeChoices(
                    model.chatAgent,
                    model.chatAgent == AgentKind.CLAUDE && model.permissionModeAvailable(CLAUDE_PERMISSION_MODE_AUTO),
                )
                choices.forEach { m ->
                    QaOption(
                        stringResource(m.label),
                        m.mode == model.chatMode && m.nativeMode == model.chatPermissionMode,
                        dot = m.dot,
                        danger = m.danger,
                        token = m.token,
                    ) {
                        model.switchMode(m.mode, m.nativeMode)
                        onDismiss()
                    }
                }
            }
        }
    }
}

/**
 * The anchored model popover (issue #157, design model-chip.jsx): the composer chip's target — and the
 * ⋯ Model row's, which now shortcuts here instead of drilling a second-level page. Carries exactly the
 * rows the old ⋯ → Model page did: gateway presets (issue #139) leading when the daemon reports a
 * gateway, the Anthropic aliases, and the custom-id field (issue #54).
 */
@Composable
fun ModelPopover(model: DesktopModel, onDismiss: () -> Unit) {
    Column(
        Modifier.width(340.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(14.dp)).padding(15.dp)
            // the focusable popup owns the keyboard while open — Esc must close from inside it
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
            },
    ) {
        PopoverLabel(stringResource(Res.string.label_model))
        LaunchedEffect(model.chatAgent) { model.fetchModels(model.chatAgent) }
        val options = when (model.chatAgent) {
            AgentKind.CODEX -> model.modelsForAgent(AgentKind.CODEX).ifEmpty { CODEX_MODEL_OPTIONS }.map { it to it }
            // daemon truth or nothing — no static catalog (see SessionSheets' OPTIONS note); the
            // empty state renders below and the custom field still takes a provider/model id
            AgentKind.OPENCODE -> model.modelsForAgent(AgentKind.OPENCODE).map { it to it }
            // KIMI (issue #206): daemon-reported aliases only (from `kimi provider list --json`)
            AgentKind.KIMI -> model.modelsForAgent(AgentKind.KIMI).map { it to it }
            // ZCode (issue #228): daemon-reported ids only, the same contract as Kimi.
            AgentKind.ZCODE -> model.modelsForAgent(AgentKind.ZCODE).map { it to it }
            // DSH (issue #255): no model switching in v1 — dsh picks its own model and the daemon has no
            // switch path, so an empty picker is the truth rather than rows that would never take effect.
            AgentKind.DSH -> emptyList()
            // Claude keeps its static alias rows (labels + the 1M/200K semantics live in the shared
            // table) — the daemon's list for Claude is config-default + the same aliases anyway.
            // claudeRowPick: on a gateway the Opus row degrades to the bare alias (#167/#168).
            AgentKind.CLAUDE -> CLAUDE_MODEL_OPTIONS.map { (label, pick) -> label to claudeRowPick(pick, model.gatewayBaseUrl) }
        }
        if (model.chatAgent == AgentKind.OPENCODE && options.isEmpty()) {
            Text(
                stringResource(Res.string.opencode_models_loading), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        fun isActive(pick: String) = model.chatModelId.equals(pick, true) || model.chatModel.equals(pick, true)
        // gateway model presets (issue #139): mirrors mobile's ModelPicker off the same shared
        // table. Gateway reported by the daemon → the section LEADS (those users pick vendor
        // ids, not Claude aliases); official endpoint → it waits behind a collapsed toggle.
        val gatewayUrl = if (model.chatAgent != AgentKind.CLAUDE) null else model.gatewayBaseUrl
        @Composable
        // #167 ②: prefer the gateway's own list; the shared table degrades to a seed + a way to draw a row
        fun gatewayRows() = gatewayRowsFrom(model.gatewayModels, gatewayUrl).forEach { p ->
            GatewayPresetRow(p, isActive(p.id), suggested = p.matchesGatewayHost(gatewayUrl)) { model.switchModel(p.id); onDismiss() }
        }
        if (gatewayUrl != null) {
            // Issue #167: the Claude aliases LEAD on a gateway — compatible endpoints map
            // opus/sonnet/haiku onto their own tiers, so an alias follows the vendor across
            // generations while a hand-written native id rots (#168). Header keeps the
            // "· host" + live dot (0714 design); the vendor rows drop one group below.
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.model_section_anthropic).uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Text("· ${gatewayHostLabel(gatewayUrl) ?: "?"}", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Dot(Tok.ok, 5.dp)
            }
            Text(
                stringResource(Res.string.model_gateway_alias_note),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        options.forEach { (label, pick) ->
            QaOption(label, isActive(pick)) { model.switchModel(pick); onDismiss() }
        }
        if (gatewayUrl != null) {
            PopoverLabel(stringResource(Res.string.model_gateway_section))
            gatewayRows()
            Text(
                stringResource(Res.string.model_gateway_note),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (gatewayUrl == null && model.chatAgent == AgentKind.CLAUDE) {
            var showGateway by remember { mutableStateOf(false) }
            QaRow(stringResource(Res.string.model_gateway_show), chevron = !showGateway) { showGateway = !showGateway }
            if (showGateway) gatewayRows()
        }
        // custom id (issue #54): third-party gateways route ids the preset list can't know;
        // `--model` takes any string, so pass it through. Enter submits. Prefilled when the
        // session already runs a non-preset id.
        val presetActive = options.any { (_, pick) -> isActive(pick) }
        var custom by remember {
            mutableStateOf(if (!presetActive) model.chatModelId.ifBlank { model.chatModel } else "")
        }
        PopoverLabel(stringResource(Res.string.model_custom_label))
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
        // mid-turn (issue #157): the running turn keeps its model — say the pick lands on the NEXT turn
        if (model.streaming) Text(
            stringResource(Res.string.model_next_turn_note),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** Places the chip's popup ABOVE its anchor with right edges aligned (design model-chip.jsx),
 *  clamped inside the window — the popover reads as growing out of the chip. */
internal class AboveAnchorEndPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
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

/** Gateway preset row (0714 design): 24dp tinted vendor monogram, name with an optional terracotta
 *  "suggested" tick (host names that vendor), mono id underneath, accent ✓ when active. */
@Composable
private fun GatewayPresetRow(p: GatewayModelPreset, active: Boolean, suggested: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Tok.surface else Color.Transparent)
            .then(if (active) Modifier.border(1.dp, Tok.hair, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GatewayVendorMonogram(p, 24.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(p.vendor, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                if (suggested) Text("✓ " + stringResource(Res.string.model_gateway_suggested), color = Tok.accent, fontFamily = Dk.ui, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(p.id, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        if (active) Text("✓", color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.sp)
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
        AutoSizeSingleLineText(
            agentName(agent), fontSize = 13.sp, minFontSize = 9.sp,
            color = if (selected) Tok.tx else Tok.tx2, fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFamily = Dk.ui),
        )
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
