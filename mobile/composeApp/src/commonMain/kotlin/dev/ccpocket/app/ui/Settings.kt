package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.lock.AppLockController
import dev.ccpocket.app.lock.AutoLockDelay
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.share.JoinFolderScreen
import dev.ccpocket.app.ui.share.SharedFoldersScreen
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import org.jetbrains.compose.resources.stringResource

// new-session default effort: the canonical levels (shared with the live /effort picker) + a leading
// null = "model default". Hoisted so it isn't rebuilt on every Settings recomposition.
private val EFFORT_DEFAULT_OPTS: List<String?> = listOf(null) + EFFORT_OPTIONS

// new-session default model: the shared Claude aliases + a leading null = "CLI default". Claude-only —
// a Codex launch never inherits it (see PocketRepository.openSession), so Codex needs no row here.
private val MODEL_DEFAULT_OPTS: List<String?> = listOf(null) + CLAUDE_MODEL_OPTIONS.map { it.second }

// context-window override presets for the usage statusline's denominator (issue #60): null = follow the
// model-derived / daemon-reported window. Covers the two standard windows a custom model id might really have.
private val CONTEXT_WINDOW_OPTS: List<Long?> = listOf(null, DEFAULT_CONTEXT_WINDOW, LARGE_CONTEXT_WINDOW)

// chat text-size presets (issue #8): five stops within PocketRepository.FONT_SCALE_MIN..MAX, rendered as an
// "A"-gradient segmented control so it reads the same in any language.
private val FONT_SCALE_STEPS: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.4f)

/**
 * Settings as a full screen (not a sheet): per-session preferences — notifications, the new-session default
 * mode + reasoning effort, About, and Exit. Paired computers are managed on the disconnected picker
 * (ConnectScreen), reached via Exit — not here. [onBack] returns to the screen that opened it.
 */
@Composable
fun SettingsScreen(repo: PocketRepository, onBack: () -> Unit) {
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
    // back closes Settings — register a handler so it doesn't fall through to the app-level navigation
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Text(stringResource(Res.string.settings_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 20.dp),
        ) {

            // Paired computers are NOT managed here — switching/adding happens on the disconnected picker
            // (ConnectScreen) reached via Exit below. Keeps Settings to per-session preferences.

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .clickable { showUsage = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.settings_usage), color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = Tok.muted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .clickable { showSchedules = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.schedule_tasks_title), color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = Tok.muted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(Res.string.settings_sharing_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                Row(
                    Modifier.fillMaxWidth().clickable { showShares = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.settings_shared_folders), color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("›", color = Tok.muted, fontSize = 16.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                Row(
                    Modifier.fillMaxWidth().clickable { showJoin = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.join_title), color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("›", color = Tok.muted, fontSize = 16.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                Row(
                    Modifier.fillMaxWidth().clickable { showBridges = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.settings_bridges), color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("›", color = Tok.muted, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(Res.string.notifications_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                ToggleRow(
                    label = stringResource(Res.string.notify_on_complete),
                    sub = stringResource(Res.string.notify_on_complete_sub),
                    checked = repo.notificationsOn.value,
                    onChange = { repo.setNotificationsEnabled(it) },
                )
            }

            SectionLabel(stringResource(Res.string.default_mode_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                MODES.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    val sel = repo.defaultMode.value == m.key
                    Row(
                        Modifier.fillMaxWidth().clickable { repo.setDefaultMode(m.key) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("●", color = m.color, fontSize = 9.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(stringResource(m.label), color = if (sel) Tok.accent else Tok.tx, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (sel) Text("✓", color = Tok.accent, fontSize = 13.5.sp)
                    }
                }
            }

            // Security (issue #109): access controls sit right under the permission mode, kept contiguous.
            SecurityGroup(repo.appLock)

            SectionLabel(stringResource(Res.string.default_model_section))
            val modelDefaultLabel = stringResource(Res.string.value_default)
            SegmentedRow(MODEL_DEFAULT_OPTS, repo.defaultModel.value, label = { it ?: modelDefaultLabel }) { repo.setDefaultModel(it) }
            Text(stringResource(Res.string.default_model_hint), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))

            SectionLabel(stringResource(Res.string.default_effort_section))
            val effortDefaultLabel = stringResource(Res.string.value_default)
            SegmentedRow(EFFORT_DEFAULT_OPTS, repo.defaultEffort.value, label = { it ?: effortDefaultLabel }) { repo.setDefaultEffort(it) }

            SectionLabel(stringResource(Res.string.context_window_section))
            val ctxDefaultLabel = stringResource(Res.string.value_default)
            SegmentedRow(
                CONTEXT_WINDOW_OPTS, repo.contextWindowOverride.value,
                label = { opt -> when (opt) { null -> ctxDefaultLabel; LARGE_CONTEXT_WINDOW -> "1M"; else -> "${opt / 1000}K" } },
            ) { repo.setContextWindowOverride(it) }
            Text(stringResource(Res.string.context_window_hint), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))

            SectionLabel(stringResource(Res.string.af_show_from))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val afOpts = listOf(
                    Triple("both", stringResource(Res.string.af_both), null as androidx.compose.ui.graphics.Color?),
                    Triple("claude", stringResource(Res.string.af_claude_only), Tok.accent),
                    Triple("codex", stringResource(Res.string.af_codex_only), Tok.codex),
                    Triple("opencode", stringResource(Res.string.af_opencode_only), Tok.opencode),
                )
                afOpts.forEach { (key, label, dot) ->
                    val sel = repo.agentFilter.value == key
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                            .then(if (sel) Modifier.background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(7.dp)) else Modifier)
                            .clickable { repo.setAgentFilter(key) }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            dot?.let {
                                Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(it))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(label, color = if (sel) Tok.tx else Tok.muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
            Text(stringResource(Res.string.af_hint), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))

            SectionLabel(stringResource(Res.string.appearance_section))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
            ) {
                // System / Light / Dark segmented control — same shape as the text-size one below (#63)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.base)
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
                            Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                                .then(if (sel) Modifier.background(Tok.accent) else Modifier)
                                .clickable { repo.setThemeMode(mode) }.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label, color = if (sel) Tok.base else Tok.tx2, fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    stringResource(Res.string.appearance_hint), color = Tok.muted, fontSize = 12.sp,
                    lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }

            SectionLabel(stringResource(Res.string.text_size_section))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
            ) {
                // "A"-gradient segmented control: each segment shows "A" at a representative size; selected fills accent
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.base)
                        .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FONT_SCALE_STEPS.forEachIndexed { i, s ->
                        val sel = repo.fontScale.value in (s - 0.04f)..(s + 0.04f)
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                                .then(if (sel) Modifier.background(Tok.accent) else Modifier)
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
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(Tok.base)
                        .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(12.dp),
                ) {
                    Text(stringResource(Res.string.text_size_sample), color = Tok.tx, fontSize = 14.sp * repo.fontScale.value)
                }
            }

            SectionLabel(stringResource(Res.string.about_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                AboutRow(stringResource(Res.string.about_version), APP_VERSION)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                AboutRow(stringResource(Res.string.about_license), "MIT")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                AboutRow(
                    stringResource(Res.string.about_connection),
                    repo.paired.value?.displayName() ?: "direct LAN",
                )
            }

            // Exit -> disconnect to the computer picker (ConnectScreen), where paired computers are managed.
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                    .clickable { onBack(); repo.disconnect() }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.exit), color = Tok.danger, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            }
        }
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

/** Horizontal segmented control: a surface track with equal-width segments; the selected one fills
 *  with accent (thumb), the rest stay flush with the track. Shared by the model/effort/window pickers. */
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
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .then(if (sel) Modifier.background(Tok.accent) else Modifier)
                    .clickable { onPick(opt) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(opt),
                    color = if (sel) Tok.base else Tok.tx2,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                )
            }
        }
    }
}

/** A settings row with a title + subtitle on the left and a Switch on the right. */
@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = Tok.tx, fontSize = 14.sp)
            Text(sub, color = Tok.muted, fontSize = 11.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
