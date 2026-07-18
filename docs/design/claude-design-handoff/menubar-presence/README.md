# 菜单栏状态胶囊（issue #151 方向 1）设计交付

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FMenu+Bar.html>
- **生成日期**：2026-07-16，追加模式（复用 desktop token 与 TrayPopover 节语法）
- **brief 源**：`~/Desktop/Brain/60_Outbox/2026-07-16-cc-pocket-菜单栏状态胶囊设计提示词.md`

## 文件清单

| 文件 | 说明 |
|---|---|
| `menubar.jsx` | 像素规格源码（图形状态条＋锚定 popover 两帧） |
| `Menu Bar.html` | 设计板页面壳 |

## 稿面内容

1. **A · 图形状态语言**——菜单栏五态特写：Idle（单色模板图形）／Running（mono 会话计数，仍单色）／Needs you（terracotta 点＋计数，唯一彩色态）／Done（绿勾闪现后衰减回 idle）／Offline（空心 50% 透明）。
2. **B · 锚定 popover**——~360pt 菜单栏原生感浮层：头部 “cc-pocket · 2 computers · 5 sessions”＋齿轮；NEEDS YOU 节（会话题＋mono 请求行＋机器 chip＋行内 Deny/Allow）；RUNNING 节（耗时 mono＋streaming 脉冲点）；脚部 “+2 more sessions”＋“Open cc-pocket ⌘⏎”。与窗内 TrayPopover 同语法、上浮到 OS 层。

## 落地状态

- [x] 已实现（分支 worktree-agent-ae041722ea146dee0）——issue #151 只做方向 1；落点：`MenuBarExtra.kt`／`MenuBarState.kt`（五态图标＋锚定 popover，复用 `Tray.kt` 的 TrayPopover）＋桌面 `Main.kt`（application 域常驻）＋`DesktopModel.attention/running`（数据已通）。方向 2（待办墙）／方向 3（状态性格）后置不做。
