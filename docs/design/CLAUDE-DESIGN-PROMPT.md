# cc-pocket —— claude.ai/design 提示词

用法：打开 https://claude.ai/design，先粘贴「① 总体 + 风格」开场（它会先出一屏，通常是 Chat）；满意后用「② 各屏」逐屏补齐。生成后把截图存到本仓库 `docs/design/claude-version/`（命名如 `cd-01-chat.png`）或直接贴回对话给我，我来和 Stitch 版做并排对比。

> 这套提示词与喂给 Stitch 的是**同一设计系统**（Calm Terminal Companion），这样两边可比的是「工具的设计能力」，而不是「输入差异」。完整规格见 [`UI-DESIGN.md`](./UI-DESIGN.md)。

---

## ① 总体 + 风格（先粘这段开场）

```
Design a dark, developer-native mobile app called "cc-pocket" — a phone remote control for Claude Code running on a developer's computer. Core jobs: resume or start a Claude session, switch the working directory, and approve/deny Claude's tool-permission prompts.

Mood: calm, focused, like a premium IDE or terminal at night, warmed by a single ember of terracotta. Restraint over decoration; high information density with breathing room. NOT a playful consumer app.

DESIGN SYSTEM (dark theme):
- Base #0E0F11, surface (cards/rows/composer) #16181B, raised (sheets) #1E2125, hairline border #2A2E33.
- Text #ECEDEE, secondary #9BA1A6, muted #6B7177.
- Accent terracotta #D97757 — used SPARINGLY for the one primary action per screen, active states, and the streaming caret. Semantic: success #4FB477, warning #E0A93B, danger #E5604D.
- Typography: Inter for UI; JetBrains Mono ONLY for file paths, session ids, git branches, code blocks, token counts, and terminal commands (this is what makes it feel developer-native).
- Elevation via tonal stacking + 1px hairline borders, NOT drop shadows. Radius: 12 cards / 20 bottom-sheet top / 999 pills. 4pt spacing grid, 16pt screen margins, tap targets ≥44pt.

It is a 7-screen app: Chat, Permission sheet, Session list, Directory picker, Pairing, Choose computer, Settings. Start by designing the Chat screen.
```

---

## ② 各屏（逐屏粘贴，与 Stitch 同一批）

**Chat**
```
Chat — a developer's live conversation with Claude Code. Top: a slim connection bar with back chevron, session title "Refactor auth module", a small "default" permission-mode pill, and an overflow menu; under it a monospace line "Lidapeng-MacBook · ~/proj/app/cc-pocket" with a green online dot. Body: a user message shown full-width with a small "You" label (not a bubble): "add a unit test for the stream parser"; a collapsed "Thinking" row; an assistant markdown reply with a short paragraph, a 2-item bullet list, and a dark inset code block (language "kotlin", copy icon, monospace); a tool-event row: terminal icon + "Bash" + monospace "gradle :protocol:test" + a spinner; the assistant text is still streaming, ending in a blinking terracotta caret; a faint monospace token line "↑1.2k ↓340". A floating "↓ Jump to latest" pill. Bottom: a composer with "+" attach, a "Message Claude…" input, and a send button showing a square STOP glyph while generating.
```

**Permission sheet**
```
A permission-request bottom sheet over a dimmed Chat screen. The sheet (raised #1E2125, 20px top radius, hairline border): a grab handle; a shield icon + "Claude needs permission"; a large tool name "Run command · Bash"; an inset dark monospace card showing "rm -rf ./build && ./gradlew clean" with a "▾ expand" affordance; a monospace line "~/proj/app/cc-pocket · ⑂ main"; a circular countdown ring "0:23" in terracotta; a "Remember for this session" checkbox; two big buttons — "Deny" (danger outline, left) and "Allow" (filled terracotta, right). Reads in under 2 seconds.
```

**Session list**
```
Session list for a working directory. Top: connection bar "Lidapeng-MacBook · ~/proj/app/cc-pocket · ⑂ main" (monospace) with a green dot. A prominent "＋ New session" row in terracotta (the most eye-catching element). Then session cards, newest first; each card: bold title, one-line first-prompt preview (secondary), and a monospace metadata row "💬 12 · ⑂ main · 2h ago". Cards: "Refactor auth module / add a unit test for the stream parser / 💬 12 · ⑂ main · 2h ago"; "Fix stream parser test / the parser drops the last token on EOF / 💬 6 · ⑂ fix/parser · 5h ago"; "Add relay websocket client / scaffold the Ktor WS client with reconnect / 💬 23 · ⑂ feat/relay · yesterday"; "Wire up pairing flow / generate a 6-digit pairing code on the daemon / 💬 4 · ⑂ main · 2d ago".
```

**Directory picker**
```
Directory picker (choose the working directory Claude runs in). Top: connection bar "Lidapeng-MacBook" + green dot. A "RECENTS" section: rows of folder icon + monospace path + terracotta "N sessions" pill + chevron — "~/proj/app/cc-pocket · 3 sessions", "~/proj/app/cc-dashboard · 8 sessions", "~/work/api-server · 1 session". A "BROWSE" section: a monospace breadcrumb "~ / proj / app" with tappable segments, then subdirectory rows (folder + monospace name + optional "N sessions" pill + chevron): "cc-pocket · 3 sessions", "cc-dashboard · 8 sessions", "analyse", "ReleaseAdmin · 2 sessions", "nanobanana". Bottom: a full-width "Use this directory" button (filled terracotta).
```

**Pairing**
```
Pairing / connect your computer. Title "Connect your computer" + subtitle "Pair this phone with the cc-pocket daemon on your computer." A large QR camera viewfinder with animated terracotta corner brackets. A divider "or enter the pairing code". A 6-digit segmented code input (monospace, one box per digit), partially filled "4 8 1 _ _ _" with a terracotta blinking caret in the 4th box. A helper line "Run  cc-pocket pair  on your computer to get a code." (monospace command). A primary "Connect" button (filled terracotta).
```

**Choose computer**
```
Choose a computer (daemon picker). Title "Choose a computer" + subtitle "Pick which computer to drive." Computer cards (surface #16181B, hairline border): OS glyph + hostname (headline) + online/offline dot with label + last-active time (monospace) + a one-line current working directory (monospace). Cards: "Lidapeng-MacBook" (Apple, green "online · active now", ~/proj/app/cc-pocket); "devbox-linux" (Linux, green "online · 3m ago", ~/src/relay); "win-desktop" (Windows, grey "offline · 2d ago", ~/code/api — this card dimmed).
```

**Settings**
```
Settings — a grouped list. Group "DEFAULT PERMISSION MODE": six selectable rows with a radio, "default" selected, each with a one-line description: default, acceptEdits, auto (terracotta lightning glyph), plan, dontAsk, bypass (warning triangle + amber tint). Group "PAIRED DEVICES": "This device · iPhone 15 Pro" (current) and "iPad Air · paired 3d ago" with a red "Revoke" button. Group "APPEARANCE": a segmented control System / Dark / Light (Dark selected, terracotta). Group "ABOUT": Version "0.1.0" (monospace), License "MIT", Daemon "ws://192.168.1.100:8765" (monospace).
```

---

## 已落地

- 用上面这份 prompt 在 **claude.ai/design** 生成了 7 屏，经 **Handoff to Code** 导出到 [`claude-design-handoff/`](./claude-design-handoff/)（各屏 `.html/.jsx` + 设计对话）——项目保留的设计版本。
- 与 Stitch 的历史选型对比已归档到 Obsidian `~/Desktop/Brain/20_Projects/cc-pocket-设计工具评估/`。
