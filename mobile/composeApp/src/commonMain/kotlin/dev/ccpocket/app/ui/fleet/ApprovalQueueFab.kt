package dev.ccpocket.app.ui.fleet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.approval_fab_a11y
import dev.ccpocket.app.resources.approval_fab_label
import dev.ccpocket.app.resources.approval_fab_refreshing
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * Contextual entry to the daemon-synced approval inbox. It is deliberately a compact raised pill rather
 * than a generic large Material FAB: the hairline + terracotta signal matches cc-pocket's existing cards,
 * while the fixed count capsule keeps 1 → 9+ updates from moving the label.
 */
@Composable
fun ApprovalQueueFab(
    count: Int,
    refreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.96f),
        exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.96f),
    ) {
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val shape = RoundedCornerShape(14.dp)
        val label = stringResource(Res.string.approval_fab_label)
        val a11y = stringResource(Res.string.approval_fab_a11y, count)
        val refreshingLabel = stringResource(Res.string.approval_fab_refreshing)
        Row(
            Modifier.height(48.dp).clip(shape)
                .background(if (pressed) Tok.accent.copy(alpha = 0.18f) else Tok.raised)
                .border(1.dp, Tok.accent.copy(alpha = if (refreshing) 0.38f else 0.68f), shape)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = a11y
                    role = Role.Button
                    if (refreshing) stateDescription = refreshingLabel
                }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = Tok.accent.copy(alpha = if (refreshing) 0.75f else 1f),
                    modifier = Modifier.size(18.dp),
                )
                if (refreshing) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(6.dp).clip(RoundedCornerShape(99.dp))
                            .background(Tok.warn).border(1.dp, Tok.raised, RoundedCornerShape(99.dp)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.widthIn(min = 24.dp).height(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(Tok.accent.copy(alpha = if (refreshing) 0.16f else 0.24f))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 9) "9+" else count.toString(),
                    color = Tok.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
