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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.ChatScreen
import dev.ccpocket.app.ui.DirectoryPickerSheet
import dev.ccpocket.app.ui.DirectoryScreen
import dev.ccpocket.app.ui.PairingScreen
import dev.ccpocket.app.ui.entry.ComputersSurface
import dev.ccpocket.app.ui.entry.ConfigureSessionSheet
import dev.ccpocket.app.ui.entry.connRecovery
import dev.ccpocket.app.ui.FileViewerScreen
import dev.ccpocket.app.ui.ModelPicker
import dev.ccpocket.app.ui.QuestionCard
import dev.ccpocket.app.ui.SessionsScreen
import dev.ccpocket.app.ui.StartSessionModeSheet
import dev.ccpocket.app.ui.SubagentCard
import dev.ccpocket.app.ui.UsageScreen
import dev.ccpocket.app.ui.approval.ApprovalUi
import dev.ccpocket.app.ui.approval.SecureApprovalSheet
import dev.ccpocket.app.ui.approval.approvalUi
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
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionRiskUpdated
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
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
            // Generated-media scratch paths still use HOME; SecureStore itself is separately redirected
            // by the desktopTest task. Preview flag hides the demo banner (the marketing-capture switch).
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
                    // 30d window sub-metrics (issue #174): the cards follow the window, labeled "· 30d"
                    requestsWindow = 5_128, cacheHitPctWindow = 82, costUsdWindow = 128.40,
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
                SecureApprovalSheet(
                    approvalUi(
                        permAsk.value, workdir = DemoData.LIVE_DIR, timedOutSignal = permTimedOut.value,
                    ),
                    onDeny = {}, onAllowOnce = {},
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
                        onPick = { _, _, _, _ -> }, onDismiss = {},
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

    // ── Mobile UI 2.0 · Secure Approval acceptance frames ────────────────────────────────────────

    /** One still of [SecureApprovalSheet] at the release baseline. [dark] follows the handoff's own frames. */
    private class ApprovalFrame(val id: String, val dark: Boolean, val ui: ApprovalUi)

    private fun approvalFrames(): List<ApprovalFrame> {
        val dir = DemoData.LIVE_DIR
        fun ask(
            id: String, tool: String, title: String, preview: String,
            rule: String? = null, grants: List<String>? = null, danger: Boolean = false,
            dangerNote: String? = null, neverRemember: Boolean = false, noAutoDeny: Boolean = false,
            diff: String? = null, timeoutSec: Int? = null,
        ) = PermissionAsk(
            convoId = convo, askId = id, tool = tool, title = title, inputPreview = preview, rule = rule,
            grantOptions = grants, danger = danger, dangerNote = dangerNote, neverRemember = neverRemember,
            noAutoDeny = noAutoDeny, diff = diff, timeoutSec = timeoutSec,
        )
        // assessedAt is deliberately left null: it renders as a RELATIVE time, which would make the frames
        // drift with wall-clock. The reason-code half of the same line still proves the layout.
        fun risk(askId: String, level: String, reason: String, vararg codes: String) =
            PermissionRiskUpdated(convo, askId, level, reason = reason, reasonCodes = codes.toList())

        return listOf(
            // 01 · ordinary V2: task + session offered, every tile NEUTRAL (capability ≠ recommendation)
            ApprovalFrame(
                "ordinary", dark = true,
                approvalUi(
                    ask(
                        "ap-1", "Bash", "Run command", "./gradlew :protocol:allTests --rerun-tasks",
                        rule = "Bash(./gradlew test:*)", grants = listOf("once", "task", "session"), timeoutSec = 18,
                    ),
                    workdir = dir, risk = risk("ap-1", "medium", "modifies release tooling", "repo.tooling.write"),
                    queueProgress = 1 to 3,
                ),
            ),
            // 02 · danger: same action SET, emphasis moves to least privilege; dangerNote is bounded
            ApprovalFrame(
                "danger", dark = true,
                approvalUi(
                    ask(
                        "ap-2", "Bash", "Reset the working tree", "git clean -fdx && git reset --hard origin/main",
                        rule = "Bash(git clean:*)", grants = listOf("once", "task"), danger = true, timeoutSec = 24,
                        dangerNote = "Removes the build directory and every untracked file in the working tree, " +
                            "including anything not yet committed.",
                    ),
                    workdir = dir, risk = risk("ap-2", "high", "deletes files", "fs.delete.recursive"), queueProgress = 2 to 3,
                ),
            ),
            // 03 · legacy pre-M2 peer, light theme: no grants, no risk, no queue, 30s fallback
            ApprovalFrame(
                "legacy", dark = false,
                approvalUi(
                    ask("ap-3", "Bash", "Run command", "npm run build", rule = "Bash(npm run build:*)"),
                    workdir = dir,
                ),
            ),
            // 04 · one-off / review shell: Deny + Allow once only, and the record band explains why
            ApprovalFrame(
                "oneoff", dark = true,
                approvalUi(
                    ask(
                        "ap-4", "Bash", "Run command",
                        "ssh deploy@build-01.internal 'sudo systemctl restart ccpocket-relay'",
                        rule = "Bash(ssh:*)", grants = listOf("once", "task", "session"), timeoutSec = 45,
                    ),
                    workdir = dir, risk = risk("ap-4", "medium", "remote host"), handoffReview = true,
                ),
            ),
            // 05 · noAutoDeny: no ring, no number, no ∞ — a truthful waiting state; V2 with only `once`
            ApprovalFrame(
                "noautodeny", dark = true,
                approvalUi(
                    ask(
                        "ap-5", "Edit", "Edit file", "scripts/release.sh", rule = "Edit(scripts/release.sh)",
                        grants = listOf("once"), noAutoDeny = true, timeoutSec = 86_400,
                        diff = """
                            @@ -18,7 +18,9 @@
                             set -euo pipefail
                            -VERSION="${'$'}1"
                            +VERSION="${'$'}{1:?usage: release.sh <version>}"
                            +test -n "${'$'}{GITHUB_TOKEN:-}" || { echo "GITHUB_TOKEN unset" >&2; exit 1; }
                             gh release create "v${'$'}VERSION" --generate-notes
                        """.trimIndent(),
                    ),
                    workdir = dir,
                ),
            ),
            // 06 · grant-aware local display floor: no false 0s/auto-deny claim while the daemon remains authoritative
            ApprovalFrame(
                "authoritywait", dark = true,
                approvalUi(
                    ask(
                        "ap-6", "Bash", "Run command", "./scripts/deploy-preview.sh",
                        grants = listOf("once", "task"), timeoutSec = 0,
                    ),
                    workdir = dir,
                ),
            ),
        )
    }

    // ── Mobile UI 2.0 · Sessions + Chat acceptance frames ───────────────────────────────────────

    /** One Sessions/Chat still at the release baseline. [scale] doubles as the Dynamic Type proof. */
    private class CoreFrame(
        val id: String,
        val dark: Boolean = true,
        val fontScale: Float = 1f,
        val seed: PocketRepository.() -> Unit,
        val content: @Composable (PocketRepository) -> Unit,
    )

    private fun coreFrames(): List<CoreFrame> {
        val dir = DemoData.LIVE_DIR
        val convoId = "core"
        fun live(executing: Boolean) = SessionLive(
            convoId = convoId, workdir = dir, sessionId = "core-s1", mode = PermissionMode.DEFAULT,
            executing = executing, model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
        )
        // one transcript for every Chat frame: a user turn, an agent turn and a real tool result
        val transcript = ConvoHistory(
            convoId,
            listOf(
                HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"),
                HistoryMessage(ChatRole.ASSISTANT, "The parser now emits exactly one event when a frame is split across chunks."),
                HistoryMessage(ChatRole.TOOL, "./gradlew :protocol:test", tool = "Bash", ok = true),
                HistoryMessage(ChatRole.ASSISTANT, "I'm checking the remaining call sites that read `TokenStore`."),
            ),
        )
        // the blocking ask: pinned as Approval required in Chat, and as the loudest row in Sessions
        val ask = PermissionAsk(
            convoId = convoId, askId = "core-ap", tool = "Bash", title = "Upload coverage to Codecov",
            inputPreview = "./gradlew test && bash scripts/upload-coverage.sh",
            grantOptions = listOf("once", "task"), timeoutSec = 600,
        )
        val minute = 60_000L
        fun ago(ms: Long) = dev.ccpocket.app.epochMillis() - ms
        val sessions = Sessions(
            dir,
            listOf(
                SessionSummary(
                    sessionId = "core-s1", title = "Refactor auth module",
                    firstPrompt = "Review the concurrency around the refresh mutex before I open the PR.",
                    messageCount = 24, cwd = dir, lastModified = ago(3 * minute),
                    gitBranch = "feat/auth-refactor", live = true,
                ),
                SessionSummary(
                    sessionId = "core-s2", title = "Fix flaky socket test",
                    firstPrompt = "The reconnect test still fails intermittently on CI.",
                    messageCount = 9, cwd = dir, lastModified = ago(120 * minute),
                    gitBranch = "fix/socket-test", agent = AgentKind.CODEX,
                ),
                SessionSummary(
                    sessionId = "core-s3", title = "Release notes 1.6",
                    firstPrompt = "Summarize the user-visible changes from the last 12 commits.",
                    messageCount = 15, cwd = dir, lastModified = ago(1680 * minute),
                    gitBranch = "main",
                ),
            ),
        )
        val blocked = PendingApprovals(listOf(PendingApproval(ask, workdir = dir, sessionId = "core-s1")))
        return listOf(
            // 01/02 · Sessions in both palettes: context hierarchy, Active/Recent, one pinned dock, and an
            // attention row that is the only filled control on the screen
            CoreFrame("sessions-dark", seed = { receiveForTest(sessions); receiveForTest(blocked) }) { SessionsScreen(it) },
            CoreFrame("sessions-light", dark = false, seed = { receiveForTest(sessions); receiveForTest(blocked) }) { SessionsScreen(it) },
            // 03 · Chat mid-turn: Running is the pinned state because nothing outranks it
            CoreFrame("chat-streaming", seed = { receiveForTest(live(true)); receiveForTest(transcript) }) { ChatScreen(it) },
            // 04 · Chat under a real open approval. The Secure Approval sheet is a ROOT overlay and is
            // deliberately not composed here: this frame proves the block beneath it states the same state
            // in the same grammar without becoming a second decision path.
            CoreFrame(
                "chat-approval",
                seed = { receiveForTest(live(true)); receiveForTest(transcript); receiveForTest(ask) },
            ) { ChatScreen(it) },
            // 05 · Dynamic Type: header, pinned state and composer all still reachable at 200%
            CoreFrame(
                "chat-type200", fontScale = 2f,
                seed = { receiveForTest(live(true)); receiveForTest(transcript) },
            ) { ChatScreen(it) },
        )
    }

    /**
     * NOT a test — the Sessions/Chat acceptance stills at the Mobile UI 2.0 release baseline (402 × 874 pt,
     * iPhone 17), rendered from real protocol frames.
     *
     *   CORE_UI_OUT=/abs/dir ./gradlew :mobile:composeApp:desktopTest \
     *     --tests dev.ccpocket.app.showcase.ShowcaseRender
     */
    @Test
    fun renderCoreFrames() {
        val outRoot = System.getenv("CORE_UI_OUT") ?: return   // opt-in only
        val scale = 2f
        val w = 402; val h = 874
        val dir = File(outRoot).apply { mkdirs() }
        for (frame in coreFrames()) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = PocketRepository(scope, coreAccount).apply(frame.seed)
            val scene = ImageComposeScene((w * scale).toInt(), (h * scale).toInt(), Density(scale)) {
                CompositionLocalProvider(LocalDensity provides Density(scale, frame.fontScale)) {
                    PocketTheme(dark = frame.dark) {
                        Box(Modifier.fillMaxSize().background(Tok.base)) { frame.content(repo) }
                    }
                }
            }
            try {
                Snapshot.sendApplyNotifications()
                // one beat past the 180ms landing grace, so the transcript is revealed rather than faded out
                scene.render(0L)
                Snapshot.sendApplyNotifications()
                val png = scene.render(400L * 1_000_000L).encodeToData(EncodedImageFormat.PNG)
                    ?: error("encode ${frame.id}")
                File(dir, "core-${frame.id}.png").writeBytes(png.bytes)
                println("core frame: ${frame.id} → ${w}x$h @${frame.fontScale}x type")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    // ── Entry Flow UI 2.0 · acceptance frames ────────────────────────────────────────────────────

    /** One entry-flow still at the release baseline. Same shape as [CoreFrame], different surfaces. */
    private class EntryFrame(
        val id: String,
        val dark: Boolean = true,
        val fontScale: Float = 1f,
        val seed: PocketRepository.() -> Unit = {},
        val content: @Composable (PocketRepository) -> Unit,
    )

    private fun entryFrames(): List<EntryFrame> {
        val dir = DemoData.LIVE_DIR
        val minute = 60_000L
        fun ago(ms: Long) = dev.ccpocket.app.epochMillis() - ms
        // real DirectoryEntry shapes only: one live project with a branch and a live title, then plain rows
        // that claim nothing beyond a name, a path and the daemon's own mtime
        val directories = Directories(
            listOf(
                DirectoryEntry(
                    path = dir, name = "cc-pocket", isDir = true, hasSessions = true, recent = true,
                    lastModified = ago(3 * minute), open = true, executing = true,
                    activeSessionId = "entry-s1", activeSessionTitle = "Add demo mode for App Review",
                    gitBranch = "main",
                ),
                DirectoryEntry(
                    path = "/Users/alex/code/cc-pocket-site", name = "cc-pocket-site", isDir = true,
                    hasSessions = true, lastModified = ago(90 * minute),
                ),
                DirectoryEntry(
                    path = "/Users/alex/code/relay-server", name = "relay-server", isDir = true,
                    hasSessions = true, lastModified = ago(600 * minute),
                ),
                DirectoryEntry(
                    path = "/Users/alex/Library/Mobile Documents/com~apple~CloudDocs/notes-cli",
                    name = "notes-cli", isDir = true, hasSessions = true, lastModified = ago(2400 * minute),
                ),
            ),
        )
        return listOf(
            // 01 · Pairing: code-first, camera-free. The scanner is a route below the hairline, never the page.
            EntryFrame("pair") { PairingScreen(it) },
            // 02 · Computers under a real failure: one recovery region, the paired list flat underneath
            EntryFrame("offline", seed = { pairedList.clear(); pairedList.add(coreAccount) }) {
                ComputersSurface(
                    it, recovery = connRecovery(ConnPhase.ComputerOffline), onSwitch = {}, onAdd = {},
                )
            },
            // 03/04 · Projects in both palettes (header v2 + #260): title + Search/Computers/overflow, then
            // the machine state + Review, then the work full-height — and the new-task FAB over its scrim
            EntryFrame("projects-dark", seed = { receiveForTest(directories) }) { DirectoryScreen(it) },
            EntryFrame("projects-light", dark = false, seed = { receiveForTest(directories) }) { DirectoryScreen(it) },
            // 04b · the header at 200% type: row 2 reflows (Review drops under the state sentence) rather
            // than crushing either side, and the FAB is still reachable without scrolling
            EntryFrame("projects-type200", fontScale = 2f, seed = { receiveForTest(directories) }) { DirectoryScreen(it) },
            // 05 · Directory picker: header and decision region pinned, only the middle list scrolls
            EntryFrame("picker", seed = { enterDemo() }) {
                DirectoryPickerSheet(it, onDismiss = {}, onTypePath = {}, onOptions = {}, onStart = {})
            },
            // 06 · Configure · Claude: workdir → Agent → Model → Mode → one Start printing the combination
            EntryFrame("configure-claude") {
                ConfigureSessionSheet(
                    workdir = dir, agent = AgentKind.CLAUDE, computer = "alex-macbook",
                    onPick = { _, _, _, _ -> }, onDismiss = {},
                )
            },
            // 07 · Configure · OpenCode: a statement where a ladder would be, not a disabled one
            EntryFrame("configure-opencode") {
                ConfigureSessionSheet(
                    workdir = dir, agent = AgentKind.OPENCODE, computer = "alex-macbook",
                    onPick = { _, _, _, _ -> }, onDismiss = {},
                )
            },
            // 08 · Dynamic Type: context pinned, body scrolling, the final decision still reachable
            EntryFrame("configure-type200", fontScale = 2f) {
                ConfigureSessionSheet(
                    workdir = dir, agent = AgentKind.CLAUDE, computer = "alex-macbook",
                    onPick = { _, _, _, _ -> }, onDismiss = {},
                )
            },
        )
    }

    /**
     * NOT a test — the Entry Flow acceptance stills at the release baseline (402 × 874 pt, iPhone 17).
     *
     *   ENTRY_UI_OUT=/abs/dir ./gradlew :mobile:composeApp:desktopTest \
     *     --tests dev.ccpocket.app.showcase.ShowcaseRender
     */
    @Test
    fun renderEntryFrames() {
        val outRoot = System.getenv("ENTRY_UI_OUT") ?: return   // opt-in only
        val scale = 2f
        val w = 402; val h = 874
        val dir = File(outRoot).apply { mkdirs() }
        for (frame in entryFrames()) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = PocketRepository(scope, coreAccount).apply(frame.seed)
            val scene = ImageComposeScene((w * scale).toInt(), (h * scale).toInt(), Density(scale)) {
                CompositionLocalProvider(LocalDensity provides Density(scale, frame.fontScale)) {
                    PocketTheme(dark = frame.dark) {
                        Box(Modifier.fillMaxSize().background(Tok.base)) { frame.content(repo) }
                    }
                }
            }
            try {
                Snapshot.sendApplyNotifications()
                scene.render(0L)
                Snapshot.sendApplyNotifications()
                val png = scene.render(400L * 1_000_000L).encodeToData(EncodedImageFormat.PNG)
                    ?: error("encode ${frame.id}")
                File(dir, "entry-${frame.id}.png").writeBytes(png.bytes)
                println("entry frame: ${frame.id} → ${w}x$h @${frame.fontScale}x type")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    /** A real paired binding, so the machine name in the Sessions header is a rendered fact, not a blank. */
    private val coreAccount = dev.ccpocket.app.pairing.PairedDaemon(
        relay = "wss://showcase.invalid", accountId = "showcase", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    /**
     * NOT a test — the Secure Approval acceptance stills at the Mobile UI 2.0 release baseline (402 × 874 pt,
     * iPhone 17). Separate from [render] on purpose: the marketing reel keeps its own scenes, size and
     * cadence, and a bare `desktopTest` run still renders nothing.
     *
     *   APPROVAL_OUT=/abs/dir ./gradlew :mobile:composeApp:desktopTest \
     *     --tests dev.ccpocket.app.showcase.ShowcaseRender
     */
    @Test
    fun renderApprovalFrames() {
        val outRoot = System.getenv("APPROVAL_OUT") ?: return   // opt-in only
        val scale = 2f
        val w = 402; val h = 874
        val dir = File(outRoot).apply { mkdirs() }
        for (frame in approvalFrames()) {
            val scene = ImageComposeScene((w * scale).toInt(), (h * scale).toInt(), Density(scale)) {
                PocketTheme(dark = frame.dark) {
                    Box(Modifier.fillMaxSize().background(Tok.base)) {
                        SecureApprovalSheet(frame.ui, onDeny = {}, onAllowOnce = {})
                    }
                }
            }
            try {
                Snapshot.sendApplyNotifications()
                val png = scene.render(0L).encodeToData(EncodedImageFormat.PNG) ?: error("encode ${frame.id}")
                File(dir, "approval-${frame.id}.png").writeBytes(png.bytes)
                println("approval frame: ${frame.id} → ${w}x$h")
            } finally {
                scene.close()
            }
        }
    }
}
