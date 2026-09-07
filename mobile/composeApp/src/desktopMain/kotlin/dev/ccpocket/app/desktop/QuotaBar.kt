package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ccpocket.app.data.ClaudeQuotaRefreshPolicy
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.QuotaRefreshTrigger
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.quota_used
import dev.ccpocket.app.resources.quota_title
import dev.ccpocket.app.resources.quota_title_generic
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.QuotaFreshnessRow
import dev.ccpocket.app.ui.QuotaSection
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.quotaSections
import dev.ccpocket.app.ui.sectionHeading
import dev.ccpocket.app.ui.QuotaLimitRow
import dev.ccpocket.app.ui.isWarn
import dev.ccpocket.app.ui.quotaShortLabel
import dev.ccpocket.app.ui.quotaLabel
import dev.ccpocket.app.ui.rememberQuotaClock
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClaudeQuotaLimit
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The always-visible Claude allowance strip, docked directly above the sidebar's [FooterActions].
 *
 * Why it lives here and not on the usage page: "how much have I got left" is a question asked WHILE
 * working — a number you glance at before starting a long run — not one worth navigating to a dashboard
 * for. It briefly sat at the top of the usage screen and user testing said exactly that.
 *
 * The strip is two segments, `5h · bar · 82%` and `7d · bar · 19%`, showing what is LEFT (`100 - percent`);
 * people plan against headroom, not against consumption. The 7d segment reports the WORST weekly window,
 * i.e. the highest `percent` among `weekly_all` and every `weekly_scoped` row — the binding constraint is
 * whichever window runs out first, and a per-model cap you cannot see is the one that stops you.
 *
 * Absent data means ZERO height, never an empty frame or an error: no daemon, an API-key account, an old
 * daemon that drops the frame, a network blip before the first success — all of them are simply "nothing
 * to say", and a persistent chrome element that shouts about its own failures is worse than one that
 * waits quietly for the next refresh.
 */
@Composable
fun QuotaBar(model: DesktopModel) {
    val repo = model.usageRepo ?: return
    // The refresh driver must run even when there is nothing to draw — otherwise the very first fetch
    // would never fire and the strip could never appear. Hence: effect first, render decision after.
    ClaudeQuotaRefreshEffect(repo)

    // one thin row per backend that has numbers (issue #348), Claude first. A single backend is the
    // pre-#348 strip unchanged; the second row only appears on a machine that really has two accounts.
    val sections = quotaSections(repo)
    if (sections.isEmpty()) return
    // WHICH backend's popover is open. `model.showQuotaPopover` stays the boolean the window's overlay
    // bookkeeping (anyOverlayOpen / Esc routing) already knows about — this only narrows it to a row.
    var popoverAgent by remember { mutableStateOf<AgentKind?>(null) }

    // ONE row for every backend (owner decision, 09-07): `Claude 5h 78% 7d 40%   Codex 7d 54%` — plain
    // numbers, no bars. Each backend keeps its own hover tooltip and click-through popover.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEachIndexed { i, sec ->
            // The FIRST group (Claude) takes whatever is left; every later group is measured first at its
            // natural width and sits at the right edge. Weighted children are laid out last, so the Codex
            // figures can never be clipped by the row running out of room — an even split clipped Claude's
            // 7d, a trailing spacer clipped Codex's "%" (user, 09-07).
            QuotaAgentBar(
                repo = repo,
                sec = sec,
                // same rule as the phone sheet: only a LONE CLAUDE row goes unnamed in its tooltip and
                // popover title, because that is the pre-#348 surface
                labelled = sections.size > 1 || sec.agent != AgentKind.CLAUDE,
                popoverOpen = model.showQuotaPopover && popoverAgent == sec.agent,
                onOpen = { popoverAgent = sec.agent; model.showQuotaPopover = true },
                onDismiss = { model.showQuotaPopover = false },
                modifier = if (i == 0 && sections.size > 1) Modifier.weight(1f) else Modifier,
            )
        }
    }
}

/** One backend's strip row, with its own hover tooltip and its own click-through popover. */
@Composable
private fun QuotaAgentBar(
    repo: PocketRepository,
    sec: QuotaSection,
    labelled: Boolean,
    popoverOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    // No hairline of its own: the host ([FooterActions]) already draws the footer's single divider above
    // this strip, and a second one 26dp below it would read as a stack of docked rows.
    Box(modifier) {
        Row(
            Modifier.height(26.dp).clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                .hoverable(src).clickable(onClick = onOpen)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // brand marker: WHOSE subscription this group reports (a bare percentage is the one number a
            // multi-backend user cannot act on)
            Text(agentName(sec.agent), color = Tok.muted, fontFamily = Dk.mono, fontSize = 9.5.sp, maxLines = 1, style = tightCenter(9.5.sp))
            // short labels, so the weekly segment can NAME a scoped cap ("7d·Fable") — an unlabelled
            // worst-of-weekly percent read as a wrong number next to the official panel's all-models row
            sec.segments.forEach { QuotaSegment(quotaShortLabel(it), it) }
        }

        // Hover summary. The repo has no TooltipArea idiom anywhere, so this follows the one floating-
        // layer pattern it does have (the composer model chip): an anchored Popup, here NON-focusable so
        // merely pointing at the strip never steals the keyboard from the composer.
        if (hovered && !popoverOpen) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { AboveAnchorStartPopupPositionProvider(gap) },
                properties = PopupProperties(focusable = false),
            ) { QuotaTooltip(sec, heading = if (labelled) sectionHeading(sec) else null) }
        }
        if (popoverOpen) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { AboveAnchorStartPopupPositionProvider(gap) },
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) { QuotaPopover(repo, sec, titled = labelled, onDismiss = onDismiss) }
        }
    }
}

/** One `label NN%` segment — numbers only, no bar (owner decision, 09-07). [limit] drives the warn state;
 *  the percent is USED, mirroring the official claude.ai usage panel (owner decision, 08-24). */
@Composable
private fun QuotaSegment(label: String, limit: ClaudeQuotaLimit, modifier: Modifier = Modifier) {
    val warn = isWarn(limit)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = if (warn) Tok.warn else Tok.muted, fontFamily = Dk.mono, fontSize = 9.5.sp, maxLines = 1, style = tightCenter(9.5.sp))
        Text(
            "${limit.percent.coerceIn(0, 100)}%",
            color = if (warn) Tok.warn else Tok.tx2, fontFamily = Dk.mono, fontSize = 9.5.sp, maxLines = 1,
            style = tightCenter(9.5.sp),
        )
    }
}

/** Hover summary: one terse line per window, so the two-segment strip can be read in full without a click.
 *  [heading] names the backend, and is present only when more than one row is docked (with a single
 *  backend it would name the obvious, and the pre-#348 tooltip is unchanged). */
@Composable
private fun QuotaTooltip(sec: QuotaSection, heading: String?) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(230.dp).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        heading?.let {
            Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, maxLines = 1, style = tightCenter(10.sp))
        }
        for (l in sec.rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(quotaLabel(l), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    stringResource(Res.string.quota_used, "${l.percent.coerceIn(0, 100)}%"),
                    color = if (isWarn(l)) Tok.warn else Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * The click-through detail for ONE backend: every window in full (label, bar, remaining, reset countdown)
 * plus the age of the numbers and a manual refresh. Focusable like [ModelPopover], so it owns Esc from
 * the inside.
 */
@Composable
private fun QuotaPopover(repo: PocketRepository, sec: QuotaSection, titled: Boolean, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val now by rememberQuotaClock()

    Column(
        Modifier.width(320.dp).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape).padding(14.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
            },
    ) {
        Text(
            if (titled) "${stringResource(Res.string.quota_title_generic)} · ${sectionHeading(sec)}" else stringResource(Res.string.quota_title),
            color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        // the rows and the freshness footer are the SHARED components (ui/QuotaRows.kt) — the phone's
        // bottom sheet renders the identical thing, wearing the platform's own faces instead of Dk's
        for (l in sec.rows) {
            QuotaLimitRow(l, now, uiFont = Dk.ui, monoFont = Dk.mono)
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Spacer(Modifier.height(8.dp))
        QuotaFreshnessRow(
            fetchedAt = sec.quota.fetchedAt,
            now = now,
            // this popover is ONE backend's, so its refresh is that backend's — refreshing the other one
            // too would move an age line the user is not looking at and cost a second daemon round trip
            onRefresh = { repo.fetchQuota(sec.agent, forceRefresh = true) },
            uiFont = Dk.ui,
            monoFont = Dk.mono,
            refreshDecoration = Modifier.hoverFill(RoundedCornerShape(7.dp)),
        )
    }
}

// ── the refresh driver ─────────────────────────────────────────────────────────────────────────────

/**
 * Wires the five refresh rules to real desktop events. All the TIMING lives in
 * [ClaudeQuotaRefreshPolicy] (a plain, clock-injected object with its own unit tests); this composable
 * only reports what happened — link went ready, window regained focus, a turn finished — and pumps a
 * coarse timer. Keeping the decisions out of the composition is deliberate: Compose's test clock has a
 * history of hanging `waitForIdle` when a `delay` loop is fast-forwarded, so the rules must be testable
 * without it.
 */
@Composable
private fun ClaudeQuotaRefreshEffect(repo: PocketRepository) {
    val policy = remember(repo) {
        ClaudeQuotaRefreshPolicy(now = { epochMillis() }, fetch = { force -> repo.fetchAllQuotas(force) })
    }
    // the in-flight latch opens on ANY reply, success or failure
    DisposableEffect(repo, policy) {
        val mine: () -> Unit = { policy.replied() }
        repo.onClaudeQuotaReply = mine
        // clear only if still OURS: a second strip (a fleet window) registering after us must not have its
        // latch silently unhooked when this one leaves the composition
        onDispose { if (repo.onClaudeQuotaReply === mine) repo.onClaudeQuotaReply = null }
    }
    // rule 1: a ready link — including every reconnect, and including a switch to a different machine,
    // whose account is a different subscription entirely
    val ready = repo.phase.value == ConnPhase.Ready
    LaunchedEffect(policy, ready) {
        if (ready) policy.event(QuotaRefreshTrigger.CONNECTED) else policy.reset()
    }
    // rule 3: window focus, gated on staleness inside the policy
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(policy, focused) { if (focused) policy.event(QuotaRefreshTrigger.FOCUSED) }
    // rule 4: a finished turn anywhere on this machine, debounced inside the policy
    val turns = repo.turnCompletions.value
    LaunchedEffect(policy, turns) { if (turns > 0) policy.event(QuotaRefreshTrigger.TURN_DONE) }
    // the pump for the two time-driven rules (periodic + the turn debounce). Coarse on purpose: the
    // shortest deadline it has to resolve is 60s.
    LaunchedEffect(policy) { while (true) { delay(TICK_MS); policy.tick() } }
    // keep the staleness basis honest — it is the daemon's OWN fetch moment, not our request moment,
    // and with several backends the OLDEST of them (one refreshing must not mark the other one fresh)
    val fetchedAt = repo.quotaByAgent.values.mapNotNull { it.fetchedAt.takeIf { t -> t > 0 } }.minOrNull()
    LaunchedEffect(policy, fetchedAt) { policy.snapshotFetchedAt(fetchedAt) }
}

private const val TICK_MS = 10_000L

