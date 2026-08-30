package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 */
@Composable
private fun EndedPane(model: DesktopModel, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Tok.base).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.split_session_ended),
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.split_pane_close),
            color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .background(Tok.surface)
                .clickable { model.closeThisPane() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
