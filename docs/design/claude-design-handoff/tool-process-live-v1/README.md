# Tool Process Live v1 — 执行过程折叠的实时行

日期：2026-09-26 至 2026-09-27（本机时区）。来源：用户在手机上的反馈（语音），用户要求实现前先用 Claude Design 出稿，因为这是频率最高的交互之一。

- 设计项目：cc-pocket Design System 2.0。
- [在线设计板：Tool Process Live v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Tool+Process+Live+v1.dc.html)，组件文件 `ToolProcessLiveV1Phone`、`ToolProcessLiveV1Desk`，逻辑在 `ToolProcessLiveV1Model.js`、`ToolProcessLiveV1Scenes.js`。
- 设计生成：Claude Design，界面显示 Opus 5.5 High；生成使用用户的 Claude Design 额度。投递内容见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。
- 生成过程中浏览器扩展断线，设计板文件已全部写完，但 Claude Design 的自查与总结阶段被中断，设计板上没有单独的“紧凑规格表”一节；下文规格取自组件源码（尺寸、颜色、状态规则都在源码里）。
- 范围：聊天流里 #380 执行过程折叠的形态与运行态。正文、用户消息、子 Agent / Workflow / 计划 / 提问卡、顶部状态行和 composer 不变。

## 问题

回合运行时列表贴底跟随。每个步骤先以完整工具带（约 80–100dp）出现，结果回来后并入上方折叠行，工具带消失、内容变短，上方内容整体下移；下一个步骤出现又整体上移。一个回合几十个步骤，整屏持续上下弹。run 的第一个工具还会先以完整工具带停留，等第二个结束两条一起收起，跳得更大；思考块、失败工具切断 run 后另起折叠行也都会跳。

回归测试 `ToolProcessLiveUiTest` 在旧实现上复现：Read 完成后 Grep 开始，用户提问上移 80px。设计板在 DOM 里实测同一段 22 个事件的回合：旧行为累计位移 1168dp，D1 只剩折叠块诞生和回合结束两次。

## 采用方案：D1 单一容器

设计板把现状和三个方向放在同一段对话里比较，推荐 D1：

- **D1（采用）**：折叠卡片随第一个步骤诞生，卡片内 hairline 下方是一条固定高度的实时行（⌁、工具 chip、一行目标、脉冲与计时）。正在做什么在实时行，做了多少在标题，失败和等批准都在同一个卡片里。
- D2：#380 折叠行不变，下方贴一条无边框实时行。新增 chrome 最少，但这条线浮在卡片之间，读起来像正文或流尾。
- D3：标题只计数，流尾指示器变成实时行。回合结束不用收，但“做什么”和“做了多少”分在两个对象里，展开后会分开。

设计决定：

- 标题在运行中与结束后文字不变，只计已结束的步骤；第一个步骤结束前只写“执行过程”。
- 失败不再切断块：标题右侧常驻红色“■ N 个失败”，不截断（标签先省略），点开即可看到失败成员。结果没回来的步骤标为“○ N 个无结果”。
- 结束后只有一个步骤的块是一行 44dp，写明工具和目标，操作词为“展开 / 收起”。
- 运行中，块自带 live 信号时隐藏流尾指示器（手机的“思考中…”行 / 脉冲点、桌面的闪烁光标）。
- 展开时成员沿用现有工具带，缩进到标题文字处，不再显示“工具”来源标签；实时行留在块底部，已完成步骤在它上方逐条增加。

## 规格（手机）

| 部分 | 规格 |
|---|---|
| 卡片 | surface 底、1dp hair 边、8dp 圆角；标题、成员、实时行是同一卡片的分段，分段之间 1dp hair |
| 标题 | 最小高度 44dp，左右 12dp，间距 8dp；12dp 宽的 ▸（10sp muted，展开时 150ms 转 90°）、标签 12.5sp Medium tx2 等宽数字、失败 / 无结果标记（12sp Medium，7dp 方块或 1.5dp 环）、操作词 11sp muted |
| 单步块 | 工具 chip（最多 52% 宽）＋ mono 12sp tx2 目标，操作词“展开 / 收起” |
| 实时行 | 固定高度 `max(31dp, 20sp + 11dp)`，左右 12dp，间距 8dp：12dp 宽的 ECG 符号（与子 Agent 卡同一个）、chip（mono 11sp Medium、raised 底、5dp 圆角、最多 48% 宽；超过 26 字符的工具名中间省略为前 9 + … + 后 16）、并行 ×N / +N（mono 11sp tx2）、目标（mono 12sp tx2，单行省略）、右侧 7dp accent 脉冲 + 计时（mono 10.5sp muted） |
| 成员 | 起点缩进 32dp、右 12dp、上 9dp、下 10dp；工具带去掉自身 hairline 与纵向留白 |
| 颜色 | 深色 surface #16181B、hair #2A2E33、raised #1E2125、tx2 #9BA1A6、muted #6B7177、danger #E5604D、warn #E0A93B、accent #D97757 / 青绿 #3FB5AC；浅色 surface #FFFFFF、hair #E4E1DB、raised #F1EFEB、tx2 #5B6066、muted #878C92、danger #C53D2B、warn #B07D1C、accent #C15F3C / 青绿 #1C8B82 |

实时行内容的优先级（在设计板 `liveContent` 的基础上按实测修订，见差异第 10 条）：

1. **等你批准**：运行中的步骤有待批准的请求时，warn 方块 + “等你批准”，脉冲停止；决定仍由顶部状态行和批准面板承担。
2. **运行中**：同一工具并行显示 `×N` 和各自文件名（用 “ · ” 连接）；不同工具显示第一个工具 + `+N`。计时取最早开始的一个。
3. **最近一步**：没有步骤在跑时，显示最近结束的那一步和它的结果——`● 完成`（ok 色）、`■ 失败`（danger）、`○ 无结果`——保持到下一步开始。
4. **思考中**：只在这个块里还没有任何工具、而思考块正在流式输出时显示：斜体 muted “思考中…” + 脉冲 + 计时。
5. 其余（块里只有思考且已结束）：“思考中…” + 脉冲，不计时。

参数（目标）一律经 `data/ToolTarget.kt` 取出：daemon 对普通工具发的是原始输入 JSON（`{"command":"…"}`，280 字符截断），实时行和单步标题从中取 `command` / `file_path` / `pattern` / `path` / `url` 等首个字段（截断的 JSON 也能取到值的开头），路径在会话工作目录下显示相对路径、在家目录下显示 `~/`。路径类目标中间省略（保留文件名），命令结尾省略。展开后的成员行仍显示 daemon 发来的原文。

读屏：标题朗读一个摘要（运行中附带“正在运行”）；实时行只在“等你批准”和“失败”时礼貌播报一次，其余状态对读屏隐藏，不逐步播报。

## 规格（桌面）

头部 40dp（左右 14dp、间距 10dp、标签 13sp）、成员 38dp（左 33dp：状态点 + 粗体工具名 + mono 12.5sp 参数 + 右侧“完成 / 失败 / 无结果”）、思考成员 34dp（左 50dp，斜体 13sp muted）、实时行 36dp。实时行的脉冲放在成员状态点那一列（距左 33dp），步骤结束时由脉冲变为 ●，位置不动。头部和成员行悬停时 120ms 填 raised，操作词由 muted 变 tx2；实时行不可点击。流内行距 18dp。

## 实现映射

- `data/ChatPresentation.kt`：投影规则改为“连续的步骤（普通工具任意结果 + 思考）都成块，单步也成块”，失败与无结果计入 `ProcessSummary.failed / unknown`。`live = true` 时，以步骤结尾的尾部块是 live 块（`ChatRow.ProcessGroup.live`），其运行中的成员不单独成行；展开时实时行挂在最后一个已完成成员下（`ChatRow.Original.liveTail`）。接缝不会落进卡片内部。
- `ui/chat/ToolProcessBlock.kt`：卡片分段绘制 `processSegment`、贴合上一分段的 `joinPreviousSegment`、实时行内容 `liveLineState`、步骤计时 `ProcessStepClock` / `rememberStepElapsed`，以及手机端 `ProcessBlockHeader`、`ProcessMemberSegment`、`ProcessLiveLine`。
- `desktop/DesktopProcessBlock.kt`：桌面端头部、成员、实时行；`ToolRow(inBlock = true)` 为卡内成员行。
- `ui/App.kt`（手机 `ChatScreen`）与 `desktop/ChatPane.kt`：`rememberChatPresentationState(live = …streaming)`，按行类型渲染，live 块在流尾时隐藏尾部指示器。
- `ui/chat/ChatChrome.kt`：`ToolTurnBand(framed = false)`。
- `data/ChatTranscript.kt`：顶层工具的 START 与文字 chunk 一样视为“回合进行中”（`streaming = true`），电脑端发起、以工具开头的回合（Codex 常见）因此不会被误标为“无结果”；子 Agent 的内部调用和迟到的 RESULT 都不会让已结束的回合重新显示为运行中。START 到达时登记该调用的计时起点（`data/ProcessStepClock.kt`），收到结果帧后记下“这个 daemon 会报告结果”（`toolOutcomesLive`）。
- 字符串：`tool_process_failed` / `tool_process_unknown` / `tool_process_autoruns`（复数）、`tool_process_unknown_one`、`tool_process_expand_one` / `tool_process_collapse_one`、`tool_process_running`、`tool_process_waiting`、`tool_process_a11y_waiting` / `tool_process_a11y_failed`。

## 与设计稿的差异

1. **中文计数用词**：沿用线上已有的“项工具 / 段思考”，设计稿写的是“个工具 / 次思考”。
2. **“未返回”过渡态未做**：设计稿 b9 在回合结束时先显示 150ms 的“空心环 + 未返回 · 0:37”，再收成标题上的“○ N 个无结果”。实现直接收成标题标记。
3. **回合结束的一次位移**：设计稿中实时行的 32dp 正好让给正文第一行，因此不移动。App 里 Agent 回复带来源标签（“CLAUDE”）和第一行，高于 32dp，所以回合结束时内容会上移一次。只增不减，不会来回跳。
4. **展开成员仍是懒加载的独立行**：为了让很长的展开块保持懒加载，成员是列表里的独立行，用 `joinPreviousSegment` 吃掉列表行距，贴在上一分段下面拼成一张卡片。
5. **等批准的匹配**：按工具名把待批准请求对应到运行中的步骤；对不上时（例如命名不同的后端），实时行显示请求自身的工具和参数。
6. **#380 规则的变化**：失败工具和结果未知（`ok == null`）的工具不再单独成行，改为留在块内并由标题标记；单个已结束的工具也折叠为一行。折叠块标题由约 36dp 变为 44dp。
7. **授权自动执行的审计 chip 并入块**（设计板未覆盖）：守护进程在工具开始之后才记录授权命中，所以 chip 会落在两个步骤之间。若它仍切断块，授权覆盖的会话会每一步重新拆块、继续跳动。审批设计 §9.6 允许把连续的自动决定折叠成一组，但要求在会话流里可见。因此 chip 并入所在的块，标题显示“⚡ N 次自动执行”，展开后逐条可见并保留“收紧”。chip 不会单独开启一个块；记住规则的 chip、提问卡、报错等仍会切断块。
8. **回合中追加的消息**（设计板未覆盖）：回合进行中用户追加消息后，块不再是流尾，随之收束。离流尾最近那个块里已开始、尚无结果的工具仍在运行，既不计数，也不标为“无结果”，结果回来后再计入；更早回合的块不受影响。
9. **旧版 daemon 与没有调用 id 的行**（设计板未覆盖）：普通工具的结果帧从 daemon v2.1.1 开始才有。只有在这个会话里实际收到过结果帧之后，才把多个无结果的调用都当作并行运行；在此之前（包括旧版 daemon）只有最新开始的那个算运行中，其余计入计数但不标“无结果”。回放行和中途重开时孤立的子 Agent 内部调用没有本机的调用 id，永远不会收到结果，因此不算运行中，按“无结果”计。每个成员的判定（完成 / 失败 / 无结果 / 不作声明 / 运行中）由投影统一给出（`StepState`），标题计数和展开后的成员状态词因此不会互相矛盾。
10. **步骤之间显示最近一步，而不是“思考中…”**（按实测修订设计板 b6）：用户 2026-09-27 的录屏（68 秒、9 个调用）显示，工具几乎都在几十毫秒内结束，模型在两步之间要花数秒决定下一步，实时行因此绝大部分时间显示“思考中…”，只有 6 秒的命令和失败的那步露过面——“当前正在执行”等于没显示。改为运行中显示工具 + 计时，结束后保留该步和结果（完成 / 失败 / 无结果）直到下一步开始；“思考中…”只在块里还没有任何工具时显示。回合仍在进行由 composer 的“正在运行”和停止按钮表达。
11. **参数解码**（设计板假设 daemon 发干净参数）：见上面的“参数（目标）”。daemon 侧统一用 `ToolMetadata.of(...).preview` 是更彻底的修法（成员行和回放也会变干净、回放去重仍一致），但要重装本机 daemon，作为后续单独处理。

## 验证

- `ChatPresentationTest`（34 项）覆盖：D1 成块规则、失败与无结果计数、审计 chip 并入与计数、回合中追加消息（只影响离流尾最近的块）、旧版 daemon 下只有最新调用算运行中、无调用 id 的行不算运行中、并行调用共用最早的计时、单步块、live 块从第一步诞生、步骤开始 / 结束时行数和 key 不变、展开时实时行位置、接缝、行映射往返。`ChatTranscriptTest` 覆盖只有顶层 START 点亮运行态、结果帧记忆与 START 即登记计时。`ChatPresentationStateTest`（3 项）覆盖展开状态在块头变化与乱序完成时保持。
- `ToolTargetTest`（7 项）覆盖原始 JSON 取参、截断 JSON、转义、多行取首行、工作目录 / 家目录缩写、非 JSON 直通、并行短名。
- `ToolProcessLiveUiTest`（6 项，真实手机 `ChatScreen`）：
  - 一整段回合（Read → Grep → 思考 → 三个并行 Read → Edit 及其授权审计 chip → 测试失败 → 修复）中，用户提问位移始终为 0；
  - 实时行依次显示运行中、结束后保留该步与“完成”、并行 ×3、等你批准、失败保留，回复开始后收束并保留计数和失败标记；
  - 原始 JSON 参数显示为相对工作目录的路径 / 命令本身；
  - live 块在流尾时只有一个“思考中…”；
  - 运行中展开，实时行在成员下方且卡内不显示“工具”标签；
  - 单步块写明工具和目标。
- `ToolProcessLivePaneUiTest`（桌面 `DesktopApp`，2 项）：同样的步骤序列中位移为 0，回合结束后实时行消失；被回合中追加的消息推离流尾、仍在运行的调用展开后不显示“无结果”。
- `ToolProcessLiveDesignTest`（8 项）：实时行在运行、并行、思考、等批准、失败、超长参数、超长 MCP 工具名、空闲各状态下高度一致，头部 ≥44dp；桌面实时行 36dp；并渲染设计板对应场景（深浅色、Codex 青绿、320pt × 1.6 倍字号、运行中展开、已结束、桌面）。
- 原 #380 的 `ToolProcessCollapseUiTest`（13 项，新增旧版 daemon 场景）与 `ToolProcessPaneUiTest`（7 项）按新规则更新了失败 / 无结果 / 工具 START 相关的断言。
- 独立审查（只读子 agent）报告的 5 个问题均已修复并补测试：早先回合的块在新回合运行时计数变化、旧版 daemon 与孤儿行被当成一直运行、桌面展开成员把运行中的调用写成“无结果”、并行计时在第一个调用结束后归零、子 Agent 内部调用会让已结束的回合重新显示为运行中。
- 保存截图：

```bash
TOOL_PROCESS_LIVE_DESIGN_OUT=/tmp/pairlet-tool-process-live JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :mobile:composeApp:desktopTest --tests '*ToolProcessLiveDesignTest'
```

## 原型归档

以下资料保存在本地忽略目录 `_local/task-handoffs/2026-09-27/tool-process-live-v1/`：

- 设计源文件 `export/`：`Tool Process Live v1.dc.html`、`ToolProcessLiveV1Phone.dc.html`、`ToolProcessLiveV1Desk.dc.html`、`ToolProcessLiveV1Model.js`、`ToolProcessLiveV1Scenes.js`、`support.js`；
- 设计板各区块截图 `board-*.png`；
- 实现截图 `impl-zh/`、`impl-en/`（`ToolProcessLiveDesignTest` 在中文与英文区域设置下的输出）。

源文件是从设计板页面直接读取的（页面 Export 没有使用）。仓库只维护本说明与在线设计板链接，不提交原型文件。
