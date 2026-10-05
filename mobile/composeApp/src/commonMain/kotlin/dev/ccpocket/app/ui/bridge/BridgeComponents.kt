package dev.ccpocket.app.ui.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AccessTier
import org.jetbrains.compose.resources.stringResource

// Small pieces the phone bridge monitor draws. They used to live with the (since retired) folder-share
// screens; the look is unchanged.

/** The display name of an [AccessTier] (an unknown tier from a newer daemon reads as the most cautious). */
@Composable
fun accessTierLabel(t: AccessTier): String = stringResource(
    when (t) {
        AccessTier.REVIEW -> Res.string.share_tier_review
        AccessTier.COLLABORATE -> Res.string.share_tier_collaborate
        AccessTier.AUTONOMOUS -> Res.string.share_tier_autonomous
        AccessTier.UNKNOWN -> Res.string.share_tier_review
    },
)

/** The terracotta tier chip ("Collaborate") — attention hue, since the tier is what the owner is granting. */
@Composable
fun AccessTierBadge(tier: AccessTier) {
    Text(
        accessTierLabel(tier), color = Tok.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.accent.copy(alpha = 0.12f))
            .border(1.dp, Tok.accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** A bordered secondary action ("Cancel"). */
@Composable
fun OutlineActionButton(text: String, modifier: Modifier = Modifier, color: Color = Tok.tx, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(13.dp)).border(1.dp, Tok.hair, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(vertical = 13.dp),
    )
}
