package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.localClock
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.limit_autocontinue
import dev.ccpocket.app.resources.limit_banner
import dev.ccpocket.app.resources.limit_confirmed
import dev.ccpocket.app.resources.limit_resets
import dev.ccpocket.app.resources.limit_undo
import dev.ccpocket.app.resources.schedule_confirm
import dev.ccpocket.app.resources.schedule_confirm_daily
import dev.ccpocket.app.resources.schedule_custom_time
import dev.ccpocket.app.resources.schedule_done
import dev.ccpocket.app.resources.schedule_due_now
import dev.ccpocket.app.resources.schedule_empty
import dev.ccpocket.app.resources.schedule_empty_hint
import dev.ccpocket.app.resources.schedule_last_failed
import dev.ccpocket.app.resources.schedule_meta_new
import dev.ccpocket.app.resources.schedule_meta_resume
import dev.ccpocket.app.resources.schedule_missed
import dev.ccpocket.app.resources.schedule_next_at
import dev.ccpocket.app.resources.schedule_next_run
import dev.ccpocket.app.resources.schedule_opt_1h
import dev.ccpocket.app.resources.schedule_opt_30m
import dev.ccpocket.app.resources.schedule_opt_3h
import dev.ccpocket.app.resources.schedule_opt_8h
import dev.ccpocket.app.resources.schedule_remove
import dev.ccpocket.app.resources.schedule_repeat_caption
import dev.ccpocket.app.resources.schedule_repeat_daily
import dev.ccpocket.app.resources.schedule_repeat_every
import dev.ccpocket.app.resources.schedule_repeat_toggle
import dev.ccpocket.app.resources.schedule_send_title
import dev.ccpocket.app.resources.schedule_stale_body
import dev.ccpocket.app.resources.schedule_stale_title
import dev.ccpocket.app.resources.schedule_tasks_title
import dev.ccpocket.app.resources.schedule_wd_1
import dev.ccpocket.app.resources.schedule_wd_2
import dev.ccpocket.app.resources.schedule_wd_3
import dev.ccpocket.app.resources.schedule_wd_4
import dev.ccpocket.app.resources.schedule_wd_5
import dev.ccpocket.app.resources.schedule_wd_6
import dev.ccpocket.app.resources.schedule_wd_7
import dev.ccpocket.app.resources.settings_title
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleRepeat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Compact language-neutral duration: "12m" / "3h 05m" / "2d 4h". Never negative. */
fun etaShort(deltaMs: Long): String {
    val m = (deltaMs / 60_000).coerceAtLeast(0)
    return when {
        m >= 1440 -> "${m / 1440}d ${(m % 1440) / 60}h"
        m >= 60 -> "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
        else -> "${m}m"
    }
}

/** Local wall-clock "23:30" of an instant — the resolved absolute times all three schedule surfaces carry. */
fun hhmm(epochMs: Long): String = localClock(epochMs).let {
    "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
}

// ── glyphs (stroke ImageVectors, verbatim from the design SVGs — scheduled-prompts.jsx) ──
private fun scheduleGlyph(name: String, viewport: Float, stroke: Float, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = viewport.dp, defaultHeight = viewport.dp, viewportWidth = viewport, viewportHeight = viewport)
        .apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

/** Clock face + hands (jsx IconClock, 20-viewport, 1.5pt). */
private val ClockGlyph: ImageVector by lazy {
    scheduleGlyph("SchedClock", 20f, 1.5f) {
        moveTo(10f, 2.6f)
        arcTo(7.4f, 7.4f, 0f, false, true, 10f, 17.4f)
        arcTo(7.4f, 7.4f, 0f, false, true, 10f, 2.6f)
        moveTo(10f, 5.6f); verticalLineTo(10f); lineTo(13f, 11.9f)
    }
}

/** Two-arrow repeat loop (jsx IconRepeat, 18-viewport, 1.6pt). */
private val RepeatGlyph: ImageVector by lazy {
    scheduleGlyph("SchedRepeat", 18f, 1.6f) {
        moveTo(3f, 8f); verticalLineTo(7f)
        arcTo(3f, 3f, 0f, false, true, 6f, 4f); horizontalLineTo(12f); lineTo(10.4f, 2.4f)
        moveTo(15f, 10f); verticalLineTo(11f)
        arcTo(3f, 3f, 0f, false, true, 12f, 14f); horizontalLineTo(6f); lineTo(7.6f, 15.6f)
    }
}

/** Hourglass (jsx IconHourglass, 16-viewport, 1.4pt) — the limit-banner offer state. */
private val HourglassGlyph: ImageVector by lazy {
    scheduleGlyph("SchedHourglass", 16f, 1.4f) {
        moveTo(4f, 2f); horizontalLineTo(12f)
        moveTo(4f, 14f); horizontalLineTo(12f)
        moveTo(4.6f, 2f); curveTo(4.6f, 5f, 7f, 6.4f, 8f, 7f); curveTo(9f, 6.4f, 11.4f, 5f, 11.4f, 2f)
        moveTo(4.6f, 14f); curveTo(4.6f, 11f, 7f, 9.6f, 8f, 9f); curveTo(9f, 9.6f, 11.4f, 11f, 11.4f, 14f)
    }
}

/** Warning triangle (jsx IconWarnTri, 16-viewport) — stale-daemon banner + failed-run caption. */
private val WarnTriGlyph: ImageVector by lazy {
    scheduleGlyph("SchedWarnTri", 16f, 1.4f) {
        moveTo(8f, 2.2f); lineTo(14f, 12.6f); horizontalLineTo(2f); close()
        moveTo(8f, 6.6f); verticalLineTo(9.6f)
        moveTo(8f, 11.2f); verticalLineTo(11.21f)
    }
}

/** Check (jsx IconCheck, 16-viewport, 2pt) — the limit-banner confirmed state. */
private val CheckGlyph: ImageVector by lazy {
    scheduleGlyph("SchedCheck", 16f, 2f) { moveTo(3f, 8.4f); lineTo(6.1f, 11.5f); lineTo(13f, 4.6f) }
}

/** ✕ (jsx IconX, 16-viewport, 1.7pt) — the task card's quiet cancel. */
private val XGlyph: ImageVector by lazy {
    scheduleGlyph("SchedX", 16f, 1.7f) { moveTo(4f, 4f); lineTo(12f, 12f); moveTo(12f, 4f); lineTo(4f, 12f) }
}

/** Chevrons (jsx IconChevron, 20-viewport, 1.9pt). */
private val ChevronRightGlyph: ImageVector by lazy {
    scheduleGlyph("SchedChevR", 20f, 1.9f) { moveTo(7f, 4f); lineTo(13f, 10f); lineTo(7f, 16f) }
}
private val ChevronDownGlyph: ImageVector by lazy {
    scheduleGlyph("SchedChevD", 20f, 1.9f) { moveTo(4f, 7f); lineTo(10f, 13f); lineTo(16f, 7f) }
}
private val ChevronLeftGlyph: ImageVector by lazy {
    scheduleGlyph("SchedChevL", 20f, 1.9f) { moveTo(13f, 4f); lineTo(7f, 10f); lineTo(13f, 16f) }
}

/**
 * The composer's long-press "schedule send" sheet (issue #137): one-shot delay presets + an optional
 * "repeat daily at this time" toggle (a 24h-interval repeat, anchored to the first fire). The message
 * targets the OPEN session — the daemon resumes it at fire time even with this phone offline.
 */
@Composable
fun ScheduleSendSheet(
    text: String,
    onSchedule: (runAtMs: Long, repeat: ScheduleRepeat?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        30 * 60_000L to stringResource(Res.string.schedule_opt_30m),
        60 * 60_000L to stringResource(Res.string.schedule_opt_1h),
        3 * 3600_000L to stringResource(Res.string.schedule_opt_3h),
        8 * 3600_000L to stringResource(Res.string.schedule_opt_8h),
    )
    // design A1: the 1h chip is pre-selected; opening "Custom time…" deselects the chips (null)
    var picked by remember { mutableStateOf<Long?>(options[1].first) }
    var customOpen by remember { mutableStateOf(false) }
    var daily by remember { mutableStateOf(false) }
    // minute ticker so the resolved absolute time on the button stays honest while the sheet sits open
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = epochMillis() } }
    // local midnight, the wheel's time base (second-level truncation is plenty for a minute picker)
    val startOfToday = remember(now) {
        val c = localClock(now)
        now - (c.hour * 3600L + c.minute * 60L + c.second) * 1000L
    }
    val wheel = rememberCustomTimeState()
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            // clock glyph + title (design: 19px glyph, 18sp/700)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(ClockGlyph, null, tint = Tok.tx, modifier = Modifier.size(19.dp))
                Text(stringResource(Res.string.schedule_send_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            // the staged message as a quote (left hairline rule), so what's being scheduled is unambiguous
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Tok.hair))
                Text(
                    text, color = Tok.tx2, fontSize = 13.5.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                )
            }
            // quick-pick chips: single-select, terracotta soft fill when active (design Chip)
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                options.forEach { (delta, label) ->
                    val sel = !customOpen && picked == delta
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (sel) Tok.accent.copy(alpha = 0.16f) else Color.Transparent)
                            .border(1.dp, if (sel) Tok.accent else Tok.hair, RoundedCornerShape(11.dp))
                            .clickable { picked = delta; customOpen = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = if (sel) Tok.accent else Tok.tx2, fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                    }
                }
            }
            // "Custom time…" row — expands into the inline day/hour/minute wheel (design A2)
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { customOpen = !customOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.schedule_custom_time),
                        color = if (customOpen) Tok.tx else Tok.tx2, fontSize = 15.5.sp, modifier = Modifier.weight(1f),
                    )
                    if (customOpen) {
                        Text(
                            wheel.dayLabel(startOfToday), color = Tok.accent, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Icon(
                        if (customOpen) ChevronDownGlyph else ChevronRightGlyph, null,
                        tint = if (customOpen) Tok.accent else Tok.muted, modifier = Modifier.size(18.dp),
                    )
                }
                if (customOpen) CustomTimeWheel(wheel, startOfToday)
            }
            // resolved fire time drives every label below (chips = now + delta; wheel = picked instant)
            val resolvedAt = if (customOpen) wheel.runAtMs(startOfToday) else now + (picked ?: 0L)
            val resolvedHm = hhmm(resolvedAt)
            ScheduleRepeatRow(daily, resolvedHm) { daily = it }
            ScheduleSheetFooter(
                daily = daily, customOpen = customOpen, resolvedHm = resolvedHm,
                dayLabel = wheel.dayLabel(startOfToday), enabled = daily || resolvedAt > now,
                onConfirm = {
                    val runAt = if (customOpen) wheel.runAtMs(startOfToday) else epochMillis() + (picked ?: 0L)
                    onSchedule(runAt, if (daily) ScheduleRepeat(intervalMs = 24 * 3600_000L) else null)
                    onDismiss()
                },
                onCancel = onDismiss,
            )
        }
    }
}

/** "Repeat daily" row (design A3): hairline-framed, terracotta toggle + resolved-time caption when on. */
@Composable
private fun ScheduleRepeatRow(daily: Boolean, resolvedHm: String, onToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onToggle(!daily) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.schedule_repeat_toggle), color = Tok.tx, fontSize = 15.5.sp)
                if (daily) {
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(RepeatGlyph, null, tint = Tok.accent, modifier = Modifier.size(13.dp))
                        Text(
                            stringResource(Res.string.schedule_repeat_caption, resolvedHm),
                            color = Tok.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            ScheduleToggle(daily)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

// the inline wheel's geometry: 30dp rows in a 118dp viewport → the middle row is the selection
private const val WHEEL_DAYS = 14
private val WheelRowHeight = 30.dp
private val WheelHeight = 118.dp

/** The custom-time wheel's selection (design A2): day = today+n, hour, minute in 5-min steps.
 *  Each column is a snapping LazyColumn whose centered row IS the value — no separate state to sync. */
class CustomTimeState internal constructor(
    val day: LazyListState, val hour: LazyListState, val minute: LazyListState,
) {
    internal var rowPx = 1f // set by the wheel from the live density; only affects mid-scroll labels
    private fun idx(s: LazyListState, count: Int) =
        (s.firstVisibleItemIndex + (s.firstVisibleItemScrollOffset / rowPx).roundToInt()).coerceIn(0, count - 1)
    val dayIndex: Int get() = idx(day, WHEEL_DAYS)
    val hourIndex: Int get() = idx(hour, 24)
    val minuteIndex: Int get() = idx(minute, 12)

    /** The picked instant. Day arithmetic is flat 24h from local midnight (a DST-shifted day is off by
     *  that hour — acceptable for a schedule picker; CN has no DST). */
    fun runAtMs(startOfToday: Long): Long =
        startOfToday + dayIndex * 86_400_000L + hourIndex * 3_600_000L + minuteIndex * 5 * 60_000L
}

@Composable
private fun rememberCustomTimeState(): CustomTimeState {
    val day = rememberLazyListState(1) // default: tomorrow…
    val hour = rememberLazyListState(8) // …at 08:00
    val minute = rememberLazyListState(0)
    return remember { CustomTimeState(day, hour, minute) }
}

/** "Thu 16" for the selected day — the row caption and the confirm button's date part. */
@Composable
private fun CustomTimeState.dayLabel(startOfToday: Long): String {
    val c = localClock(startOfToday + dayIndex * 86_400_000L + 43_200_000L) // noon dodges DST edges
    return "${weekdayShort(c.isoDayOfWeek)} ${c.dayOfMonth}"
}

@Composable
private fun weekdayShort(iso: Int): String = stringResource(
    when (iso) {
        1 -> Res.string.schedule_wd_1; 2 -> Res.string.schedule_wd_2; 3 -> Res.string.schedule_wd_3
        4 -> Res.string.schedule_wd_4; 5 -> Res.string.schedule_wd_5; 6 -> Res.string.schedule_wd_6
        else -> Res.string.schedule_wd_7
    },
)

/** The inline day/hour/minute wheel (design WheelPicker): base well, center selection band, mono values. */
@Composable
private fun CustomTimeWheel(wheel: CustomTimeState, startOfToday: Long) {
    wheel.rowPx = with(LocalDensity.current) { WheelRowHeight.toPx() }
    val days = (0 until WHEEL_DAYS).map { d ->
        val c = localClock(startOfToday + d * 86_400_000L + 43_200_000L)
        "${weekdayShort(c.isoDayOfWeek)} ${c.dayOfMonth}"
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
    ) {
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().height(34.dp)
                .clip(RoundedCornerShape(9.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(9.dp)),
        )
        Row(Modifier.fillMaxWidth()) {
            WheelColumn(days, wheel.day, Modifier.weight(1.5f)) { wheel.dayIndex }
            WheelColumn((0..23).map { it.toString().padStart(2, '0') }, wheel.hour, Modifier.weight(1f)) { wheel.hourIndex }
            WheelColumn((0..11).map { ":" + (it * 5).toString().padStart(2, '0') }, wheel.minute, Modifier.weight(1f)) { wheel.minuteIndex }
        }
    }
}

/** One snapping wheel column: 5 visible rows, the centered one is selected (largest/brightest). */
@Composable
private fun WheelColumn(values: List<String>, state: LazyListState, modifier: Modifier, selected: () -> Int) {
    LazyColumn(
        state = state, flingBehavior = rememberSnapFlingBehavior(state),
        modifier = modifier.height(WheelHeight),
        contentPadding = PaddingValues(vertical = (WheelHeight - WheelRowHeight) / 2),
    ) {
        itemsIndexed(values) { i, v ->
            val sel = selected()
            val dist = if (i >= sel) i - sel else sel - i
            Box(
                Modifier.fillMaxWidth().height(WheelRowHeight).alpha(if (dist > 2) 0.35f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    v, fontFamily = FontFamily.Monospace,
                    fontSize = if (i == sel) 17.sp else 15.sp,
                    fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = when { i == sel -> Tok.tx; dist == 1 -> Tok.tx2; else -> Tok.muted },
                )
            }
        }
    }
}

/** Design toggle (46×28, white knob): terracotta track when on, raised + hairline when off. */
@Composable
private fun ScheduleToggle(on: Boolean) {
    Box(
        Modifier.size(width = 46.dp, height = 28.dp).clip(CircleShape)
            .background(if (on) Tok.accent else Tok.raised)
            .border(1.dp, if (on) Tok.accent else Tok.hair, CircleShape),
    ) {
        Box(
            Modifier.padding(start = if (on) 20.dp else 2.5.dp, top = 2.5.dp)
                .size(22.dp).clip(CircleShape).background(Color.White),
        )
    }
}

/** Footer (design A): full-width terracotta button carrying the resolved absolute time, then Cancel. */
@Composable
private fun ScheduleSheetFooter(
    daily: Boolean, customOpen: Boolean, resolvedHm: String, dayLabel: String,
    enabled: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp).alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(13.dp)).background(Tok.accent)
            .clickable(enabled = enabled, onClick = onConfirm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (daily) Icon(RepeatGlyph, null, tint = Tok.base, modifier = Modifier.size(15.dp))
        Text(
            if (daily) stringResource(Res.string.schedule_confirm_daily, resolvedHm)
            else stringResource(Res.string.schedule_confirm, if (customOpen) "$dayLabel, $resolvedHm" else resolvedHm),
            color = Tok.base, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.cancel), color = Tok.tx2, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(10.dp),
        )
    }
}

/**
 * The usage-limit auto-continue banner (issue #137): shown above the composer when the last turn died
 * on a usage limit whose reset moment the daemon could parse. One tap schedules a "Continue" back into
 * this session shortly after the window flips.
 */
@Composable
fun LimitResetBanner(repo: PocketRepository) {
    val convo = repo.convoId.value
    val offer = repo.limitOffer.value?.takeIf { it.convoId == convo }
    val confirmed = repo.limitConfirmed.value?.takeIf { it.convoId == convo }
    if (offer == null && confirmed == null) return
    // both states share one slim card frame (design C: same 52dp height — flipping never shifts the composer)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)
            .heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (confirmed != null) Tok.surface else Tok.warn.copy(alpha = 0.13f))
            .border(1.dp, if (confirmed != null) Tok.hair else Tok.warn.copy(alpha = 0.33f), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (confirmed != null) LimitConfirmedContent(repo, confirmed) else LimitOfferContent(repo, offer!!)
    }
}

/** Offer state (design C1): hourglass + "Usage limit hit — resets 21:20", compact terracotta button. */
@Composable
private fun RowScope.LimitOfferContent(repo: PocketRepository, offer: PocketRepository.LimitOffer) {
    Icon(HourglassGlyph, null, tint = Tok.warn, modifier = Modifier.size(17.dp))
    val title = buildAnnotatedString {
        withStyle(SpanStyle(color = Tok.tx, fontWeight = FontWeight.SemiBold)) { append(stringResource(Res.string.limit_banner)) }
        append(" ")
        withStyle(SpanStyle(color = Tok.tx2)) { append(stringResource(Res.string.limit_resets, hhmm(offer.resetAtMs))) }
    }
    Text(title, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(Tok.accent)
            .clickable { repo.scheduleAutoContinue() }.padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.limit_autocontinue), color = Tok.base, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Confirmed state (design C2): check + the resolved continue time, plain-text Undo. */
@Composable
private fun RowScope.LimitConfirmedContent(repo: PocketRepository, confirmed: PocketRepository.LimitOffer) {
    Icon(CheckGlyph, null, tint = Tok.ok, modifier = Modifier.size(17.dp))
    Text(
        stringResource(Res.string.limit_confirmed, hhmm(confirmed.resetAtMs + PocketRepository.LIMIT_RESUME_MARGIN_MS)),
        color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
    )
    Text(
        stringResource(Res.string.limit_undo), color = Tok.accent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { repo.undoAutoContinue() }.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** Scheduled-tasks management (issue #137): list + cancel; repeats show their next fire time. */
@Composable
fun ScheduleScreen(repo: PocketRepository, onBack: () -> Unit) {
    LaunchedEffect(Unit) { repo.fetchSchedules() }
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = epochMillis() } }
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        // nav (design TasksNav): accent back chevron + "Settings", centered title, hairline bottom rule
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(
                Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(ChevronLeftGlyph, null, tint = Tok.accent, modifier = Modifier.size(20.dp))
                Text(stringResource(Res.string.settings_title), color = Tok.accent, fontSize = 15.sp)
            }
            Text(
                stringResource(Res.string.schedule_tasks_title), color = Tok.tx,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        repo.scheduleError.value?.let {
            Text(it, color = Tok.danger, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        val stale = repo.schedulesUnavailable.value
        if (stale) StaleDaemonBanner()
        if (repo.schedulesLoaded.value && !stale && repo.schedules.isEmpty()) {
            ScheduleEmptyState()
        } else {
            // stale: cached tasks stay visible but dim — they're paused, not gone (design B3)
            LazyColumn(
                Modifier.fillMaxSize().alpha(if (stale) 0.55f else 1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(repo.schedules, key = { it.id }) { s ->
                    ScheduleRow(s, now, onCancel = { repo.cancelSchedule(s.id) })
                }
            }
        }
    }
}

/** Stale-daemon banner (design B3): warm warning card — schedules need a newer computer-side cc-pocket. */
@Composable
private fun StaleDaemonBanner() {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .clip(RoundedCornerShape(12.dp)).background(Tok.warn.copy(alpha = 0.13f))
            .border(1.dp, Tok.warn.copy(alpha = 0.33f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WarnTriGlyph, null, tint = Tok.warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Column {
            Text(
                stringResource(Res.string.schedule_stale_title), color = Tok.tx,
                fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
            )
            Text(
                stringResource(Res.string.schedule_stale_body), color = Tok.tx2,
                fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Empty state (design B2): calm clock tile + a hint pointing back at the entry point. */
@Composable
private fun ScheduleEmptyState() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(ClockGlyph, null, tint = Tok.muted, modifier = Modifier.size(24.dp)) }
        Text(
            stringResource(Res.string.schedule_empty), color = Tok.tx2, fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            stringResource(Res.string.schedule_empty_hint), color = Tok.muted, fontSize = 13.5.sp,
            lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
        )
    }
}

/** One task card (design TaskCard): prompt excerpt + mono metadata on the left, quiet ✕ above a
 *  terracotta countdown on the right; repeating tasks carry a DAILY badge, a failed run a danger caption. */
@Composable
private fun ScheduleRow(s: ScheduleInfo, now: Long, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp))
            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            s.repeat?.let { DailyBadge(it) }
            Text(
                s.label ?: s.prompt, color = Tok.tx, fontSize = 14.5.sp, lineHeight = 20.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            // mono metadata: cwd tail · agent · session behaviour (design: "~/dev/api · claude · resumes session")
            val dir = s.workdir.substringAfterLast('/').ifEmpty { s.workdir.substringAfterLast('\\').ifEmpty { s.workdir } }
            val session = stringResource(if (s.resumeId != null) Res.string.schedule_meta_resume else Res.string.schedule_meta_new)
            Text(
                "$dir · ${s.agent.name.lowercase()} · $session",
                color = Tok.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp),
            )
            failedCaption(s)?.let { reason ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(WarnTriGlyph, null, tint = Tok.danger, modifier = Modifier.size(13.dp))
                    Text(
                        stringResource(Res.string.schedule_last_failed, reason),
                        color = Tok.danger, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxHeight(), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                XGlyph, contentDescription = stringResource(Res.string.schedule_remove), tint = Tok.muted,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCancel).padding(2.dp).size(15.dp),
            )
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 8.dp)) {
                val settledBad = s.nextRunAtMs == null && s.lastOutcome != null && s.lastOutcome != SchedulerOutcomeOk
                Text(
                    statusLabel(s, now), maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = when { settledBad -> Tok.danger; s.nextRunAtMs == null -> Tok.muted; else -> Tok.accent },
                )
                if (s.repeat != null && s.nextRunAtMs != null) {
                    Text(
                        stringResource(Res.string.schedule_next_at, hhmm(s.nextRunAtMs!!)),
                        color = Tok.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// the daemon's wire literals for a settled fire (SchedulerService.OUTCOME_*)
private const val SchedulerOutcomeMissed = "missed"
private const val SchedulerOutcomeOk = "ok"

@Composable
private fun repeatLabel(r: ScheduleRepeat): String {
    val interval = r.intervalMs
    return when {
        r.dailyAtMinute != null || interval == 24 * 3600_000L -> stringResource(Res.string.schedule_repeat_daily)
        interval != null -> stringResource(Res.string.schedule_repeat_every, etaShort(interval))
        else -> stringResource(Res.string.schedule_repeat_daily)
    }
}

/** The card's right-side "when": repeats say their cadence, one-shots count down, settled ones say how they ended. */
@Composable
private fun statusLabel(s: ScheduleInfo, now: Long): String {
    val next = s.nextRunAtMs
    val outcome = s.lastOutcome
    return when {
        s.repeat != null && next != null -> repeatLabel(s.repeat!!)
        next != null && next <= now -> stringResource(Res.string.schedule_due_now)
        next != null -> stringResource(Res.string.schedule_next_run, etaShort(next - now))
        outcome == SchedulerOutcomeMissed -> stringResource(Res.string.schedule_missed)
        outcome != null && outcome != SchedulerOutcomeOk -> outcome
        else -> stringResource(Res.string.schedule_done)
    }
}

/** The danger caption's reason, only for tasks that will run again (a settled one-shot's countdown
 *  slot already says how it ended — no double reporting). */
@Composable
private fun failedCaption(s: ScheduleInfo): String? {
    val outcome = s.lastOutcome ?: return null
    if (outcome == SchedulerOutcomeOk || s.nextRunAtMs == null) return null
    return if (outcome == SchedulerOutcomeMissed) stringResource(Res.string.schedule_missed) else outcome
}

/** DAILY badge (design): soft terracotta pill with the repeat glyph, above the prompt excerpt. */
@Composable
private fun DailyBadge(r: ScheduleRepeat) {
    Row(
        Modifier.padding(bottom = 5.dp).clip(RoundedCornerShape(6.dp))
            .background(Tok.accent.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(RepeatGlyph, null, tint = Tok.accent, modifier = Modifier.size(11.dp))
        Text(
            repeatLabel(r).uppercase(), color = Tok.accent, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp,
        )
    }
}
