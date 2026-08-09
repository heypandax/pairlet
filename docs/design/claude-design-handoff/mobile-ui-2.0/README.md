# cc-pocket Mobile UI 2.0 — Claude Design handoff

This directory is the production design handoff for the approved mobile UI direction. The online project remains the visual source of truth during review; these exports are immutable implementation evidence, not production code.

## Online project

- Project: [cc-pocket Design System 2.0](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d)
- Direction record: [Direction Study Core 3](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Direction+Study+Core+3.dc.html)
- Core master: [A Master Core v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=A+Master+Core+v1.dc.html)
- Core proofs: [A Master Proofs v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=A+Master+Proofs+v1.dc.html)
- Approval handoff: [Approval Protocol Handoff v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Approval+Protocol+Handoff+v1.dc.html)
- Model visible in the design UI during export: **Opus 5 Max**.
- Exported: 2026-08-08.

## Export inventory

| File | Role | SHA-256 |
|---|---|---|
| `Direction Study Core 3.dc.html` | A/B/C direction decision; reference only | `7b0b282d868f472f1482f6e98e7485ef4f08f0d51055ebfe3f50842f9e3efe80` |
| `A Master Core v1.dc.html` | Locked Sessions + Chat master and original approval composition | `763075695a6be42ee3b2a535ef73a8811b0a5425c4a365f27f9aa8a41a326652` |
| `A Master Proofs v1.dc.html` | Light, long-content and dynamic-type proofs | `fa7b12c3da68b89b9c244a6a32f031b544386253c750b43c539ad85616d0e042` |
| `Approval Protocol Handoff v1.dc.html` | Canonical Approval visual/interaction contract | `c7b6eeec589fb69e73cbdc291a6544fc649158ac5a965c3a073d749634fb3fb0` |
| `support.js` | Claude Design runtime used by the exported `.dc.html` files | `ae4f0ac8449655e17cca1e3b179effcb6817a3b0d8dc47f112a9c39c25c39fd7` |

The HTML exports retain their generated Google Fonts links. Production Compose must use platform fonts; the remote font dependency is only an export-preview limitation.

## Authority and supersession

```text
protocol + runtime behavior
> Approval Protocol Handoff v1 (Approval only)
> A Master Core v1 (Sessions + Chat)
> A Master Proofs v1
> Direction Study Core 3
> previous project and prior handoffs
```

The Approval handoff supersedes every approval sheet shown in Core and Proofs. It removes fields the V1 protocol does not provide and changes the canvas baseline to iPhone 17, 402 × 874 pt. Sessions and Chat remain locked by Core/Proofs and are now implemented against those masters.

See `supersession-map.md` for the explicit list and `DESIGN_BRIEF.md` for production behavior.

## Confirmed information architecture

- Computer → Project/workdir → Session is the persistent context hierarchy.
- Sessions is list-first, with state priority stronger than decorative grouping.
- Chat keeps context, blocking intervention and composer reachable while the work stream scrolls.
- Questions remain conversational and render in Chat.
- Approval is a separate non-dismissible security surface with pinned header, scrolling body and pinned decisions.
- Supporting screens must reuse the same foundations rather than create a second product skin.

## Implementation boundary

- Recreate the approved output using the existing Compose Multiplatform architecture.
- Do not paste or translate prototype DOM structure line-by-line.
- Do not invent daemon fields, client summaries, timestamps, durations or recommendations.
- Do not replace the existing navigation/repository model as part of a visual slice.
- Preserve mixed-version behavior and old-daemon fallbacks.
- Record any intentional visual deviation here after real-app verification.

## Convergence corrections applied during implementation

The exported Approval file remains unchanged for auditability. Production intentionally differs in two details agreed after the final review:

- Ordinary grant capability does not imply recommendation; “Allow for task” is not automatically filled.
- `noAutoDeny` uses a textual waiting state and no `∞` symbol.

Additional protocol/implementation corrections are listed in `DESIGN_BRIEF.md`.

## Verification status

- Online files opened successfully while authenticated.
- All four files exported and SHA-256 recorded.
- Visual target confirmed as 402 × 874 pt.
- Secure Approval production slice implemented on 2026-08-08 in:
  - `ui/approval/ApprovalUi.kt` — authoritative state/action classifier.
  - `ui/approval/SecureApprovalSheet.kt` — fixed three-zone security surface.
  - `ui/approval/ApprovalDecisions.kt` — scope-aware decisions and Retry safer flow.
  - `ui/App.kt` — production routing, AttentionLease and background-semantics isolation.
- Reproducible 402 × 874 acceptance frames live in `ShowcaseRender.renderApprovalFrames`; the six fixtures cover ordinary, danger, legacy, one-off, `noAutoDeny` and grant-aware local-zero waiting.
- Sessions and Chat production slices implemented on 2026-08-08:
  - `ui/session/SessionStateUi.kt` and `ui/chat/ChatStateUi.kt` own the shared authoritative state ladder and priority rules.
  - `ui/session/SessionsSurface.kt` implements the Computer → Project/workdir → Session hierarchy, Active/Recent split, intervention row and pinned new-session dock.
  - `ui/chat/ChatChrome.kt` implements the wrapping identity header, bounded expandable context, pinned state block and User/Agent/Tool turn grammar.
  - Existing group management, session routes, history paging, composer/IME behavior, QuestionCard and Secure Approval decision ownership remain intact.
- Reproducible Sessions/Chat 402 × 874 acceptance frames live in `ShowcaseRender.renderCoreFrames`; fixtures cover Sessions dark/light, Chat streaming, Chat approval and 200% type.
- Final implementation verification: 690 desktop tests with 0 failures, iOS Simulator ARM64 compilation successful and `git diff --check` clean.
- Intentional real-data boundaries: Sessions does not invent Failure/New result; Chat does not invent branch/timestamps; the Chat state block is actionless because Secure Approval and QuestionCard remain the only decision surfaces.
- Remaining device gates: real iPhone bottom safe-area/home-indicator proof, VoiceOver focus traversal, software-keyboard flows and OS-level 200% Dynamic Type. These are device QA, not permission to reopen the visual direction.
