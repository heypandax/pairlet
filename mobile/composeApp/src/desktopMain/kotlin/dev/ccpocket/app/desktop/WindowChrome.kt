package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import dev.ccpocket.app.theme.Tok

/**
 * The custom (undecorated) title bar — draggable, with platform-appropriate window controls: macOS traffic
 * lights on the left, Windows/Linux min/max/close on the right. The label region is the drag handle and
 * ALSO the zoom handle (double-click toggles fill-screen, the macOS title-bar convention); the search chip
 * and status dot sit outside it so they stay clickable. The dot opens the tray popover.
 */
@Composable
fun FrameWindowScope.DkTitleBar(
    mac: Boolean,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMax: () -> Unit,
    onTray: () -> Unit,
    onSearch: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).background(Tok.base).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (mac) TrafficLights(onClose, onMinimize, onToggleMax)
            // the label region is the drag handle. Anchor to the GLOBAL mouse position (AWT points), not the
            // compose drag delta: deltas are physical px (2× on Retina → the window outruns the cursor) and
            // are measured relative to the moving window itself (feedback loop → jitter). Grab the
            // mouse-to-window offset on press and pin the window to it — density-independent, no feedback.
            Row(
                Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onToggleMax() }) // double-click zoom, beside the drag detector
                }.pointerInput(Unit) {
                    var grab: java.awt.Point? = null // mouse−window offset at press, in screen points
                    detectDragGestures(
                        onDragStart = {
                            grab = java.awt.MouseInfo.getPointerInfo()?.location?.let {
                                java.awt.Point(it.x - window.x, it.y - window.y)
                            }
                        },
                        onDragEnd = { grab = null },
                        onDragCancel = { grab = null },
                    ) { change, _ ->
                        change.consume()
                        val g = grab ?: return@detectDragGestures
                        java.awt.MouseInfo.getPointerInfo()?.location?.let { m ->
                            window.setLocation(m.x - g.x, m.y - g.y)
                        }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("cc-pocket", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
            }
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).clickable(onClick = onSearch).padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Search, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
                Text("Search", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp)
                Key("⌘K")
            }
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onTray), contentAlignment = Alignment.Center) {
                PulseDot(Tok.ok, 7.dp)
            }
            if (!mac) WinControls(onMinimize, onToggleMax, onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun TrafficLights(onClose: () -> Unit, onMinimize: () -> Unit, onMax: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Light(Color(0xFFED6A5E), onClose)
        Light(Color(0xFFF4BE4F), onMinimize)
        Light(Color(0xFF61C554), onMax)
    }
}

@Composable
private fun Light(color: Color, onClick: () -> Unit) {
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(color).clickable(onClick = onClick))
}

@Composable
private fun WinControls(onMinimize: () -> Unit, onMax: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WinCell(Icons.Rounded.Remove, onMinimize)
        WinCell(Icons.Rounded.CropSquare, onMax)
        WinCell(Icons.Rounded.Close, onClose)
    }
}

@Composable
private fun WinCell(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(width = 30.dp, height = 38.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Tok.tx2, modifier = Modifier.size(13.dp))
    }
}
