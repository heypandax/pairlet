package dev.ccpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import org.jetbrains.compose.resources.stringResource
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.compact_summary

/** Shared desktop/mobile presentation. The full summary remains available for copying. */
@Composable
fun CompactSummaryCard(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        DisableSelection {
            Text(
                (if (expanded) "▾ " else "▸ ") + stringResource(Res.string.compact_summary),
                color = Tok.muted,
                style = tightCenter(12.sp),
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 8.dp),
            )
        }
        if (expanded) {
            val shown = renderClip(text)
            SelectionContainer { Text(shown, color = Tok.tx2, fontSize = 13.sp) }
            if (shown.length < text.length) TruncatedNote(text.length)
            Row { DisableSelection { CopyChip(text) } }
        }
    }
}
