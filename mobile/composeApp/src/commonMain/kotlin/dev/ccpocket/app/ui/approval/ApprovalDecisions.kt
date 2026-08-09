package dev.ccpocket.app.ui.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow_for_task
import dev.ccpocket.app.resources.allow_once
import dev.ccpocket.app.resources.allow_session_option
import dev.ccpocket.app.resources.always_allow
import dev.ccpocket.app.resources.ap_more_options
import dev.ccpocket.app.resources.ap_more_options_sub
import dev.ccpocket.app.resources.auto_denied_body
import dev.ccpocket.app.resources.auto_denied_title
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.dismiss
import dev.ccpocket.app.resources.retry_safer
import dev.ccpocket.app.resources.retry_safer_send
import dev.ccpocket.app.resources.retry_safer_title
import dev.ccpocket.app.resources.rs_custom_hint
import dev.ccpocket.app.resources.rs_no_network
import dev.ccpocket.app.resources.rs_patch_only
import dev.ccpocket.app.resources.rs_read_only
import dev.ccpocket.app.resources.rs_stay_workspace
import dev.ccpocket.app.resources.rs_tests_only
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.TightCenter
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The pinned decision region of [SecureApprovalSheet], plus the two surfaces that replace it (Retry safer
 * and the TimeoutTerminal). Every tile shares one height, one radius and one hit area — including demoted
 * and sublabelled ones — so no offered action is harder to hit than another.
 */

/** Shared tile metrics: one height, one radius, one gap for every family. Comfortably over [Metric.touch]. */
private val TILE_H = 60.dp
private val TILE_GAP = 10.dp

@Composable
internal fun ApprovalDecisions(
    ui: ApprovalUi,
    onAction: (ApprovalActionId) -> Unit,
    onOpenSafer: () -> Unit,
) {
    var more by remember(ui.ask.convoId, ui.ask.askId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // Pair the tiles up. Retry-safer may span a trailing row, but a persistent legacy rule keeps the
        // same half-width hit target as one-time actions — broad authority must not get twice the tap area.
        ui.actions.chunked(2).forEachIndexed { i, row ->
            Row(
                Modifier.padding(top = if (i == 0) 0.dp else TILE_GAP)
                    .fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                row.forEach { a ->
                    DecisionTile(a.id.label(), a.emphasis, a.sublabel, Modifier.weight(1f)) {
                        if (a.id == ApprovalActionId.RETRY_SAFER) onOpenSafer() else onAction(a.id)
                    }
                }
                if (row.size == 1 && row.single().id == ApprovalActionId.ALWAYS_ALLOW) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        // the session grant is one deliberate step away: the broadest scope is never the fastest tap
        val session = ui.sessionAction
        if (session != null) {
            if (more) SessionScopeRow(session) { onAction(session.id) } else MoreOptionsRow { more = true }
        }
    }
}

/** The one state a card may leave without a decision: the daemon reported TIMED_OUT and already answered. */
@Composable
internal fun ApprovalTimeoutTerminal(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Metric.radius))
            .background(Tok.danger.copy(alpha = 0.08f))
            .border(Metric.hairline, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(Metric.radius))
            .padding(start = Metric.gapL, end = Metric.gapXs, top = Metric.gapS, bottom = Metric.gapS),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Tok.danger))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.auto_denied_title), color = Tok.tx, style = TypeRole.body.copy(fontWeight = FontWeight.SemiBold))
            Text(stringResource(Res.string.auto_denied_body), color = Tok.tx2, style = TypeRole.caption)
        }
        Box(
            Modifier.clip(RoundedCornerShape(Metric.radiusS))
                .clickable(role = Role.Button, onClick = onDismiss)
                .heightIn(min = Metric.touch).padding(horizontal = Metric.gap),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(Res.string.dismiss), color = Tok.accent, style = TypeRole.action.merge(TightCenter)) }
    }
}

@Composable
private fun DecisionTile(
    label: StringResource,
    emphasis: ActionEmphasis,
    sublabel: String?,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metric.radius)
    val fill = when (emphasis) {
        ActionEmphasis.PRIMARY -> Tok.accent
        ActionEmphasis.BOUNDARY -> Tok.danger.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val ink = when (emphasis) {
        ActionEmphasis.PRIMARY -> Tok.base
        ActionEmphasis.CAUTION -> Tok.tx2
        else -> Tok.tx
    }
    val subInk = if (emphasis == ActionEmphasis.PRIMARY) Tok.base.copy(alpha = 0.78f) else Tok.tx2
    Column(
        modifier.fillMaxHeight().heightIn(min = TILE_H).clip(shape).background(fill)
            .then(
                when (emphasis) {
                    // a danger boundary, never a second fill: two filled tiles would read as two endorsements
                    ActionEmphasis.BOUNDARY -> Modifier.border(1.5.dp, Tok.danger, shape)
                    ActionEmphasis.PRIMARY -> Modifier
                    else -> Modifier.border(Metric.hairline, Tok.hair, shape)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapS, vertical = Metric.gapXs),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(label), color = ink, style = TypeRole.action.merge(TightCenter),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        // the scope sublabel is the daemon's `rule` verbatim — absent rule, absent sublabel
        sublabel?.let {
            Text(
                it, color = subInk, style = TypeRole.actionSub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** The disclosure that reveals the session grant. Deliberately not a tile — it must not compete. */
@Composable
private fun MoreOptionsRow(onClick: () -> Unit) {
    Row(
        Modifier.padding(top = Metric.gapXs).fillMaxWidth().clip(RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Metric.touch).padding(horizontal = Metric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.ap_more_options), color = Tok.tx2, style = TypeRole.body)
            Text(
                stringResource(Res.string.ap_more_options_sub), color = Tok.tx2, style = TypeRole.captionMono,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SessionScopeRow(action: ApprovalAction, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = Metric.gapXs).fillMaxWidth().clip(RoundedCornerShape(Metric.radiusS))
            .background(Tok.surface).border(Metric.hairline, Tok.hair, RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = Metric.touch)
            .padding(horizontal = Metric.gap, vertical = Metric.gapS),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.allow_session_option), color = Tok.tx, style = TypeRole.body)
            action.sublabel?.let {
                Text(it, color = Tok.tx2, style = TypeRole.captionMono, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * "Retry safer": the user picks constraint chips (and/or types one) and the agent gets a structured
 * RETRY_SAFER deny it re-plans under. Deliberately an explicit sub-surface — the BASE decision surface has
 * no text field, so nothing there can raise the keyboard under the pinned decisions.
 */
@Composable
internal fun RetrySaferBody(
    picked: MutableList<String>,
    custom: String,
    onCustom: (String) -> Unit,
) {
    val presets = listOf(
        stringResource(Res.string.rs_no_network),
        stringResource(Res.string.rs_read_only),
        stringResource(Res.string.rs_tests_only),
        stringResource(Res.string.rs_stay_workspace),
        stringResource(Res.string.rs_patch_only),
    )
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.retry_safer_title), color = Tok.tx, style = TypeRole.title)
        presets.forEach { c ->
            val on = c in picked
            Row(
                Modifier.padding(top = Metric.gapS).fillMaxWidth().clip(RoundedCornerShape(Metric.radiusS))
                    .background(if (on) Tok.accent.copy(alpha = 0.14f) else Tok.surface)
                    .border(Metric.hairline, if (on) Tok.accent else Tok.hair, RoundedCornerShape(Metric.radiusS))
                    .toggleable(value = on, role = Role.Checkbox) {
                        if (on) picked.remove(c) else picked.add(c)
                    }
                    .heightIn(min = Metric.touch).padding(horizontal = Metric.gap, vertical = Metric.gapS),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
            ) {
                Icon(Icons.Rounded.Check, null, tint = if (on) Tok.accent else Tok.muted, modifier = Modifier.size(14.dp))
                Text(c, color = if (on) Tok.tx else Tok.tx2, style = TypeRole.body)
            }
        }
        BasicTextField(
            value = custom, onValueChange = onCustom, singleLine = true,
            textStyle = TypeRole.body.copy(color = Tok.tx),
            cursorBrush = SolidColor(Tok.accent),
            decorationBox = { inner ->
                Box(
                    Modifier.padding(top = Metric.gapS).fillMaxWidth().clip(RoundedCornerShape(Metric.radiusS))
                        .background(Tok.base).border(Metric.hairline, Tok.hair, RoundedCornerShape(Metric.radiusS))
                        .heightIn(min = Metric.touch).padding(horizontal = Metric.gap, vertical = Metric.gap),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (custom.isEmpty()) Text(stringResource(Res.string.rs_custom_hint), color = Tok.tx2, style = TypeRole.body)
                    inner()
                }
            },
        )
    }
}

/** Pinned pair under [RetrySaferBody]: back out, or send the constraints as a structured deny. */
@Composable
internal fun RetrySaferDecisions(enabled: Boolean, onBack: () -> Unit, onSend: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        DecisionTile(
            Res.string.cancel, ActionEmphasis.NEUTRAL, null, Modifier.weight(1f), onClick = onBack,
        )
        DecisionTile(
            Res.string.retry_safer_send,
            if (enabled) ActionEmphasis.PRIMARY else ActionEmphasis.CAUTION,
            null, Modifier.weight(1f), enabled = enabled,
        ) { onSend() }
    }
}

private fun ApprovalActionId.label(): StringResource = when (this) {
    ApprovalActionId.DENY -> Res.string.deny
    ApprovalActionId.ALLOW_ONCE -> Res.string.allow_once
    ApprovalActionId.RETRY_SAFER -> Res.string.retry_safer
    ApprovalActionId.ALLOW_TASK -> Res.string.allow_for_task
    ApprovalActionId.ALLOW_SESSION -> Res.string.allow_session_option
    ApprovalActionId.ALWAYS_ALLOW -> Res.string.always_allow
}
