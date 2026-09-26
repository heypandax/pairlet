# Chat Quote v1 — Agent 回复中的引用块

日期：2026-09-26。来源：用户在手机上的反馈（Codex 会话截图，含个人信息，未外发）。用户要求用 Claude Design 设计后按稿实现。

- 设计项目：cc-pocket Design System 2.0。
- [在线设计板：Chat Quote v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Chat+Quote+v1.dc.html)。
- 设计生成：Claude Design，界面显示 Opus 5.5 Medium；生成使用用户的 Claude Design 额度。投递内容见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。
- 范围：Agent 回复中 Markdown `>` 引用块的样式与复制入口。用户消息、代码块、表格、标题、整条回复的「复制」均不变。

## 问题

引用块首版是 surface 底色的圆角面板，左侧 3dp 竖线，右下角放文字「复制」。它与 [Chat Roles v1](../chat-roles-v1/README.md) 的用户消息容器语法相同（带底色的圆角块、右下角「复制」），而且比用户消息更显眼。整页读起来像多了一条消息，分不清是谁说的。

## 采用方案：1a

设计板把首版和三个方向放在同一段对话里比较（深浅色各一行），推荐 1a：

- **1a（采用）**：不要底色、边框和圆角，只留 2dp muted 竖线和 12dp 缩进，文字用 tx2、正文字号。复制是首行尾端的 16dp 图标；整条回复的「复制」是底部文字，两者形态和位置都不同。
- 1b：图标放在竖线上方的左侧沟槽里。两个复制入口分得最开，但文字要缩进 30dp，放不下「已复制」。
- 1c：复制跟在引用最后一行后面。会贴着整条回复的「复制」，流式生成时还会跟着文字移动。

| 项目 | 深色 | 浅色 / 规则 |
|---|---|---|
| 容器 | 无 | 不加底色、边框、圆角和内边距，直接落在页面底色上 |
| 竖线 | 2dp · muted `#6B7177` | 2dp · `#878C92`；贴左缘，高度覆盖整个引用；不用强调色 |
| 缩进 | 12dp | 竖线到文字 12dp |
| 文字 | tx2 `#9BA1A6` | tx2 `#5B6066`；字号同正文，粗体、行内代码也保持 tx2 |
| 嵌套 | hair `#2A2E33` 竖线 | hair `#E4E1DB`；缩进 10dp，没有自己的图标，外层图标一并复制 |
| 复制图标 | 16dp · muted | 首行尾端 24dp 槽位，距文字 8dp，垂直居中于首行；文字 ≥1.3× 时为 20dp / 28dp 槽位 |
| 点击区 | 44×44dp | 以图标为中心，可向页面边距伸出 10dp |
| 按下 / 悬停 | 图标 tx2 + 28dp raised 圆底 | 桌面悬停另在图标左侧 6dp 显示「复制引用」（12.5sp tx2） |
| 已复制 | 对勾 + 「已复制」· tx2 | 竖线同时变为 tx2，保持 1.5 秒后在 150ms 内恢复 |
| 复制内容 | 不变 | 只复制这段引用的纯文本（去掉 Markdown 标记），含嵌套层级 |

## 实现映射

- `ui/Markdown.kt`
  - `QuoteBlock`：竖线、缩进、tx2 正文，首段为图标让位。
  - `QuoteCopyFloat`：图标、标签、按下与悬停圆底、已复制状态、无障碍描述和礼貌播报。
  - `quoteGlyphMetrics`：图标尺寸随文字缩放。
  - `touchTarget`：24dp 视觉槽位对应 44dp 点击区。
  - 解析（`parseBlocks`、`mdQuoteBody`）和复制文本（`mdPlainText`）不变。
- 字符串：新增 `quote_copy`（复制引用 / copy quote）和 `quote_copied`（已复制引用 / quote copied）；已复制标签复用 `code_copied`。
- 手机与桌面共用 `MarkdownText`，聊天、子 Agent 卡片、Workflow、计划审批和文件查看同时生效。

## 与设计稿的差异

1. **首行让位**：设计稿用浮动，只有首个视觉行避让图标。Compose 不能让文字绕排浮动元素，所以改为引用的整个首段收窄。首段较长时，后续几行也会少 32dp。
2. **行高**：设计稿按 14/22 标注。实现沿用正文实际行盒（环境文本样式的 lineHeight，手机为 24sp），图标居中于这个行盒。
3. **流式中的禁用态未做**：`MarkdownText` 不知道回复是否仍在生成。图标在流式时也可点，会复制已到达的文字，与整条回复的「复制」一致。
4. **键盘焦点描边未做**：桌面端鼠标点击也会让图标获得焦点，描边会在每次复制后一直停留。App 其他控件也都没有自绘焦点描边。
5. **列表圆点**：沿用正文的「•」字符，颜色随引用文字为 tx2；设计稿是 muted 小圆点。
6. **上下间距**：9dp 来自 Markdown 中引用前后的空行。Agent 没写空行时，间距是 3dp 行距。

## 验证

- `MarkdownQuoteTest`（7 项）覆盖以下场景：
  - 标记剥离，复制内容为纯文本；
  - 字面 `>` 保持原样，引用在首个无标记行结束；
  - 嵌套只有外层图标，深层嵌套有上限；
  - 已复制时的标签与无障碍描述切换。
- `ChatQuoteDesignTest`（6 项）渲染真实手机 `ChatScreen` 与桌面 `ChatPane`，场景是「用户消息 → 带引用的回复 → 下一条用户消息」。
  - 覆盖：深浅色、青绿主题、320pt×1.6 倍字号、桌面和已复制状态。
  - 检查：引用处没有底色、点击区 44dp、图标在首行且不压文字、已复制标签在图标左侧、竖线变为 tx2。
- 设置 `CHAT_QUOTE_DESIGN_OUT` 可保存截图：

```bash
CHAT_QUOTE_DESIGN_OUT=/tmp/pairlet-chat-quote JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :mobile:composeApp:desktopTest --tests '*ChatQuoteDesignTest'
```

## 原型归档

以下资料保存在本地忽略目录 `_local/task-handoffs/2026-09-26/chat-quote-v1/`：

- 前后对比截图；
- 设计板截图；
- 设计源文件 `export/Chat Quote v1.dc.html`。

页面 Export 的 Project archive 没有产生下载文件，源文件是从设计板页面直接读取的，依赖的 `support.js` 运行时未保存。仓库只维护本说明与在线设计板链接，不提交原型文件。
