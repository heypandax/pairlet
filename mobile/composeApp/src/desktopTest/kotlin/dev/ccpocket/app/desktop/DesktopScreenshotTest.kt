package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.HelpCenterScreen
import dev.ccpocket.app.ui.HelpEntryPoint
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

    /**
     * The full window look. There is no title bar to replicate any more (desktop chrome v2): the sidebar
     * runs to the window top and carries its own control row, and the chat column's sub-header is its own
     * first element. So the shell simply fills the frame — what used to be a hand-built static bar here is
     * now the REAL [SidebarControlRow] and [ChatSubHeader], which is one less replica to drift.
     *
     * A macOS chrome is provided because that is the platform the design was drawn for and the one whose
     * traffic lights the shots are meant to show; the [DesktopWindowChrome] default (no window, no
     * gestures) keeps everything else inert, so nothing here needs an AWT window to compose.
     */
    @Composable
    private fun WindowFrame(model: DesktopModel) {
        CompositionLocalProvider(LocalWindowChrome provides DesktopWindowChrome(mac = true)) {
            Box(Modifier.fillMaxSize()) { DesktopApp(model) }
        }
    }

    private fun seed(block: SeedDesktopModel.() -> Unit = {}) = SeedDesktopModel().apply(block)

    @Test
    fun generate() {
        val W = 1180; val H = 798
        shot("01-shell.png", W, H) { WindowFrame(seed()) } // fleet: machine-grouped sidebar + chat
        shot("02-codex-diff-approval.png", W, H) { WindowFrame(seed { selectSession(sessions[2]) }) }
        shot("03-attention-popover.png", W, H) { WindowFrame(seed { showAttention = true }) }
        shot("04-new-session.png", W, H) { WindowFrame(seed { showNewSession = true }) }
        shot("05-tray-quick-approve.png", W, H) { WindowFrame(seed { showTray = true }) }
        shot("07-command-palette.png", W, H) { WindowFrame(seed { palette = PaletteScope.ALL }) }
        shot("08-settings.png", W, H) { WindowFrame(seed { showSettings = true }) }
        shot("09-help-learning-mobile.png", 390, 844) {
            HelpCenterScreen(HelpEntryPoint.CHAT, onBack = {}, onOpenChanges = {})
        }
        shot("10-help-learning-mobile-light.png", 390, 844) {
            PocketTheme(dark = false) {
                HelpCenterScreen(HelpEntryPoint.CHAT, onBack = {}, onOpenChanges = {})
            }
        }

        val shots = outDir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        println("[screenshots] wrote ${shots.size} files to ${outDir.absolutePath}")
        shots.forEach { println("[screenshots]   ${it.name}  ${it.length() / 1024}KB") }
        assertTrue(shots.size >= 9, "expected at least 9 screenshots")
    }
}

