package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoListState
import dev.ccpocket.app.memo.MemoScreen
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.action_back
import dev.ccpocket.app.resources.memo_back_to_sessions_cd
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.ChatHeader
import dev.ccpocket.app.ui.git.GitDiffScreen
import dev.ccpocket.app.ui.git.GitPanelScreen
import dev.ccpocket.app.ui.git.WorktreesScreen
import dev.ccpocket.app.ui.memo.MemoFx
import dev.ccpocket.app.ui.memo.VoiceMemoScreen
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone has ONE top-left back: [BackTarget]. Every screen that can be left from its top-left corner —
 * pushed pages, a sheet's second page, the entry flow — puts that same 48 dp target 4 dp from the leading
 * edge, so the chevron sits on one line across the app and always answers to the same name.
 *
 * Screens used to draw their own (← text buttons, a Material arrow, grey and white ‹ glyphs in four sizes,
 * two hand-drawn chevrons, "‹ Settings" links), each a little off the others. Asserting the target's slot
 * and name on every screen that has one is what keeps a new header from quietly bringing its own back.
 *
 * Set `BACK_SHOTS_OUT` to also write each header and a stacked contact sheet (with a guide on the chevron's
 * centre line) for design review.
 */
@OptIn(ExperimentalTestApi::class)
class BackTargetUiTest {

    private val output: File? = System.getenv("BACK_SHOTS_OUT")?.let(::File)?.apply { mkdirs() }

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-back-target", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "Panda · MacBook Pro",
    )

    /** [label] null = the generic "Back". */
    private class Scene(
        val name: String,
        val label: (() -> String)? = null,
        val seed: PocketRepository.() -> Unit = {},
        val content: @Composable (PocketRepository) -> Unit,
    )

    private val scenes = listOf(
        Scene("settings", content = { SettingsScreen(it) {} }),
        Scene("chat-header", content = {
            ChatHeader(
                title = "Refactor auth module", summary = emptyList(), workdir = null,
                expanded = false, onToggleContext = {}, onBack = {},
            )
        }),
        Scene("sessions", seed = { enterDemo(); listSessions(DIR) }, content = { SessionsScreen(it) }),
        Scene("archived", content = { ArchivedSessionsScreen(it) {} }),
        Scene("terminal", seed = { workdir.value = DIR }, content = { TerminalScreen(it) {} }),
        Scene("usage", content = { UsageScreen(it, onBack = {}) }),
        Scene("help", content = { HelpCenterScreen(HelpEntryPoint.SETTINGS, onBack = {}) }),
        Scene("file-viewer", seed = { workdir.value = DIR; viewedFilePath.value = "docs/README.md" }, content = {
            FileViewerScreen(it, onExit = {}, onBack = {})
        }),
        Scene("git-panel", content = { GitPanelScreen(it, onBack = {}, onOpenFiles = {}, onOpenWorktrees = {}) }),
        Scene("git-diff", seed = { gitDiffPath.value = "src/main/kotlin/App.kt" }, content = {
            GitDiffScreen(it, onBack = {}, onOpenFiles = {}, onOpenTerminal = {})
        }),
        Scene("worktrees", content = { WorktreesScreen(it, onOpenSessionHere = {}, onBack = {}) }),
        Scene("workflow-run", seed = {
            workflowRuns[RUN] = WorkflowRun(RUN, "release-pipeline", WorkflowRunStatus.RUNNING)
            viewedWorkflowRunId.value = RUN
        }, content = { WorkflowRunScreen(it) {} }),
        Scene("schedule", content = { ScheduleScreen(it) {} }),
        Scene("onboarding", content = { OnboardingScreen(onPairNow = {}, onBack = {}) }),
        Scene("pairing", content = { PairingScreen(it) }),
        Scene("memo-list", label = { str(Res.string.memo_back_to_sessions_cd) }, content = {
            VoiceMemoScreen(
                MemoUiState(
                    readiness = MemoFx.readiness(MemoBlock.NONE), screen = MemoScreen.LIST,
                    list = MemoListState(loaded = true, rows = emptyList()),
                ),
                0f, {}, {},
            )
        }),
        // a sheet's second page, inside the 16 dp the quick-actions sheet pads its content by
        Scene("model-sheet", content = {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 40.dp)) { ModelPicker(it, onBack = {}, onDone = {}) }
        }),
    )

    @Test
    fun everyTopLeftBackIsTheSharedTargetOnOneLine() {
        val scale = if (output != null) 2 else 1
        val frames = scenes.map { scene ->
            var frame: Image? = null
            runDesktopComposeUiTest(PHONE_W * scale, PHONE_H * scale) {
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(scale.toFloat())) {
                        val scope = rememberCoroutineScope()
                        val repo = remember { PocketRepository(scope, account()).apply(scene.seed) }
                        PocketTheme(dark = true) {
                            Box(Modifier.fillMaxSize().background(Tok.base)) { scene.content(repo) }
                        }
                    }
                }
                waitForIdle()
                val label = scene.label?.invoke() ?: str(Res.string.action_back)
                val targets = onAllNodes(hasContentDescription(label) and hasClickAction()).fetchSemanticsNodes()
                assertEquals(1, targets.size, "${scene.name}: exactly one top-left target named \"$label\"")
                val b = targets.single().boundsInRoot
                fun dp(px: Float) = px / scale
                assertTrue(abs(dp(b.left) - INSET) < 0.5f, "${scene.name}: target starts ${dp(b.left)} dp in, not $INSET")
                assertTrue(
                    abs(dp(b.width) - TARGET) < 0.5f && abs(dp(b.height) - TARGET) < 0.5f,
                    "${scene.name}: target is ${dp(b.width)}×${dp(b.height)} dp, not $TARGET",
                )
                if (output != null) frame = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
            }
            scene.name to frame
        }
        output?.let { writeSheet(it, frames, scale) }
    }

    /**
     * The chevron's INK sits on the target's centre, which is where every row centres its title. The "‹" glyph
     * this used to be put its ink where its font does — 2.6 dp low on SF Pro — so each back beside a title
     * read as dropped. A drawn chevron has no font metrics to disagree with; this keeps it that way.
     */
    @Test
    fun theChevronIsDrawnOnTheTargetsCentre() {
        val scale = 4
        val side = (TARGET * scale).toInt()
        runDesktopComposeUiTest(side, side) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale.toFloat())) {
                    PocketTheme(dark = true) { Box(Modifier.fillMaxSize().background(Tok.base)) { BackTarget({}) } }
                }
            }
            waitForIdle()
            val px = onRoot().captureToImage().toPixelMap()
            val bg = px[0, 0]
            var top = side; var bottom = -1; var left = side; var right = -1
            for (y in 0 until side) for (x in 0 until side) {
                val c = px[x, y]
                if (abs(c.red - bg.red) + abs(c.green - bg.green) + abs(c.blue - bg.blue) > 0.35f) {
                    top = minOf(top, y); bottom = maxOf(bottom, y); left = minOf(left, x); right = maxOf(right, x)
                }
            }
            assertTrue(bottom >= 0, "the target draws a chevron")
            val dx = ((left + right + 1) / 2f - side / 2f) / scale
            val dy = ((top + bottom + 1) / 2f - side / 2f) / scale
            assertTrue(abs(dx) <= 0.25f && abs(dy) <= 0.25f, "chevron ink is off the target's centre by ($dx, $dy) dp")
        }
    }

    private fun writeSheet(out: File, frames: List<Pair<String, Image?>>, scale: Int) {
        val cropH = CROP_H * scale
        val labelH = 30
        val width = PHONE_W * scale
        val sheet = Surface.makeRasterN32Premul(width, frames.size * (cropH + labelH))
        val canvas = sheet.canvas
        canvas.clear(Color.makeRGB(40, 40, 40))
        val font = Font(null, 20f)
        val ink = Paint().apply { color = Color.makeRGB(235, 235, 235) }
        val guide = Paint().apply { color = Color.makeARGB(150, 0, 200, 255) }
        val src = Rect.makeWH(width.toFloat(), cropH.toFloat())
        frames.forEachIndexed { i, (name, frame) ->
            val top = (i * (cropH + labelH)).toFloat()
            canvas.drawString(name, 10f, top + 22f, font, ink)
            if (frame == null) return@forEachIndexed
            canvas.drawImageRect(frame, src, Rect.makeXYWH(0f, top + labelH, width.toFloat(), cropH.toFloat()))
            // the chevron's centre line: the inset plus half the target
            canvas.drawRect(Rect.makeXYWH((INSET + TARGET / 2) * scale, top + labelH, 1f, cropH.toFloat()), guide)
            val single = Surface.makeRasterN32Premul(width, cropH)
            single.canvas.drawImageRect(frame, src, src)
            single.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.let { File(out, "$name.png").writeBytes(it.bytes) }
        }
        val png = sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        File(out, "_sheet.png").writeBytes(png.bytes)
        println("back targets: ${frames.size} headers → $out")
    }

    private companion object {
        const val PHONE_W = 402
        const val PHONE_H = 874
        const val CROP_H = 132
        const val INSET = 4f
        const val TARGET = 48f
        const val DIR = "/Users/alex/code/relay-server"
        const val RUN = "wf_demo1234567890"
    }
}
