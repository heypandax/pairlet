package dev.ccpocket.app.ui.fleet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.computers_section
import dev.ccpocket.app.resources.computers_title
import dev.ccpocket.app.resources.fl_current
import dev.ccpocket.app.resources.fl_empty_body
import dev.ccpocket.app.resources.fl_empty_title
import dev.ccpocket.app.resources.fl_not_connected
import dev.ccpocket.app.resources.fl_pair_new
import dev.ccpocket.app.resources.st_act_review
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.FirstHopHeader
import dev.ccpocket.app.ui.FirstHopSectionLabel
import dev.ccpocket.app.ui.FirstHopWideAction
import dev.ccpocket.app.ui.session.Hairline
import org.jetbrains.compose.resources.stringResource

/**
 * Fleet home — the first hop the Projects header opens onto (Supporting Surfaces UI 2.0 · Master v1).
 *
 * Back, the screen's own name, one factual line of counts, then the machines. Every row states its status in
 * WORDS beside a shape-distinct mark, so "which computer needs me" survives greyscale, a colour-blind reader
 * and a screen reader alike. Full-screen; replaces the caller.
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
        FirstHopHeader(
            title = stringResource(Res.string.computers_title),
            summary = fleetSummaryText(repo.fleetSummary()),
            onBack = onBack,
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = Metric.gutter),
        ) {
            // the one eye magnet, and only when something REAL is waiting
            if (waiting > 0) AttentionStrip(waiting, Modifier.padding(top = Metric.gapL), onOpenInbox)

            if (machines.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    Text(stringResource(Res.string.fl_empty_title), color = Tok.tx, style = TypeRole.rowTitle)
                    Text(
                        stringResource(Res.string.fl_empty_body), color = Tok.tx2, style = TypeRole.preview,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                FirstHopSectionLabel(stringResource(Res.string.computers_section))
                machines.forEach { m ->
                    MachineRow(m) {
                        if (m.current || repo.demoMode.value) { onBack(); return@MachineRow }
                        repo.pairedList.firstOrNull { it.accountId == m.accountId }?.let { repo.switchDaemon(it) }
                        onBack()
                    }
                }
                Hairline()
            }

            // pair a new computer — close the fleet first (pairing tears the session down; without this
            // the overlay would pop back up on its own once the new binding connects)
            FirstHopWideAction(
                stringResource(Res.string.fl_pair_new),
                Modifier.padding(top = 20.dp),
            ) { onBack(); repo.beginAddDevice() }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The compact attention strip: how many approvals are waiting, and the one route to them.
 *
 * Deliberately small — it names a count and opens the existing inbox; it never becomes a second place a
 * decision can be made.
 */
@Composable
private fun AttentionStrip(waiting: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(Metric.radius)
    Row(
        modifier.fillMaxWidth().heightIn(min = Metric.touch).clip(shape)
            .background(Tok.accent.copy(alpha = 0.10f))
            .border(Metric.hairline, Tok.accent.copy(alpha = 0.42f), shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Metric.gapL, vertical = Metric.gapS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metric.gap),
    ) {
        Text(
            waitingApprovalText(waiting), color = Tok.tx, style = TypeRole.action,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(Res.string.st_act_review), color = Tok.accent, style = TypeRole.action)
    }
}

/**
 * One machine.
 *
 * Only fields the repository really supplies: name, coarse OS, one of the three statuses, the activity or a
 * last-seen fallback, the pending count and whether it is the current binding. No OS version, no latency, no
 * health — a row that cannot state a fact omits its line entirely.
 */
@Composable
private fun MachineRow(m: FleetMachine, onClick: () -> Unit) {
    // no live link is a fact about THIS app, not about that computer — so the status line says so rather
    // than asserting "Offline" for a machine nobody has actually failed to reach
    val notLinked = m.activity == MachineActivity.NotConnected
    val activity = machineActivityText(m.activity).takeIf { !notLinked }
    val lastSeen = machineLastSeenText(m.lastSeen).takeIf { m.status != MachineStatus.ONLINE }
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
        ) {
            Icon(
                machineIcon(m.os), null, tint = Tok.tx2,
                modifier = Modifier.padding(top = 2.dp).size(18.dp).align(Alignment.Top),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                    Text(
                        m.name, color = Tok.tx, style = TypeRole.rowTitle,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    // written, never a colour or a highlight: "which one am I on" has to be readable
                    if (m.current) Text(stringResource(Res.string.fl_current), color = Tok.ok, style = TypeRole.caption)
                }
                MachineStatusLine(
                    m.status, Modifier.padding(top = 5.dp),
                    label = if (notLinked) stringResource(Res.string.fl_not_connected) else null,
                )
                (activity ?: lastSeen)?.let {
                    Text(
                        it, color = Tok.tx2, style = TypeRole.metaMono,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (m.pending > 0) AttentionBadge(m.pending)
            Text("›", color = Tok.muted, style = TypeRole.title)
        }
    }
}
