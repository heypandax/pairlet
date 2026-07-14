package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.TextStyle
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
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.contextWindowFor
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
internal val CODEX_MODEL_OPTIONS = listOf("gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5-codex") // Codex sessions get Codex models; shared with the desktop ⋯ popover
internal val CLAUDE_MODEL_OPTIONS = listOf("Fable" to "fable", "Opus" to "opus", "Sonnet" to "sonnet", "Haiku" to "haiku") // display name → alias; shared by both shells' pickers
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
fun QuickActionsSheet(repo: PocketRepository, onTerminal: () -> Unit, onMode: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
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
                        // the permission-mode switch lives here now (was a persistent header badge — one
                        // more thing crowding the top bar for a setting touched a few times per session)
                        ActionRow(
                            stringResource(Res.string.label_mode),
                            value = (MODE_BY[repo.mode.value]?.short ?: MODES[0].short).let { stringResource(it) },
                            chevron = true,
                        ) { onMode(); onDismiss() }
                        ActionRow(stringResource(Res.string.terminal_open)) { onTerminal(); onDismiss() }
                        ActionRow(stringResource(Res.string.qa_files)) { onFiles(); onDismiss() }
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
internal fun ModelPicker(repo: PocketRepository, onBack: () -> Unit, onDone: () -> Unit) { // internal (was private) so desktopTest's ShowcaseRender can compose it — SessionsScreen/ChatScreen precedent
    val codex = repo.sessionAgent.value == AgentKind.CODEX
    val choices = if (codex) CODEX_MODEL_OPTIONS.map { ModelChoice(it, it, it, "", false) }
    // window pill derives from the protocol table, so registering a new alias THERE is the only edit
    else CLAUDE_MODEL_OPTIONS.map { (name, alias) ->
        val big = contextWindowFor(alias) == LARGE_CONTEXT_WINDOW
        ModelChoice(name, alias, alias, if (big) "1M" else "200K", big)
    }
    val selected = if (codex) repo.model.value else modelAlias(repo.model.value)
    var switchingTo by remember { mutableStateOf<String?>(null) }
    // close once the daemon confirms the switch (model re-announced through SessionLive)…
    LaunchedEffect(switchingTo, repo.model.value) {
        val target = switchingTo ?: return@LaunchedEffect
        val now = if (codex) repo.model.value else modelAlias(repo.model.value)
        // raw compare too: a custom id ("kimi-k2…") never alias-matches, but the daemon echoes it verbatim
        if (now.equals(target, ignoreCase = true) || repo.model.value?.equals(target, ignoreCase = true) == true) onDone()
    }
    // …or after a short timeout, so a silent relaunch never leaves the sheet stuck spinning
    LaunchedEffect(switchingTo) { if (switchingTo != null) { delay(4000); onDone() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ ", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(enabled = switchingTo == null, onClick = onBack).padding(end = 4.dp))
        Text(stringResource(Res.string.qa_model), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    // Gateway model presets (issue #139): one-tap vendor ids for third-party gateway users. When the
    // daemon reports a gateway ANTHROPIC_BASE_URL (DaemonInfo) the section LEADS the picker — those
    // users pick vendor ids, not Claude aliases. On the official endpoint it sits behind a collapsed
    // toggle below, so the sheet keeps today's size for everyone else. Claude sessions only: Codex
    // model routing doesn't go through ANTHROPIC_BASE_URL.
    val gatewayUrl = if (codex) null else repo.gatewayBaseUrl.value
    val pickPreset: (String) -> Unit = { switchingTo = it; repo.switchModel(it) }
    if (gatewayUrl != null) GatewayPresetSection(repo, gatewayUrl, switchingTo, pickPreset)
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
    // no gateway detected: the same preset rows stay reachable behind a quiet toggle (issue #139)
    if (!codex && gatewayUrl == null) {
        var showGateway by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable(enabled = switchingTo == null) { showGateway = !showGateway }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.model_gateway_show), color = Tok.muted, fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Text(if (showGateway) "⌃" else "›", color = Tok.muted, fontSize = 14.sp)
        }
        if (showGateway) GatewayPresetSection(repo, gatewayUrl = null, switchingTo = switchingTo, onPick = pickPreset)
    }
    // Custom model id (issue #54): third-party gateways (cc-switch presets etc.) route ids a fixed list
    // can't know, and `--model` passes any string through — so hand that power to the user. Prefilled when
    // the session already runs an id outside the presets, with the same ✓/spinner the preset rows use.
    val presetActive = choices.any { it.pick.equals(selected, ignoreCase = true) }
    val customActive = !presetActive && !repo.model.value.isNullOrBlank()
    var custom by remember { mutableStateOf(if (customActive) repo.model.value.orEmpty() else "") }
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(Res.string.model_custom_label), color = Tok.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                custom, { custom = it },
                placeholder = { Text(stringResource(Res.string.model_custom_hint), color = Tok.muted, fontSize = 12.5.sp) },
                singleLine = true, enabled = switchingTo == null,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Tok.tx),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                val t = custom.trim()
                val isSwitchingCustom = switchingTo != null && switchingTo.equals(t, ignoreCase = true) && !presetActive
                when {
                    isSwitchingCustom -> CircularProgressIndicator(Modifier.size(17.dp), color = Tok.accent, strokeWidth = 2.dp)
                    customActive && t.equals(repo.model.value, ignoreCase = true) && switchingTo == null ->
                        Text("✓", color = Tok.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    t.isNotEmpty() && switchingTo == null -> Text(
                        "→", color = Tok.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { switchingTo = t; repo.switchModel(t) }.padding(6.dp),
                    )
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

/**
 * The gateway model preset rows (issue #139), fed by the shared [GATEWAY_MODEL_PRESETS] table.
 * [gatewayUrl] non-null = the daemon reported a third-party ANTHROPIC_BASE_URL: the header carries
 * a "via host" pill and [recommendedGatewayPresets] ranks that vendor's ids first. Ids route through
 * whatever the user's gateway maps them to — the note row says exactly that instead of promising.
 */
@Composable
private fun GatewayPresetSection(repo: PocketRepository, gatewayUrl: String?, switchingTo: String?, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.model_gateway_section), color = Tok.muted, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        )
        gatewayHostLabel(gatewayUrl)?.let { host ->
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(Res.string.model_gateway_via, host), color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recommendedGatewayPresets(gatewayUrl).forEach { p ->
            val isSel = p.id.equals(repo.model.value, ignoreCase = true)
            val isSwitching = switchingTo?.equals(p.id, ignoreCase = true) == true
            val dimmed = switchingTo != null && !isSwitching
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (isSel || isSwitching) Tok.raised else Tok.surface)
                    .then(if (isSel || isSwitching) Modifier.border(1.dp, Tok.hair, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(enabled = switchingTo == null) { onPick(p.id) }
                    .alpha(if (dimmed) 0.45f else 1f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.vendor, color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Text(p.id, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        isSwitching -> CircularProgressIndicator(Modifier.size(15.dp), color = Tok.accent, strokeWidth = 2.dp)
                        isSel -> Text("✓", color = Tok.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(stringResource(Res.string.model_gateway_note), color = Tok.muted, fontSize = 10.5.sp, lineHeight = 15.sp)
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
fun BackgroundJobsSheet(jobs: List<BackgroundJob>, onStop: (BackgroundJob) -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.bg_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // running first, then most-recently-updated. Sorted straight in composition — [jobs] is one
            // SnapshotStateList instance mutated in place, so an instance-keyed remember would compute once
            // per sheet-open and freeze the rows (a stopped job kept showing RUNNING until reopen).
            val sorted = jobs.sortedWith(compareByDescending<BackgroundJob> { it.status == JobStatus.RUNNING }.thenByDescending { it.lastUpdate })
            Column(Modifier.padding(top = 12.dp).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sorted.forEach { JobRow(it, onStop) }
            }
            // discoverability for the long-press stop (issue #80): a quiet hint, only while something can be
            // stopped — keeps the affordance off an always-visible per-row button as the issue asks
            if (jobs.any { it.status == JobStatus.RUNNING }) {
                Text(
                    stringResource(Res.string.job_stop_hint), color = Tok.muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // combinedClickable (long-press)
@Composable
private fun JobRow(job: BackgroundJob, onStop: (BackgroundJob) -> Unit) {
    val running = job.status == JobStatus.RUNNING
    var confirmStop by remember(job.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
            // stop lives in a long-press (not an always-visible button, per issue #80) and only on a RUNNING
            // row — a settled job has nothing to stop. The confirm guards a costly real build.
            .then(if (running) Modifier.combinedClickable(onClick = {}, onLongClick = { confirmStop = true }) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (running) CircularProgressIndicator(Modifier.size(13.dp), color = Tok.accent, strokeWidth = 1.5.dp)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(jobStatusColor(job.status)))
        Column(Modifier.weight(1f)) {
            Text(job.label, color = Tok.tx, fontSize = 13.sp, maxLines = 2)
            Text(jobKindLabel(job.kind), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
        // Option (a): a RUNNING job's right-side label is its ticking elapsed time (a moving "3h12m" already
        // implies running), so the status word never fights the truncated command for width; past ~1h it
        // turns warn-coloured to flag a possibly-stuck task. A settled job keeps its status word.
        if (running) {
            val (elapsed, warn) = rememberJobElapsed(job.startedAt)
            Text(elapsed, color = if (warn) Tok.warn else Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        } else {
            Text(jobStatusLabel(job.status), color = jobStatusColor(job.status), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (confirmStop) {
        JobStopConfirm(job, onConfirm = { confirmStop = false; onStop(job) }, onDismiss = { confirmStop = false })
    }
}

/** Confirm stopping a running task — costly to lose a real build, so guard it (issue #80). */
@Composable
private fun JobStopConfirm(job: BackgroundJob, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tok.raised,
        titleContentColor = Tok.tx,
        textContentColor = Tok.tx2,
        title = { Text(stringResource(Res.string.job_stop_title)) },
        text = { Text(stringResource(Res.string.job_stop_confirm, job.label), color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(Res.string.job_stop_action), color = Tok.danger) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel), color = Tok.muted) } },
    )
}

/** Compact elapsed since [startedAt] ("42s" / "12m" / "3h12m"), ticking each second while composed, plus
 *  whether it has crossed the ~1h warn threshold. Reads the daemon's wall-clock [BackgroundJob.startedAt]
 *  (matches [epochMillis] on every platform), so it stays accurate even when the phone attaches mid-run. */
@Composable
private fun rememberJobElapsed(startedAt: Long): Pair<String, Boolean> {
    var now by remember(startedAt) { mutableStateOf(epochMillis()) }
    LaunchedEffect(startedAt) {
        while (true) { delay(1000); now = epochMillis() }
    }
    val elapsedMs = (now - startedAt).coerceAtLeast(0)
    return fmtJobDuration(elapsedMs) to (elapsedMs >= JOB_WARN_MS)
}

/** "42s" under a minute, "12m" under an hour, "3h12m" beyond — a compact "is it stuck?" readout. */
internal fun fmtJobDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** Past ~1h a still-running task is likely stuck (the issue #80 gcloud-auth case) — its duration turns warn. */
private const val JOB_WARN_MS = 60 * 60 * 1000L

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
