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
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.QuotaFreshnessRow
import dev.ccpocket.app.ui.QuotaLimitRow
import dev.ccpocket.app.ui.isSessionWindow
import dev.ccpocket.app.ui.isWarn
import dev.ccpocket.app.ui.quotaShortLabel
import dev.ccpocket.app.ui.quotaLabel
import dev.ccpocket.app.ui.rememberQuotaClock
import dev.ccpocket.app.ui.worstWeekly
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.ClaudeQuota
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

    val q = repo.claudeQuota.value
    if (q == null || q.status != CLAUDE_QUOTA_OK || q.limits.isEmpty()) return

    val session = q.limits.firstOrNull { isSessionWindow(it) }
    val weekly = worstWeekly(q.limits)
    if (session == null && weekly == null) return

    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    // No hairline of its own: the host ([FooterActions]) already draws the footer's single divider above
    // this strip, and a second one 26dp below it would read as a stack of docked rows.
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
                .hoverable(src).clickable { model.showQuotaPopover = true }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // brand marker: Claude-subscription quota only (multi-agent users would otherwise read it as global)
            Text("Claude", color = Tok.muted, fontFamily = Dk.mono, fontSize = 9.5.sp, maxLines = 1, style = tightCenter(9.5.sp))
            // short labels, so the weekly segment can NAME a scoped cap ("7d·Fable") — an unlabelled
            // worst-of-weekly percent read as a wrong number next to the official panel's all-models row
            session?.let { QuotaSegment(quotaShortLabel(it), it, Modifier.weight(1f)) }
            weekly?.let { QuotaSegment(quotaShortLabel(it), it, Modifier.weight(1f)) }
        }

        // Hover summary. The repo has no TooltipArea idiom anywhere, so this follows the one floating-
        // layer pattern it does have (the composer model chip): an anchored Popup, here NON-focusable so
        // merely pointing at the strip never steals the keyboard from the composer.
        if (hovered && !model.showQuotaPopover) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { AboveAnchorStartPopupPositionProvider(gap) },
                properties = PopupProperties(focusable = false),
            ) { QuotaTooltip(q.limits) }
        }
        if (model.showQuotaPopover) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { AboveAnchorStartPopupPositionProvider(gap) },
                onDismissRequest = { model.showQuotaPopover = false },
                properties = PopupProperties(focusable = true),
            ) { QuotaPopover(repo, q) { model.showQuotaPopover = false } }
        }
    }
}

/** One `label · bar · NN%` segment. [limit] drives both the fill fraction and the segment's warn state. */
@Composable
private fun QuotaSegment(label: String, limit: ClaudeQuotaLimit, modifier: Modifier = Modifier) {
    val warn = isWarn(limit)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = if (warn) Tok.warn else Tok.muted, fontFamily = Dk.mono, fontSize = 9.5.sp, maxLines = 1, style = tightCenter(9.5.sp))
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(999.dp)).background(Tok.hair)) {
            // fills by USED, mirroring the official claude.ai usage panel (owner decision, 08-24)
            if (limit.percent > 0) {
                Box(Modifier.fillMaxWidth(limit.percent.coerceIn(0, 100) / 100f).fillMaxHeight().background(if (warn) Tok.warn else Tok.tx2))
            }
        }
        Text(
            "${limit.percent.coerceIn(0, 100)}%",
            color = if (warn) Tok.warn else Tok.tx2, fontFamily = Dk.mono, fontSize = 9.5.sp, maxLines = 1,
            style = tightCenter(9.5.sp),
        )
    }
}

/** Hover summary: one terse line per window, so the two-segment strip can be read in full without a click. */
@Composable
private fun QuotaTooltip(limits: List<ClaudeQuotaLimit>) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(230.dp).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (l in limits) {
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
 * The click-through detail: every window in full (label, bar, remaining, reset countdown) plus the age of
 * the numbers and a manual refresh. Focusable like [ModelPopover], so it owns Esc from the inside.
 */
@Composable
private fun QuotaPopover(repo: PocketRepository, q: ClaudeQuota, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val now by rememberQuotaClock()

    Column(
        Modifier.width(320.dp).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape).padding(14.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
            },
    ) {
        Text(stringResource(Res.string.quota_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        // the rows and the freshness footer are the SHARED components (ui/QuotaRows.kt) — the phone's
        // bottom sheet renders the identical thing, wearing the platform's own faces instead of Dk's
        for (l in q.limits) {
            QuotaLimitRow(l, now, uiFont = Dk.ui, monoFont = Dk.mono)
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Spacer(Modifier.height(8.dp))
        QuotaFreshnessRow(
            fetchedAt = q.fetchedAt,
            now = now,
            onRefresh = { repo.fetchClaudeQuota(forceRefresh = true) },
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
        ClaudeQuotaRefreshPolicy(now = { epochMillis() }, fetch = { force -> repo.fetchClaudeQuota(force) })
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
    // keep the staleness basis honest — it is the daemon's OWN fetch moment, not our request moment
    val fetchedAt = repo.claudeQuota.value?.fetchedAt
    LaunchedEffect(policy, fetchedAt) { policy.snapshotFetchedAt(fetchedAt) }
}

private const val TICK_MS = 10_000L

