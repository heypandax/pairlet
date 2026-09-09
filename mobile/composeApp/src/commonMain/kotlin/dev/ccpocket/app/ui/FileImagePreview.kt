package dev.ccpocket.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource
import kotlin.math.exp
import kotlin.math.roundToInt

/** The file viewer's inline image surface. Gesture input stays on the fixed viewport; only the
 * picture is transformed. Bounds use the fitted image, including letterboxing, not the whole box. */
@Composable
internal fun FileImagePreview(bitmap: ImageBitmap, path: String, modifier: Modifier = Modifier) {
    val zoom = remember(path, bitmap) { ImageZoomState(maxScale = 8f) }
    // The viewport can keep its layout size while the selected image changes. Retain that measured
    // size across files; waiting for a new onSizeChanged callback would leave the new bounds at zero.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val bounds = ImageZoomState.fit(bitmap.width.toFloat(), bitmap.height.toFloat(), viewportSize.width.toFloat(), viewportSize.height.toFloat())
    // Multiples of the fitted view (1x), not a claim to show source pixels at 100%.
    val scaleLabel = "${(zoom.scale * 10f).roundToInt() / 10f}×"
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("file-image-viewport")
                .semantics { stateDescription = scaleLabel }
                .onSizeChanged {
                    viewportSize = it
                    zoom.clamp(ImageZoomState.fit(bitmap.width.toFloat(), bitmap.height.toFloat(), it.width.toFloat(), it.height.toFloat()))
                }
                .pointerInput(zoom, bounds) {
                    detectTransformGestures { centre, pan, scale, _ ->
                        zoom.transformAt(scale, pan.x, pan.y, bounds, centre.x, centre.y)
                    }
                }
                .pointerInput(zoom, bounds) {
                    detectTapGestures(onDoubleTap = { point ->
                        if (zoom.atRest) zoom.transformAt(zoom.doubleTapScale / zoom.scale, 0f, 0f, bounds, point.x, point.y)
                        else zoom.reset()
                    })
                }
                .pointerInput(zoom, bounds) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = change.scrollDelta.y
                            if (delta != 0f) {
                                val factor = exp(-delta.coerceIn(-10f, 10f) * 0.12f)
                                zoom.transformAt(factor, 0f, 0f, bounds, change.position.x, change.position.y)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Image(
                bitmap, contentDescription = fileNameOf(path), contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale
                    translationX = zoom.offsetX; translationY = zoom.offsetY
                },
            )
        }
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(
                onClick = { zoom.transformAt(1f / 1.5f, 0f, 0f, bounds) },
                enabled = !zoom.atRest, modifier = Modifier.testTag("file-image-zoom-out"),
            ) {
                Icon(Icons.Rounded.ZoomOut, stringResource(Res.string.image_zoom_out), tint = if (zoom.atRest) Tok.muted else Tok.tx2)
            }
            Text(scaleLabel, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.testTag("file-image-zoom"))
            IconButton(
                onClick = { zoom.transformAt(1.5f, 0f, 0f, bounds) },
                enabled = zoom.scale < zoom.maxScale, modifier = Modifier.testTag("file-image-zoom-in"),
            ) {
                Icon(Icons.Rounded.ZoomIn, stringResource(Res.string.image_zoom_in), tint = if (zoom.scale >= zoom.maxScale) Tok.muted else Tok.tx2)
            }
            TextButton(onClick = { zoom.reset() }, modifier = Modifier.testTag("file-image-fit")) {
                Text(stringResource(Res.string.image_zoom_fit), color = Tok.tx2, fontSize = 12.sp)
            }
        }
    }
}
