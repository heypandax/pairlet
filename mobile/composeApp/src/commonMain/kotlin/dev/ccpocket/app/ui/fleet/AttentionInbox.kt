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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.ap_waiting_title
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.fl_ask_danger
import dev.ccpocket.app.resources.fl_attention_title
import dev.ccpocket.app.resources.fl_clear_body
import dev.ccpocket.app.resources.fl_clear_title
import dev.ccpocket.app.resources.fl_details
import dev.ccpocket.app.resources.fl_details_hide
import dev.ccpocket.app.resources.fl_none_waiting
import dev.ccpocket.app.resources.fl_sec_approval
import dev.ccpocket.app.resources.fl_sec_finished
import dev.ccpocket.app.resources.permission_fallback
import dev.ccpocket.app.resources.fl_unlinked_body
import dev.ccpocket.app.resources.fl_unlinked_title
import dev.ccpocket.app.resources.st_complete
import dev.ccpocket.app.resources.st_failure
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.FirstHopHeader
import dev.ccpocket.app.ui.FirstHopSectionLabel
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateMarkGlyph
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.PermissionAsk
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Attention inbox — the unified triage queue for ALL machines (Supporting Surfaces UI 2.0 · Master v1):
 * glance, open, decide. Soonest timeout first; "Recently finished" stays visually secondary below.
 *
 * The one rule that shapes the row: a decision is only offered once its details are on screen. Allow and Deny
 * live inside the expanded detail region, so a consequential answer can never be given to a truncated command.
 */
@Composable
fun AttentionInboxScreen(repo: PocketRepository, onBack: () -> Unit) {
    val entries = repo.fleetAttention().sortedBy { it.seconds }
    val finished = repo.fleetFinished()
    val links = dev.ccpocket.app.data.FleetRuntime.forPrimary(repo)?.repos() ?: listOf(repo)
    LaunchedEffect(Unit) {
        links.forEach { it.refreshPendingApprovals() }
    }
    // an empty queue only means "nothing is waiting" when we are actually TALKING to a computer. With no
    // link the queue is unknown, and "All clear" would be the one claim this surface must never make.
    val linked = repo.demoMode.value || links.any { it.connected.value }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        FirstHopHeader(
            title = stringResource(Res.string.fl_attention_title),
            summary = when {
                entries.isNotEmpty() -> waitingApprovalText(entries.size)
                linked -> stringResource(Res.string.fl_none_waiting)
                else -> null
            },
            onBack = onBack,
        )
        if (entries.isEmpty() && !linked) {
            NotLinked(Modifier.weight(1f))
        } else if (entries.isEmpty() && finished.isEmpty()) {
            AllClear(Modifier.weight(1f))
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Metric.gutter),
            ) {
                if (entries.isNotEmpty()) {
                    FirstHopSectionLabel(stringResource(Res.string.fl_sec_approval))
                    entries.forEach { e ->
                        RequestRow(
                            e,
                            ask = repo.attentionAsk(e),
                            onDeny = { repo.resolveAttention(e, allow = false) },
                            onAllow = { repo.resolveAttention(e, allow = true) },
                        )
                    }
                }
                if (finished.isNotEmpty()) {
                    FirstHopSectionLabel(stringResource(Res.string.fl_sec_finished))
                    finished.forEach { FinishedRow(it) }
                    Hairline()
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * One waiting request.
 *
 * Collapsed it identifies the work: computer, verb, tool, its own context and the head of the payload.
 * Expanded it shows the payload in FULL — the command, the diff, the flagged risk — and only then offers the
 * two decisions. [ask] is the live ask behind the row when a link still holds it; without one the row falls
 * back to the entry's own real preview rather than claiming details it cannot read.
 */
@Composable
private fun RequestRow(e: AttentionEntry, ask: PermissionAsk?, onDeny: () -> Unit, onAllow: () -> Unit) {
    // each row runs its own clock from the budget it arrived with (the sheet's 30s convention);
    // hitting zero renders it spent — the daemon's auto-deny is the actual decision of record.
    // A `noAutoDeny` ask has no deadline at all: the daemon renews it, so drawing a ring counting toward
    // an expiry that will never happen would be the surface inventing the one fact it must not.
    val waiting = ask?.noAutoDeny == true
    var seconds by remember(e.askId) { mutableStateOf(e.seconds) }
    var expanded by remember(e.askId) { mutableStateOf(false) }
    LaunchedEffect(e.askId, waiting) { while (!waiting && seconds > 0) { delay(1000); seconds -= 1 } }
    val title = e.title.ifBlank { stringResource(Res.string.permission_fallback) }
    val context = listOfNotNull(
        e.workdir?.let(::tilde),
        e.sessionId?.take(8)?.let { "$it…" },
        e.origin,
    ).joinToString(" · ")
    val payload = ask?.diff ?: ask?.inputPreview ?: e.preview
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Column(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(if (expanded) Res.string.fl_details_hide else Res.string.fl_details),
                ) { expanded = !expanded }
                .padding(vertical = Metric.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                MachineChip(e.machineName, e.os, fontSize = 12.sp, glyph = 14.dp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                if (waiting) Text(stringResource(Res.string.ap_waiting_title), color = Tok.tx2, style = TypeRole.caption)
                else MiniCountdownRing(seconds, e.seconds.coerceAtLeast(30))
            }
            Text(
                "$title · ${e.tool}", color = Tok.tx, style = TypeRole.rowTitle,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Metric.gapS),
            )
            if (context.isNotEmpty()) Text(
                context, color = Tok.muted, style = TypeRole.captionMono,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            if (!expanded) Text(
                payload, color = Tok.tx2, style = TypeRole.bodyMono,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Metric.gapS),
            )
            Text(
                stringResource(if (expanded) Res.string.fl_details_hide else Res.string.fl_details),
                color = Tok.accent, style = TypeRole.body, modifier = Modifier.padding(top = Metric.gapS),
            )
        }
        if (expanded) {
            AskDetail(ask, payload, Modifier.padding(bottom = Metric.gap))
            Row(Modifier.fillMaxWidth().padding(bottom = Metric.gapL), horizontalArrangement = Arrangement.spacedBy(Metric.gap)) {
                DecisionButton(stringResource(Res.string.deny), Tok.danger, filled = false, modifier = Modifier.weight(1f), onClick = onDeny)
                DecisionButton(stringResource(Res.string.allow), Tok.accent, filled = true, modifier = Modifier.weight(1.25f), onClick = onAllow)
            }
        }
    }
}

/**
 * The full request, unclipped.
 *
 * The payload WRAPS rather than ellipsizing: a half-shown command is exactly the thing a decision must never
 * be made against. A daemon-flagged danger is stated in words above it, with the daemon's own note.
 */
@Composable
private fun AskDetail(ask: PermissionAsk?, payload: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Metric.radiusS)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metric.gapS)) {
        if (ask?.danger == true) {
            Text(
                listOfNotNull(stringResource(Res.string.fl_ask_danger), ask.dangerNote).joinToString(" · "),
                color = Tok.warn, style = TypeRole.body,
            )
        }
        Text(
            payload, color = Tok.tx, style = TypeRole.bodyMono,
            modifier = Modifier.fillMaxWidth().clip(shape).background(Tok.surface)
                .border(Metric.hairline, Tok.hair, shape).padding(Metric.gap),
        )
    }
}

/** A decision target: a real 48 dp floor, its verb written, and never two filled tiles in one row. */
@Composable
private fun DecisionButton(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metric.radiusS)
    Box(
        modifier.heightIn(min = Metric.touch).clip(shape)
            .then(if (filled) Modifier.background(tint) else Modifier.border(Metric.hairline, tint.copy(alpha = 0.45f), shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gap, vertical = Metric.gapS),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) Tok.base else tint, style = TypeRole.action) }
}

/** History, deliberately quieter than the queue above it: what finished, where, and whether it worked. */
@Composable
private fun FinishedRow(f: FinishedEntry) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Metric.gapS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
        ) {
            Column(Modifier.weight(1f)) {
                Text(f.title, color = Tok.tx2, style = TypeRole.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        stringResource(if (f.ok) Res.string.st_complete else Res.string.st_failure),
                        f.machineName,
                        relativeMinutes(f.minutesAgo),
                    ).joinToString(" · "),
                    color = if (f.ok) Tok.muted else Tok.danger, style = TypeRole.captionMono,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * No link, so no queue.
 *
 * Deliberately NOT the all-clear check: an approval could be blocking a computer right now and this app
 * would have no way to know. Saying "all clear" here is how someone closes the phone on a request that then
 * expires unanswered.
 */
@Composable
private fun NotLinked(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateMarkGlyph(StateMark.RING, Tok.muted, 12.dp)
        Spacer(Modifier.height(Metric.gapL))
        Text(stringResource(Res.string.fl_unlinked_title), color = Tok.tx, style = TypeRole.title)
        Spacer(Modifier.height(Metric.gapS))
        Text(
            stringResource(Res.string.fl_unlinked_body), color = Tok.tx2, style = TypeRole.preview,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(56.dp))
    }
}

/** Empty state: a calm check-circle — approvals from any machine queue here the moment they arrive. */
@Composable
private fun AllClear(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp),
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
            drawPath(p, Tok.ok, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Spacer(Modifier.height(Metric.gapL))
        Text(stringResource(Res.string.fl_clear_title), color = Tok.tx, style = TypeRole.title)
        Spacer(Modifier.height(Metric.gapS))
        Text(
            stringResource(Res.string.fl_clear_body), color = Tok.tx2, style = TypeRole.preview,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(56.dp))
    }
}
