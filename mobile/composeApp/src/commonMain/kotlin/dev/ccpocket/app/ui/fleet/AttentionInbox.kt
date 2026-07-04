package dev.ccpocket.app.ui.fleet

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay

/**
 * Attention inbox — the unified triage queue for ALL machines ("Fleet Mobile" board ③): glance, decide,
 * move on. Soonest timeout first; thumb-sized Deny/Allow inline; "Recently finished" below. On today's
 * single connection the queue holds the active machine's pending ask (the demo carries the full fleet).
 */
@Composable
fun AttentionInboxScreen(repo: PocketRepository, onBack: () -> Unit) {
    val entries = repo.fleetAttention().sortedBy { it.seconds }
    val finished = repo.fleetFinished()
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onBack) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Text("Needs you", color = Tok.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        FleetStripText(repo.fleetStrip(), Modifier.background(Tok.surface).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        if (entries.isEmpty()) {
            AllClear(Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                FleetSectionLabel("Needs approval")
                entries.forEach { e ->
                    ApprovalCard(
                        e,
                        onDeny = { repo.resolveAttention(e, allow = false) },
                        onAllow = { repo.resolveAttention(e, allow = true) },
                    )
                }
                if (finished.isNotEmpty()) {
                    FleetSectionLabel("Recently finished")
                    finished.forEach { FinishedRow(it) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ApprovalCard(e: AttentionEntry, onDeny: () -> Unit, onAllow: () -> Unit) {
    // each row runs its own clock from the budget it arrived with (the sheet's 30s convention);
    // hitting zero renders it spent — the daemon's auto-deny is the actual decision of record
    var seconds by remember(e.askId) { mutableStateOf(e.seconds) }
    LaunchedEffect(e.askId) { while (seconds > 0) { delay(1000); seconds -= 1 } }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MachineChip(e.machineName, e.os, fontSize = 11.5.sp, glyph = 13.dp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            MiniCountdownRing(seconds, e.seconds.coerceAtLeast(30))
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Shield, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
            Text("${e.title} · ${e.tool}", color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            e.preview, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onDeny),
                contentAlignment = Alignment.Center,
            ) { Text("Deny", color = Tok.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.weight(1.25f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                    .clickable(onClick = onAllow),
                contentAlignment = Alignment.Center,
            ) { Text("Allow", color = Tok.base, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FinishedRow(f: FinishedEntry) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            MachineChip(f.machineName, f.os, status = MachineStatus.OFFLINE, fontSize = 11.sp, glyph = 12.dp)
            Text(f.title, color = Tok.tx2, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(
                if (f.ok) Icons.Rounded.Check else Icons.Rounded.Close, null,
                tint = if (f.ok) Tok.ok else Tok.danger, modifier = Modifier.size(13.dp),
            )
            Text(f.timeAgo, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

/** Empty state: a calm check-circle — approvals from any machine queue here the moment they arrive. */
@Composable
private fun AllClear(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 44.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(46.dp)) {
            val sw = 1.6.dp.toPx()
            drawCircle(Tok.ok.copy(alpha = 0.5f), (size.minDimension - sw) / 2f, style = Stroke(sw))
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.32f, size.height * 0.52f)
                lineTo(size.width * 0.45f, size.height * 0.64f)
                lineTo(size.width * 0.70f, size.height * 0.36f)
            }
            drawPath(p, Tok.ok, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        Spacer(Modifier.height(16.dp))
        Text("All clear — nothing needs you", color = Tok.tx2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Approvals from any machine will queue here the moment they arrive.",
            color = Tok.muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(60.dp))
    }
}
