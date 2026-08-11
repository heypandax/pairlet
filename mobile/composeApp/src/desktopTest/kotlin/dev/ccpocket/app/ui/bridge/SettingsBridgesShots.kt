package dev.ccpocket.app.ui.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.FirstHopHeader
import dev.ccpocket.app.ui.SettingsScreen
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.BridgeRunnerState
import dev.ccpocket.protocol.RUNNER_KIND_FEISHU
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Locale
import kotlin.test.Test

/**
 * Not a gate — a screenshot generator for the two UI 2.1 surfaces (the gate is [BridgesUiTest] and
 * `SettingsIaTest`, which measure instead of comparing pixels).
 *
 * Renders the design's own proof frames offscreen through Skia, the engine the app itself draws with, into
 * `build/screenshots/ui21`: the Settings landing in both languages, and Bridges at the three geometries the
 * responsive claim rests on — 390 dp Chinese, the 320 dp stress width, and 200% type. Deterministic and
 * headless; nothing here asserts, so it can never fail for a font-metric reason. Same shape and purpose as
 * `dev.ccpocket.app.desktop.DesktopScreenshotTest`, one floor up.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SettingsBridgesShots {

    private val outDir = File("build/screenshots/ui21").apply { mkdirs() }
    private val scale = 2 // pixel scale; the sizes below are LOGICAL dp, the scene takes pixels

    private fun shot(name: String, w: Int, h: Int, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = Density(scale.toFloat(), fontScale)
        val scene = ImageComposeScene(w * scale, h * scale, density) {
            CompositionLocalProvider(LocalDensity provides density) {
                PocketTheme { Box(Modifier.fillMaxSize().background(Tok.base)) { content() } }
            }
        }
        try {
            val data = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed for $name")
            File(outDir, name).writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    /** Compose one throwaway frame first — warms font/resource fallback, and for a screen that launches
     *  repository effects, advances them before its proof frame. */
    private fun warmSurface(content: @Composable () -> Unit) {
        val density = Density(scale.toFloat())
        val scene = ImageComposeScene(390 * scale, 844 * scale, density) {
            CompositionLocalProvider(LocalDensity provides density) {
                PocketTheme { Box(Modifier.fillMaxSize().background(Tok.base)) { content() } }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-shot", daemonPub = "pub", deviceId = "dev",
        credential = "cred", hostName = "Panda · MacBook Pro",
    )

    /** One card of each kind: managed+running+trusted, managed+stopped+pending, and unmanaged. */
    @Composable
    private fun Bridges() {
        val scope = rememberCoroutineScope()
        val repo = remember {
            PocketRepository(scope, account()).apply {
                bridgeControl.value = true
                receiveForTest(
                    BridgeListing(
                        listOf(
                            BridgeInfo(
                                name = "research-adapter", workdirs = listOf("/w/cc-pocket", "/w/pocket-nightly"),
                                online = true, activeSessions = 2, tier = AccessTier.COLLABORATE,
                                runner = BridgeRunnerState(RUNNER_KIND_FEISHU, "", running = true, noApproval = true),
                            ),
                            BridgeInfo(
                                name = "design-review-bot", workdirs = listOf("/w/alpha"),
                                pendingTicket = true, tier = AccessTier.REVIEW,
                                runner = BridgeRunnerState(RUNNER_KIND_FEISHU, "", running = false),
                            ),
                            BridgeInfo(
                                name = "self-run-adapter", workdirs = listOf("/w/beta"),
                                online = false, tier = AccessTier.REVIEW, runner = null,
                            ),
                        ),
                    ),
                )
            }
        }
        BridgesScreen(repo, onBack = {})
    }

    @Composable
    private fun Settings() {
        val scope = rememberCoroutineScope()
        val repo = remember { PocketRepository(scope, account()) }
        SettingsScreen(repo, onBack = {})
    }

    @Test
    fun renderTheProofFrames() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            warmSurface { FirstHopHeader(title = "设置", summary = "已连接 Panda", onBack = {}) }
            shot("settings-zh-390.png", 390, 844) { Settings() }
            warmSurface { Bridges() }
            shot("bridges-zh-390.png", 390, 844) { Bridges() }
            shot("bridges-zh-320.png", 320, 844) { Bridges() }   // stress width: whole controls wrap 2 + 2
            shot("bridges-zh-390-200pct.png", 390, 874, fontScale = 2f) { Bridges() }

            Locale.setDefault(Locale.US)
            shot("settings-en-402.png", 402, 874) { Settings() }
            shot("bridges-en-390.png", 390, 844) { Bridges() }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
