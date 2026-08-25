package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.quota_5h
import dev.ccpocket.app.resources.quota_5h_short
import dev.ccpocket.app.resources.quota_7d
import dev.ccpocket.app.resources.quota_7d_model
import dev.ccpocket.app.resources.quota_7d_short
import dev.ccpocket.app.resources.quota_used
import dev.ccpocket.app.resources.schedule_wd_1
import dev.ccpocket.app.resources.schedule_wd_2
import dev.ccpocket.app.resources.schedule_wd_3
import dev.ccpocket.app.resources.schedule_wd_4
import dev.ccpocket.app.resources.schedule_wd_5
import dev.ccpocket.app.resources.schedule_wd_6
import dev.ccpocket.app.resources.schedule_wd_7
import dev.ccpocket.app.resources.quota_refresh
import dev.ccpocket.app.resources.quota_reset_at
import dev.ccpocket.app.resources.quota_reset_d
import dev.ccpocket.app.resources.quota_reset_h
import dev.ccpocket.app.resources.quota_reset_m
import dev.ccpocket.app.resources.quota_reset_now
import dev.ccpocket.app.resources.quota_updated_min
import dev.ccpocket.app.resources.quota_updated_now
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_SESSION
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_WARNING
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaLimit
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// ===========================================================================
//  The Claude subscription-allowance rows, shared verbatim between the desktop
//  sidebar popover and the phone's bottom sheet.
//
//  Only TYPEFACE is parameterised: the desktop wears its own Dk.ui/Dk.mono
//  faces, the phone takes the platform defaults. Everything that carries
//  MEANING — which colour a nearly-spent window gets, whether a countdown is
//  shown at all, how "left" is computed — lives here once, because two copies
//  of a rule about running out of allowance is exactly two chances to disagree
//  with each other on screen.
// ===========================================================================

/** Anthropic marks a window `warning` before it is spent. The local threshold is a second, independent
 *  guard: a payload that stops sending `severity` (or sends a value we do not know) must still colour a
 *  nearly-spent window. Erring toward "warn" is the safe direction for a number about running out. */
fun isWarn(l: ClaudeQuotaLimit): Boolean =
    l.severity == CLAUDE_QUOTA_SEVERITY_WARNING || l.percent >= QUOTA_WARN_PCT

const val QUOTA_WARN_PCT = 80

/** True for the rolling 5-hour session window. */
fun isSessionWindow(l: ClaudeQuotaLimit): Boolean =
    l.kind == CLAUDE_QUOTA_KIND_SESSION || l.group == "session"

/** True for any 7-day window — the all-model one and every per-model `weekly_scoped` row. */
fun isWeeklyWindow(l: ClaudeQuotaLimit): Boolean =
    l.group == "weekly" || l.kind.startsWith("weekly")

/**
 * The weekly row the STRIP shows. Defaults to `weekly_all` — the official panel's headline number, so
 * the strip compares 1:1 with what the user sees there (owner decision, 08-24: a scoped cap in the slot
 * read as a wrong number next to the panel's all-models row). A per-model `weekly_scoped` row takes the
 * slot ONLY when it is both in warning territory and worse than the overall — that is the moment it
 * becomes the constraint that will actually stop work, and showing a healthy overall then would lie.
 * Falls back to the worst row of the group when there is no `weekly_all` at all.
 */
fun worstWeekly(limits: List<ClaudeQuotaLimit>): ClaudeQuotaLimit? {
    val weekly = limits.filter { isWeeklyWindow(it) }
    val all = weekly.firstOrNull { it.modelDisplayName == null } ?: return weekly.maxByOrNull { it.percent }
    val scopedThreat = weekly.filter { it.modelDisplayName != null && isWarn(it) && it.percent > all.percent }
        .maxByOrNull { it.percent }
    return scopedThreat ?: all
}

/**
 * The single tightest window across BOTH groups — what the phone's one-slot pill reports. Same logic as
 * [worstWeekly] widened to include the 5-hour window: with room for one number, it must be the number
 * that will actually stop you, whichever clock it belongs to.
 */
fun tightestLimit(limits: List<ClaudeQuotaLimit>): ClaudeQuotaLimit? =
    limits.filter { isSessionWindow(it) || isWeeklyWindow(it) }.maxByOrNull { it.percent }

/** The rows worth rendering, or an empty list for every "nothing to say" state (no snapshot, a transient
 *  failure before the first success, a signed-out/API-key machine). Callers render nothing at all on empty
 *  — a persistent indicator that shouts about its own failures is worse than one that waits quietly. */
fun quotaRows(q: ClaudeQuota?): List<ClaudeQuotaLimit> =
    if (q == null || q.status != CLAUDE_QUOTA_OK) emptyList() else q.limits

/** A clock that advances once a minute. These are hour-scale windows; a per-second tick would only buy
 *  recompositions nobody can see. Shared so both surfaces age their countdowns identically. */
@Composable
fun rememberQuotaClock(): State<Long> {
    val now = remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now.value = epochMillis() } }
    return now
}

/** Which window a row is, tolerant of a vocabulary that is upstream's to grow: a scoped row is named by
 *  its model, otherwise the `group`/`kind` pair decides, and an unrecognised row shows its raw `kind`
 *  rather than being hidden (a window we cannot name is still a window that can run out). */
@Composable
fun quotaLabel(row: ClaudeQuotaLimit): String = when {
    row.modelDisplayName != null -> stringResource(Res.string.quota_7d_model, row.modelDisplayName!!)
    isSessionWindow(row) -> stringResource(Res.string.quota_5h)
    isWeeklyWindow(row) -> stringResource(Res.string.quota_7d)
    else -> row.kind
}

/** The two-character form for a pill with no room for words. Falls back to the full label for a row whose
 *  window we cannot classify — better a long chip than a chip that lies about which clock it means. */
@Composable
fun quotaShortLabel(row: ClaudeQuotaLimit): String = when {
    isSessionWindow(row) -> stringResource(Res.string.quota_5h_short)
    // a scoped weekly cap NAMES its model ("7d·Fable"): the strip shows the worst weekly row, and an
    // unlabelled 16% next to the official panel's all-models 8% read as a wrong number (owner, 08-24)
    isWeeklyWindow(row) && row.modelDisplayName != null -> "${stringResource(Res.string.quota_7d_short)}·${row.modelDisplayName}"
    isWeeklyWindow(row) -> stringResource(Res.string.quota_7d_short)
    else -> row.kind
}

/** "resets in 3h 20m", or null when there is nothing trustworthy to say — an absent/unparseable
 *  `resets_at` must show NO countdown rather than a wrong one. */
@Composable
fun resetText(resetsAt: Long?, now: Long): String? {
    if (resetsAt == null) return null
    val left = resetsAt - now
    if (left <= 0) return stringResource(Res.string.quota_reset_now)
    val mins = left / 60_000
    val hours = mins / 60
    val days = hours / 24
    return when {
        days > 0 -> stringResource(Res.string.quota_reset_d, days.toInt(), (hours % 24).toInt())
        hours > 0 -> stringResource(Res.string.quota_reset_h, hours.toInt(), (mins % 60).toInt())
        else -> stringResource(Res.string.quota_reset_m, mins.toInt().coerceAtLeast(1))
    }
}

/** The STRIP's reset caption: an absolute local clock time ("19:49 重置") when the reset lands today —
 *  stable width, no per-minute churn, and it survives the strip's narrow trailing slot where the
 *  countdown form was getting ellipsized (owner feedback, 08-24). Falls back to the countdown for a
 *  reset on another day (a weekly window as the tightest limit), where a bare clock time would be
 *  ambiguous. The sheet's detail rows keep the countdown — space is not scarce there. */
@Composable
fun stripResetText(resetsAt: Long?, now: Long): String? {
    if (resetsAt == null) return null
    if (resetsAt - now <= 0) return stringResource(Res.string.quota_reset_now)
    val r = dev.ccpocket.app.localClock(resetsAt)
    val n = dev.ccpocket.app.localClock(now)
    val sameDay = r.year == n.year && r.monthOfYear == n.monthOfYear && r.dayOfMonth == n.dayOfMonth
    val hh = r.hour.toString().padStart(2, '0')
    val mm = r.minute.toString().padStart(2, '0')
    // cross-day (a weekly window): weekday + clock, the official panel's own "Resets Mon 1:00 AM" shape —
    // the countdown fallback ("6 天 7 小时后重置") was exactly the long tail the strip cannot afford
    val clock = if (sameDay) "$hh:$mm" else "${quotaWeekday(r.isoDayOfWeek)} $hh:$mm"
    return stringResource(Res.string.quota_reset_at, clock)
}

@Composable
private fun quotaWeekday(iso: Int): String = stringResource(
    when (iso) {
        1 -> Res.string.schedule_wd_1; 2 -> Res.string.schedule_wd_2; 3 -> Res.string.schedule_wd_3
        4 -> Res.string.schedule_wd_4; 5 -> Res.string.schedule_wd_5; 6 -> Res.string.schedule_wd_6
        else -> Res.string.schedule_wd_7
    },
)

/** How old the numbers are. Ages from the DAEMON's fetch moment, so a reply served out of its own 60s
 *  cache reports the age of the DATA rather than the age of our request. */
@Composable
fun quotaUpdatedText(fetchedAt: Long, now: Long): String {
    val mins = if (fetchedAt <= 0) 0 else ((now - fetchedAt) / 60_000).coerceAtLeast(0)
    return if (mins < 1) stringResource(Res.string.quota_updated_now)
    else stringResource(Res.string.quota_updated_min, mins.toInt())
}

/**
 * One full window row: label · bar · remaining · reset countdown. The shape the desktop popover and the
 * phone sheet both render.
 *
 * [uiFont]/[monoFont] are the only per-platform knobs (the desktop passes its Dk faces). [labelWidth]
 * keeps the bars of successive rows aligned in a column; a wider sheet can afford more.
 */
@Composable
fun QuotaLimitRow(
    row: ClaudeQuotaLimit,
    now: Long,
    modifier: Modifier = Modifier,
    uiFont: FontFamily? = null,
    monoFont: FontFamily = FontFamily.Monospace,
    labelWidth: Dp = 104.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
) {
    val warn = isWarn(row)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // every Text on this row is geometrically centred against the 2-5dp bar beside it, so EVERY one
        // of them needs the pinned line box — one Text left on font metrics is enough to knock the row
        // out of alignment on a platform whose fallback face measures differently (#293)
        Text(
            quotaLabel(row), color = Tok.tx2, fontFamily = uiFont, fontSize = fontSize,
            style = tightCenter(fontSize),
            // the window upstream calls "active" is the one actually binding right now — worth a glance
            fontWeight = if (row.isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, modifier = Modifier.width(labelWidth),
        )
        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(999.dp)).background(Tok.hair)) {
            // fills by USED, mirroring the official claude.ai usage panel ("77% used") so the numbers
            // compare 1:1 with what the user sees there (owner decision, 08-24)
            if (row.percent > 0) {
                Box(Modifier.fillMaxWidth(row.percent.coerceIn(0, 100) / 100f).fillMaxHeight().background(if (warn) Tok.warn else Tok.accent))
            }
        }
        Text(
            stringResource(Res.string.quota_used, "${row.percent.coerceIn(0, 100)}%"),
            color = if (warn) Tok.warn else Tok.tx, fontFamily = monoFont, fontSize = fontSize, maxLines = 1,
            style = tightCenter(fontSize),
            modifier = Modifier.padding(start = 9.dp),
        )
        resetText(row.resetsAt, now)?.let {
            Text(
                it, color = Tok.muted, fontFamily = uiFont, fontSize = fontSize * 0.86f, maxLines = 1,
                style = tightCenter(fontSize * 0.86f), modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

/**
 * The footer of both detail surfaces: how old the numbers are, and a manual refresh.
 *
 * [refreshDecoration] lets the desktop hang its hover fill on the button without dragging a desktop-only
 * modifier into common code.
 */
@Composable
fun QuotaFreshnessRow(
    fetchedAt: Long,
    now: Long,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    uiFont: FontFamily? = null,
    monoFont: FontFamily = FontFamily.Monospace,
    refreshDecoration: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            quotaUpdatedText(fetchedAt, now), color = Tok.muted, fontFamily = monoFont,
            fontSize = fontSize * 0.86f, style = tightCenter(fontSize * 0.86f), modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clip(RoundedCornerShape(7.dp)).then(refreshDecoration)
                // MANUAL is the one trigger that bypasses the daemon's own 60s cache too: someone pressing
                // refresh is doing it precisely because they doubt what is on screen
                .clickable(onClick = onRefresh)
                .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = Tok.tx2, modifier = Modifier.size(12.dp))
            Text(
                stringResource(Res.string.quota_refresh), color = Tok.tx2, fontFamily = uiFont,
                fontSize = fontSize, style = tightCenter(fontSize),
            )
        }
    }
}
