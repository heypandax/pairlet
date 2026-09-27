package dev.ccpocket.app.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ChatRow
import dev.ccpocket.app.data.StepState
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.thinking_streaming
import dev.ccpocket.app.resources.thought_for
import dev.ccpocket.app.resources.tool_process_a11y_failed
import dev.ccpocket.app.resources.tool_process_a11y_waiting
import dev.ccpocket.app.resources.tool_process_autoruns
import dev.ccpocket.app.resources.tool_process_collapse
import dev.ccpocket.app.resources.tool_process_collapse_one
import dev.ccpocket.app.resources.tool_process_expand
import dev.ccpocket.app.resources.tool_process_expand_one
import dev.ccpocket.app.resources.tool_process_failed
import dev.ccpocket.app.resources.tool_process_running
import dev.ccpocket.app.resources.tool_process_unknown
import dev.ccpocket.app.resources.tool_process_unknown_one
import dev.ccpocket.app.resources.tool_process_waiting
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.LiveLineState
import dev.ccpocket.app.ui.chat.StateRing
import dev.ccpocket.app.ui.chat.StateSquare
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_LIVE_TAG
import dev.ccpocket.app.ui.chat.capWidth
import dev.ccpocket.app.ui.chat.chipToken
import dev.ccpocket.app.data.ToolTarget
import dev.ccpocket.app.ui.chat.targetOverflow
import dev.ccpocket.app.ui.chat.processSegment
import dev.ccpocket.app.ui.chat.processSummaryLabel
import dev.ccpocket.app.ui.chat.rememberStepElapsed
import dev.ccpocket.app.ui.chat.singleThoughtOf
import dev.ccpocket.app.ui.chat.singleToolOf
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * Tool Process Live v1 on the desktop pane — the phone's card, rows and live line at desktop metrics: header
 * 40dp, members 38dp, live line 36dp, 18dp between stream rows. The live line's pulse sits in the members'
 * status-dot column (33dp in), so a step that finishes turns from pulse to ● without moving sideways. Header
 * and member rows lift to raised on hover (120ms); the live line is not a target of its own.
 */

private const val TNUM = "tnum"

@Composable
internal fun DesktopProcessHeader(
    group: ChatRow.ProcessGroup,
    items: List<ChatItem>,
    liveLine: LiveLineState?,
    onToggle: () -> Unit,
    /** The session's working directory — a settled single step shows its path relative to it. */
    cwd: String? = null,
) {
    val single = singleToolOf(group, items)
    val thought = singleThoughtOf(group, items)
    val summary = group.summary
    val action = stringResource(
        when {
            single != null && group.expanded -> Res.string.tool_process_collapse_one
            single != null -> Res.string.tool_process_expand_one
            group.expanded -> Res.string.tool_process_collapse
            else -> Res.string.tool_process_expand
        },
    )
    val state = if (group.live != null) stringResource(Res.string.tool_process_running, action) else action
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) Tok.raised else Tok.raised.copy(alpha = 0f), tween(120))
    val caret by animateFloatAsState(if (group.expanded) 90f else 0f, tween(150, easing = FastOutSlowInEasing))
    Column(Modifier.fillMaxWidth().processSegment(top = true, bottom = !group.hasMemberRows, fill = Tok.surface, line = Tok.hair)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(TOOL_PROCESS_GROUP_TAG)
                .hoverable(hover).background(fill)
                .clickable(onClick = onToggle)
                .semantics { stateDescription = state; onClick(label = action) { onToggle(); true } }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(9.dp), contentAlignment = Alignment.Center) {
                Text("▸", color = Tok.muted, fontSize = 10.sp, style = tightCenter(10.sp), modifier = Modifier.rotate(caret))
            }
            if (single != null) {
                Text(
                    single.tool, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = tightCenter(13.sp), modifier = Modifier.capWidth(0.4f),
                )
                val target = ToolTarget.of(single.preview, cwd)
                Text(
                    target, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.5.sp,
                    maxLines = 1, overflow = targetOverflow(target), style = tightCenter(12.5.sp), modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    thought?.seconds?.let { stringResource(Res.string.thought_for, it) } ?: processSummaryLabel(summary),
                    color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = tightCenter(13.sp).copy(fontFeatureSettings = TNUM), modifier = Modifier.weight(1f),
                )
            }
            if (summary.failed > 0) {
                DesktopFoldMarker(
                    if (single != null) stringResource(Res.string.chat_tool_failed)
                    else pluralStringResource(Res.plurals.tool_process_failed, summary.failed, summary.failed),
                    Tok.danger,
                ) { StateSquare(Tok.danger) }
            }
            if (summary.unknown > 0) {
                DesktopFoldMarker(
                    if (single != null) stringResource(Res.string.tool_process_unknown_one)
                    else pluralStringResource(Res.plurals.tool_process_unknown, summary.unknown, summary.unknown),
                    Tok.tx2,
                ) { StateRing(Tok.muted) }
            }
            if (summary.autoRuns > 0) {
                DesktopFoldMarker(pluralStringResource(Res.plurals.tool_process_autoruns, summary.autoRuns, summary.autoRuns), Tok.tx2) {
                    Text("⚡", fontSize = 10.5.sp, style = tightCenter(10.5.sp))
                }
            }
            Text(
                action, color = if (hovered) Tok.tx2 else Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                maxLines = 1, style = tightCenter(12.sp),
            )
        }
        if (liveLine != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            DesktopProcessLiveLine(liveLine)
        }
    }
}

/** A finished member of an opened fold — the flat row inside the card; the last closes the card and, in a live
 *  fold, carries the live line. Anything that is neither a tool nor a thought (a grant's audit chip) renders
 *  through [other], the stream's own renderer for it. */
@Composable
internal fun DesktopProcessMember(
    item: ChatItem,
    last: Boolean,
    liveLine: LiveLineState?,
    step: StepState?,
    other: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().processSegment(top = false, bottom = last, fill = Tok.surface, line = Tok.hair)) {
        when (item) {
            is ChatItem.Tool -> ToolRow(
                item.tool, item.preview,
                when (item.ok) {
                    true -> ToolStatus.OK
                    false -> ToolStatus.FAIL
                    null -> ToolStatus.UNKNOWN
                },
                output = item.output,
                images = item.images,
                imagesTruncated = item.imagesTruncated,
                // the fold's own verdict, so the row and the header can never disagree
                blockStep = step ?: when (item.ok) {
                    true -> StepState.DONE
                    false -> StepState.FAILED
                    null -> StepState.UNKNOWN
                },
            )
            is ChatItem.Thinking -> Text(
                item.seconds?.let { stringResource(Res.string.thought_for, it) } ?: stringResource(Res.string.thinking_streaming),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                style = tightCenter(13.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(start = 50.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            )
            else -> Box(Modifier.fillMaxWidth().padding(start = 33.dp, end = 14.dp, top = 7.dp, bottom = 7.dp)) { other() }
        }
        if (liveLine != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            DesktopProcessLiveLine(liveLine)
        }
    }
}

/** The live line at desktop metrics: pulse (or the finished step's outcome mark / the waiting mark) in the dot
 *  column, the bold tool name, the target and the clock or outcome word — one 36dp line in every state. */
@Composable
internal fun DesktopProcessLiveLine(state: LiveLineState) {
    val failed = state.kind == LiveLineState.Kind.FINISHED && state.outcome == StepState.FAILED
    val a11y = when {
        state.kind == LiveLineState.Kind.WAITING -> stringResource(Res.string.tool_process_a11y_waiting, state.tool.orEmpty(), state.target)
        failed -> stringResource(Res.string.tool_process_a11y_failed, state.tool.orEmpty(), state.target)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().height(36.dp).testTag(TOOL_PROCESS_LIVE_TAG)
            .semantics(mergeDescendants = true) {
                if (a11y != null) {
                    contentDescription = a11y
                    liveRegion = LiveRegionMode.Polite
                } else {
                    hideFromAccessibility()
                }
            }
            .padding(start = 33.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.kind == LiveLineState.Kind.WAITING -> StateSquare(Tok.warn)
            state.kind == LiveLineState.Kind.FINISHED -> when (state.outcome) {
                StepState.FAILED -> StateSquare(Tok.danger)
                StepState.UNKNOWN -> StateRing(Tok.muted)
                StepState.DONE -> Dot(Tok.ok, 7.dp)
                else -> Dot(Tok.muted, 7.dp)
            }
            else -> PulseDot(Tok.accent, 7.dp)
        }
        val quiet = state.kind == LiveLineState.Kind.THINKING || state.kind == LiveLineState.Kind.IDLE
        if (!quiet && state.tool != null) {
            Text(
                chipToken(state.tool), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = tightCenter(13.sp), modifier = Modifier.capWidth(0.4f),
            )
        }
        if (!quiet && state.more != null) {
            Text(
                state.more, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, style = tightCenter(12.sp),
            )
        }
        Text(
            if (quiet) stringResource(Res.string.thinking_streaming) else state.target,
            color = if (quiet) Tok.muted else Tok.tx2,
            fontFamily = if (quiet) Dk.ui else Dk.mono,
            fontStyle = if (quiet) FontStyle.Italic else FontStyle.Normal,
            fontSize = 12.5.sp, maxLines = 1, overflow = if (quiet) TextOverflow.Ellipsis else targetOverflow(state.target),
            style = tightCenter(12.5.sp), modifier = Modifier.weight(1f),
        )
        val (mark, ink) = when {
            state.kind == LiveLineState.Kind.WAITING -> stringResource(Res.string.tool_process_waiting) to Tok.warn
            state.kind == LiveLineState.Kind.FINISHED -> when (state.outcome) {
                StepState.FAILED -> stringResource(Res.string.chat_tool_failed) to Tok.danger
                StepState.UNKNOWN -> stringResource(Res.string.tool_process_unknown_one) to Tok.tx2
                StepState.QUIET, StepState.RUNNING, null -> null to Tok.muted
                StepState.DONE -> stringResource(Res.string.done) to Tok.ok
            }
            else -> state.clockKeys.takeIf { it.isNotEmpty() }?.let { rememberStepElapsed(it) } to Tok.muted
        }
        if (mark != null) {
            Text(
                mark, color = ink, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1,
                style = tightCenter(11.sp).copy(fontFeatureSettings = TNUM),
            )
        }
    }
}

@Composable
private fun DesktopFoldMarker(text: String, ink: Color, glyph: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        glyph()
        Text(
            text, color = ink, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            style = tightCenter(12.5.sp).copy(fontFeatureSettings = TNUM),
        )
    }
}
