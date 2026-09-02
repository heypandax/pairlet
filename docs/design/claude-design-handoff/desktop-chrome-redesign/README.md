# Desktop Chrome Redesign——顶栏拆除＋侧栏直通窗顶（2026-09-02）

- 在线设计板：`https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Desktop+Chrome+Redesign+v2.dc.html`
- 像素规格：本目录 `Desktop Chrome Redesign v2.dc.html`（定稿，四帧：默认／侧栏收起／2× 细节／分屏＋4b 收起分屏条）。`v1` 仅历史留档（保留全宽标题栏的旧方案，已被否）。
- 需求来源：机主参考 Codex 桌面端顶栏的五点调整＋补充（顶行四操作、分屏状态）。

## 设计要点（v2 定稿）

1. **没有任何横贯窗宽的标题栏**。侧栏整列直通窗口顶；聊天列的子头部是该列第一个元素，顶到窗口上沿。
2. **侧栏顶行（38px，侧栏底色）恰好四个操作**：红绿灯（mac）→ 收起侧栏 → 后退 ‹ → 前进 › → 搜索框（占余宽、齐侧栏右缘收边，260px 下不放 ⌘K 提示）。
3. **设备行降为一行细条**（26px：状态点＋mono 机器名＋小箭头＋铃铛），会话列表从 136px 高度即开始（原 232px）。
4. **聊天子头部**承接原标题栏职责：拖窗区＋双击 zoom＋右端连接状态点；侧栏收起时左端出现「红绿灯＋收起＋‹ ›」簇＋16px 竖分隔线。
5. **分屏**：聚焦列子头部带 2px terracotta 顶端 inset；非聚焦列标题降 #B4B5BC。状态点只出现一次＝最右列右端。收起＋分屏时控件簇在最左列子头部。

## 实现方案（fable 定稿，opus5 执行）

> **范围铁律（机主原话）：这次主要调整顶部结构，不要根据设计稿改其他不相关的区域。**
> 设计稿里对消息流、composer、会话行、底部 footer 的简化是 mock 噪声，一概**不实现**。
> `ChatSubHeader` 保留现有两行结构与全部功能（AgentBadge、终端 chip、ChangesPill/GitPill/⋯、mono 元信息行、LineageBanner），只做「承接标题栏职责」的增量。

### 已完成（本会话已落）

- `DesktopModel` 新增 `canGoBack/canGoForward/goBack/goForward`（惰性默认）；`RepoDesktopModel` 历史栈（sessionKey×workdir 快照流记录、浏览器语义、跨机复用 openPin 路径）；`Main.kt` ⌘[/⌘]；`RepoDesktopModelNavHistoryTest` 两条已绿。

### 待实现（编码工单）

**A. 收起状态提升到 model**
- `DesktopModel`：加 `val sidebarCollapsed: Boolean get() = false`、`fun setSidebarCollapsed(v: Boolean) {}`（惰性默认，seed/preview 不动）。
- `RepoDesktopModel`：实现＋经自己的 `store` 持久化，**沿用原 key `"desktop_sidebar_collapsed"`**（值 "1"/"0"，与 DesktopApp 现状兼容）。
- `DesktopApp.kt`：删本地 `collapsed`/`setCollapsed`，改读写 model；`sidebarW` 仍留本地。`SidebarRevealStrip` 移除（收起态零 chrome，回来的路＝子头部簇的 toggle／⌘\；拖拽把手逻辑不变）。
- `SidePaneModel`（`desktop/SidePaneModel.kt`）：**必须把 `sidebarCollapsed/setSidebarCollapsed/canGoBack/canGoForward/goBack/goForward` 显式委托给 base**——分屏列的簇/箭头才不会拿到接口惰性默认。先看该文件现有委托风格照做。
- `Main.kt`：`onPreviewKeyEvent` 加 ⌘\（`Key.Backslash`）→ `model.setSidebarCollapsed(!model.sidebarCollapsed)`。

**B. 窗口 chrome 钩子下放**
- `Main.kt` 删除 `DkTitleBar(...)` 调用（`if (!fullscreen)` 分支整个去掉；`else if (!mac) FullscreenExitStrip` 保留，非 mac 全屏行为不变）。
- 新建 `DesktopWindowChrome`（放 `WindowChrome.kt`）：`mac: Boolean`、`fullscreen: Boolean`、`onClose/onMinimize/onToggleMax/onToggleFullscreen`、`dragAndZoomModifier: Modifier`（把现 `DkTitleBar` 里那两段 pointerInput——AWT 全局鼠标锚定拖窗＋双击 zoom——原样搬进去，注释一并保留）。以 `CompositionLocal`（默认 = 惰性实例，`Modifier` 空）在 `Main.kt` 提供，包住 `DesktopApp`。
- `DkTitleBar` 本体删除；`TrafficLights`/`WinControls`/`FullscreenExitStrip` 保留复用。

**C. 侧栏顶行（`Sidebar.kt`）**
- `SwitcherHeader` 前插入新 `SidebarControlRow`（38dp，无自底 hairline，间距 2dp，水平 padding 左 12 右 10）：
  1. mac 且非全屏 → `TrafficLights`（右 margin 10dp）；
  2. 收起侧栏按钮：28dp 方形热区、hover `Tok.raised` 圆角 6；图标＝自绘 panel-left（15×13 圆角 2.5 边框＋x=5 竖线，1.5dp 描边，色 `Tok.tx2`）——新建小 Composable（Canvas 或 Box 线段拼装，参照 v2.dc.html:44-47）；
  3. ‹ ›：28dp 热区，chevron 1.5dp 描边（自绘或 `Icons.Rounded` 里合适的 KeyboardArrowLeft/Right 缩到 16dp——以视觉贴近 mock 为准）；disabled（`!canGoBack`/`!canGoForward`）时色 #4A4E55 系（用 `Tok` 里最接近的 muted 降档）、无 hover 无 tooltip；
  4. 搜索框：`weight(1f)`、高 26、圆角 7、hairline 边框、`Tok.base` 底、放大镜 12dp＋`Res.string.search` 12sp muted（**tightCenter**）、hover 边框提亮；点击 → `model.palette = PaletteScope.ALL`。不渲染 ⌘K 提示（260px 放不下，设计定稿如此）。
- tooltip：用 `androidx.compose.foundation.TooltipArea`（compose desktop 自带），内容＝raised 底圆角 5 小胶囊「文案＋快捷键」（参照 mock:323-330；胶囊内文字 tightCenter）。新增字符串资源（values＋values-zh）：`tooltip_toggle_sidebar`（⌘\）、`tooltip_prev_session`（⌘[）、`tooltip_next_session`（⌘]）。
- `SwitcherHeader` 瘦身为 26dp 一行：保留 osIcon（14→12dp 可调）＋机器名（mono 11sp，tightCenter）＋在线状态点／reconnecting 文案＋下拉小箭头＋右端铃铛（含 AttentionBadge）；**删去 `Key("⌘0")` 键帽视觉**（快捷键本身不动）。点击行为全部保留。行下 hairline 保留。
- 侧栏其余分区（NewSessionRow 起）一律不动。

**D. 聊天子头部承接（`ChatPane.kt` 的 `ChatSubHeader`，约 :1066 起）**
- 槽位感知：`DesktopApp.kt` 的列循环里为每列 `CompositionLocalProvider(LocalPaneEdge provides PaneEdge(leftmost, rightmost))`（新 data class，默认值 `PaneEdge(true, true)`——单列与既有测试零感知）。watch 分支：ChatPane=（true,true），`WatchPane` 不动。docked workflow 面板忽略。
- 第一行增量（保持现有内容与顺序不变）：
  1. **左端**：`model.sidebarCollapsed && leftmost` 时插入簇——mac 非全屏 → TrafficLights；然后 toggle＋‹ ›（同 C 的组件，直接复用）；随后 1×16dp hairline 竖线、间距参照 mock:190-211；
  2. **右端**：`rightmost` 时在行尾加连接状态点（28dp 热区＋`PulseDot(Tok.ok, 7.dp)`，点击 `model.showTray = !model.showTray`）；非 mac 再接 `WinControls`（关/最小化/最大化从 chrome 钩子取）；
  3. **拖窗**：第一行中部的弹性空白区（现有 `Spacer(weight)`/等价区域）套 `chrome.dragAndZoomModifier`——**只挂在空白弹性区，不挂整行**（子控件点击优先，DkTitleBar 的既有教训）；
  4. **聚焦 accent**：`focused && model.sidePanes.isNotEmpty()` 时子头部顶端画 2dp `Tok.accent` inset 线（mock:462 的 `inset 0 2px 0 #D97757`）。
- 第二行（mono 元信息）与其余一概不动。

**E. 截图测试同步**
- `desktopTest/.../DesktopScreenshotTest.kt` 的 `WindowFrame`（:79 起）是顶栏静态复刻——按新结构重写（去标题栏、侧栏顶行四操作），保测试绿。

**F. 验收**
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:desktopTest` 全绿；
- 迭代期可 `--tests` 定向（check-all 三档惯例）；
- **禁止**：跑 `:daemon:run`、动 daemon/relay、`bash scripts/update-local-daemon.sh`。

### 平台差异备忘

| 平台 | 红绿灯 | 窗控 | 全屏 |
|---|---|---|---|
| macOS | 侧栏顶行（展开）／最左列子头部（收起）；全屏隐藏 | — | 系统菜单栏自现，无额外 chrome |
| Windows/Linux | 无 | `WinControls` 移到最右列子头部行尾 | `FullscreenExitStrip` 不变 |

## 追加交付（同批，均已实现）

- **右键菜单重设计**（`Context Menu v1.dc.html`）：`PocketContextMenu.kt` 自绘 `ContextMenuRepresentation`＋`LocalContextMenuRepresentation` 全局挂载（含文本框剪切/复制菜单）；`PocketMenuItem`（removal hover 转红/双色前缀）＋`joinMenuFamilies` 族分隔；侧栏两处菜单已迁移。
- **收起态左缘悬停窥视**：4dp 热区滑入覆盖层侧栏（不回流）、离开 250ms 宽限收回；切换器/铃铛/新会话浮层/右键菜单（`anyMenuOpen`）/拖拽分屏进行中按住不收。

## 落地状态

- [x] 会话导航历史（model＋⌘[/⌘]＋单测）
- [x] A–E 编码（opus5 工单）
- [x] `desktopTest` 全绿（1224 tests / 0 failures）
- [ ] 真机目验（macOS＋Windows）——发版前必补

### 编码期的两处方案外增补（已实现，需复核）

-1. **切换抖动修复＋过渡动画**（真机目验＋录屏抽帧两轮定位）。三个抖动源与修法：
   - **主犯＝侧栏内容随动画宽度回流**：`Box(width=animW)` 的父约束会钳制 `Sidebar(width=260)`（Compose 约束覆盖 preferred width，录屏 f003 帧＝40px 宽时整列图标竖排、文字全掉）。修法＝子加 `wrapContentWidth(Start, unbounded=true)`——按全宽测量一次、边缘纯 wipe，配 `animateFloatAsState`（200ms FastOutSlowIn）＋`clipToBounds`。
   - **header 高度动画＝标题/元信息行整体弹跳**：padV 10→5 的做法废弃，行高恒定；簇 y 差 5dp 用 `offset(y=-5.dp)`（draw-time，不动布局）对齐侧栏行 y=19 中线。
   - **簇延迟展开＝标题先左移后右飞**：改为「空间与滑动同步 expand/shrink（同 `SIDEBAR_ANIM_MS` 时钟）＋控件晚 120ms 淡入/立即淡出」——标题单段连续运动，交接读作同一簇换了主人。padStart 18→12 仍动画。
0. **无会话的聊天列同样需要 chrome**（`EmptyChatChromeRow`，真机目验抓出）。`ChatPane` 的 `!hasChat` 分支（空状态/打开中/打开失败）跳过 `ChatSubHeader` 直接 return——拆掉标题栏后这条路径整窗无拖拽面，且侧栏收起时连展开按钮都没有。补 38dp 行：收起簇（leftmost）＋全宽拖窗区＋状态点/WinControls（rightmost）。
1. **配对前的连接页需要自己的 chrome**（`ConnectChromeRow`）。方案 B 删掉 `DkTitleBar` 后，`ConnectPanel` 这条分支既没有侧栏也没有子头部，undecorated 窗口在配对完成前将**无法拖动 / 缩放 / 关闭**。补了一条只有红绿灯＋拖窗区＋`WinControls` 的 38dp 裸条（无标题、无搜索、无状态点——此时既没有会话也没有链路可报）。
2. **侧栏锚定的三个浮层上移 16dp**：顶行 38＋设备行 26＋hairline＝65dp（原 header 49dp）。`switcherOpen` / `showAttention` 52→68，`showNewSession` 84→100。不改会与新的侧栏顶部几何错位。
