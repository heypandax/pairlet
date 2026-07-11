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
    onSelect: (PermissionMode) -> Unit, onClearRule: (String) -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit,
) {
    var confirmBypass by remember { mutableStateOf(false) }
    PocketSheet(onDismiss) {
        if (confirmBypass) {
            BypassConfirm(workdir, onCancel = { confirmBypass = false }, onConfirm = { confirmBypass = false; onSelect(PermissionMode.BYPASS_PERMISSIONS) })
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
                    MODES.forEach { m ->
                        ModeRow(m, selected = current == m.key, enabled = !switching) {
                            if (m.key == PermissionMode.BYPASS_PERMISSIONS && current != PermissionMode.BYPASS_PERMISSIONS) confirmBypass = true
                            else onSelect(m.key)
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

/** New-session picker: choose the agent backend + execution mode up front. Defaults stay safe; Full auto still confirms. */
@Composable
fun StartSessionModeSheet(
    workdir: String? = null,
    selected: PermissionMode = PermissionMode.DEFAULT,
    agent: AgentKind = AgentKind.CLAUDE,
    onPick: (PermissionMode, AgentKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmBypass by remember { mutableStateOf(false) }
    var chosenAgent by remember { mutableStateOf(agent) }
    PocketSheet(onDismiss) {
        if (confirmBypass) {
            BypassConfirm(workdir, onCancel = { confirmBypass = false }, onConfirm = { onPick(PermissionMode.BYPASS_PERMISSIONS, chosenAgent) })
        } else {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 4.dp)) {
                Text(stringResource(Res.string.new_session_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.new_session_subtitle), color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
                SectionLabel(stringResource(Res.string.label_agent))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentOption(AgentKind.CLAUDE, chosenAgent == AgentKind.CLAUDE, Modifier.weight(1f)) { chosenAgent = AgentKind.CLAUDE }
                    AgentOption(AgentKind.CODEX, chosenAgent == AgentKind.CODEX, Modifier.weight(1f)) { chosenAgent = AgentKind.CODEX }
                }
                SectionLabel(stringResource(Res.string.label_mode))
                if (chosenAgent == AgentKind.CLAUDE) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MODES.forEach { m ->
                            ModeRow(m, selected = m.key == selected, enabled = true) {
                                if (m.key == PermissionMode.BYPASS_PERMISSIONS) confirmBypass = true else onPick(m.key, AgentKind.CLAUDE)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CODEX_PRESETS.forEach { p ->
                            PresetRow(p, selected = p.mode == selected) {
                                if (p.danger) confirmBypass = true else onPick(p.mode, AgentKind.CODEX)
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Shield, null, tint = Tok.muted, modifier = Modifier.padding(top = 1.5.dp).size(13.dp))
                    Text(stringResource(Res.string.note_change_anytime), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** The Codex preset behind an agent+mode defaults pair — null for Claude (its modes stand alone). */
fun codexPresetFor(agent: AgentKind, mode: PermissionMode): CodexPreset? =
    if (agent == AgentKind.CODEX) CODEX_PRESETS.first { it.mode == mode } else null

/** What [SessionDefaultsChip] is labeled for [agent]+[mode] — one owner for the rule, shared with tests. */
fun sessionDefaultsLabel(agent: AgentKind, mode: PermissionMode): StringResource =
    codexPresetFor(agent, mode)?.name ?: MODE_BY.getValue(mode).short

/** Compact "what you'll get" chip beside the one-tap new-session entries: default agent glyph + the
 *  mode it will start in (Codex shows its preset name), tap → the full [StartSessionModeSheet]. Keeps
 *  the up-front agent/mode choice reachable now that a plain tap starts immediately with the defaults. */
@Composable
fun SessionDefaultsChip(agent: AgentKind, mode: PermissionMode, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val preset = codexPresetFor(agent, mode)
    val label = stringResource(sessionDefaultsLabel(agent, mode))
    val color = when { preset == null -> MODE_BY.getValue(mode).color; preset.danger -> Tok.danger; else -> Tok.codex }
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

/** Uppercase section label inside the new-session sheet ("Agent" / "Mode"). */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** An agent choice card: glyph + name + tagline; selected → tinted in the agent's identity color with a check. */
@Composable
private fun AgentOption(agent: AgentKind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = agentColor(agent)
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier.clip(shape)
            .background(if (selected) c.agentTintFill() else Tok.surface)
            .border(1.5.dp, if (selected) c else Tok.hair, shape)
            .clickable(onClick = onClick).padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) c.agentTintFill() else Tok.raised)
                    .border(1.dp, if (selected) c.agentTintBorder() else Tok.hair, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { AgentGlyph(agent, c, 17) }
            Text(agentName(agent), color = Tok.tx, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, style = TightCenter, modifier = Modifier.weight(1f))
            if (selected) Box(Modifier.size(18.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Tok.base, modifier = Modifier.size(13.dp))
            }
        }
        Text(agentTagline(agent), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp))
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

/** One Codex preset row: name + RECOMMENDED/warning + plain-language desc + the two raw-axis mono chips. */
@Composable
private fun PresetRow(p: CodexPreset, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val outline = if (p.danger) Tok.danger else if (selected) Tok.codex else Tok.hair
    val fill = when {
        !selected -> Tok.surface
        p.danger -> Tok.danger.copy(alpha = 0.07f)
        else -> Tok.codex.copy(alpha = 0.12f)
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(fill).border(1.5.dp, outline, shape)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(p.name), color = if (p.danger) Tok.danger else Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (p.danger) Icon(Icons.Rounded.WarningAmber, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
            if (p.recommended) Text(
                stringResource(Res.string.recommended_badge), color = Tok.codex, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, Tok.codex.copy(alpha = 0.42f), RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, null, tint = if (p.danger) Tok.danger else Tok.codex, modifier = Modifier.size(15.dp))
        }
        Text(stringResource(p.desc), color = Tok.tx2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // both axes are only ever "dangerous" on the Full-auto preset, so a single danger flag drives the color
            val chipColor = if (p.danger) Tok.danger else Tok.tx2
            MonoChip(stringResource(p.askChip), chipColor)
            MonoChip(stringResource(p.fsChip), chipColor)
        }
    }
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

// ── upgraded permission sheet (3-way + remember) ────────────────
@Composable
fun PermissionSheet(
    ask: PermissionAsk, workdir: String?, timedOutSignal: Boolean = false,
    onDeny: () -> Unit, onOnce: () -> Unit, onAlways: () -> Unit, onDismiss: () -> Unit,
) {
    var seconds by remember(ask.askId) { mutableStateOf(ask.total()) }
    LaunchedEffect(ask.askId) {
        seconds = ask.total()
        while (seconds > 0) { delay(1000); seconds -= 1 }
    }
    // issue #100: the daemon's authoritative TIMED_OUT signal flips the card to its terminal state even when the
    // local countdown never ran (phone backgrounded/locked — the real scenario). The countdown is now just the
    // fallback for a pre-#100 daemon, and it counts against the daemon's real window (timeoutSec) not a fixed 30s.
    val timedOut = timedOutSignal || seconds <= 0
    PocketSheet(onDismiss = { if (timedOut) onDismiss() else onDeny() }) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp, top = 2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).alpha(if (timedOut) 0.5f else 1f)) { PermBody(ask, workdir) }
                if (!timedOut) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        CountdownRing(seconds, ask.total())
                        Text(stringResource(Res.string.auto_deny), color = Tok.muted, fontSize = 10.sp)
                    }
                }
            }
            if (timedOut) {
                Row(
                    Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.danger.copy(alpha = 0.08f)).border(1.dp, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Tok.danger))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.auto_denied_title), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.auto_denied_body), color = Tok.tx2, fontSize = 12.sp)
                    }
                    Text(stringResource(Res.string.dismiss), color = Tok.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onDismiss() }.padding(6.dp))
                }
            } else {
                Decision(ask, onDeny, onOnce, onAlways)
            }
        }
    }
}

// issue #100: count the local no-response fallback against the daemon's REAL window; a pre-#100 daemon omits
// timeoutSec → keep the legacy 30s so this phone still matches that daemon's 30s auto-deny.
private fun PermissionAsk.total() = timeoutSec ?: 30

@Composable
private fun PermBody(ask: PermissionAsk, workdir: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.Shield, null, tint = if (ask.danger) Tok.danger else Tok.warn, modifier = Modifier.size(16.dp))
            Text(stringResource(Res.string.needs_permission), color = Tok.tx2, fontSize = 13.sp)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(ask.title.ifBlank { stringResource(Res.string.permission_fallback) }, color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(" · ", color = Tok.muted, fontSize = 18.sp)
            Text(ask.tool, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        val diff = ask.diff
        when {
            diff != null -> // Codex file-change approval → render the patch as +/- lines
                DiffView(diff, Modifier.padding(top = 12.dp))
            ask.tool == "ExitPlanMode" || ask.tool == "exit_plan_mode" -> // plan approval → render the full plan as scrollable markdown (issue #10)
                Column(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.base)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                        .heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                ) {
                    MarkdownText(ask.inputPreview, Tok.tx)
                }
            else ->
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(ask.inputPreview, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 6)
                }
        }
        if (workdir != null) {
            TailPathText(workdir, fontSize = 11.5.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** A line-based unified-diff viewer for Codex patch approvals: added lines green, removed red, scrollable + truncated. */
@Composable
private fun DiffView(diff: String, modifier: Modifier = Modifier) {
    val all = remember(diff) { diff.lines() }
    val shown = remember(diff) { all.take(140) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
    ) {
        shown.forEach { ln ->
            val sign = ln.firstOrNull()
            val bg = when (sign) { '+' -> Tok.ok.copy(alpha = 0.12f); '-' -> Tok.danger.copy(alpha = 0.12f); else -> Color.Transparent }
            val col = when (sign) { '+' -> Tok.ok; '-' -> Tok.danger; else -> Tok.tx2 }
            Text(
                ln.ifEmpty { " " }, color = col, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp),
            )
        }
        if (all.size > shown.size) {
            Text(
                stringResource(Res.string.diff_more_lines, all.size - shown.size), color = Tok.codex, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Decision(ask: PermissionAsk, onDeny: () -> Unit, onOnce: () -> Unit, onAlways: () -> Unit) {
    if (ask.oneOff) {
        // A one-off human decision (plan approval etc.) — never "Always allow"; the flag rides the ask
        // from the daemon's ToolMeta (issue #10), with a legacy tool-name fallback inside oneOff.
        Row(Modifier.padding(top = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecisionButton(stringResource(Res.string.deny), Modifier.weight(1f).fillMaxHeight(), outline = Tok.danger, fg = Tok.danger, onClick = onDeny)
            DecisionButton(stringResource(Res.string.allow_once), Modifier.weight(1f).fillMaxHeight(), bg = Tok.accent, fg = Tok.base, bold = true, onClick = onOnce)
        }
        return
    }
    val danger = ask.danger
    Column(Modifier.padding(top = 16.dp)) {
        Row(Modifier.padding(bottom = 11.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(
                if (danger) Icons.Rounded.WarningAmber else Icons.Rounded.Check, null,
                tint = if (danger) Tok.warn else Tok.muted, modifier = Modifier.padding(top = 1.dp).size(14.dp),
            )
            Text(
                if (danger) stringResource(Res.string.always_allow_danger, ask.dangerNote ?: stringResource(Res.string.act_freely))
                else stringResource(Res.string.always_allow_scope, ask.rule ?: ask.tool),
                color = if (danger) Tok.warn else Tok.tx2, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        // IntrinsicSize.Min + fillMaxHeight: the rule subtitle makes "Always allow" taller — stretch all three to match
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecisionButton(stringResource(Res.string.deny), Modifier.weight(1f).fillMaxHeight(), outline = Tok.danger, fg = Tok.danger, onClick = onDeny)
            DecisionButton(stringResource(Res.string.allow_once), Modifier.weight(1f).fillMaxHeight(), bg = if (danger) Tok.accent else Tok.surface, outline = if (danger) null else Tok.hair, fg = if (danger) Tok.base else Tok.tx, bold = danger, onClick = onOnce)
            DecisionButton(
                stringResource(Res.string.always_allow), Modifier.weight(1.25f).fillMaxHeight(), sub = ask.rule ?: ask.tool,
                bg = if (danger) Color.Transparent else Tok.accent, outline = if (danger) Tok.warn.copy(alpha = 0.6f) else null,
                fg = if (danger) Tok.warn else Tok.base, warn = danger, bold = true, onClick = onAlways,
            )
        }
    }
}

@Composable
private fun DecisionButton(
    label: String, modifier: Modifier, bg: Color = Color.Transparent, outline: Color? = null, fg: Color = Tok.tx,
    sub: String? = null, bold: Boolean = false, warn: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.heightIn(54.dp).clip(shape).background(bg)
            .then(if (outline != null) Modifier.border(if (warn) 1.5.dp else 1.dp, outline, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (warn) Icon(Icons.Rounded.WarningAmber, null, tint = fg, modifier = Modifier.size(12.dp))
            Text(label, color = fg, fontSize = if (sub != null) 14.sp else 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
        }
        if (sub != null) Text(sub, color = fg.copy(alpha = 0.78f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun CountdownRing(seconds: Int, total: Int) {
    val frac = (seconds.toFloat() / total).coerceIn(0f, 1f)
    val col = if (seconds <= 5) Tok.danger else Tok.accent
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(50.dp)) {
            val sw = 3.dp.toPx()
            val r = (size.minDimension - sw) / 2f
            val tl = Offset((size.width - 2 * r) / 2f, (size.height - 2 * r) / 2f)
            drawCircle(Tok.hair, r, style = Stroke(sw))
            drawArc(col, -90f, 360f * frac, useCenter = false, topLeft = tl, size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = col, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
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
