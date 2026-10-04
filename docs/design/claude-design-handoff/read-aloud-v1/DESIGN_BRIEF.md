# Read Aloud v1 — 设计 brief

日期：2026-10-04（本机时区）。来源：用户试用 iOS 朗读后的反馈——离开界面就停、没有倍率、只在一个界面可用，希望做成对其他文本也适用的通用组件，并要求先补设计。下面是投递到 Claude Design 的原文，未做删改；设计结果与采用情况见 [README](README.md)。

投递前两个待定点按建议值写入：锁屏和后台继续朗读；先做 iOS，平板和桌面只给出同一播放条的摆放位置。

```text
New board: "Read Aloud v1" — turn read-aloud from a button on one screen into a small app-wide player. Create NEW files only: the board `Read Aloud v1.dc.html` and a shared live component `ReadAloudPhone.dc.html`. Reuse the existing file-viewer, chat and voice-memo device components in this project for the surfaces around it; do not redraw those screens and do not modify any existing file. Use this project's existing design system (Design System 2.0 tokens, type roles, state blocks, sheets); do not restate it.

CONTEXT
Pairlet is a phone app that drives the AI coding agent running on the user's own computer. People read a lot in it: Markdown documents the agent wrote, long agent replies in chat, sub-agent reports, voice-memo transcripts. A first read-aloud exists today: in the file viewer's header, next to "copy path" and "share", a speaker icon reads the open Markdown document with the system voice (on device, nothing is sent anywhere). Markup is stripped to prose; each code block is replaced by one spoken line, "Code block skipped."

WHAT IS WRONG WITH IT (from the person who asked for it)
- It stops the moment you leave the viewer. Listening is exactly what you do while doing something else.
- There is no speed control.
- It only exists on one screen and only for .md files. The same thing should read any long text in the app.

GOAL
One player, one thing playing at a time, reachable from every place that shows long text, and still there when you navigate away. Calm and small: this is a utility, not a media app.

WHAT TO DESIGN

A. The mini player (the heart of this board)
A compact bar that is present on every screen while something is playing or paused, and absent otherwise.
- One line: what is being read (document name, or "Reply · <session title>"), a paragraph counter such as "4 / 31", play / pause, a speed chip, and close (stop and dismiss).
- The speed chip shows the current speed ("1×") and steps through 0.75×, 1×, 1.25×, 1.5×, 2× on tap. The choice is remembered.
- Tapping the title goes back to the source, scrolled to the paragraph being read.
- Decide where it lives on the phone. It must never cover the chat composer, the question card docked above the composer, or the bottom safe area, and it must coexist with the pinned state block and the "Demo mode" banner at the top. Show your recommended placement as the master and ONE alternative you considered, with two or three sentences on why the master wins.
- Show it over these existing surfaces: the file viewer (its own source), the chat screen with the composer, the chat screen with a docked question card, the sessions list, and Settings.

B. The expanded player
Tapping the bar away from the title opens a bottom sheet "Now reading": title and source, previous paragraph / play-pause / next paragraph, the paragraph counter as a simple progress line (not a time scrubber — length is counted in paragraphs), the five speeds as a segmented row, the voice in use ("中文 · 系统语音" / "English · system voice", chosen automatically from the text), and "Open source" to jump back. Nothing else.

C. Entry points
- File viewer header: the existing speaker icon stays, now for Markdown and plain-text files.
- Chat: every agent reply gets a "Read aloud" action. Place it where a reply's existing "copy" action lives (the source row of the turn); it must not add a row to the transcript. Sub-agent report cards and workflow results get the same action in the same place.
- Voice memo result page: "Listen" on the transcript.
- In the document viewer and in a long reply, long-press on a paragraph offers "Read from here".
- Starting a second item while one is playing replaces it; show how the bar makes that swap legible.

D. Following along in the source
While its source is on screen, the paragraph being read is marked (a quiet rule or tint from the existing tokens, not a highlight colour of its own) and the view follows it. If the user scrolls by hand, following stops and a small "Back to reading position" affordance appears; tapping it resumes following.

E. States (as variants of the live component)
1. idle — no bar.
2. playing.
3. paused by the user.
4. interrupted — a phone call or the microphone (dictation / voice memo recording) took the audio; the bar says so and offers "Resume". Reading never restarts by itself after an interruption.
5. finished — the bar says "Finished" with "Replay", then leaves on its own.
6. skipped code — while a code block is being skipped the counter still advances; show how that reads.
7. no voice — the device has no voice for the text's language: the entry shows a one-line explanation and a link to the system voice settings instead of failing silently.
8. unavailable — the platform has no speech engine: the entries are simply not there (show the file viewer header without the icon).

F. Settings
Settings → General gains one row, "Read aloud", opening a short page: default speed; "Say when a code block is skipped" (on by default); a line explaining that reading happens on the device with the system voice, plus a link to the system settings where better voices can be downloaded.

G. Lock screen
Reading continues when the phone is locked or the app is in the background. Show one reference frame of the system lock-screen player as it would appear (title = the document or session name, subtitle "Pairlet"), with play / pause and next paragraph. This is system UI; the frame is only there to fix the metadata we send.

H. Copy
Final English and Simplified Chinese strings for every label, button, state line and the Settings page. English frames are the master; show Chinese frames for the mini player in all states, the expanded player and the Settings page. Tone: calm, short, no exclamation marks.

CONSTRAINTS
- One accent, existing tokens only. No waveform, no equaliser animation, no album-art block, no gradients. The only motion is the play / pause change and the paragraph marker moving.
- The bar is one row high and never taller than the composer's collapsed height. It must work at 200% text size: say what truncates first (the title) and what never does (play / pause, close).
- Every control is a 44 pt target.
- Phone frame 390×844, dark as master, one light proof of the mini player in its playing state, one small-phone proof (360×640) with the bar over the chat composer. Then one tablet frame and one desktop-window frame showing where the same bar goes in those layouts, so the phone design does not have to be redone for them.
- Out of scope, do not design: reading new replies automatically as they arrive, a queue or playlist, voice selection per document, downloading voices inside the app, any waveform or transcript view.

THE PROTOTYPE MUST ACTUALLY DO
1. File viewer → tap the speaker → bar appears, paragraph marker moves → go back to the chat → the bar is still there over the composer → change speed → pause → tap the title → back in the viewer at the paragraph being read.
2. In chat, start "Read aloud" on an agent reply while the document is still playing → the bar swaps to the reply.
3. Open the expanded player → next paragraph twice → change speed → "Open source".
4. Trigger the interrupted state, then "Resume". Trigger finished, then "Replay".
Put the state controls outside the phone frame.

OUTPUT
One board. At the top: the placement recommendation and why. Then the mini player over the five surfaces, the alternative placement, the expanded player, the entry points (viewer header, chat reply, sub-agent report, voice memo, "Read from here"), following along, the eight states, the Settings page, the lock-screen reference, the proofs (light, small phone, 200% text), the tablet and desktop frames, and the Chinese frames. At the bottom, a short "what engineering needs" list: every new string (English and Chinese), every action with a stable snake_case name for analytics (for example read_aloud_start with its source, read_aloud_speed, read_aloud_pause, read_aloud_stop), the sizes and offsets of the bar on each surface, and anything that has to be built outside the UI (keeping audio alive in the background, lock-screen metadata).
```
