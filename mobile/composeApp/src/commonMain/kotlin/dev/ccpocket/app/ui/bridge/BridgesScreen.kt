package dev.ccpocket.app.ui.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.share.ShareOutlineButton
import dev.ccpocket.app.ui.share.ShareTopBar
import dev.ccpocket.app.ui.share.TierBadge
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.RUNNER_RESTART
import dev.ccpocket.protocol.RUNNER_START
import dev.ccpocket.protocol.RUNNER_STOP
import org.jetbrains.compose.resources.stringResource

/**
 * The phone-side bridge monitor (issue #91 follow-up). Deliberately NOT a creation surface: minting a
 * managed bridge needs a local script path and an IM app secret, which belong on the desktop. Here the
 * owner does what a phone is FOR — watch the bots that can drive their machine from anywhere, and pull the
 * plug (revoke, or stop/restart a managed adapter) the moment one misbehaves. It closes the loop with the
 * approval pushes that already land on this phone: the same device that gets "feishu-bot needs approval"
 * can now see what feishu-bot is and kill it.
 */
@Composable
fun BridgesScreen(repo: PocketRepository, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    LaunchedEffect(Unit) { if (repo.bridgeControl.value != false) repo.fetchBridges() } // don't fire at a daemon that can't answer
    var revokeTarget by remember { mutableStateOf<BridgeInfo?>(null) }
    var editTarget by remember { mutableStateOf<BridgeInfo?>(null) }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        ShareTopBar(stringResource(Res.string.bridges_title), onBack)
        // the repo surfaces daemon-side refusals AND the merge-loss guard verbatim — on the phone this is
        // the only place they can appear, so it sits above the cards, impossible to scroll past unread
        repo.bridgeError.value?.let { err ->
            Text(
                err, color = Tok.warn, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        repo.bridgeMergeLost.value?.let { lost ->
            Text(
                stringResource(Res.string.bridge_merge_lost, lost.joinToString(", ")),
                color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            // old daemon told us up front it has no bridge control plane (issue #91 capability bit) — show the
            // same "update the daemon" hint immediately, instead of waiting for a bridge fetch to time out
            repo.bridgeControl.value == false || repo.bridgesUnavailable.value ->
                CenteredHint(stringResource(Res.string.bridges_stale))
            repo.bridges.isEmpty() && repo.bridgesLoaded.value -> EmptyBridges()
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repo.bridges.forEach { b -> BridgeCard(b, repo, onRevoke = { revokeTarget = b }, onEdit = { editTarget = b }) }
            }
        }
    }

    revokeTarget?.let { b ->
        RevokeBridgeSheet(name = b.name, onCancel = { revokeTarget = null }) {
            repo.revokeBridge(b.name); revokeTarget = null
        }
    }
    editTarget?.let { b ->
        EditBridgeSheet(
            name = b.name,
            envKeys = b.runner?.envKeys.orEmpty(),
            onCancel = { editTarget = null },
        ) { appId, appSecret, adminId ->
            repo.configureBridgeRunner(
                b.name,
                dev.ccpocket.protocol.BridgeRunnerSpec(
                    scriptPath = "", // blank = keep (built-in stays built-in, a custom path stays put)
                    env = buildMap {
                        if (appId.isNotBlank()) put("FEISHU_APP_ID", appId.trim())
                        if (appSecret.isNotBlank()) put("FEISHU_APP_SECRET", appSecret.trim())
                        if (adminId.isNotBlank()) put("FEISHU_ADMIN_OPEN_ID", adminId.trim())
                    },
                    kind = b.runner?.kind ?: dev.ccpocket.protocol.RUNNER_KIND_FEISHU,
                    autostart = b.runner?.autostart ?: true,
                ),
                mergeEnv = true,
            )
            editTarget = null
        }
    }
}

@Composable
private fun BridgeCard(b: BridgeInfo, repo: PocketRepository, onRevoke: () -> Unit, onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val runner = b.runner
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }.padding(15.dp),
    ) {
        // ── name + revoke ──
        Row(verticalAlignment = Alignment.Top) {
            Text(b.name, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RevokeButton(onRevoke)
        }
        Spacer(Modifier.height(12.dp))

        // ── tier + live count ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TierBadge(b.tier)
            Spacer(Modifier.weight(1f))
            if (b.activeSessions > 0) {
                Text(stringResource(Res.string.share_sessions_live, b.activeSessions), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Spacer(Modifier.height(12.dp))

        // ── status line + managed-runner controls ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (dot, label, color) = when {
                b.pendingTicket -> Triple(Tok.muted, stringResource(Res.string.bridge_waiting_adapter), Tok.tx2)
                b.online -> Triple(Tok.ok, stringResource(Res.string.bridge_online), Tok.ok)
                else -> Triple(Tok.muted, stringResource(Res.string.bridge_offline), Tok.tx2)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, color = color, fontSize = 12.5.sp, fontWeight = if (b.online) FontWeight.SemiBold else FontWeight.Normal)
            Spacer(Modifier.weight(1f))
            // controls exist only for a daemon-MANAGED adapter; a self-run one has nothing to start/stop
            if (runner != null) {
                if (runner.running) {
                    RunnerButton(stringResource(Res.string.bridge_runner_restart)) { repo.controlBridgeRunner(b.name, RUNNER_RESTART) }
                    RunnerButton(stringResource(Res.string.bridge_runner_stop)) { repo.controlBridgeRunner(b.name, RUNNER_STOP) }
                } else {
                    RunnerButton(stringResource(Res.string.bridge_runner_start), accent = true) { repo.controlBridgeRunner(b.name, RUNNER_START) }
                }
                RunnerButton(stringResource(Res.string.bridge_edit)) { onEdit() }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(14.dp))
            // projects (basenames — the phone never shows the owner an absolute machine path)
            DetailLabel(stringResource(Res.string.bridge_projects))
            Text(
                b.workdirs.joinToString("  ·  ") { it.trimEnd('/').substringAfterLast('/') },
                color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            if (runner == null) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.bridge_runner_unmanaged), color = Tok.muted, fontSize = 11.5.sp)
            } else {
                runner.exitCode?.takeIf { !runner.running }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(Res.string.bridge_exited_code, it), color = Tok.warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                runner.lastError?.let {
                    Spacer(Modifier.height(10.dp))
                    DetailLabel(stringResource(Res.string.bridge_last_error))
                    Text(it, color = Tok.warn, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
                if (runner.logTail.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    DetailLabel(stringResource(Res.string.bridge_adapter_log))
                    Box(Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)).background(Tok.base).padding(9.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            // horizontal scroll: one long adapter line must not wrap into an unreadable block
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                Column {
                                    runner.logTail.forEach { line ->
                                        Text(line, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLabel(text: String) =
    Text(text, color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun RunnerButton(text: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = if (accent) Tok.accent else Tok.tx2
    Text(
        text, color = c, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun RevokeButton(onClick: () -> Unit) {
    Text(
        stringResource(Res.string.share_revoke), color = Tok.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.5.dp, Tok.danger.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyBridges() {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SmartToy, null, tint = Tok.muted, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(stringResource(Res.string.bridges_empty), color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.bridges_empty_hint), color = Tok.muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

@Composable
private fun CenteredHint(text: String) =
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tok.muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }

/**
 * Edit a managed bridge's adapter config from the phone — same merge semantics as the desktop form:
 * blank fields KEEP their stored values (the app secret is never echoed back, so retyping everything
 * isn't even possible). Exists because the /bind bootstrap ends here: the bot echoes your open_id in
 * the chat you're already holding your phone for.
 */
@Composable
private fun EditBridgeSheet(
    name: String,
    envKeys: List<String>,
    onCancel: () -> Unit,
    onSave: (appId: String, appSecret: String, adminId: String) -> Unit,
) {
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }
    val dirty = appId.isNotBlank() || appSecret.isNotBlank() || adminId.isNotBlank()

    PocketSheet(onCancel) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 18.dp, top = 4.dp)) {
            Text(stringResource(Res.string.bridge_edit_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(name, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.bridge_edit_hint, envKeys.joinToString(", ").ifEmpty { "—" }),
                color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(14.dp))
            EditField(adminId, { adminId = it }, stringResource(Res.string.bridge_edit_admin_ph))
            Spacer(Modifier.height(8.dp))
            EditField(appId, { appId = it }, stringResource(Res.string.bridge_edit_appid_ph))
            Spacer(Modifier.height(8.dp))
            EditField(appSecret, { appSecret = it }, stringResource(Res.string.bridge_edit_secret_ph), secret = true)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                ShareOutlineButton(stringResource(Res.string.cancel), Modifier.weight(1f), onClick = onCancel)
                Text(
                    stringResource(Res.string.bridge_edit_save),
                    color = if (dirty) Tok.tx else Tok.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1.3f).clip(RoundedCornerShape(14.dp))
                        .background(if (dirty) Tok.accent else Tok.surface)
                        .clickable(enabled = dirty) { onSave(appId, appSecret, adminId) }
                        .padding(vertical = 15.dp),
                )
            }
        }
    }
}

@Composable
private fun EditField(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Tok.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Tok.accent),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RevokeBridgeSheet(name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    PocketSheet(onCancel) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 18.dp, top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Tok.danger.copy(alpha = 0.1f)).border(1.dp, Tok.danger.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("⛊", color = Tok.danger, fontSize = 24.sp) }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.bridge_revoke_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(name, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.bridge_revoke_c1), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center)
                Text(stringResource(Res.string.bridge_revoke_c2), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                ShareOutlineButton(stringResource(Res.string.cancel), Modifier.weight(1f), onClick = onCancel)
                Text(
                    stringResource(Res.string.bridge_revoke_confirm), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1.3f).clip(RoundedCornerShape(14.dp)).background(Tok.danger).clickable(onClick = onConfirm).padding(vertical = 15.dp),
                )
            }
        }
    }
}
