package dev.ccpocket.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.BackgroundJob
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.JobKind
import dev.ccpocket.protocol.JobStatus
import dev.ccpocket.protocol.AgentKind
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// ── model + effort option sets (what `--model` / `--effort` accept) ──
private val CODEX_MODEL_OPTIONS = listOf("gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5-codex") // Codex sessions get Codex models
internal val EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh", "max") // shared: live /effort picker + Settings default

/** Short header alias for a model id: "claude-opus-4-8[1m]" -> "opus". */
fun modelAlias(model: String?): String {
    val m = model?.trim().orEmpty()
    if (m.isEmpty()) return ""
    return m.removePrefix("claude-").takeWhile { it != '-' && it != '[' && it != '_' }.ifBlank { m }
}

/** Compact human token count: 45200 -> "45k", 1000000 -> "1.0M" (one decimal, truncated). */
fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M" // integer /100k then /10.0 = one truncated decimal
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

/** Context-occupancy color ramp, shared by the session sheet's [ContextBar] and the chat statusline:
 *  [base] under 80%, warn to 95%, danger past it. One definition keeps the thresholds in lockstep;
 *  callers pick the calm base (the bar fills with accent, the corner text rests at muted). */
fun contextColor(frac: Float, base: Color = Tok.accent): Color = when {
    frac >= 0.95f -> Tok.danger
    frac >= 0.80f -> Tok.warn
    else -> base
}

// ════════════════════════════════════════════════════════════════════
//  Session info (read-only): model · effort · mode · dir · context bar
// ════════════════════════════════════════════════════════════════════
@Composable
fun SessionInfoSheet(repo: PocketRepository, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.session_info_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Column(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.label_agent), color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    AgentTag(repo.sessionAgent.value ?: AgentKind.CLAUDE, small = false)
                }
                Hairline()
                AboutRow(stringResource(Res.string.label_model), repo.model.value ?: stringResource(Res.string.value_default))
                Hairline()
                AboutRow(stringResource(Res.string.label_effort), repo.effort.value ?: stringResource(Res.string.value_default))
                Hairline()
                AboutRow(stringResource(Res.string.label_mode), MODE_BY[repo.mode.value]?.tech ?: repo.mode.value.name)
            }
            ContextBar(used = repo.contextUsed.value, total = repo.contextWindow.value)
            Column(Modifier.padding(top = 10.dp)) {
                Text(stringResource(Res.string.label_workdir), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                TailPathText(repo.workdir.value ?: "", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ContextBar(used: Long?, total: Long?) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.label_context), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            // total == null → no known denominator (Codex): show raw occupancy instead of a fake /200k
            val label = when {
                total == null -> if (used == null) "—" else "~${formatTokens(used)}"
                used == null -> "— / ${formatTokens(total)}"
                else -> "~${formatTokens(used)} / ${formatTokens(total)}"
            }
            Text(label, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        val frac = if (used == null || total == null || total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)
        val fill = contextColor(frac)
        Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tok.hair)) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(fill))
        }
    }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

// ════════════════════════════════════════════════════════════════════
//  Quick actions: switch model / effort, compact, clear, simplify
// ════════════════════════════════════════════════════════════════════
private enum class QaSub { MAIN, MODEL, EFFORT }

@Composable
fun QuickActionsSheet(repo: PocketRepository, onTerminal: () -> Unit, onDismiss: () -> Unit) {
    var sub by remember { mutableStateOf(QaSub.MAIN) }
    var clearArmed by remember { mutableStateOf(false) }
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            when (sub) {
                QaSub.MAIN -> {
                    Text(stringResource(Res.string.quick_actions_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionRow(stringResource(Res.string.qa_model), value = modelAlias(repo.model.value).ifBlank { stringResource(Res.string.value_default) }, chevron = true) { sub = QaSub.MODEL }
                        ActionRow(stringResource(Res.string.label_effort), value = repo.effort.value ?: stringResource(Res.string.value_default), chevron = true) { sub = QaSub.EFFORT }
                        ActionRow(stringResource(Res.string.terminal_open)) { onTerminal(); onDismiss() }
                        ActionRow(stringResource(Res.string.qa_compact)) { repo.sendPrompt("/compact"); onDismiss() }
                        if (repo.hasSimplify()) ActionRow(stringResource(Res.string.qa_simplify)) { repo.sendPrompt("/simplify"); onDismiss() }
                        ActionRow(
                            stringResource(Res.string.qa_clear),
                            value = if (clearArmed) stringResource(Res.string.qa_clear_hint) else null,
                            danger = true,
                        ) {
                            if (clearArmed) { repo.clearConversation(); onDismiss() } else clearArmed = true
                        }
                    }
                }
                QaSub.MODEL -> ModelPicker(repo, onBack = { sub = QaSub.MAIN }, onDone = onDismiss)
                QaSub.EFFORT -> OptionPicker(
                    title = stringResource(Res.string.label_effort),
                    options = EFFORT_OPTIONS,
                    selected = repo.effort.value,
                    onBack = { sub = QaSub.MAIN },
                ) { repo.switchEffort(it); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, value: String? = null, danger: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (danger) Tok.danger else Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1) }
        if (chevron) Text(" ›", color = Tok.muted, fontSize = 16.sp)
    }
}

@Composable
private fun OptionPicker(title: String, options: List<String>, selected: String?, onBack: () -> Unit, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ ", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 4.dp))
        Text(title, color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { opt ->
            val isSel = opt.equals(selected, ignoreCase = true)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) Tok.accent.copy(alpha = 0.12f) else Tok.surface)
                    .clickable { onPick(opt) }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(opt, color = if (isSel) Tok.accent else Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (isSel) Text("✓", color = Tok.accent, fontSize = 14.sp)
            }
        }
    }
}

/** One row in the [ModelPicker]: a display [name], the `--model` value [pick] shown in mono as [id], and a
 *  context-window pill ([ctx], filled terracotta when [big]). Uses the app's real model aliases, not invented ids. */
private data class ModelChoice(val name: String, val id: String, val pick: String, val ctx: String, val big: Boolean, val unavailable: Boolean = false)

/** Context-window pill — filled terracotta for a 1M window, muted outline otherwise. */
@Composable
private fun CtxPill(ctx: String, big: Boolean) {
    Text(
        ctx, color = if (big) Tok.base else Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .then(if (big) Modifier.background(Tok.accent) else Modifier.border(1.dp, Tok.hair, RoundedCornerShape(999.dp)))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Model picker (design cc-pocket/Model Picker.html) — reached from Quick actions → Model (NOT a new top-bar
 * button; the bar is already busy). Rich rows: display name + mono `--model` value + a 1M/200K context pill +
 * a check. Tapping starts a switch: the row shows a spinner while the daemon relaunches; the sheet closes once
 * the model is re-announced (or after a short timeout, so it never hangs). Claude uses the real opus/sonnet/haiku
 * aliases; Codex sessions list Codex model ids.
 */
@Composable
private fun ModelPicker(repo: PocketRepository, onBack: () -> Unit, onDone: () -> Unit) {
    val codex = repo.sessionAgent.value == AgentKind.CODEX
    val choices = if (codex) CODEX_MODEL_OPTIONS.map { ModelChoice(it, it, it, "", false) }
    else listOf(
        ModelChoice("Opus", "opus", "opus", "1M", big = true),
        ModelChoice("Sonnet", "sonnet", "sonnet", "1M", big = true),
        ModelChoice("Haiku", "haiku", "haiku", "200K", big = false),
    )
    val selected = if (codex) repo.model.value else modelAlias(repo.model.value)
    var switchingTo by remember { mutableStateOf<String?>(null) }
    // close once the daemon confirms the switch (model re-announced through SessionLive)…
    LaunchedEffect(switchingTo, repo.model.value) {
        val target = switchingTo ?: return@LaunchedEffect
        val now = if (codex) repo.model.value else modelAlias(repo.model.value)
        if (now.equals(target, ignoreCase = true)) onDone()
    }
    // …or after a short timeout, so a silent relaunch never leaves the sheet stuck spinning
    LaunchedEffect(switchingTo) { if (switchingTo != null) { delay(4000); onDone() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ ", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(enabled = switchingTo == null, onClick = onBack).padding(end = 4.dp))
        Text(stringResource(Res.string.qa_model), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { c ->
            val isSel = c.pick.equals(selected, ignoreCase = true)
            val isSwitching = switchingTo?.equals(c.pick, ignoreCase = true) == true
            val raised = isSwitching || (isSel && switchingTo == null)
            val dimmed = (switchingTo != null && !isSwitching) || c.unavailable
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (raised) Tok.raised else Color.Transparent)
                    .then(if (raised) Modifier.border(1.dp, Tok.hair, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(enabled = switchingTo == null && !c.unavailable) { switchingTo = c.pick; repo.switchModel(c.pick) }
                    .alpha(if (dimmed) 0.45f else 1f)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (c.unavailable) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(Res.string.model_not_installed), color = Tok.muted, fontSize = 10.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.id, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1)
                        if (c.ctx.isNotEmpty()) { Spacer(Modifier.width(8.dp)); CtxPill(c.ctx, c.big) }
                    }
                }
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        isSwitching -> CircularProgressIndicator(Modifier.size(17.dp), color = Tok.accent, strokeWidth = 2.dp)
                        isSel -> Text("✓", color = Tok.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    Column(Modifier.padding(top = 14.dp)) {
        Hairline()
        Box(Modifier.padding(top = 12.dp)) {
            if (switchingTo != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), color = Tok.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.model_switching), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
            } else Text(stringResource(Res.string.model_switch_hint), color = Tok.muted, fontSize = 12.5.sp)
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Background tasks: composer strip + expandable list
// ════════════════════════════════════════════════════════════════════
/** Compact strip above the composer; shown only while ≥1 job is RUNNING. Tap to expand. */
@Composable
fun BackgroundJobsStrip(jobs: List<BackgroundJob>, onClick: () -> Unit) {
    val running = jobs.count { it.status == JobStatus.RUNNING }
    if (running == 0) return
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(12.dp), color = Tok.accent, strokeWidth = 1.5.dp)
        Text(stringResource(Res.string.bg_running, running), color = Tok.tx2, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("⌃", color = Tok.muted, fontSize = 13.sp)
    }
}

@Composable
fun BackgroundJobsSheet(jobs: List<BackgroundJob>, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.bg_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // running first, then most-recently-updated; remembered so unrelated recompositions don't re-sort
            val sorted = remember(jobs) {
                jobs.sortedWith(compareByDescending<BackgroundJob> { it.status == JobStatus.RUNNING }.thenByDescending { it.lastUpdate })
            }
            Column(Modifier.padding(top = 12.dp).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sorted.forEach { JobRow(it) }
            }
        }
    }
}

@Composable
private fun JobRow(job: BackgroundJob) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (job.status == JobStatus.RUNNING) CircularProgressIndicator(Modifier.size(13.dp), color = Tok.accent, strokeWidth = 1.5.dp)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(jobStatusColor(job.status)))
        Column(Modifier.weight(1f)) {
            Text(job.label, color = Tok.tx, fontSize = 13.sp, maxLines = 2)
            Text(jobKindLabel(job.kind), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Text(jobStatusLabel(job.status), color = jobStatusColor(job.status), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun jobKindLabel(kind: JobKind): String = stringResource(
    when (kind) {
        JobKind.BASH_BACKGROUND -> Res.string.job_kind_bash
        JobKind.SUBAGENT -> Res.string.job_kind_subagent
        JobKind.MONITOR -> Res.string.job_kind_monitor
    },
)

@Composable
private fun jobStatusLabel(status: JobStatus): String = stringResource(
    when (status) {
        JobStatus.RUNNING -> Res.string.job_running
        JobStatus.DONE -> Res.string.job_done
        JobStatus.FAILED -> Res.string.job_failed
        JobStatus.KILLED -> Res.string.job_killed
    },
)

private fun jobStatusColor(status: JobStatus): Color = when (status) {
    JobStatus.RUNNING -> Tok.accent
    JobStatus.DONE -> Tok.ok
    JobStatus.FAILED -> Tok.danger
    JobStatus.KILLED -> Tok.muted
}
