package dev.ccpocket.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rv_bad_commits
import dev.ccpocket.app.resources.rv_bad_local_path
import dev.ccpocket.app.resources.rv_bad_url
import dev.ccpocket.app.resources.rv_dir_inbound
import dev.ccpocket.app.resources.rv_dir_outbound
import dev.ccpocket.app.resources.rv_kind_commits
import dev.ccpocket.app.resources.rv_kind_doc
import dev.ccpocket.app.resources.rv_kind_mr
import dev.ccpocket.app.resources.rv_open_link
import dev.ccpocket.app.resources.rv_request_required
import dev.ccpocket.app.resources.rv_status_acknowledged
import dev.ccpocket.app.resources.rv_status_cancelled
import dev.ccpocket.app.resources.rv_status_closed
import dev.ccpocket.app.resources.rv_status_declined
import dev.ccpocket.app.resources.rv_status_delivered
import dev.ccpocket.app.resources.rv_status_expired
import dev.ccpocket.app.resources.rv_status_in_progress
import dev.ccpocket.app.resources.rv_status_queued
import dev.ccpocket.app.resources.rv_status_responded
import dev.ccpocket.app.resources.rv_status_unknown
import dev.ccpocket.app.resources.rv_summary_clear
import dev.ccpocket.app.resources.rv_summary_sent_many
import dev.ccpocket.app.resources.rv_summary_sent_one
import dev.ccpocket.app.resources.rv_summary_waiting_many
import dev.ccpocket.app.resources.rv_summary_waiting_one
import dev.ccpocket.app.resources.rv_untrusted_body
import dev.ccpocket.app.resources.rv_untrusted_title
import dev.ccpocket.app.resources.rv_verdict_approve
import dev.ccpocket.app.resources.rv_verdict_comment
import dev.ccpocket.app.resources.rv_verdict_request_changes
import dev.ccpocket.app.resources.rv_verdict_unable
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewVerdict
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// ===========================================================================
//  Review Center display kit (REVIEW-REQUEST.md §12). Pure mapping + small
//  stateless pieces, in the ui/handoff/*Components.kt idiom: no repository,
//  no side effects, so the screens above can be driven straight from a test.
//
//  Two rules that are load-bearing rather than cosmetic:
//   - an UNKNOWN enum renders as an explicit "can't read this", never as a
//     neutral-looking blank and never as a success. A status this build cannot
//     read is locked daemon-side (§8), and the UI has to say so.
//   - nothing here fetches, opens or executes anything from peer material.
// ===========================================================================

/**
 * Whether the Center may state a count at all.
 *
 * Loading, unsupported, offline and error are not "zero" — they are "we don't know", and a header that
 * carried the last ready state's pending count through one of them would be asserting daemon truth the
 * daemon never sent (Supporting Surfaces UI 2.0 · README "Non-ready states do not reuse ready-state counts").
 */
fun ReviewCenterState.summaryReady(): Boolean = !loading && !unsupported && !offline && error == null

/**
 * The Center's one factual line — or null whenever [summaryReady] is false, so the header simply drops it.
 *
 * [pending] is the repository's own count of requests waiting on this human; the sent tally is the honest
 * size of the outbound list, never a delivery claim about any of them.
 */
@Composable
fun reviewSummaryText(state: ReviewCenterState, pending: Int): String? {
    if (!state.summaryReady()) return null
    val parts = buildList {
        add(
            if (pending > 0) {
                stringResource(
                    if (pending == 1) Res.string.rv_summary_waiting_one else Res.string.rv_summary_waiting_many,
                    pending,
                )
            } else {
                stringResource(Res.string.rv_summary_clear)
            },
        )
        if (state.sent.isNotEmpty()) add(
            stringResource(
                if (state.sent.size == 1) Res.string.rv_summary_sent_one else Res.string.rv_summary_sent_many,
                state.sent.size,
            ),
        )
    }
    return parts.joinToString(" · ")
}

fun reviewStatusRes(s: ReviewStatus): StringResource = when (s) {
    ReviewStatus.QUEUED -> Res.string.rv_status_queued
    ReviewStatus.DELIVERED -> Res.string.rv_status_delivered
    ReviewStatus.ACKNOWLEDGED -> Res.string.rv_status_acknowledged
    ReviewStatus.IN_PROGRESS -> Res.string.rv_status_in_progress
    ReviewStatus.RESPONDED -> Res.string.rv_status_responded
    ReviewStatus.CLOSED -> Res.string.rv_status_closed
    ReviewStatus.DECLINED -> Res.string.rv_status_declined
    ReviewStatus.CANCELLED -> Res.string.rv_status_cancelled
    ReviewStatus.EXPIRED -> Res.string.rv_status_expired
    ReviewStatus.UNKNOWN -> Res.string.rv_status_unknown
}

/** Terracotta is reserved for "needs you" across the app, so only the two states that actually want a
 *  human wear it. UNKNOWN is warn — not muted: an unreadable state is a prompt to update, not a shrug. */
fun reviewStatusColor(s: ReviewStatus): Color = when (s) {
    ReviewStatus.QUEUED -> Tok.muted
    ReviewStatus.DELIVERED -> Tok.accent
    ReviewStatus.ACKNOWLEDGED, ReviewStatus.IN_PROGRESS -> Tok.info
    ReviewStatus.RESPONDED -> Tok.ok
    ReviewStatus.CLOSED -> Tok.tx2
    ReviewStatus.DECLINED, ReviewStatus.CANCELLED, ReviewStatus.EXPIRED -> Tok.tx2
    ReviewStatus.UNKNOWN -> Tok.warn
}

fun reviewVerdictRes(v: ReviewVerdict): StringResource = when (v) {
    ReviewVerdict.APPROVE -> Res.string.rv_verdict_approve
    ReviewVerdict.COMMENT -> Res.string.rv_verdict_comment
    ReviewVerdict.REQUEST_CHANGES -> Res.string.rv_verdict_request_changes
    ReviewVerdict.UNABLE_TO_REVIEW -> Res.string.rv_verdict_unable
    // never renders as an approval — a verdict this build can't read is not a pass
    ReviewVerdict.UNKNOWN -> Res.string.rv_status_unknown
}

fun reviewVerdictColor(v: ReviewVerdict): Color = when (v) {
    ReviewVerdict.APPROVE -> Tok.ok
    ReviewVerdict.COMMENT -> Tok.info
    ReviewVerdict.REQUEST_CHANGES -> Tok.warn
    ReviewVerdict.UNABLE_TO_REVIEW, ReviewVerdict.UNKNOWN -> Tok.tx2
}

fun artifactKindRes(k: ArtifactKind): StringResource = when (k) {
    ArtifactKind.MERGE_REQUEST -> Res.string.rv_kind_mr
    ArtifactKind.DOCUMENT_URL -> Res.string.rv_kind_doc
    ArtifactKind.COMMIT_RANGE -> Res.string.rv_kind_commits
    ArtifactKind.UNKNOWN -> Res.string.rv_status_unknown
}

/** One artifact as a single line, exactly what the sender declared. No fetching, no normalizing —
 *  resolving it happens later, in the reviewer's own tools, under their own access (§3.2). */
fun artifactLine(a: ArtifactRef): String = when (a.kind) {
    ArtifactKind.MERGE_REQUEST, ArtifactKind.DOCUMENT_URL -> a.url.orEmpty()
    ArtifactKind.COMMIT_RANGE -> "${a.repo}#${a.base}..${a.head}"
    ArtifactKind.UNKNOWN -> ""
}

/** The URL a tap may open — null for anything this build can't describe, so an unreadable artifact
 *  kind can never become a link. Opening is always an explicit user action (§11.2). */
fun artifactUrl(a: ArtifactRef): String? = when (a.kind) {
    ArtifactKind.MERGE_REQUEST, ArtifactKind.DOCUMENT_URL ->
        a.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    else -> null
}

// ---- client-side form validation ------------------------------------------
//
// FAST FEEDBACK ONLY. The daemon validates again and is authoritative (ReviewLimits/ArtifactSyntax);
// these mirror its grammar so a typo is caught before a round trip, never so the client can decide
// what is acceptable. Anything these accept and the daemon rejects surfaces as the daemon's own error.

/** Why this artifact draft isn't sendable yet, or null when it looks fine. */
fun artifactProblem(kind: ArtifactKind, url: String, repo: String, base: String, head: String): StringResource? =
    when (kind) {
        ArtifactKind.MERGE_REQUEST, ArtifactKind.DOCUMENT_URL ->
            if (url.trim().startsWith("https://") || url.trim().startsWith("http://")) null else Res.string.rv_bad_url
        ArtifactKind.COMMIT_RANGE -> when {
            repo.isBlank() || base.isBlank() || head.isBlank() -> Res.string.rv_bad_commits
            // a local path is the mistake worth naming: it leaks the sender's directory layout AND the
            // recipient can't match it against their own checkout (§7.2)
            looksLocal(repo) -> Res.string.rv_bad_local_path
            else -> null
        }
        ArtifactKind.UNKNOWN -> Res.string.rv_status_unknown
    }

private fun looksLocal(repo: String): Boolean {
    val r = repo.trim()
    return r.startsWith("/") || r.startsWith("~") || r.startsWith(".") || (r.length > 1 && r[1] == ':')
}

fun requestProblem(request: String): StringResource? =
    if (request.isBlank()) Res.string.rv_request_required else null

fun buildArtifact(kind: ArtifactKind, url: String, repo: String, base: String, head: String, title: String): ArtifactRef =
    when (kind) {
        ArtifactKind.COMMIT_RANGE -> ArtifactRef(
            kind = kind, repo = repo.trim(), base = base.trim(), head = head.trim(),
            title = title.trim().takeIf { it.isNotEmpty() },
        )
        else -> ArtifactRef(kind = kind, url = url.trim(), title = title.trim().takeIf { it.isNotEmpty() })
    }

/** The contact's direction line. Never merged by label: two directions are two credentials, and only a
 *  deterministic match may render as one person (§9). */
fun contactDirectionRes(d: CollaboratorDirection): StringResource = when (d) {
    CollaboratorDirection.INBOUND -> Res.string.rv_dir_inbound
    else -> Res.string.rv_dir_outbound
}

/** A contact established for Session Handoff, still usable for reviews because that is what every link
 *  from before purposes existed looks like. Worth labelling so the two kinds stay distinguishable. */
fun isLegacyContact(c: ReviewContact) = c.purpose == CollaboratorPurpose.SESSION_HANDOFF

// ---- components ------------------------------------------------------------

@Composable
fun ReviewStatusChip(status: ReviewStatus, modifier: Modifier = Modifier) {
    val c = reviewStatusColor(status)
    Text(
        stringResource(reviewStatusRes(status)),
        color = c, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * The untrusted-material notice (§11.2). Deliberately ABOVE the peer's text, deliberately not
 * dismissible, and deliberately phrased as what the app will NOT do: the reader has to be able to tell
 * our chrome from their words before they read a single line of them.
 */
@Composable
fun UntrustedNotice(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Tok.warn.copy(alpha = 0.08f)).border(1.dp, Tok.warn.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(stringResource(Res.string.rv_untrusted_title), color = Tok.warn, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.rv_untrusted_body), color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** One artifact, with an explicit open affordance. The row itself is inert: a link opens only on a tap
 *  of the labelled action, never by rendering and never automatically. */
@Composable
fun ArtifactRow(a: ArtifactRef, onOpen: ((String) -> Unit)? = null) {
    val url = artifactUrl(a)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(artifactKindRes(a.kind)), color = Tok.muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
            a.title?.let { Text(it, color = Tok.tx2, fontSize = 11.5.sp) }
            if (url != null && onOpen != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(Res.string.rv_open_link), color = Tok.accent, fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpen(url) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(artifactLine(a), color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** A titled block of the brief. Renders nothing at all when empty rather than an empty heading. */
@Composable
fun BriefSection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(), color = Tok.muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
        lines.forEach { Text("· $it", color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp) }
    }
}

@Composable
fun ReviewSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = modifier,
    )
}

@Composable
fun ReviewCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) { content() }
}

@Composable
fun ReviewDivider() = androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

/** An honest empty/loading/unsupported pane: the three read differently on purpose, because "you have
 *  none" and "the daemon never answered" are not the same news. */
@Composable
fun ReviewStatePane(title: String, sub: String? = null, tint: Color = Tok.tx2) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        sub?.let {
            Text(
                it, color = Tok.muted, fontSize = 12.5.sp, lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(320.dp),
            )
        }
    }
}
