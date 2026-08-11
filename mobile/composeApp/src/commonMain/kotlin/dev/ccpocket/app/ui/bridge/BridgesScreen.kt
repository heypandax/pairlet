package dev.ccpocket.app.ui.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.FirstHopHeader
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.connectedToSummary
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.share.ShareOutlineButton
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
        // same chrome as the supporting-surface family it is reached through (Settings ▸ Connections ▸
        // Bridges): back · large title · at most one factual line. The line is the paired computer's own
        // display name — these bots drive THAT machine, and nothing else here is a truth we already hold.
        FirstHopHeader(
            title = stringResource(Res.string.bridges_title),
            summary = connectedToSummary(repo),
            onBack = onBack,
        )
        Spacer(Modifier.height(Metric.gap))
        // the repo surfaces daemon-side refusals AND the merge-loss guard verbatim — on the phone this is
        // the only place they can appear, so it sits above the cards, impossible to scroll past unread
        repo.bridgeError.value?.let { err ->
            Text(
                err, color = Tok.warn, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Metric.gutter, vertical = 8.dp),
            )
        }
        repo.bridgeMergeLost.value?.let { lost ->
            Text(
                stringResource(Res.string.bridge_merge_lost, lost.joinToString(", ")),
                color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Metric.gutter, vertical = 8.dp),
            )
        }
        when {
            // old daemon told us up front it has no bridge control plane (issue #91 capability bit) — show the
            // same "update the daemon" hint immediately, instead of waiting for a bridge fetch to time out
            repo.bridgeControl.value == false || repo.bridgesUnavailable.value ->
                CenteredHint(stringResource(Res.string.bridges_stale))
            repo.bridges.isEmpty() && repo.bridgesLoaded.value -> EmptyBridges()
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metric.gutter).padding(top = 6.dp, bottom = 40.dp),
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

/**
 * One bridge, in zones (UI 2.1 · C2).
 *
 * The defect this shape fixes: identity, status, trust and every runner control used to share ONE
 * horizontal row, so the row's width was divided between facts and buttons. On a 390 dp Chinese screen
 * that left `编辑` about one glyph wide and Compose stacked it as 编 over 辑 — a button rendered
 * vertically. The cure is structural, not a wider literal: facts and actions no longer size each other.
 * Identity, then facts, then a hairline, then an action [FlowRow] whose members are whole controls with a
 * floor of their own. When the floors no longer fit, a control WRAPS to the next line intact; nothing is
 * ever squeezed, and no label may break.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow scope: the facts line wraps instead of squeezing
@Composable
private fun BridgeCard(b: BridgeInfo, repo: PocketRepository, onRevoke: () -> Unit, onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val runner = b.runner
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
            // the card itself is the expand affordance, as before — but it now SAYS so: an unlabelled
            // full-card tap target reads out as nothing at all to a screen reader
            .clickable(onClickLabel = stringResource(Res.string.bridge_expand_toggle)) { expanded = !expanded }
            .padding(14.dp),
    ) {
        // ── zone A · identity: the name, once, and what this bridge is allowed to do ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(b.name, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TierBadge(b.tier)
        }

        // ── zone B · facts: link state, then the two optional truths beside it. A FlowRow because these
        // are informational and may wrap; they must never compete with an action for width ──
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // link state is the ADAPTER's link, and only that: a managed runner that is up says nothing
            // about whether the adapter ever connected, so runner.running must not reach this decision
            val (dot, label, color) = when {
                b.pendingTicket -> Triple(Tok.muted, stringResource(Res.string.bridge_waiting_adapter), Tok.tx2)
                b.online -> Triple(Tok.ok, stringResource(Res.string.bridge_online), Tok.ok)
                else -> Triple(Tok.muted, stringResource(Res.string.bridge_offline), Tok.tx2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Text(label, color = color, fontSize = 12.5.sp, fontWeight = if (b.online) FontWeight.SemiBold else FontWeight.Normal)
            }
            // issue #198: THIS is the device whose approval cards stop arriving once the owner allows
            // no-approval chats, so it has to say so out loud — silence would read as "nothing is happening"
            if (runner?.noApproval == true) {
                Text(stringResource(Res.string.bridge_no_approval_tag), color = Tok.warn, fontSize = 11.sp)
            }
            if (b.activeSessions > 0) {
                Text(stringResource(Res.string.share_sessions_live, b.activeSessions), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        Hairline(Modifier.padding(vertical = 12.dp))

        // ── zone D · actions: whole controls that wrap, never shrink ──
        BridgeActions(b, repo, onRevoke = onRevoke, onEdit = onEdit)

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

/**
 * What this bridge can be told to do, as controls that wrap rather than compress.
 *
 * Start/Stop/Restart/Edit exist only for a daemon-MANAGED adapter — a self-run one has no process to
 * control, and offering the buttons anyway would be a lie the tap could not fulfil. Revoke is on every
 * card: pulling the plug is the one thing a phone must always be able to do.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRowScope.weight: whole controls share a row, then wrap
@Composable
private fun BridgeActions(b: BridgeInfo, repo: PocketRepository, onRevoke: () -> Unit, onEdit: () -> Unit) {
    val runner = b.runner
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (runner != null) {
            if (runner.running) {
                BridgeAction(stringResource(Res.string.bridge_runner_restart)) { repo.controlBridgeRunner(b.name, RUNNER_RESTART) }
                BridgeAction(stringResource(Res.string.bridge_runner_stop)) { repo.controlBridgeRunner(b.name, RUNNER_STOP) }
            } else {
                BridgeAction(stringResource(Res.string.bridge_runner_start), tone = Tok.accent) { repo.controlBridgeRunner(b.name, RUNNER_START) }
            }
            BridgeAction(stringResource(Res.string.bridge_edit), onClick = onEdit)
        }
        // destructive, and deliberately NOT the primary: danger ink on an outline, never a filled button,
        // and `stretch = false` so a Revoke that wraps onto a row of its own keeps its floor instead of
        // spanning the card as the largest control on it
        BridgeAction(stringResource(Res.string.share_revoke), tone = Tok.danger, stretch = false, onClick = onRevoke)
    }
}

/**
 * One bridge action: a whole control with a floor on BOTH axes and a label that may not break.
 *
 * `softWrap = false` is the load-bearing half — without it Compose answers a too-narrow slot by wrapping
 * the text, which for a two-glyph Chinese label means one glyph per line. With it, the label stays one
 * horizontal line and the pressure moves where it belongs: to [FlowRow], which wraps this whole control
 * onto the next row instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.BridgeAction(
    text: String,
    tone: Color = Tok.tx2,
    stretch: Boolean = true,
    onClick: () -> Unit,
) {
    // 200% type needs a bigger floor, not smaller text: a 96 dp control is where "重启" starts to break
    // once every glyph doubles, so past 1.5× the floor grows with it and the row wraps sooner.
    val big = LocalDensity.current.fontScale >= 1.5f
    val minWidth = if (big) 150.dp else 96.dp
    val minHeight = if (big) 60.dp else 48.dp
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.weight(1f, fill = stretch).widthIn(min = minWidth).heightIn(min = minHeight)
            .clip(shape).border(1.dp, tone.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tone, style = TypeRole.action, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
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
