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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/** Design easing for the recording-bar morph: cubic-bezier(.22,1,.36,1), 220ms. */
private val MorphEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** m:ss from whole seconds — the one countdown/elapsed format (recording timer, fleet cards, palette rows).
 *  Hour-scale durations are the exception: the jobs panel's compact "3h12m" shape is fmtJobDuration (SessionSheets). */
internal fun fmtMmSs(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal fun fmtElapsed(ms: Long): String = fmtMmSs((ms / 1000).toInt())

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
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.heightIn(min = 44.dp).clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            state.field, state::onValueChange,
            textStyle = TextStyle(color = Tok.tx, fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(Tok.accent),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)
                .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
        )
        if (state.text.isEmpty()) Text(placeholder, color = Tok.muted, fontSize = 15.sp, maxLines = 1)
    }
}

/** 44dp round action button: filled terracotta (send/done) or hairline outline (mic). */
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
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (filled) Tok.accent else Tok.base)
            .let { if (filled) it else it.border(1.dp, Tok.hair, CircleShape) }
            .let {
                if (onLongClick != null) {
                    it.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
                } else it.clickable(enabled = enabled, onClick = onClick)
            }
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        contentAlignment = Alignment.Center,
    ) { content() }
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
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = MorphEasing)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .graphicsLayer { translationY = (1f - appear.value) * 6.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
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
                Text(stringResource(Res.string.transcribing), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            } else {
                RecDot()
                Waveform(levels, frozen = false, modifier = Modifier.weight(1f))
            }
            Text(
                fmtElapsed(elapsedMs), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
            )
        }
        val doneLabel = stringResource(Res.string.done)
        RoundActionButton(onClick = onDone, filled = true, contentDescription = doneLabel) {
            Icon(CheckIcon, doneLabel, tint = Tok.base, modifier = Modifier.size(20.dp))
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

/** S5: the danger chip above the composer. */
@Composable
fun VoiceErrorChip(message: String) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.danger.copy(alpha = 0.10f))
                .border(1.dp, Tok.danger.copy(alpha = 0.33f), RoundedCornerShape(999.dp))
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
        ) {
            Icon(WarnTriIcon, null, tint = Tok.danger, modifier = Modifier.size(13.dp))
            Text(message, color = Tok.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        }
    }
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
