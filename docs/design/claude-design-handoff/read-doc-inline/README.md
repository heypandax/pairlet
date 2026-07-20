# Read the doc, right here — 文档直达阅读（handoff）

移动端「文档直达阅读」的设计交付：把「电脑上 Claude 生成的 md 文档」在手机端变成可点、可读的入口链路。中等偏小的入口打通单，不是文件管理器。

## 在线设计板

claude.ai/design 项目 `cc-pocket`，本轮文件 `Read Doc Inline.html`（登录即看）：

<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FRead+Doc+Inline.html>

生成模型 Opus 4.8 Medium（Fable 5 周配额已满，自动落到 Opus，质量足够）。

## 文件清单

- `Read Doc Inline.html` —— 入口板（import 下面两个 jsx）。
- `docopen-parts.jsx` —— **像素规格源**：DARK/LIGHT token、icons、`PathLink`（Component 1，三变体 pill/inline/code）、`ToolCard`（Component 2，路径 chip + 竖分隔 + chevron 的一卡两用手势分区）、`FileViewer`（Component 3，read / error-export / loading）。
- `docopen-app.jsx` —— 板面装配（把 parts 组进手机 artboard 的各状态）。

zip 未含 chat transcript（设计对话留在网页端）。

## 三个组件与关键设计决策

1. **Component 1 — 正文可开路径链接**：openable 文件路径用**陶土色**下划线（区别于 URL 的蓝色、copy-only 的虚线灰）。三变体：pill（独占行的 chip）/ inline（句中，tinted 下划线 + 可选 doc glyph）/ code（代码块内仅下划线，不与代码块自己的 copy 按钮打架）。乐观点亮：低承诺的「tap to try」，不是保证有效的大 CTA。
2. **Component 2 — Write/Edit 工具卡「打开文件」**：路径落进一个陶土 tinted 的 bordered chip（doc glyph + mono 路径 + open-arrow）= 打开；竖分隔线 + chevron = 展开/折叠。空间分区解决一卡两用手势冲突，不用隐藏长按。
3. **Component 3 — md 阅读页**：复用现有全屏 FileViewer（已自研 MarkdownText 渲染）。重点是新的**错误/导出态**：lock glyph + "Can't read this file directly" + "It's outside the synced workspace" + 「Export & open」主按钮 + 路径 + 「Copy path instead」兜底；以及 loading 态（spinner + "Opening report.md…"）。

## 落地映射（Compose 实现）

| 设计 | 实现落点 |
|---|---|
| Component 1 链接（mobile 助手 markdown 流） | `LinkifiedText.kt` `styled()`：OPEN=陶土 / URL=蓝 / COPY=虚线灰。opener 由 `RemotePathOpener`（`Markdown.kt`，`exists()` 乐观恒 true）在 `App.kt` 聊天屏经 `LocalPathOpener` 注入，`open()`→`repo.openChangedFile()` |
| Component 1 链接（desktop） | `Markdown.kt` `withPathLinks()` 路径改陶土（`withLinks` 参数化 color），URL 保持蓝 |
| Component 2 工具卡 | `App.kt` `ChatItem.Tool` 分支：Write/Edit/MultiEdit/NotebookEdit 且 preview 形如路径时渲染 `OpenablePathChip`（陶土 tinted + OpenInNew），卡片其余保留展开/折叠。配套 daemon `Conversation.kt` 对文件写入类工具下发**干净 tilde 路径**（原先是埋着 content 的原始 JSON） |
| Component 3 错误/导出态 | `FileViewer.kt` exportSlot 增「Copy path instead」兜底（`file_copy_path` 字符串）；daemon `SessionFilesService.kt` `~/` 展开让工具卡 tilde 路径按 daemon home 归一后过原 containment 闸门 |

## 与设计的偏差（如实记录）

- **链接下划线颜色**：设计想「正文色文字 + 陶土下划线」，Compose 的 `AnnotatedString` link 无法让下划线色 ≠ 文字色，故实现为「陶土文字 + 陶土下划线」（标准 link 处理）。观感等价、更省事，符合「陶土=可开」的核心决策。
- **Component 3 错误态**：设计给了 lock glyph + headline + explainer + 「Export & open」的完整卡。实现复用现有 FileViewer 错误文案 + exportSlot，新增「Copy path instead」兜底；未新造整卡（错误渲染是 desktop/mobile 共享的 `FileTabBody`，重构风险不匹配「中等偏小」的分量）。树外文件受 containment 红线本就不可导出，兜底给复制路径是诚实 UX。
- **工程摸底修正**：Component 2 依赖的 transcript 工具卡 preview，实际是原始 JSON（`Conversation.kt`），非 `ToolMeta.kt:46` 的 tilde 路径（那只用于 PermissionAsk 审批卡）；已在 daemon 侧对齐为干净路径，并在 mobile 侧加 JSON 形态守卫防混版。
- **P1 前提已部分不成立**：daemon `serveAt` 早已按扩展名把 md/txt/log 返回为 `FileContent.text`（非 base64），且 #133 起 `readGate` 让 workdir 内任意文件直接可读——「项目目录里的 md」在 P0 打通入口后即内联可读，P1 的 daemon「改走 text 通道」实为已完成，用测试锁定而非新造改动。
