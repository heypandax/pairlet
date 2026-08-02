package dev.ccpocket.app.ui.handoff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.SessionHandoff
import org.jetbrains.compose.resources.stringResource

/**
 * The ROOT-level incoming-handoff surface (implementation review §3.2.5).
 *
 * Why it can't live in ChatScreen, where the accept preview used to: a recipient holding only a
 * Collaborator credential has no session, no workdir and no convoId — the Grant that gives them one is
 * exactly what accepting this offer creates. Hosting the doorway inside the chat made the first offer
 * unreachable by construction.
 *
 * Two states, both driven purely by daemon truth ([offers] is re-derived from the latest listing, so an
 * offer withdrawn while you were away simply disappears):
 *
 *  - the LIST (design Frames 7a/7b): every waiting offer as a doorway card — View or Decline, no scope
 *    details, because the trust screen is the next step and not this one;
 *  - the ACCEPT PREVIEW (Frame 4): [HandoffAcceptScreen], reused verbatim.
 */
@Composable
fun IncomingHandoffScreen(
    offers: List<SessionHandoff>,
    selected: SessionHandoff?,
    ownerLabelOf: (SessionHandoff) -> String,
    accepting: String?,
    errorNote: String?,
    onSelect: (SessionHandoff) -> Unit,
    onAccept: (SessionHandoff) -> Unit,
    onDecline: (SessionHandoff) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    if (selected != null) {
        val owner = ownerLabelOf(selected)
        HandoffAcceptScreen(
            ownerLabel = owner,
            sessionTitle = selected.brief.originalGoal?.takeIf { it.isNotBlank() }
                ?: selected.workdir.substringAfterLast('/').ifBlank { owner },
            path = selected.workdir,
            branch = null,
            returnsIn = selected.expiresCountdown(),
            roots = selected.allowedRoots.ifEmpty { listOf(selected.workdir) },
            briefSections = selected.brief.toSections(),
            expiredNote = null,
            accepting = accepting == selected.id,
            onAccept = { onAccept(selected) },
            onDecline = { onDecline(selected) },
            onClose = onBack,
            kind = selected.kind,
            access = selected.access,
            errorNote = errorNote,
        )
        return
    }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.ho_inbox_title), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (offers.isNotEmpty()) {
                    Text(stringResource(Res.string.ho_inbox_sub, offers.size), color = Tok.muted, fontSize = 11.5.sp)
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            offers.forEach { offer ->
                HandoffOfferCard(offer, large = true, onView = { onSelect(offer) }, onDecline = { onDecline(offer) })
            }
            if (offers.isNotEmpty()) {
                Text(
                    stringResource(Res.string.co_offer_safe_note, ownerLabelOf(offers.first())),
                    color = Tok.muted, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }
        Box(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(12.dp)).background(Tok.surface).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(Res.string.ho_inbox_dismiss), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}
