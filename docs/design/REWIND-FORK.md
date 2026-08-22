# #282 会话 rewind / fork 设计（草案 v2，实证已定稿）

> 状态：**待用户拍板**。v1 的全部〔待实证〕项已由 `scripts/probe-claude-wire.py` 新增的 `scenario_rewind()` 落定（claude 2.1.228，21 项断言全绿三连跑）；技术路线据实证从「手工截断复制」改为「CLI 原生截断 fork」，v1 的手工路线降级为已实证的应急保底。

## 1. 命题

agent 走偏几轮后，手机端只能中断整个会话重来。需要两种回溯能力：

- **Rewind**：回到历史某条用户消息之前，「换个说法重来」——该消息及其后的所有轮次不再作为上下文。
- **Fork**：从某个历史点分叉出一条新会话并行探索，原会话完整保留。

## 2. 哲学边界（先裁决再设计）

cc-pocket 的「原地接管不 fork」哲学，反对的是**隐式 fork**：take-over 时的保护性 fork 曾是冗余会话增殖的根因（记忆 `cc-pocket-redundant-sessions-fork`）。#282 引入的是**用户显式请求的分叉**，与哲学不冲突，但必须守住三条铁律：

1. **fork 永不隐式发生**。只有用户在 UI 上明确选择才产生新会话实体；任何自动路径（重连、模式切换、锁恢复）维持现状，不得借道新机制。
2. **每个新会话实体必须可辨识**。带 lineage 标注（分叉自哪个会话、哪个锚点），会话列表里不允许出现来历不明的条目。
3. **默认列表不增殖**。Rewind 产生的「被回溯替代」的原会话自动折叠收起（可找回），列表可见条目数不因 rewind 而增长。

## 3. 技术路线（实证定稿）

### 3.1 主路线：CLI 原生截断 fork

claude 2.1.228 存在三个 `--help` 里查不到（`.hideHelp()`）但功能完整的 flag，与 Agent SDK 的 `resumeSessionAt` 同源（SDK 选项就翻译成这些 flag）：

- `--resume-session-at <uuid>`：resume 时只保留到该链条目（含）为止——**行粒度**截断，锚点是任意链条目 uuid；
- `--resume-drops-turn <uuid>`：护栏——声明本次截断有意丢弃的那一轮的 prompt uuid，若丢弃范围含**不属于该轮**的内容（被吸收的排队消息、task notification、他轮内容）则拒绝启动；
- `--rewind-files <uuid>`：文件回滚，独立能力，依赖 checkpointing——**本设计不使用**（见 §4 警示文案）。

**rewind 与 fork 的底层完全同一**：`--resume <sid> --resume-session-at <anchor> --fork-session`——原文件逐字节不动（实证含 mtime）、铸新 session id、副本干净线性（CLI 在自己解析出的 message 列表上 slice，边车行 / summary / leafUuid 全由它自理）。两者的差异只剩原会话的 UI 处置：rewind 折叠原会话，fork 保留原会话可见。

**明确禁用「无 fork 的截断 resume」**：实证表明它沿用原 id 并把新分支**追加**进原 jsonl——transcript 变成分支树，线性回放会把新旧分支同时渲染（重影），且动了原文件。这条形态在 daemon 侧永不发出。

护栏使用规则：丢弃范围恰好一轮时带 `--resume-drops-turn`（CLI 拒绝连坐丢弃排队消息——正是 #122 排队注入的高危形态）；丢弃多轮时该 flag 语义不适用（声明单轮必被拒），不带——此时风险已由 dry-run 预览＋用户确认覆盖。

### 3.2 保底路线（已实证，不默认启用）

手工截断复制同样被实证接受：复制 jsonl 截断到锚点、以新 UUID 为**文件名**存同一 project 目录即可 resume（CLI 按文件名认会话，行内 `sessionId` 字段改不改都行）。若未来 CLI 升级移除隐藏 flag（`scenario_rewind` 会立刻报警），此路线是现成退路。第一版**不实现**该路线的代码，只保留 probe 断言。

### 3.3 锚点语义与换算

- App 端用户选中的是**用户消息行**（uuid 随 `HistoryMessage` 下发，见 §5）；语义＝「这条消息及其后全部丢弃」。
- daemon 换算：`anchor = 被选中用户消息行的 parentUuid`（保留到它的父链条目为止）。选中首条消息时 parentUuid 为 null → 等价于全新会话，UI 直接禁掉该行入口（用「新建会话」就好）。
- daemon 预检 anchorUuid 存在性＋anchorSeq 一致性（双锚校验，防 stale）；CLI 的 exit 1（无 init 帧＋stdout 结构化 `error_during_execution` result——实证的拒绝形态）是兜底，daemon 按此判据识别失败并原样上报。

### 3.4 前置安全条件（rewind/fork 的准入门）

- 会话必须 **idle**：无 in-flight turn，`promptLedger` 为空。非 idle 时 UI 置灰入口（「先停止当前轮」）。这使 rewind 与 #285 归属门（`Conversation.promptFate`）正交。
- 无外部 writer：`SessionRegistry.externallyActive` 为 false。锁语义已实证：`-p` stream-json 活进程**不**构成 CLI 锁（锁特指 `--bg` background agent，`--fork-session` 是官方逃生口）——但 cc-pocket 自己的「无双写」纪律不变，daemon 自己持有的活进程先正常终止再操作。
- dry-run 数据 daemon 侧自算（解析 jsonl 数轮次/工具调用，`TranscriptReplay` 基建现成）——CLI 无对话级 dry-run flag。

## 4. 交互设计

- **入口**：聊天历史中长按**用户消息气泡** → 菜单新增「从这里重来」「从这里分叉」。rewind 后 composer 预填该消息原文（可编辑重发）——「换个说法」的自然形态。
- **dry-run 确认页**（必经，不可跳过）：显示「将丢弃 N 轮对话（含 M 次工具调用）」＋固定警示「文件改动不会回滚——回溯的只是对话，不是工作区」（`--rewind-files` 能力存在但依赖 checkpointing，第一版不做，警示保持；与 #280 落地后可加「查看 git 状态」联动）＋两种模式差异说明（rewind：原会话收起可找回；fork：原会话保留）。
- **执行后**：App 自动切换到新会话（新 convoId），回放截断后的历史；rewind 模式原会话折叠（「已回溯」过滤器后可找回），fork 模式原会话原位保留；新会话带「⑂ 自 <原会话> 第 N 轮」标注进现有分组视图。
- 仅 Claude 后端显示入口（多后端矩阵见 §7）。UI 形态细节按稿实施，真岔路走 /design。

## 5. 数据模型与 wire 变更（走 protocol-wire-compat-reviewer）

新增帧：

| 帧 | 方向 | 字段 | 语义 |
|---|---|---|---|
| `pocket/session.rewind`（RewindSession） | →daemon | convoId, anchorSeq: Long, anchorUuid: String, mode: REWIND\|FORK, dryRun: Boolean | anchorUuid＝用户选中的**用户消息行** uuid（daemon 内部换算 parentUuid，见 §3.3）；只对当前打开的会话操作 |
| `pocket/session.rewindPreview`（RewindPreview） | →phone | convoId, dropTurns, dropToolCalls, ok, reason? | dry-run 应答；准入门不满足时 ok=false＋原因 |
| `pocket/session.rewindDone`（RewindDone） | →phone | convoId, ok, newConvoId?, newSessionId?, reason? | 执行结果；App 据此跳转 |

既有类型扩展（trailing optional，双向兼容）：

- `HistoryMessage` 增 `seq: Long? = null`（transcript 行号）与 `uuid: String? = null`（仅 USER 行填）。无 seq/uuid 的行（旧 daemon）不显示入口——天然能力探测。
- `SessionSummary` 增 lineage 字段（forkedFrom?, rewindOf?）。**实证确认 CLI 不写任何血缘字段**（fork 副本里原 sid 零出现，仅链条目 uuid 相同可作相关性线索）——lineage 只能 daemon 自己记：新增小账本 `rewind-lineage.tsv`（parent, child, anchorSeq, mode，仿 `spawned-sessions.tsv` 手法），扫描时贴到 SessionSummary。旧 App 忽略（不折叠、无标注，无害降级）。

兼容矩阵：新 App＋旧 daemon → 无 seq/uuid，入口不显示；旧 App＋新 daemon → 忽略新字段照旧。

## 6. daemon 实现要点

- `AgentSpec` 增 `resumeSessionAt: String?` / `resumeDropsTurn: String?`；`ClaudeLauncher.buildArgs` 在 `spec.resumeId` 块内追加两 flag（机械改动，guarded 同 forkSession）。
- `SessionRegistry` 新增 rewind 编排（高危件）：准入检查 → dry-run 应答或执行 → 停旧进程 → 以截断参数 open 新 Conversation → 记 lineage 账本 → RewindDone。旧 convo 关闭走既有 close 路径。
- 回放侧：`TranscriptReplay.parse` 已持有行号，seq/uuid 透传是机械改动。
- **回放分支树韧性**（实证的衍生发现，可拆件）：若用户在终端/SDK 侧对同一会话做过「无 fork 截断」，盘上 transcript 已是分支树，现行线性回放会重影。防御＝回放按 `parentUuid` 链从活跃叶（`last-prompt.leafUuid` 或最后链条目）回溯取单链。此件独立于主流程，验收紧张时拆成独立 issue 不挡 #282。
- **不碰**：promptLedger 语义、#285 归属门、take-over fork 决策路径（`SessionRegistry.kt:470` 原样）。

## 7. 多后端矩阵

Claude 先行。Codex / OpenCode / kimi / dsh：README 能力矩阵标「不支持」，App 按 agent kind 隐藏入口，daemon 对非 Claude 会话的 RewindSession 回 `ok=false, reason=unsupported`（出口守卫）。

## 8. 回归门禁与风险

- `scenario_rewind()`（已交付，21 断言：flag 在场、逐字节不动原文件、截断正确性、drops-turn 双向、错误形态、分支树行为）进「升级 claude CLI 必跑」清单——**隐藏 flag 的契约风险由它托底**，断言全部结构性（不赌模型语义，规避 auto-memory 旁路假信号——实证踩坑记录）。
- 主风险与对冲：`.hideHelp()` flag 稳定性弱于文档化 flag，但与 SDK 公开能力同源（漂移＝SDK breaking change 级别）；probe 预警＋§3.2 保底路线双保险。
- `BridgePromptFateTest` 保绿（idle 前置正交）；protocol 过 wire 评审；registry 编排测试逐条覆盖准入拒绝路径；折叠语义测试锚（rewind 后默认列表条目数不变）。

## 9. 拆件与档位（实施期，进 worktree A）

| 件 | 档位 | 执行者 |
|---|---|---|
| protocol 帧＋HistoryMessage/SessionSummary 扩展 | 高（wire 兼容） | Fable |
| Registry rewind 编排（准入/停进程/身份切换/lineage） | 高 | Fable |
| AgentSpec＋ClaudeLauncher 两 flag＋dry-run 计算 | 中 | opus5 |
| 回放 seq/uuid 透传＋lineage 账本读写 | 中 | opus5 |
| 手机端：长按菜单＋dry-run sheet＋跳转＋折叠/标注 | 中（按稿） | opus5 |
| 桌面端同款交互 | 中（按稿） | opus5 |
| 回放分支树韧性（可拆独立 issue） | 中 | opus5 |

对比 v1 的变化：`TranscriptRewinder.kt`（截断/特殊行/parentUuid 重链整块）从拆件表**删除**——CLI 原生完成；新增两 flag 透传与分支树韧性两件。
