# Fleet 多机并行（Mobile · Desktop）设计落地记录

> 设计源：claude.ai/design 项目 `cc-pocket`（projectId `93b56700-6ed2-46c9-bf81-3fd0b1a6340b`），文件 `cc-pocket/Fleet Mobile.html` + `fleet-mobile.jsx`、`cc-pocket/Fleet Desktop.html` + `fleet-desktop.jsx`（已归档到本目录 `project/cc-pocket/`）。
> 落地日期：2026-07-02。对应设计 brief 见 Obsidian `60_Outbox/2026-07-02-cc-pocket-多机并行设计提示词.md`。

## 设计要旨

四台电脑同时在线并行工作：**移动端 = 分诊器**（一屏一个决定，审批优先），**桌面端 = 指挥中心**（全量并行可见，键盘驱动）。机器身份保持单色——OS 图标 + 等宽主机名 + 状态点，**不引入新强调色**（陶土 = 需要你，青色 = Codex）。

## 落点映射（设计 → Compose 代码）

### 共享 fleet 设计语言（commonMain，两端复用）

| 设计块 | 实现位置 |
|---|---|
| MachineChip / AttentionBadge / FleetStrip / MiniCountdownRing / 分组标签 | `ui/fleet/FleetKit.kt`（新） |
| Fleet 视图模型接缝（FleetMachine / AttentionEntry / FinishedEntry + repo 映射 + DemoFleet） | `ui/fleet/FleetModel.kt`（新） |

### Mobile（board ②–⑤）

| 设计块 | 实现位置 |
|---|---|
| ② Fleet home（总览 + attention banner + 机器卡 + reconnecting 琥珀顶边） | `ui/fleet/FleetHome.kt`（新）；入口 = Projects 头部机器行（`App.kt` DirectoryScreen） |
| ③ Attention inbox（跨机审批队列 + 倒计时环 + Recently finished + All clear 空态） | `ui/fleet/AttentionInbox.kt`（新） |
| ④ 切机 sheet（where you left off + badge + 当前勾） | `ui/fleet/FleetSheets.kt` `MachineSwitcherSheet`；入口 = Chat 连接栏机器名 |
| ⑤ Chat 跨机横幅（浮动不回流，1 条具名 / ≥2 聚合） | `ui/fleet/FleetSheets.kt` `CrossMachineBanner`，挂在 ChatScreen 消息区顶部 |
| 导航接线（fleetOpen / inboxOpen 全屏覆盖 + Android back LIFO） | `ui/App.kt` |

### Desktop（board ⑥–⑧）

| 设计块 | 实现位置 |
|---|---|
| 数据模型（DkMachine / DkAttention / DkWatch + showAttention + jumpMachine） | `desktop/DesktopModel.kt`；Seed = 设计四机场景（`SeedDesktopModel.kt`），Repo = 诚实映射（`RepoDesktopModel.kt`） |
| ⑥ 机器分组侧栏（FleetStrip + 铃铛徽标 + GroupHeader：chip/this Mac/badge/⌘n/折叠） | `desktop/Sidebar.kt`（重写；ComputerSwitcher 退役，点非活跃组头即切换） |
| ⑥ 分屏（聚焦 pane 2px 陶土顶边 + watch pane 只读流 + ⏸ waiting 条） | `desktop/FleetOverlays.kt` `WatchPane`/`PaneHeader` + `DesktopApp.kt` 分屏 + `ChatPane.kt` `focused` 参数 |
| ⑦ Attention popover（紧凑行 + 光标级 Deny/Allow + hover 出 Open session + caution 页脚） | `desktop/FleetOverlays.kt` `AttentionPopover`，铃铛/Watch 条触发 |
| ⑧ 命令面板机器动词（Switch to X ⌘n + badge、New session on X…、Approve pending on X） | `desktop/CommandPalette.kt`（PItem 加 hint/badge/accent） |
| ⌘1–⌘4 跳机器 | `Main.kt` 键盘处理 → `model.jumpMachine(i)` |
| DkBadge（陶土计数 pill） | `desktop/DesktopKit.kt` |

### 测试 / 截图

- `desktopTest/.../FleetUiTest.kt`（新）：移动端 fleet 冒烟——demo 四机总览、收件箱分诊（Allow 出队）、空态、聚合横幅、badge 跟随队列。
- `desktopTest/.../DesktopUiTest.kt`：更新旧断言到新 seed；新增分组侧栏 / watch pane / popover 审批出队 / jumpMachine / 面板机器动词 5 项回归。
- `DesktopScreenshotTest.kt`：`03-computer-switcher` → `03-attention-popover`；01-shell 现在即 fleet 分屏窗口。共 29 测试全绿。

## 数据接缝（最重要的架构决定）

repo 仍是**单连接**（一次只连一台，`switchDaemon` 整体切换）。fleet UI 全部通过 `FleetModel`/`DkMachine` 接缝读数：

- **活跃机器**：真实状态（phase → online/reconnecting、pendingAsk → badge/attention、活动行由 streaming/convoId/open dirs 推导）。
- **其他已配对机器**：诚实显示 `not connected · tap to switch`，点击即切换（现有语义），**不臆造在线状态**。
- **Demo 模式**（App Store 审核 / 截图）：`DemoFleet` 喂设计稿的四机场景，Allow/Deny 本地出队，可展示完整分诊循环。
- 未来多连接 repo 落地时，只改这两个映射文件（`FleetModel.kt` / `RepoDesktopModel.kt`），UI 零改动。

## 多连接已落地（2026-07-02 第二增量）

「每台已配对电脑一条常驻连接」已实现，架构为 **primary + pinned 卫星**：

- `PocketRepository(scope, pinnedTo)`：pinned 实例绑定固定 binding、不读写全局 active、跳过平台 push 单例；连接栈（重连/退避/心跳/watchdog）原样复用，零改动。
- `data/FleetCoordinator.kt`（新）：不变量 `satellites = bindings − primary.paired`，由 snapshotFlow 观察 primary 的 pairedList/active/sessionActive/demoMode 自动重derive——switchDaemon 的 sessionActive 翻转天然保证「同一台机器绝不双连」；onboarding/demo 下回退单连接模式。`FleetRuntime` 全局句柄让 fleet 读模型跨链路聚合而不改任何屏幕签名。
- 聚合接线：`fleetMachines()`/`fleetAttention()`/`resolveAttention()` 与 `RepoDesktopModel.machines/attention` 全部按 accountId 路由到对应链路；桌面侧栏卫星组显示其 live projects（运行中优先，截 5 + more 行）。
- 实测（本机 3 绑定）：App 进程 3 条 wss ESTABLISHED；本机 daemon 日志见卫星设备 handshake+ListDirectories；30s 零新日志（无重连环）；FleetStrip「3 machines · N online」。

## 与设计稿的差异 / 未做

- **跨机审批广播（daemon 侧，下一步）**：daemon 把会话的 PermissionAsk 绑定在「打开它的设备连接（sink）」上——卫星链路收得到 Directories/Sessions，收不到别的设备开的会话的 ask。跨机审批收件箱/横幅要真正点亮，需 daemon 将 ask 广播给账号下所有 attached 设备（+ 显式 resolve 路由与竞态处理），属 daemon+协议改造，走 wire-compat 审查。聚合层已就位，daemon 落地即亮。
- **连接策略（常驻 vs 按需）**：当前默认全部常驻（桌面无感；手机电量策略见「真并发交互缺口」brief ②）。
- **卫星会话流（watch pane 数据源）**：卫星可开会话但激活手势未设计（brief ③）——`watch` 仍 null。
- **机器 OS 与主机名**：配对凭据不含 OS/hostname（QR 只有账号身份），暂按用户命名启发式判断（`osFromName`：win→WIN、linux→LINUX、默认 MAC）；协议层补 hostname/os 上报是后续项（涉及 wire 兼容，须走 protocol-wire-compat 审查）。
- **"this Mac" 标签**：桌面端无法可靠自识别本机 daemon → 仅 Seed 展示；live 不标。
- **倒计时**：live attention 行不显示倒计时（`seconds = null`，不臆造 deadline）；PermissionSheet/InlinePermCard 上的真实 30s 倒计时不变。移动端收件箱行沿用 sheet 的 30s 约定本地走表。
- **桌面侧栏活跃组内容**：设计稿组内直接列会话；实现保留 PROJECTS + SESSIONS 两段（live 导航依赖 projects，规模化必需）——fleet 语言（chip/badge/⌘n/折叠）完全对齐设计。
- **命令面板**：保持既有扁平排序列表（键盘导航 1:1），未做设计稿的分区标题；机器/动作行以 ⌘n keycap 与陶土文案区分。
- **i18n**：fleet 微文案暂英文硬编码（与 multi-agent 落地同一先例），后续补 Res.string 中文资源。
- **切机 sheet 的 "where you left off"**：目前活跃机器显示当前会话/目录，其他机器显示连接状态；真正的 per-machine 上次上下文记忆随多连接改造补。
