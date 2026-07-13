package dev.ccpocket.app.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.ChatScreen
import dev.ccpocket.app.ui.FileViewerScreen
import dev.ccpocket.app.ui.ModelPicker
import dev.ccpocket.app.ui.PermissionSheet
import dev.ccpocket.app.ui.QuestionCard
import dev.ccpocket.app.ui.SessionsScreen
import dev.ccpocket.app.ui.StartSessionModeSheet
import dev.ccpocket.app.ui.SubagentCard
import dev.ccpocket.app.ui.UsageScreen
import dev.ccpocket.app.ui.fleet.FleetHomeScreen
import dev.ccpocket.app.ui.share.ShareFolderScreen
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.ShareCreated
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TokenUsage
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.UsageDay
import dev.ccpocket.protocol.UsageModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.ceil
import kotlin.test.Test

/**
 * NOT a test — the marketing frame renderer (see marketing/video/README.md).
 *
 * Renders the REAL phone UI offscreen, frame by frame, driven by scripted mock beats — so promo
 * footage always matches the shipped app and a new feature only needs a new [Show] entry here.
 * Lives in desktopTest deliberately: this code and its mock data can never reach a shipped
 * artifact, and the source set already sees the `internal` screens (SessionsScreen precedent).
 *
 * Opt-in via environment (a bare `desktopTest` run skips it):
 *   SHOWCASE_OUT=/abs/dir [SHOWCASE_ONLY=stream] [SHOWCASE_FPS=30] \
 *     ./gradlew :mobile:composeApp:desktopTest --tests dev.ccpocket.app.showcase.ShowcaseRender
 *
 * Determinism: every frame is a pure function of t — beats mutate repository state at fixed
 * offsets, Compose animations advance via ImageComposeScene.render(tNanos), and the repo scope
 * is Unconfined so nothing depends on wall-clock scheduling.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ShowcaseRender {

    private class Beat(val at: Long, val action: PocketRepository.() -> Unit)
    private class Show(
        val id: String,
        val durationMs: Long,
        val beats: List<Beat>,
        val content: @Composable (PocketRepository) -> Unit,
    )

    private companion object {
        init {
            // isolation: a scratch HOME so SecureStore never reads/writes the developer's real
            // ~/.cc-pocket-app; preview flag hides the demo banner (the marketing-capture switch)
            System.setProperty("user.home", createTempDirPath())
            System.setProperty("ccpPreview", "true")
        }

        fun createTempDirPath(): String =
            java.nio.file.Files.createTempDirectory("ccp-showcase").toString()
    }

    private val convo = "vid"
    private var seq = 0L
    private fun live(executing: Boolean) = SessionLive(
        convoId = convo, workdir = DemoData.LIVE_DIR, sessionId = DemoData.LIVE_SESSION_ID,
        mode = PermissionMode.DEFAULT, executing = executing,
        model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )
    private fun text(s: String) = AssistantChunk(convo, seq++, StreamPiece.Text(s))
    private fun think(s: String) = AssistantChunk(convo, seq++, StreamPiece.Thinking(s))
    private fun tool(name: String, preview: String) =
        ToolEvent(convo, seq++, ToolPhase.START, name, preview)

    private val userAsk = "给首页加暗色模式，先问我配色基调，改完自己 commit"

    /** Codex 后端的 live 帧 — 与 [live] 相同的会话身份，但 agent/model 换成 Codex（青色标识）。 */
    private fun liveCodex(executing: Boolean) = SessionLive(
        convoId = convo, workdir = DemoData.LIVE_DIR, sessionId = DemoData.LIVE_SESSION_ID,
        mode = PermissionMode.DEFAULT, executing = executing,
        model = "gpt-5-codex", agent = AgentKind.CODEX,
    )

    private fun shows(): List<Show> {
        // permtimeout 的剧本状态：PermissionSheet 的读秒由内部 LaunchedEffect+delay 自跑（离屏渲染下
        // 不确定），所以由 Beat 换 askId + 递减 timeoutSec 来重置内部 remember(askId) 驱动读秒数字；
        // ≤5s 数字/环翻红，最后 timedOutSignal（daemon 权威信号，同 PermissionTimeoutTest）翻终态。
        fun permAskAt(sec: Int) = PermissionAsk(
            convoId = convo, askId = "perm-$sec", tool = "Bash",
            inputPreview = "git push --force origin main",
            title = "Run command", rule = "git push:*", timeoutSec = sec,
        )
        val permAsk = mutableStateOf(permAskAt(12))
        val permTimedOut = mutableStateOf(false)

        // backend 的剧本状态：StartSessionModeSheet 的选中 agent 是内部 remember——用 key() 重建来切换
        val backendAgent = mutableStateOf(AgentKind.CLAUDE)

        // share 的入参：演示文件夹（与 DemoData 同一虚构用户，不含真实路径）
        val shareDir = DirectoryEntry(path = "/Users/alex/code/cc-pocket", name = "cc-pocket", isDir = true)

        // backfill 的口播素材：断网前流出的前缀 + 补齐帧一次续上的正文
        val bfAsk = "把这次重构整理成迁移文档，写到 docs/migration.md"
        val bfA = "好，我来整理迁移文档。先扫一遍这次的提交记录，"
        val bfB = "把破坏性变更单独列出来。"
        val bfRest = "\n\n文档分三部分：\n\n1. 前置条件与版本要求\n2. 破坏性变更清单（3 处，含改法示例）\n3. 回滚步骤\n\n已写入 docs/migration.md，共 148 行。"

        return listOf(

        // ① 会话列表 + live 会话（「手机接管」的画面底）
        Show("sessions", 5200, beats = listOf(
            Beat(0) { receiveForTest(Sessions(DemoData.LIVE_DIR, DemoData.sessions(DemoData.LIVE_DIR))) },
        )) { repo -> SessionsScreen(repo) },

        // ② 流式干活：思考 → 输出 → 工具调用（「实时看它写代码」）
        Show("stream", 7000, beats = listOf(
            Beat(0) {
                receiveForTest(live(executing = true))
                receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, userAsk))))
            },
            Beat(500) { receiveForTest(think("先读现有的 token 结构，规划暗色色板，再决定切换入口放哪。")) },
            Beat(1600) { receiveForTest(text("我来给首页加暗色模式。先把颜色抽成 token，")) },
            Beat(2400) { receiveForTest(text("再接上系统主题跟随。")) },
            Beat(3100) { receiveForTest(tool("Read", "src/styles/tokens.css")) },
            Beat(3900) { receiveForTest(tool("Edit", "src/styles/tokens.css  +38 −6")) },
            Beat(4700) { receiveForTest(text("token 抽好了，正在给首页接切换入口……")) },
        )) { repo -> ChatScreen(repo) },

        // ③ AskUserQuestion 提问卡（「它问，我点」）— 独立卡片，无弹层依赖
        Show("askq", 5600, beats = emptyList()) { _ ->
            Box(Modifier.fillMaxSize().background(Tok.base).padding(14.dp), contentAlignment = Alignment.Center) {
                QuestionCard(
                    ask = PermissionAsk(
                        convoId = convo, askId = "ask-q1", tool = "AskUserQuestion",
                        inputPreview = "", title = "Claude 想确认一下", neverRemember = true,
                        questions = listOf(
                            AskQuestion(
                                question = "暗色模式用哪套配色基调？", header = "配色",
                                options = listOf(
                                    AskOption("暖调近黑", "官网同款 · #0E0F11"),
                                    AskOption("石墨蓝灰", "冷调，更中性"),
                                    AskOption("纯黑 OLED", "省电，对比最硬"),
                                ),
                            ),
                        ),
                    ),
                    onAnswer = { _, _ -> }, onSkip = {},
                )
            }
        },

        // ④ 完成态：commit 工具 + TurnDone 落章（「干完了」）
        Show("done", 5600, beats = listOf(
            Beat(0) {
                receiveForTest(live(executing = true))
                receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, userAsk))))
                receiveForTest(text("暗色模式完成：token 抽离、系统跟随、对比度用例全过。"))
            },
            Beat(700) { receiveForTest(text("按约定，我自己提交了：")) },
            Beat(1500) { receiveForTest(tool("Bash", "git commit -m \"feat: dark mode with system-follow\"")) },
            Beat(2600) { receiveForTest(TurnDone(convo, usage = TokenUsage(inputTokens = 48_213, outputTokens = 6_402))) },
        )) { repo -> ChatScreen(repo) },

        // ⑤ 行级 diff 查看器（「改动一目了然」）
        Show("diff", 5600, beats = listOf(
            Beat(0) {
                changedFiles.add(ChangedFile("src/styles/tokens.css", op = "edit", adds = 38, dels = 6))
                viewedFilePath.value = "src/styles/tokens.css"
                viewedFileDiff.value = FileDiff(
                    workdir = DemoData.LIVE_DIR, sessionId = DemoData.LIVE_SESSION_ID,
                    path = "src/styles/tokens.css", adds = 38, dels = 6,
                    diff = """
                        @@ -12,4 +12,9 @@
                         :root {
                        -  --bg: #ffffff;
                        -  --text: #1a1a19;
                        +  --bg: light-dark(#ffffff, #0E0F11);
                        +  --text: light-dark(#1a1a19, #ECEDEE);
                        +  --surface: light-dark(#f7f6f3, #16181B);
                        +  color-scheme: light dark;
                         }
                        +
                        +[data-theme="dark"] { color-scheme: dark; }
                    """.trimIndent(),
                )
            },
        )) { repo -> FileViewerScreen(repo, onBack = {}) },

        // ⑥ 用量页：今日小时柱 + 30 天热力（「看清花了多少」）
        Show("usage", 6000, beats = listOf(
            Beat(250) {
                val hours = listOf(8, 6, 5, 4, 4, 7, 13, 24, 40, 54, 62, 68, 58, 66, 94, 76, 68, 55, 44, 36, 30, 22, 16, 10)
                    .mapIndexed { h, v -> UsageDay("%02d".format(h), v * 9_000L) }
                val heat = listOf(12, 20, 8, 34, 52, 16, 40, 66, 24, 80, 44, 28, 10, 48, 72, 90, 36, 20, 60, 30, 84, 54, 18, 46, 22, 70, 38, 96, 26, 58)
                val days = (0 until 30).map { i ->
                    val d = java.time.LocalDate.of(2026, 6, 13).plusDays(i.toLong())
                    UsageDay("${d.monthValue}/${d.dayOfMonth}", heat[i] * 62_000L, date = d.toString())
                }
                usage.value = Usage(
                    days = days, hours = hours,
                    models = listOf(
                        UsageModel("claude-sonnet-4-5", 41_200_000),
                        UsageModel("claude-opus-4", 9_800_000),
                        UsageModel("gpt-5-codex", 3_100_000, AgentKind.CODEX),
                    ),
                    tokensToday = 1_840_000, requestsToday = 214, costUsdToday = 4.20,
                )
                usageLoading.value = false
            },
        )) { repo -> UsageScreen(repo, onBack = {}) },

        // ⑦ 授权超时：倒计时读秒（12→4，末段翻红）→ daemon 权威超时信号翻「已自动拒绝」红色终态
        Show("permtimeout", 6400, beats = listOf(
            Beat(1000) { permAsk.value = permAskAt(9) },
            Beat(1800) { permAsk.value = permAskAt(7) },
            Beat(2600) { permAsk.value = permAskAt(5) },
            Beat(3200) { permAsk.value = permAskAt(4) },
            Beat(3800) { permTimedOut.value = true },
        )) { _ ->
            Box(Modifier.fillMaxSize().background(Tok.base)) {
                PermissionSheet(
                    ask = permAsk.value, workdir = DemoData.LIVE_DIR, timedOutSignal = permTimedOut.value,
                    onDeny = {}, onOnce = {}, onAlways = {}, onDismiss = {},
                )
            }
        },

        // ⑧ 新建会话二选一：CLAUDE/CODEX agent 卡片 + 各自的模式/预设列表，中段切到 CODEX
        Show("backend", 6000, beats = listOf(
            Beat(3000) { backendAgent.value = AgentKind.CODEX },
        )) { _ ->
            Box(Modifier.fillMaxSize().background(Tok.base)) {
                key(backendAgent.value) { // 选中 agent 是 sheet 内部 remember——换 key 重建即切换选中态
                    StartSessionModeSheet(
                        workdir = DemoData.LIVE_DIR, agent = backendAgent.value,
                        onPick = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        },

        // ⑨ Codex 流式干活：同 stream 的节奏，但 agent/model 是 Codex（青色标识）
        Show("codexstream", 7000, beats = listOf(
            Beat(0) {
                receiveForTest(liveCodex(executing = true))
                receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "把接口限流从 IP 维度改成用户维度，改完补上单测"))))
            },
            Beat(500) { receiveForTest(think("先看现有限流中间件的键是怎么取的，再决定改动面。")) },
            Beat(1600) { receiveForTest(text("我把限流键从 IP 换成用户 ID，")) },
            Beat(2400) { receiveForTest(text("原来的 IP 限流保留作为未登录请求的兜底。")) },
            Beat(3100) { receiveForTest(tool("Read", "server/middleware/rateLimit.ts")) },
            Beat(3900) { receiveForTest(tool("Edit", "server/middleware/rateLimit.ts  +24 −8")) },
            Beat(4700) { receiveForTest(text("改好了，正在补按用户维度的限流单测……")) },
        )) { repo -> ChatScreen(repo) },

        // ⑩ 子 Agent 卡片：执行中 / 已完成 / 失败 三种状态竖排（SubagentCard 直渲，无弹层依赖）
        Show("subagents", 5600, beats = emptyList()) { _ ->
            Column(
                Modifier.fillMaxSize().background(Tok.base).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                SubagentCard(ChatItem.Tool(
                    tool = "Explore", preview = "定位换网后偶发重连卡死的根因",
                    taskId = "sa-run", childCount = 7, lastChild = "Grep(reconnect)",
                ))
                SubagentCard(ChatItem.Tool(
                    tool = "Agent", preview = "补全断线重连的回归测试",
                    taskId = "sa-ok", ok = true, childCount = 12,
                    output = "根因是心跳链路缺写超时。已补 3 个回归用例，全部通过。",
                ))
                SubagentCard(ChatItem.Tool(
                    tool = "Agent", preview = "升级依赖并跑全量测试",
                    taskId = "sa-err", ok = false,
                    output = "构建失败：3 个用例超时，已回滚依赖升级",
                ))
            }
        },

        // ⑪ 多机总览：Demo fleet（四机 · 3 online · 2 waiting）——总览态，不演跨机批准
        Show("fleet", 5600, beats = listOf(
            Beat(0) { demoMode.value = true },
        )) { repo -> FleetHomeScreen(repo, onBack = {}, onOpenInbox = {}) },

        // ⑫ 共享文件夹（owner 侧）：三档访问级 + 有效期的分享面板 → 中段生成邀请（QR + 摘要）
        Show("share", 6400, beats = listOf(
            Beat(3400) {
                receiveForTest(ShareCreated(ok = true, invite = DemoData.sampleInvite(shareDir.path, AccessTier.COLLABORATE, 3L * 24 * 3600)))
            },
        )) { repo -> ShareFolderScreen(repo, shareDir, onBack = {}) },

        // ⑬ 断线补齐：流出两句 → 中段静止（模拟断网）→ ConvoHistory 一次把漏掉的正文续上（TranscriptMerge）
        Show("backfill", 8000, beats = listOf(
            Beat(0) {
                receiveForTest(live(executing = true))
                receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, bfAsk))))
            },
            Beat(600) { receiveForTest(text(bfA)) },
            Beat(1400) { receiveForTest(text(bfB)) },
            // 1400→4800 无新帧：断网静止段
            Beat(4800) {
                receiveForTest(ConvoHistory(convo, listOf(
                    HistoryMessage(ChatRole.USER, bfAsk),
                    HistoryMessage(ChatRole.ASSISTANT, bfA + bfB + bfRest),
                    HistoryMessage(ChatRole.TOOL, "docs/migration.md", tool = "Write"),
                    HistoryMessage(ChatRole.ASSISTANT, "需要我顺手把 CHANGELOG 也更新一版吗？"),
                )))
            },
            Beat(6400) { receiveForTest(TurnDone(convo, usage = TokenUsage(inputTokens = 32_408, outputTokens = 4_117))) },
        )) { repo -> ChatScreen(repo) },

        // ⑭ 模型选择：真实 opus/sonnet/haiku 别名 + 上下文窗 pill + 自定义模型 id 入口；中段选中态移到 Fable
        Show("modelpicker", 6000, beats = listOf(
            Beat(0) { model.value = "claude-sonnet-4-5"; sessionAgent.value = AgentKind.CLAUDE },
            Beat(3200) { model.value = "claude-fable-5" }, // 选中 ✓ 全由 repo.model 派生，改状态即移动
        )) { repo ->
            Box(Modifier.fillMaxSize().background(Tok.base)) {
                Column( // ModelPicker 是 sheet 的内容层：按 PocketSheet 的壳手工铺底（QuestionCard 先例）
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(Tok.raised).padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 26.dp),
                ) { ModelPicker(repo, onBack = {}, onDone = {}) }
            }
        },
        )
    }

    @Test
    fun render() {
        val outRoot = System.getenv("SHOWCASE_OUT") ?: return   // opt-in only
        val fps = (System.getenv("SHOWCASE_FPS") ?: "30").toInt()
        val only = System.getenv("SHOWCASE_ONLY")
        val scale = 2f
        val w = 390; val h = 844

        for (show in shows()) {
            if (only != null && show.id != only) continue
            seq = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = PocketRepository(scope)
            val dir = File(outRoot, show.id).apply { mkdirs() }
            val scene = ImageComposeScene((w * scale).toInt(), (h * scale).toInt(), Density(scale)) {
                PocketTheme(dark = true) {
                    Box(Modifier.fillMaxSize().background(Tok.base)) { show.content(repo) }
                }
            }
            try {
                val frames = ceil(show.durationMs / 1000.0 * fps).toInt()
                var next = 0
                for (i in 0 until frames) {
                    val t = i * 1000L / fps
                    while (next < show.beats.size && show.beats[next].at <= t) {
                        show.beats[next].action(repo); next++
                    }
                    Snapshot.sendApplyNotifications()
                    val img = scene.render(t * 1_000_000L)
                    val png = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode $i")
                    File(dir, "f%05d.png".format(i)).writeBytes(png.bytes)
                }
                println("showcase: ${show.id} → $frames frames @ ${fps}fps")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }
}
