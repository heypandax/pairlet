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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.protocol.AgentKind

/**
 * The tray popover — the title-bar status dot's "what needs me / what's running" glance, wired to the live
 * [DesktopModel] (issue #111; it was a static mockup that showed the developer's OWN machine names and
 * placeholder sessions to every user, so Deny/Allow did nothing and the header count was invented).
 *
 * Both lists aggregate across EVERY connected computer — the same cross-machine folds the bell's
 * [AttentionPopover] and the sidebar's RUNNING zone read — because the tray is a fleet-wide summary, not a
 * single-machine view. Allow/Deny ride [DesktopModel.resolveAttention], the SAME repo verdict the inline
 * permission card and the phone use, so a decision taken here is real. The running list is `model.running`
 * (not `runningVisible`): the tray shows no pins, so nothing should be deduped out of a complete glance.
 *
 * [onOpenMain] raises/focuses the main window (a no-op for the seed/preview model, which is windowless). Each
 * section caps at a few rows so the popover stays a glance; the remainder routes to the full surface (the
 * bell / the sidebar) through a "+N more" row.
 */
@Composable
fun TrayPopover(model: DesktopModel, onOpenMain: () -> Unit = {}, modifier: Modifier = Modifier) {
    val approvals = model.attention
    val running = model.running
    val (computers, sessions) = trayHeaderCounts(model)
    // "Open cc-pocket" and every row click dismiss the popover and surface the window.
    val openMain = { model.showTray = false; onOpenMain() }
    Column(modifier.width(360.dp)) {
        // little pointer up toward the menu-bar icon
        Canvas(Modifier.width(360.dp).height(7.dp)) {
            val w = 14f; val cx = size.width / 2f
            val p = Path().apply { moveTo(cx - w, size.height); lineTo(cx, 0f); lineTo(cx + w, size.height); close() }
            drawPath(p, Tok.raised)
            drawLine(Tok.hair, Offset(cx - w, size.height), Offset(cx, 0f), strokeWidth = 1f, cap = StrokeCap.Round)
            drawLine(Tok.hair, Offset(cx, 0f), Offset(cx + w, size.height), strokeWidth = 1f, cap = StrokeCap.Round)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                PulseDot(Tok.ok, 6.dp)
                Spacer(Modifier.weight(1f))
                Text(trayStatsLabel(computers, sessions), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.padding(14.dp)) {
                TrayGroupLabel("Pending approvals")
                if (approvals.isEmpty()) {
                    TrayEmpty(Icons.Rounded.Check, Tok.ok, "No pending approvals")
                } else {
                    val (shown, hidden) = trayVisible(approvals, TRAY_MAX_APPROVALS)
                    shown.forEach { a ->
                        TrayApprovalRow(
                            a,
                            onDeny = { model.resolveAttention(a, allow = false) },
                            onAllow = { model.resolveAttention(a, allow = true) },
                            onOpen = { openMain(); jumpToMachine(model, a.accountId) },
                        )
                    }
                    // overflow → the bell popover, which lists the whole fleet attention queue
                    TrayMore(hidden, "waiting") { openMain(); model.showAttention = true }
                }
                Spacer(Modifier.height(14.dp))
                TrayGroupLabel("Running sessions")
                if (running.isEmpty()) {
                    TrayEmpty(null, Tok.muted, "No running sessions")
                } else {
                    val (shown, hidden) = trayVisible(running, TRAY_MAX_RUNNING)
                    shown.forEach { (m, p) ->
                        TrayRunning(p.name, m.computer.name) { openMain(); model.openRunning(m, p) }
                    }
                    TrayMore(hidden, "running") { openMain() }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Open cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).clickable(onClick = openMain).padding(vertical = 8.dp),
                )
                Icon(
                    Icons.Outlined.Settings, "Settings", tint = Tok.tx2,
                    modifier = Modifier.size(16.dp).clip(RoundedCornerShape(6.dp)).clickable { model.showTray = false; model.showSettings = true }.padding(2.dp),
                )
            }
        }
    }
}

// ── derivation (pure, unit-tested) ───────────────────────────────────────────────────────────────

/** Per-section row caps — the tray is a glance, so it never grows past a few rows; the rest routes to the
 *  full surface (the bell / the sidebar) through a "+N more" row. */
internal const val TRAY_MAX_APPROVALS = 3
internal const val TRAY_MAX_RUNNING = 6

/** A capped tray section: the rows to render (first [max]) plus how many were hidden — the "+N more" count.
 *  Pure so the overflow rule is unit-tested without composition. */
internal fun <T> trayVisible(all: List<T>, max: Int): Pair<List<T>, Int> =
    all.take(max) to (all.size - max).coerceAtLeast(0)

/** Tray header counts from live fleet state: connected (online) computers · running sessions across the WHOLE
 *  fleet. Uses `running` (not `runningVisible`) so the count matches the un-deduped list the tray renders. */
internal fun trayHeaderCounts(model: DesktopModel): Pair<Int, Int> =
    model.machines.count { it.computer.online } to model.running.size

/** "N computer(s) · M session(s)", singular/plural aware — the header's mono subtitle. */
internal fun trayStatsLabel(computers: Int, sessions: Int): String =
    "$computers ${if (computers == 1) "computer" else "computers"} · $sessions ${if (sessions == 1) "session" else "sessions"}"

/** Switch the active binding to the machine that owns an approval (its single live ask then surfaces inline).
 *  [DkAttention] carries no session id, so this is the honest "jump" — exactly what the bell popover does. */
private fun jumpToMachine(model: DesktopModel, accountId: String) {
    model.machines.firstOrNull { it.computer.accountId == accountId }?.let { if (!it.active) model.selectComputer(it.computer) }
}

// ── rows ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrayGroupLabel(text: String) {
    Text(text.uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, modifier = Modifier.padding(bottom = 10.dp))
}

/** A section's empty state — an optional leading glyph plus a muted line, matching the sidebar's empty rows. */
@Composable
private fun TrayEmpty(icon: androidx.compose.ui.graphics.vector.ImageVector?, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(Modifier.padding(vertical = 10.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp)) else Dot(tint, 6.dp)
        Text(text, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp)
    }
}

/** One real fleet approval: OS glyph · machine (mono) · tool, with the deadline countdown when the daemon
 *  reports one; a mono preview; Deny/Allow ride [DesktopModel.resolveAttention]. The card body jumps to the
 *  owning machine. An AskUserQuestion row swaps Deny/Allow for "Answer in session" — its answer must ride
 *  the ALLOW as an answers map, so a bare ALLOW from a summary surface would silently read "did not answer".
 *  Long machine names / tools / previews truncate with an ellipsis (mono, layout-aware). */
@Composable
private fun TrayApprovalRow(a: DkAttention, onDeny: () -> Unit, onAllow: () -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(osIcon(a.os), null, tint = Tok.tx2, modifier = Modifier.size(13.dp))
            Text(a.machine, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("·", color = Tok.hair, fontFamily = Dk.ui, fontSize = 11.5.sp)
            Text(
                a.tool, color = Tok.tx, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            a.seconds?.let { s ->
                Text(
                    "${s / 60}:${(s % 60).toString().padStart(2, '0')}",
                    color = if (s <= 25) Tok.warn else Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp,
                )
            }
        }
        Text(
            a.preview, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 9.dp).clip(RoundedCornerShape(7.dp)).background(Tok.base)
                .border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
        )
        if (a.question) {
            Text(
                "Answer in session ↗", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 7.dp),
            )
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Deny", color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).clickable(onClick = onDeny).padding(vertical = 7.dp),
            )
            Text(
                "Allow", color = Tok.base, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Tok.accent).clickable(onClick = onAllow).padding(vertical = 7.dp),
            )
        }
    }
}

/** One running-session row: pulse · project (mono) · machine (muted mono). Click jumps to the live session
 *  (`openRunning` switches the machine first when the work is on another computer). */
@Composable
private fun TrayRunning(title: String, computer: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.ok, 6.dp)
        Text(title, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(computer, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "+N more …" — the overflow escape hatch when a section is capped; click opens the full surface. */
@Composable
private fun TrayMore(hidden: Int, verb: String, onClick: () -> Unit) {
    if (hidden <= 0) return
    Text(
        "+$hidden more $verb — open cc-pocket",
        color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 7.dp),
    )
}

/** The menu-bar / tray icon with a count badge when an approval is waiting. */
@Composable
fun TrayIcon(badge: Int = 1) {
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
        ) { AgentGlyph(AgentKind.CLAUDE, size = 17) }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(16.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent),
                contentAlignment = Alignment.Center,
            ) { Text("$badge", color = Tok.base, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
