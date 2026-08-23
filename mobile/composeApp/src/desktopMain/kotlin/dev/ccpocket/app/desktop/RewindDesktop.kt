package dev.ccpocket.app.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rewind_menu_blocked
import dev.ccpocket.app.resources.rewind_menu_fork
import dev.ccpocket.app.resources.rewind_menu_rewind
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.RewindConfirmBody
import dev.ccpocket.protocol.RewindMode
import org.jetbrains.compose.resources.stringResource

/**
 * Desktop half of session rewind / fork (issue #282). The BODIES are shared with mobile
 * (`ui/chat/RewindUi.kt`); what differs is only how they are summoned — a right-click context menu
 * instead of a long press, a centered popup instead of a bottom sheet.
 */

/** What a user turn's context menu can do. Null at the call site = build no menu at all. */
internal class RewindEntries(
    val enabled: Boolean,
    val onPick: (ChatItem.User, String) -> Unit,
)

/**
 * Wrap a user turn in its rewind/fork context menu, mirroring the sidebar row's own [ContextMenuArea].
 *
 * When [entries] is null the content is emitted BARE — not wrapped in an empty menu area — so a row the
 * daemon gave no coordinates behaves exactly as it did before this feature existed, right-click
 * included. A blocked turn keeps its entries and adds the reason as a third, inert row: the desktop
 * menu has no styling for a greyed item, so the reason line carries that job instead.
 */
@Composable
internal fun RewindMenuArea(item: ChatItem.User, entries: RewindEntries?, content: @Composable () -> Unit) {
    if (entries == null) return content()
    val rewind = stringResource(Res.string.rewind_menu_rewind)
    val fork = stringResource(Res.string.rewind_menu_fork)
    val blocked = stringResource(Res.string.rewind_menu_blocked)
    ContextMenuArea(
        items = {
            buildList {
                if (entries.enabled) {
                    add(ContextMenuItem(rewind) { entries.onPick(item, RewindMode.REWIND) })
                    add(ContextMenuItem(fork) { entries.onPick(item, RewindMode.FORK) })
                } else {
                    add(ContextMenuItem(blocked) { })
                }
            }
        },
        content = content,
    )
}

/**
 * The confirmation, as a focusable centered popup over a scrim — the desktop's equivalent of the phone's
 * bottom sheet, following [RemoteDirPickerPopup]'s shape. Dismiss-on-scrim only: the confirm button
 * itself stays inside the shared body, so the two platforms cannot drift on when it is armed.
 */
@Composable
internal fun RewindConfirmPopup(model: DesktopModel) {
    val sheet = model.rewindSheet ?: return
    Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = true), onDismissRequest = { model.cancelRewind() }) {
        Box(Modifier.fillMaxSize().background(Tok.base.copy(alpha = 0.66f)).clickable(onClick = { model.cancelRewind() }), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(16.dp))
                    // swallow clicks so a tap inside the card doesn't hit the scrim behind it
                    .clickable(enabled = false, onClick = {})
                    .padding(top = 8.dp),
            ) {
                RewindConfirmBody(sheet, onCancel = { model.cancelRewind() }, onConfirm = { model.confirmRewind() })
            }
        }
    }
}
