# Chat Roles v1 — 用户与 Agent 消息区分

日期：2026-09-22。对应 [Issue #397](https://github.com/heypandax/pairlet/issues/397)。用户授权在当前 Claude Design 项目完成方案后按稿优化。

- 设计项目：cc-pocket Design System 2.0。
- [在线设计板：Chat Roles v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Chat+Roles+v1.dc.html)。
- 设计生成：Claude Design，界面显示 Opus 5 Medium；生成使用用户的 Claude Design 额度。
- 范围：真人用户消息的容器与来源标签。仅替代 Chat Master v2 中“用户消息无容器”的视觉约定；聊天框架、正文、附件、操作和其他消息类型继续以现有实现为准。

## 采用方案

设计板比较“强调色底 + 左色条”和“中性背景 + 四边细线”，采用后者。用户消息用封闭容器与明确的来源标签区分；Agent 回复继续在页面底色上按文档流展示。切换陶土色或青绿色强调色不改变角色识别方式。

| 项目 | 深色 | 浅色 / 规则 |
|---|---|---|
| 用户容器 | `Tok.raised`，`#1E2125` | `#F1EFEB`，不透明，不混入强调色 |
| 边线 | `Tok.hair`，`#2A2E33` | `#E4E1DB`，四边 1dp；移除左色条 |
| 来源标签 | `Tok.tx2`，`#9BA1A6` | `#5B6066`，使用次级正文色而非 muted |
| 标签排版 | 11sp / 15sp，等宽、Medium、字距约 1.3sp | 独立一行，尾端对齐，与内容相隔 7dp |
| 普通容器 | 圆角 12dp，水平 / 垂直内距 12 / 10dp | 手机及桌面默认布局均保持完整可用宽度 |
| 桌面右气泡 | 圆角 14dp，内距 14 / 11dp | 随短内容收缩，仅最大宽度限制为 520dp |
| 正文与列表 | 保留现有排版、间距 | 手机正文 14sp，桌面正文 14.5sp / 22sp；不统一重排 |

边线只提供辅助形状，不宣称达到 3:1 非文本对比度。必需的来源文字在容器上的对比度约为深色 6.19:1、浅色 5.52:1；自动化检查读取实际文字样式验证最低 4.5:1。没有实测强制颜色模式或低质量屏幕，不把设计判断当成这些环境的验收结果。

## 实现映射与边界

- `theme/Theme.kt`：`userTurnBg`、`userTurnBorder`、`userTurnLabel` 使用现有中性色语义 token。
- `ui/chat/ChatChrome.kt`：共用 `UserTurnContainer` 与 `UserTurnSourceLabel`；容器保持纵向排列，防止标签与内容重叠。
- `ui/App.kt`：手机来源行采用共用标签，保留既有整列宽度。
- `desktop/ChatPane.kt`：桌面标签尾端对齐；默认布局填满内容宽度，右气泡仍按内容收缩。
- 图片、文件、选择复制、长按回退、发送/未送达状态和既有重试逻辑不变；不增加操作、角色或协议字段。

设计板中的“上下文摘要”折叠/展开状态用于确认三类内容的层级，属于 #394 的集成参考。本次 #397 修改不实现其新边线、文案、44dp 披露行，也不改摘要分类逻辑；不能将设计板展示理解为这些细节已进入本次代码。

## 验证与原型归档

`ChatRoleDesignTest` 渲染真实手机 `ChatScreen` 与桌面 `ChatPane`，使用合成消息和本地生成的附件图；覆盖深浅色、青绿色主题、320dp 放大字号、桌面两种对齐和短气泡。检查标签/正文不重叠、可读对比度、内容不越界及短气泡不被撑宽。

本轮 worktree 聚焦回归 106 项通过；同步主目录后 10 项集成检查通过。完整检查的其他模块无失败，移动端 1,896 项中有 4 项已复现于主分支基线的失败（EntryFlowUiTest、AgentPresetRowTest 各两项），因此完整脚本仍返回非零。

设置 `CHAT_ROLE_DESIGN_OUT` 可保存真实 Compose 截图：

```bash
CHAT_ROLE_DESIGN_OUT=/tmp/pairlet-chat-roles JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :mobile:composeApp:desktopTest --tests '*ChatRoleDesignTest'
```

前后截图、生成 brief 与运行日志保存在本地忽略目录 `_local/task-handoffs/2026-09-22/design-397/`。只维护本 Markdown 与在线来源，不把原始设计对话提交到仓库。已通过页面的 Project archive 执行导出，但本次内置浏览器没有返回下载事件或可读文件，且不支持页面内容导出，因此未取得离线源文件 ZIP；在线画板与本说明是本次设计交接来源。测试结果和未覆盖项见该目录的交付回执。
