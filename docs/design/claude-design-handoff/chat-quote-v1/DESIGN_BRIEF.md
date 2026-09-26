# Chat Quote v1 · 设计 brief

日期：2026-09-26（本机时区）。状态：已投递 Claude Design（cc-pocket Design System 2.0 项目），采用方向 1a 并已实现，见 [README](README.md)。范围：Agent 回复中 Markdown 引用块的视觉与复制入口；不改用户消息、代码块、表格和整条回复的复制。

## 输入与观察

当天刚上线引用块渲染（`Markdown.kt` 的 `QuoteBlock`）：surface 底色面板、8dp 圆角、左侧 3dp muted 竖线、正文 tx2，面板右下角放“复制”，只复制引用内容（去掉 Markdown 标记的纯文本）。

用户在手机上反馈（两张 Codex 会话截图，含个人信息，不外发）：引用块与用户自己发的消息配色和形态几乎一样——都是带底色的圆角深色块、右下角都有“复制”——而且引用块比用户消息还显眼，抢注意力，整页显得乱，角色也容易混淆。

对照 [Chat Roles v1](../chat-roles-v1/README.md)：用户消息是 raised 底色 + 四边 hairline + 12dp 圆角的封闭容器，Agent 回复在页面底色上按文档流展示。当前引用块使用了与用户消息同一套容器语法，这是问题的直接来源。

## 设计要求摘要

- 引用属于 Agent 正文的一部分，层级低于用户消息和 Agent 正文本身，不能再像一条消息或卡片。
- 一眼能与用户消息区分，深浅色、陶土色与青绿色主题都要成立。
- 保留“一键只复制这段引用”：常见用法是 Agent 用引用给出可直接发出的草稿；iOS 没有可靠的划选复制菜单，所以需要可见的一键入口。入口形态和位置不能与整条回复的“复制”混淆，平时安静，复制后有约 1.5 秒反馈，触控区域不小于 44pt。
- 引用文字要保持易读，因为它经常是回复中最有用的部分。
- 对比 2–3 个方向并放入同一会话上下文，附当前版本作为对照，给出一个推荐和理由。

## 隐私与内容

示例使用虚构的人物与任务，不发送用户截图、真实会话、路径或账号。

## 投递提示（英文）

```text
Chat Quote v1 — redesign the Markdown blockquote inside Agent replies (mobile first, desktop parity)

Context
Pairlet (this project, Design System 2.0) renders Agent replies as Markdown flowing directly on the page background, with no container. Chat Roles v1 gives the real user's turn a closed neutral container: raised fill (#1E2125 dark / #F1EFEB light), a 1dp hairline border (#2A2E33 / #E4E1DB), 12dp radius, a trailing "你" source label, and a small text action "复制" (copy) at the bottom-right. Each Agent reply also ends with a right-aligned "复制" that copies the whole reply. Code blocks are the other container inside a reply: base fill, hairline border, a surface header row with the language label and its own "复制".

We just shipped blockquote support. Current rendering: a filled panel (surface #16181B dark / #FFFFFF light), 8dp radius, a 3dp muted (#6B7177 / #878C92) rule down the left edge, body text in the secondary color (tx2 #9BA1A6 / #5B6066), and a "复制" text action at the panel's bottom-right that copies only the quoted text (plain text, Markdown markers removed).

Problem (user feedback from the phone): the quote panel and the user's own message now look almost identical — a filled dark rounded box with "复制" at the bottom-right — and the quote reads even heavier than the user's turn. It grabs attention, breaks the Agent's reading flow, makes the page feel messy and blurs who said what.

Why the quote needs its own copy: Agents often put text meant to be sent elsewhere in a quote — "可以这样发：" followed by a quoted draft message — and the user wants to copy just that draft in one tap. iOS has no reliable select-to-copy menu, so a visible one-tap copy for the quote is required. Quotes also appear as short citations or notes.

Goal — a blockquote that:
1. Reads as part of the Agent's prose: subordinate to the user turn and to the Agent's own text, never a turn-like card.
2. Is unmistakable from the user turn at a glance, in dark and light, with both accent themes (terracotta Pocket, teal Codex).
3. Keeps a one-tap "copy this quote" action that cannot be confused with the reply-level "复制" (different placement and/or form), stays quiet at rest, and shows clear "已复制" feedback for about 1.5s. Touch target at least 44pt even if the visible glyph is small.
4. Keeps the quoted text comfortably readable — it is often the most useful part of the reply, so don't fade it into illegibility.

Explore 2–3 directions and show them side by side in the same conversation context, with the current version as a "before" reference. Recommend one and explain why. Starting points (not mandatory): a rule-only quote with no fill; indented text with a hairline rule and a small copy icon aligned to the first line; a quiet copy icon living in the rule's gutter; a copy action inline after the last line. Avoid: filled panels in raised/surface tones, full borders, 12dp-radius cards, accent-colored fills or rules, and anything that repeats the user turn's container grammar.

Scenes (Chinese UI, fictional content; 402×874 mobile artboards, dark primary with a light counterpart)
A. Core case. User turn: "帮我回一下设计同事 Mia，说这周先不改首页". Agent reply: one sentence, "可以这样发：", a three-paragraph quoted draft ("嗨 Mia，这周我们先集中修登录流程的问题，首页改版挪到下周。" / "你上次给的两版稿子我都看过了，第二版的导航更清楚。" / "周一上午有空一起过一下细节吗？"), one closing sentence, then the reply-level "复制". Show the next user turn below so the two can be compared directly.
B. A one-line quote used as a citation between paragraphs, followed by more prose and a short code block, so the quote-vs-code-block hierarchy is visible.
C. Edge states: a quote containing a bullet list with bold and inline code; a nested quote (rare, must stay light); a long quote; the copied state; a quote still streaming (last line growing).
D. Desktop: one chat column (the message stream caps at 760dp) showing scene A with the same treatment, including a hover state for the copy action if the direction has one.
E. Stress: 320pt width with 1.6× text scale.

Constraints
- Use this project's Design System 2.0 tokens only. Dark: base #0E0F11, surface #16181B, raised #1E2125, hair #2A2E33, tx #ECEDEE, tx2 #9BA1A6, muted #6B7177. Light: base #FAF9F7, surface #FFFFFF, raised #F1EFEB, hair #E4E1DB, tx #1C1D1F, tx2 #5B6066, muted #878C92. Body text 14sp; keep the current paragraph rhythm (3dp between lines, about 9dp for a blank line).
- Text and an icon on one row must be optically centered.
- Don't change the user turn, code blocks, tables, headings, the reply-level copy, the header or the composer.
- The quote's copy keeps copying plain text with Markdown markers removed; design the visuals only.
- Output a new file "Chat Quote v1.dc.html" (helpers prefixed ChatQuoteV1). Don't modify other project files. Add a compact spec for the recommended direction: rule width and color, indent, text color, spacing above and below, copy glyph size, color and position, pressed and copied states, with dark and light values.
```
