package dev.ccpocket.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.tool_process_unknown_one
import dev.ccpocket.app.resources.chat_copy_message
import dev.ccpocket.app.resources.chat_copy_message_done
import dev.ccpocket.app.resources.chat_copy_message_failed
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
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.BackTarget
import dev.ccpocket.app.ui.CopyOutcome
import dev.ccpocket.app.ui.PulseDot
import dev.ccpocket.app.ui.rememberCopyOutcome
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
data class ContextLine(
    val text: String,
    val onClick: (() -> Unit)? = null,
    val clickLabel: String? = null,
    /** Chat Rhythm v1: what this line contributes to the COLLAPSED summary when it differs from [text] — the
     *  phone keeps the model out of the header (the composer's chip names it) while the expanded line still
     *  states it. Null = the same text collapsed and expanded. */
    val collapsedText: String? = null,
)

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
    /** false: the expanded facts are NOT laid out under the toggle — the host draws [ChatContextPanel] as an
     *  overlay over its stream instead (Chat Rhythm v1 phone), so a small screen never squeezes the transcript. */
    panelInline: Boolean = true,
    /** Lines the collapsed summary may take. The phone passes 1 (Chat Rhythm v1): the full facts are one tap
     *  away in the panel, so a folder name split onto a second line only cost the transcript a row. */
    summaryMaxLines: Int = 2,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            BackTarget(onBack)
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
                        summary.joinToString(CONTEXT_SEP) { it.collapsedText ?: it.text }, color = Tok.tx2, style = TypeRole.body,
                        maxLines = summaryMaxLines, overflow = TextOverflow.Ellipsis,
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
            if (expanded && panelInline) ContextPanelBody(summary, workdir, onSessionInfo)
        }
        Hairline()
    }
}

/**
 * The expanded context as an overlay (Chat Rhythm v1, phone): the same facts, full path and pinned Session info
 * as [ChatHeader]'s inline region, on a raised sheet the host places over the top of its stream. Covering part of
 * the transcript for a moment is the trade: laid out inline, a 320pt screen at large type had the header, the
 * region and the composer leave the transcript almost no height. The facts scroll inside the sheet; the sheet
 * itself is bounded by whatever height the host gives it, and Session info stays pinned below the scroller.
 */
@Composable
fun ChatContextPanel(
    summary: List<ContextLine>,
    workdir: String?,
    modifier: Modifier = Modifier,
    onSessionInfo: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth()
            .shadow(8.dp)
            .background(Tok.base)
            .testTag(CHAT_CONTEXT_PANEL_TAG),
    ) {
        ContextPanelBody(summary, workdir, onSessionInfo, shrinkable = true)
        Hairline()
    }
}

const val CHAT_CONTEXT_PANEL_TAG = "chat-context-panel"

/** The facts + path scroller and the pinned Session info foot, shared by the inline region and the overlay.
 *  [shrinkable]: the scroller yields height to the foot when the host's bound is tighter than [ContextBodyMax]. */
@Composable
private fun ColumnScope.ContextPanelBody(
    summary: List<ContextLine>,
    workdir: String?,
    onSessionInfo: (() -> Unit)?,
    shrinkable: Boolean = false,
) {
    // bounded + internally scrolling: an expanded context must never push the stream away.
    // Only the facts and the path live in here — see [ContextBodyMax] for the budget split.
    Column(
        Modifier.fillMaxWidth().then(if (shrinkable) Modifier.weight(1f, fill = false) else Modifier)
            .heightIn(max = ContextBodyMax).verticalScroll(rememberScrollState())
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
fun TurnSourceLabel(label: String, modifier: Modifier = Modifier, alignEnd: Boolean = false, color: Color = Tok.tx2) {
    Text(
        label.uppercase(), color = color, fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp,
        modifier = modifier.then(if (alignEnd) Modifier.fillMaxWidth() else Modifier),
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else null,
    )
}

/** Chat Roles v1 source row. Callers align it to the trailing edge without forcing short bubbles wide. */
@Composable
fun UserTurnSourceLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(), color = Tok.userTurnLabel, fontSize = 11.sp, lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp,
        textAlign = TextAlign.End, modifier = modifier,
    )
}

const val USER_TURN_CONTAINER_TAG = "user-turn-container"

/** Chat Roles v1 (#397): neutral raised surface and a closed hairline; Agent prose keeps the base.
 *  Shared by mobile and both desktop alignment preferences. Geometry and content behavior stay intact. */
@Composable
fun UserTurnContainer(
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 10.dp,
    /** Chat Rhythm v1 phone: the source row's copy target reaches above the container into the gap before the
     *  turn, so the phone draws the rounded fill and hairline WITHOUT clipping (the padding keeps content off
     *  the corners either way). Desktop keeps the clip. */
    clipContent: Boolean = true,
    topPadding: Dp = verticalPadding,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    // The source label and message body are separate children on mobile. Give each its own
    // vertical space; a Box would paint the first body row over the label.
    Column(
        modifier
            .testTag(USER_TURN_CONTAINER_TAG)
            .then(if (clipContent) Modifier.clip(shape) else Modifier)
            .background(Tok.userTurnBg, shape)
            .border(1.dp, Tok.userTurnBorder, shape)
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = verticalPadding),
    ) { content() }
}

/** Test handles for the Chat Rhythm v1 source row and its whole-turn copy target. */
const val TURN_SOURCE_ROW_TAG = "turn-source-row"
const val TURN_COPY_TAG = "turn-copy"

/** The source row's visual height (Chat Rhythm v1). The body starts [TURN_SOURCE_BODY_GAP] below it. */
val TURN_SOURCE_ROW_HEIGHT = 28.dp
val TURN_SOURCE_BODY_GAP = 2.dp

/** The copy glyph's visual disc and the touch square around it. */
private val TurnCopySlot = 28.dp
private val TurnCopyTarget = 44.dp

/**
 * Chat Rhythm v1 source row: the turn's source label and — at its trailing end — the one-tap copy of the WHOLE
 * turn. It replaces the old 复制 line under the body, which cost every message a row of its own.
 *
 * [copyText] is the turn's raw text (Markdown included) exactly as received so far; null when there is nothing to
 * copy. [user] switches to the user-turn grammar (mono, trailing-aligned label on the neutral container). Both
 * labels sit beside a geometric control, so they use [tightCenter] (AGENTS.md).
 */
@Composable
fun TurnSourceRow(
    label: String,
    copyText: String?,
    modifier: Modifier = Modifier,
    user: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = TURN_SOURCE_ROW_HEIGHT).testTag(TURN_SOURCE_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (user) {
            Text(
                label.uppercase(), color = Tok.userTurnLabel, fontSize = 11.sp, style = tightCenter(11.sp),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                label.uppercase(), color = Tok.tx2, fontSize = 11.sp, style = tightCenter(11.sp),
                fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        if (copyText != null) {
            Spacer(Modifier.width(4.dp))
            TurnCopyButton(copyText)
        }
    }
}

/**
 * The whole-turn copy: a 16dp glyph on a 28dp disc (shown only while pressed/hovered), resting in tx2.
 *
 * The TOUCH square is 44 × 44 but the row only budgets the 28dp disc: the square shares the disc's bottom-trailing
 * corner and grows UP and toward the label, never down or outward — so it ends on the source row's bottom edge and
 * cannot reach the body's first line 2dp below, where a long press has to start a text selection, and it never leaves
 * the text column (the list clips at its sides). Toward the label it only covers the label's inert tail. The rows
 * above a source row leave at least 16dp of gap for the part that rises out of it (see the phone list's spacing and
 * its top content padding), so it covers no other control either.
 *
 * Feedback swaps the glyph in place (✓ in ok / ✕ in danger) — nothing appears beside it, so nothing moves. The ✓
 * is shown only when the clipboard write returned; a write that throws says it failed instead.
 */
@Composable
fun TurnCopyButton(text: String, modifier: Modifier = Modifier) {
    // the click reads the text at TAP time: a streaming turn copies everything received so far
    val latest by rememberUpdatedState(text)
    val (outcome, copy) = rememberCopyOutcome()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val description = stringResource(
        when (outcome) {
            CopyOutcome.IDLE -> Res.string.chat_copy_message
            CopyOutcome.COPIED -> Res.string.chat_copy_message_done
            CopyOutcome.FAILED -> Res.string.chat_copy_message_failed
        },
    )
    Box(
        modifier.turnCopyTarget()
            .testTag(TURN_COPY_TAG)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { copy(latest) }
            .semantics {
                contentDescription = description
                if (outcome != CopyOutcome.IDLE) liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            Modifier.size(TurnCopySlot).clip(CircleShape).background(if (pressed || hovered) Tok.raised else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (outcome) {
                    CopyOutcome.IDLE -> Icons.Rounded.ContentCopy
                    CopyOutcome.COPIED -> Icons.Rounded.Check
                    CopyOutcome.FAILED -> Icons.Rounded.Close
                },
                contentDescription = null,
                tint = when (outcome) {
                    CopyOutcome.IDLE -> Tok.tx2
                    CopyOutcome.COPIED -> Tok.ok
                    CopyOutcome.FAILED -> Tok.danger
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Reports the 28dp slot to the row but lays out (and hit-tests) a 44dp square sharing the slot's bottom-end corner. */
private fun Modifier.turnCopyTarget() = layout { measurable, _ ->
    val slot = TurnCopySlot.roundToPx()
    val hit = TurnCopyTarget.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(hit, hit))
    layout(slot, slot) { placeable.placeRelative(slot - hit, slot - hit) }
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
    /** Rendered INSIDE the band, under the payload (issue #332: the pictures the result returned).
     *  A slot rather than an images parameter so the band keeps knowing nothing about image decoding —
     *  and so it stays exactly as it was for the ~all tools that return only text. */
    footerSlot: (@Composable () -> Unit)? = null,
    /** false inside a process fold's card (Tool Process Live v1): the card's own hairlines and padding
     *  bound the member, so the band drops its rules and vertical padding. */
    framed: Boolean = true,
    /** With no [status]: the fold's verdict that this call's outcome will never arrive — said as "No result",
     *  the same words the fold's header counts it under. Never set for a call that may still be running. */
    unknownOutcome: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        if (framed) Hairline()
        Column(
            Modifier.fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(vertical = if (framed) 11.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(Metric.gapS),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tool, color = Tok.tx, style = TypeRole.captionMono, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.raised)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                )
                Spacer(Modifier.weight(1f))
                if (status != null) ToolStatus(status) else if (unknownOutcome) UnknownOutcome()
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
            footerSlot?.invoke()
        }
        if (framed) Hairline()
    }
}

/** ○ No result — a call whose outcome never arrived (Tool Process Live v1), in the fold header's words. */
@Composable
private fun UnknownOutcome() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateRing(Tok.muted)
        Text(
            stringResource(Res.string.tool_process_unknown_one),
            color = Tok.tx2, style = TypeRole.captionMono, fontWeight = FontWeight.Medium,
        )
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
