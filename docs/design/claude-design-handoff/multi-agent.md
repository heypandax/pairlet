# Multi-Agent（Claude · Codex）设计落地记录

> 设计源：claude.ai/design 项目 `cc-pocket`（projectId `93b56700-6ed2-46c9-bf81-3fd0b1a6340b`），文件 `cc-pocket/Multi-Agent.html` + `agents-core.jsx` + `agents-app.jsx`（壳 + 组件，存云端，可用 DesignSync get_file 取回）。
> 落地日期：2026-06-24。对应需求见 [`../CODEX-MULTI-AGENT.md`](../CODEX-MULTI-AGENT.md)。

## 关键设计决策（来自设计稿）

- **Agent 身份色**：Claude = 陶土 `#D97757`（既有 app accent）；**Codex = 青色 `#3FB5AC`**（落到 `Tok.codex`）。
- **glyph**：Claude = shell 提示符箭头 `>` + 下划线；Codex = 同心圆 + 上下轨道弧。
- **身份呈现策略（设计推荐，已采用）**：**只标 Codex，Claude 默认不标**——常见的 Claude 路径保持安静；会话信息 sheet 里 agent 始终显式（一行 Agent）。
- **Codex 权限 = 两轴**：approvalPolicy（every/needed/never）× sandbox（read/workspace/full），用 4 个命名预设承载：Cautious / **Balanced（推荐默认）** / Autonomous / Full auto。

## 落点映射（设计 → Compose 代码）

| 设计块 | 实现位置 |
|---|---|
| Agent 身份（AgentTag/glyph/色） | `mobile/.../ui/AgentIdentity.kt`（新）；`theme/Theme.kt` 加 `Tok.codex` |
| 头部 / 会话列表 Codex 徽标 | `ui/App.kt`（连接栏 + 列表行用 `AgentTag`，只标 Codex） |
| ① 新建会话 sheet（agent 卡片 + Claude 模式 / Codex 预设） | `ui/Permissions.kt` `StartSessionModeSheet` + `AgentOption`/`PresetRow`/`CODEX_PRESETS` |
| ② Codex 两轴预设 → 后端策略 | `daemon/codex/CodexBackend.kt` `approvalPolicy()`（PLAN=untrusted/DEFAULT=on-request/ACCEPT_EDITS=never/BYPASS=never）+ sandbox |
| ③ 模型选择器按 agent | `ui/SessionSheets.kt`（`CODEX_MODEL_OPTIONS` + QaSub.MODEL agent 感知）；会话信息加 Agent 行 |
| ④ Codex 改文件 diff 审批 | 协议 `PermissionAsk.diff`；`CodexBackend` 捕获 `fileChange.changes[].diff`（限 6000 字符）；`PermissionBridge` 透传；`ui/Permissions.kt` `DiffView`（增绿删红、限高滚动、超长截断） |

## 与设计稿的差异 / 未做

- **i18n**：Codex 预设名/描述、Agent 行标签、模型 hint 暂为英文硬编码（其余沿用 Res.string）。后续补中文资源。
- **② 高级两轴展开**：设计有「Advanced 展开两个 segmented 轴」做任意组合 + Custom；当前协议只有 4 个 PermissionMode，已实现 4 预设，**任意两轴组合（Custom）未做**（需扩协议）。
- **④ 多文件 diff 折叠**：当前把 changes[] 的 diff 拼成一段渲染；设计的「多文件可折叠头」未做（合并成单段滚动）。
- glyph 用 Compose Canvas 绘制（非 SVG/ImageVector），视觉等价。
