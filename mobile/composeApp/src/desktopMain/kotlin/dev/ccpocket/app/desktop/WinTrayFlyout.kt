package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.tray_answer_in_session
import dev.ccpocket.app.resources.tray_needs_you
import dev.ccpocket.app.resources.tray_open_app
import dev.ccpocket.app.resources.win_tray_all_quiet
import dev.ccpocket.app.resources.win_tray_exit
import dev.ccpocket.app.resources.win_tray_hide_flyout
import dev.ccpocket.app.resources.win_tray_hide_note
import dev.ccpocket.app.resources.win_tray_more
import dev.ccpocket.app.resources.win_tray_more_sessions
import dev.ccpocket.app.resources.win_tray_more_waiting
import dev.ccpocket.app.resources.win_tray_notification_settings
import dev.ccpocket.app.resources.win_tray_running
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * Windows 托盘浮层（issue #292）——**换载体，不换内容**。
 *
 * macOS 的 [TrayPopover] 是一枚带指针三角、跟着菜单栏图标 x 坐标走的下拉气泡；那套语法 1:1 搬到
 * Windows 就成了一扇贴在任务栏上方、无所依附的孤儿小窗（三角指向空气，圆角 14dp 也比 Win11 原生
 * 面板圆一大截）。这里给 Windows 换成原生 flyout 语法：**贴工作区右下角**（锚定见
 * [winFlyoutOrigin]，不再跟随图标 x）、无指针、窗口圆角 8dp / 控件圆角 4dp（比 mac 更方正）、
 * 更重的投影补偿桌面端拿不到的真实 acrylic。
 *
 * **数据折叠与 mac 完全一致**：[DesktopModel.attention] / [DesktopModel.running] 两条全机群列表、
 * [trayHeaderCounts] 的舰队摘要、[trayVisible] 的分段上限、[TrayRunningSince] 的观测时钟、
 * Allow/Deny 走 [DesktopModel.resolveAttention]——同一份仓库裁决，手机与内联审批卡用的也是它。
 * 所以这个文件里**没有第二套事实来源**，只有第二套排版。
 *
 * 与设计稿的两处必要偏离（都记在 issue #292 里）：
 * 1. 稿子的审批行是「会话标题 + 第二行所请求工具」，但 [DkAttention] 根本不带会话标题（协议里没有），
 *    于是标题落到 `tool`、第二行落到 `preview`——[TrayPopover] 面对同一约束也是这么折的。
 * 2. AskUserQuestion 行（`question = true`）不给 Deny/Allow，换成「到会话里回答」。裸 ALLOW 对 CLI
 *    读作「没有作答」，答案必须以 answers map 搭着 ALLOW 一起回去；这条正确性约束高于版式统一。
 *
 * 颜色一律走 [Tok]，不硬编码稿子里的十六进制（稿子的 `#16171B` / `#E2795A` 就是本 App 暗色板的
 * 同位色，只差一两级）。硬编码会同时废掉亮色主题和 Codex 强调色主题——那才是真的改设计。
 * 稿子里的白色叠层（wash / hover fill / 高光线）按 [Tok.tx] 取 alpha，亮色板下自然反相成压暗。
 */
@Composable
fun WinTrayFlyout(
    model: DesktopModel,
    onOpenMain: () -> Unit = {},
    onExitApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onRelayout: () -> Unit = {},
) {
    val approvals = model.attention
    val running = model.running
    val (computers, sessions) = trayHeaderCounts(model)
    // 与 TrayPopover 同因：**故意不在组合里跑 ticker**。无界 delay 循环会让 UI 测试时钟永远非空闲
    // （waitForIdle 一直给一个自我重排的任务泵帧——历史上真的挂过 24 分钟 CI）。浮层是一瞥即走的
    // 东西，机群有任何变化（或重新打开）都会带出新鲜的标签。
    val nowMs = epochMillis()
    val since = TrayRunningSince.observe(running.map { (m, p) -> runningKey(m, p) }, nowMs)
    val openMain = { model.showTray = false; onOpenMain() }

    var menuOpen by remember { mutableStateOf(false) }
    var menuH by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // 菜单是「窗内浮层」而不是 DropdownMenu：Compose Desktop 的 Popup 同样画在本窗口的 scene 里、
    // 一样会被窗口边界裁掉，却额外引入焦点争夺（浮层窗口失焦即关闭，菜单一弹就把自己关了）。
    // 手写浮层换来的代价是要自己把窗口撑高——[onRelayout] 让宿主重新 pack，[winFlyoutOrigin] 贴底
    // 锚定会把长出来的高度向上生长。菜单展开时页脚下方会临时留白，这是不透明窗口下的已知取舍。
    LaunchedEffect(menuOpen, menuH) { onRelayout() }

    val shape = RoundedCornerShape(WIN_FLYOUT_RADIUS)
    Box(
        modifier.width(360.dp)
            .then(if (elevated) Modifier.shadow(28.dp, shape) else Modifier)
            .clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
            .then(if (menuOpen && menuH > 0.dp) Modifier.heightIn(min = WIN_MENU_TOP + menuH + 8.dp) else Modifier),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 顶部 1px 高光线 + 自顶向下 96px 的极淡 wash：Win11 面板的「玻璃有厚度」暗示，
            // 在拿不到真 acrylic 的 Compose 窗口里靠这两笔把纯色面板从「一块色卡」拉出层次
            Box(
                Modifier.fillMaxWidth().height(1.dp).background(
                    Brush.horizontalGradient(
                        listOf(Tok.tx.copy(alpha = 0.02f), Tok.tx.copy(alpha = 0.14f), Tok.tx.copy(alpha = 0.02f)),
                    ),
                ),
            )
            Column(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(
                        listOf(Tok.tx.copy(alpha = 0.035f), Color.Transparent),
                        startY = 0f, endY = with(density) { 96.dp.toPx() },
                    ),
                ),
            ) {
                WinFlyoutHeader(computers, sessions, menuOpen) { menuOpen = !menuOpen }

                if (approvals.isNotEmpty()) {
                    WinFlyoutDivider()
                    WinSectionLabel(stringResource(Res.string.tray_needs_you), count = approvals.size, accent = true)
                    val (shown, hidden) = trayVisible(approvals, TRAY_MAX_APPROVALS)
                    shown.forEach { a ->
                        WinApprovalRow(
                            a,
                            onDeny = { model.resolveAttention(a, allow = false) },
                            onAllow = { model.resolveAttention(a, allow = true) },
                            onOpen = { openMain(); jumpToMachine(model, a.accountId) },
                        )
                    }
                    // 溢出去的审批落到「审批中心」= 铃铛的全机群待办队列（model.showAttention）。
                    // 稿子写的是「打开审批中心」，而 model.openReviewCenter() 是 ReviewRequest 代码评审
                    // 中心（⌘⇧R，REVIEW-REQUEST.md §12）——名字像，语义不是一回事，路到那里等于把
                    // 待审批的人送进一个完全无关的面板。
                    WinOverflowRow(hidden) { openMain(); model.showAttention = true }
                }

                if (running.isNotEmpty()) {
                    WinFlyoutDivider()
                    WinSectionLabel(stringResource(Res.string.win_tray_running))
                    val (shown, _) = trayVisible(running, TRAY_MAX_RUNNING)
                    shown.forEach { (m, p) ->
                        WinRunningRow(
                            p.name, m.computer.name,
                            elapsed = since[runningKey(m, p)]?.let { elapsedLabel((nowMs - it).coerceAtLeast(0)) },
                        ) { openMain(); model.openRunning(m, p) }
                    }
                }

                // 空闲态只在「一条都没有」时出现：有审批没运行时，稿子的读法是只显示审批段，
                // 而不是补一句「一切安静」去和上面的红点自相矛盾
                if (approvals.isEmpty() && running.isEmpty()) {
                    WinFlyoutDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Dot(Tok.tx.copy(alpha = 0.22f), 6.dp)
                        Text(
                            stringResource(Res.string.win_tray_all_quiet),
                            color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp,
                        )
                    }
                }

                WinFlyoutFooter(
                    moreSessions = (running.size - TRAY_MAX_RUNNING).coerceAtLeast(0),
                    onOpenMain = openMain,
                    onExitApp = onExitApp?.let { exit -> { model.showTray = false; exit() } },
                )
            }
        }

        if (menuOpen) {
            // 点浮层任意别处即收菜单（窗口外点击由宿主的失焦监听负责）
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { menuOpen = false })
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = WIN_MENU_TOP, end = 6.dp)
                    .onSizeChanged { with(density) { menuH = it.height.toDp() } },
            ) {
                WinFlyoutMenu(
                    onHide = { menuOpen = false; model.showTray = false; model.menuBarEnabled = false },
                    onSettings = { menuOpen = false; model.showTray = false; model.showSettings = true; onOpenMain() },
                )
            }
        }
    }
}

// ── 版式常量（稿子里的像素规格） ──────────────────────────────────────────────────────────────────

/** 窗口圆角。控件一律 [WIN_CTRL_RADIUS]——比 mac 的 14dp/10dp 方，Windows 原生感就在这两个数上。 */
internal val WIN_FLYOUT_RADIUS = 8.dp
internal val WIN_CTRL_RADIUS = 4.dp

/** (…) 菜单相对浮层顶部的落点（头部 42dp 高，菜单从按钮下沿 10dp 处展开）。 */
private val WIN_MENU_TOP = 52.dp

/** 稿子的暖色两档：填充用 [Tok.accent] 本色，hover 暖一阶，标签/计数/溢出行则提亮以便在暗底上认读。
 *  用 lerp 从既有 token 推导，而不是新增 token——亮色板下同样成立。 */
private val accentHot: Color @Composable get() = lerp(Tok.accent, Tok.tx, 0.12f)
private val accentSoft: Color @Composable get() = lerp(Tok.accent, Tok.tx, 0.30f)

// ── 组件 ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WinFlyoutDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

/** 头部：22dp terracotta 字标 · CC Pocket · 舰队摘要 · (…) 溢出按钮。 */
@Composable
private fun WinFlyoutHeader(computers: Int, sessions: Int, menuOpen: Boolean, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Tok.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("cc", color = Tok.base, fontFamily = Dk.mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = tightCenter(11.sp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "CC Pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                trayStatsLine(computers, sessions), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val src = remember { MutableInteractionSource() }
        val hovered by src.collectIsHoveredAsState()
        val label = stringResource(Res.string.win_tray_more)
        Row(
            Modifier.size(32.dp).clip(RoundedCornerShape(WIN_CTRL_RADIUS))
                .background(if (hovered || menuOpen) Tok.tx.copy(alpha = 0.07f) else Color.Transparent)
                .hoverable(src).clickable(onClick = onMenu).semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            val ink = if (hovered || menuOpen) Tok.tx else Tok.muted
            repeat(3) { i ->
                if (i > 0) Spacer(Modifier.width(2.5.dp))
                Dot(ink, 3.dp)
            }
        }
    }
}

/** 分区标签：需要你（暖色 + 计数 pill）/ 正在运行（灰）。letterSpacing 照稿 .09em。 */
@Composable
private fun WinSectionLabel(text: String, count: Int = 0, accent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = if (accent) 6.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text, color = if (accent) accentSoft else Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp, style = tightCenter(11.sp),
        )
        if (count > 0) {
            Text(
                "$count", color = accentSoft, fontFamily = Dk.mono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(10.5.sp), textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 17.dp).clip(RoundedCornerShape(999.dp))
                    .background(Tok.accent.copy(alpha = 0.16f))
                    .border(1.dp, Tok.accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/** 机器归属 chip：稿子的方角（4dp）版本，无 OS 图标——48dp 两行行高里塞不下第二个图形符号。 */
@Composable
private fun WinChip(name: String, bright: Boolean) {
    Text(
        name, color = if (bright) Tok.tx2 else Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
        style = tightCenter(10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(WIN_CTRL_RADIUS))
            .background(Tok.tx.copy(alpha = if (bright) 0.07f else 0.055f))
            .border(1.dp, Tok.tx.copy(alpha = if (bright) 0.13f else 0.09f), RoundedCornerShape(WIN_CTRL_RADIUS))
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
    )
}

/**
 * 一条待你处理的审批，行高 48dp 两行。行悬停整行亮起（白 5.5% 填充 + 左侧 2dp 暖色 inset 边），
 * 允许钮同时暖一阶并罩上柔和 halo——指针路径和键盘焦点（焦点走按钮自身的 outline）视觉分得开。
 */
@Composable
private fun WinApprovalRow(a: DkAttention, onDeny: () -> Unit, onAllow: () -> Unit, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().height(48.dp).hoverable(src).clickable(onClick = onOpen)) {
        if (hovered) {
            Box(Modifier.matchParentSize().background(Tok.tx.copy(alpha = 0.055f)))
            Box(Modifier.width(2.dp).height(48.dp).background(Tok.accent)) // 左侧 inset 边
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        a.tool, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    WinChip(a.machine, bright = hovered)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    a.preview, color = if (hovered) Tok.tx2 else Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (a.question) {
                // AskUserQuestion：答案必须以 answers map 搭 ALLOW 回去，摘要面板给不出选项，
                // 所以这里只提供「到会话里回答」——裸 ALLOW 对 CLI 读作「没有作答」
                WinGhostButton(stringResource(Res.string.tray_answer_in_session), hovered, onOpen)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WinGhostButton(stringResource(Res.string.deny), hovered, onDeny)
                    Text(
                        stringResource(Res.string.allow), color = Tok.base, fontFamily = Dk.ui, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, style = tightCenter(12.sp), maxLines = 1,
                        modifier = Modifier.height(30.dp).clip(RoundedCornerShape(WIN_CTRL_RADIUS))
                            // halo：稿子的 0 0 0 3px rgba(accent,.18)。Compose 没有外发光，用一圈
                            // 3dp 的半透明描边做等效的「暖气圈」，视觉重量与稿子一致
                            .then(if (hovered) Modifier.border(3.dp, Tok.accent.copy(alpha = 0.18f), RoundedCornerShape(WIN_CTRL_RADIUS + 3.dp)) else Modifier)
                            .background(if (hovered) accentHot else Tok.accent)
                            .clickable(onClick = onAllow).padding(horizontal = 13.dp)
                            .wrapContentHeightCentered(),
                    )
                }
            }
        }
    }
}

/** 拒绝 / 到会话里回答：30dp 高的 ghost 钮。 */
@Composable
private fun WinGhostButton(text: String, hovered: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (hovered) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
        fontWeight = FontWeight.Medium, style = tightCenter(12.sp), maxLines = 1,
        modifier = Modifier.height(30.dp).clip(RoundedCornerShape(WIN_CTRL_RADIUS))
            .background(Tok.tx.copy(alpha = if (hovered) 0.1f else 0.055f))
            .border(1.dp, Tok.tx.copy(alpha = if (hovered) 0.22f else 0.11f), RoundedCornerShape(WIN_CTRL_RADIUS))
            .clickable(onClick = onClick).padding(horizontal = 11.dp)
            .wrapContentHeightCentered(),
    )
}

/** 固定行高的按钮里把文字压到垂直正中（[tightCenter] 管的是行盒，这里管的是行盒在按钮里的落点）。 */
private fun Modifier.wrapContentHeightCentered(): Modifier =
    this.wrapContentHeight(Alignment.CenterVertically)

/** 「还有 N 条在等 → 打开审批中心」：36dp 暖色溢出行，只在审批被上限截断时出现。 */
@Composable
private fun WinOverflowRow(hidden: Int, onClick: () -> Unit) {
    if (hidden <= 0) return
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(36.dp)
            .background(if (hovered) Tok.accent.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(src).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.win_tray_more_waiting, hidden), color = accentSoft, fontFamily = Dk.ui,
            fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一条正在跑的会话，行高 44dp 单行：绿点（带 halo）· 标题 · 机器 chip · 右侧 mono 耗时。 */
@Composable
private fun WinRunningRow(title: String, computer: String, elapsed: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).hoverFill(base = Color.Transparent, hover = Tok.tx.copy(alpha = 0.04f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 6dp 实心点外罩 3dp 的 14% 光晕——稿子的 box-shadow spread，用同心 Box 等效
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Tok.ok.copy(alpha = 0.14f)))
            Dot(Tok.ok, 6.dp)
        }
        Text(
            title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        WinChip(computer, bright = false)
        Spacer(Modifier.weight(1f))
        if (elapsed != null) {
            Text(elapsed, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp, style = tightCenter(11.5.sp), maxLines = 1)
        }
    }
}

/** 页脚 42dp：打开 CC Pocket（左）· +N 个会话（灰，居中）· 退出（右）。 */
@Composable
private fun WinFlyoutFooter(moreSessions: Int, onOpenMain: () -> Unit, onExitApp: (() -> Unit)?) {
    WinFlyoutDivider()
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Tok.tx.copy(alpha = 0.018f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.tray_open_app), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.clickable(onClick = onOpenMain),
        )
        Text(
            if (moreSessions > 0) stringResource(Res.string.win_tray_more_sessions, moreSessions) else "",
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        if (onExitApp != null) {
            Text(
                stringResource(Res.string.win_tray_exit), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                maxLines = 1, modifier = Modifier.clickable(onClick = onExitApp),
            )
        }
    }
}

/** (…) 溢出菜单：214dp 宽、[Tok.raised] 面、圆角 8dp。「隐藏此浮层」直接写 `menuBarEnabled` 这个
 *  既有设置开关——所以关掉之后设置页里的托盘开关也跟着灭，两处永远是同一个事实。 */
@Composable
private fun WinFlyoutMenu(onHide: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.width(214.dp).shadow(22.dp, RoundedCornerShape(WIN_FLYOUT_RADIUS))
            .clip(RoundedCornerShape(WIN_FLYOUT_RADIUS)).background(Tok.raised)
            .border(1.dp, Tok.tx.copy(alpha = 0.1f), RoundedCornerShape(WIN_FLYOUT_RADIUS)).padding(4.dp),
    ) {
        WinMenuItem(stringResource(Res.string.win_tray_hide_flyout), onHide)
        WinMenuItem(stringResource(Res.string.win_tray_notification_settings), onSettings)
        Box(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp).height(1.dp).background(Tok.tx.copy(alpha = 0.08f)))
        Text(
            stringResource(Res.string.win_tray_hide_note), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            lineHeight = 16.5.sp, modifier = Modifier.padding(start = 9.dp, end = 9.dp, top = 2.dp, bottom = 7.dp),
        )
    }
}

@Composable
private fun WinMenuItem(text: String, onClick: () -> Unit) {
    Text(
        text, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(5.dp))
            .hoverFill(RoundedCornerShape(5.dp), hover = Tok.tx.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(horizontal = 9.dp)
            .wrapContentHeightCentered(),
    )
}
