package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.SentFile
import dev.ccpocket.app.media.rememberLocalVideoOpener
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.video_play
import dev.ccpocket.app.resources.video_workspace_hint
import dev.ccpocket.app.resources.video_workspace_note
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

// ============================================================================================
//  Full-screen video player overlay (issue #98) — design: sent-attach-app.jsx VideoPlayer.
//  Framing (scrim · close × · filename · centered 16:9 poster) matches the design. Playback:
//  a video lands in the computer's workspace inbox, not on the phone, and the phone drops the
//  bytes on land — so there is nothing to decode in-app on a general view. When the video was
//  just picked ON THIS device the picker's local handle survives in [SentFile.localUri], and
//  "Play" hands it to the platform's video player. Otherwise the overlay says plainly that the
//  clip lives in the session workspace (open it on the computer) — honest to the #90 semantics.
// ============================================================================================

@Composable
fun VideoPlayerOverlay(file: SentFile, onClose: () -> Unit) {
    val openLocal = rememberLocalVideoOpener()
    val canPlayHere = file.localUri != null
    Box(Modifier.fillMaxSize().background(Color(0xFF060708))) {
        // top bar: close × (left) · filename (center)
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 50.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                CloseCross(Color.White, 18.dp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    file.name, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(44.dp))
        }

        // centered poster + workspace framing
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            VideoPoster(
                Modifier.clip(RoundedCornerShape(12.dp)).let { m -> if (canPlayHere) m.clickable { openLocal(file.localUri!!) } else m },
                durationSecs = file.durationSecs,
                buttonSize = 60.dp,
                glyphSize = 26.dp,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PathRefText(file.path, fontSize = 12.sp) }
            if (canPlayHere) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent)
                        .clickable { openLocal(file.localUri!!) }.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(PlayTriangleGlyph, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(stringResource(Res.string.video_play), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    stringResource(Res.string.video_workspace_note),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.5.sp, lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // footer hint — the video is a workspace artifact for the agent, not model input
        Text(
            stringResource(Res.string.video_workspace_hint),
            color = Tok.muted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** A geometrically-centered ✕ (two diagonal strokes), matching the image viewer's close glyph. */
@Composable
private fun CloseCross(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val p = w * 0.22f
        val sw = (w * 0.15f).coerceAtLeast(1f)
        drawLine(color, androidx.compose.ui.geometry.Offset(p, p), androidx.compose.ui.geometry.Offset(w - p, w - p), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(w - p, p), androidx.compose.ui.geometry.Offset(p, w - p), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
