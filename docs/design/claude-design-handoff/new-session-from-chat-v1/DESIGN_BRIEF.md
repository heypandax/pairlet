# New Session From Chat v1 — 设计 brief

日期：2026-10-05（本机时区）。来源：维护者提出「在会话界面里增加新开会话的便捷方式」，并指出会话界面是关键界面，要求先出设计。下面是投递到 Claude Design 的原文，未做删改；设计结果与采用情况见 [README](README.md)。

写 brief 时先核对了现状：手机上新开会话只能先退出聊天（会话列表底部的「＋ New session」停靠按钮，或项目页悬浮「+」打开的 Fast Start 输入弹层）；聊天内的会话切换器只在既有会话之间切换、没有其他会话时整个消失；「⋯」快捷菜单里没有新建项，只有会清空当前会话上下文的红色「Clear conversation」；桌面端已有 ⌘N、侧栏「New session」行和每个项目行的「+」。brief 把聊天主路径（读、回答、审批、发送）写成硬约束，给出允许与禁止的入口位置清单，由设计推荐一个一键的主入口，并在「打开预填当前项目与智能体的 Fast Start 弹层」和「直接开一个空会话落地」两种既有行为之间做推荐。

```text
New board: "New Session From Chat v1" — a convenient way to start a NEW session from inside the chat screen on the phone. Create NEW files only: the board `New Session From Chat v1.dc.html` and, if you need a live component, files prefixed `NewSessionFromChat`. Reuse this project's existing chat device (Chat Rhythm v1 / ChatDeviceV2 with ChatComposerV2), the Fast Start composer sheet (FastStartDevice, from "Fast Start Direction v1") and the Cross-project Session Switcher sheet for the surfaces around it; do not redraw those screens and do not modify any existing file. Use the Design System 2.0 tokens, type roles, sheets and state blocks already in this project; do not restate them.

CONTEXT
Pairlet is a phone app that drives the AI coding agent (Claude Code, Codex and others) running on the user's own computer. The chat screen is the product's core surface and the maintainer calls it critical: it is where people read what the agent did, answer its questions, approve tool calls and send the next prompt. That is the main path, and this board must leave it intact.

What the chat screen is today (phone; Chat Rhythm v1 is the current master, 402 × 874):
- Header: Back (48 pt target) · session title (flexible, wraps up to 3 lines) · ONE trailing glyph "⋯" (48 pt target) that opens Quick actions. Under the title, one collapsed context line such as "Claude · Ask each step · MacBook · pairlet" with a disclosure chevron; expanded, it floats a panel over the transcript with the full path and "Session info".
- Optional pinned rows under that: a lineage banner after a rewind/fork, a voice-memo delivery notice, and the ONE pinned state block (Approval / Answer / Failure). A question card can dock above the composer; a "Demo mode" banner can sit at the very top.
- Transcript: source rows with a whole-turn copy, user turns in a neutral container, tool-process folds.
- Composer: one container (surface fill, hairline, 16 pt radius): the text field, then a tool lane — attach "+" · model chip · session-stack chip · context gauge … and Stop / Send at the trailing end. Send is the only accent-filled control on the screen. At 320 pt the lane wraps.
- The session-stack chip is a stack glyph + the count of OTHER sessions (an attention dot when one of them wants you). It opens the switcher sheet: Current → Active → Recent → "All projects". It is ABSENT when there is no other session, and it only switches between existing sessions.
- Quick actions ("⋯") is one grouped list titled "Quick actions": SESSION SETTINGS (Model, Effort, Thinking, Fast mode, Mode), SESSION TOOLS (Terminal, Files, Git, Help), CONTEXT (Compact, Simplify), then — set apart under its own rule — a red two-tap "Clear conversation" that wipes THIS session's context. "⋯" is hidden while the user is only observing a session that runs in a terminal.

How a new session is started today: only by leaving the chat. Back → the project's sessions list, whose bottom dock has a hairline "＋ New session" button (starts at once under the saved defaults) beside a defaults chip. Or Back → Back → the projects page, whose floating "+" opens the Fast Start composer sheet: a multi-line prompt, a Project chip and an Agent chip (prefilled with the most recent project and the default agent), Send → you land in the new chat with that first prompt already streaming; dismissing keeps the draft; a failure re-opens the sheet with the draft and one inline line. The desktop app already has ⌘N, a "New session" row at the top of its sidebar and a "+" on every project row; the phone has nothing inside the chat.

WHY
Starting the next piece of work is a frequent move while you are in a chat: a task is done and you want a fresh conversation in the same project with a clean context, or you want to kick something off in another project while this one keeps running. On the phone that costs two or three backward taps and you lose your place. The maintainer asked for a convenient entry inside the chat.

THE RULES (hard)
- The main path — read, answer, approve, send — stays exactly as it is. Nothing that exists moves, shrinks, is renamed or is demoted.
- Allowed placements for the entry: a glyph in the header's trailing group beside "⋯"; a row in the existing Quick actions sheet; a row in the existing session-switcher sheet (if you use it, say what the stack chip does when there is no other session, since today it disappears); a change to the stack chip itself. Choose with care: a glyph beside "⋯" costs the title 48 pt on every line.
- Forbidden: the Send slot or any second accent-filled control; a bar, row or button docked above or inside the composer; a permanent row or banner under the header; anything on message rows; cards or hints injected into the transcript; a floating button over the transcript; a hidden gesture (long-press, swipe) as the ONLY way in.
- Exactly ONE primary entry, one tap. Decide whether a second, deeper path in Quick actions is worth having; if yes, say where among its groups and keep it clearly apart from the red "Clear conversation" (which wipes this session — the opposite of leaving it alone and starting another); if no, say why.

WHAT TO DESIGN

A. The entry (the heart of this board)
Show your recommended placement in the chat chrome in context at 1× plus a 2× detail crop of the header's trailing group, and ONE alternative you considered, with two or three sentences on why the master wins. An icon-only control needs an accessible name; every target is 48 pt (the visible glyph may be smaller).

B. What a tap does
Two behaviours already exist in the product; recommend one and show the other as an alternative:
1. Open the existing Fast Start composer sheet prefilled with THIS chat's project and agent (not the most-recent project): type the first prompt, change either chip if you want, Send → land in the new chat with the prompt streaming. Dismiss keeps the draft. It is the same sheet the projects page uses, so it is learned once.
2. Open a fresh, empty session in this project with this agent at once and land in it with the composer focused; mode and model follow the saved defaults; the first prompt is typed there.
Whichever you choose: the session you leave is left alone — a turn that is still running keeps running in the background, a pending approval stays pending with it, and the switcher's attention dot points back to it. Back from the new session goes to its project's sessions list. Say in one line how "new session" differs from "Clear conversation".

C. States of the entry, as variants of the chat device
1. idle chat (baseline).
2. the current turn is still streaming (Stop visible) — the entry stays usable; show how the sheet or the moment says that this chat keeps running.
3. an approval is pinned (the state block is on screen) — the entry must not compete with the block.
4. a question card is docked above the composer.
5. computer offline / connection lost — the entry is present but disabled, with the reason reachable (existing copy: "The computer is offline; new sessions are unavailable").
6. observing a terminal session ("⋯" is hidden) — say whether the entry shows.
7. the new session failed to open — the existing Fast Start failure line with the draft kept (reuse its strings: "The session never opened. Your prompt is still here.").
8. the landing — the first frame of the new session: its title, an empty transcript or the first prompt streaming, its context line, and the stack chip now counting the session you came from.
Demo mode: say whether the entry shows; do not invent a demo-only flow.

D. Proofs
Light theme of the master. Small phone 360 × 640 with a 3-line title so the title still has room beside the trailing group. 200% text size. A tablet two-pane frame where the sessions list with its own "＋ New session" dock sits beside the chat — say whether the chat-side entry stays there for consistency or yields to avoid the duplicate. Simplified Chinese frames for the master, the sheet and every state that carries copy.

E. Copy
Final English and Simplified Chinese for every new label, accessible name, state line and note. Reuse strings that exist ("New session" / "新会话", "New session here", the Fast Start sheet's strings). Tone: calm, short, no exclamation marks.

CONSTRAINTS
- One accent, existing tokens only. No new colours, no counting badges, no "NEW" tag, no onboarding tooltip or coach mark.
- Nothing animates except what already does (sheet rise, chevron turn).
- Out of scope, do not design: the projects page, the sessions list, the internals of the Fast Start sheet (its chips and pickers), forking or rewinding, clearing the current session, the desktop app, prompt templates or suggestions, choosing mode or model in the quick path.

THE PROTOTYPE MUST ACTUALLY DO
1. From the idle chat, use the entry → (your recommended behaviour) → arrive in the new session; Back → that project's sessions list.
2. From a streaming chat, use the entry → arrive in the new session → open the switcher → the previous session is listed as Running, with the attention dot once it asks for something.
3. Trigger the offline state and show the disabled entry with its reason.
4. Trigger the failure state and show the draft kept.
5. Open "⋯" to show where the deep path sits, or that it is absent.
Put the state controls outside the phone frame.

OUTPUT
One board. At the top: the placement and behaviour recommendation and why. Then the entry in context (1× and the 2× crop), the alternative placement, the tap behaviour and its alternative, the eight states, the proofs, the tablet frame, the Chinese frames. At the bottom a short "what engineering needs" list: every new string (English and Chinese); every action with a stable snake_case name for analytics (for example new_session_entry with its source — header | switcher | quick_actions — and new_session_result with opened | timeout | refused); the sizes and offsets of the entry; and anything that has to change outside the UI (what the Fast Start sheet must accept to open prefilled with a given project and agent; how the stack chip's count-zero rule changes if you touched it).
```
