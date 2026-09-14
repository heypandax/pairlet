package dev.ccpocket.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.TimeSource
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * The explanation shown when a managed row whose original record is gone is clicked (issue #360): it is not opened.
 * Removal is two steps — [confirming] says that the native record is untouched and that a later re-import won't keep the
 * old place — then [busy] while the daemon writes, and [error] when it refused, was read-only or didn't answer.
 */
data class UnavailableNoticeUi(
    val title: String,
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val error: ManagedSessionsError? = null,
)

/** How long after the confirmation appears a click on "Remove" is ignored — the second click of a double click. */
const val REMOVE_CONFIRM_ARM_MS: Long = 400

@Composable
fun UnavailableNoticeCard(
    ui: UnavailableNoticeUi,
    onAskRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    // when the confirmation appeared: a click that lands before the guard window is the tail of the click that asked
    // monotonic on purpose: a wall clock stepped backwards would keep the confirm button dead for the gap
    val confirmShownAt = remember(ui.confirming) { TimeSource.Monotonic.markNow() }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(Res.string.managed_sessions_unavailable_open, ui.title), color = Tok.tx, fontSize = fontSize)
        if (ui.confirming) {
            Text(stringResource(Res.string.managed_sessions_remove_confirm_body), color = Tok.tx2, fontSize = fontSize, modifier = Modifier.padding(top = 6.dp))
        }
        ui.error?.let {
            Text(
                stringResource(Res.string.managed_sessions_remove_failed) + " · " + stringResource(it.messageRes()),
                color = Tok.danger, fontSize = fontSize, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                ui.busy -> Text(stringResource(Res.string.managed_sessions_removing), color = Tok.muted, fontSize = fontSize)
                ui.confirming -> {
                    // reversed on purpose: "Remove from list" sat first, so the spot a double click's second press lands
                    // on is "Cancel" here — the destructive verb is never under the finger that just asked for it
                    Action(stringResource(Res.string.managed_sessions_cancel), Tok.tx2, fontSize, bold = false, onCancel)
                    Action(stringResource(Res.string.managed_sessions_remove_confirm), Tok.danger, fontSize, bold = true) {
                        if (confirmShownAt.elapsedNow().inWholeMilliseconds >= REMOVE_CONFIRM_ARM_MS) onConfirmRemove()
                    }
                }
                else -> {
                    Action(stringResource(Res.string.managed_sessions_remove), Tok.accent, fontSize, bold = true, onAskRemove)
                    Action(stringResource(Res.string.managed_sessions_dismiss), Tok.tx2, fontSize, bold = false, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Action(text: String, color: androidx.compose.ui.graphics.Color, fontSize: TextUnit, bold: Boolean, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = fontSize, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp),
    )
}
