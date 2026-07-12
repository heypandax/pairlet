package dev.ccpocket.app.ui.share

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * Guest terminal states (issue #115, design frames 4b/4c): endings are calm, explained and final — no
 * scary error, no retry spinner. A muted "Access ended" card, and a slim disconnect banner for a share
 * revoked mid-session. (The row-level ended states live in the projects list; these are the detail/inline
 * surfaces.)
 */

/** Frame 4b — the guest's terminal detail card: muted folder, an honest one-liner, and two ways out.
 *  [ending] picks the honest body line: REVOKED = "<owner> ended this share", EXPIRED = "this share
 *  has expired" — the precise distinction the daemon's ShareEnded notice carries (#115 follow-up). */
@Composable
fun GuestEndedCard(ownerLabel: String?, ending: GuestEnding = GuestEnding.REVOKED, onRemove: () -> Unit, onAskNew: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Tok.base).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 340.dp).clip(RoundedCornerShape(20.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Text("🚫", fontSize = 26.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(Res.string.share_access_ended), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                when (ending) {
                    GuestEnding.REVOKED -> stringResource(Res.string.share_ended_body, ownerLabel ?: stringResource(Res.string.share_the_owner))
                    GuestEnding.EXPIRED -> stringResource(Res.string.share_ended_body_expired)
                },
                color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(22.dp))
            ShareOutlineButton(stringResource(Res.string.share_remove_from_list), Modifier.fillMaxWidth(), onClick = onRemove)
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(Res.string.share_ask_new_invite), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onAskNew).padding(vertical = 8.dp),
            )
        }
    }
}

/** Frame 4c — the slim danger banner that slides in when a share is revoked during a live session. */
@Composable
fun ShareRevokedBanner(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.danger.copy(alpha = 0.4f)))
        Row(
            Modifier.fillMaxWidth().background(Tok.danger.copy(alpha = 0.1f)).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Rounded.Warning, null, tint = Tok.danger, modifier = Modifier.size(16.dp))
            Text(stringResource(Res.string.share_revoked_disconnected), color = Tok.danger, fontSize = 12.5.sp, lineHeight = 16.sp)
        }
    }
}

/** An ended shared row in the projects list (frame 4a): muted folder + Expired / Access-ended pill. */
@Composable
fun EndedSharedRow(name: String, revoked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Tok.muted))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (revoked) Res.string.share_access_ended else Res.string.share_expired_label),
                    color = if (revoked) Tok.danger.copy(alpha = 0.85f) else Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (revoked) Tok.danger.copy(alpha = 0.08f) else Tok.surface)
                        .border(1.dp, if (revoked) Tok.danger.copy(alpha = 0.25f) else Tok.hair, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}
