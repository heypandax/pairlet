@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // combinedClickable (send long-press, #137)

package dev.ccpocket.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.data.VoiceComposerReason
import dev.ccpocket.app.data.VoiceSetupIssue
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Design easing for the recording-bar morph: cubic-bezier(.22,1,.36,1), 220ms. */
private val MorphEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** m:ss from whole seconds — the one countdown/elapsed format (recording timer, fleet cards, palette rows).
 *  Hour-scale durations are the exception: the jobs panel's compact "3h12m" shape is fmtJobDuration (SessionSheets). */
internal fun fmtMmSs(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal fun fmtElapsed(ms: Long): String = fmtMmSs((ms / 1000).toInt())

/**
 * Append a reviewed voice transcript to the draft already in the composer (#221, #238).
 *
 * The transcript has already been trimmed by PocketRepository. Preserve the user's draft byte-for-byte
 * and add exactly one separator only when the draft did not already provide whitespace. Keeping this pure
 * pins the existing pendingVoiceText behavior while the microphone becomes reachable beside staged text.
 */
internal fun appendVoiceTranscript(existing: String, transcript: String): String = when {
    existing.isEmpty() -> transcript
    existing.last().isWhitespace() -> existing + transcript
    else -> "$existing $transcript"
}

/** The composer text field per the design (mobile-composer.jsx): base bg, hairline border,
 *  radius 14, minHeight 44. [state] is the field's single source of truth ([ComposerState]) —
 *  external writes (completion, clear-on-send, draft adopt) go through its explicit methods,
 *  never through recomposition. */
@Composable
fun ComposerField(
    state: ComposerState,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** Optional independent action inside the full-width field, e.g. Mic while text is already staged. */
    trailingAction: (@Composable () -> Unit)? = null,
    /** false inside a host that already frames the composer (Chat Rhythm v1's one container): no fill, no
     *  hairline of its own — one border around field and tools instead of a frame inside a frame. */
    framed: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    // The TEXT owns the field's inset, not the field itself. A shared outer `horizontal = 14.dp` also
    // pushed the trailing target 14dp off the right border, so its centered plate sat 21dp from the
    // visible edge while it sat 7dp from the top and bottom — a mark that read as nudged inward. The
    // target is flush with the border instead, and the text stops [Metric.touch] + 4dp short of it, which
    // leaves the same optical gap between the last glyph and the plate that it had before.
    val textEndInset = if (trailingAction == null) 14.dp else Metric.touch + 4.dp
    Box(
        modifier.heightIn(min = 44.dp)
            .then(if (framed) Modifier.clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The state IS the IME's buffer (no value round trip); a write held behind a live composition
        // lands the moment the IME releases the field — see ComposerState.
        LandPendingWrites(state)
        BasicTextField(
            state = state.state,
            textStyle = TextStyle(color = Tok.tx, fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(Tok.accent),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = textEndInset, top = 11.dp, bottom = 11.dp)
                .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
        )
        if (state.text.isEmpty()) {
            Text(
                placeholder, color = Tok.muted, fontSize = 15.sp, maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, end = textEndInset),
            )
        }
        trailingAction?.let { action -> Box(Modifier.align(Alignment.CenterEnd)) { action() } }
    }
}

/**
 * 44dp round action button: filled terracotta (send/done) or hairline outline (capture stop).
 *
 * The circle keeps its 44dp look; the button ITSELF is the design's [Metric.touch] minimum (Chat Master
 * v2: "44–48 pt controls, with 48 pt accessibility targets even when the visible chip is smaller"). The
 * extra ring is transparent, so nothing about the drawn control changes — only what a thumb, and the
 * semantics tree, can actually hit. [contentDescription] names it there too: the stop square carries no
 * icon of its own, so before this it reached assistive tech as an unnamed button.
 *
 * This is the grammar of the FREE-STANDING round actions. The composer field's Mic draws its own inset
 * plate instead — a hairline circle nested inside the field's hairline read as a frame within a frame.
 */
@Composable
fun RoundActionButton(
    onClick: () -> Unit,
    filled: Boolean,
    enabled: Boolean = true,
    contentDescription: String?,
    onLongClick: (() -> Unit)? = null, // e.g. the send button's "schedule send" (issue #137)
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(Metric.touch).clip(CircleShape)
            .let {
                if (onLongClick != null) {
                    it.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
                } else it.clickable(enabled = enabled, onClick = onClick)
            }
            .then(contentDescription?.let { cd -> Modifier.semantics { this.contentDescription = cd } } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (filled) Tok.accent else Tok.base)
                .let { if (filled) it else it.border(1.dp, Tok.hair, CircleShape) }
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * A turn action in the accessory lane, deliberately distinct from [RoundActionButton]. The recording bar
 * and the field's Mic keep their compact icon-only grammar; Stop and Send are the lane's labelled,
 * rectangular actions from the canonical handoff. [modifier] may carry the lane's `weight(1f)` when the
 * action group stacks, so both actions divide the available row evenly without weakening their 84 dp
 * inline floor.
 */
@Composable
fun ComposerLaneActionButton(
    onClick: () -> Unit,
    filled: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val target = if (LocalDensity.current.fontScale >= 1.5f) 58.dp else 48.dp
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.widthIn(min = 84.dp).height(target).clip(shape)
            .background(if (filled) Tok.accent else Tok.base)
            .let { if (filled) it else it.border(1.dp, Tok.hair, shape) }
            .let {
                if (onLongClick != null) {
                    it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else it.clickable(onClick = onClick)
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            contentDescription,
            color = if (filled) Tok.base else Tok.tx2,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * The composer's one-line explanation of why the action slot reads the way it does (design
 * `ChatComposerV2`, the `showNote` row): a quiet mark plus written cause, above the field.
 *
 * It is a NOTE, not a placeholder. "Send will queue" used to ride the field's placeholder, which meant the
 * explanation vanished at exactly the moment it became true — the first character you typed mid-turn.
 */
@Composable
fun ComposerNote(text: String) {
    // Chat Rhythm v1: the running / uploading note is a quiet written line, not one more bordered capsule stacked
    // over the composer. Same slot, same single polite live region (#238); the voice failure keeps its ribbon.
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        // the dot centres on the FIRST line box (the note may wrap): a box one line tall, and a centred line
        // style so that line box means the same thing on every platform font (tightCenter, AGENTS.md)
        val line = 17.sp
        Box(Modifier.height(with(LocalDensity.current) { line.toDp() }).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Tok.muted))
        }
        Text(
            text, color = Tok.tx2, fontSize = 12.5.sp, style = tightCenter(12.5.sp).copy(lineHeight = line),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The ONE state ribbon above the composer field (#238 · V3): queue, upload and voice failure all arrive
 * in the same slot, in the same grammar, so the reason a control behaves unusually is read before the
 * control is reached.
 *
 * A low container with a hairline rather than a floating line of prose — the composer already stacks a
 * completion menu, an attach tray and a pending-files strip, and an unbounded sentence among them read as
 * transcript rather than as state. The mark is decorative and excluded from semantics (the sentence is the
 * state); the container is a polite live region, so a change is announced once instead of on every
 * recomposition. Nothing is capped: the text wraps to as many lines as its locale needs.
 */
@Composable
private fun ComposerRibbon(text: String, danger: Boolean, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    val line = if (danger) Tok.danger.copy(alpha = 0.33f) else Tok.hair
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 9.dp)
            .clip(shape).background(if (danger) Tok.danger.copy(alpha = 0.10f) else Tok.raised)
            .border(1.dp, line, shape)
            .padding(horizontal = 11.dp, vertical = 8.dp)
            // merged, so the live region has the SENTENCE to announce — a live region on a node that
            // carries no text of its own announces nothing at all
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        // shape, not colour, is what separates informational from failure here — the two survive greyscale
        Box(Modifier.padding(top = 2.dp).clearAndSetSemantics {}) {
            if (danger) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.5.dp)).background(Tok.danger))
            } else {
                Box(Modifier.padding(top = 1.dp).size(6.dp).clip(CircleShape).background(Tok.muted))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text, color = if (danger) Tok.danger else Tok.tx2, fontSize = 12.5.sp, lineHeight = 17.sp,
                fontWeight = if (danger) FontWeight.Medium else FontWeight.Normal,
            )
            action?.invoke()
        }
    }
}

/** How wide the composer's model label may draw. The lane lowers it (never below [MODEL_LABEL_FLOOR]) when that is
 *  what keeps the tool row on one line; the model chip's call site reads it as its `labelMax`. */
val LocalComposerModelLabelMax = compositionLocalOf { 120.dp }

/** The narrowest the lane squeezes the model label to stay inline — about seven mono characters at 11sp. */
internal val MODEL_LABEL_FLOOR = 48.dp

/**
 * The inline-or-stack decision (Chat Rhythm v1 · design master "Wrapping rule · one predicate"), on MEASURED widths.
 *
 * [room] is what the leading group may use once the trailing actions and their gap are taken; [leadAtFloor] and
 * [leadNatural] are the leading group's measured widths with the model label capped at [floor] and at [labelMax].
 * Returns the label cap to draw inline, or null to stack the whole trailing group below. The only thing that ever
 * gives is the model LABEL — which ellipsizes — never a control's target or its type; past 1.5× type nothing packs
 * inline. Pure (pixels in, pixels out), so the rule is provable without a screenshot.
 */
internal fun composerLaneLabelCap(room: Int, leadAtFloor: Int, leadNatural: Int, floor: Int, labelMax: Int, fontScale: Float): Int? = when {
    fontScale >= 1.5f || leadAtFloor > room -> null
    leadNatural <= room -> labelMax
    // the chip is linear in its label, so the label grows from the floor by exactly the room left over at the floor
    else -> (floor + (room - leadAtFloor)).coerceIn(floor, labelMax)
}

private enum class LaneSlot { Actions, LeadFloor, LeadNatural, Final }

/**
 * The composer's accessory lane: one LEADING group (attach, model, cross-session switcher, context) and one TRAILING
 * group (stop, send, upload status).
 *
 * Chat Rhythm v1: the fit is decided on what the controls actually measure ([composerLaneLabelCap]) rather than on
 * fixed floors — the standard phone keeps field + ONE tool row even with a switcher, a context number and Stop, by
 * ellipsizing the model label first. When even the floor label does not fit (320pt, large type) the complete
 * trailing group moves below: two or more actions share that row evenly; a single Stop or Send stays a compact
 * button at the trailing edge, as on the design board. No control is hidden, shrunk or moved into a menu.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComposerAccessoryLane(
    actionCount: Int,
    // The lane owns the modifier because only it knows whether the complete trailing group stacked. In
    // that branch paired actions receive the same weight; otherwise each keeps its own 84 dp floor.
    leading: @Composable () -> Unit,
    actions: @Composable (Modifier) -> Unit,
    /** The lane's inset. The default suits a lane under a free-standing field; Chat Rhythm v1's one composer
     *  container passes its own (the container's border already sets the edge). */
    contentPadding: PaddingValues = PaddingValues(start = 6.dp, end = 8.dp, top = 6.dp),
    labelMax: Dp = 120.dp,
) {
    val fontScale = LocalDensity.current.fontScale
    // 4dp between the leading controls: each already carries a transparent ring to its 48dp target
    val leadGap = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    val actGap = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    val lineGap = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    @Composable
    fun leadRow(cap: Dp, modifier: Modifier = Modifier) = CompositionLocalProvider(LocalComposerModelLabelMax provides cap) {
        Row(modifier, horizontalArrangement = leadGap, verticalAlignment = Alignment.CenterVertically) { leading() }
    }
    SubcomposeLayout(Modifier.fillMaxWidth().padding(contentPadding)) { constraints ->
        val unbounded = Constraints()
        // measuring probes: composed and measured, never placed — and cleared from semantics, or a screen reader
        // (and every test) would find each lane control three times
        fun widthOf(slot: LaneSlot, content: @Composable () -> Unit) =
            subcompose(slot) { Box(Modifier.clearAndSetSemantics {}) { content() } }.maxOfOrNull { it.measure(unbounded).width } ?: 0
        val actionsW = if (actionCount > 0) widthOf(LaneSlot.Actions) { Row(horizontalArrangement = actGap) { actions(Modifier) } } else 0
        val room = constraints.maxWidth - actionsW - (if (actionCount > 0) 8.dp.roundToPx() else 0)
        val floorPx = MODEL_LABEL_FLOOR.roundToPx()
        val maxPx = labelMax.roundToPx()
        val atFloor = widthOf(LaneSlot.LeadFloor) { leadRow(MODEL_LABEL_FLOOR) }
        val cap = composerLaneLabelCap(
            room, atFloor,
            // only measured when the floor fits at all — otherwise the lane stacks and the natural width is moot
            leadNatural = if (fontScale < 1.5f && atFloor <= room) widthOf(LaneSlot.LeadNatural) { leadRow(labelMax) } else Int.MAX_VALUE,
            floor = floorPx, labelMax = maxPx, fontScale = fontScale,
        )
        val placeables = subcompose(LaneSlot.Final) {
            if (cap != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    leadRow(cap.toDp(), Modifier.weight(1f))
                    if (actionCount > 0) Row(horizontalArrangement = actGap, verticalAlignment = Alignment.CenterVertically) { actions(Modifier) }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    CompositionLocalProvider(LocalComposerModelLabelMax provides labelMax) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = leadGap, verticalArrangement = lineGap) { leading() }
                    }
                    if (actionCount == 1) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                            actions(Modifier)
                        }
                    } else if (actionCount > 1) {
                        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = actGap, verticalArrangement = lineGap) {
                            actions(Modifier.weight(1f))
                        }
                    }
                }
            }
        }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, placeables.maxOfOrNull { it.height } ?: 0) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * S2/S3 recording bar: ✕ · [rec-dot + waveform | spinner + “Transcribing…”] · timer · ✓.
 * Morph-in per the design (translateY 6dp → 0, 220 ms).
 */
@Composable
fun RecordingBar(
    elapsedMs: Long,
    transcribing: Boolean,
    levels: List<Float>,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    /** S3 while the audio is still queued on the phone: the same spinner row reads the upload line instead. */
    uploading: Boolean = false,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = MorphEasing)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .graphicsLayer { translationY = (1f - appear.value) * 6.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(Metric.touch)) {
            Icon(XSmallIcon, stringResource(Res.string.cancel_recording), tint = Tok.muted, modifier = Modifier.size(18.dp))
        }
        val pillShape = RoundedCornerShape(12.dp)
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).clip(pillShape).background(Tok.raised)
                .border(1.dp, Tok.hair, pillShape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            if (transcribing) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), color = Tok.accent, strokeWidth = 2.dp)
                // Keep the live region on the stable state text only. Putting it on the whole bar would
                // re-announce every timer tick; this announces the Recording -> Transcribing edge once.
                Text(
                    // one recording → the file strip's own "uploading 1 of 1" line
                    if (uploading) stringResource(Res.string.file_strip_uploading, 1, 1) else stringResource(Res.string.transcribing),
                    color = Tok.tx2,
                    fontSize = 13.sp,
                    style = tightCenter(13.sp),
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                RecDot()
                Waveform(levels, frozen = false, modifier = Modifier.weight(1f))
            }
            Text(
                fmtElapsed(elapsedMs), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, style = tightCenter(12.5.sp),
            )
        }
        val doneLabel = stringResource(Res.string.done)
        RoundActionButton(onClick = onDone, filled = true, contentDescription = doneLabel) {
            // the button names itself now — a second description here would be announced twice
            Icon(CheckIcon, null, tint = Tok.base, modifier = Modifier.size(20.dp))
        }
    }
}

/** The pulsing red recording dot (1.2s ease pulse, glow via a soft outer ring). */
@Composable
private fun RecDot() {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
    )
    Box(
        Modifier.size(8.dp).graphicsLayer { alpha = pulse; scaleX = 0.82f + 0.18f * pulse; scaleY = 0.82f + 0.18f * pulse }
            .clip(CircleShape).background(Tok.danger),
    )
}

/**
 * iOS native dictation: the live transcript above the recording bar — committed text primary,
 * volatile tail muted, blinking terracotta caret at the live end (echoes the streaming cursor).
 */
@Composable
fun LiveTranscriptField(final: String, partial: String) {
    val shape = RoundedCornerShape(12.dp)
    val caret by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(525, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse),
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp).heightIn(min = 44.dp)
            .clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Tok.tx)) { append(final) }
                withStyle(SpanStyle(color = Tok.muted)) { append(partial) }
            },
            fontSize = 14.5.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            Modifier.padding(start = 2.dp, bottom = 2.dp).size(width = 2.dp, height = 16.dp)
                .graphicsLayer { alpha = if (caret > 0.5f) 1f else 0f }
                .background(Tok.accent, RoundedCornerShape(2.dp)),
        )
    }
}

/** The reason line for each way a send-bar dictation lands in the composer unsent (review §4, §11). */
internal fun voiceReasonRes(reason: VoiceComposerReason): StringResource = when (reason) {
    VoiceComposerReason.TIMEOUT -> Res.string.voice_reason_timeout
    VoiceComposerReason.NOT_ADOPTED -> Res.string.voice_reason_not_adopted
    VoiceComposerReason.UNAVAILABLE -> Res.string.voice_reason_unavailable
    VoiceComposerReason.REVIEW -> Res.string.voice_reason_review
    VoiceComposerReason.DISCONNECTED -> Res.string.voice_reason_disconnected
    VoiceComposerReason.NOT_SENT -> Res.string.voice_reason_not_sent
}

/**
 * S5: the danger state above the composer.
 *
 * Same ribbon slot and layout primitive as the queue/upload note, and the same danger treatment it always
 * had — the tint, the hairline, the warning mark and the medium danger text are unchanged. What it gains
 * is the full width and an uncapped wrap, so a long transcription failure detail is read rather than cut
 * at two lines. The retry itself stays where it is: the field's trailing action, named Retry voice input.
 */
@Composable
fun VoiceErrorChip(message: String) =
    ComposerRibbon(message, danger = true)

/** Setup failures have a short explanation and an explicit send action. The complete diagnostic goes
 * into the request shown in the conversation, rather than filling the phone with shell commands. */
@Composable
fun VoiceSetupChip(issue: VoiceSetupIssue, enabled: Boolean, onRequest: () -> Unit) {
    val message = stringResource(when (issue) {
        VoiceSetupIssue.TRANSCRIBER -> Res.string.voice_setup_transcriber_missing
        VoiceSetupIssue.MODEL -> Res.string.voice_setup_model_missing
        VoiceSetupIssue.CONVERTER -> Res.string.voice_setup_converter_missing
    })
    ComposerRibbon(message + "\n" + stringResource(Res.string.voice_setup_complete_hint), danger = true, action = {
        TextButton(onClick = onRequest, enabled = enabled, modifier = Modifier.heightIn(min = Metric.touch)) {
            Text(stringResource(Res.string.voice_setup_ask_agent), style = tightCenter(13.sp), fontWeight = FontWeight.Medium)
        }
    })
}

/** S6: mic permission sheet in the PermissionSheet visual language. */
@Composable
fun MicPermissionSheet(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)) {
            Box(
                Modifier.size(50.dp).clip(RoundedCornerShape(14.dp))
                    .background(Tok.accent.copy(alpha = 0.12f))
                    .border(1.dp, Tok.accent.copy(alpha = 0.30f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(ShieldMicIcon, null, tint = Tok.accent, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.mic_title), color = Tok.tx, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(
                stringResource(Res.string.mic_body),
                color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(Tok.accent)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.open_settings), color = Tok.base, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.fillMaxWidth().height(44.dp).padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.not_now), color = Tok.tx2, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
    }
}
