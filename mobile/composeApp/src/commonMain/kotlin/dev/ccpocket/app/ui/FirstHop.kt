package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.action_back
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import org.jetbrains.compose.resources.stringResource

/**
 * The chrome every Mobile UI 2.0 FIRST-HOP surface shares — the destinations the Projects header opens
 * (Computers, Reviews, Settings) and the pages they push (Supporting Surfaces UI 2.0 · Master v1).
 *
 * One owner, because "back · large title · one factual line" is the thing that makes those three read as the
 * same product as Sessions and Chat. Nothing here has a fixed height: the title and the summary grow with the
 * text, so a long localisation or 200% Dynamic Type makes the header taller instead of clipping it.
 */

/**
 * The standard back affordance: a real 48 dp target that NAMES itself.
 *
 * The chevron is a drawing, not a word — without the merged description a screen reader reads this control
 * out as "‹". The description is on the node that takes the tap, so what you can hear is what you can press.
 */
@Composable
fun BackTarget(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.action_back)
    Box(
        modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(role = Role.Button, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) { Text("‹", color = Tok.accent, fontSize = 26.sp, fontWeight = FontWeight.Light) }
}

/**
 * Back, then the screen's own name, then at most ONE line of facts about it.
 *
 * [summary] is nullable on purpose: a surface that cannot state something true (loading, offline, a state
 * whose counts are not yet real) passes null rather than a placeholder — see the Review Center, where a
 * non-ready state must never inherit the ready state's pending count.
 */
@Composable
fun FirstHopHeader(
    title: String,
    summary: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        BackTarget(onBack, Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = Metric.gutter).padding(top = Metric.gapXs)) {
            Text(title, color = Tok.tx, style = TypeRole.screenTitle)
            if (!summary.isNullOrBlank()) Text(
                summary, color = Tok.tx2, style = TypeRole.preview,
                modifier = Modifier.padding(top = Metric.gapS),
            )
        }
    }
}

/** An uppercase section label. It orders the page; it never shouts. */
@Composable
fun FirstHopSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Tok.tx2, style = TypeRole.label,
        modifier = modifier.padding(top = 22.dp, bottom = Metric.gapS),
    )
}

/**
 * A navigation row of a first-hop landing: title, optional factual subtitle, an optional [trailing] mark, ›.
 *
 * A 56 dp FLOOR rather than a height — at 200% type the row grows and both lines stay whole rather than
 * being cropped to fit.
 */
@Composable
fun FirstHopRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Metric.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tok.tx, style = TypeRole.rowTitle)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, color = Tok.tx2, style = TypeRole.preview,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing()
        Text("›", color = Tok.muted, style = TypeRole.title)
    }
}

/** A full-width secondary target after a list — the "Pair a new computer" shape. Never a second primary. */
@Composable
fun FirstHopWideAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Metric.radius)
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(Tok.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapL, vertical = Metric.gapS),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Tok.tx, style = TypeRole.action) }
}
