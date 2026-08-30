package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.split_pane_close
import dev.ccpocket.app.resources.split_session_ended
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * One split column (issue #311).
 *
 * It renders the real [ChatPane] over a [SidePaneModel], not a second, thinner chat view: a column shows
 * the same stream, the same tool cards, the same composer and the same approval card as the focused
 * conversation, because the alternative is two chat UIs that drift apart on every later change.
 *
 * The model is remembered against [SidePane.paneId] so the column keeps its composer draft and scroll
 * position while it exists, and starts clean when a different session takes the slot.
 */
@Composable
fun SplitPane(base: DesktopModel, pane: SidePane, modifier: Modifier = Modifier) {
    val paneModel = remember(pane.paneId) { SidePaneModel(base, pane) }
    if (pane.gone.value) {
        EndedPane(paneModel, modifier)
        return
    }
    ChatPane(paneModel, modifier)
}

/**
 * A column whose session ended underneath it (daemon-side close, a crash, a machine that went away).
 * Said plainly, with the only useful verb, rather than leaving a blank chat that reads as "still loading".
 *
 * Rendered by the focused chat's own [ChatNotice] rather than a look-alike rebuilt here: the hand-made
 * copy had drifted from it in exactly the ways a second copy always does — no hover feedback on the one
 * clickable thing, and a capsule label missing `tightCenter`.
 */
@Composable
private fun EndedPane(model: DesktopModel, modifier: Modifier = Modifier) {
    ChatNotice(
        title = stringResource(Res.string.split_session_ended),
        modifier = modifier.background(Tok.base), // a column is not opaque on its own
        actionLabel = stringResource(Res.string.split_pane_close),
        onAction = { model.closeThisPane() },
    )
}
