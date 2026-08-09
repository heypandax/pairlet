package dev.ccpocket.app.ui.fleet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LaptopWindows
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.ccpocket.app.ui.PulseDot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.fl_idle
import dev.ccpocket.app.resources.fl_not_connected
import dev.ccpocket.app.resources.fl_active_many
import dev.ccpocket.app.resources.fl_active_one
import dev.ccpocket.app.resources.fl_pending_a11y_many
import dev.ccpocket.app.resources.fl_pending_a11y_one
import dev.ccpocket.app.resources.fl_status_offline
import dev.ccpocket.app.resources.fl_status_online
import dev.ccpocket.app.resources.fl_status_reconnecting
import dev.ccpocket.app.resources.fl_summary_computers_many
import dev.ccpocket.app.resources.fl_summary_computers_one
import dev.ccpocket.app.resources.fl_summary_online
import dev.ccpocket.app.resources.fl_waiting_many
import dev.ccpocket.app.resources.fl_waiting_one
import dev.ccpocket.app.resources.session_fallback
import dev.ccpocket.app.resources.time_days_ago
import dev.ccpocket.app.resources.time_hours_ago
import dev.ccpocket.app.resources.time_just_now
import dev.ccpocket.app.resources.time_minutes_ago
import dev.ccpocket.app.resources.time_yesterday
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.fmtMmSs
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateMarkGlyph
import org.jetbrains.compose.resources.stringResource

/**
 * The fleet design language (shared by every machine-first surface): machines stay MONOCHROME — an OS glyph,
 * a mono hostname, a status dot. No per-machine accent colors, so terracotta keeps meaning "needs you" and
 * teal stays Codex. From the "Fleet Mobile/Desktop" boards.
 *
 * This file is also where the fleet's WORDS live. [FleetMachine] carries structured facts; every sentence a
 * human reads is assembled here from resources, so the same row reads correctly in English and Chinese and
 * nothing downstream has to parse an English fragment back into a state.
 */
fun machineIcon(os: MachineOs): ImageVector = when (os) {
    MachineOs.MAC -> Icons.Rounded.LaptopMac
    MachineOs.LINUX -> Icons.Rounded.Terminal
    MachineOs.WIN -> Icons.Rounded.LaptopWindows
}

fun statusColor(status: MachineStatus): Color = when (status) {
    MachineStatus.ONLINE -> Tok.ok
    MachineStatus.RECONNECTING -> Tok.warn
    MachineStatus.OFFLINE -> Tok.muted
}

/** The greyscale half of a status: three shapes for the three states, legible with no colour at all. */
fun statusMark(status: MachineStatus): StateMark = when (status) {
    MachineStatus.ONLINE -> StateMark.DOT
    MachineStatus.RECONNECTING -> StateMark.DIAMOND
    MachineStatus.OFFLINE -> StateMark.RING
}

/** The written status. Exactly three exist; none of them is ever implied by colour alone. */
@Composable
fun machineStatusLabel(status: MachineStatus): String = stringResource(
    when (status) {
        MachineStatus.ONLINE -> Res.string.fl_status_online
        MachineStatus.RECONNECTING -> Res.string.fl_status_reconnecting
        MachineStatus.OFFLINE -> Res.string.fl_status_offline
    },
)

/**
 * What the machine is doing, in words — or null when nothing is known, so the row simply omits the line
 * instead of printing a placeholder. Names, paths, tools and previews stay the daemon's own literals.
 */
@Composable
fun machineActivityText(activity: MachineActivity): String? = when (activity) {
    MachineActivity.Unknown -> null
    MachineActivity.NotConnected -> stringResource(Res.string.fl_not_connected)
    MachineActivity.Idle -> stringResource(Res.string.fl_idle)
    is MachineActivity.WaitingApproval ->
        listOf(waitingApprovalText(activity.count), "${activity.tool}: ${activity.preview}").joinToString(" · ")
    is MachineActivity.InSession -> listOfNotNull(
        activity.title.ifBlank { stringResource(Res.string.session_fallback) },
        activity.path?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    // "projects", not "sessions": the count is `directories.count { open || busy }`, and one folder can
    // host several live sessions — naming them sessions would be a number no daemon reported
    is MachineActivity.Active -> listOfNotNull(
        stringResource(if (activity.count == 1) Res.string.fl_active_one else Res.string.fl_active_many, activity.count),
        activity.path?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** "2 approvals waiting" — one owner for the plural, shared by the strip, the badge label and the rows. */
@Composable
fun waitingApprovalText(count: Int): String =
    stringResource(if (count == 1) Res.string.fl_waiting_one else Res.string.fl_waiting_many, count)

/** The last-seen fallback for a machine that is not online, or null when even that is unknown. */
@Composable
fun machineLastSeenText(lastSeen: MachineLastSeen): String? = when (lastSeen) {
    MachineLastSeen.Unknown -> null
    MachineLastSeen.ActiveNow -> stringResource(Res.string.time_just_now)
    is MachineLastSeen.Ago -> relativeMinutes(lastSeen.minutes)
}

/** The shared relative-time words, from a whole-minute offset (the only shape the fleet ever has). */
@Composable
fun relativeMinutes(minutes: Int): String = when {
    minutes < 1 -> stringResource(Res.string.time_just_now)
    minutes < 60 -> stringResource(Res.string.time_minutes_ago, minutes)
    minutes < 24 * 60 -> stringResource(Res.string.time_hours_ago, minutes / 60)
    minutes < 48 * 60 -> stringResource(Res.string.time_yesterday)
    else -> stringResource(Res.string.time_days_ago, minutes / (24 * 60))
}

/** "4 computers · 3 online · 2 approvals waiting". Real plurals, real counts, no claim beyond them. */
@Composable
fun fleetSummaryText(s: FleetSummary): String = buildList {
    add(
        stringResource(
            if (s.machines == 1) Res.string.fl_summary_computers_one else Res.string.fl_summary_computers_many,
            s.machines,
        ),
    )
    add(stringResource(Res.string.fl_summary_online, s.online))
    if (s.waiting > 0) add(waitingApprovalText(s.waiting))
}.joinToString(" · ")

/** OS glyph + mono hostname + status dot (pulsing while online). */
@Composable
fun MachineChip(
    name: String,
    os: MachineOs,
    status: MachineStatus = MachineStatus.ONLINE,
    fontSize: TextUnit = 12.sp,
    glyph: Dp = 14.dp,
    color: Color = Tok.tx,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(machineIcon(os), null, tint = Tok.tx2, modifier = Modifier.size(glyph))
        Text(
            // lineHeight = fontSize hugs the line box to the glyphs — otherwise the mono font's leading
            // makes CenterVertically seat the status dot visibly above the text's optical center
            name, color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (status == MachineStatus.ONLINE) PulseDot(Tok.ok, 6.dp) else Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(statusColor(status)))
    }
}

/**
 * The terracotta count pill marking approvals waiting on a machine or group.
 *
 * Reads its meaning out loud: a bare "2" beside a computer name is not a state anyone can hear.
 */
@Composable
fun AttentionBadge(n: Int, modifier: Modifier = Modifier) {
    val label = stringResource(if (n == 1) Res.string.fl_pending_a11y_one else Res.string.fl_pending_a11y_many, n)
    Box(
        modifier.widthIn(min = 18.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = Tok.base, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Status mark + the written status, the pair every machine-first surface shows together.
 *
 * [label] overrides the words only: a binding this app holds no live link for is not KNOWN to be down, so
 * that row says "not connected" — a statement about US — rather than asserting the computer is offline.
 */
@Composable
fun MachineStatusLine(
    status: MachineStatus,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: Dp = 8.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        StateMarkGlyph(statusMark(status), statusColor(status), size)
        Text(label ?: machineStatusLabel(status), color = Tok.tx2, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Compact countdown ring for queue rows (the PermissionSheet has its own larger one). Amber near zero. */
@Composable
fun MiniCountdownRing(seconds: Int, total: Int, size: Dp = 34.dp) {
    val frac = (seconds.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val col = if (seconds <= 25 * total / 100) Tok.warn else Tok.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = 2.4.dp.toPx()
            val r = (this.size.minDimension - sw) / 2f
            val tl = Offset((this.size.width - 2 * r) / 2f, (this.size.height - 2 * r) / 2f)
            drawCircle(Tok.hair, r, style = Stroke(sw))
            drawArc(col, -90f, 360f * frac, useCenter = false, topLeft = tl, size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(
            fmtMmSs(seconds),
            color = col, fontFamily = FontFamily.Monospace, fontSize = 8.5.sp,
        )
    }
}
