package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not a behavioural test — a screenshot generator. Renders each desktop surface offscreen (Skia, the same
 * engine the app uses) into the build/screenshots folder as PNGs at 2x. Deterministic, headless, no display
 * or screen-grab needed. Run with the gradle desktopTest task filtered to this class.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopScreenshotTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val scale = 2 // pixel scale; [w]/[h] are LOGICAL dp, the scene takes pixels → multiply

    private fun shot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w * scale, height = h * scale, density = Density(scale.toFloat())) {
            PocketTheme { Box(Modifier.fillMaxSize().background(Tok.base)) { content() } }
        }
        try {
            val data = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed for $name")
            File(outDir, name).writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    /** The full window look: a static title bar (real DkTitleBar needs a window) over the live two-pane shell. */
    @Composable
    private fun WindowFrame(model: DesktopModel) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).background(Tok.base).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFED6A5E)))
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFF4BE4F)))
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF61C554)))
                }
                Text("cc-pocket", color = Tok.muted, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, modifier = Modifier.padding(start = 2.dp))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
                    Text("Search", color = Tok.muted, fontSize = 11.5.sp)
                    Key("⌘K")
                }
                Dot(Tok.ok, 7.dp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Box(Modifier.fillMaxWidth().weight(1f)) { DesktopApp(model) }
        }
    }

    private fun seed(block: SeedDesktopModel.() -> Unit = {}) = SeedDesktopModel().apply(block)

    @Test
    fun generate() {
        val W = 1180; val H = 798
        shot("01-shell.png", W, H) { WindowFrame(seed()) } // fleet: machine-grouped sidebar + split watch pane
        shot("02-codex-diff-approval.png", W, H) { WindowFrame(seed { selectSession(sessions[2]) }) }
        shot("03-attention-popover.png", W, H) { WindowFrame(seed { showAttention = true }) }
        shot("04-new-session.png", W, H) { WindowFrame(seed { showNewSession = true }) }
        shot("05-tray-quick-approve.png", W, H) { WindowFrame(seed { showTray = true }) }
        shot("06-focused-permission.png", W, H) { WindowFrame(seed { selectSession(sessions[2]); showPermissionModal = true }) }
        shot("07-command-palette.png", W, H) { WindowFrame(seed { palette = PaletteScope.ALL }) }
        shot("08-settings.png", W, H) { WindowFrame(seed { showSettings = true }) }

        val shots = outDir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        println("[screenshots] wrote ${shots.size} files to ${outDir.absolutePath}")
        shots.forEach { println("[screenshots]   ${it.name}  ${it.length() / 1024}KB") }
        assertTrue(shots.size >= 8, "expected at least 8 screenshots")
    }
}
