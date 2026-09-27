package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ChatPane
import dev.ccpocket.app.desktop.SeedDesktopModel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.AccentTheme
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_LIVE_TAG
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Tool Process Live v1 against its design board (docs/design/claude-design-handoff/tool-process-live-v1), on the
 * real phone ChatScreen and desktop ChatPane, driven by the frames the daemon sends. Checks the one invariant
 * the whole design rests on — the live line is ONE height in every state — and, with TOOL_PROCESS_LIVE_DESIGN_OUT
 * set, keeps review PNGs of the board's scenes.
 */
@OptIn(ExperimentalTestApi::class)
class ToolProcessLiveDesignTest {
    private val convo = "tpl-proof"
    private val prompt = "把登录页的错误提示改成中文"

    private fun SkikoComposeUiTest.save(name: String) {
        val dir = System.getenv("TOOL_PROCESS_LIVE_DESIGN_OUT")?.let(::File) ?: return
        dir.mkdirs()
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private class Driver(val repo: PocketRepository, val convo: String) {
        private var seq = 100L
        fun start(tool: String, preview: String, id: String) =
            repo.receiveForTest(ToolEvent(convo, seq++, ToolPhase.START, tool, inputPreview = preview, toolUseId = id))
        fun done(tool: String, id: String, ok: Boolean = true) =
            repo.receiveForTest(ToolEvent(convo, seq++, ToolPhase.RESULT, tool, ok = ok, toolUseId = id, outcomeOnly = true))
        fun think(text: String) = repo.receiveForTest(AssistantChunk(convo, seq++, StreamPiece.Thinking(text)))
        fun say(text: String) = repo.receiveForTest(AssistantChunk(convo, seq++, StreamPiece.Text(text)))
        fun ask(tool: String, preview: String) = repo.receiveForTest(PermissionAsk(convo, "ask-$seq", tool, preview, title = "Run command"))
    }

    /** The phone chat on a running turn (or a settled one, [running] false), after [drive] fed it frames. */
    private fun phone(
        name: String,
        dark: Boolean = true,
        accent: AccentTheme = AccentTheme.POCKET,
        width: Int = 402,
        scale: Float = 1f,
        running: Boolean = true,
        history: List<HistoryMessage> = listOf(
            HistoryMessage(ChatRole.USER, "上一轮：登录路由已拆成独立模块，测试通过。"),
            HistoryMessage(ChatRole.ASSISTANT, "好的，路由已经拆好。"),
            HistoryMessage(ChatRole.USER, prompt),
            HistoryMessage(ChatRole.ASSISTANT, "我先看一下登录表单的实现。"),
        ),
        check: SkikoComposeUiTest.(Driver) -> Unit = {},
        drive: Driver.() -> Unit,
    ) = runDesktopComposeUiTest(width, 874) {
        mainClock.autoAdvance = false
        var driver: Driver? = null
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                val scope = rememberCoroutineScope()
                val repo = remember {
                    PocketRepository(scope, PairedDaemon("wss://test.invalid", "tpl-proof", "pub", "device", "credential", hostName = "Work Mac")).apply {
                        val agent = if (accent == AccentTheme.CODEX) AgentKind.CODEX else AgentKind.CLAUDE
                        receiveForTest(SessionLive(convo, "/code/acme-web", "session", executing = false, agent = agent))
                        receiveForTest(ConvoHistory(convo, history))
                        if (running) receiveForTest(SessionLive(convo, "/code/acme-web", "session", executing = true, agent = agent))
                    }
                }
                driver = remember { Driver(repo, convo).apply(drive) }
                PocketTheme(dark = dark, accent = accent) {
                    Box(Modifier.fillMaxSize().background(Tok.base)) { ChatScreen(repo) }
                }
            }
        }
        advanceFrameAndWait()
        advanceFrameAndWait()
        check(driver!!)
        save(name)
    }

    private fun SkikoComposeUiTest.liveHeight(): Float =
        onNodeWithTag(TOOL_PROCESS_LIVE_TAG).getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }

    // ── the invariant ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theLiveLineIsOneHeightInEveryState() = phone("phone-states", check = { d ->
        val heights = mutableMapOf<String, Float>()
        fun sample(state: String) { advanceFrameAndWait(); heights[state] = liveHeight() }
        sample("running")
        d.done("Read", "t1"); sample("idle")
        d.think("先确认错误文案集中在哪里"); sample("thinking")
        d.start("Read", "~/code/acme-web/src/login/messages.ts", "t2")
        d.start("Read", "~/code/acme-web/src/login/validate.ts", "t3")
        d.start("Read", "~/code/acme-web/src/i18n/zh.ts", "t4"); sample("parallel")
        d.done("Read", "t2"); d.done("Read", "t3"); d.done("Read", "t4")
        d.start("Bash", "pnpm --filter @acme/web exec vitest run src/login/LoginForm.test.tsx --reporter=verbose --coverage", "t5")
        sample("long target")
        d.ask("Bash", "pnpm --filter @acme/web exec vitest run src/login/LoginForm.test.tsx"); sample("waiting")
        d.repo.receiveForTest(dev.ccpocket.protocol.AskWithdrawn(convo, d.repo.pendingAsk.value!!.askId))
        d.done("Bash", "t5", ok = false); sample("failed")
        d.start("mcp__playwright__browser_navigate", "http://localhost:5173/login?lang=zh-CN", "t6"); sample("long MCP token")
        val first = heights.values.first()
        heights.forEach { (state, h) -> assertEquals(first, h, 0.5f, "live line height in \"$state\": $heights") }
        val header = onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).onFirst().getUnclippedBoundsInRoot()
        assertTrue(header.bottom.value - header.top.value >= 44f - 0.5f, "the header is a 44dp target")
        assertTrue(first >= 31f - 0.5f, "the live line is at least the design's 31dp")
    }) {
        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "t1")
    }

    // ── scenes (review PNGs) ────────────────────────────────────────────────────────────────────────────────

    @Test fun phoneRunningDark() = phone("phone-running-dark") {
        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "t1"); done("Read", "t1")
        start("Grep", "\"errorMessage\" src/", "t2")
    }

    @Test fun phoneParallelLight() = phone("phone-parallel-light", dark = false) {
        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "t1"); done("Read", "t1")
        think("先确认错误文案集中在哪里")
        start("Read", "~/code/acme-web/src/login/errors.ts", "t2")
        start("Read", "~/code/acme-web/src/i18n/zh-CN.json", "t3")
        start("Read", "~/code/acme-web/src/login/LoginForm.test.tsx", "t4")
    }

    @Test fun phoneWaitingTeal() = phone("phone-waiting-teal", accent = AccentTheme.CODEX) {
        start("shell", "pnpm install", "t1"); done("shell", "t1")
        start("shell", "pnpm test login", "t2")
        ask("shell", "pnpm test login")
    }

    @Test fun phoneFailedNarrowLarge() = phone("phone-failed-320-large", width = 320, scale = 1.6f) {
        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "t1"); done("Read", "t1")
        start("Bash", "pnpm --filter @acme/web exec vitest run src/login/LoginForm.test.tsx --reporter=verbose", "t2")
        done("Bash", "t2", ok = false)
    }

    @Test fun phoneExpandedLive() = phone("phone-expanded-live", check = {
        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        advanceFrameAndWait()
        mainClock.advanceTimeBy(200) // past the caret's 150ms turn
        advanceFrameAndWait()
    }) {
        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "t1"); done("Read", "t1")
        start("Grep", "\"errorMessage\" src/", "t2"); done("Grep", "t2")
        start("Read", "~/code/acme-web/src/login/errors.ts", "t3")
    }

    @Test fun phoneSettledDark() = phone(
        "phone-settled-dark",
        running = false,
        history = listOf(
            HistoryMessage(ChatRole.USER, prompt),
            HistoryMessage(ChatRole.ASSISTANT, "我先看一下登录表单的实现。"),
            HistoryMessage(ChatRole.TOOL, "~/code/acme-web/src/login/LoginForm.tsx", tool = "Read", ok = true),
            HistoryMessage(ChatRole.TOOL, "\"errorMessage\" src/", tool = "Grep", ok = true),
            HistoryMessage(ChatRole.TOOL, "src/login/errors.ts", tool = "Read", ok = true),
            HistoryMessage(ChatRole.ASSISTANT, "错误文案集中在 errors.ts。我把 6 条提示改成中文，再跑一遍登录相关的测试。"),
            HistoryMessage(ChatRole.TOOL, "src/login/errors.ts", tool = "Edit", ok = true),
            HistoryMessage(ChatRole.TOOL, "pnpm test login", tool = "Bash", ok = false),
            HistoryMessage(ChatRole.TOOL, "src/login/__snapshots__/LoginForm.test.tsx.snap", tool = "Edit", ok = true),
            HistoryMessage(ChatRole.TOOL, "pnpm test login", tool = "Bash", ok = true),
            HistoryMessage(ChatRole.ASSISTANT, "第一次测试失败是因为快照里还是英文，已同步更新快照，现在 12 个测试全部通过。"),
            HistoryMessage(ChatRole.TOOL, "git diff --stat", tool = "Bash", ok = true),
            HistoryMessage(ChatRole.ASSISTANT, "改动涉及 3 个文件，可以提交了。"),
        ),
    ) {}

    @Test
    fun desktopLiveAndSettled() = runDesktopComposeUiTest(760, 760) {
        mainClock.autoAdvance = false
        val model = object : SeedDesktopModel() {
            override val streaming = true
            override val ask: PermissionAsk? = null
            override val chatTitle = "登录页错误提示"
            override val chatWorkdir = "/code/acme-web"
            override val messages = listOf(
                ChatItem.User(prompt),
                ChatItem.Assistant("我先看一下登录表单的实现。"),
                ChatItem.Tool("Read", "src/login/LoginForm.tsx", ok = true),
                ChatItem.Tool("Grep", "errorMessage src/", ok = true),
                ChatItem.Thinking("…", seconds = 3),
                ChatItem.Tool("Read", "src/login/errors.ts", ok = true),
                ChatItem.Assistant("错误文案集中在 errors.ts。我把 6 条提示改成中文，再跑一遍登录相关的测试。"),
                ChatItem.Tool("Edit", "src/login/errors.ts", ok = true),
                ChatItem.Tool("Bash", "pnpm test login", ok = false),
                ChatItem.Tool("Edit", "src/login/__snapshots__/LoginForm.test.tsx.snap", taskId = "t-run"),
            )
        }
        setContent { PocketTheme { ChatPane(model) } }
        advanceFrameAndWait()
        advanceFrameAndWait()
        val live = onNodeWithTag(TOOL_PROCESS_LIVE_TAG).getUnclippedBoundsInRoot()
        assertEquals(36f, live.bottom.value - live.top.value, 0.5f, "the desktop live line is 36dp")
        save("desktop-live")
        onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).onFirst().performClick()
        advanceFrameAndWait()
        mainClock.advanceTimeBy(200)
        advanceFrameAndWait()
        save("desktop-first-fold-open")
    }
}
