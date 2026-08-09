# Chat Quick Actions UI 2.0

## Design source

- Claude Design project: https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Chat+Quick+Actions+Master+v1.dc.html
- Design model shown in the project: Opus 5 Medium
- Archived: 2026-08-09
- Master proof board: `Chat Quick Actions Master v1.dc.html`
  - SHA-256: `3b6a186acf87dfbaafcf6a1f8b75c92e0a022a436d1325e4550c9c6be211b781`
- Shared interactive device: `QuickActionsDevice.dc.html`
  - SHA-256: `7558812d3c4a2b4aed175be96cbff0d5dfac05d002f20f4d1902c175fb659640`
- Product/design contract: `DESIGN_BRIEF.md`

The two HTML files are the exact versioned files served by Claude Design after its visual self-check. Keep them together: the master board embeds the shared device component.

## Confirmed direction

The sheet uses one low-container vertical list with hairline-separated rows. It is grouped in this order:

1. Session settings — model, effort when available, fast mode when available, permission mode.
2. Session tools — terminal, changed files, handoff when available, help.
3. Context — compact, Simplify when available.
4. A visually separated destructive Clear row.

`Hand off to a colleague / 交给同事接力` is an ordinary peer action. It has no `NEW` badge, promotional fill, accent text, relay glyph, or other unique treatment. Availability still follows the existing active-handoff capability gate.

## Interaction and responsive rules

- Model and effort replace the list with the existing picker and a Back affordance.
- Fast mode toggles in place; existing routes and commands keep their current dismissal semantics.
- Clear keeps its existing two-tap confirmation behavior; the armed copy is available to accessibility services, not only represented by color.
- Rows remain at least 48 pt high and grow with text. The list scrolls on a shorter viewport and at 200% text instead of clipping or shrinking.
- No protocol, daemon, relay, route, callback, or capability-gate change is part of this design.

## Proofs included

The master board includes Chinese/dark, English/light with Fast mode, active-handoff omission, Clear armed, model sub-page with Back, and 200% text states at the 402 × 874 iPhone 17 standard viewport.

## Implementation record

- Implementation model: exact `claude-opus-5`
- Implementation session: `16984411-8c77-44ca-ad85-d3c2187b3708`
- Model-probe session: `4471a849-908e-4172-bf3f-b06d8a00dfe1`
- Implementation transcript: `/tmp/claude-implementation-20260809-095157.jsonl`
- Model-probe transcript: `/tmp/claude-implementation-model-probe-20260809-095157.jsonl`

Production changes:

- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/SessionSheets.kt`
- `mobile/composeApp/src/commonMain/composeResources/values/strings.xml`
- `mobile/composeApp/src/commonMain/composeResources/values-zh/strings.xml`
- `mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/Popovers.kt`
- `mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/ui/QuickActionsSheetUiTest.kt`

Verification completed on 2026-08-09:

- Full desktop suite: 94 suites, 739 tests, zero failures.
- Focused quick-actions suite rerun independently: five tests, successful.
- `compileKotlinDesktop`, `compileKotlinIosSimulatorArm64`, and `compileDebugKotlinAndroid`: successful.
- Native iOS Debug app build through Xcode: successful; only existing Clang/Swift import and Gradle deprecation warnings.
- Installed and exercised on the dedicated iPhone 17 / iOS 26.2 simulator (`469801B8-3CA5-4952-BFAC-0013FCC12F64`): main list, scrolling to Clear, and first-tap Clear armed state all verified.

Simulator proofs:

- `implementation-ios-simulator.png` — default quick-actions state; SHA-256 `9bf056ecba5ebfb7f48e7535dcaeba1a0507260fbbf7dec09792586ff960afbf`
- `implementation-ios-simulator-clear-armed.png` — scrolled destructive row after the safe first tap; SHA-256 `c909850a3bbc6c071382aecb9dc46f4f2fa52a9fa61819e048237286a67cd142`
