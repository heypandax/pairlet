# First Run · Send to Computer v1 — 设计 brief

日期：2026-10-04（本机时区）。来源：#342 的方向（降低「换到电脑上安装」这一跳的成本；演示结束后给出明确的下一步），用户要求先出设计再实现。下面是投递到 Claude Design 的原文，未做删改；设计结果与采用情况见 [README](README.md)。

投递时只给了方向，没有带任何用户行为数据或比例。

```text
New board: "First Run · Send to Computer v1" — rework the first-run "Connect your computer" screen and the end of the demo so that people actually get to their computer. Create NEW files only: the board `First Run · Send to Computer v1.dc.html` and a shared live component `FirstRunDevice v2.dc.html` (start from a copy of `FirstRunDevice.dc.html`). Do not modify `FirstRunDevice.dc.html`, `First Run · Connect + Pair v1.dc.html` or any other existing file. Use this project's existing design system (Design System 2.0 tokens, type roles, the Entry Flow components and state blocks); do not restate it.

CONTEXT
Pairlet is a phone app that drives the AI coding agent running on the user's OWN computer (Claude Code, Codex and others) over an end-to-end encrypted relay. Nothing works until a small background program is installed on that computer and the phone is paired with it: running `pairlet pair` on the computer prints a six-digit code and a QR, and the phone scans it.

Today the first screen of a first run is "Connect your computer" (FirstRunDevice, screen=connect): wordmark, title, one line of explanation; STEP 1 "Install Pairlet on the computer" with an OS segment (macOS / Windows / Linux), a Script / Package manager switch and a copyable command block, plus the prerequisite line "Needs the Claude Code CLI on that computer · What's that?" (on Windows it becomes a marked ATTENTION note); STEP 2 "Pair" ("Run pairlet pair on the computer — it shows a six-digit code and a QR."); the one filled button "I'm ready — pair now"; two quiet routes, "Already installed? Enter code directly" and "Explore the demo instead · No computer needed"; then the trust block "HOW IT STAYS PRIVATE" (open source, end-to-end encrypted, what is sent) and "Full setup guide · Support". When scrolled, the head collapses and the filled button docks to the bottom (compact state).

WHAT WE LEARNED
Pairing is not the problem: people who start pairing almost always finish. People stall BEFORE they start. The real cost is the hop from the phone to the computer. The screen shows a long shell command on the one device where it cannot be run; "copy" puts it on the phone's clipboard, and the user still has to carry it to another machine. Many are not at their computer when they first open the app, so the screen asks for something they cannot do right now and gives them nothing to do instead. A second group opens the demo, looks around and leaves: the demo shows a passive "Demo mode · sample data" banner and never offers a next step.

GOALS
1. Make "get this to my computer" the obvious first move of the first-run screen, in one tap, and useful even when the computer is not in front of the user.
2. Give the demo an explicit, calm next step that leads to the same move.

WHAT TO DESIGN

A. The first-run screen, restructured around the hop
- The first move is "Send the setup link to my computer". The link is one short URL that is the same for everyone and easy to type: `pairlet.org/start` (working name). The action opens the OS share sheet (AirDrop to a Mac, Messages / Mail / Notes / a chat with yourself) with one plain line of text plus the link. Right beside it the same URL is shown large enough to simply read off and type on the computer ("or type it on your computer"), with a copy affordance.
- The raw install command is no longer the first thing on the screen, but it must stay reachable in one tap for people who want to read what will run before they run it (this audience is security-minded): a "Show the command" disclosure that reveals today's OS segment, Script / Package manager switch, command block and prerequisite line unchanged.
- Decide the hierarchy and layout yourself. Show your recommended layout as the master, and ONE alternative you considered (first state only), with two or three sentences on why the master wins.
- States, as variants of the live component:
  1. first — first visit, nothing sent yet.
  2. sent — the share sheet was just used (or the link was copied). Say what happens next on the computer as three short steps (open the link; run the command it shows; `pairlet pair` shows a QR), and the filled button becomes "I'm at my computer — scan the code". We only know the share sheet was used, not that anything arrived: the copy must not claim delivery.
  3. return — the user opens this screen again on a later visit (the app knows it is not the first). Lead with pairing ("Back at your computer?") and keep "Send the link again" as a quiet route.
  4. after-demo — the user has just left the demo. Same screen, with one line that acknowledges it ("You've seen how it works. Now connect your own computer.").
  5. command-open — the disclosure expanded, including the Windows ATTENTION note.
  6. compact — collapsed head with the docked filled button (existing behaviour, keep it).
- Keep, possibly moved but not removed: "Already installed? Enter code directly", "Explore the demo instead · No computer needed", the trust block, "Full setup guide · Support". The pair screen (screen=pair and its six states) is out of scope and must not change.

B. The page behind the link, as seen on the computer
`pairlet.org/start` in a desktop browser (1280×800, no scrolling needed) and the same page at 390 wide in case it is opened on the phone. One job: detect the OS (with tabs to switch), show the install command with a Copy button and the package-manager alternative, then "2. Run pairlet pair" and "3. Scan the QR with the Pairlet app on your phone". One quiet trust line with a GitHub link. Dark, calm, same tokens as the app; this is a utility page, not a marketing page.

C. The demo's next step
The demo is the real Projects → Sessions → Chat surfaces running on built-in sample data. Reuse the existing chat and session device components in this project; do not redraw them.
  1. The banner "Demo mode · sample data" gains one quiet action, "Set up for real", which opens a bottom sheet: the filled "Send the setup link to my computer", then "I'm at my computer — pair now", then "Keep exploring".
  2. One next-step moment at the natural end of the walkthrough: after the user has sent a prompt in the demo and the sample turn has finished, an inline card appears in the chat stream (not a modal, not over the composer): "That was sample data. On your own code it works the same way.", with the same three actions. It appears once per walkthrough and can be dismissed.
  3. Leaving the demo lands on the first-run screen in its after-demo state (A.4).

D. Copy
Final English and Simplified Chinese strings for every new label, button, line and the share message itself. Show English frames as the master and Chinese frames for every master state, each string fitting its container at the widths shown. Tone: calm, precise, short sentences, no exclamation marks, no marketing words. Do not introduce any AI vendor or product name that is not already on the current screen.

CONSTRAINTS
- The demo must stay fully explorable with no computer at all (App Store review walks it that way). Every next-step element is dismissible and never blocks or interrupts an action in progress.
- No account, no email field, no phone number, no reminder notifications, no backend. The only transports are the OS share sheet and the user typing the short URL. The link carries no identifier; say so in one quiet line where it helps trust.
- One filled button per screen state, as in Entry Flow. Everything else is a quiet route.
- The command text and the Windows CLI sentence in the mock are placeholders (label them as such on the board); engineering substitutes the shipped ones. `pairlet pair` is the real command name.
- Phone frame 390×844, dark as master. Also: one light proof frame of the master state, one small-phone proof (360×640) showing the first move and the filled button above the fold without scrolling, and one tablet frame with the same single column centred.
- Nothing illustrated: no device drawings, no mascots, no arrows between a phone and a laptop. The copy and the hierarchy carry it, as on the current screen.

THE PROTOTYPE MUST ACTUALLY DO
1. first → tap "Send the setup link to my computer" → a simulated share sheet → sent state → "I'm at my computer — scan the code" → the existing pair screen.
2. first → "Show the command" → switch OS, switch Script / Package manager, copy.
3. Demo: banner action → bottom sheet → "Keep exploring"; send a demo prompt → the next-step card appears once → dismiss → it does not come back; leave the demo → first-run screen in the after-demo state.
Put the state controls outside the phone frame.

OUTPUT
One board. At the top: the recommendation and why. Then the master frames for states 1–6, the alternative layout, the demo frames (banner action, bottom sheet, next-step card), the `pairlet.org/start` page (desktop and phone), the light proof, the small-phone proof, the tablet frame and the Chinese frames. At the bottom, a short "what engineering needs" list: every new string (English and Chinese), every new action with a stable snake_case name for analytics (for example send_link, copy_link, show_command, demo_next_step shown / tapped / dismissed), and what has to be built outside the screen itself (the pairlet.org/start page, a text share call).
```
