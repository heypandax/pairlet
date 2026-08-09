# Chat Master v2 — design brief

## Task and primary problem

Create the missing final, implementation-ready Chat master for cc-pocket Mobile UI 2.0. The current Claude Design project contains a normal Chat frame inside `A Master Core v1`, a 200% proof inside `A Master Proofs v1`, and a separate `Chat Quick Actions Master v1`, but no single current source of truth. Later production fixes to the context disclosure are not represented in those original frames.

The user opening Chat must be able to answer, in order:

1. Which session is this?
2. Does it require action, or is it merely running?
3. What did the user, agent and tools do?
4. What can be sent or controlled now?

This is a consolidation and correction of the confirmed Direction A system, not a new visual direction.

## Product and existing surface

- Product: cc-pocket mobile app, Compose Multiplatform.
- Route: Sessions or Projects → Chat.
- Target: iPhone 17 standard, exactly 402 × 874 pt. Taller devices expand the stream; smaller devices are not a release target for this master.
- Existing foundations: near-black/white palettes, low-container hierarchy, system sans UI, monospace only for commands/paths/technical identifiers, restrained terracotta accent, hairline separation, written state plus shape/color, minimum 48 pt interactive targets.
- Existing design sources in this project:
  - `A Master Core v1.dc.html`, frame 02 Chat.
  - `A Master Proofs v1.dc.html`, frame 05 Chat at 200% type.
  - `Chat Quick Actions Master v1.dc.html` and `QuickActionsDevice.dc.html`.
  - `Approval Protocol Handoff v1.dc.html` for the approval surface only.
- Current production references:
  - `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/App.kt` (`ChatScreen`, composer and overlays).
  - `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/chat/ChatChrome.kt`.
  - `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/chat/ChatStateUi.kt`.
  - `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/SessionSheets.kt` (`QuickActionsSheet`).

Do not create a second brand skin, global tab bar, dashboard, chat bubbles or floating-glass cards.

## Locked information architecture

The phone has five vertical zones:

1. **Navigation header** — back, wrapping session title, top-right overflow.
2. **Context disclosure** — collapsed summary or bounded expanded facts; it is not a second navigation bar.
3. **Pinned state block, when factual** — exactly one lead state selected from Approval required, Answer required, Failure, Running. Omit the whole block when none applies.
4. **Flexible transcript stream** — User, real agent name and Tool turns; ordinary system/status events remain subordinate.
5. **Bottom interaction dock** — optional pinned cards/banners above the two-layer Composer; respects keyboard and safe area.

Only zones 1, 2, 3 and 5 are pinned. The transcript owns the remaining height and scrolls independently. Do not solve collisions by shrinking type or hiding actions.

## Header and context contract

### Navigation row

- Back is a 48 pt target.
- Title leads, wraps up to three lines, then ellipsizes. A long task title must not collide with overflow.
- Overflow is the only top-right action and opens the already confirmed Quick Actions sheet.

### Collapsed context

Show a maximum of two wrapping lines using only facts that really exist. Facts use the separator ` · `. The collapsed row is one 48 pt disclosure target and ends with a drawn chevron, not a typographic up/down arrow.

### Expanded context

- Label: `CONTEXT / 上下文` with an upward disclosure chevron.
- Identity row: `agent · permission mode · model`, omitting the model when it is not known yet.
- Location row: `machine · project folder`; the complete row remains the machine-switch action when a paired machine exists.
- Optional origin row: `via <literal origin>` only when supplied.
- Full working path appears separately, wraps without ellipsis and keeps its own 48 pt copy target.
- `Session info / 会话信息` is pinned below the internally scrolling facts/path body. It must never require an invisible inner scroll to discover.
- Ordinary content at 100% type must fit without inner scrolling on 402 × 874. At 200% type or with a deep path, only the facts/path body may scroll; Session info remains fully visible.
- Expanded context remains bounded and must not remove the usable transcript.

No branch, timestamp, duration, repository health or inferred session fact may be invented.

## State hierarchy

Render one lead state, derived from real repository facts only:

1. Approval required
2. Answer required
3. Failure
4. Running

- Approval and Answer may show the real pending request title, up to three lines.
- If output is genuinely streaming under Approval or Answer, show a subordinate `Also running` qualifier. Running never competes as a second headline.
- Failure comes only from the existing daemon-backed degraded-session fact.
- Running comes only from the existing streaming fact.
- Historical/idle reading has no invented `Complete` block.
- The state block contains no duplicate Review/Answer button when the approval sheet or QuestionCard already owns the decision.

## Transcript grammar

- Turns are full-width and list-first, not chat bubbles.
- Quiet uppercase source labels identify `YOU`, the real agent (`CLAUDE`, `CODEX`, etc.) and `TOOL`.
- User content may align its source label to the trailing edge; agent content reads normally.
- Markdown, code, images and attached files preserve their current rendering behavior.
- Tool events are hairline-bounded bands, never cards. Show the literal tool and payload; long commands wrap rather than truncate. Status appears only when supplied.
- No timestamp slot is reserved when no timestamp exists.
- `Jump to latest / 回到最新` is a floating-but-functional scroll affordance only when the user is away from the tail; it must not cover content or the Composer.

## Composer contract

Preserve the existing two-layer mobile Composer and document it as part of this master.

### Field row

- A full-width, multiline message field above the accessory row.
- Placeholder names the real active agent, e.g. `Message Claude… / 给 Claude 发消息…`.
- It grows vertically within the current bounded behavior and never becomes a narrow slot between controls.

### Accessory row

Left cluster, in order:

1. `+` attachment action.
2. Current model chip, one-tap entry to the existing picker.
3. Session-stack chip only when its real count is available.
4. Context occupancy control only when its real used/window values are available.

Right action slot:

- Idle + empty: microphone.
- Text or ready attachment: Send.
- Streaming + empty: Stop.
- Streaming + typed/staged content: Stop and Send coexist; Send queues behind the running turn.
- Upload in progress: muted Send with progress; sending waits.

Model labels truncate within the existing cap; technical values remain literal. Controls are at least 44–48 pt, with 48 pt accessibility targets even when visible chips are 30 pt high.

### Existing adjacent states to preserve

- Attachment tray/chips, slash command completion and `@file` completion sit above the Composer and remain scroll-safe.
- Voice recording/transcribing/error replaces the field/action state according to existing behavior; it does not add a new route.
- QuestionCard owns its text inputs and may hide the Composer while one of those fields has the keyboard.
- Handoff WAITING keeps a visibly locked/dimmed Composer with its banner; active spectating replaces it with the existing watch bar; returned result docks above it until reviewed.
- Degraded-session send gating keeps the typed draft.

Do not redesign the attachment, voice, question or handoff subflows in this file. Show enough host states to prove the Chat shell accommodates them.

## Quick Actions integration

- Overflow opens the exact confirmed `Chat Quick Actions Master v1` sheet.
- Its four groups, capability gates, ordinary Handoff row without `NEW`, two-tap Clear, picker Back behavior and 200% scrolling remain unchanged.
- In this Chat master, demonstrate the entry point and one open-sheet frame; do not fork or reinterpret the sheet.

## Real fixtures and field-to-copy mapping

All phone content is clearly a fixture. Use realistic, non-personal examples and do not claim runtime telemetry the product lacks.

| Product fact | Visible copy |
|---|---|
| `chatTitle` | Session title; fallback `Chat / 对话` |
| `sessionAgent` | Real display name in identity and turn labels |
| permission mode | Existing localized short label |
| model | Literal model label only when known |
| paired computer | Literal display name |
| workdir | Folder name in location; complete path in path row |
| session origin | `via <literal>` only when supplied |
| pending ask | Localized state label + literal ask title |
| streaming | `Running / 运行中`, or subordinate `Also running` under a higher state |
| degraded session | Existing localized Failure copy; no invented cause |
| context used/window | Existing context gauge only when values are known |
| active agent | Composer placeholder and agent source label |

Suggested fixture: title `Review relay reconnect handling`, Claude, `Ask every time`, model `fable`, machine `MacBook-Pro`, folder `cc-pocket`, path `~/Desktop/Project/app/cc-pocket`. Treat every dynamic token as fixture data.

## Required interactive master and proofs

Create one new versioned file named exactly `Chat Master v2.dc.html`. Do not overwrite or edit any existing project file. Use one shared internal device component and an explicit local state model; the proof frames must be real component instances, not screenshots.

The master board must include:

1. **Canonical Chat** — Chinese/dark, 402 × 874, ordinary transcript, context collapsed, Composer idle.
2. **Context expanded** — same data, grouped two-line facts, full path with copy, pinned Session info; all ordinary facts visible without inner scroll.
3. **Running + queued message** — real Running state, streaming assistant turn, Stop and Send together.
4. **Approval blocking while running** — Approval leads, request title shown, Running subordinate, Composer still explains queue behavior; the existing approval sheet is referenced, not redesigned.
5. **Answer required** — QuestionCard docked above the bottom area, with Composer ownership shown correctly.
6. **Failure/send-gated state** — typed draft is preserved and recovery wording stays factual.
7. **Quick Actions open** — exact confirmed v1 hierarchy, Handoff ordinary with no `NEW`.
8. **Long-content proof** — three-line title, deep path, long tool command.
9. **200% type proof** — Chinese/light at 402 × 874; context facts/path may scroll but Session info, transcript access and Composer remain reachable.
10. **Composer state strip** — compact component proofs for idle, text, streaming empty, streaming queued, upload waiting and voice. These are component proofs, not six competing full-screen masters.

Also show a compact English/dark parity proof. Do not multiply decorative frames beyond these acceptance needs.

## Prototype acceptance path

The prototype must support, in order:

1. Start on Canonical Chat and scroll the transcript.
2. Expand and collapse Context without navigation.
3. Activate the grouped machine/location row and show a named unchanged machine-switcher passthrough.
4. Activate path copy and expose a copied-state acknowledgement.
5. Open Session info from the pinned expanded-context footer, then return.
6. Switch among Idle, Running, Approval, Answer and Failure fixtures using clearly labeled host controls outside the phone.
7. Type a message while Running and show Stop + Send together with queued-message explanation.
8. Open Quick Actions, verify Handoff has no `NEW`, then dismiss back to the same Chat state.
9. Switch locale, theme and 200% type; verify no clipped label, unreachable action or hidden Session info.

Host controls must be clearly marked as prototype controls and must never look like product navigation.

## Responsive and accessibility requirements

- Baseline is exactly 402 × 874 including safe areas.
- Taller phones only expand the transcript.
- Dynamic rows grow; text and targets never shrink.
- At 200% type, use wrapping, reflow and bounded scrolling. No horizontal scroll.
- Every icon-only product action has an accessible name and at least a 48 pt hit target.
- Context disclosure exposes expanded/collapsed state.
- Machine/location row remains one named switch action.
- Copy exposes success without relying on color.
- State is always written and shape-coded; color is supplementary.
- Keyboard focus, system Back, scrim dismissal for ordinary sheets and reduced motion preserve current product behavior.

## Invariants and non-goals

- No protocol, relay, daemon, repository, route, callback, capability-gate or permission change.
- No new state, metric, timestamp, duration, branch, health value, recommendation or generated summary.
- No redesign of Secure Approval, QuestionCard, Handoff, attachments, voice, model picker, Session info or machine switcher internals.
- No new global navigation, AI suggestion chips, feature promotions, `NEW` badges, decorative dashboards, chat bubbles, gradients or glass.
- Do not turn every element into a rounded card.
- Do not hide an available action to make a proof frame fit.
- Do not overwrite any v1 file.

## Self-review and stopping rule

Run one visual self-check at the real 402 × 874 device width. Check header balance, transcript height, Composer reachability, 48 pt targets, long title/path/command wrapping, Quick Actions fidelity, dark/light parity and a genuine 200% state. Correct clipping, false hierarchy or invented facts once, then stop. Report any unresolved trade-off explicitly rather than silently iterating into a new direction.
