package dev.ccpocket.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rv_add_finding
import dev.ccpocket.app.resources.rv_add_item
import dev.ccpocket.app.resources.rv_artifact_title
import dev.ccpocket.app.resources.rv_artifacts
import dev.ccpocket.app.resources.rv_base
import dev.ccpocket.app.resources.rv_brief_background
import dev.ccpocket.app.resources.rv_brief_constraints
import dev.ccpocket.app.resources.rv_brief_dod
import dev.ccpocket.app.resources.rv_brief_done
import dev.ccpocket.app.resources.rv_brief_focus
import dev.ccpocket.app.resources.rv_brief_risks
import dev.ccpocket.app.resources.rv_brief_verified
import dev.ccpocket.app.resources.rv_copied
import dev.ccpocket.app.resources.rv_expires
import dev.ccpocket.app.resources.rv_expires_default
import dev.ccpocket.app.resources.rv_finding_detail
import dev.ccpocket.app.resources.rv_finding_file
import dev.ccpocket.app.resources.rv_finding_line
import dev.ccpocket.app.resources.rv_finding_title
import dev.ccpocket.app.resources.rv_findings
import dev.ccpocket.app.resources.rv_head
import dev.ccpocket.app.resources.rv_invite_copy
import dev.ccpocket.app.resources.rv_invite_expires
import dev.ccpocket.app.resources.rv_invite_fingerprint
import dev.ccpocket.app.resources.rv_invite_sub
import dev.ccpocket.app.resources.rv_invite_title
import dev.ccpocket.app.resources.rv_join_accept
import dev.ccpocket.app.resources.rv_join_bad
import dev.ccpocket.app.resources.rv_join_confirm
import dev.ccpocket.app.resources.rv_join_paste
import dev.ccpocket.app.resources.rv_join_sub
import dev.ccpocket.app.resources.rv_join_title
import dev.ccpocket.app.resources.rv_kind
import dev.ccpocket.app.resources.rv_loading
import dev.ccpocket.app.resources.rv_more
import dev.ccpocket.app.resources.rv_next_steps
import dev.ccpocket.app.resources.rv_no_recipients
import dev.ccpocket.app.resources.rv_open_questions
import dev.ccpocket.app.resources.rv_optional_title
import dev.ccpocket.app.resources.rv_preview_note
import dev.ccpocket.app.resources.rv_preview_title
import dev.ccpocket.app.resources.rv_recipient
import dev.ccpocket.app.resources.rv_repo
import dev.ccpocket.app.resources.rv_repo_hint
import dev.ccpocket.app.resources.rv_request
import dev.ccpocket.app.resources.rv_request_hint
import dev.ccpocket.app.resources.rv_respond
import dev.ccpocket.app.resources.rv_review_before_send
import dev.ccpocket.app.resources.rv_send
import dev.ccpocket.app.resources.rv_summary
import dev.ccpocket.app.resources.rv_summary_hint
import dev.ccpocket.app.resources.rv_to
import dev.ccpocket.app.resources.rv_url
import dev.ccpocket.app.resources.rv_url_hint
import dev.ccpocket.app.resources.rv_verdict
import dev.ccpocket.app.resources.rv_verification
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.handoff.FingerprintBlock
import dev.ccpocket.app.ui.share.ShareOutlineButton
import dev.ccpocket.app.ui.share.SharePrimaryButton
import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.HandoffFinding
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewFinding
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewVerdict
import dev.ccpocket.protocol.collaboratorFingerprint
import org.jetbrains.compose.resources.stringResource

// ===========================================================================
//  The Review Center's FORMS: compose a request, compose a result, establish a
//  contact. Pure like the screens beside them.
//
//  One rule runs through all three: the client validates for SPEED, the daemon
//  validates for TRUTH. Everything here mirrors ArtifactSyntax/ReviewLimits so
//  a typo is caught before a round trip — and every refusal the daemon returns
//  is still shown verbatim, because these checks are not allowed to be the
//  reason something was or wasn't sent.
// ===========================================================================

// ---- new review ------------------------------------------------------------

/** One artifact row being drafted. Held as UI text, converted to an [ArtifactRef] only on send. */
private class ArtifactDraft(kind: ArtifactKind) {
    var kind by mutableStateOf(kind)
    var url by mutableStateOf("")
    var repo by mutableStateOf("")
    var base by mutableStateOf("")
    var head by mutableStateOf("")
    var title by mutableStateOf("")
}

@Composable
fun NewReviewScreen(
    recipients: List<ReviewContact>,
    sending: Boolean,
    error: String?,
    onSend: (recipientDeviceId: String, title: String, brief: ReviewBrief, artifacts: List<ArtifactRef>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // hold the ID, not the row: a contacts listing landing mid-draft (an action refresh, a reconnect)
    // replaces the list, and re-keying a remember on it would silently reset the pick — to the FIRST
    // contact, which is a different colleague. Resolving by id instead keeps the choice or, if that
    // contact really is gone, falls back visibly.
    var recipientId by remember { mutableStateOf<String?>(null) }
    val recipient = recipients.firstOrNull { it.id == recipientId } ?: recipients.firstOrNull()
    var title by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("") }
    val focus = remember { mutableStateListOf<String>() }
    val risks = remember { mutableStateListOf<String>() }
    val done = remember { mutableStateListOf<String>() }
    val verified = remember { mutableStateListOf<String>() }
    val constraints = remember { mutableStateListOf<String>() }
    val dod = remember { mutableStateListOf<String>() }
    val artifact = remember { ArtifactDraft(ArtifactKind.MERGE_REQUEST) }
    var advanced by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }

    val requestErr = requestProblem(request)
    val artifactErr = artifactProblem(artifact.kind, artifact.url, artifact.repo, artifact.base, artifact.head)
    val ready = recipient != null && requestErr == null && artifactErr == null

    fun brief() = ReviewBrief(
        request = request.trim(),
        background = background.trim().takeIf { it.isNotEmpty() },
        completedWork = done.toList(),
        focusAreas = focus.toList(),
        knownRisks = risks.toList(),
        verification = verified.toList(),
        constraints = constraints.toList(),
        definitionOfDone = dod.toList(),
    )

    fun artifacts() = listOf(buildArtifact(artifact.kind, artifact.url, artifact.repo, artifact.base, artifact.head, artifact.title))

    Column(
        modifier.fillMaxSize().background(Tok.base).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp) }

        if (previewing && recipient != null) {
            // the last thing between a private brief and someone else's computer. It shows EXACTLY what
            // goes on the wire — no session, no folder path, no transcript — because that claim is only
            // worth making if the user can see it (§3.1).
            SharePreview(
                recipientLabel = recipient!!.label,
                title = title.trim(),
                brief = brief(),
                artifacts = artifacts(),
                sending = sending,
                onBack = { previewing = false },
                onConfirm = { onSend(recipient!!.id, title.trim(), brief(), artifacts()) },
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ReviewSectionLabel(stringResource(Res.string.rv_recipient))
            if (recipients.isEmpty()) {
                CenteredHint(stringResource(Res.string.rv_no_recipients))
            } else {
                recipients.forEach { c ->
                    RecipientRow(c, selected = recipient?.id == c.id) { recipientId = c.id }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewSectionLabel(stringResource(Res.string.rv_artifacts))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ArtifactKind.MERGE_REQUEST, ArtifactKind.DOCUMENT_URL, ArtifactKind.COMMIT_RANGE).forEach { k ->
                    KindChip(stringResource(artifactKindRes(k)), artifact.kind == k) { artifact.kind = k }
                }
            }
            if (artifact.kind == ArtifactKind.COMMIT_RANGE) {
                ReviewField(stringResource(Res.string.rv_repo), artifact.repo, placeholder = stringResource(Res.string.rv_repo_hint)) { artifact.repo = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { ReviewField(stringResource(Res.string.rv_base), artifact.base) { artifact.base = it } }
                    Column(Modifier.weight(1f)) { ReviewField(stringResource(Res.string.rv_head), artifact.head) { artifact.head = it } }
                }
            } else {
                ReviewField(stringResource(Res.string.rv_url), artifact.url, placeholder = stringResource(Res.string.rv_url_hint)) { artifact.url = it }
            }
            ReviewField(stringResource(Res.string.rv_artifact_title), artifact.title) { artifact.title = it }
            artifactErr?.let { if (artifactDirty(artifact)) Text(stringResource(it), color = Tok.warn, fontSize = 11.5.sp, lineHeight = 16.sp) }
        }

        ReviewField(stringResource(Res.string.rv_request), request, singleLine = false, placeholder = stringResource(Res.string.rv_request_hint)) { request = it }
        ReviewField(stringResource(Res.string.rv_optional_title), title) { title = it }

        Text(
            stringResource(Res.string.rv_more), color = Tok.accent, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable { advanced = !advanced }.padding(vertical = 4.dp),
        )
        if (advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                ReviewField(stringResource(Res.string.rv_brief_background), background, singleLine = false) { background = it }
                ListField(stringResource(Res.string.rv_brief_focus), focus)
                ListField(stringResource(Res.string.rv_brief_risks), risks)
                ListField(stringResource(Res.string.rv_brief_done), done)
                ListField(stringResource(Res.string.rv_brief_verified), verified)
                ListField(stringResource(Res.string.rv_brief_constraints), constraints)
                ListField(stringResource(Res.string.rv_brief_dod), dod)
                // the daemon clamps expiry to its own default when we send none — mirrored, not decided,
                // so the two can't drift (DEFAULT_REVIEW_EXPIRES_SEC)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.rv_expires), color = Tok.muted, fontSize = 11.sp)
                    Text(stringResource(Res.string.rv_expires_default), color = Tok.tx2, fontSize = 12.sp)
                }
            }
        }

        SharePrimaryButton(stringResource(Res.string.rv_send), enabled = ready && !sending) { previewing = true }
    }
}

private fun artifactDirty(a: ArtifactDraft) =
    a.url.isNotBlank() || a.repo.isNotBlank() || a.base.isNotBlank() || a.head.isNotBlank()

@Composable
private fun RecipientRow(c: ReviewContact, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Tok.raised else Tok.surface)
            .border(1.dp, if (selected) Tok.accent else Tok.hair, RoundedCornerShape(10.dp))
            .clickable(onClick = onPick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.label, color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(contactDirectionRes(c.direction)), color = Tok.muted, fontSize = 11.sp)
        }
        if (selected) Text("✓", color = Tok.accent, fontSize = 14.sp)
    }
}

@Composable
private fun KindChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Tok.tx else Tok.tx2, fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Tok.raised else Tok.surface)
            .border(1.dp, if (selected) Tok.accent else Tok.hair, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** A repeatable free-text list (focus areas, risks, …). Bounded by the daemon; unbounded here would only
 *  mean the refusal arrives later. */
@Composable
private fun ListField(label: String, items: androidx.compose.runtime.snapshots.SnapshotStateList<String>) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ReviewField(label, draft) { draft = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShareOutlineButton(stringResource(Res.string.rv_add_item), Modifier.width(96.dp)) {
                draft.trim().takeIf { it.isNotEmpty() }?.let { items += it; draft = "" }
            }
        }
        items.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("· $v", color = Tok.tx2, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text(
                    "✕", color = Tok.muted, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { items.removeAt(i) }.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun SharePreview(
    recipientLabel: String,
    title: String,
    brief: ReviewBrief,
    artifacts: List<ArtifactRef>,
    sending: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        ReviewSectionLabel(stringResource(Res.string.rv_preview_title))
        Text(stringResource(Res.string.rv_to, recipientLabel), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        artifacts.forEach { ArtifactRow(it) }
        BriefBlock(title, brief)
        Text(stringResource(Res.string.rv_preview_note), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShareOutlineButton(stringResource(Res.string.rv_more), Modifier.weight(1f), onClick = onBack)
            Column(Modifier.weight(1f)) { SharePrimaryButton(stringResource(Res.string.rv_send), enabled = !sending, onClick = onConfirm) }
        }
        if (sending) Text(stringResource(Res.string.rv_loading), color = Tok.muted, fontSize = 11.5.sp)
    }
}

// ---- respond ----------------------------------------------------------------

private class FindingDraft {
    var title by mutableStateOf("")
    var severity by mutableStateOf(HandoffFinding.SEVERITY_MEDIUM)
    var detail by mutableStateOf("")
    var file by mutableStateOf("")
    var line by mutableStateOf("")
}

/**
 * The manual structured response. Verdict + summary are required and everything else is optional, which
 * matches the daemon's own [dev.ccpocket.protocol.ReviewResult] contract — the rich path is still the
 * reviewer's own agent writing the JSON (the Skill), and this form exists so the UI is never a dead end.
 */
@Composable
fun RespondScreen(
    sending: Boolean,
    error: String?,
    onSubmit: (ReviewResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var verdict by remember { mutableStateOf(ReviewVerdict.COMMENT) }
    var summary by remember { mutableStateOf("") }
    val findings = remember { mutableStateListOf<FindingDraft>() }
    val verification = remember { mutableStateListOf<String>() }
    val questions = remember { mutableStateListOf<String>() }
    val next = remember { mutableStateListOf<String>() }
    var confirming by remember { mutableStateOf(false) }

    fun result() = ReviewResult(
        verdict = verdict,
        summary = summary.trim(),
        findings = findings.filter { it.title.isNotBlank() }.map {
            ReviewFinding(
                title = it.title.trim(), severity = it.severity,
                detail = it.detail.trim().takeIf { d -> d.isNotEmpty() },
                file = it.file.trim().takeIf { fl -> fl.isNotEmpty() },
                line = it.line.trim().toIntOrNull(),
            )
        },
        verification = verification.toList(),
        openQuestions = questions.toList(),
        recommendedNextSteps = next.toList(),
    )

    val ready = summary.isNotBlank() && verdict != ReviewVerdict.UNKNOWN

    Column(
        modifier.fillMaxSize().background(Tok.base).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp) }

        if (confirming) {
            // it can quote private code from this machine, and once it leaves it is on their computer
            ReviewSectionLabel(stringResource(Res.string.rv_preview_title))
            Text(stringResource(Res.string.rv_review_before_send), color = Tok.warn, fontSize = 12.sp, lineHeight = 17.sp)
            ResultBlock(result())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareOutlineButton(stringResource(Res.string.rv_more), Modifier.weight(1f)) { confirming = false }
                Column(Modifier.weight(1f)) {
                    SharePrimaryButton(stringResource(Res.string.rv_respond), enabled = !sending) { onSubmit(result()) }
                }
            }
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ReviewSectionLabel(stringResource(Res.string.rv_verdict))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    ReviewVerdict.APPROVE, ReviewVerdict.COMMENT,
                    ReviewVerdict.REQUEST_CHANGES, ReviewVerdict.UNABLE_TO_REVIEW,
                ).forEach { v ->
                    KindChip(stringResource(reviewVerdictRes(v)), verdict == v) { verdict = v }
                }
            }
        }
        ReviewField(stringResource(Res.string.rv_summary), summary, singleLine = false, placeholder = stringResource(Res.string.rv_summary_hint)) { summary = it }

        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            ReviewSectionLabel(stringResource(Res.string.rv_findings))
            findings.forEachIndexed { i, f ->
                ReviewCard {
                    Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ReviewField(stringResource(Res.string.rv_finding_title), f.title) { f.title = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                HandoffFinding.SEVERITY_CRITICAL, HandoffFinding.SEVERITY_HIGH,
                                HandoffFinding.SEVERITY_MEDIUM, HandoffFinding.SEVERITY_LOW,
                                HandoffFinding.SEVERITY_INFO,
                            ).forEach { s -> KindChip(s, f.severity == s) { f.severity = s } }
                        }
                        ReviewField(stringResource(Res.string.rv_finding_detail), f.detail, singleLine = false) { f.detail = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(2f)) { ReviewField(stringResource(Res.string.rv_finding_file), f.file) { f.file = it } }
                            Column(Modifier.weight(1f)) { ReviewField(stringResource(Res.string.rv_finding_line), f.line) { f.line = it } }
                        }
                        ShareOutlineButton("✕", Modifier.fillMaxWidth(), color = Tok.muted) { findings.removeAt(i) }
                    }
                }
            }
            ShareOutlineButton(stringResource(Res.string.rv_add_finding), Modifier.fillMaxWidth()) { findings += FindingDraft() }
        }

        ListField(stringResource(Res.string.rv_verification), verification)
        ListField(stringResource(Res.string.rv_open_questions), questions)
        ListField(stringResource(Res.string.rv_next_steps), next)

        SharePrimaryButton(stringResource(Res.string.rv_respond), enabled = ready && !sending) { confirming = true }
    }
}

// ---- contact setup -------------------------------------------------------------

/**
 * The invite screen. The URI is establishment material, so it is rendered once, here, and nowhere else —
 * no log line, no listing, no error message ever carries it.
 *
 * It shows the INVITER's half of the fingerprint, decoded from the minted URI's own daemon key. Both
 * halves have to be visible for the comparison to mean anything: a joiner confirming a word group the
 * inviter cannot see is confirming it against nothing.
 *
 * ALWAYS THE WHOLE URI — the QR, the text block and the copy button all carry `invite` verbatim, prefix
 * included, and none of them may ever be "tidied" into the bare base64 (REVIEW-REQUEST.md §13.3). The
 * `ccpocket://review-contact#` host is the only thing an ALREADY-RELEASED app can act on: it does not
 * read the trailing `purpose`, so a stripped blob pasted into its ordinary collaborator field would be
 * redeemed as a phone contact — burning the one-time ticket this daemon is waiting for.
 */
@Composable
fun ReviewInviteScreen(
    invite: String?,
    ttlSec: Int,
    creating: Boolean,
    error: String?,
    copied: Boolean,
    qr: (@Composable (String) -> Unit)? = null,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(Tok.base).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.rv_invite_title), color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.rv_invite_sub), color = Tok.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
        error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp) }
        when {
            creating -> Text(stringResource(Res.string.rv_loading), color = Tok.muted, fontSize = 12.5.sp)
            invite != null -> {
                qr?.invoke(invite)
                Text(
                    invite, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(10.dp),
                )
                // the same words the joiner's confirmation screen shows, from the same key.
                // remember(invite): decoding runs a real ECDH to validate the key, and a recomposition
                // must not re-run a keygen to redraw text that cannot have changed.
                remember(invite) { inviteFingerprint(invite) }?.let { fp ->
                    Text(
                        stringResource(Res.string.rv_invite_fingerprint), color = Tok.tx2,
                        fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                    FingerprintBlock(fp)
                }
                if (ttlSec > 0) {
                    Text(stringResource(Res.string.rv_invite_expires, ttlSec / 60), color = Tok.muted, fontSize = 11.5.sp)
                }
                ShareOutlineButton(
                    stringResource(if (copied) Res.string.rv_copied else Res.string.rv_invite_copy),
                    Modifier.fillMaxWidth(),
                ) { onCopy(invite) }
            }
        }
    }
}

/**
 * The join screen. A scan or a paste NEVER auto-joins: it decodes, shows the fingerprint, and waits for
 * an explicit confirmation — the same human trust step the Session Handoff redemption takes, for the
 * same reason. What differs is where the credential lands: on the DAEMON, not on this phone.
 */
@Composable
fun ReviewJoinScreen(
    joining: Boolean,
    error: String?,
    scanner: (@Composable ((String) -> Unit) -> Unit)? = null,
    /** A `ccpocket://review-contact#…` deep link that routed the user here (§13.3). It lands on the
     *  fingerprint step exactly as a scan would — arriving through a link is not consent, so it still
     *  redeems nothing until the human accepts. An unreadable one shows the same honest "that isn't a
     *  review invite" as a bad paste, and consumes nothing either. */
    prefill: String? = null,
    onJoin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pasted by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<Pair<String, String>?>(null) } // uri to fingerprint
    var bad by remember { mutableStateOf(false) }

    fun offer(raw: String) {
        val fp = inviteFingerprint(raw)
        if (fp == null) { bad = true; candidate = null } else { bad = false; candidate = raw to fp }
    }

    LaunchedEffect(prefill) { prefill?.let(::offer) }

    Column(
        modifier.fillMaxSize().background(Tok.base).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(Res.string.rv_join_title), color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.rv_join_sub), color = Tok.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
        error?.let { Text(it, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.sp) }

        val pending = candidate
        if (pending == null) {
            scanner?.invoke { offer(it) }
            ReviewField(stringResource(Res.string.rv_join_paste), pasted, singleLine = false) { pasted = it; bad = false }
            if (bad) Text(stringResource(Res.string.rv_join_bad), color = Tok.warn, fontSize = 12.sp)
            SharePrimaryButton(stringResource(Res.string.rv_join_accept), enabled = pasted.isNotBlank() && !joining) { offer(pasted) }
        } else {
            Text(stringResource(Res.string.rv_join_confirm), color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp)
            FingerprintBlock(pending.second)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareOutlineButton("✕", Modifier.weight(1f)) { candidate = null }
                Column(Modifier.weight(2f)) {
                    SharePrimaryButton(stringResource(Res.string.rv_join_accept), enabled = !joining) { onJoin(pending.first) }
                }
            }
            if (joining) Text(stringResource(Res.string.rv_loading), color = Tok.muted, fontSize = 11.5.sp)
        }
    }
}

/** Decode just far enough to SHOW the peer's fingerprint. The daemon redeems; this only lets the two
 *  humans compare words first, and returns null for anything that isn't a cc-pocket REVIEW contact URI —
 *  a Session Handoff invite pasted here is somebody's App, not a review peer (REVIEW-REQUEST.md §13.3). */
internal fun inviteFingerprint(raw: String): String? =
    dev.ccpocket.app.pairing.decodeReviewContactInvite(raw.trim())
        ?.let { collaboratorFingerprint(it.daemonPub) }
