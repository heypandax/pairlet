package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.bridge_adapter
import dev.ccpocket.app.resources.bridge_adapter_log
import dev.ccpocket.app.resources.bridge_configured
import dev.ccpocket.app.resources.bridge_edit
import dev.ccpocket.app.resources.bridge_exited
import dev.ccpocket.app.resources.bridge_intro
import dev.ccpocket.app.resources.bridge_last_error
import dev.ccpocket.app.resources.bridge_live_count
import dev.ccpocket.app.resources.bridge_merge_lost
import dev.ccpocket.app.resources.bridge_new
import dev.ccpocket.app.resources.bridge_projects
import dev.ccpocket.app.resources.bridge_runner_restart
import dev.ccpocket.app.resources.bridge_runner_start
import dev.ccpocket.app.resources.bridge_runner_stop
import dev.ccpocket.app.resources.bridge_runner_unmanaged
import dev.ccpocket.app.resources.bridge_tier_asks
import dev.ccpocket.app.resources.bridge_tier_silent
import dev.ccpocket.app.resources.bridge_waiting_adapter
import dev.ccpocket.app.resources.bridges_empty
import dev.ccpocket.app.resources.bridges_stale
import dev.ccpocket.app.resources.bridges_title
import dev.ccpocket.app.resources.share_revoke
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeRunnerSpec
import dev.ccpocket.protocol.RUNNER_RESTART
import dev.ccpocket.protocol.RUNNER_START
import dev.ccpocket.protocol.RUNNER_STOP
import org.jetbrains.compose.resources.stringResource

/**
 * The owner's headless-bridge manager (issue #91 follow-up).
 *
 * Until this existed the whole feature was terminal-only (`pair --headless`, hand-copied JSON, a
 * hand-rolled launchd plist), which is why nobody used it. Here the owner names a bot, ticks the projects
 * it may touch, pastes the IM app credentials once, and the daemon does the rest — it mints, injects the
 * credential into the adapter process it starts, and keeps that process alive.
 *
 * The page's job beyond CRUD is to make two things impossible to miss: WHAT the bot can do without asking
 * (its tier), and WHY it isn't working (the adapter's own log tail).
 */
@Composable
fun BridgesPane(model: DesktopModel) {
    LaunchedEffect(Unit) { model.fetchBridges() }
    var creating by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                stringResource(Res.string.bridges_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            if (model.bridgeBusy) CircularProgressIndicator(Modifier.size(12.dp), color = Tok.accent, strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            if (!creating && model.bridgesLoaded) PillButton(stringResource(Res.string.bridge_new), accent = true) { creating = true }
        }
        Text(
            stringResource(Res.string.bridge_intro),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        model.bridgeError?.let { err ->
            // verbatim: these are the daemon's own words (name taken, workdir missing, adapter wouldn't
            // start) and paraphrasing them would cost the owner the one clue they have
            SelectionContainer {
                Text(
                    err, color = Tok.warn, fontFamily = Dk.ui, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Tok.warn.copy(alpha = 0.10f)).padding(10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        model.bridgeMergeLost?.let { lost ->
            Text(
                stringResource(Res.string.bridge_merge_lost, lost.joinToString(", ")),
                color = Tok.danger, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Tok.danger.copy(alpha = 0.10f)).padding(10.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        model.bridgeCredential?.let { cred ->
            OneShotCredentialCard(cred.name, cred.ttlSec, PrettyJson.of(cred)) { model.clearBridgeCredential() }
            Spacer(Modifier.height(10.dp))
        }

        when {
            creating -> NewBridgeForm(
                onCancel = { creating = false },
                onCreate = { name, dirs, tier, allowedCommands, runner ->
                    model.createBridge(name, dirs, tier, maxSessions = null, runner = runner, allowedCommands = allowedCommands)
                    creating = false
                },
            )
            model.bridgesStale -> Text(
                stringResource(Res.string.bridges_stale),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp,
            )
            !model.bridgesLoaded -> CircularProgressIndicator(Modifier.size(16.dp), color = Tok.accent, strokeWidth = 2.dp)
            model.bridges.isEmpty() -> Text(
                stringResource(Res.string.bridges_empty),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp,
            )
            // the pane container scrolls; a second unbounded scrollable here would crash at measure
            else -> Column { model.bridges.forEach { b -> BridgeRow(b, model) } }
        }
    }
}

@Composable
private fun BridgeRow(b: BridgeInfo, model: DesktopModel) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // a live dot only when the adapter's link is actually up — the owner's "is it working" glance
            Box(
                Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (b.online) Tok.ok else Tok.muted.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.width(9.dp))
            Text(b.name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            TierPill(b.tier)
            if (b.pendingTicket) {
                Spacer(Modifier.width(6.dp))
                Tag(stringResource(Res.string.bridge_waiting_adapter), Tok.muted)
            }
            Spacer(Modifier.weight(1f))
            if (b.activeSessions > 0) {
                Text(
                    stringResource(Res.string.bridge_live_count, b.activeSessions, b.maxSessions), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                )
                Spacer(Modifier.width(10.dp))
            }
            b.runner?.let { r ->
                PillButton(stringResource(if (r.running) Res.string.bridge_runner_stop else Res.string.bridge_runner_start)) {
                    model.controlBridgeRunner(b.name, if (r.running) RUNNER_STOP else RUNNER_START)
                }
                Spacer(Modifier.width(6.dp))
                if (r.running) {
                    PillButton(stringResource(Res.string.bridge_runner_restart)) { model.controlBridgeRunner(b.name, RUNNER_RESTART) }
                    Spacer(Modifier.width(6.dp))
                }
                // the edit path exists chiefly for the /bind bootstrap: the bot echoes your open_id in the
                // chat, and THIS is where it goes (the daemon restarts the adapter with the new config)
                PillButton(stringResource(Res.string.bridge_edit)) { editing = true }
                Spacer(Modifier.width(6.dp))
            }
            PillButton(stringResource(Res.string.share_revoke), danger = true) { model.revokeBridge(b.name) }
        }
        if (editing) {
            Spacer(Modifier.height(10.dp))
            EditRunnerForm(
                envKeys = b.runner?.envKeys.orEmpty(),
                workdirs = b.workdirs,
                allowedCommands = b.allowedCommands,
                ownerBypass = b.runner?.ownerBypass ?: false,
                onCancel = { editing = false },
                onSave = { appId, appSecret, adminId, workdirs, allowedCommands, ownerBypass ->
                    // merge semantics: only what was typed lands; blank fields keep the stored values —
                    // the app secret is never echoed back out, so "retype everything" isn't even possible
                    model.configureBridgeRunner(
                        b.name,
                        dev.ccpocket.protocol.BridgeRunnerSpec(
                            scriptPath = "", // blank = keep (built-in stays built-in, a custom path stays put)
                            env = buildMap {
                                if (appId.isNotBlank()) put("FEISHU_APP_ID", appId.trim())
                                if (appSecret.isNotBlank()) put("FEISHU_APP_SECRET", appSecret.trim())
                                if (adminId.isNotBlank()) put("FEISHU_ADMIN_OPEN_ID", adminId.trim())
                                // overlay the bypass flag only when it changed (merge keeps the rest untouched)
                                if (ownerBypass != (b.runner?.ownerBypass ?: false)) put("FEISHU_OWNER_BYPASS", if (ownerBypass) "1" else "0")
                            },
                            kind = b.runner?.kind ?: dev.ccpocket.protocol.RUNNER_KIND_FEISHU,
                            autostart = b.runner?.autostart ?: true,
                        ),
                        mergeEnv = true,
                        // only send the allow-list when it actually changed — null = daemon leaves it as-is
                        workdirs = workdirs.takeIf { it != b.workdirs },
                        allowedCommands = allowedCommands.takeIf { it != b.allowedCommands },
                    )
                    editing = false
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            b.workdirs.joinToString("  ·  ") { it.substringAfterLast('/') },
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Column {
                    Detail(stringResource(Res.string.bridge_projects), b.workdirs.joinToString("\n"))
                    val r = b.runner
                    if (r == null) {
                        Detail(stringResource(Res.string.bridge_adapter), stringResource(Res.string.bridge_runner_unmanaged))
                    } else {
                        Detail(stringResource(Res.string.bridge_adapter), "${r.scriptPath}${r.pid?.let { "   (pid $it)" } ?: ""}")
                        if (r.envKeys.isNotEmpty()) Detail(stringResource(Res.string.bridge_configured), r.envKeys.joinToString(", "))
                        r.exitCode?.takeIf { !r.running }?.let { Detail(stringResource(Res.string.bridge_exited), "code $it") }
                        r.lastError?.let { Detail(stringResource(Res.string.bridge_last_error), it) }
                        if (r.logTail.isNotEmpty()) {
                            Text(
                                stringResource(Res.string.bridge_adapter_log).uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                            Box(
                                Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(6.dp))
                                    .background(Tok.base).padding(8.dp),
                            ) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    r.logTail.forEach {
                                        Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
}

/** What the bot may do WITHOUT asking — the security-relevant fact, so it gets a colour, not a footnote. */
@Composable
private fun TierPill(tier: AccessTier) = when (tier) {
    AccessTier.REVIEW, AccessTier.UNKNOWN -> Tag(stringResource(Res.string.bridge_tier_asks), Tok.ok)
    AccessTier.COLLABORATE, AccessTier.AUTONOMOUS -> Tag(stringResource(Res.string.bridge_tier_silent), Tok.warn)
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Tok.tx.copy(alpha = 0.85f), fontFamily = Dk.mono, fontSize = 10.sp)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontFamily = Dk.ui, fontSize = 9.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 20.sp)
}

@Composable
private fun PillButton(label: String, accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = when { danger -> Tok.danger; accent -> Tok.accent; else -> Tok.muted }
    Text(
        label, color = c, fontFamily = Dk.ui, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
