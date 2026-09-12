# Privacy Disclosure Gate v1（首启数据披露同意页）

> 原型存档：本目录只维护设计说明；文中的 HTML/JSX、截图、校验清单及预览命令对应[清理前原件](https://github.com/heypandax/pairlet/tree/a5e1b8687185052b1c51f5269f18d62b9b29b826/docs/design/claude-design-handoff/privacy-disclosure-gate)，需从存档恢复后使用。实现状态以当前源码为准。

- 在线设计板：https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d（文件「Privacy Disclosure Gate v1」，48 页项目内）
- 文件：`Privacy Disclosure Gate v1.dc.html`（深色 390×844 / 浅色 390×844 / 小屏 375×667 滚动态；showDiagram、showFootnote 两个开关）
- 背景：App Review 5.1.2(i) 合规（1.9.5 三轮驳回，仅剩此条）；首启先于配对与 Demo 的一次性同意门
- 落地：`mobile/.../ui/PrivacyConsentScreen.kt` 按稿实现（线图卡、四个 20dp 线性图标、hairline 分隔、docked 操作区自吃 home-indicator inset）；颜色全部走 Tok token，浅色自动成立
- 设计要点：小屏上线图最先滚走（它不承载四要点之外的信息）；操作栏永不移动
