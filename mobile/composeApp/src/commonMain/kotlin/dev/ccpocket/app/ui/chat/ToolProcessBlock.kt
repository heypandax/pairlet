package dev.ccpocket.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ChatPresentation
import dev.ccpocket.app.data.ChatRow
import dev.ccpocket.app.data.ProcessStepClock
import dev.ccpocket.app.data.StepState
import dev.ccpocket.app.data.ToolTarget
import dev.ccpocket.app.data.stepClockKeyOf
import dev.ccpocket.app.epochMillis
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
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.EcgGlyph
import dev.ccpocket.app.ui.PulseDot
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.isQuestion
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Tool Process Live v1 (docs/design/claude-design-handoff/tool-process-live-v1) — the process fold as ONE
 * card: a header that counts, its members when opened, and — while the turn runs — a fixed-height live line
 * under a hairline that says what is executing now. Nothing in the card changes height while a run is live,
 * so the stream above it no longer bounces on every step.
 *
 * This file holds what the phone list and the desktop pane share (the live line's content, the step clock,
 * the card segments) plus the phone renderers; the desktop renderers live in ChatPane.
 */

/** Test tag on every live line. */
const val TOOL_PROCESS_LIVE_TAG = "tool-process-live"

// ── card segments ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * One segment of a fold's card: surface fill, hairline sides, a hairline top edge (the card's own top, or the
 * separator from the segment above) and — on the [bottom] segment only — the bottom edge; the outer corners
 * round at the card's two ends. A header, its members and the live line stack into one card this way.
 */
fun Modifier.processSegment(top: Boolean, bottom: Boolean, fill: Color, line: Color, radius: Dp = 8.dp): Modifier {
    val t = if (top) radius else 0.dp
    val b = if (bottom) radius else 0.dp
    val shape = RoundedCornerShape(topStart = t, topEnd = t, bottomStart = b, bottomEnd = b)
    return clip(shape).background(fill).drawWithContent {
        drawContent()
        val s = 1.dp.toPx()
        // a segment with another below leaves its bottom edge (and corners) outside the clip: the next
        // segment's top edge is the single hairline between the two
        val h = if (bottom) size.height else size.height + radius.toPx() + s
        val outline = shape.createOutline(Size(size.width - s, h - s), layoutDirection, this)
        translate(s / 2f, s / 2f) { drawOutline(outline, line, style = Stroke(s)) }
    }
}

/**
 * Pulls a segment up over the list's item spacing so it sits flush under the segment above it. The chat lists
 * space their rows evenly; an opened fold's members are still rows of their own so a long fold stays lazy.
 */
fun Modifier.joinPreviousSegment(gap: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val g = gap.roundToPx().coerceAtMost(p.height)
    layout(p.width, p.height - g) { p.place(0, -g) }
}

/** Caps a row child at [fraction] of the width left for it, so a long token never crowds out its target. */
internal fun Modifier.capWidth(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).roundToInt() else constraints.maxWidth
    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = minOf(constraints.maxWidth, cap)))
    layout(p.width, p.height) { p.place(0, 0) }
}

// ── what the live line says ─────────────────────────────────────────────────────────────────────────────────

/** The live line's content — pure data read from the transcript, shared by both renderers and the tests. */
data class LiveLineState(
    val kind: Kind,
    /** The verbatim tool token shown (the earliest of several running together); null when nothing names one. */
    val tool: String? = null,
    /** "×3" when calls of one tool run together, "+2" when different tools do. */
    val more: String? = null,
    /** One line: the step's target (command, path, pattern) — or the file names of parallel calls. */
    val target: String = "",
    /** The clocks behind the line's timer ([stepClockKey]) — every call running together; the timer shows the
     *  earliest start among them, so a parallel call finishing first never resets it. Empty: no timer. */
    val clockKeys: List<String> = emptyList(),
    /** For [Kind.FINISHED]: the fold's verdict on that step (done / failed / no result / no claim). */
    val outcome: StepState? = null,
) {
    enum class Kind {
        /** A tool is running. */
        RUNNING,

        /** A thinking block is streaming and no tool has run in this fold yet. */
        THINKING,

        /** A running step waits on the user's approval (the decision itself stays with the approval sheet). */
        WAITING,

        /** Nothing in flight: the latest step that finished, with its outcome, held until the next one starts. */
        FINISHED,

        /** Nothing in flight and no step to show: the agent is working out the first one. */
        IDLE,
    }
}

/**
 * What [group]'s live line shows: waiting for approval, else what runs (parallel calls folded into one line),
 * else the LATEST step and its outcome. Between steps the line keeps the step that just finished rather than
 * saying "thinking": most calls finish in milliseconds and the agent then spends seconds deciding the next one,
 * so a line that only showed calls while they ran showed nothing (the user's recording of 2026-09-27 — see the
 * handoff README). A thinking block shows only while the fold has no step yet.
 * [ask] is this list's pending approval — not a timed-out one (#100: that card is terminal, not a wait) — and a
 * question is not one either (its card owns that turn). [cwd] shortens paths (see [ToolTarget]).
 */
fun liveLineState(p: ChatPresentation, group: ChatRow.ProcessGroup, ask: PermissionAsk?, cwd: String? = null): LiveLineState {
    val live = group.live ?: return LiveLineState(LiveLineState.Kind.IDLE)
    val running = live.inFlight.filter { p.items.getOrNull(it) is ChatItem.Tool }
    val approval = ask?.takeUnless { it.isQuestion }
    if (approval != null) {
        // the step the approval is for, when the stream shows it; else the approval's own words
        val step = running.firstNotNullOfOrNull { (p.items[it] as ChatItem.Tool).takeIf { t -> t.tool == approval.tool } }
        return LiveLineState(
            LiveLineState.Kind.WAITING,
            tool = step?.tool ?: approval.tool,
            target = ToolTarget.of(step?.preview ?: approval.inputPreview, cwd),
        )
    }
    if (running.isNotEmpty()) {
        val tools = running.map { p.items[it] as ChatItem.Tool }
        val lead = tools.first()
        val sameTool = tools.all { it.tool == lead.tool }
        return LiveLineState(
            LiveLineState.Kind.RUNNING,
            tool = lead.tool,
            more = when {
                tools.size < 2 -> null
                sameTool -> "×${tools.size}"
                else -> "+${tools.size - 1}"
            },
            target = if (tools.size > 1 && sameTool) tools.joinToString(" · ") { ToolTarget.shortName(it.preview, cwd) } else ToolTarget.of(lead.preview, cwd),
            clockKeys = running.map { stepClockKey(p, it) },
        )
    }
    val latest = group.sourceIndices.lastOrNull { it !in live.inFlight && p.items.getOrNull(it) is ChatItem.Tool }
    val thinking = live.inFlight.firstOrNull { p.items.getOrNull(it) is ChatItem.Thinking }
    if (latest == null && thinking != null) {
        return LiveLineState(LiveLineState.Kind.THINKING, clockKeys = listOf(stepClockKey(p, thinking)))
    }
    if (latest != null) {
        val step = p.items[latest] as ChatItem.Tool
        return LiveLineState(
            LiveLineState.Kind.FINISHED,
            tool = step.tool,
            target = ToolTarget.of(step.preview, cwd),
            outcome = p.stepState(latest) ?: when (step.ok) {
                true -> StepState.DONE
                false -> StepState.FAILED
                null -> StepState.UNKNOWN
            },
        )
    }
    return LiveLineState(LiveLineState.Kind.IDLE)
}

/** Stable across the list's in-place updates: the tool call's own id when it has one (the key the transcript
 *  registered at its START), else the row identity (a thinking block). */
fun stepClockKey(p: ChatPresentation, sourceIndex: Int): String =
    (p.items.getOrNull(sourceIndex) as? ChatItem.Tool)?.taskId?.let(::stepClockKeyOf) ?: "k:${p.sourceKey(sourceIndex)}"

/** A settled fold of exactly one tool names that tool instead of counting it ("one step, one line") — the
 *  audit chip its approval left does not make it two. */
fun singleToolOf(group: ChatRow.ProcessGroup, items: List<ChatItem>): ChatItem.Tool? = soleStep(group, items) as? ChatItem.Tool

/** …and a settled fold of exactly one thinking block says how long it thought. */
fun singleThoughtOf(group: ChatRow.ProcessGroup, items: List<ChatItem>): ChatItem.Thinking? = soleStep(group, items) as? ChatItem.Thinking

private fun soleStep(group: ChatRow.ProcessGroup, items: List<ChatItem>): ChatItem? {
    if (group.live != null) return null
    val steps = group.sourceIndices.mapNotNull { items.getOrNull(it) }.filter { it !is ChatItem.AutoRun }
    return steps.singleOrNull()
}

/** A tool token too long for its chip keeps the server prefix and the action: `mcp__play…browser_navigate`. */
fun chipToken(tool: String): String = if (tool.length > 26) tool.take(9) + "…" + tool.takeLast(16) else tool

/** A path or URL keeps its end (the file, the page) when it must shorten; anything else its start. */
fun targetOverflow(target: String): TextOverflow = if (ToolTarget.isPathLike(target)) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis

// ── the step clock ──────────────────────────────────────────────────────────────────────────────────────────

/** "0:42" / "3:07" since the earliest of [keys]' steps was first seen ([ProcessStepClock]), ticking once a
 *  second while composed. [keys] must not be empty. */
@Composable
internal fun rememberStepElapsed(keys: List<String>): String {
    val start = remember(keys) { keys.minOf(ProcessStepClock::startOf) }
    var seconds by remember(start) { mutableStateOf(elapsedSeconds(start)) }
    LaunchedEffect(start) {
        // an unbounded ticker is an infinite operation: under the Compose test clock the policy cancels it
        // rather than letting it keep the virtual clock busy forever (the desktop AttentionLease hang, 08-02)
        val policy = coroutineContext[InfiniteAnimationPolicy]
        while (true) {
            if (policy != null) policy.onInfiniteOperation { delay(1000) } else delay(1000)
            seconds = elapsedSeconds(start)
        }
    }
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun elapsedSeconds(start: Long): Long = ((epochMillis() - start) / 1000).coerceAtLeast(0)

// ── phone renderers ─────────────────────────────────────────────────────────────────────────────────────────

private const val TNUM = "tnum"

/**
 * The fold's header segment: caret, what it holds (counts — or, for a settled single step, the step itself),
 * the failed / outcome-less markers that never truncate, and the action. It closes the card itself when
 * nothing hangs below it, and carries the live line when no opened member does ([ChatRow.ProcessGroup.carriesLive]).
 */
@Composable
fun ProcessBlockHeader(
    group: ChatRow.ProcessGroup,
    items: List<ChatItem>,
    liveLine: LiveLineState?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
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
    val caret by animateFloatAsState(if (group.expanded) 90f else 0f, tween(150, easing = FastOutSlowInEasing))
    Column(modifier.fillMaxWidth().processSegment(top = true, bottom = !group.hasMemberRows, fill = Tok.surface, line = Tok.hair)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(TOOL_PROCESS_GROUP_TAG)
                .clickable(onClick = onToggle)
                .semantics { stateDescription = state; onClick(label = action) { onToggle(); true } }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
                Text("▸", color = Tok.muted, fontSize = 10.sp, style = tightCenter(10.sp), modifier = Modifier.rotate(caret))
            }
            if (single != null) {
                ToolChip(chipToken(single.tool), Modifier.capWidth(0.52f))
                val target = ToolTarget.of(single.preview, cwd)
                Text(
                    target, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    maxLines = 1, overflow = targetOverflow(target), style = tightCenter(12.sp), modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    thought?.seconds?.let { stringResource(Res.string.thought_for, it) } ?: processSummaryLabel(summary),
                    color = Tok.tx2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = tightCenter(12.5.sp).copy(fontFeatureSettings = TNUM), modifier = Modifier.weight(1f),
                )
            }
            if (summary.failed > 0) {
                FoldMarker(
                    if (single != null) stringResource(Res.string.chat_tool_failed)
                    else pluralStringResource(Res.plurals.tool_process_failed, summary.failed, summary.failed),
                    Tok.danger,
                ) { StateSquare(Tok.danger) }
            }
            if (summary.unknown > 0) {
                FoldMarker(
                    if (single != null) stringResource(Res.string.tool_process_unknown_one)
                    else pluralStringResource(Res.plurals.tool_process_unknown, summary.unknown, summary.unknown),
                    Tok.tx2,
                ) { StateRing(Tok.muted) }
            }
            if (summary.autoRuns > 0) {
                // grant-covered auto-decisions stay visible on the fold (approval design §9.6); opening it shows
                // each audit chip with its Tighten action
                FoldMarker(pluralStringResource(Res.plurals.tool_process_autoruns, summary.autoRuns, summary.autoRuns), Tok.tx2) {
                    Text("⚡", fontSize = 10.sp, style = tightCenter(10.sp))
                }
            }
            Text(action, color = Tok.muted, fontSize = 11.sp, maxLines = 1, style = tightCenter(11.sp))
        }
        if (liveLine != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            ProcessLiveLine(liveLine)
        }
    }
}

/** A finished member of an opened fold, drawn inside the card and indented to the header's label; the last
 *  one closes the card, and in a live fold carries the live line under it. */
@Composable
fun ProcessMemberSegment(
    last: Boolean,
    liveLine: LiveLineState?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().processSegment(top = false, bottom = last, fill = Tok.surface, line = Tok.hair)) {
        Box(Modifier.fillMaxWidth().padding(start = 32.dp, end = 12.dp, top = 9.dp, bottom = 10.dp)) { content() }
        if (liveLine != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            ProcessLiveLine(liveLine)
        }
    }
}

/**
 * The live line: ⌁, the tool chip, a one-line target, then the pulse and the step's clock — or, once the step
 * finished, its outcome (● 完成 / ■ 失败) held until the next step starts; the waiting mark in their place.
 * ONE line of one fixed height in every state, so swapping what it says moves nothing. Only the waiting and
 * failed states speak to a screen reader; per-step changes stay silent.
 */
@Composable
fun ProcessLiveLine(state: LiveLineState, modifier: Modifier = Modifier) {
    val height = max(31.dp, with(LocalDensity.current) { 20.sp.toDp() } + 11.dp)
    val failed = state.kind == LiveLineState.Kind.FINISHED && state.outcome == StepState.FAILED
    val a11y = when {
        state.kind == LiveLineState.Kind.WAITING -> stringResource(Res.string.tool_process_a11y_waiting, state.tool.orEmpty(), state.target)
        failed -> stringResource(Res.string.tool_process_a11y_failed, state.tool.orEmpty(), state.target)
        else -> null
    }
    Row(
        modifier.fillMaxWidth().height(height).testTag(TOOL_PROCESS_LIVE_TAG)
            .semantics(mergeDescendants = true) {
                if (a11y != null) {
                    contentDescription = a11y
                    liveRegion = LiveRegionMode.Polite
                } else {
                    hideFromAccessibility()
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) { EcgGlyph(Tok.muted, 11.dp) }
        val quiet = state.kind == LiveLineState.Kind.THINKING || state.kind == LiveLineState.Kind.IDLE
        if (!quiet && state.tool != null) ToolChip(chipToken(state.tool), Modifier.capWidth(0.48f))
        if (!quiet && state.more != null) {
            Text(
                state.more, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, style = tightCenter(11.sp),
            )
        }
        Text(
            if (quiet) stringResource(Res.string.thinking_streaming) else state.target,
            color = if (quiet) Tok.muted else Tok.tx2,
            fontFamily = if (quiet) null else FontFamily.Monospace,
            fontStyle = if (quiet) FontStyle.Italic else FontStyle.Normal,
            fontSize = 12.sp, maxLines = 1, overflow = if (quiet) TextOverflow.Ellipsis else targetOverflow(state.target),
            style = tightCenter(12.sp), modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            when {
                state.kind == LiveLineState.Kind.WAITING -> StateSquare(Tok.warn)
                state.kind == LiveLineState.Kind.FINISHED -> when (state.outcome) {
                    StepState.FAILED -> StateSquare(Tok.danger)
                    StepState.UNKNOWN -> StateRing(Tok.muted)
                    StepState.DONE -> StateDot(Tok.ok)
                    else -> StateDot(Tok.muted)
                }
                else -> PulseDot(Tok.accent, 7.dp)
            }
            if (mark != null) {
                Text(
                    mark, color = ink, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, maxLines = 1,
                    style = tightCenter(10.5.sp).copy(fontFeatureSettings = TNUM),
                )
            }
        }
    }
}

@Composable
private fun ToolChip(token: String, modifier: Modifier = Modifier) = Text(
    token, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium,
    maxLines = 1, overflow = TextOverflow.Ellipsis, style = tightCenter(11.sp),
    modifier = modifier.clip(RoundedCornerShape(5.dp)).background(Tok.raised).padding(horizontal = 6.dp, vertical = 3.dp),
)

@Composable
private fun FoldMarker(text: String, ink: Color, glyph: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        glyph()
        Text(
            text, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            style = tightCenter(12.sp).copy(fontFeatureSettings = TNUM),
        )
    }
}

/** ■ — failed / waiting, the same square the tool rows use for a failure. */
@Composable
internal fun StateSquare(color: Color, size: Dp = 7.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(1.dp)).background(color))

/** ● — a finished step's outcome (ok ink), or a step nobody will report on (muted). */
@Composable
internal fun StateDot(color: Color, size: Dp = 7.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(color))

/** ○ — a step whose outcome never arrived. */
@Composable
internal fun StateRing(color: Color, size: Dp = 7.dp) =
    Box(Modifier.size(size).border(1.5.dp, color, CircleShape))
