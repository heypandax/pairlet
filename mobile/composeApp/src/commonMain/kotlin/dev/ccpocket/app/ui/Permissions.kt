package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.oneOff
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// ── the autonomy ladder (top = most cautious) ───────────────────
/** [short]/[label]/[detail] are string-resource keys (resolved at render); [tech] is the raw SDK mode name. */
data class ModeInfo(
    val key: PermissionMode, val short: StringResource, val label: StringResource, val tech: String,
    val color: Color, val detail: StringResource, val warn: Boolean = false,
    val nativeMode: String? = null,
)

// same hue as the semantic info token — a getter so it tracks the light/dark palette (#63)
private val Indigo get() = Tok.info

/** Trims a single line's leading and centers the glyph in it — fixes text riding high when vertically centered. */
internal val TightCenter = TextStyle(
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)
val MODES = listOf(
    ModeInfo(PermissionMode.DEFAULT, Res.string.mode_default_short, Res.string.mode_default_label, "default", Tok.tx2, Res.string.mode_default_detail),
    ModeInfo(PermissionMode.ACCEPT_EDITS, Res.string.mode_accept_short, Res.string.mode_accept_label, "acceptEdits", Tok.ok, Res.string.mode_accept_detail),
    ModeInfo(PermissionMode.PLAN, Res.string.mode_plan_short, Res.string.mode_plan_label, "plan", Indigo, Res.string.mode_plan_detail),
    ModeInfo(PermissionMode.BYPASS_PERMISSIONS, Res.string.mode_bypass_short, Res.string.mode_bypass_label, "bypassPermissions", Tok.warn, Res.string.mode_bypass_detail, warn = true),
)
val AUTO_MODE = ModeInfo(
    PermissionMode.DEFAULT,
    Res.string.mode_auto_short,
    Res.string.mode_auto_label,
    CLAUDE_PERMISSION_MODE_AUTO,
    Tok.accent,
    Res.string.mode_auto_detail,
    nativeMode = CLAUDE_PERMISSION_MODE_AUTO,
)
val MODE_BY = MODES.associateBy { it.key }

// ── bottom-sheet shell (scrim + raised card, radius-20 top) ─────
@Composable
fun PocketSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // a sheet has no text input — drop the keyboard if the composer still held focus. Otherwise the open
    // keyboard + the sheet's imePadding fight over the bottom inset and (on iOS) wedge the layout, so a
    // nested confirm popup can't lay out and the keyboard won't dismiss — the "stuck sheet" symptom.
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus() }
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onDismiss() } // Android back = scrim tap
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x94000000)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Tok.raised)
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps so they don't dismiss via the scrim
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding() // sheets render outside the app's ime-padded Box — never hide behind the keyboard
                .padding(bottom = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).size(width = 38.dp, height = 5.dp).clip(CircleShape).background(Tok.hair))
            content()
        }
    }
}

// ── mode-switch sheet (ladder + bypass confirm + switching + rules) ──
@Composable
fun ModeSheet(
    current: PermissionMode, rules: List<String>, switching: Boolean, workdir: String? = null,
    agent: AgentKind? = null, nativeMode: String? = null, autoAvailable: Boolean = false,
    onSelect: (PermissionMode, String?) -> Unit, onClearRule: (String) -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit,
) {
    var confirmBypass by remember { mutableStateOf(false) }
    PocketSheet(onDismiss) {
        if (agent == AgentKind.OPENCODE) {
            // mid-session too the mode is immutable truth, not a choice — see OpenCodeAutoApproveNotice
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 4.dp)) {
                Text(stringResource(Res.string.exec_mode_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OpenCodeAutoApproveNotice(Modifier.padding(top = 12.dp))
            }
        } else if (confirmBypass) {
            BypassConfirm(workdir, onCancel = { confirmBypass = false }, onConfirm = { confirmBypass = false; onSelect(PermissionMode.BYPASS_PERMISSIONS, null) })
        } else {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 4.dp)) {
                Text(stringResource(Res.string.exec_mode_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.exec_mode_subtitle), color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
                if (switching) {
                    Row(
                        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(15.dp), color = Tok.accent, strokeWidth = 2.dp)
                        Text(stringResource(Res.string.mode_switching), color = Tok.tx2, fontSize = 12.5.sp)
                    }
                }
                Column(
                    Modifier.padding(top = 8.dp).alpha(if (switching) 0.55f else 1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (MODES + if (agent == AgentKind.CLAUDE && autoAvailable) listOf(AUTO_MODE) else emptyList()).forEach { m ->
                        ModeRow(m, selected = current == m.key && nativeMode == m.nativeMode, enabled = !switching) {
                            if (m.key == PermissionMode.BYPASS_PERMISSIONS && current != PermissionMode.BYPASS_PERMISSIONS) confirmBypass = true
                            else onSelect(m.key, m.nativeMode)
                        }
                    }
                }
                RulesReview(rules, onClearRule, onClearAll)
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Shield, null, tint = Tok.muted, modifier = Modifier.padding(top = 1.5.dp).size(13.dp))
                    Text(stringResource(Res.string.note_new_sessions_default), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** The guarded "Enable full auto?" confirm, shared by the mode-switch sheet and the new-session picker. */
@Composable
private fun BypassConfirm(workdir: String?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp, top = 6.dp)) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Tok.warn.copy(alpha = 0.14f))
                .border(1.dp, Tok.warn.copy(alpha = 0.4f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.WarningAmber, null, tint = Tok.warn, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(Res.string.bypass_title), color = Tok.tx, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(Res.string.bypass_body),
            color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp),
        )
        // the blast radius in plain sight: which working copy full auto is about to own
        if (workdir != null) TailPathText(workdir, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton(stringResource(Res.string.cancel), Modifier.weight(1f), outline = true, onClick = onCancel)
            SheetButton(stringResource(Res.string.bypass_cta), Modifier.weight(1.4f), bg = Tok.warn, fg = Tok.base, onClick = onConfirm)
        }
    }
}

/**
 * New-session picker — the entry-flow configuration surface (Entry Flow UI 2.0).
 *
 * Kept as the public name every existing call site already routes through; the surface itself now lives in
 * [dev.ccpocket.app.ui.entry.ConfigureSessionSheet], which orders it workdir → Agent → Model → Mode → one
 * explicit Start. The behavioural difference callers must know about: a mode row no longer commits, so
 * [onPick] fires exactly once, from Start (or from the Full-access confirmation), never from a selection.
 *
 * [computer] is the connected machine's display name — the Full-access confirmation names it, because a
 * blast radius is a place. Null simply drops that clause rather than inventing a host.
 */
@Composable
fun StartSessionModeSheet(
    workdir: String? = null,
    selected: PermissionMode = PermissionMode.DEFAULT,
    selectedNativeMode: String? = null,
    agent: AgentKind = AgentKind.CLAUDE,
    computer: String? = null,
    autoAvailable: Boolean = false,
    modelsFor: (AgentKind) -> List<ModelChoice> = { emptyList() },
    defaultModelFor: (AgentKind) -> String? = { null },
    onAgentPicked: (AgentKind) -> Unit = {},
    onPick: (PermissionMode, AgentKind, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) = dev.ccpocket.app.ui.entry.ConfigureSessionSheet(
    workdir = workdir,
    selected = selected,
    selectedNativeMode = selectedNativeMode,
    agent = agent,
    computer = computer,
    autoAvailable = autoAvailable,
    modelsFor = modelsFor,
    defaultModelFor = defaultModelFor,
    onAgentPicked = onAgentPicked,
    onPick = onPick,
    onDismiss = onDismiss,
)

/** The Codex preset behind an agent+mode defaults pair — null for Claude (its modes stand alone). */
fun codexPresetFor(agent: AgentKind, mode: PermissionMode): CodexPreset? =
    if (agent == AgentKind.CODEX) CODEX_PRESETS.first { it.mode == mode } else null

/** What [SessionDefaultsChip] is labeled for [agent]+[mode] — one owner for the rule, shared with tests.
 *  OpenCode is ALWAYS "Full access": the daemon runs it `--auto` (no approval protocol), so showing a
 *  Claude mode name here would claim an approval flow that never happens. */
fun sessionDefaultsLabel(agent: AgentKind, mode: PermissionMode): StringResource = when {
    agent == AgentKind.OPENCODE -> Res.string.opencode_mode_short
    else -> codexPresetFor(agent, mode)?.name ?: MODE_BY.getValue(mode).short
}

/**
 * The honest replacement for a mode ladder on OpenCode surfaces: the daemon launches opencode with
 * `--auto` — every tool call (edits, commands) is CLI-approved and the PermissionBridge is never
 * consulted — so any selectable Cautious/Plan row would be pure theater (the review's "security
 * semantics deception" P0). One immutable full-access card, warn-tinted, states what actually runs.
 */
@Composable
fun OpenCodeAutoApproveNotice(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Tok.warn.copy(alpha = 0.08f))
            .border(1.dp, Tok.warn.copy(alpha = 0.35f), shape).padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.WarningAmber, null, tint = Tok.warn, modifier = Modifier.size(15.dp))
            Text(stringResource(Res.string.opencode_mode_title), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            stringResource(Res.string.opencode_mode_note),
            color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Compact "what you'll get" chip beside the one-tap new-session entries: default agent glyph + the
 *  mode it will start in (Codex shows its preset name), tap → the full [StartSessionModeSheet]. Keeps
 *  the up-front agent/mode choice reachable now that a plain tap starts immediately with the defaults. */
@Composable
fun SessionDefaultsChip(agent: AgentKind, mode: PermissionMode, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val preset = codexPresetFor(agent, mode)
    val label = stringResource(sessionDefaultsLabel(agent, mode))
    val color = when {
        agent == AgentKind.OPENCODE -> Tok.warn // always full access — chip reads as the warning it is
        preset == null -> MODE_BY.getValue(mode).color
        preset.danger -> Tok.danger
        else -> Tok.codex
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentGlyph(agent, agentColor(agent), 13)
        Text(label, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = TightCenter)
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
    }
}

/** A Codex execution preset — the two-axis (approval × sandbox) combination behind a named choice.
 *  [mode] is the PermissionMode the daemon's CodexBackend translates into the actual approvalPolicy + sandbox. */
data class CodexPreset(
    val mode: PermissionMode, val name: StringResource, val desc: StringResource,
    val askChip: StringResource, val fsChip: StringResource,
    val recommended: Boolean = false, val danger: Boolean = false,
)

val CODEX_PRESETS = listOf(
    CodexPreset(PermissionMode.PLAN, Res.string.codex_preset_cautious, Res.string.codex_preset_cautious_desc, Res.string.codex_chip_ask_every, Res.string.codex_chip_fs_read),
    CodexPreset(PermissionMode.DEFAULT, Res.string.codex_preset_balanced, Res.string.codex_preset_balanced_desc, Res.string.codex_chip_ask_needed, Res.string.codex_chip_fs_workspace, recommended = true),
    CodexPreset(PermissionMode.ACCEPT_EDITS, Res.string.codex_preset_autonomous, Res.string.codex_preset_autonomous_desc, Res.string.codex_chip_ask_never, Res.string.codex_chip_fs_workspace),
    CodexPreset(PermissionMode.BYPASS_PERMISSIONS, Res.string.codex_preset_full, Res.string.codex_preset_full_desc, Res.string.codex_chip_ask_never, Res.string.codex_chip_fs_full, danger = true),
)

/** A small monospace chip ("ask: never" / "fs: full") echoing the underlying axes of a Codex preset. */
@Composable
fun MonoChip(text: String, c: Color = Tok.tx2) {
    Text(
        text, color = c, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
        modifier = Modifier.background(Tok.surface, RoundedCornerShape(6.dp))
            .border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ModeRow(m: ModeInfo, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (selected) Modifier.background(m.color.copy(alpha = 0.10f)).border(1.dp, m.color.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            // dot + label share one CenterVertically row, so the dot tracks the first line exactly
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(m.color))
                Text(stringResource(m.label), color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (m.warn) Icon(Icons.Rounded.WarningAmber, null, tint = m.color, modifier = Modifier.size(13.dp))
                if (selected) Icon(Icons.Rounded.Check, null, tint = m.color, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.padding(start = 23.dp, top = 4.dp)) { // 11 dot + 12 gap → aligns under the label
                Text(m.tech, color = m.color, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                Text(stringResource(m.detail), color = Tok.tx2, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun RulesReview(rules: List<String>, onClear: (String) -> Unit, onClearAll: () -> Unit) {
    if (rules.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(Modifier.padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.rules_remembered_header), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.clear_all), color = Tok.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onClearAll() }.padding(4.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rules.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(13.dp))
                    Text(r, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(Tok.raised).border(1.dp, Tok.hair, CircleShape).clickable { onClear(r) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, null, tint = Tok.tx2, modifier = Modifier.size(11.dp)) }
                }
            }
        }
    }
}

/**
 * A tool token that RUNS A COMMAND. The daemon normalizes every backend onto Claude-shaped names
 * (ToolMeta synthesizes "Bash" for Codex too), so this is one token in practice — the tolerant match is
 * for a backend whose naming drifts, because getting this wrong the SAFE way (treating something as a
 * shell) only costs an extra confirmation.
 */
fun isShellTool(tool: String): Boolean = tool.lowercase() in setOf(
    "bash", "shell", "local_shell", "exec_command", "execute_command", "run_command",
)

/** M3 advisory risk badge (design frame 4 `.rbg`): the four states stay distinguishable by SHAPE, not
 *  only color — HIGH is a solid danger fill ("Risk found"), UNKNOWN a dashed-feel outline with "?"
 *  ("Not assessed"): finding risk and failing to assess are different facts (SMART-APPROVAL §八). */
@Composable
fun RiskBadge(risk: String) {
    class Style(val label: String, val fg: Color, val bg: Color, val outline: Color?)
    val warningInk = if (Tok.current.dark) Tok.warn else Color(0xFF7A4F07)
    val s = when (risk.lowercase()) {
        "high" -> Style(stringResource(Res.string.risk_high), Tok.base, Tok.danger, null)
        "medium" -> Style(stringResource(Res.string.risk_medium), warningInk, Tok.warn.copy(alpha = 0.12f), warningInk.copy(alpha = 0.72f))
        "low" -> Style(stringResource(Res.string.risk_low), Tok.tx2, Color.Transparent, Tok.hair)
        else -> Style("? " + stringResource(Res.string.risk_unknown), Tok.tx2, Color.Transparent, Tok.muted)
    }
    Text(
        s.label, color = s.fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(s.bg)
            .then(s.outline?.let { Modifier.border(1.dp, it, RoundedCornerShape(6.dp)) } ?: Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}


// ── confirmation chip that drops into the message stream ────────
@Composable
fun AllowChip(rule: String) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).padding(start = 11.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Tok.ok.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(11.dp))
        }
        Row {
            // suffix is empty in languages where the scope reads naturally up front (e.g. zh)
            val suffix = stringResource(Res.string.allow_chip_suffix)
            Text(stringResource(Res.string.allow_chip_prefix) + " ", color = Tok.tx2, fontSize = 12.5.sp)
            Text(rule, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
            if (suffix.isNotBlank()) Text(" $suffix", color = Tok.tx2, fontSize = 12.5.sp)
        }
    }
}

/** Approval design M2 §9.6 (design `.achip`): the light in-stream audit row for a grant-covered auto-run —
 *  glyph + redacted mono summary + basis pill + a trailing 收紧 link that revokes the grant (or clears the
 *  session rule) so the NEXT matching action asks again. Deliberately quieter than a tool card. */
@Composable
fun AutoRunChip(item: dev.ccpocket.app.data.ChatItem.AutoRun, onTighten: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
            .padding(start = 11.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("⚡", fontSize = 11.sp)
        Text(stringResource(Res.string.autorun_label), color = Tok.tx2, fontSize = 11.5.sp)
        Text(item.summary, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Text(
            stringResource(if (item.basis == "task-grant") Res.string.autorun_basis_task else Res.string.autorun_basis_session),
            color = Tok.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        if (item.tightening) {
            Text("…", color = Tok.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
        } else if (!item.tightened) {
            Text(
                stringResource(Res.string.autorun_tighten), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTighten).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        } else {
            Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
internal fun SheetButton(label: String, modifier: Modifier, bg: Color = Color.Transparent, fg: Color = Tok.tx, outline: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(52.dp).clip(shape).background(bg)
            .then(if (outline) Modifier.border(1.dp, Tok.hair, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.Bold) }
}
