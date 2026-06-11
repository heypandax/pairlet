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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// ── the autonomy ladder (top = most cautious) ───────────────────
/** [short]/[label]/[detail] are string-resource keys (resolved at render); [tech] is the raw SDK mode name. */
data class ModeInfo(
    val key: PermissionMode, val short: StringResource, val label: StringResource, val tech: String,
    val color: Color, val detail: StringResource, val warn: Boolean = false,
)

private val Indigo = Color(0xFF5B9BD5)

/** Trims a single line's leading and centers the glyph in it — fixes text riding high when vertically centered. */
private val TightCenter = TextStyle(
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

// ── persistent mode badge (chat header) ─────────────────────────
@Composable
fun ModeBadge(mode: PermissionMode, rules: Int, onClick: () -> Unit) {
    val m = MODE_BY[mode] ?: MODES[0]
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.height(28.dp).clip(shape).background(m.color.copy(alpha = 0.12f))
            .border(1.dp, m.color.copy(alpha = 0.32f), shape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (m.warn) Icon(Icons.Rounded.WarningAmber, null, tint = m.color, modifier = Modifier.size(13.dp))
        else Box(Modifier.size(6.dp).clip(CircleShape).background(m.color))
        Text(stringResource(m.short), color = m.color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, lineHeight = 12.5.sp, style = TightCenter)
        if (rules > 0) Text(pluralStringResource(Res.plurals.mode_rules, rules, rules), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, lineHeight = 11.sp, style = TightCenter)
        ChevronDown(m.color.copy(alpha = 0.85f), 12.dp)
    }
}

/** A tight, centered down-chevron — the Material icon carries internal whitespace that unbalances the pill. */
@Composable
private fun ChevronDown(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = (w * 0.14f).coerceAtLeast(1.4f)
        drawLine(color, Offset(w * 0.22f, h * 0.40f), Offset(w * 0.5f, h * 0.61f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, h * 0.40f), Offset(w * 0.5f, h * 0.61f), strokeWidth = sw, cap = StrokeCap.Round)
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

/** New-session mode picker: choose the execution mode up front. Defaults stay safe; Full auto still confirms. */
@Composable
fun StartSessionModeSheet(workdir: String? = null, onPick: (PermissionMode) -> Unit, onDismiss: () -> Unit) {
    var confirmBypass by remember { mutableStateOf(false) }
    PocketSheet(onDismiss) {
        if (confirmBypass) {
            BypassConfirm(workdir, onCancel = { confirmBypass = false }, onConfirm = { onPick(PermissionMode.BYPASS_PERMISSIONS) })
        } else {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 4.dp)) {
                Text(stringResource(Res.string.new_session_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.new_session_subtitle), color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MODES.forEach { m ->
                        ModeRow(m, selected = false, enabled = true) {
                            if (m.key == PermissionMode.BYPASS_PERMISSIONS) confirmBypass = true else onPick(m.key)
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
    ask: PermissionAsk, workdir: String?,
    onDeny: () -> Unit, onOnce: () -> Unit, onAlways: () -> Unit, onDismiss: () -> Unit,
) {
    var seconds by remember(ask.askId) { mutableStateOf(ask.total()) }
    LaunchedEffect(ask.askId) {
        seconds = ask.total()
        while (seconds > 0) { delay(1000); seconds -= 1 }
    }
    val timedOut = seconds <= 0
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

private fun PermissionAsk.total() = 30

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
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(ask.inputPreview, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 6)
        }
        if (workdir != null) {
            TailPathText(workdir, fontSize = 11.5.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun Decision(ask: PermissionAsk, onDeny: () -> Unit, onOnce: () -> Unit, onAlways: () -> Unit) {
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
private fun SheetButton(label: String, modifier: Modifier, bg: Color = Color.Transparent, fg: Color = Tok.tx, outline: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(52.dp).clip(shape).background(bg)
            .then(if (outline) Modifier.border(1.dp, Tok.hair, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.Bold) }
}
