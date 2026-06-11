# v1 设计稿 ↔ 实现 核对清单（Design Audit）

> 来源：Claude Design handoff bundle（2026-06-10 拉取，13 个设计文件 + 发布资产），对照 `mobile/composeApp` 当前实现。
> 判定口径：**实现遗漏** = 设计正确、实现该补；**设计多余** = v1 范围外或已过时，设计稿应标注 P2 或修订；**偏差** = 两边都有但行为不同；**需决策** = 产品方向冲突，需要拍板。
> 优先级：P0 = 发布阻塞；P1 = v1 应包含；P2 = v1 之后。

---

## 1. 总览：设计文件 ↔ 实现状态

| 设计文件 | 对应实现 | 状态 |
|---|---|---|
| Pairing.html | `PairingScreen.kt` | ✅ 已实现（实现超出设计：粘贴链接、局域网直连） |
| Computers.html | 无 | ❌ 未实现 → **设计多余（P2）** |
| Directory.html | `App.kt DirectoryScreen` | 🟡 部分实现（方向有分歧，见 §2.3） |
| Sessions.html | `App.kt SessionsScreen` | 🟡 部分实现（缺预览文案、相对时间等） |
| Chat.html | `App.kt ChatScreen` + `Markdown.kt` | 🟡 部分实现（缺 thinking 折叠、代码块工具条等） |
| Execution Permissions.html（ep-*） | `Permissions.kt` | ✅ 基本一致（缺 withdrawn 态，timeout 按钮不同） |
| Permission.html | — | ⚪ 设计研究稿，已被 ep-* 取代，不作核对基准 |
| Voice Input.html（voice-*） | `VoiceComposer.kt` + repo 语音状态机 | 🟡 S1–S6 齐全，但 **S4（先回填再手动发送）与实现（转写完直接发送）冲突 → 需决策** |
| Image Attachment.html（ia-*） | `ImageAttachment.kt` | ✅ 基本一致（缺发送失败重试态） |
| Settings.html | 无 | ❌ 未实现 → 拆分判定，见 §2.1 |
| Icon.html / icon-1024.png | iOS `AppIcon.appiconset` ✅；**Android 未接入 ❌（P0）** | 🟡 |
| Splash.html / splash-1290x2796.png | iOS `Splash.imageset` ✅；Android 无启动图（可接受） | ✅ |
| Attach Icon Spec.html | `AttachImageIcon.kt` | ✅ |
| appstore/01–06.png | App Store 上架素材 | ✅ 资产就绪（含英文文案，中文区需另出一套，见 §5） |

---

## 2. 设计有、实现没有 —— 逐项判定

### 2.1 Settings 屏（整屏未实现）

| 设计内容 | 判定 | 理由 |
|---|---|---|
| 默认权限模式（**6 个模式**：default / acceptEdits / auto / plan / dontAsk / bypass） | **设计多余（过时）** | 与 ep-* 的 4 阶梯（default / acceptEdits / plan / bypassPermissions）冲突，ep-* 是后出的定稿且与 Claude Code SDK 实际模式对齐。Settings 里的 auto、dontAsk 应删除 |
| 全局「默认权限模式」设置项 | **设计多余（P2）** | 现实现为每次新建会话时选择（StartSessionModeSheet），且 ep-* 安全不变式「新会话总是从 Ask each step 开始」与全局默认互斥。保留每会话选择 |
| 已配对设备列表 + Revoke | **设计多余（P2）** | 当前配对模型是单 daemon 单设备，协议无多设备管理帧。多电脑支持（Computers 屏）排期后做 |
| 外观 System / Dark / Light | **实现遗漏（P2）** | UI-DESIGN.md 承诺「暗色优先 + 完整浅色（跟随系统）」，当前仅暗色。工作量大，放 v1 后 |
| About（Version / License / Daemon 地址） | **实现遗漏（P1）** | 上架基本要求（版本号、开源许可展示）。建议 v1 实现一个最小 Settings：About 三行 + Unpair 入口（把 ConnectScreen 的 Unpair 收进来更顺） |

### 2.2 Computers 屏（整屏未实现）

**判定：设计多余（P2）。** 配对层（`Pairing.kt`）只持久化一台 daemon，协议无多 daemon 会话路由。设计稿保留作为 P2 愿景；v1 的「已配对主屏」是实现里的 ConnectScreen，但它**没有设计稿** → 已在补充文档中补齐（见 V1-DESIGN-SUPPLEMENT.md §4）。

### 2.3 Directory 屏（方向分歧）

| 设计内容 | 实现现状 | 判定 |
|---|---|---|
| Recents 列表 + 「N sessions」橙色 pill | 扁平项目列表 + `history` 文字徽章 | **偏差（P1 对齐）**：协议已有 `hasSessions`，但会话数需要协议加字段；v1 先把 `history` 徽章视觉对齐设计的 pill 样式即可 |
| Browse 文件系统（面包屑钻取 + Use this directory） | 无（daemon 只下发配置目录 + recents 的扁平列表） | **设计多余（P2）**：需要协议新增任意目录浏览帧，v1 不做。设计稿标注 P2 |
| 顶部连接条（电脑名 + 绿点 + Switch） | 无 | Switch 依赖 Computers（P2）；**电脑名 + 连接状态点为实现遗漏（P1）**，落在 Chat/Sessions 头部统一补（见 §2.5） |
| 实现新增：「Open Sessions」live 分区、筛选框、下拉刷新、Exit | 设计稿无 | **实现超出设计** → 已补设计稿（SUPPLEMENT §7） |

### 2.4 Sessions 屏

| 设计内容 | 判定 | 说明 |
|---|---|---|
| 卡片第二行：首条 prompt 预览 | **实现遗漏（P1，零成本）** | 协议 `SessionSummary.firstPrompt` 已有数据，UI 没渲染 |
| 「2h ago / yesterday」相对时间 | **实现遗漏（P1，零成本）** | `SessionSummary.lastModified` 已有数据 |
| New session 副标题「Start Claude in ~/…」 | **实现遗漏（P1，零成本）** | 纯文案行 |
| active 徽章橙色脉冲点 | 实现为 `● running` 静态绿点 | **偏差（P1 对齐）**：视觉细节，统一成设计的脉冲样式 |
| 右上 Settings 齿轮 | **暂缓（随最小 Settings 一起，P1）** | 依赖 §2.1 的最小 Settings 落地 |
| 顶部连接条 | 同 §2.5 | — |

### 2.5 Chat 屏

| 设计内容 | 判定 | 说明 |
|---|---|---|
| 头部显示**会话标题**（非固定「Chat」） | **实现遗漏（P1）** | 列表侧已有 title，打开会话时带过去即可；新会话用首条 prompt 截断 |
| 头部连接条（绿点 + 电脑名 + 路径） | **实现遗漏（P1）** | 设计原则 #5「始终能看到连的哪台电脑·哪个目录」；实现只有路径。电脑名可用 daemon 握手信息 |
| Thinking 折叠行（「Thought for 5s」，点开看推理） | **实现遗漏（P1，偏 bug）** | 现在 `appendChunk` 把 `StreamPiece.Thinking` 直接拼进正文，思考内容和回答混在一起。协议已区分，UI 该分开渲染 |
| 代码块：语言标签 + 复制按钮 | **实现遗漏（P1）** | `Markdown.kt` 目前是裸等宽块；移动端「复制代码」是高频操作 |
| Jump to latest 悬浮按钮 | **实现遗漏（P1）** | 实现是强制贴底滚动，用户上翻历史时新消息会把视口拽走；设计的方案更对 |
| 底部 token 统计（↑1.2k ↓340） | **实现遗漏（P2）** | 协议 `TurnDone.usage` 已有数据，UI 没显示 |
| 发送按钮流式时变 Stop（■） | 实现为头部红色 Stop 文字按钮 | **偏差（P1 对齐设计）**：composer 内морph 更顺手，头部 ● 指示灯保留 |
| 头部 ⋯ 更多菜单 | **设计多余（删除）** | 设计稿里没有定义菜单内容，无对应功能 |

### 2.6 权限 / 执行模式（ep-*）

| 设计内容 | 判定 | 说明 |
|---|---|---|
| 「Request withdrawn（cancelled on <电脑名>）」状态 | **设计多余（P2）** | 协议无「请求撤回」帧；需要 daemon 侧支持后再做 |
| 超时后「Ask again」按钮 | **设计多余（保留实现的 Dismiss）** | daemon 已在 30s 自动拒绝并继续，phone 无法让 Claude 重新发起同一请求；「Ask again」承诺了做不到的事 |
| Bypass 确认框里显示路径 + 分支上下文 | **实现遗漏（P1，低成本）** | 实现的 BypassConfirm 没带 workdir 上下文，加一行即可 |
| 其余（4 阶梯、徽章、规则记忆与清除、倒计时环、危险工具按钮重排、AllowChip） | ✅ 一致 | 实现与 ep-* 逐项对得上 |
| 实现新增：StartSessionModeSheet（新建会话先选模式） | 设计稿无 | **实现超出设计** → 已补设计稿（SUPPLEMENT §5）。注意它与 ep-* 「新会话总是 default」表述需在设计稿里调和：默认仍是 default，但允许显式选择，bypass 仍需确认 |

### 2.7 语音输入（voice-*）—— ⚠ 需决策

设计明确强调「**voice never auto-sends**」：S3 转写完成后进入 **S4 Result**，文字回填输入框供编辑，用户手动点发送。
实现（`PocketRepository.deliverTranscript`）是**转写成功直接作为 prompt 发出**（注释明确写了「✓ = confirm AND send (user decision): no S4 review」）——这是实现期的有意决策，不是疏忽。

**建议：保留实现行为，修订设计稿。** 理由：移动场景里「✓」已经是一次显式确认，再加一步 Send 全程多一次点击；转写错误时错误链路（S5 重试）仍在。若你倾向设计的保守方案，改动也小（`deliverTranscript` 回填输入框即可）——**请拍板**。

其余 S1/S2/S3/S5/S6（含 iOS живой转写、波形、计时、错误 chip、mic 权限弹层）与设计一致；压缩 shimmer vs spinner 这类微偏差不动。

### 2.8 图片附件（ia-*）

| 设计内容 | 判定 | 说明 |
|---|---|---|
| 消息发送失败「Failed — tap to retry」 | **实现遗漏（P2）** | 当前依赖连接层自动重连，发送失败直接丢帧的窗口小；做 per-message 重试需要 outbox 改造 |
| 其余（4 张上限、压缩态、过大排除、计数行、1/2/4 宫格、全屏查看器手势/页码/圆点） | ✅ 一致 | — |

---

## 3. 实现有、设计稿没有 —— 已补充设计稿

以下 9 项实现存在但 handoff bundle 无对应设计，已在 **`V1-DESIGN-SUPPLEMENT.md`** 按 UI-DESIGN.md 的规格语言补齐（可直接粘回 Claude Design 生成对应画板）：

1. 重连横幅与断线重试（Connection lost — reconnecting…）
2. 观察模式条（👁 Observing · running in a terminal + Continue here）
3. 斜杠命令自动补全菜单（built-in / user / project / skill）
4. 已配对主屏 ConnectScreen（Paired · Connect / Unpair）
5. 新建会话模式选择弹层（StartSessionModeSheet）
6. 配对屏实现增项（粘贴 pair 链接、Advanced · direct LAN、状态行）
7. 目录页实现现状（Open Sessions 分区、history 徽章、筛选、下拉刷新、Exit）
8. 中英双语文案（资源化机制 + 设计稿出中文画板的指引）
9. 语音「确认即发送」流程注记（待 §2.7 拍板后定稿）

---

## 4. 发布资产核对

| 资产 | 状态 |
|---|---|
| iOS AppIcon / Splash | ✅ 已接入（`Assets.xcassets`） |
| **Android 启动图标** | ❌ **未接入（P0）**：manifest 无 `android:icon`，当前是系统默认图标。需从 icon-1024.png 生成 adaptive icon（前景/背景层 + 各密度 mipmap） |
| App Store 截图 ×6 | ✅ 设计已出（1290×2796，英文文案）；**中文区上架需中文文案版（P1）**，画板在设计项目里改文案重导出即可 |
| 应用名 | 待定稿（见上轮 slogan 讨论），确定后同步 App Store Connect / Play Console |

---

## 5. 行动清单（按优先级汇总）

> 2026-06-10 更新：设计稿修订已全部在 Claude Design 落位并重新导出；代码侧 P0/P1 已按下表完成。

**P0（发布阻塞）**
- [x] Android 启动图标接入（icon-1024.png → adaptive icon：`mipmap-*` + `mipmap-anydpi-v26` + inset 前景）

**需决策（阻塞设计稿定稿）**
- [x] 语音转写后「直接发送」定稿（设计稿 S4 已标注 removed in v1，实现不变）

**P1（v1 应做，按性价比排序）**
- [x] Sessions 卡片补 firstPrompt 预览 + 相对时间 + New session 副标题
- [x] Chat 头部改会话标题 + 连接条（脉冲点 + 路径；电脑名待协议补主机名字段，P2）
- [x] Thinking 独立渲染为可折叠行（「Thought for Ns」，repo 单独累积 + 计时）
- [x] 代码块语言标签 + 复制按钮（fence info string 解析 + 剪贴板，copied 反馈）
- [x] Jump to latest 悬浮按钮（钉底改为 pinned 状态机，上翻不再被拽走）
- [x] 发送按钮流式时 morph 成 Stop（发 `turn.cancel`；daemon 侧已接 stream-json interrupt——原 M0 空挡补上）
- [x] BypassConfirm 补路径上下文（分支待协议，P2）
- [x] 最小 Settings（About 三行 + Unpair），Sessions 头部齿轮入口
- [ ] App Store 截图中文版（设计侧任务，出中国区素材时做）
- [x] 目录行「history」徽章对齐设计 pill 样式；running 点改脉冲动画

**P2（v1 后，设计稿标注保留）**
- Computers 多电脑、Settings 完整版（全局默认模式、设备管理、浅色主题）、Directory 文件系统浏览、token 统计展示、权限 withdrawn 态、图片发送失败重试

**设计稿修订（多余/过时项）**
- Settings.html：删 auto / dontAsk 两档，模式区改为引用 ep-* 4 阶梯；全局默认模式、设备管理标 P2
- Permission.html：标注「研究稿，已被 Execution Permissions 取代」
- ep-*：timeout 态「Ask again」改为「Dismiss」；补 StartSessionModeSheet 画板
- Chat.html：删头部 ⋯ 菜单（或定义其内容）
- Voice Input.html：S4 按 §2.7 决策结果修订
