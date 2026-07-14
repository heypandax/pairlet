package dev.ccpocket.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.limit_autocontinue
import dev.ccpocket.app.resources.limit_banner
import dev.ccpocket.app.resources.schedule_confirm
import dev.ccpocket.app.resources.schedule_done
import dev.ccpocket.app.resources.schedule_due_now
import dev.ccpocket.app.resources.schedule_empty
import dev.ccpocket.app.resources.schedule_empty_hint
import dev.ccpocket.app.resources.schedule_missed
import dev.ccpocket.app.resources.schedule_next_run
import dev.ccpocket.app.resources.schedule_opt_1h
import dev.ccpocket.app.resources.schedule_opt_30m
import dev.ccpocket.app.resources.schedule_opt_3h
import dev.ccpocket.app.resources.schedule_opt_8h
import dev.ccpocket.app.resources.schedule_remove
import dev.ccpocket.app.resources.schedule_repeat_daily
import dev.ccpocket.app.resources.schedule_repeat_every
import dev.ccpocket.app.resources.schedule_repeat_toggle
import dev.ccpocket.app.resources.schedule_send_hint
import dev.ccpocket.app.resources.schedule_send_title
import dev.ccpocket.app.resources.schedule_tasks_title
import dev.ccpocket.app.resources.schedule_unavailable
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleRepeat
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
    var picked by remember { mutableStateOf(options[0].first) }
    var daily by remember { mutableStateOf(false) }
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            Text(stringResource(Res.string.schedule_send_title), color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.schedule_send_hint), color = Tok.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            // the staged message, so what's being scheduled is unambiguous
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                    .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Text(text, color = Tok.tx2, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (delta, label) ->
                    val sel = picked == delta
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Tok.accent else Tok.base)
                            .border(1.dp, if (sel) Tok.accent else Tok.hair, RoundedCornerShape(8.dp))
                            .clickable { picked = delta }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (sel) Tok.base else Tok.tx2, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.schedule_repeat_toggle), color = Tok.tx, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Switch(checked = daily, onCheckedChange = { daily = it })
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                    .clickable {
                        onSchedule(epochMillis() + picked, if (daily) ScheduleRepeat(intervalMs = 24 * 3600_000L) else null)
                        onDismiss()
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(Res.string.schedule_confirm, etaShort(picked)),
                    color = Tok.base, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The usage-limit auto-continue banner (issue #137): shown above the composer when the last turn died
 * on a usage limit whose reset moment the daemon could parse. One tap schedules a "Continue" back into
 * this session shortly after the window flips.
 */
@Composable
fun LimitResetBanner(repo: PocketRepository) {
    val offer = repo.limitOffer.value ?: return
    if (offer.convoId != repo.convoId.value) return
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(offer.resetAtMs) {
        while (true) { delay(30_000); now = epochMillis() }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.limit_banner, etaShort(offer.resetAtMs - now)),
            color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(Res.string.limit_autocontinue),
            color = Tok.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { repo.scheduleAutoContinue() }.padding(6.dp),
        )
    }
}

/** Scheduled-tasks management (issue #137): list + cancel; repeats show their next fire time. */
@Composable
fun ScheduleScreen(repo: PocketRepository, onBack: () -> Unit) {
    LaunchedEffect(Unit) { repo.fetchSchedules() }
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = epochMillis() } }
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Text(stringResource(Res.string.schedule_tasks_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        repo.scheduleError.value?.let {
            Text(it, color = Tok.danger, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        when {
            repo.schedulesUnavailable.value -> CenterNote(stringResource(Res.string.schedule_unavailable))
            repo.schedulesLoaded.value && repo.schedules.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(Res.string.schedule_empty), color = Tok.tx2, fontSize = 14.sp)
                Text(
                    stringResource(Res.string.schedule_empty_hint), color = Tok.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                items(repo.schedules, key = { it.id }) { s ->
                    ScheduleRow(s, now, onCancel = { repo.cancelSchedule(s.id) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(s: ScheduleInfo, now: Long, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.label ?: s.prompt, color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            s.repeat?.let {
                Text(
                    repeatLabel(it), color = Tok.accent, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (s.label != null && s.prompt.isNotBlank()) {
            Text(s.prompt, color = Tok.tx2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Text(
            s.workdir.substringAfterLast('/').ifEmpty { s.workdir },
            color = Tok.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair).padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(statusLabel(s, now), color = if (s.lastOutcome == SchedulerOutcomeMissed) Tok.danger else Tok.tx2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                stringResource(Res.string.schedule_remove), color = Tok.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(6.dp),
            )
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

@Composable
private fun statusLabel(s: ScheduleInfo, now: Long): String {
    val next = s.nextRunAtMs
    val outcome = s.lastOutcome
    return when {
        next != null && next <= now -> stringResource(Res.string.schedule_due_now)
        next != null -> stringResource(Res.string.schedule_next_run, etaShort(next - now))
        outcome == SchedulerOutcomeMissed -> stringResource(Res.string.schedule_missed)
        outcome != null && outcome != SchedulerOutcomeOk -> outcome
        else -> stringResource(Res.string.schedule_done)
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tok.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
    }
}
