package dev.ccpocket.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentBadge
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.agentTintBorder
import dev.ccpocket.app.ui.agentTintFill
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.oneOff

/** Countdown ring — a hairline track with a [fraction] arc in [color] (terracotta, ambering as it nears 0). */
@Composable
fun CountdownRing(diameter: Dp, stroke: Dp, color: Color, fraction: Float = 0.72f) {
    Canvas(Modifier.size(diameter)) {
        val sw = stroke.toPx()
        val arc = Size(size.width - sw, size.height - sw)
        val tl = Offset(sw / 2f, sw / 2f)
        drawArc(Tok.hair, 0f, 360f, false, topLeft = tl, size = arc, style = Stroke(sw))
        drawArc(color, -90f, -360f * fraction, false, topLeft = tl, size = arc, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun ShieldChip(color: Color, box: Dp = 34.dp, glyph: Dp = 18.dp) {
    Box(
        Modifier.size(box).clip(RoundedCornerShape(9.dp)).background(color.agentTintFill())
            .border(1.dp, color.agentTintBorder(), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Shield, null, tint = color, modifier = Modifier.size(glyph)) }
}

@Composable
private fun RememberCheck(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) Tok.accent else Color.Transparent)
                .border(1.5.dp, if (checked) Tok.accent else Tok.muted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", color = Tok.base, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Text(label, color = if (checked) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp)
    }
}

/** Whether "remember" is offered at all: needs a rule to remember, and one-off decisions (plan
 *  approval, questions) never offer it — [oneOff] carries the daemon's ToolMeta policy. */
private fun canRemember(ask: PermissionAsk): Boolean = ask.rule != null && !ask.oneOff

@Composable
private fun DenyButton(big: Boolean = false, onClick: () -> Unit) {
    Text(
        "Deny", color = Tok.danger, fontFamily = Dk.ui, fontSize = if (big) 13.5.sp else 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(if (big) 10.dp else 9.dp))
            .border(1.dp, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(if (big) 10.dp else 9.dp))
            .clickable(onClick = onClick).padding(horizontal = if (big) 18.dp else 16.dp, vertical = if (big) 10.dp else 8.dp),
    )
}

@Composable
private fun AllowButton(big: Boolean = false, key: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(if (big) 10.dp else 9.dp)).background(Tok.accent).clickable(onClick = onClick)
            .padding(horizontal = if (big) 18.dp else 16.dp, vertical = if (big) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Allow", color = Tok.base, fontFamily = Dk.ui, fontSize = if (big) 13.5.sp else 13.sp, fontWeight = FontWeight.Bold)
        if (key) Key("⌘⏎")
    }
}

@Composable
private fun DirBranchLine(workdir: String, branch: String?, fontSize: Float = 10.5f) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(workdir, color = Tok.muted, fontFamily = Dk.mono, fontSize = fontSize.sp)
        if (branch != null) {
            Text("·", color = Tok.muted, fontFamily = Dk.mono, fontSize = fontSize.sp)
            Text("⑂ $branch", color = Tok.muted, fontFamily = Dk.mono, fontSize = fontSize.sp)
        }
    }
}

@Composable
private fun CommandBox(text: String, fontSize: Float = 12f) {
    Text(
        text, color = Tok.tx, fontFamily = Dk.mono, fontSize = fontSize.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
    )
}

/** A unified diff (added green / removed red), scrollable + height-capped. */
@Composable
private fun DiffView(diff: String) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(9.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
    ) {
        diff.lines().forEach { l ->
            val sign = l.firstOrNull() ?: ' '
            val bg = when (sign) { '+' -> Tok.ok.copy(alpha = 0.12f); '-' -> Tok.danger.copy(alpha = 0.12f); else -> Color.Transparent }
            val c = when (sign) { '+' -> Tok.ok; '-' -> Tok.danger; else -> Tok.tx2 }
            Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (sign == ' ') "" else sign.toString(), color = c, fontFamily = Dk.mono, fontSize = 12.sp, modifier = Modifier.width(8.dp))
                Text(l.drop(1), color = if (sign == ' ') Tok.tx2 else c, fontFamily = Dk.mono, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
    }
}

/**
 * Inline approval card embedded in the chat stream — command box, or a diff when [PermissionAsk.diff] is set.
 * On the daemon's TIMED_OUT signal ([timedOut], issue #100) it flips to a terminal state: the request greys
 * out and Allow/Deny give way to an "Auto-denied (no response)" danger note + Dismiss — so a returning user
 * sees what happened instead of a card that still looks live, and a late click can no longer fire a verdict
 * the CLI already stopped waiting for (which the daemon would only answer with `ask_expired`). Mirrors the
 * phone's [dev.ccpocket.app.ui.PermissionSheet] terminal block.
 */
@Composable
fun InlinePermCard(
    ask: PermissionAsk,
    agent: AgentKind,
    workdir: String,
    branch: String?,
    onAllow: (remember: Boolean) -> Unit,
    onDeny: () -> Unit,
    timedOut: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val color = agentColor(agent)
    val isDiff = ask.diff != null
    var rememberRule by remember(ask.askId) { mutableStateOf(false) }
    val bodyAlpha = if (timedOut) 0.5f else 1f // grey the (now inert) request, like the phone's terminal sheet
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(15.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.alpha(bodyAlpha)) { ShieldChip(color) }
            Column(Modifier.weight(1f)) {
                Column(Modifier.alpha(bodyAlpha)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            if (isDiff) "${agentName(agent)} wants to edit files" else "${agentName(agent)} needs permission",
                            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
                        )
                        AgentBadge(agent)
                    }
                    if (isDiff) {
                        val diff = ask.diff!!
                        val added = diff.lines().count { it.startsWith("+") }
                        val removed = diff.lines().count { it.startsWith("-") }
                        Row(Modifier.padding(top = 3.dp, bottom = 11.dp)) {
                            Text(ask.inputPreview + " ", color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.5.sp)
                            Text("+$added", color = Tok.ok, fontFamily = Dk.mono, fontSize = 12.5.sp)
                            Text(" −$removed", color = Tok.danger, fontFamily = Dk.mono, fontSize = 12.5.sp)
                        }
                        DiffView(diff)
                    } else {
                        Text(
                            "${ask.title} · ${ask.tool}".trim().removePrefix("· "),
                            color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                        )
                        CommandBox(ask.inputPreview)
                        Spacer(Modifier.size(10.dp))
                        DirBranchLine(workdir, branch)
                    }
                }
                Spacer(Modifier.size(12.dp))
                if (timedOut) {
                    TimedOutBlock(onDismiss)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (canRemember(ask)) RememberCheck("Remember for this session", rememberRule) { rememberRule = !rememberRule }
                        Spacer(Modifier.weight(1f))
                        CountdownRing(26.dp, 2.2.dp, color)
                        DenyButton(onClick = onDeny)
                        AllowButton(key = !isDiff, onClick = { onAllow(rememberRule) })
                    }
                }
            }
        }
    }
}

/** The inline card's terminal "timed out" state (issue #100): a danger note that the ask auto-denied for want
 *  of a response, with a Dismiss that just retires the (now inert) card — no verdict is sent. All tokens are
 *  theme-aware ([Tok]), so it reads in both light and dark. Copy mirrors the phone's PermissionSheet block. */
@Composable
private fun TimedOutBlock(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.danger.copy(alpha = 0.08f))
            .border(1.dp, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(Tok.danger))
        Column(Modifier.weight(1f)) {
            Text("Auto-denied (no response)", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("No response within the time limit.", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp)
        }
        Text(
            "Dismiss", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Focused permission modal — shown over the live shell (scrim dims it) when an approval arrives from a
 * tray / notification deep-link with the window in the background. Names the computer at the top.
 */
@Composable
fun FocusedModal(computer: String, ask: PermissionAsk, agent: AgentKind, workdir: String, branch: String?, onAllow: (remember: Boolean) -> Unit, onDeny: () -> Unit, onDismiss: () -> Unit) {
    val color = agentColor(agent)
    var rememberRule by remember(ask.askId) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF08090A).copy(alpha = 0.66f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Tok.raised)
                .border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).clickable(enabled = false) {},
        ) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("on $computer", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp)
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    ShieldChip(color, box = 40.dp, glyph = 21.dp)
                    Column(Modifier.weight(1f)) {
                        Text("${agentName(agent)} needs permission", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp)
                        Text("${ask.title} · ${ask.tool}".trim().removePrefix("· "), color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    CountdownRing(34.dp, 2.6.dp, color)
                }
                Spacer(Modifier.size(14.dp))
                CommandBox(ask.inputPreview, fontSize = 12.5f)
                Spacer(Modifier.size(11.dp))
                DirBranchLine(workdir, branch, 11f)
                Spacer(Modifier.size(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (canRemember(ask)) RememberCheck("Remember for this session", rememberRule) { rememberRule = !rememberRule }
                    Spacer(Modifier.weight(1f))
                    DenyButton(big = true, onClick = onDeny)
                    AllowButton(big = true, onClick = { onAllow(rememberRule) })
                }
            }
        }
    }
}
