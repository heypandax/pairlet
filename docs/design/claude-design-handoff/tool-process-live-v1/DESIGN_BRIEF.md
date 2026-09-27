# Tool Process Live v1 · 设计 brief

日期：2026-09-26（本机时区）。状态：已投递 Claude Design（cc-pocket Design System 2.0 项目），采用方向 D1 并已实现，见 [README](README.md)。范围：聊天流里「执行过程」折叠行（#380）的运行态——在折叠行下方加一个显示「当前正在执行」的区域，并消除运行中工具先展开、再并入折叠行造成的跳动。不改正文、用户消息、子 Agent / Workflow / 计划 / 提问卡、顶部状态行和 composer。

## 输入与观察

用户在手机上反馈（语音转写）：折叠工具的设计能否优化——下面加一个区域显示当前正在执行的；现在每个工具会先出来一下再折叠起来，导致前面的内容跳动，体验不好。用户要求实现前先用 Claude Design 出稿，因为这是频率非常高的交互。

现状（`data/ChatPresentation.kt` + `ui/chat/ToolProcessCollapse.kt`，手机 `ChatScreen` 与桌面 `ChatPane` 共用）：

- 连续 ≥2 条「已结束」的步骤（结果为成功的普通工具、已结束的思考）收成一行 `ProcessGroupRow`：surface 底、1dp hair 边、8dp 圆角、「▸ 执行过程 · 8 个工具 · 1 次思考」、尾部「展开过程」。
- 运行中的工具 `ok == null` 不算已结束，按完整工具带渲染（手机：「工具」来源标签 + 上下 hairline + 工具 chip + 最多 2 行 payload，约 80–100dp；桌面：surface 卡片 + 状态点 + 名称 + 单行命令）。正在流式的思考单独一行「▸ 思考中…」。
- 回合进行中，列表末尾还有一个活动指示（accent 脉冲点，或「思考中…」行；桌面是闪烁光标）。

## 跳动机理

回合运行时列表贴底跟随（底部锚定）。每个工具先以完整工具带出现在末尾；结果回来（通常 <1 秒）后 `ok = true`，它并入上方折叠行，工具带消失，内容变短，上方所有内容整体下移约 90dp；下一个工具出现又整体上移。一个回合 20–50 个步骤，整屏持续上下弹。此外：

- run 的第一个工具结束后因不足 2 条仍以完整工具带停留，第二个结束时两条一起收起，跳得更大。
- 思考块同理：先单独一行，结束后并入。
- 失败的工具不折叠且会切断 run，之后的步骤另起一个折叠行。

## 设计要求摘要

- 运行期间零位移：步骤开始、结束、下一个开始、思考开始或结束，都不改变流内任何元素的高度；计数原位更新，实时区域原位换内容。
- run 以最终形态诞生：第一个步骤就进入折叠块（标题 + 实时区域），不再出现「完整工具带 → 收起」。
- 一眼看清「正在做什么」（工具名 + 一行目标）和「做了多少」（计数），不需要展开。
- 唯一允许的高度变化是 run 结束（开始写正文、回合结束、遇到不可折叠的卡片），要设计成「落定」而不是跳动，或说明如何避免。
- 失败不展开也要可见（#380 的产品规则）；由设计判断失败是否仍切断折叠块，还是留在块内用 danger 标记。
- 安静：动效 ≤150ms、交叉淡入、不闪烁；accent 只用于唯一的脉冲点；数字用等宽数字避免抖动。
- 展开/收起在运行中和已结束的块上都可用；读者展开过的块随增长保持展开，实时区域留在块底部。
- 对比 2–3 个方向，与当前行为（before）放在同一会话上下文中，给出推荐和理由。

## 隐私与内容

示例使用虚构项目与任务（`~/code/acme-web`、登录页错误提示），不发送用户截图、真实会话、路径或账号。

## 投递提示（英文）

```text
Tool Process Live v1 — a jump-free live region for the folded tool process in the chat stream (mobile first, desktop parity)

Context
Pairlet (this project, Design System 2.0) mirrors a coding agent (Claude Code / Codex) on the phone. One agent turn often runs 10–50 tool steps (Read, Grep, Edit, Bash, MCP tools…) interleaved with short thinking blocks and a few sentences of prose. This is the most frequent motion in the whole app.

Current behaviour ("Process fold", #380). In the chat stream, a run of 2+ consecutive FINISHED steps — tools whose result came back OK, and thinking blocks that ended — folds into one row: surface fill, 1dp hair border, 8dp radius, 12/9dp padding, "▸" in muted, label "执行过程 · 8 个工具 · 1 次思考 · 3 张图片" (tx2, 12.5sp medium), trailing action "展开过程" / "收起过程" (muted, 11sp). Tapping expands it: the header stays and the members render beneath it as their ordinary rows. Anything else ends a run and stays its own row: prose, a failed tool, a sub-agent card, a workflow card, the plan card, question cards, errors, turn-end markers.
A step that is still RUNNING is not "finished", so it renders as a full tool band: an uppercase "工具" source label above the first band of a run, a hairline top and bottom, a tool chip (11sp mono medium on raised, 5dp radius), a status on the right once known ("● 完成" / "■ 失败"), and the payload in 13sp mono tx2 (max 2 lines). A streaming thinking block renders as its own italic muted line "▸ 思考中…". While a turn runs, the stream also ends with a live tail: an accent pulse dot, or an italic "思考中…" row when nothing live is on screen.

Problem (user feedback, high priority). While a turn runs the chat follows the tail (bottom-anchored). Every step first appears as a full ~90dp tool band, then — usually under a second later, when its result arrives — disappears into the fold above. The content gets shorter, so everything above lurches down ~90dp; the next step appears and it lurches back up. With 30 steps in a turn the whole transcript bounces continuously. It is worse at the start of a run: the first finished step stays a full band until a second one finishes, then both collapse at once. Thinking blocks do the same.

The user's idea: keep the fold, and add a region beneath it that shows what is executing right now.

Goals
1. No movement while a run is live: a step starting, finishing, the next one starting, a thinking block starting or ending — none of these may change the height of anything in the stream. Counts update in place; the live region swaps its content in place.
2. A run is born in its final shape: the very first step of a run already lives in the block (header + live region). No step ever appears as a full band and then collapses.
3. At a glance: WHAT is running now (tool token + a one-line target: command, path, pattern) and HOW MUCH has run (counts), without expanding.
4. The one allowed height change is when the run ends (the agent starts writing prose, the turn ends, or a non-foldable card begins). Design that moment so it reads as settling, not a jump — or show how it can be avoided.
5. Failures stay visible without expanding (a #380 product rule), but benign failures (a test run exiting non-zero, an edit that missed) are common in real runs. Decide whether a failed step still splits the block (today) or stays inside it with a danger marker in the header or a persistent line, reachable in one tap.
6. Calm. Motion ≤150ms, crossfades rather than slides, no flashing, accent only on the single live pulse, tabular figures so counts and the elapsed clock don't jitter.
7. Expand/collapse keeps working on live and settled blocks; a block the reader opened stays open as it grows, with the live region staying at its bottom.

What the UI actually knows (design within these)
- Per step: the tool token verbatim (Bash, Read, Edit, Grep, WebFetch, mcp__playwright__browser_navigate, Codex shell / apply_patch…), a one-line payload preview, and an outcome that is running-or-unknown, OK, or failed; an image count when the result returned pictures.
- Elapsed time only from when THIS device first saw the step (no server start time) — the sub-agent card already works this way.
- Thinking: a live block without a duration, then "思考了 N 秒".
- No live tool output, no progress %, no ETA.
- Parallel calls: 2–5 steps can start at once and finish in any order.
- A running step may be waiting for the user's approval; the approval sheet and the pinned state line at the top of the chat own that decision — the block only needs to say it is waiting for you.
- A sub-agent (Task) runs as its own card with its own live grammar, which the new region should rhyme with: a status tile, "general-purpose · 调查登录失败", a sub-line "⌁ 7 个工具 · Grep" (mono 11sp, ECG glyph), and a 7dp accent pulse dot + elapsed "0:42" (mono 10.5sp, muted) on the right.

Explore 2–3 directions for the live region and show them side by side in the same conversation context, with the current behaviour as a "before" reference. Recommend one and explain why. Starting points (not mandatory): (1) one container — the fold card grows a second line under a hairline: pulse + tool chip + one-line target + elapsed; (2) the fold row unchanged, with a borderless live sub-line docked beneath it, indented to the label; (3) the live line replaces the stream's tail indicator and the header just counts. Also decide what a SETTLED block with exactly one step looks like (today a lone finished tool shows as a full band; folding it would make the live and the reopened views identical, but must not hide which tool ran), and whether the header text changes while live.

Scenes (Chinese UI, fictional content; 402×874 mobile artboards, dark primary with a light counterpart)
A. Filmstrip of one run, before vs after, same 5 moments: (1) user turn "把登录页的错误提示改成中文", agent sentence "我先看一下登录表单的实现。", first step Read ~/code/acme-web/src/login/LoginForm.tsx starts; (2) it finishes and Grep "errorMessage" src/ runs; (3) between steps, the model is thinking; (4) three parallel Reads; (5) the agent starts streaming its reply and the block settles. Annotate the vertical displacement of the user turn in each frame (before: ±90dp; after: 0).
B. State sheet of the live region: first step (no finished counts yet), short and very long payloads, a long MCP tool token, 3 parallel steps, thinking between steps, waiting for approval (Bash "pnpm test login"), a failed step (Bash "pnpm test login" exits 1), and a step whose result never arrived before the turn ended (must not look like it is still running).
C. Expanded during a run: finished members listed as today's rows, the live region at the block's bottom.
D. Settled: the same turn reopened later — collapsed block(s), a block with one step, two blocks separated by a short prose paragraph, and a running sub-agent card next to a live block for grammar comparison.
E. Desktop: one chat column (the stream caps at 760dp; desktop tool rows are surface cards with a 7dp status dot, bold tool name and a one-line mono command) showing the same run live and settled, with hover states.
F. Stress: 320pt width with 1.6× text scale; the Codex teal accent variant.

Constraints
- Design System 2.0 tokens only. Dark: base #0E0F11, surface #16181B, raised #1E2125, hair #2A2E33, tx #ECEDEE, tx2 #9BA1A6, muted #6B7177, ok #4FB477, warn #E0A93B, danger #E5604D, accent #D97757 (Codex teal #3FB5AC). Light: base #FAF9F7, surface #FFFFFF, raised #F1EFEB, hair #E4E1DB, tx #1C1D1F, tx2 #5B6066, muted #878C92, ok #2E9E5B, warn #B07D1C, danger #C53D2B, accent #C15F3C (teal #1C8B82). Phone stream: 16dp side padding, 10dp between rows; desktop: 18dp between rows.
- The live region is exactly one line at every text scale (ellipsis, never wraps), and its height is the same in every state of scene B.
- Text next to a dot, chip or icon on one row must be optically centred.
- The header toggle keeps a ≥44pt touch target. Screen readers hear one summary for the block and are not spammed per step (announce only a failure or waiting for approval).
- Don't change prose, user turns, sub-agent / workflow / plan / question cards, the chat header, the pinned state line or the composer.
- Output a new file "Tool Process Live v1.dc.html" (helpers prefixed ToolProcessLiveV1). Don't modify other project files. Add a compact spec for the recommended direction: anatomy, sizes, paddings, colours (dark + light), each state's content, a motion table (what changes, duration, easing) and the run-end transition.
```
