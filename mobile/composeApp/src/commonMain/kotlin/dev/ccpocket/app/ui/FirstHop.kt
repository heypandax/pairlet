package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.action_back
import dev.ccpocket.app.resources.settings_connected_to
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
 * It is the ONLY top-left back on the phone — pushed pages, sheet pages and flows alike — and every host puts
 * it 4 dp from the screen's leading edge, so the chevron sits on the same line wherever it appears. Nothing
 * rides next to it: the page's own title says where you are, and the chevron only ever means "back".
 *
 * The chevron is DRAWN, centred on the target — not the "‹" character it replaces. A glyph's ink sits where its
 * font puts it: SF Pro's "‹" landed 2.6 dp below the line's centre, so beside any title the back read as
 * dropped by 2–3 dp, and no line-height setting reaches the ink. A path has no font metrics to disagree with.
 *
 * Nothing here is a word, so without the merged description a screen reader would have nothing to say. The
 * description is on the node that takes the tap, so what you can hear is what you can press. [description]
 * replaces the generic "Back" only where the destination adds something worth hearing.
 */
@Composable
fun BackTarget(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val label = description ?: stringResource(Res.string.action_back)
    val tint = if (enabled) Tok.accent else Tok.muted
    Box(
        modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(BackChevronWidth, BackChevronHeight)) {
            val stroke = BackChevronStroke.toPx()
            val inset = stroke / 2
            drawPath(
                Path().apply {
                    moveTo(size.width - inset, inset)
                    lineTo(inset, size.height / 2)
                    lineTo(size.width - inset, size.height - inset)
                },
                tint,
                style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

// matched to the SF Pro Light 26 sp "‹" it replaced (weight, angle, size): round caps need a touch more height
// than that glyph's flat-cut ends to cover the same ink
private val BackChevronWidth = 6.5.dp
private val BackChevronHeight = 11.5.dp
private val BackChevronStroke = 1.8.dp

/**
 * Back, then the screen's own name, then at most ONE line of facts about it.
 *
 * [summary] is nullable on purpose: a surface that cannot state something true (loading, offline, a state
 * whose counts are not yet real) passes null rather than a placeholder: a non-ready state must never
 * inherit the ready state's counts.
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

/** The one factual line the Settings family of surfaces may claim: which computer it is talking to. */
@Composable
fun connectedToSummary(repo: PocketRepository): String? =
    repo.paired.value?.displayName()?.let { stringResource(Res.string.settings_connected_to, it) }

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
 * A [minHeight] FLOOR rather than a height — at 200% type the row grows and both lines stay whole rather
 * than being cropped to fit. [horizontalPadding] insets the CONTENT of a row that sits inside a container
 * (UI 2.1 Settings categories) while the tap target still spans the container's full width; a bare row on
 * the base surface keeps the page gutter and passes 0.
 */
@Composable
fun FirstHopRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    minHeight: Dp = 56.dp,
    horizontalPadding: Dp = 0.dp,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = Metric.gap),
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
