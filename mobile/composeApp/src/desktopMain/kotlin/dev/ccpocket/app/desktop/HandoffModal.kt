package dev.ccpocket.app.desktop

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.handoff.HandoffBoundaryCard
import dev.ccpocket.app.ui.handoff.HandoffRelayGlyph
import dev.ccpocket.app.ui.handoff.HandoffResultUi
import dev.ccpocket.app.ui.handoff.HandoffSessionFactsCard
import dev.ccpocket.app.ui.handoff.HandoffStatusChip
import dev.ccpocket.app.ui.handoff.HandoffUiStatus
import dev.ccpocket.app.ui.handoff.HoExpiryPills
import dev.ccpocket.app.ui.handoff.HoFieldLabel
import dev.ccpocket.app.ui.handoff.HoRoleSegments
import dev.ccpocket.app.ui.handoff.VerdictChip
import dev.ccpocket.app.ui.handoff.elapsedLabel
import dev.ccpocket.app.ui.handoff.expiresCountdown
import dev.ccpocket.app.ui.handoff.inviteBlob
import dev.ccpocket.app.ui.handoff.shortCode
import dev.ccpocket.app.ui.handoff.toUi
import org.jetbrains.compose.resources.stringResource

/**
 * Frame 11's centered draft dialog: the mobile sheet's information order split two-up —
 * decisions left (session facts, role, recipient, expiry), consequences right (boundary card).
 * The footer restates the cost of the action next to the primary button.
 */
@Composable
fun HandoffModal(model: DesktopModel, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf<dev.ccpocket.protocol.Collaborator?>(null) }
    var query by remember { mutableStateOf("") }
    var dropdownOpen by remember { mutableStateOf(false) }
    var connectNew by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf(24) }
    val defaultRequest = stringResource(Res.string.ho_default_request)
    LaunchedEffect(Unit) { model.listCollaborators() }
    if (connectNew) {
        // Frame 3 as a small dialog: mint a ticket, show the QR, return here once connected
        HandoffConnectModal(model, onBack = {
            connectNew = false
            model.lastCollaboratorConnected?.let { picked = it }
        })
        return
    }
    Column(
        Modifier.width(840.dp).clip(RoundedCornerShape(16.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HandoffRelayGlyph(Tok.accent, 20.dp)
            Text(stringResource(Res.string.ho_sheet_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "✕", color = Tok.muted, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(6.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Column(
                Modifier.width(340.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HandoffSessionFactsCard(
                    model.chatTitle, model.chatWorkdir, model.chatBranch,
                    agentLabel = model.chatAgent?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                )
                Column {
                    HoFieldLabel(stringResource(Res.string.ho_role), stringResource(Res.string.ho_role_hint))
                    Spacer(Modifier.height(8.dp))
                    HoRoleSegments()
                }
                Column {
                    HoFieldLabel(stringResource(Res.string.ho_recipient))
                    Spacer(Modifier.height(8.dp))
                    val p = picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Tok.surface)
                            .border(1.dp, if (dropdownOpen) Tok.accent else Tok.hair, RoundedCornerShape(12.dp))
                            .clickable { dropdownOpen = !dropdownOpen }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (p == null) {
                            BasicTextField(
                                query, { query = it.take(40); dropdownOpen = true },
                                textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp),
                                cursorBrush = SolidColor(Tok.accent), singleLine = true,
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box {
                                        if (query.isEmpty()) Text(stringResource(Res.string.co_search), color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.5.sp)
                                        inner()
                                    }
                                },
                            )
                        } else {
                            dev.ccpocket.app.ui.handoff.ContactChip(p)
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(Res.string.co_change), color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { picked = null; dropdownOpen = true },
                            )
                        }
                    }
                    // type-ahead dropdown (Frame 9a): recent + all + pinned connect-new footer
                    if (dropdownOpen && p == null) {
                        Column(
                            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
                        ) {
                            // recipients, not merely "not removed": a REVIEW contact is a colleague's
                            // daemon the handoff bind refuses (§13.3), so it never enters the dropdown
                            val live = dev.ccpocket.app.ui.handoff.handoffRecipients(model.collaborators, query)
                            val recent = if (query.isBlank()) dev.ccpocket.app.ui.handoff.recentHandoffRecipients(model.collaborators) else emptyList()
                            if (recent.isNotEmpty()) {
                                Text(
                                    stringResource(Res.string.co_recent).uppercase(), color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                                )
                                recent.forEach { c -> dev.ccpocket.app.ui.handoff.CollaboratorRow(c, onTap = { picked = c; dropdownOpen = false }) }
                            }
                            Text(
                                stringResource(Res.string.co_all).uppercase(), color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                            )
                            if (live.isEmpty()) {
                                Text(stringResource(Res.string.co_picker_empty), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            } else {
                                live.forEach { c -> dev.ccpocket.app.ui.handoff.CollaboratorRow(c, onTap = { picked = c; dropdownOpen = false }) }
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { connectNew = true }.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                HandoffRelayGlyph(Tok.tx2)
                                Text(stringResource(Res.string.co_connect_new), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Column {
                    HoFieldLabel(stringResource(Res.string.ho_offer_expires), stringResource(Res.string.ho_offer_expires_hint))
                    Spacer(Modifier.height(8.dp))
                    HoExpiryPills(listOf(1, 4, 24), expiry) { expiry = it }
                }
                model.handoffError?.let { Text(it, color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            Box(Modifier.width(1.dp).heightIn(min = 200.dp).background(Tok.hair))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HandoffBoundaryCard(secondPerson = false, roots = listOf(model.chatWorkdir), ownerLabel = "")
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // the cost of the action, restated next to the primary button — desktop users click faster
            Text(
                stringResource(Res.string.ho_locked_placeholder), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
                style = tightCenter(11.sp),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.cancel), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                style = tightCenter(13.sp),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp),
            )
            val p2 = picked
            Text(
                if (p2 != null) stringResource(Res.string.ho_send_to, p2.label.ifBlank { "?" }) else stringResource(Res.string.ho_create_invite),
                color = Tok.base, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(13.sp),
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (model.handoffCreating || p2 == null) Tok.accent.copy(alpha = 0.4f) else Tok.accent)
                    .clickable(enabled = !model.handoffCreating && p2 != null) {
                        p2?.let { model.handoffCreate(it.label, expiry, defaultRequest, it.deviceId) }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Invite-ready centered card (Frame 3a's desktop form): QR + mono short code + countdown + recap. */
@Composable
fun HandoffInviteModal(model: DesktopModel, onDismiss: () -> Unit) {
    val inv = model.handoffInvite ?: return
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.ho_invite_ready), color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            HandoffStatusChip(HandoffUiStatus.WAITING)
        }
        Box(
            Modifier.padding(top = 16.dp).size(188.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            // same containment as the collaborator QR below (#251) — the sibling dialog, same generator
            SafeQrImage(
                payloadKey = inv.id,
                contentDescription = "handoff QR",
                modifier = Modifier.size(168.dp),
                payload = { inv.inviteBlob() },
            )
        }
        Text(inv.shortCode(), color = Tok.tx, fontFamily = Dk.mono, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.6.sp, modifier = Modifier.padding(top = 14.dp))
        Text(stringResource(Res.string.ho_expires_in, inv.expiresCountdown()), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            // §6: the recap names THIS grant's kind/access, not a hardcoded "Review · Read-only"
            "${inv.recipientLabel ?: "?"} · ${dev.ccpocket.app.ui.handoff.kindChip(inv.kind)} · ${dev.ccpocket.app.ui.handoff.accessChip(inv.access)}",
            color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp, modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.ho_copy_link), color = Tok.base, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = tightCenter(13.sp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                    .clickable { clipboard.setText(AnnotatedString(inv.inviteBlob())) }.padding(vertical = 11.dp),
            )
            Text(
                stringResource(Res.string.ho_close), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = tightCenter(13.sp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss).padding(vertical = 11.dp),
            )
        }
    }
}

/** The recipient's desktop "Finish & return" dialog — verdict chips + return/keep-working. */
@Composable
fun HandoffReturnModal(model: DesktopModel, onDismiss: () -> Unit) {
    val ho = model.activeHandoff ?: return
    var verdict by remember(ho.id) { mutableStateOf<String?>(null) }
    val ownerLabel = ho.initiatorLabel ?: model.activeComputer?.name ?: "?"
    Column(
        Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(20.dp),
    ) {
        Text(stringResource(Res.string.ho_return_title, ownerLabel), color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.ho_return_note, ownerLabel), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        Text(stringResource(Res.string.ho_verdict).uppercase(), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                stringResource(Res.string.ho_verdict_approve),
                stringResource(Res.string.ho_verdict_fixes),
                stringResource(Res.string.ho_verdict_changes),
            ).forEach { v -> VerdictChip(v, selected = v == verdict, large = true) { verdict = v } }
        }
        Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.ho_return_action), color = Tok.base, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = tightCenter(13.sp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Tok.accent)
                    .clickable { model.handoffReturn(verdict); onDismiss() }.padding(vertical = 11.dp),
            )
            Text(
                stringResource(Res.string.ho_keep_working), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = tightCenter(13.sp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss).padding(vertical = 11.dp),
            )
        }
    }
}

/**
 * Desktop "Connect a colleague" dialog (contacts Frame 3 in small-dialog form): mints a one-time
 * ticket, shows the QR + short code + the honest establishment note, and flips to the Connected
 * state when the daemon reports the redeem. Returning from success pre-selects the new contact.
 */
@Composable
fun HandoffConnectModal(model: DesktopModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { model.createCollaboratorTicket() }
    val inv = model.collaboratorTicket
    val connected = model.lastCollaboratorConnected
    Column(
        Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.co_connect_cta), color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("✕", color = Tok.muted, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(6.dp))
        }
        if (connected != null) {
            Spacer(Modifier.height(18.dp))
            dev.ccpocket.app.ui.handoff.HandoffAvatar(connected.label, accent = true, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(connected.label.ifBlank { "?" }, color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "✓ " + stringResource(Res.string.co_connected_chip), color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.5.sp,
                    style = tightCenter(10.5.sp),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.ok.copy(alpha = 0.10f))
                        .border(1.dp, Tok.ok.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(Res.string.co_back_to_handoff), color = Tok.base, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = tightCenter(13.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.accent).clickable(onClick = onBack).padding(vertical = 11.dp),
            )
            return
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(188.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            // #251: encode() + QR rasterization are BOTH contained — a ticket that cannot be drawn
            // leaves a placeholder here and the short code below still connects the colleague.
            inv?.let {
                SafeQrImage(
                    payloadKey = it.ticket,
                    contentDescription = "collaborator QR",
                    modifier = Modifier.size(168.dp),
                    payload = { it.encode() },
                )
            }
        }
        Text(
            inv?.ticket?.let { dev.ccpocket.app.ui.handoff.collabShortCode(it) } ?: "····-····",
            color = Tok.tx, fontFamily = Dk.mono, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.6.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            stringResource(Res.string.co_qr_note), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
        )
        // same words the scanner derives from the QR's daemonPub — enables the read-aloud check
        inv?.let {
            Box(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                dev.ccpocket.app.ui.handoff.FingerprintBlock(dev.ccpocket.protocol.collaboratorFingerprint(it.daemonPub))
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(Tok.tx2))
            Text(stringResource(Res.string.co_waiting_scan), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp)
        }
        model.collaboratorError?.let {
            Text(it, color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * Settings ▸ Collaborators pane (contacts Frame 9b): list + detail in the two-column settings
 * grammar. Selection is a raised row (terracotta stays reserved for needs-you); mutual links are
 * one row with a green "Both ways" chip; the pane never implies presence.
 */
@Composable
fun CollaboratorsPane(model: DesktopModel) {
    LaunchedEffect(Unit) { model.listCollaborators() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var connectNew by remember { mutableStateOf(false) }
    if (connectNew) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HandoffConnectModal(model, onBack = { connectNew = false })
        }
        return
    }
    val contacts = model.collaborators
    val selected = contacts.firstOrNull { it.deviceId == selectedId } ?: contacts.firstOrNull { !it.removed }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.co_screen_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                "${contacts.count { !it.removed }}", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.co_connect_cta), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
                    .clickable { connectNew = true }.padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
        if (contacts.isEmpty()) {
            Text(
                stringResource(Res.string.co_picker_empty), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            return
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            Column(
                Modifier.width(250.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
            ) {
                contacts.forEachIndexed { i, c ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    Box(Modifier.background(if (c.deviceId == selected?.deviceId) Tok.raised else Tok.surface)) {
                        dev.ccpocket.app.ui.handoff.CollaboratorRow(c, onTap = { selectedId = c.deviceId })
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            selected?.let { c ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        dev.ccpocket.app.ui.handoff.HandoffAvatar(c.label, accent = true, size = 40.dp)
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(c.label.ifBlank { "?" }, color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                dev.ccpocket.app.ui.handoff.DirGlyph(c.direction)
                            }
                            Text(
                                stringResource(Res.string.co_connected_ago, dev.ccpocket.app.ui.handoff.connectedAgo(c.connectedAt)),
                                color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    c.fingerprint?.let { fp ->
                        Column {
                            HoFieldLabel(stringResource(Res.string.co_fingerprint))
                            Spacer(Modifier.height(8.dp))
                            dev.ccpocket.app.ui.handoff.FingerprintBlock(fp)
                        }
                    }
                    if (c.direction == dev.ccpocket.protocol.CollaboratorDirection.MUTUAL) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.co_direction).uppercase(), color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, style = tightCenter(10.sp))
                            Text(
                                "✓ " + stringResource(Res.string.co_both_ways), color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.5.sp,
                                style = tightCenter(10.5.sp),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.ok.copy(alpha = 0.10f))
                                    .border(1.dp, Tok.ok.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (!c.removed) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.co_remove_row), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(Res.string.co_remove_sub, c.label.ifBlank { "?" }),
                                    color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            Text(
                                stringResource(Res.string.co_remove), color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                style = tightCenter(12.sp),
                                modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.danger.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                                    .clickable { model.removeCollaborator(c.deviceId) }.padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [HandoffResultUi] built from the wire result — a thin adapter kept here so ChatPane stays lean. */
fun desktopHandoffResultUi(model: DesktopModel): HandoffResultUi? {
    val ho = model.activeHandoff ?: return null
    val r = ho.result ?: return null
    return r.toUi(ho.recipientLabel ?: "?", elapsedLabel(ho.returnedAt))
}
