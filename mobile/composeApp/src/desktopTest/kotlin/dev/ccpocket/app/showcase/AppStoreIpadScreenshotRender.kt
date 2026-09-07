package dev.ccpocket.app.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.ContentRouter
import dev.ccpocket.app.ui.WideLayoutScope
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TokenUsage
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Locale
import kotlin.test.Test

/**
 * NOT a test — the App Store **iPad** screenshot set (issue #334).
 *
 * App Store Connect will not accept a submission for a universal binary (`TARGETED_DEVICE_FAMILY =
 * 1,2` since v1.9.7) without an iPad screenshot set, so this renderer produces the six 12.9-inch
 * frames the `APP_IPAD_PRO_3GEN_129` slot wants: 1024 × 1366 pt at `Density(2f)` = **2048 × 2732 px**
 * portrait, straight out of the scene — no ffmpeg resize, and therefore no resampled text.
 *
 * Why these are PLAIN device screenshots and not the marketing canvases [AppStoreScreenshotRender]
 * builds for the phone: the thing worth selling on an iPad is the two-pane layout itself, and the
 * phone canvas spends ~40% of its height on a headline and shrinks the UI into a bezel. Full-bleed
 * frames are accepted by App Store Connect and here they are also the more honest artifact — every
 * pixel is the shipped app.
 *
 * What is composed is the REAL routing root, not a re-creation of it: `WideLayoutScope { ContentRouter
 * }` is exactly the pair `App()` mounts (see App.kt — the width is measured once by `WideLayoutScope`
 * and published as `LocalWideLayout`, and `ContentRouter` is the one routing `when`). At 1024dp the
 * measurement lands above `WIDE_LAYOUT_MIN_WIDTH` (700dp) on its own, so these frames prove the real
 * threshold rather than forcing the local.
 *
 * The `SecureApprovalSheet` overlay is deliberately NOT composed (frame 03 shows the same blocked
 * state as Chat pins it). Two reasons, one of them tablet-specific: the acceptance stills already
 * treat the sheet as a separate surface, and at `APPROVAL_SHEET_HEIGHT_FRACTION = 0.945` a sheet on a
 * 1366pt-tall window covers almost exactly the two panes the screenshot exists to show.
 *
 * Determinism and locale follow the rest of the package: every fixture comes from [ShowcaseSeeds]
 * (relative timestamps, invented `/Users/alex` paths, never a real transcript), and the renderer is
 * run ONCE PER LANGUAGE — Compose resources resolve through the JVM default locale, so a single
 * process cannot honestly produce both sets (a zh laptop once produced a half-Chinese English
 * capture; see the `CCP_CAPTURE_LOCALE` note in composeApp/build.gradle.kts).
 *
 * Opt-in via environment; a bare `desktopTest` run renders nothing:
 *
 *   APPSTORE_IPAD_OUT=/abs/fastlane/screenshots SHOWCASE_LANG=en CCP_CAPTURE_LOCALE=en \
 *     ./gradlew :mobile:composeApp:desktopTest \
 *       --tests dev.ccpocket.app.showcase.AppStoreIpadScreenshotRender
 *
 * Output lands in `<APPSTORE_IPAD_OUT>/<locale>/ipadPro129/NN-name.png` — a SUBFOLDER, which is what
 * keeps the two device sets apart. `fastlane deliver` globs a language folder non-recursively
 * (Deliver::Loader::LanguageFolder#file_paths) and only expands `appleTV`/`iMessage`, so it never
 * sees these files; `scripts/sync-appstore-screenshots.rb` uploads them to the display type by name.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AppStoreIpadScreenshotRender {

    private companion object {
        init {
            // same scratch/preview contract as ShowcaseRender: keep generated-media paths off the real
            // HOME, and hide the demo banner (the marketing-capture switch)
            System.setProperty("user.home", java.nio.file.Files.createTempDirectory("ccp-ipad-store").toString())
            System.setProperty("ccpPreview", "true")
        }

        /** 12.9-inch iPad Pro, portrait. 1024 × 1366 pt × 2 = the 2048 × 2732 px ASC wants. */
        const val WIDTH_DP = 1024
        const val HEIGHT_DP = 1366
        const val SCALE = 2f
    }

    /** One localized reel of chat copy. Every string is invented — never a real transcript. */
    private class Script(
        val earlierAsk: String,
        val earlierReply: String,
        val earlierDone: String,
        val prompt: String,
        val thinking: String,
        val say1: String,
        val say2: String,
        val shotSay: String,
        val done: String,
    )

    private fun script(lang: String): Script = if (lang == "zh") Script(
        earlierAsk = "先把 stream parser 的分片解析补个单测。",
        earlierReply = "我加了一条用例：一帧被拆到两个 chunk 里时，解析器只能吐一个事件。",
        earlierDone = "测试过了。顺手把还在读 TokenStore 的调用点也扫了一遍。",
        prompt = "把 relay 的重连回路补稳一下，改完跑一遍协议测试。",
        thinking = "先看重连的计时器挂在哪个 scope 上——socket 一关它大概就跟着没了。",
        say1 = "重试的计时器和 socket 同一个 scope，socket 一关就被取消。我把它挪到独立 scope，",
        say2 = "退避重连再加一道守卫，关闭的 socket 杀不掉它。",
        shotSay = "定价页我截了一张，改版后的排版是对的。",
        done = "守卫加好了，协议测试全过。改动在 relay/src/net/WsClient.kt。",
    ) else Script(
        earlierAsk = "add a unit test for the stream parser",
        earlierReply = "The parser now emits exactly one event when a frame is split across chunks.",
        earlierDone = "Tests pass. I also checked the remaining call sites that read `TokenStore`.",
        prompt = "Harden the relay reconnect loop, then run the protocol tests.",
        thinking = "Reading the reconnect path first — the retry timer looks like it dies with the socket.",
        say1 = "The retry timer shares the socket's scope, so closing the socket cancels it. I'll move it out,",
        say2 = " then guard the backoff so a closed socket can't take it down.",
        shotSay = "Captured the pricing page — the reworked layout holds up.",
        done = "Guard added and the protocol tests are green. The change is in relay/src/net/WsClient.kt.",
    )

    /** One iPad still. The content is ALWAYS the real two-pane root; only the seeded state differs. */
    private class Frame(
        val name: String,
        val dark: Boolean = true,
        val seed: PocketRepository.(Script) -> Unit,
    )

    // ── seeding helpers (shared by several frames) ───────────────────────────────────────────────

    /** A paired, attached machine — not a connecting spinner. */
    private fun PocketRepository.attached() {
        phase.value = ConnPhase.Ready
        connected.value = true
        sessionActive.value = true
    }

    /** The list column showing the session list of the live project, with one row needing a human. */
    private fun PocketRepository.sessionList(blocked: Boolean = true) {
        receiveForTest(ShowcaseSeeds.sessions())
        if (blocked) receiveForTest(ShowcaseSeeds.blockedApprovals())
    }

    /**
     * The chat column mid-turn: the user's ask, thinking, prose, and two real tool calls.
     *
     * The transcript is built from the localized [Script] rather than [ShowcaseSeeds.transcript] —
     * these frames are PUBLISHED per locale, and `renderSiteLoop` set the precedent that the
     * conversation is localized while the structural demo data (repos, branches, session titles)
     * stays in the English a developer's own repository would be in.
     */
    private fun PocketRepository.streamingTurn(s: Script): Long {
        var n = 0L
        receiveForTest(ShowcaseSeeds.live(executing = true))
        // a finished exchange above the live one: an iPad chat column is 1366pt tall, and a single
        // turn leaves it two-thirds empty — which reads as an empty app, not as a spacious one
        receiveForTest(
            ConvoHistory(
                ShowcaseSeeds.CONVO_ID,
                listOf(
                    HistoryMessage(ChatRole.USER, s.earlierAsk),
                    HistoryMessage(ChatRole.ASSISTANT, s.earlierReply),
                    HistoryMessage(ChatRole.TOOL, "./gradlew :protocol:test", tool = "Bash", ok = true),
                    HistoryMessage(ChatRole.ASSISTANT, s.earlierDone),
                    HistoryMessage(ChatRole.USER, s.prompt),
                ),
            ),
        )
        receiveForTest(AssistantChunk(ShowcaseSeeds.CONVO_ID, n++, StreamPiece.Thinking(s.thinking)))
        receiveForTest(AssistantChunk(ShowcaseSeeds.CONVO_ID, n++, StreamPiece.Text(s.say1)))
        receiveForTest(AssistantChunk(ShowcaseSeeds.CONVO_ID, n++, StreamPiece.Text(s.say2)))
        receiveForTest(tool(n++, "Read", "relay/src/net/WsClient.kt"))
        receiveForTest(tool(n++, "Edit", "relay/src/net/WsClient.kt  +24 −6"))
        return n
    }

    private fun tool(seq: Long, name: String, preview: String) =
        ToolEvent(ShowcaseSeeds.CONVO_ID, seq, ToolPhase.START, name, preview)

    /**
     * The six frames, in the order the App Store shows them. Frame 1 leads with the empty right pane
     * on purpose: it is the one frame that states what the layout IS before showing it working.
     */
    private fun frames(): List<Frame> = listOf(
        // 01 · The headline frame: sessions on the left, a turn streaming with tool calls on the right —
        // leads the store page because it shows the layout WORKING, not just its shape
        Frame("sessions") { s -> attached(); sessionList(blocked = false); streamingTurn(s) },
        // 02 · Projects in the list column, the chat column waiting — the two-pane shape, stated plainly.
        // The FLAT view is picked deliberately: the demo tree collapses these four projects into two
        // parent folders (`code`, `Library`), which is correct and says nothing about the product.
        Frame("projects") {
            attached(); treeView.value = false; receiveForTest(ShowcaseSeeds.directories())
        },
        // 03 · The same two panes with a real permission request open — Chat pins it, the list marks the row
        Frame("approve") { s ->
            attached(); sessionList(); streamingTurn(s)
            receiveForTest(ShowcaseSeeds.approvalAsk())
        },
        // 04 · #332: a tool RESULT that came back with a picture, rendered as a thumbnail in its own card
        Frame("screenshot") { s ->
            attached(); sessionList(blocked = false)
            var n = streamingTurn(s)
            receiveForTest(ShowcaseSeeds.screenshotToolStart(n++))
            receiveForTest(ShowcaseSeeds.screenshotToolResult(n++))
            receiveForTest(AssistantChunk(ShowcaseSeeds.CONVO_ID, n++, StreamPiece.Text(s.shotSay)))
        },
        // 05 · A finished turn plus the docked subscription strip the list column carries
        Frame("quota") { s ->
            attached(); sessionList(blocked = false)
            val n = streamingTurn(s)
            receiveForTest(AssistantChunk(ShowcaseSeeds.CONVO_ID, n, StreamPiece.Text(s.done)))
            receiveForTest(TurnDone(ShowcaseSeeds.CONVO_ID, usage = TokenUsage(inputTokens = 42_180, outputTokens = 5_360)))
            claudeQuota.value = ShowcaseSeeds.quota()
        },
        // 06 · The light palette: the same frame as 02, because a theme is not a different product
        Frame("light", dark = false) { s -> attached(); sessionList(blocked = false); streamingTurn(s) },
    )

    @Test
    fun render() {
        val outRoot = System.getenv("APPSTORE_IPAD_OUT") ?: return   // opt-in only
        val lang = (System.getenv("SHOWCASE_LANG") ?: "en").lowercase()
        val locale = if (lang == "zh") "zh-Hans" else "en-US"
        val s = script(lang)

        val previousLocale = Locale.getDefault()
        Locale.setDefault(if (lang == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US)
        val dir = File(File(outRoot, locale), "ipadPro129").apply { mkdirs() }
        try {
            frames().forEachIndexed { index, frame ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val repo = PocketRepository(scope, ShowcaseSeeds.ACCOUNT).apply { frame.seed(this, s) }
                val scene = ImageComposeScene(
                    (WIDTH_DP * SCALE).toInt(), (HEIGHT_DP * SCALE).toInt(), Density(SCALE),
                ) {
                    PocketTheme(dark = frame.dark) {
                        // the REAL root pair: the width is measured here and read as LocalWideLayout
                        WideLayoutScope(Modifier.fillMaxSize().background(Tok.base)) { ContentRouter(repo) }
                    }
                }
                try {
                    Snapshot.sendApplyNotifications()
                    // one beat past the 180ms landing grace, so the transcript is revealed rather than
                    // faded out (same two-render dance as ShowcaseRender.renderCoreFrames)
                    scene.render(0L)
                    Snapshot.sendApplyNotifications()
                    val image = scene.render(400L * 1_000_000L)
                    check(image.width == 2048 && image.height == 2732) {
                        "iPad frame ${frame.name} is ${image.width}x${image.height}, expected 2048x2732"
                    }
                    val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode ${frame.name}")
                    File(dir, "%02d-%s.png".format(index + 1, frame.name)).writeBytes(png.bytes)
                    println("ipad frame[$locale]: ${frame.name} → 2048x2732")
                } finally {
                    scene.close()
                    scope.cancel()
                }
            }
            println("appstore ipad screenshots: ${dir.absolutePath}")
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
