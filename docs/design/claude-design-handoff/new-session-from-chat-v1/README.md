# New Session From Chat v1 — 会话界面里的新建会话入口

状态：**已实现，分支待合入主分支**（2026-10-06，与设计稿的差异见文末「实现记录」）。原型由 Claude Design 交付，并经其自检修复两处原型缺陷；已评审（2026-10-05，用户委托 Fable 代为决定，见下）。

- 日期：2026-10-05（本机时区）。来源：维护者提出「在会话界面中增加新开会话的便捷方式」，强调会话界面是关键界面，要求先出设计。
- 设计项目：cc-pocket Design System 2.0。[在线设计板：New Session From Chat v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=New+Session+From+Chat+v1.dc.html)。
- 文件：`New Session From Chat v1.dc.html`（板子）、`NewSessionFromChatDevice.dc.html`（活组件；复用现有 `ChatComposerV2`，chrome 按 `ChatDeviceV2`，弹层按 `FastStartDevice`）。未改动任何已有文件。
- 生成：Claude Design，界面显示 Fable 5.1 Max，一次生成约 26 分钟（19:45–20:11），使用用户的 Claude Design 额度。投递原文见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。
- 原件（两份源码、板子正文、逐帧截图）在本机 `_local/design/new-session-from-chat-v1/`，不入库。

## 问题

手机上新开会话只能先退出聊天：返回到会话列表用底部停靠的「＋ New session」，或再返回到项目页用悬浮「+」打开 Fast Start 输入弹层。聊天内的会话切换器只在既有会话间切换，没有其他会话时整个消失；「⋯」快捷菜单里没有新建项。桌面端已有 ⌘N、侧栏「New session」行和每个项目行的「+」。

## 推荐方案

- **入口**：顶栏尾部、「⋯」左侧一个「+」。48pt 点击区（200% 字号 58pt），字形 15pt（19pt），两条 1.7pt 圆角线，颜色 ink3——与「⋯」同色同重；与「⋯」间距 6pt，「⋯」位置不变。观察终端会话时「⋯」隐藏，「+」顶到尾槽。无障碍名「New session / 新会话」（现有字符串）。
- **代价**：标题列每行少 54pt（402pt 机 279→225pt；360pt 机 237→183pt），仍最多三行后省略；E3 证明 360pt 机上三行长标题仍可读。
- **点按行为**：打开项目页同一个 Fast Start 弹层，项目与智能体 chip 预填**当前会话**的（而非最近项目＋默认智能体），仍可改；发送后落到新会话，首条提示流式中；下滑或点遮罩关闭保留草稿；打开失败按 Fast Start 现有失败行回弹、草稿仍在。
- **离开的会话原样不动**：运行中的继续跑，待批准、待回答保持。弹层顶部用 Fast Start 失败行的同一槽位放一行安静状态：「This chat keeps running. / 这个对话会继续运行。」，批准、提问各一句。新会话的返回键去该项目的会话列表（与切换器落地后一致）。
- **不在「⋯」里加第二条路**：该菜单每一行都作用于当前会话，新会话是唯一「离开它」的动作，而且会紧挨红色「Clear conversation」。「+」只要顶栏在就在，比菜单多一步也多不出入口。
- **离线**：「+」变 ink5 并禁用，不隐藏、顶栏不重排；点一下在输入区上方状态槽显示现有文案「The computer is offline; new sessions are unavailable」4 秒。
- **平板双栏**：聊天侧「+」保留，与列表停靠的「＋ New session」各做各的（停靠按默认立即开；「+」开弹层、可换项目和智能体）。
- **被否决的备选**：B3 在切换器里加「New session here」行——两步，而且单会话用户没有这个 chip，要让它在 0 时也常驻，改变了 chip 的含义；C5 一键直接开一个空会话——还没发送就先产生空会话、无处换项目、离开时没有任何提示。
- 文案、埋点名、尺寸与「UI 之外要改什么」在板子 G 区；新增字符串只有无障碍名「New session, unavailable / 新会话，不可用」与弹层的三句状态行，其余复用现有。

## 评审（先查层级与侵入，再看细节）

通过项：聊天主路径（读、回答、审批、发送）没有一处移动、缩小或改名；没有停靠条、横幅、每条消息入口或插进消息流的卡片；Send 仍是全屏唯一的强调填充控件；审批置顶块仍是最响的元素，「+」既不着色也不移动（D3、E4）；小屏与 200% 字号下点击区不缩（E3、E4）；中文帧文案与英文帧一一对应。

评审决定（2026-10-05，用户委托 Fable 代决，不再追加设计轮次）：

1. **接受裸「+」，不追加字形一轮。** 全 App 里「+」= 新会话是既有约定（项目页悬浮「+」、会话列表停靠「＋ New session」、切换器「New session here」都用 `Icons.Rounded.Add`），顶栏换成别的字形反而不一致；输入区左下的附件「+」位置固定在输入区且有各自的读屏名，可区分。实现时启用/禁用态分别给无障碍名「New session」「New session, unavailable」。
2. **平板双栏保留两个入口**，按板子的理由（停靠按默认立即开；「+」开弹层可换项目和智能体）。
3. **行为选弹层**，与 #260 一致。

原始待决点（保留备查）：

1. **同屏两个裸「+」。** 输入区左下的附件「+」与顶栏新会话「+」字形相同、含义不同，板子没有讨论这一点。可以接受（iOS 顶栏「+」=新建是通用约定，且有读屏名），也可以追加一轮小范围字形对比（例如「新建对话」compose 字形、带外框的「+」）。建议追加一轮再定。
2. **平板双栏同时可见两个新建入口。** 设计接受重复并给了理由；也可以选择双栏时让聊天侧「+」退场。
3. **行为选弹层而非直接开空会话。** 与 #260「发送前不产生会话」一致，同意。

## 与产品现状的差异（实现时以现状为准）

- 切换器里后台会话显示「Approval waiting」＋红点（C3、D8）今天不可达：daemon 把审批绑在打开它的那条连接上，`SessionWorkingSet.attention` 目前只表示「离开期间有未看的活动或已完成」。实现时该行显示 Running / Open，红点按现有规则。
- 离线呈现：板子假设上下文行出现「MacBook-Pro offline」字样，实际以现有的连接状态与会话降级呈现为准，只新增「+」变灰与点按后的那行文案。
- 演示模式：板子让「+」照常显示并把发送交给 Fast Start 的现有行为；实现前先确认 `startTaskWithPrompt` 在 demo 下可用，不可用就在 demo 里禁用并说明。
- 切换器弹层是按会话行语法重画的（项目里没有该组件），以现有 `SessionSwitcherSheet` 为准；弹层「对话名 New task / 新建任务」是板子的假设，现有 `NewTaskSheet` 没有标题，不必加。

## 实现落点（评审通过后参考）

- 入口：`ui/App.kt` 中 `ChatScreen` 的 `ChatHeader(trailing = …)` 目前只有「⋯」；加一个 48dp 的「+」图标按钮，`observing` 时仍显示，禁用态与会话列表停靠按钮用同一可达性信号。
- 弹层：复用 `ui/NewTaskSheet.kt`。需要一个「打开意图」把项目与智能体预填为 `repo.workdir` / `repo.sessionAgent`（`repo.newTaskDir` / `newTaskAgent` 是跨页面的粘性选择，预填时要注意不污染项目页的选择），加可选的状态行参数（quiet / failure）；`dirs` 需要在聊天页拿到项目列表。
- 发送：`repo.startTaskWithPrompt(wd, text, agent)` 已有——开新会话、发首条、三种失败回弹；离开前 `saveDraft`（同切换器）；落地后让返回栈指向新项目的会话列表（`sessionsDir`，参照 `switchToSession`）。
- 埋点：沿用 `TelEvent.FeatureUsed` 与 `TelKey.Target` 词表，加 `new_session_entry`（source=header）与 `new_session_result`。
- 测试：`desktopTest` 渲染 `ChatScreen` 断言「+」存在 / 禁用 / 观察模式仍在；`SessionWorkingSetTest` 不变。

## 实现记录（与设计稿的差异）

实现于 2026-10-06：顶栏按钮 `NewSessionHeaderButton`（`ui/chat/ChatChrome.kt`），接线在 `ChatScreen`（`ui/App.kt`），发送后的跟进在 `PocketRepository.followNewTaskFromChat`，测试 `desktopTest` 的 `NewSessionEntryTest`。以下逐条记录与设计稿或原实现说明不同、或设计稿没写到的处理。

- **可达性信号**：会话列表的「＋ New session」停靠按钮本身不接收可达性信号（调用方只传 `repo.opening`）。「+」改读同一页面连接徽标的来源 `repo.phase`，`ConnPhase.Ready` 时可用；打开会话路径判断「链路不通」（#340 的 LINK）用的也是它。因此重连中（Connecting / Reconnecting，宽限期过后）「+」同样变灰，点按的提示文案仍是「电脑离线」那句。
- **提示槽**：离线提示复用输入区上方的临时提示槽（`showNotice` → `voiceNotice`，原先只给语音提示用、固定 2.5 秒），加了时长参数，「+」的提示停 4 秒。这个槽只在普通输入区里显示：观察终端会话（含 Dot 只读观察条）或提问卡占用输入时，点灰色「+」看不到这行字，按钮的灰色和无障碍名照常。
- **暂存附件时不打开弹层**（设计稿未涉及）：输入区有暂存的图片或文件（`pendingImages` / `pendingFiles` 非空，含正在压缩、上传的）时，点「+」不打开弹层，在同一提示槽显示「Send or remove the attachments first / 请先发送或移除已添加的附件」4 秒。原因是新会话的首条经 `startTaskWithPrompt` 走同一份输入区状态发出，会把这些附件一起带进新会话；发送逻辑本身没改。按钮外观和无障碍名保持可用态，避免顶栏随附件闪动；这种点按不计 `new_session_entry`。
- **「会继续运行」只在属实时显示**：有待回答的提问 → 提问那句；有待批准 → 批准那句；正在流式输出 → 「This chat keeps running. / 这个对话会继续运行。」；会话空闲（没有在跑、也没有待处理）时不显示任何一行。离开的空闲会话按既有规则（与返回键、切换器相同）回收进程，之后可以再打开。
- **存稿写法**：离开前按切换器的写法 `saveDraft(draftKey, input)` 存（会话自己的键），时机是弹层发送被接受的那一刻（`NewTaskSheet` 新增的可选参数 `onStarted`）。返回键的写法是按项目目录存（`saveDraft(workdir, …)`），实测会让旧聊天没发出的文字出现在新会话的输入框里，所以不用。
- **打开期间保持聊天页**（设计稿未提）：借用切换器（#165）的 `switchingSession`，新会话打开期间聊天页留在屏幕上，不先闪到列表。打开成功、失败、返回或断开时都会释放。
- **返回栈**：新会话落地后（首条送达，或已打开但首条被拒）才让返回键指向新项目的会话列表，与 `switchToSession` 共用 `pointBackAtSessions`；打开失败时返回栈保持原样，不落到用户没去过的列表。
- **失败回弹**：首条被拒时会话已打开、聊天页还在，弹层就在聊天页重开，带着草稿和发送时的项目、智能体。打开失败（`OPEN_REFUSED` / `TIMEOUT`）时聊天页随之离开；草稿和这组选择留在仓库里，项目页（若在屏上）用它自己的弹层回弹。这条路径上项目页原来的粘性选择不再还原，选择跟着失败的草稿走。
- **「浏览其他文件夹…」**：项目页的文件夹浏览（`DirectoryPickerSheet` 及它转交的两个弹层）只在项目页内部接线，聊天页拿不到，这一行在聊天页只关闭弹层。
- **演示模式**：`startTaskWithPrompt` 在演示里可用。演示应答原先给每个新建会话同一个 convoId（`demo-convo-new`），在演示里「新会话中再点 +」会等满 30 秒超时、首条不发；已改为逐次编号（`demo-convo-new-N`）。
- **尺寸**：固定 48dp 点击区、16dp 字形（`Icons.Rounded.Add`），与「⋯」现状一致；没有实现设计稿 200% 字号时 58pt / 19pt 的放大。
- **埋点**：`feature_used` 增加 `new_session_entry`、`new_session_result`，参数 key 用 `TelKey.Target`（`target=header`；设计稿写的是 source），结果 `result=delivered / open_refused / timeout / send_refused`；登记在 `docs/observability/EVENT-CATALOG.md` 第 8 节。
- **文案**：新增字符串为 `new_session_unavailable`（「New session, unavailable / 新会话，不可用」）、三句状态行与 `new_session_attachments_staged`。现有启用态中文是「新建会话」，禁用态按评审写「新会话，不可用」，两者用词略有出入。

## 已知限制

- **平板双栏的双弹层**：左栏是项目页（没经会话列表直接进的聊天）时如果首条被拒，聊天页和项目页会各自重开一个弹层，每栏一个。本轮不修。
- **未验证**：没有做真机（iOS / Android）、读屏（VoiceOver / TalkBack，包括 TalkBack 双击禁用态按钮是否还会出提示）和 200% 字号的验证。已做的是 desktop JVM 测试与 iOS 模拟器目标编译。
