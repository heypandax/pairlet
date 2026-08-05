# cc-pocket 设计文档（Claude Design 版）

cc-pocket 移动端设计资料，**统一采用 claude.ai/design**。Stitch 的历史产物与选型对比已下线、归档到 Obsidian（见文末）。

## 内容

| 文件 / 目录 | 说明 |
|---|---|
| `UI-DESIGN.md` | 设计规格：设计系统 ／ 7 屏逐屏 ／ 组件 ／ 状态 ／ 无障碍 ＋ i18n ／ §10 可粘贴生成 prompt |
| `CLAUDE-DESIGN-PROMPT.md` | 喂给 claude.ai/design 的开场 + 逐屏 prompt（生成本设计所用） |
| `claude-design-handoff/` | **设计版本本体** —— claude.ai/design 的 Handoff bundle：7 屏 `.html/.jsx` + 设计对话 `chats/` + `README`（coding agent 落地指引） |
| `REVIEW-REQUEST.md` | **任务上下文交接，后续实现依据** —— 围绕 MR / 文档发送异步评审请求；接收者使用自己的 Agent 和本地上下文。App / 桌面端 Review Center 是一等控制面（建联、发送、收件、回复），CLI 与 Skill 是它的对等入口；三者共用 daemon 的同一套 service，两个 UI 全关之后投递、重试和历史照常运转 |
| `SESSION-HANDOFF.md` | **运行时上下文交接，现有能力继续保留** —— 接收者在发起人电脑的原始 Session、代码和运行状态上接续；适合远程协助与联合调试 |
| `SESSION-HANDOFF-IMPLEMENTATION-REVIEW.md` | **现有能力维护记录** —— Session Handoff 的实现复审和安全边界；与 ReviewRequest 共享联系人、E2E 投递、通知和历史基础设施 |
| `PEER-CALL.md` | ⏸️ **已暂停** —— daemon-to-daemon Agent 互调的历史技术探索；ReviewRequest 只复用受限投递思想，不复用远程 Agent 调用模型 |
| `APPROVAL-SYSTEM.md` | **审批最终方案，作为实现与评审的唯一依据** —— 现有场景盘点、统一 Approval Coordinator、Task Contract/Grant、Agent 自主参与、审批路由、通知/队列/软超时、自动执行留痕、协议与分期验收 |
| `SMART-APPROVAL.md` | **风险评估子设计** —— 纳入统一审批系统：确定性行为序列＋Agent 风险建议＋高风险审批升级；不再作为独立审批架构 |
| `CHANNEL-INTEGRATIONS-EVALUATING.md` | ⚠️ **评估中** —— 官方渠道集成（Claude Channels / Slack、Codex Slack / Linear）机制调研与机会分析。含五条方向层候选，**均未定案、未录台账**；定案后去掉 `-EVALUATING` 后缀 |

> 命名约定：文件名带 `-EVALUATING` 后缀 = 调研 / 提案阶段，结论未定，不可作为实现依据。

## 设计系统速记

暗色优先；base `#0E0F11` ／ surface `#16181B` ／ 强调陶土 `#D97757`；UI 用 Inter，路径/代码/分支/token 用 JetBrains Mono；分组卡 + 1px 描边、无重阴影。完整见 `UI-DESIGN.md` §2。

## 本地预览 handoff

各屏是可运行的 HTML（React/Babel，走 CDN）：

```
cd docs/design/claude-design-handoff/project/cc-pocket
python3 -m http.server 8080
# 浏览器打开 http://127.0.0.1:8080/Settings.html
# 其余：Chat.html / Sessions.html / Directory.html / Pairing.html / Computers.html / Permission.html
```

## 落地

真身是 **Compose Multiplatform（Android + iOS 共享一套 UI）**。handoff 里那层 iOS 设备外壳只是 mockup chrome；Compose 实现属 M2，以 handoff 为像素级参照。

## 归档（不在本仓库）

Stitch 7 屏 + Stitch ⟷ Claude Design 选型对比 + 评估报告：
`~/Desktop/Brain/20_Projects/cc-pocket-设计工具评估/`
