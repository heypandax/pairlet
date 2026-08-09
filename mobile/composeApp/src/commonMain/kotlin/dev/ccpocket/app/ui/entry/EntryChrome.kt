package dev.ccpocket.app.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.copy_path
import dev.ccpocket.app.resources.pair_copied
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.StateMarkGlyph
import dev.ccpocket.app.ui.session.stateColor
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Shared chrome for the entry flow (Entry Flow UI 2.0 · Master v1).
 *
 * The same low-container grammar the Sessions/Chat/Approval slices already ship: hierarchy from type,
 * hairlines and spacing rather than nested cards. Nothing here has a fixed height — every consequential
 * target declares a 48 dp FLOOR and grows with the text, so a long localisation or 200% Dynamic Type makes
 * a control taller instead of clipping its label.
 */

/** The screen's own name plus one explanatory line. One per surface; nothing else competes with it. */
@Composable
fun EntryTitle(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(title, color = Tok.tx, style = TypeRole.screenTitle)
        if (!body.isNullOrBlank()) Text(
            body, color = Tok.tx2, style = TypeRole.preview,
            modifier = Modifier.padding(top = Metric.gapS),
        )
    }
}

/** An uppercase field/section label. It orders the page; it never shouts. */
@Composable
fun EntryLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Tok.tx2, style = TypeRole.label, modifier = modifier)
}

/**
 * THE filled action of a surface — at most one is on screen at a time.
 *
 * [caption] prints the exact combination the tap will run (design: "Start session · cc-pocket · Claude ·
 * Default"), so the commitment is legible before it is made rather than implied by a highlighted row.
 */
@Composable
fun EntryPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
    tint: Color = Tok.accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metric.radius)
    Column(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .background(if (enabled) tint else Tok.surface)
            .then(if (enabled) Modifier else Modifier.border(Metric.hairline, Tok.hair, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapL, vertical = Metric.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label, color = if (enabled) Tok.base else Tok.muted,
            style = TypeRole.title.copy(fontSize = TypeRole.action.fontSize), textAlign = TextAlign.Center,
        )
        if (!caption.isNullOrBlank()) Text(
            caption, color = if (enabled) Tok.base.copy(alpha = 0.82f) else Tok.muted,
            style = TypeRole.captionMono, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Metric.gapXs),
        )
    }
}

/** A secondary route: hairline, never filled — it must not read as a second primary. */
@Composable
fun EntrySecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Tok.tx,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metric.radius)
    Box(
        modifier.fillMaxWidth().heightIn(min = Metric.touch).clip(shape)
            .border(Metric.hairline, Tok.tx2.copy(alpha = 0.45f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapL, vertical = Metric.gapS),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = tint, style = TypeRole.action, textAlign = TextAlign.Center) }
}

/** The quietest route of all — a disclosure or an alternative path, at a real 48 dp target. */
@Composable
fun EntryQuietAction(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Tok.tx2,
    onClick: () -> Unit,
) {
    Box(
        modifier.heightIn(min = Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapS, vertical = Metric.gap),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, color = color, style = TypeRole.body) }
}

/**
 * A written state block: mark + state sentence + explanation + its own actions.
 *
 * Used for every connection failure and for the honest backend statements. The mark carries the state in
 * greyscale, the sentence carries it in words, and colour only confirms what both already said.
 */
@Composable
fun EntryStateBlock(
    recovery: ConnRecoveryUi,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val tint = stateColor(recovery.tone)
    val shape = RoundedCornerShape(Metric.radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.08f))
            .border(Metric.hairline, tint.copy(alpha = 0.38f), shape).padding(Metric.gapL),
        verticalArrangement = Arrangement.spacedBy(Metric.gap),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                StateMarkGlyph(recovery.mark, tint)
                Text(title, color = Tok.tx, style = TypeRole.rowTitle, modifier = Modifier.weight(1f))
            }
            Text(body, color = Tok.tx2, style = TypeRole.preview, modifier = Modifier.padding(top = Metric.gapS))
            if (!hint.isNullOrBlank()) Text(
                hint, color = Tok.muted, style = TypeRole.caption, modifier = Modifier.padding(top = Metric.gapXs),
            )
        }
        actions()
    }
}

/**
 * The exact desktop command, mono and verbatim, beside a real 48 dp copy target — technical help that can
 * actually be USED. The command WRAPS: a half-command is worse than a tall row.
 */
@Composable
fun CopyableCommand(command: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val label = stringResource(Res.string.copy_path)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    val shape = RoundedCornerShape(Metric.radiusS)
    Row(
        modifier.fillMaxWidth().heightIn(min = Metric.touch).clip(shape).background(Tok.surface)
            .border(Metric.hairline, Tok.hair, shape).padding(start = Metric.gap, end = Metric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(command, color = Tok.tx, style = TypeRole.bodyMono, modifier = Modifier.weight(1f))
        if (copied) Text(
            stringResource(Res.string.pair_copied), color = Tok.ok, style = TypeRole.caption,
            modifier = Modifier.padding(end = Metric.gapS),
        )
        Box(
            Modifier.size(Metric.touch).clip(shape)
                .clickable(role = Role.Button, onClickLabel = label) {
                    clipboard.setText(AnnotatedString(command)); copied = true
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ContentCopy, label, tint = Tok.tx2, modifier = Modifier.size(16.dp)) }
    }
}

/**
 * One row of the quiet alternatives band: a hairline above, a 48 dp floor, a ▾/▴ when it discloses.
 *
 * Opening a disclosure is never an action with consequences — it only reveals the route.
 */
@Composable
fun EntryRouteRow(
    label: String,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    trailing: String = "›",
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Tok.tx, style = TypeRole.body, modifier = Modifier.weight(1f))
            Text(
                expanded?.let { if (it) "▴" else "▾" } ?: trailing,
                color = Tok.muted, style = TypeRole.body,
            )
        }
    }
}

/** A one-line note under a control: real, small, and never the only carrier of a state. */
@Composable
fun EntryNote(text: String, modifier: Modifier = Modifier, color: Color = Tok.muted) {
    Text(text, color = color, style = TypeRole.caption, modifier = modifier)
}

/** The section heading inside a bounded surface — smaller than a screen title, louder than a label. */
@Composable
fun EntrySheetTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, color = Tok.tx, style = TypeRole.title, modifier = modifier)
}

/**
 * A selected/unselected check column, so a selection reads as a selection and not as a button.
 *
 * Reserves a WIDTH, never a height: at 200% type a fixed 22 dp box clips the glyph in half, and a
 * half-drawn check is exactly the kind of state-by-appearance-only this system refuses.
 */
@Composable
fun EntryCheckMark(selected: Boolean, tint: Color = Tok.accent) {
    Box(Modifier.widthIn(min = 22.dp), contentAlignment = Alignment.Center) {
        if (selected) Text("✓", color = tint, style = TypeRole.action.copy(fontWeight = FontWeight.Bold))
    }
}
