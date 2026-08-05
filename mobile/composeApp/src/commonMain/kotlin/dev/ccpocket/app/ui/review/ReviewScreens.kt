package dev.ccpocket.app.ui.review

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rv_ack
import dev.ccpocket.app.resources.rv_artifacts
import dev.ccpocket.app.resources.rv_brief
import dev.ccpocket.app.resources.rv_brief_background
import dev.ccpocket.app.resources.rv_brief_constraints
import dev.ccpocket.app.resources.rv_brief_dod
import dev.ccpocket.app.resources.rv_brief_done
import dev.ccpocket.app.resources.rv_brief_focus
import dev.ccpocket.app.resources.rv_brief_risks
import dev.ccpocket.app.resources.rv_brief_verified
import dev.ccpocket.app.resources.rv_cancel_request
import dev.ccpocket.app.resources.rv_close_request
import dev.ccpocket.app.resources.rv_invite_title
import dev.ccpocket.app.resources.rv_join_title
import dev.ccpocket.app.resources.rv_copied
import dev.ccpocket.app.resources.rv_decline
import dev.ccpocket.app.resources.rv_decline_reason
import dev.ccpocket.app.resources.rv_dir_inbound
import dev.ccpocket.app.resources.rv_empty_contacts
import dev.ccpocket.app.resources.rv_empty_contacts_sub
import dev.ccpocket.app.resources.rv_empty_inbox
import dev.ccpocket.app.resources.rv_empty_inbox_sub
import dev.ccpocket.app.resources.rv_empty_sent
import dev.ccpocket.app.resources.rv_empty_sent_sub
import dev.ccpocket.app.resources.rv_findings
import dev.ccpocket.app.resources.rv_from
import dev.ccpocket.app.resources.rv_history
import dev.ccpocket.app.resources.rv_loading
import dev.ccpocket.app.resources.rv_new
import dev.ccpocket.app.resources.rv_next_steps
import dev.ccpocket.app.resources.rv_offline
import dev.ccpocket.app.resources.rv_open_questions
import dev.ccpocket.app.resources.rv_pending_actions
import dev.ccpocket.app.resources.rv_pending_section
import dev.ccpocket.app.resources.rv_prepare
import dev.ccpocket.app.resources.rv_prepare_copy
import dev.ccpocket.app.resources.rv_prepare_sub
import dev.ccpocket.app.resources.rv_purpose_legacy
import dev.ccpocket.app.resources.rv_queued_note
import dev.ccpocket.app.resources.rv_remove_contact
import dev.ccpocket.app.resources.rv_remove_contact_sub
import dev.ccpocket.app.resources.rv_respond
import dev.ccpocket.app.resources.rv_result
import dev.ccpocket.app.resources.rv_start
import dev.ccpocket.app.resources.rv_summary
import dev.ccpocket.app.resources.rv_tab_contacts
import dev.ccpocket.app.resources.rv_tab_inbox
import dev.ccpocket.app.resources.rv_tab_sent
import dev.ccpocket.app.resources.rv_title
import dev.ccpocket.app.resources.rv_to
import dev.ccpocket.app.resources.rv_unsupported
import dev.ccpocket.app.resources.rv_verdict
import dev.ccpocket.app.resources.rv_verification
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.handoff.FingerprintBlock
import dev.ccpocket.app.ui.share.ShareOutlineButton
import dev.ccpocket.app.ui.share.SharePrimaryButton
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewExecutionBundle
import dev.ccpocket.protocol.ReviewInboxItem
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.isTerminal
import org.jetbrains.compose.resources.stringResource

// ===========================================================================
//  The Review Center's PURE screens (REVIEW-REQUEST.md §12), in the
//  ui/handoff/*Screens.kt idiom: parameters and lambdas, no PocketRepository.
//  ReviewFlows.kt binds them to a repository; a test drives them directly.
//
//  The product line these screens have to keep honest: the daemon is the
//  engine, this is a window onto it. So nothing here derives a status, retries
//  anything, or turns a queued action into a delivered one.
// ===========================================================================

/** Everything the centre renders, as one snapshot — so a caller can't hand it a half-consistent view. */
data class ReviewCenterState(
    val sent: List<ReviewRequest> = emptyList(),
    val received: List<ReviewInboxItem> = emptyList(),
    val contacts: List<ReviewContact> = emptyList(),
    val loading: Boolean = false,
    val unsupported: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
)

enum class ReviewTab { INBOX, SENT, CONTACTS }

/**
 * The three-destination centre. Pending count rides the Inbox tab because that is the only number that
 * means "somebody is waiting on you"; Sent and Contacts are reference, not work.
 */
@Composable
fun ReviewCenterScreen(
    state: ReviewCenterState,
    tab: ReviewTab,
    onTab: (ReviewTab) -> Unit,
    pendingCount: Int,
    onOpenReceived: (ReviewInboxItem) -> Unit,
    onOpenSent: (ReviewRequest) -> Unit,
    onNewReview: () -> Unit,
    /** Mint an invite for a colleague's daemon to redeem. */
    onInvite: () -> Unit,
    /** Redeem a colleague's invite on THIS machine's daemon. */
    onJoin: () -> Unit,
    onRemoveContact: (ReviewContact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Tok.base)) {
        ReviewTabs(tab, pendingCount, onTab)
        ReviewDivider()
        state.error?.let { ReviewErrorRow(it) }
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            when {
                // the three unhappy states read differently on purpose — "you have none", "this daemon
                // can't do it yet" and "we aren't talking to it" are three different next actions
                state.offline -> ReviewStatePane(stringResource(Res.string.rv_offline))
                state.unsupported -> ReviewStatePane(stringResource(Res.string.rv_unsupported), tint = Tok.warn)
                state.loading -> ReviewStatePane(stringResource(Res.string.rv_loading), tint = Tok.muted)
                else -> when (tab) {
                    ReviewTab.INBOX -> InboxTab(state.received, onOpenReceived)
                    ReviewTab.SENT -> SentTab(state.sent, onOpenSent, onNewReview)
                    ReviewTab.CONTACTS -> ContactsTab(state.contacts, onInvite, onJoin, onRemoveContact)
                }
            }
        }
    }
}

@Composable
private fun ReviewTabs(tab: ReviewTab, pending: Int, onTab: (ReviewTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TabChip(stringResource(Res.string.rv_tab_inbox), tab == ReviewTab.INBOX, pending) { onTab(ReviewTab.INBOX) }
        TabChip(stringResource(Res.string.rv_tab_sent), tab == ReviewTab.SENT, 0) { onTab(ReviewTab.SENT) }
        TabChip(stringResource(Res.string.rv_tab_contacts), tab == ReviewTab.CONTACTS, 0) { onTab(ReviewTab.CONTACTS) }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(if (selected) Tok.raised else Tok.base)
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text, color = if (selected) Tok.tx else Tok.tx2, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (badge > 0) {
            Text(
                "$badge", color = Tok.base, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Tok.accent).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ReviewErrorRow(message: String) {
    Text(
        message, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().background(Tok.danger.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

// ---- inbox -----------------------------------------------------------------

@Composable
private fun InboxTab(items: List<ReviewInboxItem>, onOpen: (ReviewInboxItem) -> Unit) {
    if (items.isEmpty()) {
        ReviewStatePane(stringResource(Res.string.rv_empty_inbox), stringResource(Res.string.rv_empty_inbox_sub))
        return
    }
    // pending first, history after: an answered or terminal request is a record, not a task
    val pending = items.filter { !it.request.status.isTerminal && it.request.status != ReviewStatus.RESPONDED }
    val history = items - pending.toSet()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (pending.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ReviewSectionLabel(stringResource(Res.string.rv_pending_section))
                pending.forEach { ReceivedRow(it) { onOpen(it) } }
            }
        }
        if (history.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ReviewSectionLabel(stringResource(Res.string.rv_history))
                history.forEach { ReceivedRow(it) { onOpen(it) } }
            }
        }
    }
}

@Composable
private fun ReceivedRow(item: ReviewInboxItem, onClick: () -> Unit) {
    ReviewCard {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.request.title.ifBlank { item.request.brief.request }, color = Tok.tx, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ReviewStatusChip(item.request.status)
            }
            Text(stringResource(Res.string.rv_from, item.peerLabel), color = Tok.muted, fontSize = 11.5.sp)
            // pending actions are stated, never smoothed over: "queued" is not "they saw it" (§8)
            if (item.pending.isNotEmpty()) {
                Text(stringResource(Res.string.rv_pending_actions, item.pending.joinToString(", ")), color = Tok.info, fontSize = 11.sp)
            }
        }
    }
}

// ---- sent ------------------------------------------------------------------

@Composable
private fun SentTab(items: List<ReviewRequest>, onOpen: (ReviewRequest) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SharePrimaryButton(stringResource(Res.string.rv_new), onClick = onNew)
        if (items.isEmpty()) {
            ReviewStatePane(stringResource(Res.string.rv_empty_sent), stringResource(Res.string.rv_empty_sent_sub))
        } else {
            items.forEach { r ->
                ReviewCard {
                    Column(Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.title.ifBlank { r.brief.request }, color = Tok.tx, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            ReviewStatusChip(r.status)
                        }
                        Text(stringResource(Res.string.rv_to, r.recipientLabel ?: r.recipientDeviceId), color = Tok.muted, fontSize = 11.5.sp)
                        if (r.status == ReviewStatus.QUEUED) {
                            Text(stringResource(Res.string.rv_queued_note), color = Tok.muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---- contacts ---------------------------------------------------------------

@Composable
private fun ContactsTab(
    contacts: List<ReviewContact>,
    onInvite: () -> Unit,
    onJoin: () -> Unit,
    onRemove: (ReviewContact) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // both halves of establishing a link, side by side: whoever moves first mints, the other redeems.
        // Offering only one of them is how a user ends up unable to finish a connection they started.
        SharePrimaryButton(stringResource(Res.string.rv_invite_title), onClick = onInvite)
        ShareOutlineButton(stringResource(Res.string.rv_join_title), Modifier.fillMaxWidth(), onClick = onJoin)
        if (contacts.isEmpty()) {
            ReviewStatePane(stringResource(Res.string.rv_empty_contacts), stringResource(Res.string.rv_empty_contacts_sub))
            return@Column
        }
        contacts.forEach { c -> ContactCard(c) { onRemove(c) } }
    }
}

@Composable
private fun ContactCard(c: ReviewContact, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ReviewCard {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.label, color = if (c.removed) Tok.muted else Tok.tx, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                )
                // a legacy Session Handoff contact is usable for reviews but is NOT a review daemon peer;
                // saying so is what keeps the two kinds of link distinguishable (§13.3)
                if (isLegacyContact(c)) {
                    Text(
                        stringResource(Res.string.rv_purpose_legacy), color = Tok.muted, fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.raised).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Text(stringResource(contactDirectionRes(c.direction)), color = Tok.tx2, fontSize = 11.5.sp)
            c.fingerprint?.takeIf { it.isNotBlank() }?.let { FingerprintBlock(it) }
            if (!c.removed) {
                if (!confirming) {
                    ShareOutlineButton(
                        stringResource(Res.string.rv_remove_contact), Modifier.fillMaxWidth(), color = Tok.danger,
                    ) { confirming = true }
                } else {
                    Text(stringResource(Res.string.rv_remove_contact_sub), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                    ShareOutlineButton(
                        stringResource(Res.string.rv_remove_contact), Modifier.fillMaxWidth(), color = Tok.danger,
                        onClick = onRemove,
                    )
                }
            }
        }
    }
}

// ---- received detail ---------------------------------------------------------

/**
 * One received request. Order matters: WHO sent it and the fingerprint they verified come first, the
 * untrusted notice comes before a single word of their text, and "Use my agent" is described by what it
 * does NOT do — because the one thing this screen must never imply is that it will act on the material.
 */
@Composable
fun ReceivedDetailScreen(
    item: ReviewInboxItem,
    bundle: ReviewExecutionBundle?,
    acting: Boolean,
    preparing: Boolean,
    copied: Boolean,
    error: String?,
    onPrepare: () -> Unit,
    onCopyPrompt: (String) -> Unit,
    onAcknowledge: () -> Unit,
    onStart: () -> Unit,
    onDecline: (String?) -> Unit,
    onRespond: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var declining by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val r = item.request
    Column(
        modifier.fillMaxSize().background(Tok.base).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.rv_from, item.peerLabel), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ReviewStatusChip(r.status)
        }
        if (item.peerFingerprint.isNotBlank()) FingerprintBlock(item.peerFingerprint)
        error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp) }
        if (item.pending.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(Res.string.rv_pending_actions, item.pending.joinToString(", ")), color = Tok.info, fontSize = 12.sp)
                Text(stringResource(Res.string.rv_queued_note), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        }

        UntrustedNotice()

        if (r.artifacts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewSectionLabel(stringResource(Res.string.rv_artifacts))
                r.artifacts.forEach { ArtifactRow(it, onOpen = onOpenUrl) }
            }
        }
        BriefBlock(r.title, r.brief)

        // "Use my agent" PREPARES. It never launches one: the reviewer's agent runs under their own
        // approval policy in their own session, and starting it from here would take that away (§3.2).
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ReviewSectionLabel(stringResource(Res.string.rv_prepare))
            Text(stringResource(Res.string.rv_prepare_sub), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
            ShareOutlineButton(stringResource(Res.string.rv_prepare), Modifier.fillMaxWidth(), onClick = onPrepare)
            if (preparing) Text(stringResource(Res.string.rv_loading), color = Tok.muted, fontSize = 11.5.sp)
            bundle?.let { b ->
                Text(
                    b.recommendedPrompt, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(11.dp),
                )
                b.notes.forEach { Text("· $it", color = Tok.muted, fontSize = 11.sp, lineHeight = 16.sp) }
                ShareOutlineButton(
                    stringResource(if (copied) Res.string.rv_copied else Res.string.rv_prepare_copy),
                    Modifier.fillMaxWidth(),
                ) { onCopyPrompt(b.recommendedPrompt) }
            }
        }

        if (!r.status.isTerminal && r.status != ReviewStatus.UNKNOWN) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (r.status == ReviewStatus.DELIVERED) {
                    ShareOutlineButton(stringResource(Res.string.rv_ack), Modifier.fillMaxWidth(), onClick = onAcknowledge)
                }
                if (r.status != ReviewStatus.IN_PROGRESS && r.status != ReviewStatus.RESPONDED) {
                    ShareOutlineButton(stringResource(Res.string.rv_start), Modifier.fillMaxWidth(), onClick = onStart)
                }
                if (r.status != ReviewStatus.RESPONDED) {
                    SharePrimaryButton(stringResource(Res.string.rv_respond), enabled = !acting, onClick = onRespond)
                    if (!declining) {
                        ShareOutlineButton(stringResource(Res.string.rv_decline), Modifier.fillMaxWidth(), color = Tok.danger) { declining = true }
                    } else {
                        ReviewField(stringResource(Res.string.rv_decline_reason), reason) { reason = it }
                        ShareOutlineButton(stringResource(Res.string.rv_decline), Modifier.fillMaxWidth(), color = Tok.danger) {
                            onDecline(reason.trim().takeIf { it.isNotEmpty() })
                        }
                    }
                }
            }
        }
        r.result?.let { ResultBlock(it) }
    }
}

// ---- sent detail --------------------------------------------------------------

@Composable
fun SentDetailScreen(
    r: ReviewRequest,
    error: String?,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(Tok.base).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.rv_to, r.recipientLabel ?: r.recipientDeviceId), color = Tok.tx,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            ReviewStatusChip(r.status)
        }
        if (r.status == ReviewStatus.QUEUED) {
            Text(stringResource(Res.string.rv_queued_note), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp) }

        if (r.artifacts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewSectionLabel(stringResource(Res.string.rv_artifacts))
                r.artifacts.forEach { ArtifactRow(it, onOpen = onOpenUrl) }
            }
        }
        BriefBlock(r.title, r.brief)
        r.result?.let { ResultBlock(it) }
        r.declineReason?.let { Text(it, color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp) }

        // the UI hides what is obviously impossible, but the DAEMON decides: a cancel it refuses still
        // surfaces its own error rather than being silently swallowed here
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (r.status == ReviewStatus.QUEUED || r.status == ReviewStatus.DELIVERED || r.status == ReviewStatus.ACKNOWLEDGED) {
                ShareOutlineButton(stringResource(Res.string.rv_cancel_request), Modifier.fillMaxWidth(), color = Tok.danger, onClick = onCancel)
            }
            if (r.status == ReviewStatus.RESPONDED) {
                SharePrimaryButton(stringResource(Res.string.rv_close_request), onClick = onClose)
            }
        }
    }
}

// ---- shared blocks --------------------------------------------------------------

@Composable
fun BriefBlock(title: String, brief: ReviewBrief) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReviewSectionLabel(stringResource(Res.string.rv_brief))
        if (title.isNotBlank()) Text(title, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        if (brief.request.isNotBlank()) Text(brief.request, color = Tok.tx, fontSize = 13.sp, lineHeight = 19.sp)
        brief.background?.takeIf { it.isNotBlank() }?.let {
            BriefSection(stringResource(Res.string.rv_brief_background), listOf(it))
        }
        BriefSection(stringResource(Res.string.rv_brief_focus), brief.focusAreas)
        BriefSection(stringResource(Res.string.rv_brief_risks), brief.knownRisks)
        BriefSection(stringResource(Res.string.rv_brief_done), brief.completedWork)
        BriefSection(stringResource(Res.string.rv_brief_verified), brief.verification)
        BriefSection(stringResource(Res.string.rv_brief_constraints), brief.constraints)
        BriefSection(stringResource(Res.string.rv_brief_dod), brief.definitionOfDone)
    }
}

@Composable
fun ResultBlock(result: ReviewResult) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ReviewSectionLabel(stringResource(Res.string.rv_result))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(Res.string.rv_verdict), color = Tok.muted, fontSize = 11.sp)
            val c = reviewVerdictColor(result.verdict)
            Text(
                stringResource(reviewVerdictRes(result.verdict)), color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        if (result.summary.isNotBlank()) Text(result.summary, color = Tok.tx, fontSize = 13.sp, lineHeight = 19.sp)
        if (result.findings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReviewSectionLabel(stringResource(Res.string.rv_findings))
                result.findings.forEach { f ->
                    ReviewCard {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.severity.uppercase(), color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(f.title, color = Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            f.detail?.let { Text(it, color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.sp) }
                            f.file?.let {
                                Text(
                                    it + (f.line?.let { l -> ":$l" } ?: ""), color = Tok.muted,
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        BriefSection(stringResource(Res.string.rv_verification), result.verification)
        BriefSection(stringResource(Res.string.rv_open_questions), result.openQuestions)
        BriefSection(stringResource(Res.string.rv_next_steps), result.recommendedNextSteps)
    }
}

/** A plain single-line field. Local to this package so the review forms don't drag in a settings-only
 *  helper and inherit its layout assumptions. */
@Composable
fun ReviewField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    placeholder: String? = null,
    onValue: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            textStyle = androidx.compose.ui.text.TextStyle(color = Tok.tx, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Tok.accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 9.dp)
                .then(if (singleLine) Modifier else Modifier.height(84.dp)),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = Tok.muted, fontSize = 13.sp)
                }
                inner()
            },
        )
    }
}

@Composable
internal fun CenteredHint(text: String) {
    Text(
        text, color = Tok.muted, fontSize = 12.5.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
