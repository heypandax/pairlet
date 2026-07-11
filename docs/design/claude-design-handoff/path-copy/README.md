# 路径复制微交互 — 桌面 hover chip + 手机长按 sheet（设计 handoff）

- **在线设计板**（登录 pandaleeng@gmail.com 即看）：
  - 桌面 hover→copy chip（5 demos + 浅色）：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FCopy+Chip.html>
  - 手机长按→action sheet + 三实体对照表：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FLong-press+Sheet.html>
- **生成**：2026-07-11，claude.ai/design 主号正典项目 `93b56700`，模型 Opus 4.8 Medium，两个 Prompt 顺序追加，均一次通过（0 撞限、0 重试）
- **对应 issue／任务**：#116（#74 识别与打开的分享侧续篇；#95 已修划选）。**实现待定稿派发**：识别层（`Markdown.kt` 四种路径形状 + URL）不动，只给已有 annotation 加 hover/长按→复制的 affordance；远程路径从「不加 annotation」升级为「copy-only annotation」。不涉及 daemon/协议
- **本地打开**：目录下 `python3 -m http.server` 后访问两个 HTML（各引自身 jsx；jsx 内联定义 token，自包含无外部依赖）

## 文件清单

| 文件 | 内容 |
|---|---|
| `Copy Chip.html` | Prompt 1 入口：桌面 hover→copy chip（5 demos + 浅色 Demo2/4） |
| `copy-chip.jsx` | Prompt 1 组件源：chip 解剖、hover 浮层、规范化展开、copied ✓、长值换行/翻转（token 内联）——**像素规格主参考** |
| `Long-press Sheet.html` | Prompt 2 入口：手机长按 action sheet + 三实体对照表 |
| `longpress-sheet.jsx` | Prompt 2 组件源：ActionSheet（copy-only / copy+open 两变体）、copied pill、Frame B 六格深浅对照（token 内联） |

## 两屏内容

**Prompt 1 · 桌面 hover chip（`Copy Chip.html`）**——一段含三实体的 transcript 摘录，5 个同模式状态 demo：
1. rest — 三实体：可打开本地路径（info 蓝 #5B9BD5 实线）/ 仅复制远程路径（正文色 + **虚线** muted #6B7177，无蓝、不许诺点击）/ URL（info 蓝实线）
2. span hover — 悬停 span 抬 raised 背景 + 浮层 chip（span 上方 8px 左对齐，raised #1E2125 + hairline，内含完整值 mono 13 单行 + copy 图标，chip 本身可 hover 进去点）
3. normalization — inline 相对路径 `docs/design/UI-DESIGN.md`，chip 显解析绝对 `/Users/panda/dev/cc-pocket/docs/design/UI-DESIGN.md`（无额外 caption，展开自解释）
4. copied — 点 copy（或点 copy-only span）→ chip 内图标变 ✓（success #4FB477 "Copied"），就地反馈无全局 toast
5. long value — 超长 URL 在 chip 内换行到两行（完整值恒可见，不横滚），贴近面板顶时 chip 翻转到 span 下方
   + 浅色 Demo2/4 副本（info #3B7DC4 / success #2E9E5B）

**Prompt 2 · 手机长按 sheet（`Long-press Sheet.html`）**——
- Frame A（iPhone 框，sheet 开）：A1 路径 copy-only（file 图标 + 解析绝对值 mono 多行 + 相对时显 "resolved from session cwd" + 单 Copy 行）/ A2 URL copy+open（globe 图标 + Copy + Open in browser）/ A3 copied pill（sheet 收起后底部 home indicator 上方浮 success ✓ "Copied" pill，非常驻 toast）
- Frame B（三实体深浅六格对照）：可打开本地路径（info 蓝实线，"click/tap opens · hover/long-press copies"）/ 仅复制远程路径（正文色虚线 muted，"click copies · no open"）/ URL（info 蓝实线，"click/tap opens browser · hover/long-press copies"）——深浅两主题各三格，仅靠「下划线线型 + 有无 info 色」区分不依赖新色

## 落地状态

**未实现**（本轮到设计定稿归档为止）。实现落 Compose 两端：现成可复用 `rememberCopied()` 的 1.5s 复制确认节拍、CodeBlock CopyChip 的 copy→copied 前例；识别层与 `exists()` 门不动。jsx 内联 script 即像素规格。

## 关键约定核验（全部遵守）

- **三类实体视觉**：可打开=info 蓝实线（现状不动）；**仅可复制=正文色 + 虚线 muted 下划线**（降一档不许诺点击，深浅只靠线型+有无蓝区分，不引新色）；URL=info 蓝实线；三者均 mono 13 嵌 sans 正文（§2.2 气质）。与 inline code（mono + 陶土无下划线）天然不撞。
- **规范化「所见即所复制」**：chip/sheet 头显解析后绝对值，相对→绝对展开自解释；桌面 chip 不加说明，手机 sheet 相对时才给一行 "resolved from session cwd"。
- **复制反馈分工**：桌面 chip 内 ✓（视线在 chip），手机 sheet 收起后底部 pill（chip 已不在场）——反馈永远出现在视线所在处。
- **copy-only span 点击=复制**（无打开动作，死点击变最快路径）。

## 占位提醒（brief 原话）

稿内具体路径/URL 均为演示占位；"Copied""resolved from session cwd" 等文案实现按 i18n 校准；出现/消失时长（350ms 出现、~150ms 离开宽限、1.5s copied）是给实现的节拍建议，设计稿不表达。

## 与 brief 的偏离与待拍板项

- **无结构性偏离**——两个 Prompt 的 LAYOUT／STATES／DELIVERABLES 逐条落全（桌面 5 demos + 浅色，手机 Frame A 三态 + Frame B 六格）。
- brief 建议的「仅可复制样式 variants」（虚线 / 前缀图标 / 淡底 chip 化）**未展开**——一次成型已选虚线下划线方案（brief 首选）；两端已统一，要比稿可补一轮。
- 两屏各自包含（jsx 内联 token，非外链 ios-frame/desktop-core），实现读各 jsx 即可。
