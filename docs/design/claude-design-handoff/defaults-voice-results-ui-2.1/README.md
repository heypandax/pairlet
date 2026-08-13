# Defaults + Voice + Results UI 2.1 — Claude Design handoff

## Receipt

- Claude Design project: <https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d>
- Design model: `Opus 5 Max`
- Design date: 2026-08-12
- Code baseline: `b6024adc1d6574f919fab71c4340fbac0125a422`
- Scope: #237, #238, and #239 only
- Explicit exclusions: #232, #233, #216, and #228

## Canonical artifacts

- [Design brief](DESIGN_BRIEF.md) — production truth, state inventory, constraints, and acceptance criteria supplied to Claude Design.
- [Direction study](<Defaults + Voice + Results Direction v1.dc.html>) — compares the bounded S/V/R alternatives and selects S3 + V3 + R3.
- [Implementation master](<Defaults + Voice + Results Master v1.dc.html>) — canonical live HTML with responsive frames, state tables, measurements, accessibility guidance, and the implementation checklist.
- [Shared live component](DVRDevice.dc.html) — the real component imported by every proof frame in the direction and master files.
- [Design runtime](support.js) — the archived Claude Design runtime required to open the HTML bundle outside the hosted project.

Together these four files form the self-contained live design bundle; keep them in one directory when opening it outside the hosted project. Google Fonts are an optional visual enhancement; the artifacts fall back to system sans and monospace fonts. The Claude Design project remains the editable source.

## Confirmed information architecture

### Agent and session defaults (#237)

- Keep the existing route, group order, capability gates, daemon-owned catalogs, nullable `Default` choices, and callbacks.
- Add one unlabeled, read-only low container directly under the page title. It summarizes the stored agent, permission mode, model, effort, and conditional Fast value using existing labels and live values only.
- Long or stale custom identifiers remain fully readable. The summary stacks label/value at the narrowest width instead of truncating them.
- The settings rows below remain the editable source of truth.

### Voice-aware composer (#238)

- Keep Chat Master v2's two-layer field and accessory model.
- Use one restrained written state ribbon for queue/upload feedback and keep voice failure paired with the existing retry behavior.
- Let leading accessories and trailing actions wrap as complete groups. Stop, Send, Mic, upload status, and capture controls keep their existing semantic predicates and remain independently reachable.
- Recording/transcribing continues to replace the ordinary composer; a separate Stop survives only when the session is streaming.

### Sessions New Result (#239)

- Keep the exact precedence and derivation: `Approval > Answer > Failure > Running > New Result > Complete`.
- Use the selected half-filled attention mark plus the existing written `New result / 有新结果` state.
- Do not alter ordering, open-to-clear behavior, persistence, notifications, or daemon facts.

## Responsive and interaction contract

- Validate 402, 390, 320, and 280 dp widths, both locales/themes represented by the master, and 200% text.
- Preserve complete readable model/effort identifiers and grow rows vertically as needed.
- Interactive controls keep the existing 48 dp target where present and never fall below the 44 dp hard floor.
- Composer controls wrap rather than shrink, clip, overlap, or move into overflow menus.
- Written state is authoritative for accessibility; decorative state marks are excluded from semantics.

## Implementation boundary

Allowed production surfaces are the existing mobile Compose UI for settings, composer, and session-state chrome, plus their desktop UI test renderer and focused tests. No protocol, daemon, relay, desktop-app, route, persistence, notification, permission, transcript, context disclosure, Quick Actions, Model Picker, recording behavior, or visible-copy changes are part of this handoff.
