package dev.ccpocket.app.ui.handoff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
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
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.collaboratorFingerprint
import org.jetbrains.compose.resources.stringResource
import qrgenerator.QRCodeImage

// ════════════════════════════════════════════════════════════════════
//  Frame 1 / 1b — recipient picker page (hosted INSIDE the draft sheet
//  as its second page: back chevron, no new modal; tap = select + pop)
// ════════════════════════════════════════════════════════════════════

@Composable
fun CollaboratorPickerPage(
    contacts: List<Collaborator>,
    onPick: (Collaborator) -> Unit,
    onConnectNew: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // LIFO: the host sheet (PocketSheet) already registered a back handler that dismisses the WHOLE
    // sheet. This page is the sheet's second page, so it registers later and wins — back pops to the
    // draft instead of throwing the half-filled draft away.
    SystemBackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ ", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 4.dp))
            Text(stringResource(Res.string.co_picker_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        // eligibility, not merely "still connected": a REVIEW contact is a colleague's daemon and
        // binding a handoff to it is refused by the daemon (§13.3) — never offer it as a recipient
        val recipients = handoffRecipients(contacts)
        val live = filterCollaborators(recipients, query)
        if (recipients.isEmpty()) {
            // first-run empty: the whole mental model in one sentence, then a single primary
            Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CollaboratorGroupBox {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.People, null, tint = Tok.muted, modifier = Modifier.size(22.dp))
                        Text(stringResource(Res.string.co_picker_empty), color = Tok.muted, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
                Box(
                    Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.accent).clickable(onClick = onConnectNew),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.QrCode2, null, tint = Tok.base, modifier = Modifier.size(16.dp))
                        Text(stringResource(Res.string.co_connect_cta), color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            return
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CollaboratorSearchField(query) { query = it }
            val recent = if (query.isBlank()) recentHandoffRecipients(contacts) else emptyList()
            if (recent.isNotEmpty()) {
                Column {
                    CollaboratorGroupLabel(stringResource(Res.string.co_recent))
                    CollaboratorGroupBox {
                        recent.forEachIndexed { i, c ->
                            if (i > 0) CollaboratorRowDivider()
                            CollaboratorRow(c, onTap = { onPick(c) })
                        }
                    }
                }
            }
            Column {
                CollaboratorGroupLabel(stringResource(Res.string.co_all), live.size)
                CollaboratorGroupBox {
                    live.forEachIndexed { i, c ->
                        if (i > 0) CollaboratorRowDivider()
                        CollaboratorRow(c, onTap = { onPick(c) })
                    }
                }
            }
            ConnectNewRow(onConnectNew)
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frames 3 / 3b — connect a colleague, initiator side (the only QR)
// ════════════════════════════════════════════════════════════════════

@Composable
fun ConnectColleagueScreen(
    invite: CollaboratorInvite?,
    inviteBlob: String?,
    ticketCountdown: String?,
    connected: Collaborator?,
    error: String?,
    fromDraft: Boolean,
    onBackToHandoff: () -> Unit,
    onClose: () -> Unit,
) {
    // full-screen route with no handler of its own = Android back leaves the APP. Registered here in the
    // component (not at a mount point) so both hosts — Settings and the chat's draft detour — are covered,
    // and it does exactly what the ‹ does.
    SystemBackHandler(enabled = true) { onClose() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("‹", color = Tok.tx2, fontSize = 20.sp)
            }
            Text(stringResource(Res.string.co_connect_cta), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (connected != null) {
            // success sub-state: green = the binding SUCCEEDED (a completion, not a presence light)
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp).padding(top = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HandoffAvatar(connected.label, accent = true, size = 56.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(connected.label.ifBlank { "?" }, color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(Tok.ok.copy(alpha = 0.10f))
                            .border(1.dp, Tok.ok.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("✓", color = Tok.ok, fontSize = 10.sp)
                        Text(stringResource(Res.string.co_connected_chip), color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
                    }
                }
                CollaboratorInfoCard(stringResource(Res.string.co_connected_note, connected.label.ifBlank { "?" }))
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromDraft) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Tok.accent).clickable(onClick = onBackToHandoff),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(Res.string.co_back_to_handoff), color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(Res.string.share_done), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
            return
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(168.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                    if (inviteBlob != null) QRCodeImage(url = inviteBlob, contentDescription = "collaborator QR", modifier = Modifier.size(148.dp))
                }
                Text(
                    invite?.ticket?.let { collabShortCode(it) } ?: "····-····",
                    color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.7.sp,
                )
                if (ticketCountdown != null) {
                    Text(stringResource(Res.string.co_ticket_valid, ticketCountdown), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                }
            }
            CollaboratorInfoCard(stringResource(Res.string.co_qr_note), icon = { Icon(Icons.Rounded.GppGood, null, tint = Tok.muted, modifier = Modifier.size(16.dp)) })
            // BOTH sides must hold the SAME words for the read-aloud check (crypto review): the scanner
            // derives these from the QR's daemonPub — show the identical derivation here.
            if (invite != null) {
                Column {
                    HoFieldLabel(stringResource(Res.string.co_fingerprint))
                    Spacer(Modifier.height(8.dp))
                    FingerprintBlock(collaboratorFingerprint(invite.daemonPub))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Tok.tx2)) // neutral: nothing runs, never presence
                Text(stringResource(Res.string.co_waiting_scan), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            if (error != null) Text(error, color = Tok.danger, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.cancel), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "K4TP-9BRX" style display code for a raw ticket string. */
fun collabShortCode(ticket: String): String {
    val cleaned = ticket.filter { it.isLetterOrDigit() }.uppercase().ifEmpty { "--------" }
    val eight = (cleaned + "00000000").take(8)
    return eight.take(4) + "-" + eight.drop(4)
}

@Composable
private fun CollaboratorInfoCard(text: String, icon: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.invoke() ?: Icon(Icons.Rounded.GppGood, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        Text(text, color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frame 4 — confirm connection, recipient side (trust screen)
// ════════════════════════════════════════════════════════════════════

@Composable
fun ConfirmConnectionScreen(
    invite: CollaboratorInvite,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val owner = invite.ownerLabel ?: invite.accountId.take(12)
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
            }
            Text(stringResource(Res.string.co_confirm_title), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                stringResource(Res.string.co_trust_chip), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HandoffSessionFactsCard(
                owner, stringResource(Res.string.co_first_connection), stringResource(Res.string.co_scanned_now), agentLabel = null,
                leading = { HandoffAvatar(owner, accent = true, size = 32.dp) },
            )
            Column {
                HoFieldLabel(stringResource(Res.string.co_fingerprint))
                Spacer(Modifier.height(8.dp))
                FingerprintBlock(collaboratorFingerprint(invite.daemonPub))
                Text(
                    stringResource(Res.string.co_fingerprint_cap, owner), color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            CollaboratorGroupBox {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(Icons.Rounded.ArrowForward, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
                    Column {
                        Text(stringResource(Res.string.co_dir_to_you, owner), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.co_dir_to_you_sub, owner), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                CollaboratorRowDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
                    Column {
                        Text(stringResource(Res.string.co_no_access_now), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.co_no_access_sub), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (confirming) Tok.accent.copy(alpha = 0.4f) else Tok.accent)
                    .clickable(enabled = !confirming, onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.co_confirm_title), color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.cancel), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frames 5 / 5b / 6 — Collaborators management + contact detail
// ════════════════════════════════════════════════════════════════════

@Composable
fun CollaboratorsScreen(
    contacts: List<Collaborator>,
    handoffsWith: @Composable (Collaborator) -> List<HandoffHistoryItemUi>,
    onRemove: (Collaborator) -> Unit,
    onConnectNew: () -> Unit,
    onBack: () -> Unit,
) {
    var detail by remember { mutableStateOf<Collaborator?>(null) }
    // #257: this is a full-screen route off Settings, and the root handler is disabled at that depth —
    // without this, the Android edge gesture left the app. Registered BEFORE the detail early-return so
    // the detail screen's own handler (registered later, hence LIFO-first) pops to the list, and only the
    // second back reaches here and returns to Settings.
    SystemBackHandler(enabled = true) { onBack() }
    // keep the detail view pinned to the live list (a remove flips the row to its terminal state)
    val liveDetail = detail?.let { d -> contacts.firstOrNull { it.deviceId == d.deviceId } ?: d }
    if (liveDetail != null) {
        CollaboratorDetailScreen(liveDetail, handoffsWith(liveDetail), onRemove = { onRemove(liveDetail) }, onBack = { detail = null })
        return
    }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Text("‹", color = Tok.tx2, fontSize = 20.sp) }
            Text(stringResource(Res.string.co_screen_title), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable(onClick = onConnectNew), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.QrCode2, null, tint = Tok.tx2, modifier = Modifier.size(20.dp))
            }
        }
        val connected = contacts.filter { !it.removed }
        val removed = contacts.filter { it.removed }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (connected.isEmpty() && removed.isEmpty()) {
                CollaboratorGroupBox {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.People, null, tint = Tok.muted, modifier = Modifier.size(22.dp))
                        Text(stringResource(Res.string.co_picker_empty), color = Tok.muted, fontSize = 13.sp, lineHeight = 20.sp)
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                                .clickable(onClick = onConnectNew).padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.QrCode2, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
                            Text(stringResource(Res.string.co_connect_cta), color = Tok.tx2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                return@Column
            }
            CollaboratorSearchField(query) { query = it }
            val shown = filterCollaborators(contacts, query)
            if (shown.isNotEmpty()) {
                Column {
                    CollaboratorGroupLabel(stringResource(Res.string.co_connected_group), shown.size)
                    CollaboratorGroupBox {
                        shown.forEachIndexed { i, c ->
                            if (i > 0) CollaboratorRowDivider()
                            CollaboratorRow(c, onTap = { detail = c })
                        }
                    }
                }
            }
            if (removed.isNotEmpty() && query.isBlank()) {
                Column {
                    CollaboratorGroupLabel(stringResource(Res.string.co_removed_group))
                    CollaboratorGroupBox {
                        removed.forEachIndexed { i, c ->
                            if (i > 0) CollaboratorRowDivider()
                            CollaboratorRow(c)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CollaboratorDetailScreen(
    c: Collaborator,
    history: List<HandoffHistoryItemUi>,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    // the contact detail is inner state of [CollaboratorsScreen], so back must pop it to the list first;
    // this handler is registered after that screen's, so LIFO gives it the first refusal.
    SystemBackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Text("‹", color = Tok.tx2, fontSize = 20.sp) }
            Text(stringResource(Res.string.co_screen_title), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HandoffAvatar(c.label, accent = true, size = 48.dp)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(c.label.ifBlank { "?" }, color = Tok.tx, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        DirGlyph(c.direction)
                    }
                    Text(
                        stringResource(Res.string.co_connected_ago, connectedAgo(c.connectedAt)),
                        color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            c.fingerprint?.let { fp ->
                Column {
                    HoFieldLabel(stringResource(Res.string.co_fingerprint))
                    Spacer(Modifier.height(8.dp))
                    FingerprintBlock(fp) {
                        Row(
                            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                                .clickable { }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.GppGood, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
                            Text(stringResource(Res.string.co_reverify), color = Tok.tx2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Column {
                HoFieldLabel(stringResource(Res.string.co_direction))
                Spacer(Modifier.height(8.dp))
                CollaboratorGroupBox {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Icon(Icons.Rounded.ArrowForward, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            val dirText = when (c.direction) {
                                CollaboratorDirection.MUTUAL -> stringResource(Res.string.co_mutual)
                                else -> "${c.label.ifBlank { "?" }} ${directionGlyph(c.direction)}"
                            }
                            Text(dirText, color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (c.direction == CollaboratorDirection.MUTUAL) {
                            Text(
                                "✓ " + stringResource(Res.string.co_both_ways), color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.ok.copy(alpha = 0.10f))
                                    .border(1.dp, Tok.ok.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (c.direction != CollaboratorDirection.MUTUAL) {
                        CollaboratorRowDivider()
                        // disabled-with-a-reason, never hidden: the mono hint says exactly what's missing
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp)
                                .then(if (c.hasDaemon != true) Modifier.clip(RoundedCornerShape(0.dp)) else Modifier),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            HandoffRelayGlyph(if (c.hasDaemon == true) Tok.tx2 else Tok.muted)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(Res.string.co_reverse_link),
                                    color = if (c.hasDaemon == true) Tok.tx else Tok.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                )
                                if (c.hasDaemon != true) {
                                    Text(stringResource(Res.string.co_reverse_needs_daemon), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                            Text("›", color = Tok.muted, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                Column {
                    HoFieldLabel(stringResource(Res.string.co_handoffs_with, c.label.ifBlank { "?" }), history.size.toString())
                    Spacer(Modifier.height(8.dp))
                    CollaboratorGroupBox {
                        history.forEachIndexed { i, h ->
                            if (i > 0) CollaboratorRowDivider()
                            HandoffHistoryRow(h) {}
                        }
                    }
                }
            }
            // the only red on the screen; states its consequence in one line
            CollaboratorGroupBox {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = !c.removed, onClick = onRemove)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = Tok.danger, modifier = Modifier.size(16.dp))
                    Column {
                        Text(stringResource(Res.string.co_remove_row), color = Tok.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(Res.string.co_remove_sub, c.label.ifBlank { "?" }),
                            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frames 7a/7b — the handoff offer card (a doorway, not a trust screen)
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffOfferCard(
    offer: SessionHandoff,
    large: Boolean = false,
    onView: () -> Unit,
    onDecline: () -> Unit,
) {
    val owner = offer.initiatorLabel ?: "?"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HandoffAvatar(owner, accent = true, size = 32.dp)
            Text(
                stringResource(Res.string.co_offer_title, owner), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp, modifier = Modifier.weight(1f),
            )
        }
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // the chips state THIS offer's grant, read off the daemon (§6) — the card used to say
                // "Review · Read-only" whatever the handoff actually was
                Text(
                    kindChip(offer.kind), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    accessChip(offer.access), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(offer.workdir.substringAfterLast('/'), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Schedule, null, tint = Tok.muted, modifier = Modifier.size(12.dp))
                Text(stringResource(Res.string.ho_expires_in, offer.expiresCountdown()), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // View is terracotta: this IS a needs-you moment (the one accent allowed here)
            Row(
                Modifier.weight(if (large) 2f else 1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(Tok.accent).clickable(onClick = onView),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.RemoveRedEye, null, tint = Tok.base, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.co_offer_view), color = Tok.base, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).clickable(onClick = onDecline),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.ho_decline), color = Tok.tx2, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
    }
}
