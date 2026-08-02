package dev.ccpocket.app.ui.handoff

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.PocketSheet
import org.jetbrains.compose.resources.stringResource
import qrgenerator.QRCodeImage

// ── shared form bits (design fl / segbox / pills) ──

@Composable
internal fun HoFieldLabel(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.uppercase(), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        if (hint != null) Text(hint, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
    }
}

/** Role two-up card segment — read-only sub-label always reads; Continue present-but-disabled (roadmap). */
@Composable
internal fun HoRoleSegments() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .background(Tok.accent.copy(alpha = 0.10f)).border(1.dp, Tok.accent, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).border(2.dp, Tok.accent, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Tok.accent))
                }
                Text(stringResource(Res.string.ho_role_review), color = Tok.accent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(Res.string.ho_role_review_desc), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Column(
            Modifier.weight(1f).heightIn(min = 56.dp).alpha(0.42f).clip(RoundedCornerShape(12.dp))
                .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).border(2.dp, Tok.hair, androidx.compose.foundation.shape.CircleShape))
                Text(stringResource(Res.string.ho_role_continue), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(Res.string.ho_role_continue_desc), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Expiry pill row (1h / 4h / 24h). Returns via [onPick]; [selected] is the active option's hours. */
@Composable
internal fun HoExpiryPills(options: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { h ->
            val on = h == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) Tok.accent.copy(alpha = 0.10f) else Tok.surface)
                    .border(1.dp, if (on) Tok.accent else Tok.hair, RoundedCornerShape(12.dp))
                    .clickable { onPick(h) },
                contentAlignment = Alignment.Center,
            ) {
                Text("${h}h", color = if (on) Tok.accent else Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Collapsible "Handoff brief" card (design disc) — auto chip + Edit affordance + labelled sections. */
@Composable
fun HandoffBriefCard(
    sections: List<HandoffBriefSectionUi>,
    editable: Boolean,
    startExpanded: Boolean = true,
    onEdit: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { expanded = !expanded }.padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(stringResource(Res.string.ho_brief), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            if (editable) {
                Text(
                    stringResource(Res.string.ho_auto_chip), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (editable && onEdit != null) {
                Row(
                    Modifier.clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Rounded.Edit, null, tint = Tok.accent, modifier = Modifier.size(13.dp))
                    Text(stringResource(Res.string.ho_edit), color = Tok.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Text(if (expanded) "⌃" else "⌄", color = Tok.muted, fontSize = 14.sp)
            }
        }
        if (expanded && sections.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                sections.forEach { s ->
                    Column {
                        Text(s.label.uppercase(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                        if (s.text != null) {
                            Text(s.text, color = Tok.tx2, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        s.items.forEach { item ->
                            Text("•  $item", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frame 2 — Handoff draft sheet (the initiator's trust screen)
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffDraftSheet(
    sessionTitle: String,
    path: String,
    branch: String?,
    agentLabel: String?,
    roots: List<String>,
    briefSections: List<HandoffBriefSectionUi>,
    creating: Boolean,
    error: String?,
    contacts: List<dev.ccpocket.protocol.Collaborator>,
    onConnectNew: () -> Unit,
    onCreate: (recipient: dev.ccpocket.protocol.Collaborator, expiresHours: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<dev.ccpocket.protocol.Collaborator?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf(24) }
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            if (pickerOpen) {
                // Frame 1/1b: pushed as the sheet's second page — back chevron, no new modal
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    CollaboratorPickerPage(
                        contacts,
                        onPick = { picked = it; pickerOpen = false },
                        onConnectNew = onConnectNew,
                        onBack = { pickerOpen = false },
                    )
                }
                return@Column
            }
            Text(stringResource(Res.string.ho_sheet_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HandoffSessionFactsCard(sessionTitle, path, branch, agentLabel)
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
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                            .clickable { pickerOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (p == null) {
                            Text(stringResource(Res.string.co_picker_title), color = Tok.muted, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 2.dp))
                            Text("›", color = Tok.muted, fontSize = 15.sp)
                        } else {
                            ContactChip(p)
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(Res.string.co_change), color = Tok.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Column {
                    // the Grant expires, the link doesn't — hence the hint (design Frame 2)
                    HoFieldLabel(stringResource(Res.string.ho_offer_expires), stringResource(Res.string.ho_offer_expires_hint))
                    Spacer(Modifier.height(8.dp))
                    HoExpiryPills(listOf(1, 4, 24), expiry) { expiry = it }
                }
                HandoffBoundaryCard(secondPerson = false, roots = roots, ownerLabel = "")
                HandoffBriefCard(briefSections, editable = true, startExpanded = true, onEdit = null)
                if (error != null) Text(error, color = Tok.danger, fontSize = 12.5.sp, lineHeight = 18.sp)
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = picked
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (creating || p == null) Tok.accent.copy(alpha = 0.4f) else Tok.accent)
                        .clickable(enabled = !creating && p != null) { p?.let { onCreate(it, expiry) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (p != null) stringResource(Res.string.ho_send_to, p.label.ifBlank { "?" }) else stringResource(Res.string.ho_create_invite),
                        color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.cancel), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frame 3a — invite ready sheet: QR + mono short code + countdown + recap
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffInviteSheet(
    qrBlob: String,
    shortCode: String,
    countdown: String,
    recapLine: String,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.ho_invite_ready), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                HandoffStatusChip(HandoffUiStatus.WAITING)
            }
            Column(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(168.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                    QRCodeImage(url = qrBlob, contentDescription = "handoff QR", modifier = Modifier.size(148.dp))
                }
                Text(shortCode, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.7.sp)
                Text(stringResource(Res.string.ho_expires_in, countdown), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
            }
            Text(
                recapLine, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Tok.accent).clickable(onClick = onShare),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.Share, null, tint = Tok.base, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.ho_share), color = Tok.base, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onCopyLink),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = Tok.tx, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.ho_copy_link), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Frame 8 — Finish & return sheet: structured, editable result draft
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffReturnSheet(
    ownerLabel: String,
    result: HandoffResultUi,
    verdictOptions: List<String>,
    selectedVerdict: String?,
    onPickVerdict: (String) -> Unit,
    returning: Boolean,
    onReturn: () -> Unit,
    onDismiss: () -> Unit,
) {
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.ho_return_title, ownerLabel), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.ho_return_note, ownerLabel), color = Tok.tx2, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 14.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(Tok.raised).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(stringResource(Res.string.ho_return_result), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(Res.string.ho_draft_chip), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text(stringResource(Res.string.ho_verdict).uppercase(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                verdictOptions.forEach { v ->
                                    VerdictChip(v, selected = v == selectedVerdict, large = true) { onPickVerdict(v) }
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                        Column {
                            Text(
                                stringResource(Res.string.ho_findings_n, result.findings.size).uppercase(),
                                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp,
                            )
                            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                result.findings.forEach { FindingRow(it) }
                            }
                        }
                        if (result.verifications.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                            Column {
                                Text(stringResource(Res.string.ho_verified).uppercase(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                                Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    result.verifications.forEach { VerifyRow(it) }
                                }
                            }
                        }
                        if (result.nextSteps.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                            Column {
                                Text(stringResource(Res.string.ho_next_steps).uppercase(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    result.nextSteps.forEach { Text("•  $it", color = Tok.tx2, fontSize = 13.sp, lineHeight = 20.sp) }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (returning) Tok.accent.copy(alpha = 0.4f) else Tok.accent)
                        .clickable(enabled = !returning, onClick = onReturn),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.ho_return_action), color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(Res.string.ho_keep_working), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
