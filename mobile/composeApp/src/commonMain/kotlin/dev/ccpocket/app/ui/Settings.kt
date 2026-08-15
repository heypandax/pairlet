package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import dev.ccpocket.app.APP_STORE_URL
import dev.ccpocket.app.AppUpdateRoute
import dev.ccpocket.app.RELEASES_URL
import dev.ccpocket.app.USER_MANUAL_TROUBLESHOOTING_URL
import dev.ccpocket.app.USER_MANUAL_URL
import dev.ccpocket.app.appUpdateRoute
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.agentFilterIsAll
import dev.ccpocket.app.data.toggleAgentFilter
import dev.ccpocket.app.update.VersionStatus
import dev.ccpocket.app.lock.AppLockController
import dev.ccpocket.app.lock.AutoLockDelay
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.voice.NativeDictation
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.share.JoinFolderScreen
import dev.ccpocket.app.ui.share.SharedFoldersScreen
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.PermissionMode
import org.jetbrains.compose.resources.stringResource

/**
 * Default-model options for the selected backend. Claude deliberately keeps its existing alias table;
 * Codex uses the connected daemon's catalog (with the same static fallback as the session picker), while
 * OpenCode/Kimi only offer ids their daemon actually reported. A selected stale/custom id leads so a
 * refresh can never make the current preference disappear from Settings.
 */
internal fun settingsDefaultModelOptions(
    agent: AgentKind,
    selected: String?,
    discovered: List<String>,
): List<String?> {
    val available = when (agent) {
        AgentKind.CLAUDE -> CLAUDE_MODEL_OPTIONS.map { it.second }
        AgentKind.CODEX -> discovered.ifEmpty { CODEX_MODEL_OPTIONS }
        AgentKind.OPENCODE, AgentKind.KIMI, AgentKind.ZCODE -> discovered
    }
    return (listOf<String?>(null) + listOfNotNull(selected) + available.filter { it.isNotBlank() }).distinct()
}

// #220: Full Control expiry presets (ms). 0 = never expires (the default); the rest re-arm the old
// safety net at a chosen duration. Kept as raw ms so the wire value is language-neutral.
val FULL_CONTROL_EXPIRY_OPTS: List<Long> = listOf(0L, 30 * 60_000L, 60 * 60_000L, 4 * 60 * 60_000L)

fun fullControlExpiryLabel(ms: Long, neverLabel: String): String = when {
    ms <= 0L -> neverLabel
    ms % (60 * 60_000L) == 0L -> "${ms / (60 * 60_000L)}h"
    else -> "${ms / 60_000L}m"
}

// context-window override presets for the usage statusline's denominator (issue #60): null = follow the
// model-derived / daemon-reported window. Covers the two standard windows a custom model id might really have.
private val CONTEXT_WINDOW_OPTS: List<Long?> = listOf(null, DEFAULT_CONTEXT_WINDOW, LARGE_CONTEXT_WINDOW)

// chat text-size presets (issue #8): five stops within PocketRepository.FONT_SCALE_MIN..MAX, rendered as an
// "A"-gradient segmented control so it reads the same in any language.
private val FONT_SCALE_STEPS: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.4f)

/**
 * Settings as a full screen (not a sheet).
 *
 * Mobile UI 2.0 replaces the single long scroll with a first-hop LANDING plus five focused category pages
 * (Supporting Surfaces UI 2.0 · Master v1). Every control and every repository binding is the one that was
 * here before — only where it lives moved. Token usage and scheduled tasks stay one tap from the landing
 * because they are destinations, not settings.
 *
 * Navigation is one local [SettingsCategory] beside the existing full-screen flags. Back pops a category to
 * the landing and only then leaves Settings, so a drill-down is never skipped over. [onBack] returns to the
 * screen that opened Settings.
 */
@Composable
fun SettingsScreen(repo: PocketRepository, onBack: () -> Unit) {
    // Capability rows come from the installed CLI/model cache. A recomposition after the reply replaces
    // the loading/empty state; no global max/ultra list is guessed in the client.
    LaunchedEffect(repo.sessionDefaultAgent) { repo.fetchModels(repo.sessionDefaultAgent) }
    // Approvals (issue #201). Daemon truth: the Security page shows the preference only once an
    // ApprovalPrefs reply proves this daemon can honor it. Asked HERE rather than on that page, so the
    // answer has already arrived by the time it is opened.
    LaunchedEffect(Unit) { repo.fetchApprovalPrefs() }

    // the one local route. Full-screen children below keep their own flags and return to whatever this
    // holds, so backing out of Usage lands on the page that opened it rather than on the landing.
    var category by remember { mutableStateOf<SettingsCategory?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        HelpCenterScreen(HelpEntryPoint.SETTINGS, onBack = { showHelp = false })
        return
    }
    var showUsage by remember { mutableStateOf(false) }
    if (showUsage) { UsageScreen(repo, onBack = { showUsage = false }); return } // full-screen usage dashboard (#26)
    // scheduled tasks (issue #137): list + cancel, full-screen like usage
    var showSchedules by remember { mutableStateOf(false) }
    if (showSchedules) { ScheduleScreen(repo, onBack = { showSchedules = false }); return }
    // folder-share (issue #115): owner management + guest redeem, each full-screen like usage
    var showShares by remember { mutableStateOf(false) }
    if (showShares) { SharedFoldersScreen(repo, onBack = { showShares = false }); return }
    var showJoin by remember { mutableStateOf(false) }
    if (showJoin) { JoinFolderScreen(repo, onBack = { showJoin = false }, onJoined = { showJoin = false; onBack() }); return }
    // headless bridges (issue #91 follow-up): monitor + revoke the IM bots driving this machine
    var showBridges by remember { mutableStateOf(false) }
    if (showBridges) { dev.ccpocket.app.ui.bridge.BridgesScreen(repo, onBack = { showBridges = false }); return }
    // collaborator links (SESSION-HANDOFF.md §4.1): contact management + the one QR connect flow
    var showCollaborators by remember { mutableStateOf(false) }
    var showConnectColleague by remember { mutableStateOf(false) }
    if (showConnectColleague) {
        dev.ccpocket.app.ui.handoff.ConnectColleagueFlow(repo, fromDraft = false, onBackToHandoff = {}, onClose = { showConnectColleague = false })
        return
    }
    if (showCollaborators) {
        dev.ccpocket.app.ui.handoff.CollaboratorsFlow(repo, onConnectNew = { showConnectColleague = true }, onBack = { showCollaborators = false })
        return
    }
    // ReviewRequest (REVIEW-REQUEST.md §12): the discoverable fallback. The routine path is the header
    // action on the projects screen — a feature reachable only through Settings is a feature people
    // forget they have, and this one's whole value is that a colleague's ask does not go unseen.
    var showReviews by remember { mutableStateOf(false) }
    if (showReviews) { dev.ccpocket.app.ui.review.ReviewCenterRoute(repo) { showReviews = false }; return }
    // back pops a category to the landing first, and only then leaves Settings — so it never falls
    // through to the app-level navigation while a drill-down is open
    dev.ccpocket.app.SystemBackHandler(enabled = true) { if (category != null) category = null else onBack() }

    val page = category
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        FirstHopHeader(
            title = stringResource(page?.let(::settingsCategoryTitleRes) ?: Res.string.settings_title),
            // one factual line, and only on the landing: which computer these settings are talking to.
            // Nothing derived, nothing secret — the paired binding's own display name or nothing at all.
            summary = if (page != null) null else connectedToSummary(repo),
            onBack = { if (page != null) category = null else onBack() },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
        ) {
            when (page) {
                null -> SettingsLanding(
                    repo,
                    onCategory = { category = it },
                    onUsage = { showUsage = true },
                    onSchedules = { showSchedules = true },
                )

                SettingsCategory.GENERAL -> GeneralPage(repo)
                SettingsCategory.AGENT -> AgentDefaultsPage(repo)
                SettingsCategory.CONNECTIONS -> ConnectionsPage(
                    repo,
                    // switching and pairing both tear this screen down, so they leave Settings first
                    onSwitch = { target -> onBack(); repo.switchDaemon(target) },
                    onAdd = { onBack(); repo.beginAddDevice() },
                    onShares = { showShares = true },
                    onJoin = { showJoin = true },
                    onCollaborators = { showCollaborators = true },
                    onReviews = { showReviews = true },
                    onBridges = { showBridges = true },
                )

                SettingsCategory.SECURITY -> SecurityPage(repo)
                SettingsCategory.SUPPORT -> SupportPage(
                    repo,
                    onHelp = { showHelp = true },
                    // Exit -> disconnect to the computer picker (ConnectScreen), where paired computers
                    // are managed
                    onExit = { onBack(); repo.disconnect() },
                )
            }
        }
    }
}

/**
 * The landing: the two utility destinations, then the five categories. Nothing here is itself a control.
 *
 * UI 2.1 (A3) splits the two groups the eye kept merging. The utilities stay BARE on the paper base —
 * they are places to go, not settings — while the five categories move into one low container under a
 * written label. One container, not five cards: the grouping is the point, and a stack of cards would
 * re-flatten it into the same undifferentiated list this replaces.
 */
@Composable
private fun SettingsLanding(
    repo: PocketRepository,
    onCategory: (SettingsCategory) -> Unit,
    onUsage: () -> Unit,
    onSchedules: () -> Unit,
) {
    Hairline()
    FirstHopRow(stringResource(Res.string.settings_usage), onClick = onUsage)
    Hairline()
    FirstHopRow(stringResource(Res.string.schedule_tasks_title), onClick = onSchedules)
    Hairline()
    FirstHopSectionLabel(stringResource(Res.string.settings_categories))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.surface)) {
        SettingsCategory.entries.forEachIndexed { index, c ->
            if (index > 0) Hairline(Modifier.padding(horizontal = 14.dp))
            FirstHopRow(
                title = stringResource(settingsCategoryTitleRes(c)),
                subtitle = stringResource(settingsCategorySubRes(c)),
                // 64 dp floor: a two-line row inside a container needs the extra air, and at 200% type it
                // grows past this rather than compressing the second line away
                minHeight = 64.dp,
                horizontalPadding = 14.dp,
                onClick = { onCategory(c) },
            ) {
                // "a colleague is waiting on you" must not be two taps deep
                if (c == SettingsCategory.CONNECTIONS && repo.reviewPendingCount > 0) PendingCountMark(repo.reviewPendingCount)
            }
        }
    }
}

/** A count that says what it counts: a bare accent "2" is not something a screen reader can convey. */
@Composable
private fun PendingCountMark(count: Int) {
    val label = stringResource(
        if (count == 1) Res.string.rv_summary_waiting_one else Res.string.rv_summary_waiting_many, count,
    )
    Text(
        "$count", color = Tok.base, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Tok.accent)
            .semantics { contentDescription = label }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ══ General ════════════════════════════════════════════════════════════════════════════════════════

/** Appearance, text size, notifications and — where a native engine exists to choose against — voice. */
@Composable
private fun GeneralPage(repo: PocketRepository) {
    SectionLabel(stringResource(Res.string.appearance_section))
    // System / Light / Dark segmented control — same shape as the text-size one below (#63)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val modes = listOf(
            ThemeMode.SYSTEM to stringResource(Res.string.appearance_system),
            ThemeMode.LIGHT to stringResource(Res.string.appearance_light),
            ThemeMode.DARK to stringResource(Res.string.appearance_dark),
        )
        modes.forEach { (mode, label) ->
            val sel = repo.themeMode.value == mode
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(7.dp))
                    .then(if (sel) Modifier.background(Tok.accent) else Modifier)
                    .semantics { selected = sel }
                    .clickable { repo.setThemeMode(mode) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (sel) Tok.base else Tok.tx2, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
    Text(
        stringResource(Res.string.appearance_hint), color = Tok.muted, fontSize = 12.sp,
        lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp),
    )

    SectionLabel(stringResource(Res.string.text_size_section))
    // "A"-gradient segmented control: each segment shows "A" at a representative size; selected fills accent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FONT_SCALE_STEPS.forEachIndexed { i, s ->
            val sel = repo.fontScale.value in (s - 0.04f)..(s + 0.04f)
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(7.dp))
                    .then(if (sel) Modifier.background(Tok.accent) else Modifier)
                    .semantics { selected = sel }
                    .clickable { repo.setFontScale(s) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "A", color = if (sel) Tok.base else Tok.tx2,
                    fontSize = (11f + i * 2.5f).sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
    // live preview at the chosen scale
    Box(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        Text(stringResource(Res.string.text_size_sample), color = Tok.tx, fontSize = 14.sp * repo.fontScale.value)
    }

    SectionLabel(stringResource(Res.string.notifications_section))
    ToggleRow(
        label = stringResource(Res.string.notify_on_complete),
        sub = stringResource(Res.string.notify_on_complete_sub),
        checked = repo.notificationsOn.value,
        onChange = { repo.setNotificationsEnabled(it) },
    )

    // Only shown where a native dictation engine exists to choose against (iOS) — elsewhere
    // whisper is already the only voice path and the toggle would be a no-op.
    if (NativeDictation.available) {
        SectionLabel(stringResource(Res.string.voice_section))
        ToggleRow(
            label = stringResource(Res.string.voice_use_whisper),
            sub = stringResource(Res.string.voice_use_whisper_sub),
            checked = repo.voiceWhisper.value,
            onChange = { repo.setVoiceWhisper(it) },
        )
    }
}

// ══ Agent & session defaults ═══════════════════════════════════════════════════════════════════════

/** What a NEW session starts with, plus the two windows that decide how its usage is measured. */
@Composable
private fun AgentDefaultsPage(repo: PocketRepository) {
    val defaultAgent = repo.sessionDefaultAgent
    // Claude Auto is a native Claude-only mode. Keep the stored value when the user merely inspects
    // another backend, but render that backend's real launch mode (the shared PermissionMode fallback).
    val effectivePermissionMode = repo.defaultPermissionMode.value.takeIf { defaultAgent == AgentKind.CLAUDE }
    AgentDefaultsSummary(repo, defaultAgent)
    SectionLabel(stringResource(Res.string.settings_default_agent))
    SettingsChoiceRows(
        options = repo.availableAgents,
        selected = defaultAgent,
        label = ::agentName,
    ) { repo.setDefaultAgent(it) }
    Text(
        stringResource(Res.string.settings_default_agent_sub),
        color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(top = 10.dp, start = 2.dp),
    )

    SectionLabel(stringResource(Res.string.default_mode_section))
    Column(Modifier.fillMaxWidth()) {
        val modeOptions = MODES + if (
            defaultAgent == AgentKind.CLAUDE &&
            repo.supportsPermissionMode(CLAUDE_PERMISSION_MODE_AUTO)
        ) listOf(AUTO_MODE) else emptyList()
        modeOptions.forEach { m ->
            Hairline()
            val sel = repo.defaultMode.value == m.key && effectivePermissionMode == m.nativeMode
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { selected = sel }
                    .clickable {
                        if (m.nativeMode == CLAUDE_PERMISSION_MODE_AUTO) repo.setDefaultAutoMode()
                        else repo.setDefaultMode(m.key)
                    }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", color = m.color, fontSize = 9.sp, modifier = Modifier.padding(end = 10.dp))
                Text(
                    stringResource(m.label), color = if (sel) Tok.accent else Tok.tx, fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
                )
                if (sel) Text("✓", color = Tok.accent, fontSize = 13.5.sp)
            }
        }
        Hairline()
    }

    SectionLabel("${stringResource(Res.string.default_model_section)} · ${agentName(defaultAgent)}")
    val modelDefaultLabel = stringResource(Res.string.value_default)
    val defaultModel = repo.defaultModelFor(defaultAgent)
    val modelOptions = settingsDefaultModelOptions(
        defaultAgent,
        defaultModel,
        repo.agentModels[defaultAgent]?.models.orEmpty(),
    )
    SettingsChoiceRows(
        modelOptions,
        defaultModel,
        label = { id ->
            when {
                id == null -> modelDefaultLabel
                defaultAgent == AgentKind.CLAUDE -> modelAlias(id)
                // A settings row has room for the daemon's exact id. Do not chip-truncate it: unlike the
                // compact composer chip this is where users audit which backend model will really launch.
                else -> id
            }
        },
        monospace = { it != null },
    ) { repo.setDefaultModelFor(defaultAgent, it) }
    Text(
        stringResource(Res.string.settings_default_model_sub, agentName(defaultAgent)),
        color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(top = 10.dp, start = 2.dp),
    )

    SectionLabel("${stringResource(Res.string.default_effort_section)} · ${agentName(defaultAgent)}")
    val effortDefaultLabel = stringResource(Res.string.value_default)
    val defaultEffort = repo.defaultEffortFor(defaultAgent)
    val effortOptions = (listOf<String?>(null) + repo.effortOptions(defaultAgent, repo.defaultModelFor(defaultAgent)))
        .let { opts -> if (defaultEffort != null && defaultEffort !in opts) opts + defaultEffort else opts }
        .distinct()
    // Capability catalogs are daemon-owned and can exceed the handful of values that fit in a segmented
    // control. Use the same full-width radio rows as models so every option keeps a >=44 dp target on a
    // narrow phone and long/custom effort ids remain readable rather than collapsing into tiny columns.
    SettingsChoiceRows(
        effortOptions,
        defaultEffort,
        label = { it ?: effortDefaultLabel },
        monospace = { it != null },
    ) {
        repo.setDefaultEffortFor(defaultAgent, it)
    }
    if (fastModeAvailable(repo, defaultAgent)) {
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            label = stringResource(Res.string.fast_mode),
            sub = stringResource(Res.string.fast_mode_detail),
            checked = repo.defaultServiceTier.value == "priority",
            onChange = { repo.setDefaultServiceTier(if (it) "priority" else null) },
        )
    }

    SectionLabel(stringResource(Res.string.context_window_section))
    val ctxDefaultLabel = stringResource(Res.string.value_default)
    // #171: this control governs the CATCH-ALL only — models holding their own window ignore it. A silent
    // no-op here IS the bug we're replacing (tap 200K, nothing moves, the setting reads broken), so once
    // the user actually touches it we name the models it won't reach instead of staying quiet.
    var catchAllEdited by remember { mutableStateOf(false) }
    SegmentedRow(
        CONTEXT_WINDOW_OPTS, repo.contextWindowOverride.value,
        label = { opt -> when (opt) { null -> ctxDefaultLabel; LARGE_CONTEXT_WINDOW -> "1M"; else -> "${opt / 1000}K" } },
    ) { catchAllEdited = true; repo.setContextWindowOverride(it) }
    ContextWindowCustomRow(repo) { catchAllEdited = true }
    val shadowing = repo.contextWindowOverrides.keys.sorted()
    if (catchAllEdited && shadowing.isNotEmpty()) CatchAllShadowedNote(shadowing)
    else Text(stringResource(Res.string.context_window_hint), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))

    PerModelWindows(repo)

    SectionLabel(stringResource(Res.string.af_show_from))
    AgentFilterPicker(repo)
    Text(stringResource(Res.string.af_hint), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
}

/**
 * "Show projects & sessions from" — a MULTI-select over the agent backends (issue #248).
 *
 * Two things were wrong with the segmented radio this replaces. It could only ever express one agent, so
 * "Claude and Codex, but not the rest" was unaskable; and it packed every option into one fixed-width row,
 * which squeezed the labels until they read "Open…" — and each new backend (Kimi, ZCode) made it worse.
 *
 * So: wrapping chips sized by their own text (no squeeze, no truncation, every chip its own >=44dp target),
 * plus a leading "All" chip. The interaction rules live in [toggleAgentFilter], not here — tapping an agent
 * while All is active narrows to that one, and turning the last one off returns to All, so the control can
 * never reach the empty selection (a list with nothing in it and no way back).
 *
 * Kimi and ZCode appear exactly when the paired computer advertises them, same gate as everywhere else; an
 * agent that isn't offered keeps whatever membership it has, so a stored "all" stays all when the user
 * switches to a computer that DOES run it.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow: the options wrap instead of squeezing (that was the bug)
@Composable
private fun AgentFilterPicker(repo: PocketRepository) {
    val selected = repo.agentFilter.value
    val all = agentFilterIsAll(selected)
    val options = AgentKind.entries.filter {
        it == AgentKind.CLAUDE || it == AgentKind.CODEX || it == AgentKind.OPENCODE || repo.supportsAgent(it)
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentFilterChoice(stringResource(Res.string.af_both), null, all) { repo.clearAgentFilter() }
        options.forEach { agent ->
            // while All is on, the individual chips read UNselected: "All" is the one carrying the state,
            // and lighting every chip too would leave "tap Claude" looking like a no-op
            AgentFilterChoice(agentName(agent), agentColor(agent), !all && agent in selected) {
                repo.setAgentFilter(toggleAgentFilter(selected, agent))
            }
        }
    }
}

/** One chip in [AgentFilterPicker]: dot + label, sized by its own text so nothing ever truncates. */
@Composable
private fun AgentFilterChoice(label: String, dot: Color?, sel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp))
            .background(if (sel) Tok.accent else Tok.surface)
            .border(1.dp, if (sel) Tok.accent else Tok.hair, RoundedCornerShape(999.dp))
            .semantics { selected = sel }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let {
            // the dot inverts to Tok.base on the accent fill so the Claude dot (itself accent) stays visible
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (sel) Tok.base else it))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label, color = if (sel) Tok.base else Tok.tx2, fontSize = 13.sp,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
    }
}

/**
 * The ONE owner of "does the selected agent's default model advertise the `priority` tier Fast rides on".
 *
 * Read by the Fast switch and by the summary above it. Two copies of this predicate is how a summary
 * starts claiming a control the page is not actually showing.
 */
private fun fastModeAvailable(repo: PocketRepository, agent: AgentKind): Boolean =
    agent == AgentKind.CODEX &&
        repo.serviceTierOptions(agent, repo.defaultModelFor(agent)).any { it.id == "priority" }

/**
 * What a new session would launch with, printed once above the controls that own it (#237 · S3).
 *
 * Read-only and unlabeled by design: every value is the same live state the group below binds to, so this
 * container holds no state, no callback and no string of its own — no heading, no framing sentence, no
 * derived text. The rows below stay the editable source of truth; this only answers "what runs next"
 * without scrolling a capability-driven page to find out.
 *
 * Fast appears under exactly [fastModeAvailable], the switch's own gate. Printing it unconditionally would
 * name a control the page is not showing; omitting it under a priority model would let a summary that
 * looks complete hide one of the stored defaults.
 */
@Composable
private fun AgentDefaultsSummary(repo: PocketRepository, agent: AgentKind) {
    val modelDefault = stringResource(Res.string.value_default)
    val storedModel = repo.defaultModelFor(agent)
    val effectivePermissionMode = repo.defaultPermissionMode.value.takeIf { agent == AgentKind.CLAUDE }
    // the mode group's own option list AND its own selection rule, unchanged: Auto only where it is
    // advertised, and key + native mode must BOTH match — so a stored `auto` is never reported for a
    // backend whose rows cannot offer it, and no row reads selected while the summary claims another
    val mode = (MODES + if (agent == AgentKind.CLAUDE && repo.supportsPermissionMode(CLAUDE_PERMISSION_MODE_AUTO)) {
        listOf(AUTO_MODE)
    } else {
        emptyList()
    }).firstOrNull { repo.defaultMode.value == it.key && effectivePermissionMode == it.nativeMode }
    val pairs = buildList {
        add(SummaryPair(stringResource(Res.string.settings_default_agent), agentName(agent), mono = false))
        mode?.let { add(SummaryPair(stringResource(Res.string.default_mode_section), stringResource(it.label), mono = false)) }
        add(
            SummaryPair(
                stringResource(Res.string.default_model_section),
                when {
                    storedModel == null -> modelDefault
                    // the rows' own labelling rule: Claude keeps its alias table, every other backend
                    // keeps the daemon's exact id so what launches stays auditable
                    agent == AgentKind.CLAUDE -> modelAlias(storedModel)
                    else -> storedModel
                },
                mono = storedModel != null,
            ),
        )
        val storedEffort = repo.defaultEffortFor(agent)
        add(SummaryPair(stringResource(Res.string.default_effort_section), storedEffort ?: modelDefault, mono = storedEffort != null))
        if (fastModeAvailable(repo, agent)) {
            add(
                SummaryPair(
                    stringResource(Res.string.fast_mode),
                    stringResource(if (repo.defaultServiceTier.value == "priority") Res.string.value_on else Res.string.value_off),
                    mono = false,
                ),
            )
        }
    }
    // stacking, not truncating: below this width (or once type doubles) a 118 dp label column would leave
    // a long custom model id a sliver to wrap inside, so the value takes the whole line instead. Nothing
    // here may ellipsize, shrink or scroll sideways — the ids are what a user audits.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.3f
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pairs.forEach { pair ->
                // merged, so each pair is announced as one "label, value" instead of two loose strings.
                // No role and no click: this is a readout, not a control, and must not enter the tab order
                // or claim a target budget.
                val cell = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
                if (stacked) {
                    Column(cell, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SummaryLabel(pair.label, Modifier)
                        SummaryValue(pair, Modifier)
                    }
                } else {
                    Row(cell, verticalAlignment = Alignment.Top) {
                        SummaryLabel(pair.label, Modifier.width(118.dp).padding(end = 10.dp))
                        SummaryValue(pair, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One label/value fact in [AgentDefaultsSummary]. [mono] marks the technical values — ids, effort keys. */
private data class SummaryPair(val label: String, val value: String, val mono: Boolean)

@Composable
private fun SummaryLabel(text: String, modifier: Modifier) = Text(
    text, color = Tok.muted, fontSize = 11.sp, lineHeight = 15.sp,
    fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = modifier,
)

/** No `maxLines`, no overflow: a long custom id wraps to as many lines as it needs and stays complete. */
@Composable
private fun SummaryValue(pair: SummaryPair, modifier: Modifier) = Text(
    pair.value, color = Tok.tx, fontSize = if (pair.mono) 12.sp else 13.sp, lineHeight = 18.sp,
    fontFamily = if (pair.mono) FontFamily.Monospace else null, modifier = modifier,
)

// ══ Connections & collaboration ════════════════════════════════════════════════════════════════════

/**
 * The paired computers and every way this app links to another human's machine.
 *
 * Each route keeps its existing full-screen implementation — this page only links to them. Removing a
 * computer changes only this app's local credential; it does not delete the daemon or its sessions.
 */
@Composable
private fun ConnectionsPage(
    repo: PocketRepository,
    onSwitch: (dev.ccpocket.app.pairing.PairedDaemon) -> Unit,
    onAdd: () -> Unit,
    onShares: () -> Unit,
    onJoin: () -> Unit,
    onCollaborators: () -> Unit,
    onReviews: () -> Unit,
    onBridges: () -> Unit,
) {
    SectionLabel(stringResource(Res.string.settings_paired_computers))
    DeviceList(repo = repo, onSwitch = onSwitch, onAdd = onAdd)

    SectionLabel(stringResource(Res.string.settings_sharing_section))
    Hairline()
    FirstHopRow(stringResource(Res.string.settings_shared_folders), onClick = onShares)
    Hairline()
    FirstHopRow(stringResource(Res.string.join_title), onClick = onJoin)
    Hairline()
    FirstHopRow(stringResource(Res.string.co_screen_title), onClick = onCollaborators)
    Hairline()
    FirstHopRow(stringResource(Res.string.rv_settings_row), onClick = onReviews) {
        if (repo.reviewPendingCount > 0) PendingCountMark(repo.reviewPendingCount)
    }
    Hairline()
    FirstHopRow(stringResource(Res.string.settings_bridges), onClick = onBridges)
    Hairline()
}

// ══ Security & approvals ═══════════════════════════════════════════════════════════════════════════

/**
 * How much this phone answers on its own, and what guards the app itself.
 *
 * The approval half appears only once an `ApprovalPrefs` reply proves the daemon can honor it (issue #201) —
 * against an older daemon the frame is dropped, and a switch that silently does nothing would be worse than
 * no switch at all.
 */
@Composable
private fun SecurityPage(repo: PocketRepository) {
    repo.approvalPrefs.value?.let { noAutoDeny ->
        SectionLabel(stringResource(Res.string.approvals_section))
        ToggleRow(
            label = stringResource(Res.string.approval_no_auto_deny),
            sub = stringResource(Res.string.approval_no_auto_deny_sub),
            checked = noAutoDeny,
            onChange = { repo.setAskNoAutoDeny(it) },
        )
        // #220: Full Control存续时长 — default 0 (never expires). A pre-#220 daemon reports 0 and
        // honoring the change is harmless there (it just ignores the field), so it rides the same
        // capability gate as the toggle above.
        val neverLabel = stringResource(Res.string.full_control_expiry_never)
        val expiry = repo.approvalFullControlExpiryMs.value ?: 0L
        SectionLabel(stringResource(Res.string.full_control_expiry))
        SegmentedRow(
            FULL_CONTROL_EXPIRY_OPTS,
            FULL_CONTROL_EXPIRY_OPTS.minByOrNull { kotlin.math.abs(it - expiry) } ?: 0L,
            label = { fullControlExpiryLabel(it, neverLabel) },
        ) { repo.setFullControlExpiryMs(it) }
        Text(stringResource(Res.string.full_control_expiry_sub), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
    }

    // Security (issue #109): the app's own access controls.
    SecurityGroup(repo.appLock)
}

// ══ Support & about ════════════════════════════════════════════════════════════════════════════════

/** Where to get help, what both sides are running, and the way out. */
@Composable
private fun SupportPage(repo: PocketRepository, onHelp: () -> Unit, onExit: () -> Unit) {
    SectionLabel(stringResource(Res.string.settings_help_section))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) {
        ManualLinkRow(
            title = stringResource(Res.string.support_open),
            sub = stringResource(Res.string.support_sub),
            onClick = onHelp,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        ManualLinkRow(
            title = stringResource(Res.string.settings_manual_title),
            sub = stringResource(Res.string.settings_manual_sub),
        ) { openWebUrl(USER_MANUAL_URL) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        ManualLinkRow(
            title = stringResource(Res.string.settings_troubleshooting),
            sub = stringResource(Res.string.settings_troubleshooting_sub),
        ) { openWebUrl(USER_MANUAL_TROUBLESHOOTING_URL) }
    }

    VersionsGroup(repo.versionStatus.value)

    SectionLabel(stringResource(Res.string.about_section))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
        AboutRow(stringResource(Res.string.about_license), "MIT")
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        AboutRow(
            stringResource(Res.string.about_connection),
            repo.paired.value?.displayName() ?: stringResource(Res.string.about_direct_lan),
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onExit).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.exit), color = Tok.danger, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * VERSIONS (issue #200): what this app and the connected computer's daemon are running, and — when either
 * is behind — how to fix that side specifically. The phone does no version check of its own; everything
 * here rides in on `DaemonInfo`, so the section degrades to "unknown" against a daemon that predates it
 * rather than guessing. Update guidance is per side: the daemon gets the exact command for ITS install
 * layout (the daemon computed it — only it can see whether it's brew, scoop or installer-managed), the
 * app gets whatever its own store/download route allows.
 */
@Composable
private fun VersionsGroup(status: VersionStatus) {
    val clipboard = LocalClipboardManager.current
    SectionLabel(stringResource(Res.string.updates_section))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
        VersionRow(
            stringResource(Res.string.updates_app), status.appVersion,
            if (status.appBehind) stringResource(Res.string.updates_outdated, status.newestKnown) else null,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        VersionRow(
            stringResource(Res.string.updates_daemon),
            status.daemonVersion ?: stringResource(Res.string.updates_unknown),
            if (status.daemonBehind) stringResource(Res.string.updates_outdated, status.newestKnown) else null,
        )

        if (status.daemonBehind && status.updateCommand != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    stringResource(Res.string.updates_daemon_howto),
                    color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.base)
                        .border(1.dp, Tok.hair, RoundedCornerShape(8.dp))
                        .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        status.updateCommand, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    // a real 44 dp target: this is the one control that makes the update guidance usable
                    Box(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { clipboard.setText(AnnotatedString(status.updateCommand)) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(Res.string.path_copy), color = Tok.accent, fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        if (status.appBehind) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            AppUpdateGuidance()
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** The app-side half of [VersionsGroup] — one action per distribution route (never a self-updater). */
@Composable
private fun AppUpdateGuidance() {
    when (appUpdateRoute()) {
        AppUpdateRoute.ANDROID_DOWNLOAD ->
            ManualLinkRow(stringResource(Res.string.updates_app_android), RELEASES_URL) { openWebUrl(RELEASES_URL) }
        AppUpdateRoute.IOS_STORE ->
            ManualLinkRow(stringResource(Res.string.updates_app_ios), stringResource(Res.string.updates_app_ios_note)) { openWebUrl(APP_STORE_URL) }
        AppUpdateRoute.DESKTOP_IN_APP -> Text(
            stringResource(Res.string.updates_app_desktop),
            color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Label + mono version, with an accent "vX available" tail when that side is behind. */
@Composable
private fun VersionRow(label: String, version: String, behindNote: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        if (behindNote != null) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Tok.accent))
            Spacer(Modifier.width(6.dp))
            Text(behindNote, color = Tok.accent, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text(version, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 1)
    }
}

@Composable
private fun ManualLinkRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(
                sub, color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("↗", color = Tok.muted, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Security group (issue #109): the "Require Face ID" switch (verify-once-to-enable, design frame B) plus the
 *  auto-lock timing sub-row revealed only when settled ON. Biometric name adapts to the device at runtime. */
@Composable
private fun SecurityGroup(lock: AppLockController) {
    val kindName = biometryName(lock.biometryKind)
    val enableReason = stringResource(Res.string.app_lock_enable_reason)
    var showAutoLock by remember { mutableStateOf(false) }
    val showSub = lock.enabled.value && !lock.enabling.value

    SectionLabel(stringResource(Res.string.security_section))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaceIdGlyph(color = Tok.accent, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(Res.string.app_lock_require, kindName), color = Tok.tx, fontSize = 14.sp)
                Text(stringResource(Res.string.app_lock_require_sub, kindName), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            // ON verifies once before it takes effect; a cancel snaps back OFF (controller.requestEnable)
            Switch(
                checked = lock.enabled.value,
                enabled = !lock.enabling.value && (lock.enabled.value || lock.canUseBiometrics()),
                onCheckedChange = { on -> if (on) lock.requestEnable(enableReason) else lock.disable() },
            )
        }
        if (showSub) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Row(
                Modifier.fillMaxWidth().clickable { showAutoLock = true }.padding(start = 14.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(34.dp)) // align under the title (glyph 22 + gap 12)
                Text(stringResource(Res.string.app_lock_autolock), color = Tok.tx, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(autoLockText(lock.autoLock.value), color = Tok.tx2, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text("›", color = Tok.muted, fontSize = 16.sp)
            }
        }
    }
    if (lock.enabling.value) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceIdGlyph(color = Tok.muted, size = 14.dp)
            Spacer(Modifier.width(7.dp))
            Text(stringResource(Res.string.app_lock_verifying, kindName), color = Tok.muted, fontSize = 11.5.sp)
        }
    }
    if (showAutoLock) AutoLockSheet(lock) { showAutoLock = false }
}

/** Auto-lock timing bottom sheet (design frame C): Immediately (default) or After 1 minute. */
@Composable
private fun AutoLockSheet(lock: AppLockController, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                stringResource(Res.string.app_lock_autolock), color = Tok.muted, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 18.dp, top = 4.dp, bottom = 6.dp),
            )
            AutoLockDelay.entries.forEach { d ->
                val sel = lock.autoLock.value == d
                Row(
                    Modifier.fillMaxWidth().clickable { lock.setAutoLock(d); onDismiss() }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(autoLockText(d), color = if (sel) Tok.tx else Tok.tx2, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (sel) Text("✓", color = Tok.accent, fontSize = 14.sp)
                }
            }
            Text(stringResource(Res.string.app_lock_autolock_hint), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))
        }
    }
}

@Composable
private fun autoLockText(d: AutoLockDelay): String = stringResource(
    when (d) {
        AutoLockDelay.IMMEDIATELY -> Res.string.app_lock_immediately
        AutoLockDelay.AFTER_1_MIN -> Res.string.app_lock_after_1min
    },
)

/** A small uppercase group heading, shared by the settings groups. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = Tok.muted, fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/**
 * Free-form token count for the context-window denominator (issue #159), below the 200K/1M segments.
 *
 * The desktop settings modal has had this field since the override shipped (515282f, 2026-07-06); mobile
 * got only the three segments, while [Res.string.context_window_hint] right below promised exactly the
 * capability the segments couldn't give — "set this when a custom model's real window isn't 200K". A
 * gateway model with a 128K or 256K window had no way in. That gap IS #159.
 *
 * Digits only, capped at 9 (a billion-token window is past any real model, and it keeps the parse total).
 * Blank / 0 clears back to the segments rather than pinning a nonsense denominator.
 */
@Composable
private fun ContextWindowCustomRow(repo: PocketRepository, onEdit: () -> Unit = {}) {
    val current = repo.contextWindowOverride.value
    val isCustom = current != null && current != DEFAULT_CONTEXT_WINDOW && current != LARGE_CONTEXT_WINDOW
    // NOT keyed on the live value: picking a segment must not wipe digits the user is mid-typing
    var draft by remember { mutableStateOf(if (isCustom) current.toString() else "") }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (isCustom) Tok.raised else Tok.surface)
            .border(1.dp, if (isCustom) Tok.accent else Tok.hair, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.context_window_custom), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        // Tok-native input matching the desktop settings field (SettingsModal Box+BasicTextField): a single
        // Tok.hair-bordered well on Tok.base with an accent cursor — no M3 OutlinedTextField (its 56dp min
        // height dwarfed the ~40dp segments above and its focus stroke drew in Material primary, not Tok.accent).
        Box(
            Modifier.width(130.dp).clip(RoundedCornerShape(8.dp)).background(Tok.base)
                .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            if (draft.isEmpty()) Text(
                stringResource(Res.string.context_window_tokens),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
            BasicTextField(
                draft,
                { new ->
                    draft = new.filter(Char::isDigit).take(9)
                    onEdit()
                    repo.setContextWindowOverride(draft.toLongOrNull()?.takeIf { it > 0 })
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Tok.tx),
                cursorBrush = SolidColor(Tok.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Thousands separators for a token count. The per-model surfaces show the exact number the user typed
 *  (they typed "262144", not "262k"), so this deliberately does NOT reuse [formatTokens]'s abbreviations. */
internal fun groupDigits(n: Long): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

/** Dashed hairline: marks the empty per-model table as a placeholder waiting to be filled, rather than a
 *  solid card that looks like a real (but broken) list. */
private fun Modifier.dashedBorder(color: Color, radius: Dp) = this.drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))),
        cornerRadius = CornerRadius(radius.toPx()),
    )
}

/** #171: the catch-all edit the user just made cannot reach models holding their own window — so name them,
 *  right here, the moment it happens. Amber rather than red: nothing broke, the edit simply has a narrower
 *  reach than it looks. Silence here is precisely the old bug (a tap that changes nothing, with no reason given). */
@Composable
private fun CatchAllShadowedNote(shadowing: List<String>) {
    val text =
        if (shadowing.size == 1) stringResource(Res.string.ctx_conflict_one, shadowing[0])
        else stringResource(Res.string.ctx_conflict_many, shadowing[0], shadowing.size - 1)
    Row(
        Modifier.padding(top = 11.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Tok.warn.copy(alpha = 0.09f))
            .border(1.dp, Tok.warn.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Text(text, color = Tok.warn, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

/**
 * #171: the per-model table, made auditable.
 *
 * Settings has no "current model" in scope (it opens with no session running), so this section never offers to
 * WRITE an entry — that belongs in Session Info, beside the very bar being corrected. Here you review and clear.
 *
 * KNOWN GAP vs the design: the handoff also drew a "not run recently" marker on stale rows. The app keeps no
 * per-model last-used record, so rather than invent a signal the rows carry no staleness mark — they stay
 * visible and deletable, which is the requirement that actually mattered. See the handoff README.
 */
@Composable
internal fun PerModelWindows(repo: PocketRepository) {
    Spacer(Modifier.height(22.dp))
    SectionLabel(stringResource(Res.string.per_model_section))
    val entries = repo.contextWindowOverrides.entries.sortedBy { it.key }.map { it.key to it.value }
    if (entries.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).dashedBorder(Tok.hair, 14.dp)
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            Text(stringResource(Res.string.per_model_empty_title), color = Tok.tx2, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(Res.string.per_model_empty_body), color = Tok.muted, fontSize = 12.5.sp,
                lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp),
            )
        }
        return
    }
    // The row for the model running right now is flagged: its override is not hypothetical, it is in force.
    val liveKey = repo.contextWindowKeyOf(repo.model.value)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        entries.forEachIndexed { i, (id, tokens) ->
            if (i > 0) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(Tok.hair))
            PerModelRow(id, tokens, live = id == liveKey) { repo.setContextWindowOverrideFor(id, null) }
        }
    }
    Text(
        stringResource(Res.string.per_model_hint), color = Tok.muted, fontSize = 12.sp,
        lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp),
    )
}

@Composable
private fun PerModelRow(id: String, tokens: Long, live: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                id, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (live) Text(
                stringResource(Res.string.per_model_overrides), color = Tok.warn,
                fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            groupDigits(tokens), color = Tok.tx2, fontFamily = FontFamily.Monospace,
            fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp),
        )
        // Label sits on the CLICKABLE node, not the icon inside it: the tap target and the thing a11y (and
        // the tests) name must be the same node, or you can find the label but not press it.
        val deleteLabel = stringResource(Res.string.per_model_delete)
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                .semantics { contentDescription = deleteLabel }
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DeleteOutline, null, tint = Tok.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Horizontal segmented control: a surface track with equal-width segments; the selected one fills with accent
 * (thumb), the rest stay flush with the track. Shared by the bounded window/expiry pickers.
 *
 * A 44 dp FLOOR and no `maxLines`: at 200% type a segment grows taller and its label wraps whole rather than
 * being cut mid-glyph. A picker you cannot read is a picker you cannot use.
 */
@Composable
private fun <T> SegmentedRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { opt ->
            val sel = selected == opt
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(7.dp))
                    .then(if (sel) Modifier.background(Tok.accent) else Modifier)
                    .semantics { this.selected = sel }
                    .clickable { onPick(opt) }.padding(horizontal = 2.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(opt),
                    color = if (sel) Tok.base else Tok.tx2,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A variable-length settings choice. The page already owns vertical scrolling, so daemon-discovered catalogs
 * stay readable as one full-width row per id instead of being crushed into an equal-width segmented control.
 * Merged semantics make the whole 48 dp row — not just its glyphs — the radio target in UI tests and a11y.
 * Prose choices stay in the system face; only technical ids use monospace.
 */
@Composable
private fun <T> SettingsChoiceRows(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    monospace: (T) -> Boolean = { false },
    onPick: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
    ) {
        options.forEachIndexed { index, opt ->
            if (index > 0) Hairline(Modifier.padding(horizontal = 12.dp))
            val sel = selected == opt
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { this.selected = sel }
                    .clickable(role = Role.RadioButton, onClick = { onPick(opt) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(opt), color = if (sel) Tok.accent else Tok.tx,
                    fontFamily = if (monospace(opt)) FontFamily.Monospace else null,
                    fontSize = if (monospace(opt)) 12.sp else 13.sp, lineHeight = 18.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (sel) Text("✓", color = Tok.accent, fontSize = 13.5.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/**
 * A settings row with a title + subtitle on the left and a Switch on the right.
 *
 * Flush with the page gutter and hairline-bounded rather than card-wrapped (Supporting Surfaces UI 2.0:
 * "prefer flat row groups; reserve filled containers for status/warning blocks"). A 48 dp FLOOR, never a
 * height — the explanatory line is allowed to grow instead of being cropped at large type.
 */
@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, color = Tok.tx, fontSize = 14.sp)
                Text(sub, color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Hairline()
    }
}

/** A label/value row used by the settings + session-info sheets (label left, mono value right). */
@Composable
fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 1)
    }
}
