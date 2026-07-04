package dev.ccpocket.app.ui.fleet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.Tok

/**
 * Fleet home — the Computers surface evolved from a picker into a live overview ("Fleet Mobile" board ②).
 * The phone is a triage device: the attention banner is the one eye magnet; machine cards carry mono
 * ActivityLines; tapping a card jumps to (or switches to) that machine. Full-screen, replaces the caller.
 */
@Composable
fun FleetHomeScreen(
    repo: PocketRepository,
    onBack: () -> Unit,
    onOpenInbox: () -> Unit,
) {
    val machines = repo.fleetMachines()
    val waiting = repo.fleetAttention().size
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        // ── header: back · big title · FleetStrip ──
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onBack) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text("Your computers", color = Tok.tx, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                FleetStripText(repo.fleetStrip(), Modifier.padding(top = 3.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (waiting > 0) AttentionBanner(waiting, onOpenInbox)
            machines.forEach { m ->
                MachineCard(m) {
                    if (m.current || repo.demoMode.value) { onBack(); return@MachineCard }
                    repo.pairedList.firstOrNull { it.accountId == m.accountId }?.let { repo.switchDaemon(it) }
                    onBack()
                }
            }
            // pair a new computer — close the fleet first (pairing tears the session down; without this
            // the overlay would pop back up on its own once the new binding connects)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onBack(); repo.beginAddDevice() }
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Add, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
                Text("Pair a new computer", color = Tok.tx2, fontSize = 13.sp)
            }
        }
    }
}

/** The one eye magnet: terracotta-tinted, full-width, tapping opens the Attention inbox. */
@Composable
private fun AttentionBanner(waiting: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Tok.accent.copy(alpha = 0.10f))
            .border(1.dp, Tok.accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Shield, null, tint = Tok.accent, modifier = Modifier.size(17.dp))
        Text(
            "$waiting approval${if (waiting == 1) "" else "s"} waiting",
            color = Tok.accent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        Text(
            "Review", color = Tok.base, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MachineCard(m: FleetMachine, onClick: () -> Unit) {
    val reconnecting = m.status == MachineStatus.RECONNECTING
    val dim = m.status == MachineStatus.OFFLINE && !m.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .alpha(if (dim) 0.5f else 1f),
    ) {
        if (reconnecting) ReconnectEdge()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MachineChip(m.name, m.os, m.status, fontSize = 13.sp, glyph = 15.dp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                if (m.pending > 0 && !dim) AttentionBadge(m.pending)
                Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
            }
            Text(
                if (reconnecting) "reconnecting…" else m.activity,
                color = if (reconnecting) Tok.warn else Tok.tx2,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp),
            )
            if (m.lastSeen.isNotBlank()) {
                Text(m.lastSeen, color = Tok.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

/** The thin pulsing amber top edge on a reconnecting machine's card. */
@Composable
private fun ReconnectEdge() {
    val a by rememberInfiniteTransition().animateFloat(
        initialValue = 0.9f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
    )
    Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = a }.background(Tok.warn))
}
