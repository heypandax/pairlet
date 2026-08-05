package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rv_offline
import dev.ccpocket.app.resources.rv_title
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.review.ReviewCenterFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop Review Center (REVIEW-REQUEST.md §12): the SAME shared `ui/review` surface mobile renders,
 * inside this shell's centred-overlay chrome — one card, one header, the ⌘K/Changes/Skills grammar.
 *
 * There is no desktop-specific Review UI on purpose. A second implementation of "what will be shared"
 * or of the untrusted-material notice is a second thing to get wrong, and those two in particular are
 * the screens whose wording is load-bearing rather than decorative.
 */
@Composable
fun ReviewCenterOverlay(model: DesktopModel, onDismiss: () -> Unit) {
    Column(
        Modifier.widthIn(max = 880.dp).fillMaxWidth(0.82f).heightIn(max = 640.dp).fillMaxHeight(0.86f)
            .shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        val repo = model.reviewRepo
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.rv_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (model.reviewPending > 0) {
                Text(
                    "${model.reviewPending}", color = Tok.base, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Tok.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "✕", color = Tok.tx2, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        if (repo == null) {
            // a seed/preview model has no daemon behind it, and inventing a ledger for a screenshot
            // would be the one place this feature is allowed to show data nobody's daemon holds
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.rv_offline), color = Tok.tx2, fontSize = 13.sp)
            }
        } else {
            // desktop pastes rather than scans: there is no camera to reuse, and the invite URI is a
            // copyable string on both ends anyway
            ReviewCenterFlow(
                repo = repo,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // no onExit: leaving is the overlay's ✕ / scrim / Esc, which the shell already owns
                header = { onBack ->
                    if (onBack != null) {
                        Text(
                            "‹", color = Tok.tx2, fontSize = 20.sp,
                            modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onBack)
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                },
            )
        }
    }
}
