# Changed files v2 — git-grade diff review（设计 handoff）

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2Fsite%2FChanged+Files+v2.html>（登录即看，工具栏可切每屏状态变体）
- **设计 brief**：`~/Desktop/Brain/60_Outbox/2026-07-07-cc-pocket-变更文件Diff设计提示词.md`
- **生成**：2026-07-07，claude.ai/design（Opus 4.8 Max），cc-pocket 设计项目追加模式

## 文件清单

| 文件 | 内容 |
|---|---|
| `Changed Files v2.html` | 评审文档主体：3 屏 × 15 个状态变体，工具栏切换 |
| `changed-files-v2.css` | diff 视觉语法（tint/gutter/hunk 带）、手机/桌面框、文件行、状态章 |
| `changed-files-v2.js` | 状态切换、hunk 折叠、分隔行展开、fit 缩放交互 |

## 三屏

1. **Screen 01** 移动端「Files changed」底部弹层——状态字母章（A/M/D/N）+ 彩色 +N/−M；变体：default / empty / stale-daemon / loading
2. **Screen 02** 移动端全屏 diff 查看器——[ Diff | File ] 分段切换 + 共享 diff 语法（hunk 折叠、12% tint、单侧行号 gutter）；变体：diff / deleted / image / truncated / stale
3. **Screen 03** 桌面 Changes 双栏浏览器——chat header「± 8」入口 pill + 1040×640 居中 modal（左文件列表右 diff 详情、双行号 gutter、键盘提示 footer）；变体：default / loading / stale / deleted / image / empty

## 落地状态（2026-07-07 同日实现）

- 协议：`ChangedFile.adds/dels`（可空增量）+ `ReadFileDiff`→`FileDiff`（`pocket/diff.read`/`pocket/diff.content`）
- daemon：`SessionFilesService` 统一扫描——Claude `toolUseResult.structuredPatch`（真实行号 hunk）/ Write-create 合成 all-add / Codex apply_patch 信封转 hunk；256KB 截断
- 移动端：`FileViewer.kt`（列表行升级 + Diff/File 查看器）、共享 `DiffView.kt` + `DiffModel.kt`
- 桌面端：`ChangesOverlay.kt`（± pill + 双栏浏览器，↑↓ 切文件 / esc 关 / hunk 点击折叠 / copy path）

与设计稿的有意偏差：
- 「⋯ N unchanged lines」分隔行**不可展开**（daemon 的 diff 按工具调用记录，间隙内容不在 wire 上；行号可知时仅展示计数）
- 截断横幅只报「已显示 N KB」不报总量（daemon 不知道未截断总大小）
- 桌面 footer 快捷键提示只列已实现的（↑↓ / click @@ / esc）；⌥←→ 与 ⏎ 未实现故不展示
- copy-hunk 按钮暂未做（复制路径已做）；后续需要时加
