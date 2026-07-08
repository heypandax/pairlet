package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.usage_by_model
import dev.ccpocket.app.resources.usage_cache
import dev.ccpocket.app.resources.usage_cost
import dev.ccpocket.app.resources.usage_empty
import dev.ccpocket.app.resources.usage_empty_hint
import dev.ccpocket.app.resources.usage_less
import dev.ccpocket.app.resources.usage_more
import dev.ccpocket.app.resources.usage_offline
import dev.ccpocket.app.resources.usage_peak
import dev.ccpocket.app.resources.usage_per_day
import dev.ccpocket.app.resources.usage_per_hour
import dev.ccpocket.app.resources.usage_requests
import dev.ccpocket.app.resources.usage_title
import dev.ccpocket.app.resources.usage_tokens_today
import dev.ccpocket.app.resources.usage_trend
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.UsageDay
import dev.ccpocket.protocol.UsageModel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.round

/** Two-decimal money for the cost card ("$4.20"), no platform locale formatter needed. */
private fun money(v: Double): String {
    val cents = round(v * 100).toLong()
    return "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/**
 * Usage — a calm token dashboard (design cc-pocket/Usage.html, issue #26). Reached from Settings. Four states:
 * loading / offline / empty / populated. The range switch (Today/7d/30d) re-fetches; the stat cards are always
 * for today, the trend + by-model cover the window. Data is aggregated by the daemon from Claude transcripts.
 */
@Composable
fun UsageScreen(repo: PocketRepository, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    var days by remember { mutableStateOf(1) } // default to "Today"
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(days) {
        timedOut = false
        repo.fetchUsage(days)
        kotlinx.coroutines.delay(10_000)
        // no reply after the deadline → most likely an older daemon that can't aggregate usage yet (or a huge scan)
        if (repo.usage.value == null) timedOut = true
    }

    val u = repo.usage.value
    val connected = repo.phase.value == ConnPhase.Ready

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        // header
        Column(Modifier.fillMaxWidth().background(Tok.base)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Text(stringResource(Res.string.usage_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).padding(2.dp)) {
                    for ((label, d) in listOf("Today" to 1, "7d" to 7, "30d" to 30)) {
                        val on = d == days
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).then(if (on) Modifier.background(Tok.accent) else Modifier)
                                .clickable { days = d }.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(label, color = if (on) Tok.base else Tok.tx2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        }

        when {
            u != null && (u.tokensToday > 0 || u.models.isNotEmpty() || u.days.any { it.tokens > 0 }) -> Populated(u)
            u != null -> Empty()
            !connected || timedOut -> Offline()
            else -> Loading()
        }
    }
}

@Composable
private fun Populated(u: Usage) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val today = u.days.lastOrNull()?.tokens ?: u.tokensToday
        val yesterday = u.days.getOrNull(u.days.size - 2)?.tokens
        val delta = yesterday?.takeIf { it > 0 }?.let { ((today - it) * 100 / it).toInt() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Modifier.weight(1f), formatTokens(u.tokensToday), stringResource(Res.string.usage_tokens_today), delta)
            StatCard(Modifier.weight(1f), u.requestsToday.toString(), stringResource(Res.string.usage_requests), null)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Modifier.weight(1f), u.costUsdToday?.let { money(it) } ?: "—", stringResource(Res.string.usage_cost), null)
            StatCard(Modifier.weight(1f), u.cacheHitPct?.let { "$it%" } ?: "—", stringResource(Res.string.usage_cache), null, arcPct = u.cacheHitPct)
        }

        // Each range answers its own question: Today → hourly bars, 7d → daily bars, 30d → a calendar heatmap.
        // Branch on the reply's own shape (span == days.size), not the selected tab — while a fetch is in flight
        // the stale reply keeps rendering as its own coherent view. A span-1 reply without hours (older daemon)
        // shows no trend at all: one flat day-block would only repeat the stat cards.
        val hrs = if (u.days.size == 1) u.hours else null
        if (hrs != null || u.days.size >= 2) {
            SLabel(stringResource(Res.string.usage_trend), right = stringResource(if (hrs != null) Res.string.usage_per_hour else Res.string.usage_per_day))
            when {
                hrs != null -> { HourlyBars(hrs); PeakRow(hrs.maxByOrNull { it.tokens }) }
                u.days.size >= 30 -> Heatmap(u.days) // draws its own peak caption + less→more legend under the grid
                else -> { Bars(u.days); PeakRow(u.days.maxByOrNull { it.tokens }) }
            }
        }

        if (u.models.isNotEmpty()) {
            SLabel(stringResource(Res.string.usage_by_model))
            val max = u.models.maxOf { it.tokens }.coerceAtLeast(1)
            for (m in u.models) ModelRow(m, max)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String, deltaPct: Int?, arcPct: Int? = null) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Tok.tx2, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(value, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (arcPct != null) Arc(arcPct)
        }
        if (deltaPct != null) {
            val up = deltaPct >= 0
            val c = if (up) Tok.warn else Tok.ok
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (up) "↑" else "↓", color = c, fontSize = 11.sp)
                Spacer(Modifier.width(3.dp))
                Text("${if (up) deltaPct else -deltaPct}%", color = c, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

/** Terracotta progress arc for cache-hit. */
@Composable
private fun Arc(pct: Int) {
    Canvas(Modifier.size(40.dp).rotate(-90f)) {
        val stroke = 3.5.dp.toPx()
        val inset = stroke / 2
        val sz = Size(size.width - stroke, size.height - stroke)
        drawArc(Tok.raised, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(stroke))
        drawArc(Tok.accent, 0f, 360f * (pct.coerceIn(0, 100) / 100f), false, Offset(inset, inset), sz, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun Bars(days: List<UsageDay>) {
    val max = days.maxOfOrNull { it.tokens }?.coerceAtLeast(1) ?: 1
    val peakIdx = days.indexOfFirst { it.tokens == days.maxOfOrNull { d -> d.tokens } }
    Row(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, d ->
            val frac = (d.tokens.toFloat() / max).coerceIn(0.03f, 1f)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth().height((frac * 100).dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (i == peakIdx) Tok.accent else Tok.accent.copy(alpha = 0.42f)))
                if (days.size <= 10) {
                    Spacer(Modifier.height(6.dp))
                    Text(d.label, color = if (i == peakIdx) Tok.tx2 else Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
}

/** Today's 24 hourly buckets. Same 120dp band as [Bars]; the peak hour is full accent, the rest faded; a 3%
 *  stub keeps empty hours on the axis. Only 0/6/12/18 carry a label so the 24-wide row never crowds. */
@Composable
private fun HourlyBars(hours: List<UsageDay>) {
    val maxTok = hours.maxOfOrNull { it.tokens } ?: 0L
    val max = maxTok.coerceAtLeast(1L)
    val peakIdx = hours.indexOfFirst { it.tokens == maxTok }
    Row(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        hours.forEachIndexed { i, h ->
            val frac = (h.tokens.toFloat() / max).coerceIn(0.03f, 1f)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth().height((frac * 100).dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (i == peakIdx) Tok.accent else Tok.accent.copy(alpha = 0.42f)))
                Spacer(Modifier.height(6.dp))
                // sparse axis: label 0/6/12/18 only; the rest keep an empty placeholder so bars stay aligned
                Text(if (i % 6 == 0) i.toString() else "", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
}

/** 30d calendar heatmap (GitHub-contribution style): 7 columns Mon→Sun, oldest week on top, today on the
 *  bottom row, first day offset by its weekday. Cell intensity = quartile of the window's max; today gets a
 *  hairline ring. Colors are read from [Tok] at draw time (reactive palette — never hoist them to a top val). */
@Composable
private fun Heatmap(days: List<UsageDay>) {
    if (days.isEmpty()) return
    val lead = weekdayMon0(days.first()) // leading blanks so day 0 sits under its weekday column
    val rows = (lead + days.size + 6) / 7
    val max = days.maxOf { it.tokens } // non-empty guarded above; quartile handles max == 0
    val alphas = listOf(0.25f, 0.45f, 0.70f, 1.0f)
    val todayIdx = days.lastIndex
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in COL_LETTERS) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(c, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c - lead
                    val d = days.getOrNull(idx)
                    val bg = when {
                        d == null -> Tok.raised.copy(alpha = 0.4f) // out-of-window filler
                        d.tokens == 0L -> Tok.raised
                        else -> Tok.accent.copy(alpha = alphas[quartile(d.tokens, max) - 1])
                    }
                    val ring = if (idx == todayIdx) Modifier.border(1.dp, Tok.hair, RoundedCornerShape(3.dp)) else Modifier
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(bg).then(ring))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PeakRow(days.maxByOrNull { it.tokens }, labelOf = ::peakLabel) }
            Text(stringResource(Res.string.usage_less), color = Tok.muted, fontSize = 10.sp)
            Spacer(Modifier.width(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Tok.raised))
                for (a in alphas) Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Tok.accent.copy(alpha = a)))
            }
            Spacer(Modifier.width(5.dp))
            Text(stringResource(Res.string.usage_more), color = Tok.muted, fontSize = 10.sp)
        }
    }
}

/** The "Peak Wed · 12.7M" caption under a trend chart; nothing when the window is all zeros. */
@Composable
private fun PeakRow(peak: UsageDay?, labelOf: (UsageDay) -> String = { it.label }) {
    val p = peak?.takeIf { it.tokens > 0 } ?: return
    Text(stringResource(Res.string.usage_peak, labelOf(p), formatTokens(p.tokens)), color = Tok.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val COL_LETTERS = WEEKDAY_LABELS.map { it.take(1) }

/** Monday=0 … Sunday=6 for a trend bucket. Prefer the ISO [UsageDay.date] (Sakamoto's algorithm — commonMain
 *  has no date library); fall back to the daemon's English short label ("Mon".."Sun") when date is null. */
internal fun weekdayMon0(day: UsageDay): Int {
    day.date?.split('-')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 3 && it[1] in 1..12 }
        ?.let { (y, m, d) -> return sakamotoMon0(y, m, d) }
    return WEEKDAY_LABELS.indexOfFirst { day.label.startsWith(it, ignoreCase = true) }.coerceAtLeast(0)
}

// Sakamoto's day-of-week (0=Sunday..6=Saturday), remapped so Monday=0..Sunday=6.
private fun sakamotoMon0(y: Int, m: Int, d: Int): Int {
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val yy = if (m < 3) y - 1 else y
    val dow = (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    return (dow + 6) % 7
}

/** Which of four intensity buckets [tokens] falls in (1..4), by fraction of the window [max]. */
internal fun quartile(tokens: Long, max: Long): Int {
    val f = tokens.toFloat() / max.coerceAtLeast(1L)
    return when {
        f <= 0.25f -> 1
        f <= 0.50f -> 2
        f <= 0.75f -> 3
        else -> 4
    }
}

/** The peak day's caption label: ISO [UsageDay.date] "2026-07-05" → "07-05"; falls back to the weekday [UsageDay.label]. */
internal fun peakLabel(day: UsageDay): String = day.date?.substringAfter('-') ?: day.label

@Composable
private fun ModelRow(m: UsageModel, max: Long) {
    val color = if (m.agent == AgentKind.CODEX) Tok.codex else Tok.accent
    Column(Modifier.padding(vertical = 9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(m.model, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(formatTokens(m.tokens), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)).background(Tok.raised)) {
            Box(Modifier.fillMaxWidth((m.tokens.toFloat() / max).coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(999.dp)).background(color))
        }
    }
}

@Composable
private fun SLabel(text: String, right: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text.uppercase(), color = Tok.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
        right?.let { Text(it, color = Tok.muted, fontSize = 11.5.sp) }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Tok.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun Empty() {
    Column(Modifier.fillMaxSize().padding(horizontal = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(Res.string.usage_empty), color = Tok.tx2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(Res.string.usage_empty_hint), color = Tok.muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Offline() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(stringResource(Res.string.usage_offline), color = Tok.tx2, fontSize = 12.5.sp) }
    }
}
