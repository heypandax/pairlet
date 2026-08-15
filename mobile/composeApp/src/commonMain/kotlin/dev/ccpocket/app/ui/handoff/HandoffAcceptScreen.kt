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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffKind
import org.jetbrains.compose.resources.stringResource

// ── daemon-reported kind/access → words (§6). UNKNOWN is a NEWER peer's value this build can't enforce:
// it renders as an explicit refusal, never as the friendly default it used to be hardcoded to.

@Composable
fun kindTitle(kind: HandoffKind): String = stringResource(
    when (kind) {
        HandoffKind.REVIEW -> Res.string.ho_role_code_review
        HandoffKind.CONTINUE -> Res.string.ho_kind_continue_title
        HandoffKind.UNKNOWN -> Res.string.ho_kind_unknown_title
    },
)

@Composable
fun kindChip(kind: HandoffKind): String = stringResource(
    when (kind) {
        HandoffKind.REVIEW -> Res.string.ho_role_review
        HandoffKind.CONTINUE -> Res.string.ho_role_continue
        HandoffKind.UNKNOWN -> Res.string.ho_kind_unknown_chip
    },
)

@Composable
fun accessTitle(access: HandoffAccess): String = stringResource(
    when (access) {
        // NOT "Read-only": the shell can still write once you approve a command (§2.2)
        HandoffAccess.REVIEW_READ_ONLY -> Res.string.ho_access_review_title
        HandoffAccess.CONTINUE_SCOPED -> Res.string.ho_role_continue_desc
        HandoffAccess.UNKNOWN -> Res.string.ho_access_unknown_title
    },
)

@Composable
fun accessChip(access: HandoffAccess): String = stringResource(
    when (access) {
        HandoffAccess.REVIEW_READ_ONLY -> Res.string.ho_access_review_chip
        HandoffAccess.CONTINUE_SCOPED -> Res.string.ho_role_continue_desc
        HandoffAccess.UNKNOWN -> Res.string.ho_access_unknown_chip
    },
)

/** Facts group row (design hrow): icon + title/sub + optional trailing chip. */
@Composable
private fun FactRow(icon: ImageVector, title: String, sub: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        trailing?.invoke()
    }
}

/**
 * Frames 4 / 4b — the recipient's trust screen after opening a handoff invite.
 * Facts first (who / what / where), then the boundary grammar (second person), then the brief.
 * [expiredNote] non-null renders the inline error variant: content dims to 32%, actions collapse
 * to a single Close. The same shell serves DECLINED / CANCELLED / RECALLED with swapped strings.
 *
 * [kind]/[access] come from the DAEMON's copy of the handoff and are rendered as-is (implementation
 * review §6): this screen used to hardcode "Code review / Read-only", which would have described a
 * CONTINUE grant — or one this daemon refuses outright — as a read-only review. A combination v1 doesn't
 * implement renders its own refusal and disables Accept rather than pretending.
 *
 * [errorNote] carries a real failure (a lost accept race, an expired offer, a `handoff_not_supported`
 * refusal) — the accept round-trip is never allowed to look successful when it wasn't (§3.2.7).
 */
@Composable
fun HandoffAcceptScreen(
    ownerLabel: String,
    sessionTitle: String,
    path: String,
    branch: String?,
    returnsIn: String,
    roots: List<String>,
    briefSections: List<HandoffBriefSectionUi>,
    expiredNote: String?,
    accepting: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
    kind: HandoffKind = HandoffKind.REVIEW,
    access: HandoffAccess = HandoffAccess.REVIEW_READ_ONLY,
    errorNote: String? = null,
) {
    val expired = expiredNote != null
    // v1 ships exactly one authorization combination; anything else is honestly unusable here (§6)
    val supported = kind == HandoffKind.REVIEW && access == HandoffAccess.REVIEW_READ_ONLY
    // #257: this screen is drawn full-screen OVER whatever hosts it (the chat, or the incoming-offer
    // list), so Android back must close it rather than fall through. [onClose] is what ✕ does, and in
    // the offer-list host it is that list's own "back to the offers" — so both hosts stay correct
    // without a mount-point handler. Registered here, i.e. later than the host's, so LIFO picks it.
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onClose() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        // top bar: ✕ + title + INVITE / EXPIRED chip
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(Res.string.ho_invite_title), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (expired) {
                HandoffStatusChip(HandoffUiStatus.EXPIRED, Modifier.padding(end = 12.dp))
            } else {
                Row(
                    Modifier.padding(end = 12.dp).height(22.dp).clip(RoundedCornerShape(6.dp))
                        .background(Tok.accent.copy(alpha = 0.10f)).border(1.dp, Tok.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    HandoffRelayGlyph(Tok.accent, 11.dp)
                    Text(stringResource(Res.string.ho_invite_chip), color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (expired) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Tok.warn.copy(alpha = 0.08f)).border(1.dp, Tok.warn.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Column {
                    Text(stringResource(Res.string.ho_expired_title), color = Tok.warn, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(expiredNote!!, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Column(
            Modifier.weight(1f).alpha(if (expired) 0.32f else 1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HandoffSessionFactsCard(
                sessionTitle, path, branch, agentLabel = null,
                leading = { HandoffAvatar(ownerLabel, accent = true) },
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
            ) {
                // role + access read off the daemon's handoff, never assumed (§6)
                FactRow(Icons.Rounded.RemoveRedEye, kindTitle(kind), stringResource(Res.string.ho_role_label)) {
                    Text(
                        kindChip(kind), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                FactRow(Icons.Rounded.Lock, accessTitle(access), stringResource(Res.string.ho_access_label)) {
                    val ok = access == HandoffAccess.REVIEW_READ_ONLY
                    val tint = if (ok) Tok.ok else Tok.warn
                    Text(
                        accessChip(access), color = tint, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.10f))
                            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                if (supported) {
                    // §2.2: "READ ONLY" alone is a boundary lie — shell can write. Say what actually holds.
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    Text(
                        stringResource(Res.string.ho_access_honest), color = Tok.tx2, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                FactRow(Icons.Rounded.Schedule, stringResource(Res.string.ho_returns_in, returnsIn), stringResource(Res.string.ho_returns_sub))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                FactRow(Icons.Rounded.Memory, stringResource(Res.string.ho_runs_on, ownerLabel), stringResource(Res.string.ho_runs_on_sub, ownerLabel))
            }
            HandoffBoundaryCard(secondPerson = true, roots = roots, ownerLabel = ownerLabel)
            HandoffBriefCard(briefSections, editable = false, startExpanded = true)
        }
        // pinned footer
        Column(
            Modifier.fillMaxWidth().background(Tok.base).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // a refusal is stated where the action is, so nothing reads as "accepted" that wasn't
            val note = errorNote ?: if (!supported) stringResource(Res.string.ho_not_supported) else null
            if (note != null && !expired) {
                Text(note, color = if (supported) Tok.danger else Tok.warn, fontSize = 12.5.sp, lineHeight = 18.sp)
            }
            if (expired) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(Res.string.ho_close), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                // §3.2.7: Accept holds a real waiting state until the daemon answers — it never flips to
                // "done" locally, and an unsupported grant can't be accepted at all
                val armed = !accepting && supported
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (armed) Tok.accent else Tok.accent.copy(alpha = 0.4f))
                        .clickable(enabled = armed, onClick = onAccept),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (accepting) Res.string.ho_accepting else Res.string.ho_accept),
                        color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable(onClick = onDecline),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(Res.string.ho_decline), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
