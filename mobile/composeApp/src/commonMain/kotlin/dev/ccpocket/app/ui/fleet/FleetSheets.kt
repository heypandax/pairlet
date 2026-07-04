package dev.ccpocket.app.ui.fleet

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.fmtMmSs
import dev.ccpocket.app.ui.tilde
import kotlinx.coroutines.delay

/**
 * Machine switcher — opened by tapping the machine name in the chat connection bar ("Fleet Mobile" board ④).
 * One tap jumps straight to that machine's last context; no intermediate screens. Rows show "where you left
 * off" under each hostname, an AttentionBadge when that machine holds approvals, a check on the current one.
 */
@Composable
fun MachineSwitcherSheet(repo: PocketRepository, onDismiss: () -> Unit, onManage: () -> Unit) {
    val machines = repo.fleetMachines()
    PocketSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                "Switch computer", color = Tok.tx, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            machines.forEach { m ->
                val where = when {
                    m.current -> repo.chatTitle.value ?: repo.workdir.value?.let(::tilde) ?: m.activity
                    else -> m.activity
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(enabled = !m.current) {
                            if (!repo.demoMode.value) repo.pairedList.firstOrNull { it.accountId == m.accountId }?.let { repo.switchDaemon(it) }
                            onDismiss()
                        }
                        .alpha(if (m.status == MachineStatus.OFFLINE && !m.current) 0.45f else 1f)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        MachineChip(m.name, m.os, m.status, fontSize = 13.sp, glyph = 14.dp)
                        Text(
                            where, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (m.pending > 0) AttentionBadge(m.pending)
                    if (m.current) Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(17.dp))
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Tok.hair))
            Text(
                "Manage computers", color = Tok.muted, fontSize = 12.5.sp,
                modifier = Modifier.clickable { onDismiss(); onManage() }.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Cross-machine attention banner — floats under the chat header without reflowing the stream ("Fleet
 * Mobile" board ⑤). Shows only for approvals on OTHER machines (this machine's ask already has its sheet).
 * One request names it; two or more aggregate with the soonest countdown.
 */
@Composable
fun CrossMachineBanner(entries: List<AttentionEntry>, onReview: () -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val soonest = entries.minBy { it.seconds }
    var seconds by remember(soonest.askId) { mutableStateOf(soonest.seconds) }
    LaunchedEffect(soonest.askId) { while (seconds > 0) { delay(1000); seconds -= 1 } }
    Row(
        modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)).background(Tok.raised.copy(alpha = 0.97f))
            .border(1.dp, Tok.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Outlined.Shield, null, tint = Tok.accent, modifier = Modifier.size(14.dp))
        if (entries.size == 1) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(soonest.machineName, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                Text(" · ${soonest.tool} needs approval", color = Tok.tx, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} approvals waiting", color = Tok.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    " · " + entries.joinToString(", ") { it.machineName },
                    color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            fmtMmSs(seconds),
            color = Tok.warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
        Text(
            "Review", color = Tok.base, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent)
                .clickable(onClick = onReview).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
