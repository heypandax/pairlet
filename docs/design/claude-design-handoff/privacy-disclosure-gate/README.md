# Privacy Disclosure Gate v1（首启数据披露同意页）

- 在线设计板：https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d（文件「Privacy Disclosure Gate v1」，48 页项目内）
- 文件：`Privacy Disclosure Gate v1.dc.html`（深色 390×844 / 浅色 390×844 / 小屏 375×667 滚动态；showDiagram、showFootnote 两个开关）
- 背景：App Review 5.1.2(i) 合规（1.9.5 三轮驳回，仅剩此条）；首启先于配对与 Demo 的一次性同意门
- 落地：`mobile/.../ui/PrivacyConsentScreen.kt` 按稿实现（线图卡、四个 20dp 线性图标、hairline 分隔、docked 操作区自吃 home-indicator inset）；颜色全部走 Tok token，浅色自动成立
- 设计要点：小屏上线图最先滚走（它不承载四要点之外的信息）；操作栏永不移动
