# 内嵌终端（issue #153）设计交付

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FEmbedded+Terminal.html>（登录即看）
- **生成日期**：2026-07-16，追加模式（复用项目内 desktop-core.jsx 的 token／icon／窗口 chrome）
- **brief 源**：`~/Desktop/Brain/60_Outbox/2026-07-16-cc-pocket-终端内嵌设计提示词.md`

## 文件清单

| 文件 | 说明 |
|---|---|
| `embedded-terminal.jsx` | 像素规格源码（两屏组件树，token 色阶、尺寸、间距以此为准） |
| `Embedded Terminal.html` | 设计板页面壳 |

## 稿面内容

1. **① Terminal open · default**——嵌入面板停靠 ChatPane 底部约 35% 高：可拖 hairline 分隔条（hover 出抓手）、raised 头部行（终端图标＋中截断 cwd＋git 分支 chip｜外开／收起／关闭）、近黑正文跑真实 `git status` 输出、终端持键盘焦点时面板带 1px terracotta 内描边、滚动回溯顶部渐隐。
2. **② Collapsed strip · open-mode menu**——收起为 ChatPane 底部细状态条（图标＋cwd＋分支＋“1 running”点，点击恢复）；从终端图标锚出打开方式菜单：Open embedded（⌘J，默认，勾选）＋ Open in Ghostty（次级），注“Default can be changed in Settings → Terminal”。

## 落地状态

- [x] 已实现（分支 worktree-agent-aa56d76842bb3356c）——按稿落 Compose Desktop（`ChatPane.kt` 底部面板层＋`TerminalLauncher` 外开降级为次选）。
