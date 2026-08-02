# 审批系统 V2 界面（结构化 Grant 审批卡）— 设计 handoff

- **在线设计板**（登录 b01099485423@gmail.com 即看）：
  <https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=Approvals+V2.html>
- **生成**：2026-08-02，cc-pocket 正典项目追加，Opus 5 Medium 一次通过（自查修了两处：
  桌面紧凑按钮不再收缩换行、组件帧高度容下末行 caption）。
- **依据**：`docs/design/APPROVAL-SYSTEM.md` V2——审批从一次 Allow/Deny 升级为结构化
  Grant（本次／本任务／本 Session 三档）＋「换种安全方式」反提案＋Grant 覆盖内自动执行留痕
  ＋风险评估徽标。双形态：手机 PermissionSheet 底部弹层／桌面 ChatPane 内联卡。
- **Brief 存档**：`~/Desktop/Brain/60_Outbox/2026-08-02-cc-pocket-审批系统V2界面设计提示词.md`。
- **本地打开**：本目录 `python3 -m http.server` 后访问 `Approvals V2.html`
  （基础样式共享 `./session-handoff/handoff.css` 副本，增量样式在 `./session-approvals/approvals.css`）。

## 文件清单

| 文件 | 内容 |
|---|---|
| `Approvals V2.html` | 一张画布，7 帧 |
| `session-approvals/approvals.css` | 本轮增量样式（审批卡壳／风险徽标／信息层级／详情披露／动作区／安全方式面板／留痕 chip／组件表） |
| `session-handoff/handoff.css` | 与 Session Handoff 画布共享的基础 token 样式（副本，便于独立预览） |

## 帧清单

| 帧 | 内容 |
|---|---|
| 1 | 手机 sheet 常态：MEDIUM 风险，「Allow for this task」推荐高亮，2×2 动作网格，详情折叠 |
| 1b | 详情展开态：原始 command 的 mono codebox |
| 1c | 超时终态：卡体灰化 ＋ danger 横幅 ＋ Dismiss（沿用既有机制只换内容） |
| 2 | 「换种安全方式」子面板：5 个快捷约束 chips（多选）＋ 自由文本 ＋ Send to agent |
| 3 | 桌面会话流：HIGH 风险内联卡（横排动作）＋ 折叠留痕组（×6）＋ 一条展开的留痕 chip |
| 3b | 浅色主题审批卡变体（含 delta 行的正向绿勾形态） |
| 4 | 组件表：四态风险徽标并排 ＋ 双布局动作组 ＋ ⋯ More 菜单 ＋ 留痕 chip 解剖与权重梯 |

## 四个界面点 → 组件类名对照（approvals.css）

| 界面点 | 关键类名 |
|---|---|
| ① 审批卡动作区＋信息层级 | 壳 `.apcard`（`.risky` 高危变体）；头部 `.aphd` / `.aptool` / n/m 角标 `.qbadge` / 倒计时 `.ring`；层级 `.apsum` → `.ap-what`（结果）→ `.ap-why`（目的）→ `.ap-scope`＋`.schip`（影响范围 chips）→ `.ap-risk`（.hi/.md/.unk）→ `.ap-delta`（已授权差异，浅色卡有正向绿勾形态）；详情披露 `.apdet` / `.araw` / `.codebox`；动作 `.actgrid`（手机 2×2）/ `.actrow`（桌面横排）/ `.btn.rec`（推荐高亮）/ `.apmore`（⋯ More 溢出，内含降级的 Always allow this session）；超时 `.tobanner`＋`.dim` |
| ② 安全方式约束子面板 | `.cchips` / `.cchip`（`.on` 选中态）快捷约束；`.otherfld` 自由文本；`.swprev`（回传预览）/ `.swcap` |
| ③ 自动执行留痕 chip | `.achip`（单条：glyph＋mono 摘要＋时间）；`.abasis`（授权依据 "task grant" pill）；`.agrp`（连续同 Grant 折叠组）；`.achild`（组展开子行）；尾部 View / Tighten 文字链 |
| ④ 风险徽标四态 | `.rbg` ＋ `.low`（灰绿点）/ `.med`（amber 三角）/ `.high`（实心 danger 填充「Risk found」）/ `.unk`（空心虚线灰＋?「Not assessed」）——HIGH 实心 vs UNKNOWN 虚线空心，色弱下靠形状仍可区分 |

## 设计稿的关键裁定（聊天总结）

- **⋯ More 菜单自行加了两项**：「Allow for 15 minutes」和「Manage grants…」，与降级的
  「Always allow this session」并列——设计侧提议，实现时可裁剪（brief 未要求）。
- **delta 行有正向形态**：范围内的 ask 显示 "covered by current task grant"（绿勾），
  而不是永远只写超出项——恒负向措辞会让范围内请求也显得可疑（浅色卡帧展示了这个形态）。
- 超时终态与 n/m 角标沿用既有机制（灰化＋Dismiss、队列角标），只换 V2 内容。

## 落地状态

- 仅设计定稿；实现待排（工程侧对接 feat/approval-system worktree 的 ApprovalCoordinator：
  四动作 verdict 映射、TASK/SESSION 档 Grant、约束 chips 结构化回传 daemon、留痕 chip 按
  grantId 聚合、风险四态来自 daemon 评估字段；颜色一律走 Tok 语义 token 不新造 hex）。
