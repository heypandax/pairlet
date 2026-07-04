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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.fmtMmSs

/**
 * The fleet design language (shared by every machine-first surface): machines stay MONOCHROME — an OS glyph,
 * a mono hostname, a status dot. No per-machine accent colors, so terracotta keeps meaning "needs you" and
 * teal stays Codex. From the "Fleet Mobile/Desktop" boards.
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

/** The terracotta count pill marking approvals waiting on a machine or group. */
@Composable
fun AttentionBadge(n: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(min = 18.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = Tok.base, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** "4 machines · 3 online · 2 waiting approval" — mono secondary, everywhere the fleet summarizes itself. */
@Composable
fun FleetStripText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** The 11sp uppercase muted section caption ("NEEDS APPROVAL" / "RECENTLY FINISHED"). */
@Composable
fun FleetSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp, modifier = modifier.padding(top = 16.dp, bottom = 10.dp),
    )
}
