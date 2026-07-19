package dev.ccpocket.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.isModelCompatibleWithAgent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// ── model + effort option sets (what `--model` / `--effort` accept) ──
internal val CODEX_MODEL_OPTIONS = CODEX_MODEL_IDS // Codex sessions get Codex models; shared with the desktop ⋯ popover
// NO static OpenCode options on purpose: opencode's usable models are whatever PROVIDERS this
// user configured (free catalogs also rotate weekly) — a hardcoded list is someone else's setup
// and every wrong row is a launch failure. The picker shows the daemon's `opencode models` answer
// (FetchModels) or an explicit empty/error state, never a guess.
internal val CLAUDE_MODEL_OPTIONS = listOf("Fable" to "fable", "Opus" to "opus", "Sonnet" to "sonnet", "Haiku" to "haiku") // display name → alias; shared by both shells' pickers
internal val EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh", "max") // shared: live /effort picker + Settings default

/** Short header alias for a model id: "claude-opus-4-8[1m]" -> "opus". */
fun modelAlias(model: String?): String {
    val m = model?.trim().orEmpty()
    if (m.isEmpty()) return ""
    return m.removePrefix("claude-").takeWhile { it != '-' && it != '[' && it != '_' }.ifBlank { m }
}

/** Agent-aware display label: Claude gets aliases, Codex/OpenCode keep their full model ids
 *  (an alias-shaped collapse of "zhipuai/glm-5" would misname it "zhipuai"). Shows the model the
 *  daemon REPORTS verbatim — no compat filtering on the display path. */
fun modelLabelForAgent(agent: AgentKind?, model: String?): String {
    val m = model?.trim().orEmpty()
    if (m.isEmpty()) return ""
    return when (agent ?: AgentKind.CLAUDE) {
        AgentKind.CLAUDE -> modelAlias(m)
        AgentKind.CODEX, AgentKind.OPENCODE -> m
    }
}

/** Middle-truncate a long model id for the composer chip ("deepseek-chat-v3.2" → "deepseek…v3.2"):
 *  head + tail keep both the vendor and the variant readable while the chip's width holds steady
 *  (issue #157, design model-chip.jsx). ≤ head+tail+1 chars pass through — swapping one character
 *  for '…' would save nothing. */
internal fun midTruncateModel(id: String, head: Int = 8, tail: Int = 4): String =
    if (id.length <= head + tail + 1) id else id.take(head) + "…" + id.takeLast(tail)

/** The composer chip's label (issue #157): Claude-family ids collapse to their short alias exactly
 *  like the header meta line; anything else (gateway/custom/Codex ids) keeps the REAL id, middle-
 *  truncated — [modelAlias] would misname "deepseek-chat" as "deepseek". Blank in = blank out
 *  (callers fall back to the "account default" placeholder, same as the header). */
internal fun modelChipLabel(model: String?): String {
    val m = model?.trim().orEmpty()
    if (m.isEmpty()) return ""
    val claudeFamily = m.startsWith("claude-", ignoreCase = true) ||
        CLAUDE_MODEL_OPTIONS.any { (_, alias) -> alias.equals(m, ignoreCase = true) }
    return if (claudeFamily) modelAlias(m) else midTruncateModel(m)
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
            PerModelWindowRow(repo)
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

/**
 * #171: the WRITE surface for a model's own context window — deliberately here rather than in Settings.
 *
 * This is the one place where a concrete model and the denominator that's wrong about it are both on screen
 * (the bar directly above), and it's where the composer gauge taps through to. Settings stays global: it owns
 * the catch-all and audits the table, but it has no "current model" to write against.
 *
 * Hidden when the session has no model id to key an entry on — an entry needs something to be keyed BY.
 */
@Composable
internal fun PerModelWindowRow(repo: PocketRepository) {
    val model = repo.model.value ?: return
    val key = repo.contextWindowKeyOf(model) ?: return
    val own = repo.contextWindowOverrides[key]
    val effective = repo.contextWindow.value
    var editing by remember(key) { mutableStateOf(false) }
    var draft by remember(key) { mutableStateOf(own?.toString() ?: "") }

    if (editing) {
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Tok.base).border(1.dp, Tok.accent, RoundedCornerShape(12.dp))
                .padding(start = 13.dp, end = 13.dp, top = 13.dp, bottom = 14.dp),
        ) {
            Text(stringResource(Res.string.ctx_window_for, model), color = Tok.tx2, fontSize = 12.sp)
            OutlinedTextField(
                draft,
                { new -> draft = new.filter(Char::isDigit).take(9) },
                placeholder = { Text(stringResource(Res.string.context_window_tokens), color = Tok.muted, fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Tok.tx),
                modifier = Modifier.padding(top = 9.dp).fillMaxWidth(),
            )
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                        .clickable {
                            // Blank clears the entry: the model falls back to the catch-all rather than pinning a 0.
                            repo.setContextWindowOverrideFor(model, draft.toLongOrNull()?.takeIf { it > 0 })
                            editing = false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.ctx_save_for_model), color = Tok.base, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.padding(start = 8.dp).height(44.dp).clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                        .clickable { draft = own?.toString() ?: ""; editing = false }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.cancel), color = Tok.tx2, fontSize = 14.sp)
                }
            }
        }
        return
    }

    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .clickable { draft = own?.toString() ?: ""; editing = true }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (own == null) Icons.Rounded.SubdirectoryArrowRight else Icons.Rounded.Edit, null,
            tint = if (own == null) Tok.muted else Tok.tx2, modifier = Modifier.size(15.dp),
        )
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(
                stringResource(if (own == null) Res.string.ctx_set_for_model else Res.string.ctx_edit_for_model),
                color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
            )
            // Says WHERE the current denominator comes from — the inherit/own distinction is the whole point.
            val sizeText = (own ?: effective)?.let { groupDigits(it) } ?: "—"
            Text(
                stringResource(if (own == null) Res.string.ctx_using_catchall else Res.string.ctx_own_value, sizeText),
                color = if (own == null) Tok.muted else Tok.warn, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
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
                        ActionRow(stringResource(Res.string.qa_model), value = modelChipLabel(repo.model.value).ifBlank { stringResource(Res.string.value_default) }, chevron = true) { sub = QaSub.MODEL }
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

/**
 * The composer's quiet model chip (issue #157, design model-chip.jsx): hairline pill on the raised
 * surface, mono 11sp label (middle-truncated id), chevron-up that flips while its picker is open
 * (the border warms to accent). Dimmed + disabled while a turn streams — a switch would only land
 * on the NEXT turn anyway. Never accent-filled: send stays the loudest control. Shared by both
 * shells; [labelMax] defaults to the original 82dp cap (desktop's single-row composer) — the
 * mobile accessory row relaxes it to 120dp now that the chip has its own lane (mobile-composer.jsx).
 */
@Composable
internal fun ModelChip(label: String, open: Boolean, enabled: Boolean, contentDescription: String, labelMax: Dp = 82.dp, onClick: () -> Unit) {
    val chev by animateFloatAsState(if (open) 180f else 0f, label = "chipChevron")
    val cd = contentDescription
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).background(Tok.raised)
            .border(1.dp, if (open) Tok.accent else Tok.hair, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.42f)
            .padding(start = 10.dp, end = 8.dp)
            .semantics { this.contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // TightCenter: mono ascent/descent are asymmetric, so a raw Text rides high inside the
            // pill even under CenterVertically — same trim the agent tags and mode chips use
            label, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, style = TightCenter,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = labelMax),
        )
        Spacer(Modifier.width(5.dp))
        ChevronUpGlyph(Tok.muted, Modifier.size(12.dp).rotate(chev))
    }
}

/** 12dp chevron-up stroke (design: M3 9l4-4 4 4 in a 14-box) — drawn, not a text glyph, so the
 *  open-state 180° flip stays optically centered. */
@Composable
private fun ChevronUpGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.21f, size.height * 0.64f)
            lineTo(size.width * 0.50f, size.height * 0.36f)
            lineTo(size.width * 0.79f, size.height * 0.64f)
        }
        drawPath(p, tint, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The chip's direct model sheet (issue #157): the SAME [ModelPicker] the quick-actions page hosts,
 *  one level shallower — opens straight on the rows (no back affordance), closes on done/scrim. */
@Composable
fun ModelSheet(repo: PocketRepository, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            ModelPicker(repo, onBack = null, onDone = onDismiss)
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
 * Model picker (design cc-pocket/Model Picker.html) — reached from Quick actions → Model AND, one level
 * shallower, straight from the composer's model chip via [ModelSheet] (issue #157; onBack = null there —
 * direct open, direct close). Rich rows: display name + mono `--model` value + a 1M/200K context pill +
 * a check. Tapping starts a switch: the row shows a spinner while the daemon relaunches; the sheet closes once
 * the model is re-announced (or after a short timeout, so it never hangs). Claude uses the real opus/sonnet/haiku
 * aliases; Codex sessions list Codex model ids.
 */
@Composable
internal fun ModelPicker(repo: PocketRepository, onBack: (() -> Unit)?, onDone: () -> Unit) { // internal (was private) so desktopTest's ShowcaseRender can compose it — SessionsScreen/ChatScreen precedent
    val codex = repo.sessionAgent.value == AgentKind.CODEX
    val opencode = repo.sessionAgent.value == AgentKind.OPENCODE
    val agent = repo.sessionAgent.value ?: AgentKind.CLAUDE
    // Fetch dynamic model list from the daemon when any agent picker opens.
    LaunchedEffect(agent) { repo.fetchModels(agent) }
    val agentModels = repo.agentModels[agent]
    val choices = if (codex) {
        // daemon list first (real cache: configured default leads, includes ids the static trio lacks);
        // static trio only until it answers. A list may ride WITH an error (last-good + failed refresh).
        val visibleModels = agentModels?.models?.takeIf { it.isNotEmpty() } ?: CODEX_MODEL_OPTIONS
        visibleModels.map { ModelChoice(it, it, it, "", false) }
    } else if (opencode) {
        // daemon truth or nothing (see the OPTIONS note above) — the empty state renders below
        (agentModels?.models ?: emptyList()).map { m -> ModelChoice(m, m, m, "", false) }
    }
    // window pill derives from the protocol table, so registering a new alias THERE is the only edit
    else CLAUDE_MODEL_OPTIONS.map { (name, alias) ->
        val big = contextWindowFor(alias) == LARGE_CONTEXT_WINDOW
        ModelChoice(name, alias, alias, if (big) "1M" else "200K", big)
    }
    val selected = if (codex || opencode) repo.model.value else modelAlias(repo.model.value)
    var switchingTo by remember { mutableStateOf<String?>(null) }
    // close once the daemon confirms the switch (model re-announced through SessionLive)…
    LaunchedEffect(switchingTo, repo.model.value) {
        val target = switchingTo ?: return@LaunchedEffect
        val now = if (codex || opencode) repo.model.value else modelAlias(repo.model.value)
        // raw compare too: a custom id ("kimi-k2…") never alias-matches, but the daemon echoes it verbatim
        if (now.equals(target, ignoreCase = true) || repo.model.value?.equals(target, ignoreCase = true) == true) onDone()
    }
    // …or after a short timeout, so a silent relaunch never leaves the sheet stuck spinning
    LaunchedEffect(switchingTo) { if (switchingTo != null) { delay(4000); onDone() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // chip-direct opens (ModelSheet) have no quick-actions page to go back to — the title stands alone
        if (onBack != null) Text("‹ ", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(enabled = switchingTo == null, onClick = onBack).padding(end = 4.dp))
        Text(stringResource(Res.string.qa_model), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    // Gateway model presets (issue #139): one-tap vendor ids for third-party gateway users. When the
    // daemon reports a gateway ANTHROPIC_BASE_URL (DaemonInfo) the section LEADS the picker — those
    // users pick vendor ids, not Claude aliases. On the official endpoint it sits behind a collapsed
    // toggle below, so the sheet keeps today's size for everyone else. Claude sessions only: Codex
    // model routing doesn't go through ANTHROPIC_BASE_URL, and OpenCode has its own model format
    // (provider/name) — gateway presets would send bare ids like "deepseek-chat" that cause hangs.
    val gatewayUrl = if (codex || opencode) null else repo.gatewayBaseUrl.value
    val pickPreset: (String) -> Unit = { switchingTo = it; repo.switchModel(it) }
    // opencode has NO static fallback rows: surface the fetch error / loading state explicitly
    // instead of a silent blank (or worse, a catalog of models this user's providers can't run)
    if (opencode && (agentModels?.error != null || choices.isEmpty())) {
        Column(Modifier.padding(top = 10.dp)) {
            agentModels?.error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 16.sp) }
            if (choices.isEmpty() && agentModels?.error == null) {
                Text(stringResource(Res.string.opencode_models_loading), color = Tok.muted, fontSize = 12.5.sp)
            }
        }
    }
    if (gatewayUrl != null) {
        // Issue #167: on a gateway the Claude ALIASES lead. Anthropic-compatible endpoints map
        // opus/sonnet/haiku onto their own tiers, so an alias follows the vendor across generations
        // — while a hand-written native id rots silently (#168 was exactly that rot coming due).
        // The vendor rows keep their place one group below as cold-start seeds: aggregator gateways
        // that don't map aliases still need them, and so does anyone wanting a specific tier.
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(Res.string.model_section_anthropic))
            Spacer(Modifier.weight(1f)) // pill sits flush right (0714 design)
            gatewayHostLabel(gatewayUrl)?.let { host -> GatewayHostPill(host) }
        }
        Text(
            stringResource(Res.string.model_gateway_alias_note),
            color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Column(Modifier.padding(top = if (gatewayUrl != null) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    // …then the vendor ids, demoted to the second group (issue #167). Ranking + "suggested" ticks
    // still read the host, but the pill has moved up to the recommended group's header.
    if (gatewayUrl != null) {
        Column(Modifier.padding(top = 14.dp)) { Hairline() }
        GatewayPresetSection(repo, gatewayUrl, switchingTo, pickPreset, showHostPill = false)
    }
    // Custom model id (issue #54): third-party gateways (cc-switch presets etc.) route ids a fixed list
    // can't know, and `--model` passes any string through — so hand that power to the user. Prefilled when
    // the session already runs an id outside the presets, with the same ✓/spinner the preset rows use.
    val presetActive = choices.any { it.pick.equals(selected, ignoreCase = true) }
    val customActive = !presetActive && !repo.model.value.isNullOrBlank()
    // NOT keyed on the live model: an external switch (another device's /model, SessionLive echo)
    // must never wipe an id the user is mid-typing here
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
                // the arrow appears only for ids the backend can take at all (opencode: provider/model;
                // codex: not a Claude alias) — the ONE surface where the compat guard gates a user action
                val canSwitchCustom = t.isNotEmpty() && isModelCompatibleWithAgent(agent, t)
                when {
                    isSwitchingCustom -> CircularProgressIndicator(Modifier.size(17.dp), color = Tok.accent, strokeWidth = 2.dp)
                    customActive && t.equals(repo.model.value, ignoreCase = true) && switchingTo == null ->
                        Text("✓", color = Tok.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    canSwitchCustom && switchingTo == null -> Text(
                        "→", color = Tok.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { switchingTo = t; repo.switchModel(t) }.padding(6.dp),
                    )
                }
            }
        }
    }
    // no gateway detected: the same preset rows wait behind ONE quiet disclosure row at the very end
    // (0714 design) — official-endpoint users keep today's picker, no gateway chrome above it.
    // Claude only: gateway presets are bare vendor ids, meaningless to codex and a hang for opencode.
    if (!codex && !opencode && gatewayUrl == null) {
        var showGateway by remember { mutableStateOf(false) }
        Column(Modifier.padding(top = 14.dp)) {
            Hairline()
            Row(
                Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = switchingTo == null) { showGateway = !showGateway }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.model_gateway_show), color = Tok.tx2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (showGateway) "⌃" else "›", color = Tok.muted, fontSize = 14.sp)
            }
            // expanded: same rows, no host pill ([gatewayUrl] null keeps the header pill + ticks away)
            if (showGateway) GatewayPresetSection(repo, gatewayUrl = null, switchingTo = switchingTo, onPick = pickPreset)
        }
    }
    Column(Modifier.padding(top = 14.dp)) {
        Hairline()
        Box(Modifier.padding(top = 12.dp)) {
            if (switchingTo != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), color = Tok.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.model_switching), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
            } else Column {
                // mid-turn (issue #157): the running turn keeps its model — say the pick lands NEXT turn
                if (repo.streaming.value) Text(stringResource(Res.string.model_next_turn_note), color = Tok.tx2, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                Text(stringResource(Res.string.model_switch_hint), color = Tok.muted, fontSize = 12.5.sp)
            }
        }
    }
}

/**
 * The gateway model preset rows (issue #139), fed by the shared [GATEWAY_MODEL_PRESETS] table.
 * [gatewayUrl] non-null = the daemon reported a third-party ANTHROPIC_BASE_URL: [recommendedGatewayPresets]
 * ranks that vendor's ids first and its rows wear the "suggested" tick. Ids route through whatever the
 * user's gateway maps them to — the note row says exactly that instead of promising.
 *
 * [showHostPill] is false when the caller already shows the "via host" pill above (issue #167 put it on
 * the recommended alias group's header), so the sheet never carries two.
 */
@Composable
private fun GatewayPresetSection(
    repo: PocketRepository,
    gatewayUrl: String?,
    switchingTo: String?,
    onPick: (String) -> Unit,
    showHostPill: Boolean = true,
) {
    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(stringResource(Res.string.model_gateway_section))
        Spacer(Modifier.weight(1f)) // pill sits flush right (0714 design)
        if (showHostPill) gatewayHostLabel(gatewayUrl)?.let { host -> GatewayHostPill(host) }
    }
    // #167 ②: prefer what the gateway itself reported; the built-in table is now only a fallback seed
    // (and a lookup for how to draw a row). Empty list = no authoritative answer → previous behaviour.
    val authoritative = repo.agentModels[AgentKind.CLAUDE]?.gatewayModels.orEmpty()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        gatewayRowsFrom(authoritative, gatewayUrl).forEach { p ->
            val isSel = p.id.equals(repo.model.value, ignoreCase = true)
            val isSwitching = switchingTo?.equals(p.id, ignoreCase = true) == true
            val raised = isSel || isSwitching
            val dimmed = switchingTo != null && !isSwitching
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (raised) Tok.raised else Color.Transparent)
                    .then(if (raised) Modifier.border(1.dp, Tok.hair, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(enabled = switchingTo == null) { onPick(p.id) }
                    .alpha(if (dimmed) 0.45f else 1f)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GatewayVendorMonogram(p, 32.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.vendor, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        // host names this vendor → terracotta "suggested" tick (accent stays reserved
                        // for this + the selected check; the monograms never wear it)
                        if (p.matchesGatewayHost(gatewayUrl)) {
                            Spacer(Modifier.width(8.dp))
                            Text("✓", color = Tok.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(3.dp))
                            Text(stringResource(Res.string.model_gateway_suggested), color = Tok.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(p.id, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        isSwitching -> CircularProgressIndicator(Modifier.size(15.dp), color = Tok.accent, strokeWidth = 2.dp)
                        isSel -> Text("✓", color = Tok.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(stringResource(Res.string.model_gateway_note), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

/** Uppercase micro section header (0714 design: 10.5sp bold, wide tracking, muted). */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Tok.muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = modifier)
}

/** Mono "via host" pill with a live-green dot, on the gateway section header's right edge (0714 design). */
@Composable
private fun GatewayHostPill(host: String) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Tok.ok))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(Res.string.model_gateway_via, host), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, maxLines = 1)
    }
}

/** Two-letter vendor lettermark in its semantic tint (0714 design): low-alpha tint fill + tint border,
 *  mono bold letters — never a logo. Shared by both shells; 32dp on mobile rows, 24dp in the desktop popover. */
@Composable
internal fun GatewayVendorMonogram(preset: GatewayModelPreset, size: Dp) {
    val tint = preset.tint.color()
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        Modifier.size(size).clip(shape).background(tint.copy(alpha = 0.13f)).border(1.dp, tint.copy(alpha = 0.33f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(preset.monogram, color = tint, fontFamily = FontFamily.Monospace, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
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
