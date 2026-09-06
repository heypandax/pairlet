@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ccpocket.app.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cfg_claude_note
import dev.ccpocket.app.resources.cfg_codex_autonomous_body
import dev.ccpocket.app.resources.cfg_codex_balanced_body
import dev.ccpocket.app.resources.cfg_codex_cautious_body
import dev.ccpocket.app.resources.cfg_codex_full_body
import dev.ccpocket.app.resources.cfg_codex_note
import dev.ccpocket.app.resources.cfg_kimi_note
import dev.ccpocket.app.resources.cfg_mode_accept
import dev.ccpocket.app.resources.cfg_mode_accept_body
import dev.ccpocket.app.resources.cfg_mode_auto
import dev.ccpocket.app.resources.cfg_mode_auto_body
import dev.ccpocket.app.resources.cfg_mode_automatic
import dev.ccpocket.app.resources.cfg_mode_default
import dev.ccpocket.app.resources.cfg_mode_default_body
import dev.ccpocket.app.resources.cfg_mode_full
import dev.ccpocket.app.resources.cfg_mode_full_body
import dev.ccpocket.app.resources.cfg_mode_plan
import dev.ccpocket.app.resources.cfg_mode_plan_body
import dev.ccpocket.app.resources.cfg_model_follow
import dev.ccpocket.app.resources.cfg_model_none
import dev.ccpocket.app.resources.cfg_model_reported
import dev.ccpocket.app.resources.cfg_opencode_body
import dev.ccpocket.app.resources.cfg_opencode_body2
import dev.ccpocket.app.resources.cfg_opencode_note
import dev.ccpocket.app.resources.cfg_opencode_title
import dev.ccpocket.app.resources.cfg_permission
import dev.ccpocket.app.resources.cfg_preset
import dev.ccpocket.app.resources.cfg_preset_custom
import dev.ccpocket.app.resources.cfg_preset_follow
import dev.ccpocket.app.resources.cfg_preset_note
import dev.ccpocket.app.resources.cfg_start
import dev.ccpocket.app.resources.cfg_start_caption
import dev.ccpocket.app.resources.cfm_body
import dev.ccpocket.app.resources.cfm_cta
import dev.ccpocket.app.resources.cfm_note
import dev.ccpocket.app.resources.cfm_reach
import dev.ccpocket.app.resources.cfm_reach_body
import dev.ccpocket.app.resources.cfm_reach_body_local
import dev.ccpocket.app.resources.cfm_title
import dev.ccpocket.app.resources.cfm_workdir
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.close
import dev.ccpocket.app.resources.codex_preset_autonomous
import dev.ccpocket.app.resources.codex_preset_balanced
import dev.ccpocket.app.resources.codex_preset_cautious
import dev.ccpocket.app.resources.label_agent
import dev.ccpocket.app.resources.label_mode
import dev.ccpocket.app.resources.label_model
import dev.ccpocket.app.resources.new_session_title
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.CtxPill
import dev.ccpocket.app.ui.ModelChoice
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.folderName
import dev.ccpocket.app.ui.modelChipLabel
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.PathWithCopy
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentModePreset
import dev.ccpocket.protocol.AgentPresetInfo
import dev.ccpocket.protocol.PermissionMode
import org.jetbrains.compose.resources.stringResource

/** How much of the screen the bounded configuration surface may occupy — pinned zones + a scrolling body. */
private const val CONFIGURE_SHEET_HEIGHT_FRACTION = 0.90f

/**
 * Configure a new session (Entry Flow UI 2.0 · Master frames 05, 06, 13, 14).
 *
 * The order is semantic and fixed: **workdir → Agent → Model → Mode → one explicit Start**. Three zones —
 * pinned context, scrolling body, pinned decision — so at 200% Dynamic Type the user can still read which
 * directory they are in and still reach the control that starts it.
 *
 * The behavioural change from the old sheet: **selecting a mode no longer starts the session.** A mode row
 * is a selection and reads as one (a check mark, no button ink); [onPick] fires from the single filled
 * `Start session`, which prints the exact combination it will run. Consequences of that:
 *
 *  - switching agent RESETS Model and Mode to that agent's real defaults, and Start reprints before it can
 *    be tapped — no more starting under a Codex preset the label never showed;
 *  - Full access opens the existing confirmation, which names the agent, the workdir and the computer;
 *    Cancel returns here with the selection intact and starts nothing;
 *  - a `started` latch makes a double tap (or an overlapping dismiss callback) a no-op.
 *
 * Dismiss, scrim and system back start nothing — they never did, and now neither does a mode row.
 */
@Composable
fun ConfigureSessionSheet(
    workdir: String?,
    selected: PermissionMode = PermissionMode.DEFAULT,
    selectedNativeMode: String? = null,
    agent: AgentKind = AgentKind.CLAUDE,
    computer: String? = null,
    autoAvailable: Boolean = false,
    availableAgents: List<AgentKind> = AgentKind.entries,
    modelsFor: (AgentKind) -> List<ModelChoice> = { emptyList() },
    defaultModelFor: (AgentKind) -> String? = { null },
    modePresetsFor: (AgentKind) -> List<AgentModePreset> = { emptyList() },
    /**
     * The connected daemon's advertised AGENT presets, per agent (issue #333; dsh only today). A lambda for
     * the same reason as [modePresetsFor] — the agent chips switch backends in place.
     *
     * EMPTY MEANS NO ROW AT ALL, and that is the degradation contract, not a styling choice: a daemon that
     * never advertised presets also never reads [OpenSession.agentPreset], so a picker shown against one
     * would let the user choose something that is silently dropped on the wire and never takes effect.
     */
    agentPresetsFor: (AgentKind) -> List<AgentPresetInfo> = { emptyList() },
    onAgentPicked: (AgentKind) -> Unit = {},
    onPick: (PermissionMode, AgentKind, String?, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectableAgents = availableAgents.ifEmpty { listOf(AgentKind.CLAUDE) }
    val openedAgent = remember { agent.takeIf { it in selectableAgents } ?: selectableAgents.first() }
    var chosenAgent by remember { mutableStateOf(openedAgent) }
    // The connected daemon's advertised permission vocabulary for the agent on screen (Codex only today).
    // It is read per chosen agent because the agent chips switch backends in place, and empty simply means
    // "this daemon doesn't advertise one" — the ladder falls back to the App's built-in table.
    val modePresets = modePresetsFor(chosenAgent)
    // null = "follow the default" (Settings / the CLI's own). Reset per agent: a Claude alias is not a model
    // Codex can run, and compatibleModelForAgent would drop it silently anyway.
    var chosenModel by remember(chosenAgent) { mutableStateOf<String?>(null) }
    // #333. Reset per agent like the model above: a dsh preset id means nothing to Claude. Null = "follow
    // the backend's own default", which is also what an un-answered catalogue leaves it at.
    val agentPresets = agentPresetsFor(chosenAgent)
    var chosenPreset by remember(chosenAgent) { mutableStateOf<String?>(null) }
    var chosenMode by remember(chosenAgent) {
        mutableStateOf(seedModeChoice(chosenAgent, openedAgent, selected, selectedNativeMode, autoAvailable, modePresets))
    }
    // whether the ladder below is the user's answer or still the seed. The peer's capability list can land
    // a beat AFTER the sheet opens (Claude's native Auto row appears then, and Codex's advertised vocabulary
    // arrives with its ModelsList), and re-seeding at that moment must never overwrite a rung the user has
    // already chosen.
    var modeTouched by remember(chosenAgent) { mutableStateOf(false) }
    LaunchedEffect(chosenAgent, autoAvailable, modePresets) {
        if (!modeTouched) chosenMode = seedModeChoice(chosenAgent, openedAgent, selected, selectedNativeMode, autoAvailable, modePresets)
    }
    var confirming by remember { mutableStateOf(false) }
    // one start per sheet: a second tap, or a dismiss callback racing the start, has nothing left to fire
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(chosenAgent) { onAgentPicked(chosenAgent) }

    val start = {
        if (!started) {
            started = true
            // The preset only travels when this agent really advertised one — otherwise a value left over
            // from a build that did would ride out to a daemon that ignores it.
            onPick(
                chosenMode.mode, chosenAgent, chosenMode.nativeMode, chosenModel,
                chosenPreset?.takeIf { agentPresets.isNotEmpty() },
            )
        }
    }

    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxHeight(CONFIGURE_SHEET_HEIGHT_FRACTION)) {
            if (confirming) {
                FullAccessConfirm(
                    agent = chosenAgent, workdir = workdir, computer = computer,
                    // Cancel returns to configuration with the selection intact — `confirming` is the only
                    // thing that changes, so Full access is still the chosen mode.
                    onCancel = { confirming = false },
                    onConfirm = { confirming = false; start() },
                )
                return@Column
            }
            ConfigureHeader(workdir, onDismiss)
            Hairline()
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = Metric.gutter).padding(bottom = Metric.gapL),
            ) {
                EntryLabel(stringResource(Res.string.label_agent), Modifier.padding(top = Metric.gapL, bottom = Metric.gap))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
                    verticalArrangement = Arrangement.spacedBy(Metric.gapS),
                ) {
                    selectableAgents.forEach { a ->
                        AgentChip(a, selected = a == chosenAgent) { chosenAgent = a }
                    }
                }

                val models = modelsFor(chosenAgent)
                EntryLabel(
                    stringResource(if (models.isEmpty()) Res.string.label_model else Res.string.cfg_model_reported),
                    Modifier.padding(top = 22.dp, bottom = Metric.gapS),
                )
                ModelSection(models, chosenModel, defaultModelFor(chosenAgent)) { chosenModel = it }

                when (modeChoiceSet(chosenAgent)) {
                    ModeChoiceSet.OPENCODE_AUTOMATIC -> {
                        EntryLabel(stringResource(Res.string.cfg_permission), Modifier.padding(top = 22.dp, bottom = Metric.gapS))
                        OpenCodeAutomatic()
                    }
                    else -> {
                        EntryLabel(stringResource(Res.string.label_mode), Modifier.padding(top = 22.dp, bottom = Metric.gapS))
                        agentModeChoices(chosenAgent, autoAvailable, modePresets).forEach { choice ->
                            ModeSelectionRow(
                                label = modeChoiceLabel(chosenAgent, choice),
                                body = modeChoiceBody(chosenAgent, choice),
                                danger = choice.danger,
                                selected = choice == chosenMode,
                            ) { chosenMode = choice; modeTouched = true } // selection only — nothing starts here
                        }
                        modeFootnote(chosenAgent)?.let {
                            EntryNote(it, Modifier.padding(top = Metric.gap))
                        }
                    }
                }

                // #333: a THIRD axis, under the mode ladder because it is the least often changed of the
                // three and reads as a refinement of "how should this session behave". Shown only when the
                // daemon advertised presets for this agent.
                if (agentPresets.isNotEmpty()) {
                    EntryLabel(stringResource(Res.string.cfg_preset), Modifier.padding(top = 22.dp, bottom = Metric.gapS))
                    PresetSection(agentPresets, chosenPreset) { chosenPreset = it }
                    EntryNote(stringResource(Res.string.cfg_preset_note), Modifier.padding(top = Metric.gap))
                }
            }
            Hairline()
            Column(Modifier.padding(horizontal = Metric.gutter).padding(top = Metric.gap)) {
                EntryPrimaryButton(
                    stringResource(Res.string.cfg_start),
                    caption = stringResource(
                        Res.string.cfg_start_caption,
                        folderName(workdir),
                        agentName(chosenAgent),
                        modeChoiceLabel(chosenAgent, chosenMode),
                    ),
                    enabled = !started,
                ) {
                    if (chosenMode.needsFullAccessConfirm(chosenAgent)) confirming = true else start()
                }
            }
        }
    }
}

/** Zone A: the context that must never scroll away — what this session will run in. */
@Composable
private fun ConfigureHeader(workdir: String?, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Metric.gutter).padding(top = Metric.gapS, bottom = Metric.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EntrySheetTitle(stringResource(Res.string.new_session_title), Modifier.weight(1f))
            Box(
                Modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(Res.string.close), tint = Tok.tx2, modifier = Modifier.size(18.dp))
            }
        }
        if (workdir != null) {
            Text(
                folderName(workdir), color = Tok.tx, style = TypeRole.action,
                modifier = Modifier.padding(top = Metric.gapXs),
            )
            // the FULL path, wrapping — the end of a path is the part that identifies it
            PathWithCopy(workdir, Modifier.padding(top = 2.dp), maxLines = Int.MAX_VALUE)
        }
    }
}

/** An agent choice. A name is all it needs at this width; the explanation belongs to the mode rows. */
@Composable
private fun AgentChip(agent: AgentKind, selected: Boolean, onClick: () -> Unit) {
    val c = agentColor(agent)
    val shape = RoundedCornerShape(Metric.gap)
    Box(
        Modifier.heightIn(min = Metric.touch).widthIn(min = 78.dp).clip(shape)
            .background(if (selected) c.copy(alpha = 0.12f) else Color.Transparent)
            .border(Metric.hairline, if (selected) c else Tok.hair, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Metric.gapL, vertical = Metric.gapS),
        contentAlignment = Alignment.Center,
    ) {
        Text(agentName(agent), color = if (selected) c else Tok.tx2, style = TypeRole.action)
    }
}

/**
 * The model section.
 *
 * Ids come from the connected computer VERBATIM. When it reported no list the row says exactly that instead
 * of naming a model nobody promised — an invented default is the one thing this section must never do.
 */
@Composable
private fun ModelSection(choices: List<ModelChoice>, chosen: String?, fallback: String?, onChoose: (String?) -> Unit) {
    val follow = stringResource(Res.string.cfg_model_follow)
    if (choices.isEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            Hairline()
            Column(Modifier.padding(vertical = Metric.gap)) {
                Text(follow, color = Tok.tx, style = TypeRole.action)
                Text(
                    fallback?.takeIf { it.isNotBlank() }?.let { modelChipLabel(it) }
                        ?: stringResource(Res.string.cfg_model_none),
                    color = Tok.tx2, style = TypeRole.metaMono, modifier = Modifier.padding(top = Metric.gapXs),
                )
            }
            Hairline()
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        choices.forEach { c ->
            ModelRow(c.name, c.id, c.ctx, c.big, chosen.equals(c.pick, ignoreCase = true)) { onChoose(c.pick) }
        }
        // the honest fallback stays selectable even when a list exists: "whatever the computer defaults to"
        ModelRow(
            follow,
            fallback?.takeIf { it.isNotBlank() }?.let { modelChipLabel(it) } ?: stringResource(Res.string.cfg_model_none),
            "", false, chosen == null,
        ) { onChoose(null) }
        Hairline()
    }
}

/**
 * The agent-preset section — the same list-row shape as [ModelSection], deliberately.
 *
 * Copy is the BACKEND's, verbatim ([AgentPresetInfo.label] / [AgentPresetInfo.detail]), so a preset this
 * App build has never heard of — including one the user wrote themselves — still shows up readable
 * instead of as a bare id. A user-authored preset is tagged, because "mine" versus "shipped" is the one
 * distinction the backend makes that the label alone does not carry.
 *
 * "Follow the computer's default" leads and is the initial selection: the backend already HAS a default
 * (marked [AgentPresetInfo.recommended]) and pre-selecting a named row would send an explicit choice the
 * user never made. The recommended row is still marked so the default is visible, not merely implied.
 */
@Composable
private fun PresetSection(presets: List<AgentPresetInfo>, chosen: String?, onChoose: (String?) -> Unit) {
    val recommended = presets.firstOrNull { it.recommended }
    Column(Modifier.fillMaxWidth()) {
        PresetRow(
            name = stringResource(Res.string.cfg_preset_follow),
            detail = recommended?.label,
            custom = false,
            selected = chosen == null,
        ) { onChoose(null) }
        presets.forEach { preset ->
            PresetRow(
                name = preset.label,
                detail = preset.detail,
                custom = preset.custom,
                selected = chosen == preset.id,
            ) { onChoose(preset.id) }
        }
        Hairline()
    }
}

@Composable
private fun PresetRow(name: String, detail: String?, custom: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryCheckMark(selected)
            Column(Modifier.weight(1f).padding(start = Metric.gapS)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Tok.tx, style = TypeRole.action, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (custom) {
                        Spacer(Modifier.width(Metric.gapXs))
                        Text(
                            stringResource(Res.string.cfg_preset_custom),
                            color = Tok.tx2,
                            // tightCenter: this sits geometrically beside a LARGER text on the same row —
                            // a bare fontSize would centre the line box, not the glyphs (project rule).
                            style = tightCenter(11.sp),
                        )
                    }
                }
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it, color = Tok.tx2, style = TypeRole.caption,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(name: String, id: String?, ctx: String, big: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryCheckMark(selected)
            Column(Modifier.weight(1f).padding(start = Metric.gapS)) {
                Text(name, color = Tok.tx, style = TypeRole.action, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!id.isNullOrBlank()) Row(
                    Modifier.padding(top = Metric.gapXs), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(id, color = Tok.tx2, style = TypeRole.metaMono, modifier = Modifier.weight(1f, fill = false))
                    if (ctx.isNotEmpty()) { Box(Modifier.padding(start = Metric.gapS)) { CtxPill(ctx, big) } }
                }
            }
        }
    }
}

/** One mode row. A check mark and a sentence — no chevron, no button ink: this SELECTS, it does not start. */
@Composable
private fun ModeSelectionRow(label: String, body: String, danger: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = Metric.gap),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 2.dp)) { EntryCheckMark(selected) }
            Column(Modifier.weight(1f).padding(start = Metric.gapS)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                    // danger ink appears on Full access and in its confirmation, nowhere else
                    if (danger) Box(Modifier.size(8.dp).background(Tok.danger))
                    Text(label, color = Tok.tx, style = TypeRole.action)
                }
                Text(body, color = Tok.tx2, style = TypeRole.preview, modifier = Modifier.padding(top = Metric.gapXs))
            }
        }
    }
}

/**
 * OpenCode's Permission section: a STATEMENT where a ladder would be.
 *
 * The daemon launches opencode with `--auto` — every tool call is CLI-approved and the PermissionBridge is
 * never consulted — so a greyed-out ladder would imply a ladder exists. The mark is attention, not danger:
 * this is honest backend behaviour, not a hazardous choice the user is making.
 */
@Composable
private fun OpenCodeAutomatic() {
    val shape = RoundedCornerShape(Metric.radius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Tok.warn.copy(alpha = 0.08f))
            .border(Metric.hairline, Tok.warn.copy(alpha = 0.38f), shape).padding(Metric.gapL),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
            Box(Modifier.size(8.dp).rotate(45f).background(Tok.warn))
            Text(stringResource(Res.string.cfg_opencode_title), color = Tok.tx, style = TypeRole.rowTitle)
        }
        Text(
            stringResource(Res.string.cfg_opencode_body), color = Tok.tx2, style = TypeRole.preview,
            modifier = Modifier.padding(top = Metric.gapS),
        )
        Text(
            stringResource(Res.string.cfg_opencode_body2), color = Tok.tx2, style = TypeRole.preview,
            modifier = Modifier.padding(top = Metric.gapS),
        )
    }
    EntryNote(stringResource(Res.string.cfg_opencode_note), Modifier.padding(top = Metric.gap))
}

/**
 * The existing Full-access safety step, restyled — not a new gate.
 *
 * It names the agent, the workdir and the computer, because a blast radius is a place, not an adjective.
 */
@Composable
private fun FullAccessConfirm(
    agent: AgentKind,
    workdir: String?,
    computer: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = Metric.gutter).padding(top = Metric.gapS, bottom = Metric.gapL),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                Box(Modifier.size(10.dp).background(Tok.danger))
                EntrySheetTitle(stringResource(Res.string.cfm_title))
            }
            Text(
                stringResource(Res.string.cfm_body, agentName(agent)), color = Tok.tx2, style = TypeRole.preview,
                modifier = Modifier.padding(top = Metric.gap),
            )
            if (workdir != null) {
                Hairline(Modifier.padding(top = Metric.gapL))
                EntryLabel(stringResource(Res.string.cfm_workdir), Modifier.padding(top = Metric.gap, bottom = Metric.gapXs))
                PathWithCopy(workdir, color = Tok.tx, maxLines = Int.MAX_VALUE)
            }
            Hairline(Modifier.padding(top = Metric.gapL))
            EntryLabel(stringResource(Res.string.cfm_reach), Modifier.padding(top = Metric.gap, bottom = Metric.gapXs))
            Text(
                computer?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.cfm_reach_body, it) }
                    ?: stringResource(Res.string.cfm_reach_body_local),
                color = Tok.tx2, style = TypeRole.preview,
            )
            EntryNote(stringResource(Res.string.cfm_note), Modifier.padding(top = Metric.gapL))
        }
        Hairline()
        Row(
            Modifier.padding(horizontal = Metric.gutter).padding(top = Metric.gap),
            horizontalArrangement = Arrangement.spacedBy(Metric.gap),
        ) {
            EntrySecondaryButton(stringResource(Res.string.cancel), Modifier.weight(1f), onClick = onCancel)
            Box(Modifier.weight(1.35f)) {
                EntryPrimaryButton(stringResource(Res.string.cfm_cta), tint = Tok.danger, onClick = onConfirm)
            }
        }
    }
}

// ── labels ──────────────────────────────────────────────────────────────────────────────────────

/** The mode's product name, per agent. Codex keeps its own preset names; OpenCode prints `automatic`. */
@Composable
fun modeChoiceLabel(agent: AgentKind, choice: ModeChoice): String = stringResource(
    when {
        agent == AgentKind.OPENCODE -> Res.string.cfg_mode_automatic
        choice.nativeMode != null -> Res.string.cfg_mode_auto
        agent == AgentKind.CODEX -> when (choice.mode) {
            PermissionMode.PLAN -> Res.string.codex_preset_cautious
            PermissionMode.DEFAULT -> Res.string.codex_preset_balanced
            PermissionMode.ACCEPT_EDITS -> Res.string.codex_preset_autonomous
            PermissionMode.BYPASS_PERMISSIONS -> Res.string.cfg_mode_full
        }
        else -> when (choice.mode) {
            PermissionMode.DEFAULT -> Res.string.cfg_mode_default
            PermissionMode.ACCEPT_EDITS -> Res.string.cfg_mode_accept
            PermissionMode.PLAN -> Res.string.cfg_mode_plan
            PermissionMode.BYPASS_PERMISSIONS -> Res.string.cfg_mode_full
        }
    },
)

/** What the mode actually does, in one plain sentence. */
@Composable
private fun modeChoiceBody(agent: AgentKind, choice: ModeChoice): String = stringResource(
    when {
        choice.nativeMode != null -> Res.string.cfg_mode_auto_body
        agent == AgentKind.CODEX -> when (choice.mode) {
            PermissionMode.PLAN -> Res.string.cfg_codex_cautious_body
            PermissionMode.DEFAULT -> Res.string.cfg_codex_balanced_body
            PermissionMode.ACCEPT_EDITS -> Res.string.cfg_codex_autonomous_body
            PermissionMode.BYPASS_PERMISSIONS -> Res.string.cfg_codex_full_body
        }
        else -> when (choice.mode) {
            PermissionMode.DEFAULT -> Res.string.cfg_mode_default_body
            PermissionMode.ACCEPT_EDITS -> Res.string.cfg_mode_accept_body
            PermissionMode.PLAN -> Res.string.cfg_mode_plan_body
            PermissionMode.BYPASS_PERMISSIONS -> Res.string.cfg_mode_full_body
        }
    },
)

/** The one thing worth saying about an agent's ladder that the rows themselves cannot say. */
@Composable
private fun modeFootnote(agent: AgentKind): String? = when (agent) {
    AgentKind.CLAUDE -> stringResource(Res.string.cfg_claude_note)
    AgentKind.CODEX -> stringResource(Res.string.cfg_codex_note)
    AgentKind.KIMI -> stringResource(Res.string.cfg_kimi_note)
    AgentKind.ZCODE -> null
    AgentKind.DSH -> null // issue #255: the three rows already say everything v1 can promise about dsh
    AgentKind.OPENCODE -> null
}
