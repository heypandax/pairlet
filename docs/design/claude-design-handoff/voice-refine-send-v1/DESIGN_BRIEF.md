# Voice Refine Send v1 — 设计 brief

日期：2026-10-05（本机时区）。来源：维护者反馈语音输入常把几个词识别成同音错词，且「✓ → 落输入框 → 再点发送」要三步；希望说完后由当前默认 Agent 的轻量模型校对后直接发送，并参考 Codex App（✓ 直接发送、关闭把内容放进输入框）。技术方案与实测数据见 [VOICE-INPUT-REFINE-SEND](../../VOICE-INPUT-REFINE-SEND.md)；设计结果与采用情况见 [README](README.md)。

写 brief 前核对了现状：录音条（✕ · 波形/计时胶囊 · ✓）替换输入框；iOS 有实时听写预览，Android 走电脑 Whisper；✓ 之后文本追加到草稿由用户再发（#221）；失败走 danger ribbon + 重试，空转写走 2.5 s 通知行；设置里只有 iOS 的 Whisper 开关。brief 把聊天主路径与双层 composer 写成硬约束，只允许录音条在自己的槽内变化，并给出工程事实：校对在机主电脑上用其自己的 Agent 一次性无工具调用完成、返回替换列表并校验、实测等待约 3–8 s、预算 8 s、无校对器时不自动发送。下面是投递到 Claude Design 的原文，未做删改。

```text
New board: "Voice Refine Send v1" — what happens on the phone when you finish dictating a prompt: the check mark now SENDS (after a quick correction pass on the computer), and the leading control hands the text to the composer for editing by hand. Create NEW files only: the board `Voice Refine Send v1.dc.html` and, if you need a live component, files prefixed `VoiceRefineSend`. Reuse this project's existing chat device (Chat Rhythm v1 / ChatDeviceV2 with ChatComposerV2) and the recording bar and composer states from "Defaults + Voice + Results Master v1" (DVRDevice) as the surfaces around it; do not redraw those screens and do not modify any existing file. Use the Design System 2.0 tokens, type roles, sheets and state blocks already in this project; do not restate them.

CONTEXT
Pairlet is a phone app that drives the AI coding agent (Claude Code, Codex and others) running on the user's own computer. The chat screen is the product's core surface: read what the agent did, answer its questions, approve tool calls, send the next prompt. The composer is part of that main path; dictation is one way of filling it. This board changes only what the dictation controls do once you stop speaking and the few seconds that follow. Everything else stays exactly as it is.

What dictation is today (phone, production truth):
- Idle: the Mic sits inside the full-width text field as its trailing action (48 pt target) when the field is empty, and it stays reachable there beside a staged draft; Send lives on the tool lane below the field and is the only accent-filled control on the screen.
- Recording: the recording bar REPLACES the field (220 ms morph). Left to right: "✕ Cancel recording" (48 pt, muted glyph) · a pill (raised fill, hairline, 12 pt radius, min 44 pt tall) holding a pulsing red dot, a live waveform and a monospace m:ss timer · "✓ Done" (accent-filled round button, 48 pt). On iOS a live transcript field sits above the bar (committed words in primary text, the volatile tail muted, a blinking caret); Android records without a live transcript. If an agent turn is still streaming, a separate row below keeps Stop reachable through the whole capture.
- Transcribing: the pill swaps the dot and waveform for a spinner and the written state "Transcribing…" (or "Uploading 1 of 1" while the audio is still leaving the phone); the timer freezes; ✕ still cancels. Whisper on the user's own computer answers in about one second; Apple's on-device dictation on iOS answers in well under a second.
- After that, today: the text is APPENDED to the composer draft, the caret lands at the end, the keyboard comes up, and the user reads it, fixes it and taps Send. This was a deliberate decision (issue #221): recognition makes mistakes, and sending an unreviewed transcript wasted a model turn and could fire a wrong instruction.
- Failure: a danger ribbon above the field with the reason, full width, uncapped wrap; the field's trailing action becomes "Retry voice input". A capture with no speech shows the small notice line "Didn't catch any speech" above the composer for 2.5 s.
- Settings › General › VOICE INPUT exists only on iOS today: one toggle "Transcribe on the computer (Whisper)" with its sub line.

What the maintainer has decided (engineering facts you can rely on):
- A correction pass. Once the transcript exists, the computer runs a one-shot, tool-less call on the user's own coding agent at its fastest tier (the session's agent — Claude or Codex today) and gets back a short list of substitutions: homophones ("用功体验" → "用户体验"), mis-heard technical terms, mistranscribed English ("cloud" → "Claude", "edit" → "effort"). The computer validates every substitution (it must match the raw text exactly once; total change is bounded) and the corrected text is what gets sent. Nothing is paraphrased or added. Measured on the maintainer's Mac: Claude answers in about 4–6 s after the transcript; Codex in about 3–10 s once its server is kept warm. So between "I'm done" and "it is sent" there is a wait of roughly 3–8 s. The phone gives the pass 8 s; past that, the raw text goes to the composer as today, with a one-line note.
- ✓ now means finish and SEND. Codex's own app does this on its check mark.
- The leading control now means finish and hand the text to the composer to edit by hand — what Codex's app does on close, and what Apple's dictation does with its keyboard glyph. Nothing is lost on this path; discarding is deleting the text in the composer. Decide whether that is enough or whether a separate, non-destructive-by-default way to discard is needed; whatever you choose, never place a destructive control in the slot where ✓ just was (a second tap on ✓ must not discard).
- When no corrector is available (the computer runs an older version, the agent's CLI is missing or signed out, or the session's agent has no one-shot mode) ✓ cannot send: the text goes to the composer as today, with a calm one-line reason.
- One setting. Settings › General › VOICE INPUT gets a row "After dictation" with two values: "Correct and send" (default) and "Put in the composer". It applies to iOS and Android; the existing Whisper toggle stays on iOS.

WHY
Speaking is how the maintainer writes most prompts on the phone. Today every dictation costs a read-through, a few corrections and a second tap on Send; the errors are almost always a handful of homophones and mis-heard terms, which a small model fixes reliably. The maintainer wants the check mark to just send, with the correction making that safe, and the response time kept visibly short.

THE RULES (hard)
- The main path — read, answer, approve, send — stays exactly as it is. The two-layer composer, the Send slot, the Stop row, the Mic placement, the transcript rows and the header do not move, shrink, rename or change meaning.
- The recording bar may be redesigned, but only inside its own slot: it replaces the field while a capture is in progress and nothing else. No new rows, banners or bars above or below the composer beyond the two that exist (the 12 sp notice line and the ribbon slot). No cards or hints in the transcript. No marker, badge or label on a sent message saying it was dictated or corrected.
- Exactly one accent-filled control on screen at a time. Every target 48 pt (44 pt hard floor). Written state is authoritative; colour and motion are supplementary.
- Both engines must work: iOS with the live transcript above the bar, Android/desktop-whisper without it (the "Uploading 1 of 1" line can appear on that path).
- No confirmation dialogs, no toasts or snackbars other than the existing notice line, no undo-send (the product cannot unsend a prompt), no timers the user has to beat.

WHAT TO DESIGN

A. The bar's controls after this change (the heart of this board)
Show the recording state with the new meanings: the leading control (glyph, accessible name, visible label if any) and the trailing ✓ that now sends. Show it twice: Android (no live transcript) and iOS (live transcript above the bar). Give ONE alternative for the leading control that you considered and say in two or three sentences why the master wins. Answer the discard question explicitly.

B. Finishing: the wait between ✓ and sent
This is new. Recommend and show how the 3–8 s read. Our starting point, improve on it: as soon as the transcript exists the bar shows the raw text so the user can read while the correction runs; the written state goes "Transcribing…" → "Correcting · Claude" (name the agent) → sent; when the substitutions land they swap in with a brief highlight on the changed spans (nothing else moves), then the bar collapses and the message appears in the transcript as an ordinary user turn. Say what the trailing slot shows during the wait (a non-interactive progress treatment is fine) and what the leading control means now ("edit instead": the text — corrected if it has arrived, raw if not — goes to the composer and nothing is sent). Decide whether the 2.5 s notice line should confirm the send ("Sent · 3 corrections") or stay silent, and why. Show the sequence as a strip of frames with the time since ✓ written under each.

C. The other endings, as variants of the chat device
1. The pass takes longer than 8 s: the raw text goes to the composer, caret at the end, keyboard up, one calm line in the notice slot (e.g. "Correction took too long — check and send"). Say what happens if the result arrives a moment later while the user has not typed yet (we propose: it swaps in with the same highlight; if they have typed, it is dropped).
2. No corrector on the computer: the text goes to the composer with one calm line naming the reason; the Send slot behaves as today.
3. The corrector answered but its substitutions failed validation: same as 2 with a neutral line — this is not a failure of recognition.
4. Transcription itself failed: the existing danger ribbon and "Retry voice input" — unchanged, shown for completeness.
5. Nothing was heard: the existing "Didn't catch any speech" line — unchanged.
6. An agent turn is streaming while you dictate: the Stop row stays through recording, transcribing and correcting; the send then queues into the running turn, as a typed send does today.
7. The connection drops during the wait: the text is on the phone, so it goes to the composer with the existing connection copy; nothing is sent twice.
8. The setting is "Put in the composer": ✓ and the leading control — say whether they now mean the same thing and how the bar reads, or whether ✓ keeps a distinct meaning.

D. Settings
The VOICE INPUT section of Settings › General with the new "After dictation" row and its two values, each with one sub line that says what the check mark will do and that the correction runs on the user's own computer with their own agent. Show it on iOS (with the Whisper toggle) and on Android (without).

E. Proofs
Light theme master at 402 × 874. 320 pt wide recording and finishing frames (the bar must not clip, shrink below 44 pt or push a control off screen). 200% text. Simplified Chinese frames for every state that carries copy. A long dictation (eight lines of text) in the finishing state so the preview's height rule is shown.

F. Copy
Final English and Simplified Chinese for every new label, accessible name, written state, notice line and settings string. Reuse what exists: "Dictate / 语音输入", "Done / 完成", "Cancel recording / 取消录音", "Transcribing… / 转写中…", "Didn't catch any speech / 没有听到语音", "Retry voice input / 重试语音转写", "Send / 发送", "Stop / 停止". Tone: calm, short, no exclamation marks. Name the agent with its product name (Claude, Codex).

CONSTRAINTS
- One accent, existing tokens only. No new colours; the highlight for changed spans must come from the existing tokens.
- Motion: only the existing 220 ms morph, the spinner and the one highlight fade. Nothing bounces.
- Out of scope, do not design: the voice memo feature, the desktop composer, where the Mic sits, the model picker, the transcript's message rendering, how the recording bar looks before the user stops (keep the current dot, waveform and timer), any tutorial or first-use coaching.

THE PROTOTYPE MUST ACTUALLY DO
1. Record → ✓ → finishing state with the raw preview → the substitutions swap in with the highlight → the bar collapses and the message appears in the transcript.
2. Record → leading control → the text lands in the composer, caret at the end, keyboard up; nothing is sent.
3. Record → ✓ → during the wait tap the leading control → the text (corrected if it has arrived, raw if not) lands in the composer; nothing is sent.
4. Trigger the over-8-s ending and show the late result swapping in while the composer is untouched.
5. Trigger the no-corrector ending.
6. Switch the iOS live-transcript variant on and off.
7. Flip the setting and show what ✓ does under each value.
Put the state controls outside the phone frame, including a "correction time" control with 2 s, 5 s and 9 s so the wait can be felt.

OUTPUT
One board. At the top: the recommendation — the two controls and their names, how the wait reads, the discard answer — and why, in a short paragraph. Then A, the finishing strip (B), the eight endings (C), Settings (D), the proofs and the Chinese frames (E). At the bottom a short "what engineering needs" list: every new string (English and Chinese) with a stable snake_case key; every action with a stable snake_case name for analytics (for example voice_done with action = send | compose, voice_refine with result = applied | none | invalid | timeout | unavailable | cancelled and agent = claude | codex | none); the geometry of the bar in each state (heights, the preview's max height and its scroll rule, highlight style); and anything that has to change outside the UI (what the phone must know from the computer to show the agent's name and whether a corrector is available).
```
