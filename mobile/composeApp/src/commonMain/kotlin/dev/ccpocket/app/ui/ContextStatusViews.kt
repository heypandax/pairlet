package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_status_no_data
import dev.ccpocket.app.resources.context_status_no_data_detail
import dev.ccpocket.app.resources.context_status_used_pending
import dev.ccpocket.app.resources.context_status_window_override
import dev.ccpocket.app.resources.context_status_window_unknown
import dev.ccpocket.app.resources.label_context
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import org.jetbrains.compose.resources.stringResource

/**
 * Issue #320-A: the context block both shells show — the mobile session sheet inline, the desktop header
 * popover inside its card. Label + numbers on one row, the occupancy bar, then any caveats from
 * [ContextStatusUi.notes] as wrapping text (no maxLines: it has to stay readable at large font scales).
 *
 * The bar only fills when both facts exist; every other shape keeps the empty track, so a missing value
 * can never be drawn as a 0% or a 100% bar.
 */
@Composable
fun ContextStatusPanel(status: ContextStatusUi, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // label 11sp beside a 12sp mono readout: different sizes on one row → both tightCenter (AGENTS.md)
            Text(
                stringResource(Res.string.label_context), color = Tok.muted, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, style = tightCenter(11.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                contextStatusReadout(status), color = Tok.tx2, fontFamily = FontFamily.Monospace,
                fontSize = 12.sp, style = tightCenter(12.sp), maxLines = 1,
            )
        }
        val frac = status.fraction ?: 0f
        Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tok.hair)) {
            if (frac > 0f) {
                Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(contextColor(frac)))
            }
        }
        ContextStatusNotes(status, Modifier.padding(top = 8.dp))
    }
}

/** The caveat lines alone, for a surface that already draws its own numbers. Renders nothing when there are none. */
@Composable
fun ContextStatusNotes(status: ContextStatusUi, modifier: Modifier = Modifier) {
    val notes = status.notes
    if (notes.isEmpty()) return
    Column(modifier) {
        notes.forEachIndexed { i, note ->
            val top = if (i == 0) 0.dp else 4.dp
            when (note) {
                ContextStatusNote.NO_DATA -> {
                    Text(
                        stringResource(Res.string.context_status_no_data), color = Tok.tx, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = top),
                    )
                    Text(
                        stringResource(Res.string.context_status_no_data_detail), color = Tok.muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ContextStatusNote.WINDOW_UNKNOWN -> NoteLine(stringResource(Res.string.context_status_window_unknown), top)
                ContextStatusNote.USED_PENDING -> NoteLine(stringResource(Res.string.context_status_used_pending), top)
                ContextStatusNote.WINDOW_USER_OVERRIDE -> NoteLine(stringResource(Res.string.context_status_window_override), top)
            }
        }
    }
}

@Composable
private fun NoteLine(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(text, color = Tok.muted, fontSize = 12.sp, modifier = Modifier.padding(top = top))
}
