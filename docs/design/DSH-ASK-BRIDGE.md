# #291 dsh 提问与审批桥接设计（草案 v1）

> 状态：**待用户拍板**。全部结论以 2026-08-23 的实证为准（dsh 0.1.0-rc.6，`scripts/probe-dsh-api.py --probe-ask` 36/36 固化），不凭文档推断。

## 1. 实证要点（设计的地基）

- **通道形态**：提问/审批以 mux `server-request` 帧到达（`question/requested` / `approval/requested`，host 铸 rpcId）；答复**不是 RPC method**，是 `POST /api/respond` 回显同一 rpcId 的 `client-response`，应答是 `{accepted}` 回执。
- **答案词汇＝选项 label 字面量**（非下标、非 option id）；答案与问题按位对应且 id 必须相同；重复作答 `not-pending`（首个认领者赢）。
- **审批 outcome 只有 `allowed-once` / `rejected`**——dsh 没有「总是允许」语义。
- **永不超时**：不答即回合**永久挂死**（源码零 setTimeout，实测 120s 无解析）；唯一收敛是 `session.cancel`。README「fail closed」的实际含义是 fail-hang。
- **重连恢复基线**：重连 mux 会以**相同 rpcId** 重放未决提问——桥接可依赖它做幂等恢复。
- **回放形态**：提问只以 `tool/call`(name=`ask_user_question`)＋配对 `tool/result` 落盘（取消的没有 result）；审批是一等事件 `approval/asked`＋`approval/decided`。
- **现成接缝**（全仓只差三处接线）：`DshApiClient.respond(rpcId, value)` 已实现且形状正确、无人调用（`DshApiClient.kt:174`）；`DshBackend.translateMux` 的两个分支目前「只 log 不答」（`DshBackend.kt:262-272`）；envelope 的 rpcId 在 `DshBackend.kt:242` 被丢弃。

## 2. 关键裁决

### 2.1 M1（只读显示）与 M2（作答桥接）一次做完，不分两步

issue 原文「先只读显示，再评估作答」的前提被实证推翻了两次：其一，**不答即挂死**——只读显示一个永久挂死的提问，是看着它死，不是能力增量；其二，作答基建（respond）已经存在，M2 的边际成本从「评估后另做」降到「接线」。回放显示（历史会话）与实时桥接（显示＋作答）一并交付。

### 2.2 零 protocol 变更（预期）

`PermissionAsk.questions: List<AskQuestion>`（#110）＋ `PermissionVerdict.answers/response` ＋ `neverRemember` 现成字段完整覆盖 dsh 语义，App 端复用 OpenCode #210 铺好的问答/审批 UI，**预期手机端零代码改动**。不需要同版门槛。若实施中发现缺口再走 protocol 评审——缺口本身要先回到本文档修订。

### 2.3 超时策略：桥接把 fail-hang 修成真 fail-closed

dsh ask 进 `ApprovalCoordinator` 统一管理（对齐 ShellService/claude 的既有语义）：超时 → `respond(rejected)` ＋ AskWithdrawn 撤卡——挂死的回合被兜住；owner 的 `noAutoDeny` 偏好（#201 续租链）照常尊重——那本来就是「人不在就一直等」，与 dsh 永不超时天然兼容。

## 3. 映射规格

### 3.1 提问（question/requested → PermissionAsk）

| dsh | cc-pocket |
|---|---|
| envelope `rpcId` | askId＝`dsh-<rpcId>`（前缀对齐 `sh-` 惯例；pending 表按 rpcId 幂等——重连重放同 rpcId 不重复发卡） |
| `questions[].question / header` | `AskQuestion.question / header` |
| `questions[].options[].label` | `AskOption(label)`（dsh 无 description → null） |
| `questions[].id` | **不上 wire**——daemon pending 表私存 question-text ↔ id 映射 |
| （无 always-allow） | `neverRemember=true`，`grantOptions=["once"]` |
| multiSelect | 样本未见；实施时按 `questions.schema.js` 定，未确认前按 false |

Verdict 回程：`answers: Map<questionText, label>` → 查 pending 表 → `{answers:[{id, selected:[label]}]}`；自由文本 `response` → 每题 `{id, custom: response}`（实证：有 options 时 custom 合法，只要不与 selected 同发）。`respond` 返回 `bad-response` 时回一条错误 chip，不静默。

### 3.2 审批（approval/requested → PermissionAsk）

`tool=toolName`、`inputPreview=reason`（模型自撰的升级理由，正是人要看的）、`neverRemember=true`。ALLOW → `outcome:"allowed-once"`；DENY / 超时 → `"rejected"`。`remember` / `grantScope` 一律忽略（dsh 无此语义，UI 被 neverRemember 关掉）。

### 3.3 解析与撤卡

监听 `question/resolved` / `approval/resolved`：无论 outcome（answered / cancelled / 他端抢答）——撤卡＋清 pending 表。`session.cancel` 引发的 cancelled 同路径处理。

### 3.4 回放（DshTranscriptReplay 扩展）

- `tool/call`(name=`ask_user_question`) → `HistoryMessage(TOOL, tool="AskUserQuestion", text=问题文本)`，按 callId 配对 `tool/result` → 解析 `{answers:[{id,selected}]}` 填 `QuestionAnswer` 列表（对齐 claude 回放 `attachQuestionAnswers` 的形态）；无 result（被取消）→ 未回答形态，现有 UI 已处理。
- `approval/asked`＋`approval/decided` → `HistoryMessage(TOOL, tool=toolName, text=reason, ok=outcome=="allowed-once")`。

## 4. 顺手修正（随实施走）

- README 能力矩阵 dsh approval：△ Limited → 完整（发版时更新）。
- README:60 的 fail-closed 措辞修正为实况：不桥接的旧版行为是**回合挂死**（仅自带 timeoutMs 的工具能自行脱身），不是被拒绝。

## 5. 回归门禁

- `probe-dsh-api.py --probe-ask` 进「dsh 升级必跑」清单（已含默认 17/17 ＋ ask 层 36/36）。
- daemon 单测：pending 表幂等（同 rpcId 重放）、超时→rejected、resolved 撤卡、text↔id 翻译、bad-response 上报。
- 真机链路：手机答一次 dsh 提问＋一次审批（发版前验收项）。

## 6. 拆件与档位（实施期，worktree C，几乎无交集可独立合）

| 件 | 档位 | 执行者 |
|---|---|---|
| DshBackend 桥接（rpcId 管道＋translateMux 翻译＋pending 表＋coordinator 接线＋respond 回程） | 高（审批安全面＋竞态） | Fable |
| DshTranscriptReplay 回放扩展 | 中 | opus5 |
| README 矩阵＋措辞修正 | 低（并入上两件） | opus5 |
| 手机/桌面端 | 预期零改动（#110/#210 UI 复用），仅真机验收 | — |
