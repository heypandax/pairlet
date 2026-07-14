package dev.ccpocket.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Older-history paging rows (issue #147) — visuals per the 0714
//  chat-components handoff (docs/design/claude-design-handoff/0714-batch/),
//  section B. Shared by the mobile chat (App.kt) and desktop ChatPane.
// ════════════════════════════════════════════════════════════════════

/** B1/B2 — the top-of-thread loader. An AMBIENT status line, never a button: fetching auto-triggers
 *  on scroll-into-view, so the row only ever says what is already happening — a small determinate-
 *  free spinner and a quiet caption, centered. [fading]=true is the silent-failure exit (B2): the
 *  request died with nothing prepended, and the row simply fades — no error text, no retry. */
@Composable
fun LoadEarlierRow(fading: Boolean, fontFamily: FontFamily? = null) {
    val alpha by animateFloatAsState(if (fading) 0f else 1f, tween(500))
    Row(
        Modifier.fillMaxWidth().height(46.dp).graphicsLayer { this.alpha = alpha },
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            Modifier.size(14.dp), color = Tok.accent, trackColor = Tok.hair, strokeWidth = 2.dp,
        )
        Text(
            stringResource(Res.string.history_loading_earlier),
            color = Tok.muted, fontSize = 12.5.sp, fontFamily = fontFamily, maxLines = 1,
        )
    }
}

/** How long the loader row lingers past hasMore's withdrawal, so its B2 fade can play before the
 *  item unmounts. Slightly over the 500ms fade. */
private const val LOADER_FADE_MS = 550L

/** Host-side visibility for the loader item: shows with hasMore, but on withdrawal holds the item
 *  mounted for [LOADER_FADE_MS] so [LoadEarlierRow]'s fade-out plays instead of a hard vanish. */
@Composable
fun rememberEarlierLoaderVisible(hasMore: Boolean): Boolean {
    var visible by remember { mutableStateOf(hasMore) }
    LaunchedEffect(hasMore) {
        if (hasMore) visible = true
        else if (visible) { delay(LOADER_FADE_MS); visible = false }
    }
    return visible
}

/** B3 — the seam. For a beat after a page of older messages lands, a subtle hairline divider marks
 *  where the old window began, so the reader keeps their place; then it fades and folds away. */
@Composable
fun EarlierMessagesSeam(gen: Int, monoFamily: FontFamily = FontFamily.Monospace) {
    var shown by remember(gen) { mutableStateOf(true) }
    LaunchedEffect(gen) { delay(1800); shown = false }
    AnimatedVisibility(shown, exit = fadeOut(tween(500)) + shrinkVertically(tween(500))) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
            Text(
                stringResource(Res.string.history_earlier_seam),
                color = Tok.muted, fontFamily = monoFamily, fontSize = 10.5.sp,
                letterSpacing = 0.6.sp, maxLines = 1,
            )
            Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
        }
    }
}

/** Host-side seam bookkeeping: returns the MESSAGE index the seam renders above (-1 = none). A landed
 *  page pins it to the old window's first surviving row — exactly where the viewport re-anchors — and
 *  retires it once [EarlierMessagesSeam]'s hold + fade have played out. A follow-up page simply moves
 *  the seam to the newest junction. */
@Composable
fun rememberHistorySeam(prependGen: Int, prependCount: Int): Int {
    var at by remember { mutableStateOf(-1) }
    LaunchedEffect(prependGen) {
        if (prependGen > 0 && prependCount > 0) {
            at = prependCount
            delay(2400) // 1800ms hold + 500ms fade inside the seam, plus a small grace
            at = -1
        }
    }
    return at
}
