package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ClaudeQuotaRefreshPolicy
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.QuotaRefreshTrigger
import dev.ccpocket.app.epochMillis
import dev.ccpocket.protocol.ClaudeQuotaLimit
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.quota_title
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's allowance entry: a strip docked to the bottom edge of Home and the session list.
 *
 * Design of record: `docs/design/claude-design-handoff/mobile-quota-entry/` direction **B**. The desktop
 * solved this with a footer status strip in its sidebar chrome; a phone has no persistent chrome, so this
 * borrows the weight of the window's bottom edge instead. It is the only one of the three explored
 * directions that survives into the session list — the earlier top-bar pill and machine-state-line pill
 * (direction A) were replaced by it and are gone.
 *
 * Pixel contract, taken from the handoff's DOM/CSS rather than restated here in prose: 48dp min height,
 * `0 20 8` padding (the 8dp bottom lifts the reading clear of the home indicator), 18dp between segments,
 * a hairline top rule and NO fill of its own — the page's own base shows through. Per segment: a 12.5sp
 * mono label, a 40×2dp track with a 1dp radius, and a 12.5sp mono percentage. On the right, the tightest
 * window's reset countdown at 12sp.
 *
 * Two things worth knowing before touching the numbers:
 *  1. **The bar fills with what is LEFT, not with what is spent** — it drains as you work. That is the
 *     handoff's `bar(left, …)` contract, and it is deliberately the opposite of the desktop strip and of
 *     the shared [QuotaLimitRow], which fill by consumption. See the deviation note in the report.
 *  2. Both windows warn independently and nothing more happens when both do at once — an explicitly
 *     closed open question on the board, not an oversight.
 *
 * No snapshot / a non-OK status / no rows → renders NOTHING, and the layout collapses back to exactly
 * what it is today (the handoff's third state). Callers must therefore give it zero-height tolerance:
 * it is a plain sibling in a Column, never an overlay.
 *
 * Safe area is NOT handled here: the app root already applies `windowInsetsPadding(WindowInsets.systemBars)`
 * to the whole tree, so a second inset would double-pad the strip off the bottom of the screen.
 */
@Composable
fun QuotaStrip(repo: PocketRepository, onOpen: () -> Unit) {
    val rows = quotaRows(repo.claudeQuota.value)
    val session = rows.firstOrNull { isSessionWindow(it) }
    val weekly = worstWeekly(rows)
    val segments = listOfNotNull(session, weekly)
    if (segments.isEmpty()) return
    val tightest = tightestLimit(rows)
    val now by rememberQuotaClock()

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // brand marker: this strip is the CLAUDE subscription's quota, not an all-backends gauge —
            // other agents (Codex/dsh/…) simply have no snapshot and the strip stays absent for them
            Text(
                "Claude", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                style = tightCenter(12.sp), maxLines = 1,
            )
            segments.forEach { QuotaStripSegment(it) }
            Spacer(Modifier.weight(1f))
            // the reset the user actually has to plan around is the one attached to the tightest window
            stripResetText(tightest?.resetsAt, now)?.let {
                Text(
                    it, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    style = tightCenter(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One `5h ▬▬ 64%` segment. Only the fill and the percentage take the warning colour; the label stays
 *  quiet, exactly as the handoff draws it — two-thirds of the segment turning amber would shout. */
@Composable
private fun QuotaStripSegment(limit: ClaudeQuotaLimit) {
    val warn = isWarn(limit)
    // USED percent + used-fill, mirroring the official claude.ai panel so its numbers compare 1:1
    val used = limit.percent.coerceIn(0, 100)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            quotaShortLabel(limit), color = Tok.tx2, fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, style = tightCenter(12.5.sp), maxLines = 1,
        )
        Box(Modifier.width(40.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Tok.hair)) {
            // fraction of the TRACK; an untouched window draws nothing rather than a stray dot at the left edge
            if (used > 0) Box(Modifier.fillMaxWidth(used / 100f).fillMaxHeight().background(if (warn) Tok.warn else Tok.tx2))
        }
        Text(
            "$used%", color = if (warn) Tok.warn else Tok.tx, fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, style = tightCenter(12.5.sp), maxLines = 1,
        )
    }
}

/** The strip's sheet: every window in full, plus how old the numbers are and a manual refresh. Renders the
 *  SAME components as the desktop popover ([QuotaLimitRow] / [QuotaFreshnessRow]) — one implementation of
 *  "what a limit row looks like", wearing the phone's default faces. */
@Composable
fun QuotaSheet(repo: PocketRepository, onDismiss: () -> Unit) {
    val q = repo.claudeQuota.value
    val rows = quotaRows(q)
    val now by rememberQuotaClock()
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp)) {
            Text(stringResource(Res.string.quota_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            for (l in rows) {
                QuotaLimitRow(l, now, labelWidth = 116.dp, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Spacer(Modifier.height(10.dp))
            QuotaFreshnessRow(
                fetchedAt = q?.fetchedAt ?: 0,
                now = now,
                onRefresh = { repo.fetchClaudeQuota(forceRefresh = true) },
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * The phone's half of the refresh wiring — the same [ClaudeQuotaRefreshPolicy] the desktop drives, fed by
 * phone-shaped events. Mount ONCE at the app root, not per screen: two instances would mean two policies,
 * two in-flight latches, and therefore double the traffic the de-duplication exists to prevent.
 *
 * The one real difference from the desktop driver is the "came back" signal. Desktop watches
 * `LocalWindowInfo.isWindowFocused`; on a phone that is meaningless (and the desktop actual of
 * `OnAppForeground` is a deliberate no-op), so here it is [foreground], the root's existing
 * `OnAppForeground` / `OnAppBackground` state. It also GATES the tick: a suspended phone must not hold a
 * timer, and iOS would kill the socket underneath it anyway.
 */
@Composable
fun ClaudeQuotaRefreshEffect(repo: PocketRepository, foreground: Boolean) {
    val policy = androidx.compose.runtime.remember(repo) {
        ClaudeQuotaRefreshPolicy(now = { epochMillis() }, fetch = { force -> repo.fetchClaudeQuota(force) })
    }
    // the in-flight latch opens on ANY reply, success or failure
    DisposableEffect(repo, policy) {
        val mine: () -> Unit = { policy.replied() }
        repo.onClaudeQuotaReply = mine
        onDispose { if (repo.onClaudeQuotaReply === mine) repo.onClaudeQuotaReply = null }
    }
    // rule 1: a ready link — including every reconnect and every machine switch (a different account
    // entirely, whose allowance the previous snapshot says nothing about)
    val ready = repo.phase.value == ConnPhase.Ready
    LaunchedEffect(policy, ready) { if (ready) policy.event(QuotaRefreshTrigger.CONNECTED) else policy.reset() }
    // rule 3: back in the foreground, gated on staleness inside the policy
    LaunchedEffect(policy, foreground) { if (foreground) policy.event(QuotaRefreshTrigger.FOCUSED) }
    // rule 4: a finished turn anywhere on this machine, debounced inside the policy
    val turns = repo.turnCompletions.value
    LaunchedEffect(policy, turns) { if (turns > 0) policy.event(QuotaRefreshTrigger.TURN_DONE) }
    // rule 2 + the turn debounce, pumped only while visible
    LaunchedEffect(policy, foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) { delay(QUOTA_TICK_MS); policy.tick() }
    }
    // the staleness basis is the DAEMON's own fetch moment, not our request moment
    val fetchedAt = repo.claudeQuota.value?.fetchedAt
    LaunchedEffect(policy, fetchedAt) { policy.snapshotFetchedAt(fetchedAt) }
}

/** Coarse on purpose: the shortest deadline the policy has to resolve is the 60s turn debounce. */
private const val QUOTA_TICK_MS = 10_000L
