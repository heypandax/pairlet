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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.epochMillis
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
 * section caps at a few rows so the popover stays a glance; the approvals remainder routes to the bell, the
 * running remainder to the footer's "+N more sessions" row (the menubar-presence handoff's footer grammar).
 *
 * The menu-bar extra (issue #151) renders this SAME composable in its OS-anchored window: [showPointer]
 * hides the up-arrow when the popover grows upward from a bottom taskbar, [elevated] adds the floating
 * drop shadow an OS-layer surface needs (the in-window overlay keeps its flat look), and [keyHint] shows
 * the ⌘⏎ keycap only where the shortcut is actually wired (the menu-bar window's key handler).
 */
@Composable
fun TrayPopover(
    model: DesktopModel,
    onOpenMain: () -> Unit = {},
    modifier: Modifier = Modifier,
    showPointer: Boolean = true,
    elevated: Boolean = false,
    keyHint: Boolean = false,
) {
    val approvals = model.attention
    val running = model.running
    val (computers, sessions) = trayHeaderCounts(model)
    // Elapsed is computed at composition time — deliberately NO in-composition ticker: an unbounded
    // delay-loop under the UI-test clock keeps the scheduler permanently non-idle (waitForIdle pumps
    // frames toward a task that reschedules itself forever — a real 24-minute CI hang). The popover is
    // a transient glance, and any fleet delta (or a reopen) recomposes fresh labels anyway.
    val nowMs = epochMillis()
    val since = TrayRunningSince.observe(running.map { (m, p) -> runningKey(m, p) }, nowMs)
    // "Open cc-pocket" and every row click dismiss the popover and surface the window.
    val openMain = { model.showTray = false; onOpenMain() }
    Column((if (elevated) modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp) else modifier).width(360.dp)) {
        // little pointer up toward the menu-bar icon
        if (showPointer) Canvas(Modifier.width(360.dp).height(7.dp)) {
            val w = 14f; val cx = size.width / 2f
            val p = Path().apply { moveTo(cx - w, size.height); lineTo(cx, 0f); lineTo(cx + w, size.height); close() }
            drawPath(p, Tok.raised)
            drawLine(Tok.hair, Offset(cx - w, size.height), Offset(cx, 0f), strokeWidth = 1f, cap = StrokeCap.Round)
            drawLine(Tok.hair, Offset(cx, 0f), Offset(cx + w, size.height), strokeWidth = 1f, cap = StrokeCap.Round)
        }
        Column(
            Modifier.fillMaxWidth()
                .then(if (elevated) Modifier.shadow(24.dp, RoundedCornerShape(14.dp)) else Modifier)
                .clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CcGlyphMark(Tok.tx, 16.dp)
                Text("cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(trayStatsLabel(computers, sessions), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
                Icon(
                    Icons.Outlined.Settings, "Settings", tint = Tok.tx2,
                    // Settings lives in the main window, so the gear surfaces it too (from the menu-bar
                    // popover the window may be buried — a modal opening off-screen would read as a dead click)
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
                        .clickable { model.showTray = false; model.showSettings = true; onOpenMain() }.padding(4.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.padding(14.dp)) {
                TrayGroupLabel("Needs you", count = approvals.size)
                if (approvals.isEmpty()) {
                    TrayEmpty(Icons.Rounded.Check, Tok.ok, "Nothing needs you")
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
                TrayGroupLabel("Running")
                if (running.isEmpty()) {
                    TrayEmpty(null, Tok.muted, "No running sessions")
                } else {
                    val (shown, _) = trayVisible(running, TRAY_MAX_RUNNING)
                    shown.forEach { (m, p) ->
                        TrayRunning(
                            p.name, m.computer.name, m.computer.os,
                            elapsed = since[runningKey(m, p)]?.let { elapsedLabel((nowMs - it).coerceAtLeast(0)) },
                        ) { openMain(); model.openRunning(m, p) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                // the running overflow lives in the footer ("+N more sessions"), per the menubar handoff
                val hiddenRunning = (running.size - TRAY_MAX_RUNNING).coerceAtLeast(0)
                if (hiddenRunning > 0) {
                    Text(
                        "+$hiddenRunning more sessions",
                        color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).hoverFill(RoundedCornerShape(9.dp))
                            .clickable(onClick = openMain).padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).hoverFill(RoundedCornerShape(9.dp))
                        .clickable(onClick = openMain).padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Open cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (keyHint) Key("⌘⏎")
                }
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
private fun TrayGroupLabel(text: String, count: Int = 0) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
        // the accent count pill beside NEEDS YOU — the one coloured cue inside the popover chrome
        if (count > 0) {
            Text(
                "$count", color = Tok.accent, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

/** The cc-pocket mark (menubar.jsx's chevron+underscore), drawn so the popover header matches the bar glyph. */
@Composable
private fun CcGlyphMark(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp, alpha: Float = 0.95f) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 18f
        val w = 1.9f * u
        val c = color.copy(alpha = alpha)
        drawLine(c, Offset(4f * u, 4.5f * u), Offset(8.3f * u, 8.8f * u), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(8.3f * u, 8.8f * u), Offset(4f * u, 13.1f * u), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(9.6f * u, 13.2f * u), Offset(14f * u, 13.2f * u), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** The machine provenance pill (OS glyph + mono name) — the handoff's chip grammar for fleet rows. */
@Composable
private fun MachineChip(name: String, os: DkOs) {
    Row(
        Modifier.widthIn(max = 130.dp).clip(RoundedCornerShape(999.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(osIcon(os), null, tint = Tok.muted, modifier = Modifier.size(10.dp))
        Text(name, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
            Text(
                a.tool, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            MachineChip(a.machine, a.os)
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

/** One running-session row: pulse · project (mono) · machine chip · elapsed (mono, right-aligned). Click
 *  jumps to the live session (`openRunning` switches the machine first when the work is on another
 *  computer). [elapsed] is the client-side observation clock ([TrayRunningSince]) — "running for at least
 *  this long while we've been watching" — because the protocol carries no per-project start time. */
@Composable
private fun TrayRunning(title: String, computer: String, os: DkOs, elapsed: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.ok, 6.dp)
        Text(title, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        MachineChip(computer, os)
        if (elapsed != null) {
            Text(
                elapsed, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 30.dp),
            )
        }
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
