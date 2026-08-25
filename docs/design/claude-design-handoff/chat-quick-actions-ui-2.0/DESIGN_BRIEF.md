# Chat Quick Actions UI 2.0 — design brief

## Task and primary problem

Redesign the bottom sheet opened by the top-right overflow control in a live Chat session. The user should be able to scan the available actions in a few seconds, distinguish session configuration from commands and destructive actions, and reach any existing route without the menu feeling like a stack of promotional cards.

The current production sheet renders every item as its own filled rounded rectangle. One ordinary conditional action, **Hand off to a colleague / 交给同事接力**, is uniquely rendered with a relay glyph, tinted background, accent text and a `NEW` badge. That treatment has no state or risk meaning and incorrectly makes handoff look recommended. The `NEW` badge must be removed.

## Product and existing surface

- Product: cc-pocket mobile app, Compose Multiplatform.
- Route: Chat → top-right overflow → `QuickActionsSheet`.
- Target device: iPhone 17 standard, 402 × 874 pt.
- Existing foundations: near-black/white palettes, 20 pt page gutters, low-container hierarchy, restrained terracotta accent, minimum 44–48 pt targets, system sans for UI and monospace only for technical values.
- Existing shell: bottom-anchored `PocketSheet`, dimmed scrim, rounded top corners, drag handle, back/scrim dismissal.
- This is a product utility surface, not a feature-discovery or marketing surface.

## Real actions and capability gates

The sheet owns these existing actions only:

### Session configuration

- Switch model: always shown; opens the existing model sub-page.
- Effort: shown only when the active agent reports effort options; opens the existing effort sub-page.
- Fast mode: shown only when the active model reports the `priority` service tier; toggles in place and displays On/Off.
- Permission mode: always shown; dismisses Quick Actions and opens the existing mode sheet.

### Session tools

- Open terminal: opens the existing terminal surface.
- Changed files: fetches and opens the existing changed-files surface.
- Hand off to a colleague: shown only when the current session has no active handoff; opens the existing handoff draft.
- Help & support: opens the existing Help surface.

### Context maintenance

- Compact context: sends `/compact` and dismisses.
- Simplify: shown only when the active agent supports it; sends `/simplify` and dismisses.

### Destructive action

- Clear conversation: remains a two-tap action in the same row. The first tap changes the trailing copy to “Tap again to confirm / 再次点击确认”; the second clears and dismisses.

## Concept boundaries

- Available ≠ recommended. Handoff being available is not a state, alert, promotion or canonical next action.
- Conditional ≠ new. Capability gating is not a reason to add a badge.
- Destructive ≠ primary. Clear remains visually separated and written in danger color, never filled as the sheet’s primary action.
- Compact and Simplify are commands sent to the active session; they are not navigation settings.
- Model, effort and mode display current values; fixed grammar is localized, while model IDs and daemon-provided values stay literal.

## Locked information architecture

Group rows by intent, in this order:

1. **Session settings / 会话设置** — model, effort when available, fast mode when available, permission mode.
2. **Session tools / 会话工具** — terminal, changed files, handoff when available, help.
3. **Context / 上下文** — compact and Simplify when available.
4. A separated destructive row — Clear conversation.

Use a compact low-container list language: group labels and hairline-separated rows, or an equivalent restrained grouping. Do not render every row as a detached card. Preserve a single vertical reading path and right-aligned values/chevrons.

`Hand off to a colleague` must use the same row geometry, text color, surface and weight as Terminal and Changed files. No `NEW`, accent fill, accent text, special badge or unique icon. If the design uses icons, use them consistently for every peer action; otherwise use none.

## Interaction and state requirements

- Tapping model or effort replaces the main content with the existing picker and a clear Back affordance.
- Fast mode toggles in place without dismissing.
- Mode, terminal, files, handoff and help dismiss before opening their existing destination.
- Compact and Simplify send once and dismiss.
- Clear uses the existing two-tap arming behavior; tapping a different row does not accidentally clear.
- If handoff is active, its row is absent; do not show it disabled and do not explain internal protocol state.
- Scrim, system Back and drag dismissal remain unchanged.
- The sheet must scroll at 200% text or on a shorter viewport. Rows grow; text and targets never shrink or clip.

## Copy mapping

| Product fact | Visible copy |
|---|---|
| model | “Switch model / 切换模型” + current model value |
| effort | “Effort / 思考深度” + current value |
| service tier | “Fast mode / 快速模式” + On/Off |
| permission mode | “Mode / 模式” + current short label |
| handoff available | Ordinary “Hand off to a colleague / 交给同事接力” row |
| handoff unavailable | No row |
| clear armed | “Tap again to confirm / 再次点击确认” trailing copy |

## Required proof frames

Create one versioned, implementation-ready design file in the existing **cc-pocket Design System 2.0** Claude Design project. Include:

1. Default Chinese/dark main sheet with effort, handoff and Simplify visible.
2. English/light main sheet with Fast mode visible.
3. Chinese/dark main sheet with handoff absent, proving the list closes cleanly without a placeholder.
4. Clear armed state.
5. Model or effort sub-page with Back.
6. 200% text proof at 402 × 874 where the content scrolls and every target remains reachable.

Use realistic but clearly labeled fixture values such as `fable`, default effort and Ask every time. Do not include session transcript, user names, secrets or external data.

## Accessibility

- Every row is one named button with at least a 48 pt target.
- Values and section labels must not replace the accessible action name.
- Chevron is supplementary, not the only navigation signal.
- Armed Clear must expose the confirmation state in its label/description, not color alone.
- State and grouping must survive dark/light and 200% type.

## Invariants and non-goals

- No protocol, relay, daemon, repository or route changes.
- No redesign of the model picker, effort picker, mode sheet, terminal, files, Help or Handoff flows.
- No new action, global navigation, recommendation, status metric, tutorial or feature-discovery badge.
- Do not change when a row is visible or what its callback does.
- Do not add gradients, glass, floating cards, large icons or a primary CTA.
- Do not overwrite an existing design file.

## Prototype acceptance path

1. Open the default main sheet and verify four written groups in the locked order.
2. Verify Handoff is a normal peer row with no badge or accent treatment.
3. Open Model, return with Back, and preserve the main-sheet structure.
4. Toggle the fixture with an active handoff; the row disappears and no gap/placeholder remains.
5. Arm Clear; only its trailing copy changes and the row remains separated.
6. Switch locale/theme and inspect the 200% proof; all items remain reachable by scrolling.

