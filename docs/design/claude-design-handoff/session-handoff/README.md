# Session Handoff（协作接力）— 设计 handoff

- **在线设计板**（登录 b01099485423@gmail.com 即看）：
  <https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=Session+Handoff.html>
- **生成**：2026-08-02，claude.ai/design cc-pocket 正典项目，模型 Opus 5 Medium，一段 brief 一次通过（含自查两轮 Screenshot 修帧）。
- **依据**：`docs/design/SESSION-HANDOFF.md`（已定稿方案），覆盖 §9.4 的 10 个 UI 面；brief 存档 `~/Desktop/Brain/60_Outbox/2026-08-02-cc-pocket-协作接力SessionHandoff设计提示词.md`。
- **本地打开**：本目录 `python3 -m http.server` 后访问 `Session Handoff.html`（样式与 QR 脚本在 `./session-handoff/` 子目录）。

## 文件清单

| 文件 | 内容 |
|---|---|
| `Session Handoff.html` | 一张可平移画布，15 帧（12 主帧 + 过期邀请 4b + 历史空态 10b + 3a/3b 拆分） |
| `session-handoff/handoff.css` | 全部帧共享样式（token、边界卡、chip、banner） |
| `session-handoff/qr.js` | 邀请二维码占位渲染 |

## 帧清单

| 帧 | 内容 | 规格要点 |
|---|---|---|
| 1 | 发起入口 · 会话操作菜单 | ⋯ 锚点 44×44，popover 256px r14；新行是唯一带陶土 glyph 的项；NEW 徽标临时（两个版本） |
| 2 | 发起草稿全屏 sheet | 390×1420 滚动区；hero＝信任边界卡；Role 分段（Continue 禁用带 later 提示）；Expires pill；brief 可编辑折叠卡 |
| 3a | 邀请就绪 sheet | QR 卡 + mono 短码 H7QX-2MRD + 倒计时 + recap 行 + Share…/Copy link |
| 3b | 发起端 WAITING 锁定 | composer 变暗锁定（保草稿），锁 banner：等待点 + Copy invite / Recall（danger ghost） |
| 4 | 接收端接受预览（信任屏） | 第二人称边界卡（You will see / can't do）+ 成本归属行 + Accept/Decline |
| 4b | 过期邀请变体 | 内容压暗 + danger banner + 单 Close |
| 5 | 帧 4 浅色锁定版 | token-for-token 对应 |
| 6 | 接收端接管中（IN_PROGRESS） | 陶土 ribbon（含归还倒计时）；消息按操作者标注（Panda / Frank (you)）；composer 上方 Finish & return pill；read-only tools 盾牌注记 |
| 7 | 发起端旁观 | 中性 ribbon（非陶土）；composer 换 Watching 只读条 + Recall control |
| 8 | 归还流 sheet | 390×900；结构化 Return Result 草稿（verdict chip / findings 带 severity 点 + mono file:line / Verified / next steps），可编辑 |
| 9 | 发起端结果卡（RETURNED） | 流内 Result 卡：verdict + findings 预览 + 验证 mono 行 + Mark reviewed / Open full result；下方注入系统行 |
| 10 | 会话信息页 Handoffs 历史 | 状态 chip 全谱（Completed 绿/Recalled 琥珀/Declined·Expired·Cancelled 灰/In progress 陶土脉冲） |
| 10b | 历史空态 | 一行提示 |
| 11 | 桌面复合 · WAITING | 两栏 + 侧栏 handoff 状态点 + composer 上方锁 banner + 居中 draft modal（双列：会话事实｜边界卡+brief） |
| 12 | 桌面复合 · 接管中 + 结果 | pane header 常驻 Finish & return；Result 卡 820px、findings 两列、超长内部滚动 |

## 设计稿自带的实现注记（每帧下方 mono 注）

- 信任边界卡是**一个组件三个变体**：mobile 堆叠 / desktop modal 两列 / 第一·第二人称文案，全帧共用。
- **状态色法则**写进画布头：waiting＝中性灰（还没在跑）、in-progress＝陶土、returned/completed＝绿、recalled/expired＝琥珀、declined/cancelled＝灰；**danger 红只给 Recall/Revoke**。
- composer 处理按角色刻意不同：发起端 WAITING＝**变暗锁定**（可能有未发草稿要保留）；IN_PROGRESS 旁观＝**整条替换成 watch bar**（无草稿可保）。
- 桌面 guest 侧栏只显示一个会话＋一行显式 "not shared with you"——**缺席要明说，不靠暗示**。

## 落地状态（2026-08-02）

- **protocol**：`Handoff.kt` 全新落地（3 枚举带 UNKNOWN 回退 + 5 模型 + 11 条 `pocket/handoff.*` 消息），`HandoffWireCompatTest` 10 例。
- **daemon**：`handoff/`（Store/Registry/Guard/Service）＋ RequestRouter/SessionRegistry/DeviceSessions/WsConnection 接线，drive gate ＋ 30s sweep ＋ idle-reaper 保护，单测 32 例＋Router 集成 5 例。
- **移动端**：`ui/handoff/`（组件/横幅/三 sheet/接受屏/映射）＋ ChatScreen 集成（菜单入口、header 状态 chip、IN_PROGRESS 双 ribbon、WAITING 锁 banner＋变暗 composer、旁观 watch bar、Finish & return、RETURNED 结果卡 dock、SessionInfo 接力历史）。
- **桌面端**：`HandoffModal.kt`（Frame 11 双列草稿 modal＋邀请 QR modal＋归还 modal）＋ ChatPane（ribbon/锁条/watch bar/820dp 两列结果卡）＋ ⋯ 菜单入口＋ DesktopModel/RepoDesktopModel/Seed 接线。
- **有意偏差**：结果卡 dock 在 composer 上方而非流内（daemon 尚未把结果注入 transcript）；「returns in」倒计时以接管耗时代替（wire 无 lease 到期字段）；侧栏会话行状态点未做（repo 只跟踪打开会话的 handoff）。
- **未做（后续里程碑）**：HANDOFF 受限凭证与跨账号邀请兑换（v1 仅同账号设备）、接续提示词注入 Skill、push 通知、CONTINUE 模式。
