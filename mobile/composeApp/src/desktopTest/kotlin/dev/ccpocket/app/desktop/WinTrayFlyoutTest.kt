package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.tray_answer_in_session
import dev.ccpocket.app.resources.tray_needs_you
import dev.ccpocket.app.resources.tray_open_app
import dev.ccpocket.app.resources.win_tray_all_quiet
import dev.ccpocket.app.resources.win_tray_exit
import dev.ccpocket.app.resources.win_tray_hide_flyout
import dev.ccpocket.app.resources.win_tray_more
import dev.ccpocket.app.resources.win_tray_more_sessions
import dev.ccpocket.app.resources.win_tray_more_waiting
import dev.ccpocket.app.resources.win_tray_notification_settings
import dev.ccpocket.app.resources.win_tray_running
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Windows 托盘浮层（issue #292）的结构测试。
 *
 * 盯的是「换载体没换内容」这条边界：分段折叠、上限与溢出、空态、菜单写的是哪个开关，以及角锚定的
 * 坐标算术。像素（间距/圆角/投影）不在这里断言——那是设计稿的事，真机目验才算数。
 */
@OptIn(ExperimentalTestApi::class)
class WinTrayFlyoutTest {

    private fun approvals(n: Int) = (1..n).map {
        DkAttention("ask-$it", "acct-studio", "mac-studio", DkOs.MAC, "Tool-$it", "preview $it", seconds = null, live = true)
    }

    @Test
    fun flyoutShowsRealFleetContent() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertPresent("CC Pocket")                              // 头部字标（品牌名，不走翻译）
        assertPresent(str(Res.string.tray_needs_you))           // 与 mac 同一份 label 资源，不另起炉灶
        assertPresent(str(Res.string.win_tray_running))
        assertPresent("Bash")                                   // 真实机群审批的 tool（标题位）
        assertPresent("rm -rf ./build && ./gradlew clean")       // 第二行 = 所请求的动作
        assertPresent("mac-studio")                             // 归属机器 chip
        assertPresent("api-server")                             // 另一台电脑上真实在跑的项目
        assertPresent(str(Res.string.tray_open_app))
    }

    @Test
    fun emptyNeedsYouRendersNoEmptyShell() = runComposeUiTest {
        // 稿子的硬要求：没有待办时「需要你」**整段不渲染**（连 label、计数 pill、空态占位都不留），
        // 而不是像 mac 那样留一行「没有需要你处理的事」——浮层贴着任务栏，每一行都要挣得自己的位置
        val model = object : DesktopModel by SeedDesktopModel() {
            override val attention = emptyList<DkAttention>()
        }
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertTrue(!present(str(Res.string.tray_needs_you)), "空审批队列不该留下「需要你」的空壳")
        assertTrue(!present(str(Res.string.allow)), "没有审批行就不该有允许钮")
        assertPresent(str(Res.string.win_tray_running))          // 运行段照旧
    }

    @Test
    fun approvalsCapAtThreeAndOverflowRowRoutesToTheApprovalQueue() = runComposeUiTest {
        val model = object : DesktopModel by SeedDesktopModel() {
            override val attention = approvals(5)
        }
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertPresent("Tool-1"); assertPresent("Tool-2"); assertPresent("Tool-3")
        assertTrue(!present("Tool-4") && !present("Tool-5"), "审批分段上限 3 条")
        // 溢出行文案带的是**被截断的条数**，不是总数
        assertPresent(str(Res.string.win_tray_more_waiting, 2))

        onAllNodes(hasText(str(Res.string.win_tray_more_waiting, 2))).onFirst().performClick()
        waitForIdle()
        // 稿子写「打开审批中心」= 铃铛的全机群待办队列。model.openReviewCenter() 是 ReviewRequest
        // 代码评审中心（⌘⇧R），名字像但语义无关——路到那里等于把待审批的人送错房间
        assertTrue(model.showAttention, "溢出行打开的是全机群审批队列")
        assertTrue(!model.showReviewCenter, "不是 ReviewRequest 代码评审中心")
    }

    @Test
    fun idleShowsExactlyOneQuietLine() = runComposeUiTest {
        val model = object : DesktopModel by SeedDesktopModel() {
            override val attention = emptyList<DkAttention>()
            override val running = emptyList<Pair<DkMachine, DkProject>>()
        }
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertPresent(str(Res.string.win_tray_all_quiet))
        assertTrue(!present(str(Res.string.tray_needs_you)))
        assertTrue(!present(str(Res.string.win_tray_running)), "一条会话都没有时连「正在运行」的标题都不出")
    }

    @Test
    fun overflowMenuHidesTheFlyoutThroughTheExistingSetting() = runComposeUiTest {
        // 「隐藏此浮层」写的必须是既有的 menuBarEnabled 开关（设置页里那一个），不是第二个私有状态位——
        // 否则托盘关掉了，设置页还显示开着
        val model = SeedDesktopModel()
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertTrue(model.menuBarEnabled, "托盘存在感默认开")
        assertTrue(!present(str(Res.string.win_tray_hide_flyout)), "菜单未展开时不占版面")

        onAllNodesWithContentDescription(str(Res.string.win_tray_more)).onFirst().performClick()
        waitForIdle()
        assertPresent(str(Res.string.win_tray_hide_flyout))
        assertPresent(str(Res.string.win_tray_notification_settings))

        onAllNodes(hasText(str(Res.string.win_tray_hide_flyout))).onFirst().performClick()
        waitForIdle()
        assertTrue(!model.menuBarEnabled, "「隐藏此浮层」写既有的 menuBarEnabled 设置")
    }

    @Test
    fun overflowMenuSettingsItemSurfacesTheMainWindow() = runComposeUiTest {
        // 设置在主窗里；从托盘浮层点进去必须同时把主窗抬起来，否则弹窗开在被压住的窗口下面 = 死点击
        val model = SeedDesktopModel()
        var raised = false
        setContent { PocketTheme { WinTrayFlyout(model, onOpenMain = { raised = true }) } }
        onAllNodesWithContentDescription(str(Res.string.win_tray_more)).onFirst().performClick()
        waitForIdle()
        onAllNodes(hasText(str(Res.string.win_tray_notification_settings))).onFirst().performClick()
        waitForIdle()
        assertTrue(model.showSettings)
        assertTrue(raised)
    }

    @Test
    fun approvalAllowRidesTheRealResolvePath() = runComposeUiTest {
        val model = SeedDesktopModel()
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertEquals(2, model.attention.size)
        onAllNodes(hasText(str(Res.string.allow))).onFirst().performClick()
        waitForIdle()
        assertEquals(1, model.attention.size, "允许走 model.resolveAttention，和手机/内联审批卡同一条裁决")
    }

    @Test
    fun questionRowKeepsTheAnswerInSessionEscape() = runComposeUiTest {
        // AskUserQuestion 的答案必须以 answers map 搭着 ALLOW 回去；摘要面板给不出选项，裸 ALLOW 对
        // CLI 读作「没有作答」。稿子没画这个变体，但正确性高于版式统一——与 mac 保持同一处理
        val q = DkAttention(
            "ask-q", "acct-studio", "mac-studio", DkOs.MAC, "AskUserQuestion", "Which approach?",
            seconds = null, live = true, question = true,
        )
        val model = object : DesktopModel by SeedDesktopModel() {
            override val attention = listOf(q)
        }
        setContent { PocketTheme { WinTrayFlyout(model) } }
        assertPresent(str(Res.string.tray_answer_in_session))
        assertTrue(!present(str(Res.string.deny)), "提问行不给裸 Deny/Allow")
    }

    @Test
    fun footerCarriesRunningOverflowAndExit() = runComposeUiTest {
        val many = (1..8).map { i ->
            DkMachine(DkComputer("acct-$i", "box-$i", DkOs.WIN, online = true, meta = "")) to
                DkProject("~/p$i", "proj-$i", running = true)
        }
        val model = object : DesktopModel by SeedDesktopModel() {
            override val running = many
        }
        var exited = false
        setContent { PocketTheme { WinTrayFlyout(model, onExitApp = { exited = true }) } }
        assertPresent("proj-6")
        assertTrue(!present("proj-7"), "运行分段上限 6 条")
        assertPresent(str(Res.string.win_tray_more_sessions, 2)) // 剩下的落页脚「+N 个会话」

        onAllNodes(hasText(str(Res.string.win_tray_exit))).onFirst().performClick()
        waitForIdle()
        assertTrue(exited)
    }

    @Test
    fun exitHidesWhenTheHostOffersNoExitPath() = runComposeUiTest {
        // onExitApp = null（mac/Linux 的 close 语义）时页脚不该长出一个点了没反应的「退出」
        setContent { PocketTheme { WinTrayFlyout(SeedDesktopModel()) } }
        assertTrue(!present(str(Res.string.win_tray_exit)))
    }

    // ── 角锚定（纯算术，无需显示器） ───────────────────────────────────────────────────────────

    @Test
    fun cornerAnchorHugsTheWorkAreaBottomRight() {
        // 1920×1080，底部 48px 任务栏 → 工作区右下角 (1920, 1032)，浮层 360×420 退 12px
        val a = TrayAnchor(centerX = 1920, y = 1032, fromTop = false, screen = Rectangle(0, 0, 1920, 1080), corner = true, workRight = 1920, workBottom = 1032)
        assertEquals((1920 - 12 - 360) to (1032 - 12 - 420), winFlyoutOrigin(a, 360, 420))
    }

    @Test
    fun cornerAnchorFollowsASideTaskbarAndASecondMonitor() {
        // 任务栏靠右（inset.right = 48）时贴的是工作区右缘，不是屏幕右缘
        val right = TrayAnchor(0, 0, false, Rectangle(0, 0, 1920, 1080), corner = true, workRight = 1920 - 48, workBottom = 1080)
        assertEquals((1920 - 48 - 12 - 360) to (1080 - 12 - 300), winFlyoutOrigin(right, 360, 300))
        // 左侧副屏：屏幕原点为负，落点跟着走（不会算回主屏）
        val second = TrayAnchor(0, 0, false, Rectangle(-1920, 0, 1920, 1080), corner = true, workRight = 0, workBottom = 1032)
        assertEquals((0 - 12 - 360) to (1032 - 12 - 300), winFlyoutOrigin(second, 360, 300))
    }

    @Test
    fun cornerAnchorClampsAFlyoutTallerThanTheScreen() {
        // 长到超过屏幕（极端 DPI / 一堆审批）时夹在屏内，宁可底部被任务栏压住也不整块跑出可视区
        val a = TrayAnchor(0, 0, false, Rectangle(0, 0, 1920, 1080), corner = true, workRight = 1920, workBottom = 1032)
        assertEquals((1920 - 12 - 360) to 12, winFlyoutOrigin(a, 360, 2000))
    }
}
