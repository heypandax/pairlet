package dev.ccpocket.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.chat_context
import dev.ccpocket.app.resources.chat_context_collapse
import dev.ccpocket.app.resources.chat_context_collapsed
import dev.ccpocket.app.resources.chat_context_expand
import dev.ccpocket.app.resources.chat_context_expanded
import dev.ccpocket.app.resources.chat_session_info
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.st_also_running
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.PulseDot
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.StateMarkGlyph
import dev.ccpocket.app.ui.session.SurfaceState
import dev.ccpocket.app.ui.session.stateColor
import dev.ccpocket.app.ui.session.stateLabel
import org.jetbrains.compose.resources.stringResource

/**
 * Chat's chrome (Mobile UI 2.0 · A Master Core v1 frame 02 / Proofs frame 05).
 *
 * The header keeps session identity, the state block keeps the highest-priority intervention, and the body
 * between them stays the flexible region. Nothing here decides state or invents a fact — the pinned block
 * renders whatever [chatStateUi] selected, and every context line is dropped when its source is absent.
 */

/** One context fact. [onClick] keeps a line that used to BE a control (the machine name → machine
 *  switcher) a control, now that the surrounding row toggles the disclosure instead of navigating.
 *  A line may carry SEVERAL facts joined by [CONTEXT_SEP] — see the grouping note on [ChatHeader]. */
data class ContextLine(val text: String, val onClick: (() -> Unit)? = null, val clickLabel: String? = null)

/** The one separator between context facts, collapsed and expanded alike. */
const val CONTEXT_SEP = " · "

/**
 * The scrolling half of the expanded context.
 *
 * The whole expanded region still costs ~200pt; [Metric.touch] of it now belongs to the pinned Session
 * info row below the scroller, so the facts+path body keeps the rest.
 */
private val ContextBodyMax = 200.dp - Metric.touch

/**
 * Title + context, with the verbose half behind a disclosure.
 *
 * Collapsed, the summary is one wrapping line of whatever is really known. Expanded, a bounded
 * internally-scrolling region shows those same facts plus the FULL workdir beside its own copy target — so
 * the header never permanently owns the viewport (Proofs: collapsing returns ~210pt to the stream at 200%
 * type). Toggling only toggles; it never navigates.
 *
 * Two rules keep the region honest on a standard iPhone. [summary] arrives already GROUPED — the short
 * facts share a line instead of each owning one — because one fact per line plus the path plus the action
 * overflowed the bound with an ordinary session's facts. And Session info is pinned BELOW the scroller,
 * never inside it: an action that scrolls out of a region whose scrollbar nobody can see is an action
 * nobody can find. The quick actions stay in [trailing]; both remain explicitly reachable.
 */
@Composable
fun ChatHeader(
    title: String,
    /** Ordered context facts, already filtered to the ones that exist. Rendered verbatim, never padded. */
    summary: List<ContextLine>,
    workdir: String?,
    expanded: Boolean,
    onToggleContext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSessionInfo: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(Metric.touch).clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = Tok.accent, fontSize = 26.sp, fontWeight = FontWeight.Light) }
            // no fixed-height row: the title leads and is allowed three lines before it may ellipsize
            Text(
                title, color = Tok.tx, style = TypeRole.rowTitle,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 13.dp),
            )
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
        if (summary.isNotEmpty() || !workdir.isNullOrBlank()) {
            val toggleLabel = stringResource(if (expanded) Res.string.chat_context_collapse else Res.string.chat_context_expand)
            // the action says what a tap DOES; the state says where the disclosure is now. A reader who
            // arrives on the row mid-session cannot infer the second from the first, and the drawn chevron
            // carries nothing to a screen reader — so the state is spoken, not only drawn.
            val toggleState = stringResource(if (expanded) Res.string.chat_context_expanded else Res.string.chat_context_collapsed)
            Row(
                Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                    .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggleContext)
                    .semantics { stateDescription = toggleState }
                    .padding(start = Metric.gutter, end = Metric.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (expanded) {
                    Text(
                        stringResource(Res.string.chat_context).uppercase(), color = Tok.tx2,
                        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.9.sp, modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        summary.joinToString(CONTEXT_SEP) { it.text }, color = Tok.tx2, style = TypeRole.body,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = Metric.gapS),
                    )
                }
                Spacer(Modifier.width(Metric.gapS))
                val chevronRotation = animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "chatContextChevron",
                )
                Box(Modifier.size(Metric.touch), contentAlignment = Alignment.Center) {
                    ContextChevronDown(Modifier.size(15.dp).rotate(chevronRotation.value))
                }
            }
            if (expanded) {
                // bounded + internally scrolling: an expanded context must never push the stream away.
                // Only the facts and the path live in here — see [ContextBodyMax] for the budget split.
                Column(
                    Modifier.fillMaxWidth().heightIn(max = ContextBodyMax).verticalScroll(rememberScrollState())
                        .padding(start = Metric.gutter, end = Metric.gapS, bottom = Metric.gapS),
                ) {
                    summary.forEach { line ->
                        val tap = line.onClick
                        Text(
                            line.text, color = Tok.tx2, style = TypeRole.body,
                            modifier = if (tap == null) {
                                Modifier.padding(bottom = 2.dp)
                            } else {
                                Modifier.heightIn(min = Metric.touch)
                                    .clickable(role = Role.Button, onClickLabel = line.clickLabel, onClick = tap)
                                    .wrapContentHeight(Alignment.CenterVertically)
                            },
                        )
                    }
                    // the full path, wrapped, never truncated into a half-truth — with its own copy target
                    if (!workdir.isNullOrBlank()) {
                        dev.ccpocket.app.ui.session.PathWithCopy(
                            workdir,
                            Modifier.padding(top = 6.dp),
                            color = Tok.tx2,
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
                // pinned foot, OUTSIDE the scroller: however tall the facts above grow — 200% type, a long
                // path, an external origin — the way into the full session record stays on screen
                onSessionInfo?.let { open ->
                    Text(
                        stringResource(Res.string.chat_session_info), color = Tok.accent, style = TypeRole.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = Metric.gutter, bottom = Metric.gapS)
                            .heightIn(min = Metric.touch)
                            .clickable(role = Role.Button, onClick = open)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
        }
        Hairline()
    }
}

/**
 * A drawn chevron instead of the typographic `⌄`/`⌃` glyphs. Text glyphs inherit each platform font's
 * baseline and side bearings, which made the disclosure mark look low and detached from the session facts.
 */
@Composable
private fun ContextChevronDown(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.36f)
            lineTo(size.width * 0.50f, size.height * 0.64f)
            lineTo(size.width * 0.78f, size.height * 0.36f)
        }
        drawPath(
            path,
            Tok.tx2,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The pinned state block.
 *
 * Approval/Answer lead; a genuinely streaming turn under them is demoted to a qualifying line, never the
 * headline — and streaming ALONE never reaches this block at all ([chatStateUi] pins nothing for it; the
 * composer note and Stop control own that fact). Deliberately actionless: an open approval already OWNS
 * the screen as a modal Secure Approval sheet and a question already docks its QuestionCard above the
 * composer, so a second "Review"/"Answer" control here would be a second path into a decision that must
 * have exactly one.
 */
@Composable
fun ChatStateBlock(ui: ChatStateUi, modifier: Modifier = Modifier) {
    val tint = stateColor(ui.tone)
    Column(
        modifier.fillMaxWidth().background(Tok.surface)
            .padding(horizontal = Metric.gutter, vertical = Metric.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (ui.state == SurfaceState.RUNNING) PulseDot(tint, size = 8.dp) else StateMarkGlyph(ui.mark, tint)
            // Tok.tx over the tone: the label has to clear contrast in both palettes, and the mark + the
            // block's own tinted rule already carry the colour half of the signal
            Text(
                stateLabel(ui.state), color = Tok.tx, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
        }
        // the request's own title, verbatim — the sheet below still carries tool, payload and evidence
        ui.detail?.let { detail ->
            Text(
                detail, color = Tok.tx2, style = TypeRole.preview,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 17.dp, top = 9.dp),
            )
        }
        // the demoted qualifier — present only because a turn really is still producing tokens
        if (ui.alsoRunning) {
            Row(
                Modifier.padding(start = 17.dp, top = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
            ) {
                PulseDot(Tok.ok, size = 7.dp)
                Text(stringResource(Res.string.st_also_running), color = Tok.tx2, style = TypeRole.caption)
            }
        }
        Hairline(Modifier.padding(top = Metric.gap), color = tint.copy(alpha = 0.32f))
    }
}

/**
 * The quiet source label above an ordinary turn — the structure that tells User, Agent and Tool apart
 * without a permanent timeline rail or a card stack.
 */
@Composable
fun TurnSourceLabel(label: String, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Text(
        label.uppercase(), color = Tok.tx2, fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp,
        modifier = modifier.then(if (alignEnd) Modifier.fillMaxWidth() else Modifier),
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else null,
    )
}

/**
 * A generic tool call: a hairline-bounded band, never a card.
 *
 * The tool token and the literal payload are shown as the daemon sent them — a wrapped long command is
 * strictly better than a short one that misrepresents what will run. [status] renders only when the
 * transcript actually carries an outcome; absence stays absence.
 */
@Composable
fun ToolTurnBand(
    tool: String,
    preview: String,
    status: Boolean?,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    previewSlot: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Column(
            Modifier.fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(Metric.gapS),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tool, color = Tok.tx, style = TypeRole.captionMono, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.raised)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                )
                Spacer(Modifier.weight(1f))
                if (status != null) ToolStatus(status)
            }
            if (previewSlot != null) previewSlot()
            else if (preview.isNotBlank()) {
                // Preserve the transcript's compact scan rhythm: a production tool band owns an expand
                // toggle, so its literal payload wraps within a two-line preview and opens in full on tap.
                // A standalone band with no toggle must never hide unreachable content.
                val showFullPayload = expanded || onToggle == null
                Text(
                    preview, color = Tok.tx2, style = TypeRole.bodyMono,
                    maxLines = if (showFullPayload) Int.MAX_VALUE else 2,
                    overflow = if (showFullPayload) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
        }
        Hairline()
    }
}

/** Done / Failed from the transcript's real `ok`. Never a synthesized count or a green "probably fine". */
@Composable
private fun ToolStatus(ok: Boolean) {
    val tint = if (ok) Tok.ok else Tok.danger
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateMarkGlyph(if (ok) dev.ccpocket.app.ui.session.StateMark.DOT else dev.ccpocket.app.ui.session.StateMark.SQUARE, tint, 7.dp)
        Text(
            stringResource(if (ok) Res.string.done else Res.string.chat_tool_failed),
            color = Tok.tx2, style = TypeRole.captionMono, fontWeight = FontWeight.Medium,
        )
    }
}
