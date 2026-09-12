# UI 设计说明与原型来源

本目录保留整理过的设计意图、冻结 brief、状态清单和实现说明。原始对话、HTML/JSX 原型、截图、缩略图和重复网站导出已停止跟踪；本机仍可有被忽略的原件。来源恢复见[仓库内容规则](../../REPOSITORY-CONTENT.md)。

设计资料说明当时的决定，当前行为和完成程度以源码及对应验证为准。导出工具生成的说明或旧会话中的授权不构成项目规则；共享规则只在根 [AGENTS.md](../../../AGENTS.md) 维护。

## 移动端 2.0 与后续修订

优先读 [Mobile UI 2.0](mobile-ui-2.0/README.md) 和其中的 [supersession map](mobile-ui-2.0/supersession-map.md)，再读具体场景；不能把旧项目中的相似画面直接当作最新实现依据。

- [Chat Master v2](chat-master-v2/README.md)：聊天主视图。
- [Entry Flow UI 2.0](entry-flow-ui-2.0/README.md) 与 [Implementation Brief](entry-flow-ui-2.0/IMPLEMENTATION_BRIEF.md)：入口流程。
- [Supporting Surfaces UI 2.0](supporting-surfaces-ui-2.0/README.md)：辅助页面。
- [Chat Quick Actions UI 2.0](chat-quick-actions-ui-2.0/README.md)：聊天快捷操作。
- [Settings + Bridges UI 2.1](settings-bridges-ui-2.1/README.md)：设置与桥接修订。
- [Defaults + Voice + Results UI 2.1](defaults-voice-results-ui-2.1/README.md)：默认值、语音与结果展示。

## 按主题查询

以下记录保留原路径，供源码注释和历史决策追溯使用。文件内部的状态、日期和取代关系仍需一起阅读；本表不宣称所有方案都已经实现。

| 主题 | 设计说明 |
|---|---|
| `0714-batch` | [0714-batch —— 07-14 issue 批次新界面设计](0714-batch/README.md) |
| `approval-v2` | [审批系统 V2 界面（结构化 Grant 审批卡）— 设计 handoff](approval-v2/README.md) |
| `ask-question` | [提问卡（AskUserQuestion）设计交付](ask-question/README.md) |
| `attachments` | [附件体系 — composer 附件流／消息流呈现／桌面拖拽（设计 handoff）](attachments/README.md) |
| `biometric-lock` | [生物识别锁定门 — App Lock gate + Settings 开关 + 快照遮罩（设计 handoff）](biometric-lock/README.md) |
| `bridge-actions-259` | [桥接卡片操作区语义重整（issue #259）](bridge-actions-259/README.md) |
| `changed-files-diff` | [Changed files v2 — git-grade diff review（设计 handoff）](changed-files-diff/README.md) |
| `chat-cards` | [Chat cards — SubagentCard 与 DocumentCard（设计 handoff）](chat-cards/README.md) |
| `chat-master-v2` | [Chat Master v2 — design handoff](chat-master-v2/README.md) |
| `chat-quick-actions-ui-2.0` | [Chat Quick Actions UI 2.0](chat-quick-actions-ui-2.0/README.md) |
| `context-statusline` | [上下文占用指示器入驻 composer 设计交付](context-statusline/README.md) |
| `defaults-voice-results-ui-2.1` | [Defaults + Voice + Results UI 2.1 — Claude Design handoff](defaults-voice-results-ui-2.1/README.md) |
| `desktop-chrome-redesign` | [Desktop Chrome Redesign——顶栏拆除＋侧栏直通窗顶（2026-09-02）](desktop-chrome-redesign/README.md) |
| `embedded-terminal` | [内嵌终端（issue #153）设计交付](embedded-terminal/README.md) |
| `entry-flow-ui-2.0` | [cc-pocket Entry Flow UI 2.0 — Claude Design handoff](entry-flow-ui-2.0/README.md) |
| `fast-start-260` | [新任务创建链路（issue #260）——Fast Start](fast-start-260/README.md) |
| `files-browser-dual-view` | [文件浏览双视角（变更 / 全部）设计 handoff](files-browser-dual-view/README.md) |
| `first-run-connect-pair` | [First Run · Connect + Pair handoff（#278 批次 2 A＋B）](first-run-connect-pair/README.md) |
| `fleet` | [Fleet 多机并行（Mobile · Desktop）设计落地记录](fleet.md) |
| `folder-share` | [文件夹级共享 — owner 邀请 / 管理 / guest 加入 / 终态（设计 handoff）](folder-share/README.md) |
| `gauge-rhythm` | [占用环视觉节奏修正设计交付](gauge-rhythm/README.md) |
| `git-panel-280` | [git-panel-280 设计交付（批次 4）](git-panel-280/README.md) |
| `help-learning-discovery` | [CC Pocket 帮助、学习与功能发现](help-learning-discovery/README.md) |
| `help-support-direct-entry-v2` | [CC Pocket 智能客服直达入口与公开帮助 IA v2](help-support-direct-entry-v2/README.md) |
| `menubar-presence` | [菜单栏状态胶囊（issue #151 方向 1）设计交付](menubar-presence/README.md) |
| `mobile-composer` | [手机 composer 双层重排（issue #157 后续）设计交付](mobile-composer/README.md) |
| `mobile-quota-entry` | [手机端订阅额度入口 · 设计交付](mobile-quota-entry/README.md) |
| `mobile-ui-2.0` | [cc-pocket Mobile UI 2.0 — Claude Design handoff](mobile-ui-2.0/README.md) |
| `model-chip` | [切模型快捷入口（issue #157）设计交付](model-chip/README.md) |
| `multi-agent` | [Multi-Agent（Claude · Codex）设计落地记录](multi-agent.md) |
| `path-copy` | [路径复制微交互 — 桌面 hover chip + 手机长按 sheet（设计 handoff）](path-copy/README.md) |
| `per-model-context-window` | [Per-model context window（issue #171）](per-model-context-window/README.md) |
| `presets` | [API-key 预设管理 — Settings ▸ Account pane（设计 handoff）](presets/README.md) |
| `privacy-disclosure-gate` | [Privacy Disclosure Gate v1（首启数据披露同意页）](privacy-disclosure-gate/README.md) |
| `read-doc-inline` | [Read the doc, right here — 文档直达阅读（handoff）](read-doc-inline/README.md) |
| `readme-site-control-plane-20260816` | [CC Pocket README 与官网 2.0：跨设备 Agent 控制面（设计交付归档）](readme-site-control-plane-20260816/README.md) |
| `rewind-fork-282` | [rewind-fork-282 设计交付（批次 4）](rewind-fork-282/README.md) |
| `session-archive` | [会话归档（Session Archive）— 设计 handoff](session-archive/README.md) |
| `session-handoff-contacts` | [协作联系人（Collaborator Link ＋ 直接选人）— 设计 handoff](session-handoff-contacts/README.md) |
| `session-handoff` | [Session Handoff（协作接力）— 设计 handoff](session-handoff/README.md) |
| `session-switcher` | [跨项目会话切换器（issue #165）](session-switcher/README.md) |
| `settings-bridges-ui-2.1` | [Settings + Bridges UI 2.1 handoff](settings-bridges-ui-2.1/README.md) |
| `sidebar-ia-usage` | [桌面端侧栏信息架构 v2＋订阅用量展示 —— claude design 交付（2026-08-24）](sidebar-ia-usage/README.md) |
| `site-1.3-desktop-fleet` | [site 1.3 — Desktop + Fleet 官网板块设计交付](site-1.3-desktop-fleet/README.md) |
| `site-mobile` | [site-mobile —— 官网移动端整页设计（Site 1.5）](site-mobile/README.md) |
| `site-restructure` | [site-restructure —— 官网 IA 重构 section 设计（Site 1.4）](site-restructure/README.md) |
| `supporting-surfaces-ui-2.0` | [Supporting Surfaces UI 2.0 handoff](supporting-surfaces-ui-2.0/README.md) |
| `user-manual` | [CC Pocket 用户手册设计交接](user-manual/README.md) |
| `win-tray-flyout-292` | [Windows 托盘浮层（Tray Flyout）· issue #292](win-tray-flyout-292/README.md) |
| `workflow-view` | [Workflow 编排视图 — 运行卡片／进度树／journal 回看／桌面停靠面板（设计 handoff）](workflow-view/README.md) |
| `worktrees-281` | [worktrees-281 设计交付（批次 4）](worktrees-281/README.md) |
| `IMPLEMENTATION_HANDOFF` | [Issue #228 ZCode backend — Claude implementation handoff](zcode-issue-228/IMPLEMENTATION_HANDOFF.md) |

## 查看原型

每份设计说明的首部链接指向清理前提交的原始目录；部分文档另有 Claude Design 在线画板。需要本地预览或验证 SHA256SUMS 时，先从外部备份或固定提交导出相应原型及其依赖到独立目录，再执行文中的历史预览命令。新 clone 只包含 Markdown，不应直接在本目录执行原型预览命令。

今后新设计只提交评审后仍有维护价值的说明；完整导出和原始对话放仓库外或 `_local/design/<task>/`。不把生成工具的运行时、脚本探针和重复媒体重新加入此目录。
