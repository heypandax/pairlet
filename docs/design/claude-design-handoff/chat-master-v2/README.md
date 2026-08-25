# Chat Master v2 — design handoff

## Status

- Current Chat design source of truth for Mobile UI 2.0.
- Designed and reviewed on 2026-08-09 in Claude Design with Opus 5 Medium.
- Project: <https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Chat+Master+v2.dc.html>
- Target viewport: iPhone 17 standard screen, represented by an exact 402 × 874 frame.
- Design only. This handoff does not change production routes, protocol messages, persistence, relay behavior, or daemon behavior.

This file supersedes the Chat frame in `A Master Core v1` and the Chat accessibility proof in `A Master Proofs v1`. Those files remain historical references. Quick Actions is still owned by `Chat Quick Actions Master v1`; Secure Approval is still owned by `Approval Protocol Handoff v1`.

## Files

| File | Role | SHA-256 |
| --- | --- | --- |
| `DESIGN_BRIEF.md` | Locked problem statement, real data contract, states, proof matrix and acceptance path | `07b395f8f8a2ebaabf261bf0385d93ede8c46f3ed386cc38f469bf349a5fb691` |
| `Chat Master v2.dc.html` | Review board and current source-of-truth composition | `e37d982f34ae7ce7610e8d4a5b7e435dba9143257be9d06424924643a0549a61` |
| `ChatDeviceV2.dc.html` | Shared 402 × 874 Chat device component and interactive state fixture | `b9214874f9ac03169cf8882a43090cf7c7d582ccf32ee0a0e70ca3a10fbd9c48` |
| `ChatComposerV2.dc.html` | Shared two-layer composer and its six state variants | `b9edd6d1fb16feda73982edeb06444bce8f1ab13a868ffff49bc268b9dcee1d7` |
| `support.js` | Claude Design component runtime used by the archived `.dc.html` files | `4935e85cbc1c2eafbb360ba1ca472bc556f7015973a5b0ae61e23646a5707dca` |

## Screen contract

The Chat screen is one vertical system with five zones:

1. Navigation — Back, wrapping session title, and the single Quick Actions entry.
2. Context — a two-line collapsed summary; expanded identity and location groups; full wrapping/copyable path; Session info pinned outside the facts scroller.
3. Lead state — exactly one of Approval, Answer, Failure, or Running. Priority is `Approval > Answer > Failure > Running`; idle renders nothing.
4. Transcript — list-first conversation grammar with quiet source labels and hairline tool bands, without chat bubbles or decorative cards.
5. Composer — input surface above an accessory row; both remain reachable while content scrolls.

## Required proofs

The master contains ten live instances of the same component rather than ten redrawn screens:

1. Chinese, dark, canonical idle.
2. Chinese, dark, context expanded.
3. English, dark, running with a queued draft.
4. English, dark, approval while streaming.
5. Chinese, dark, answer required.
6. English, light, failure with send gated.
7. Chinese, dark, Quick Actions open.
8. English, dark, long title/path/tool content.
9. Chinese, light, 200% text with expanded context.
10. English, dark, localization parity.

The composer strip separately proves idle, staged text, streaming-empty, streaming-queued, upload-in-progress, and voice-recording states.

## Locked implementation truths

- Streaming with an empty field shows **Stop only**. It must not also show a microphone.
- Streaming with staged content shows Stop and Send; Send explicitly queues behind the running turn.
- Failure has no detail string in `ChatStateUi`. Render only the localized Failure label; do not invent a cause, reconnect message, recovery banner, or health diagnosis.
- A gated send preserves the draft when sending returns false.
- Approval and Answer actions stay in the existing approval sheet and `QuestionCard`; the lead-state block does not duplicate them.
- Quick Actions uses four groups. Handoff is an ordinary peer row with no `NEW` badge.
- Missing stack/context occupancy values remove their chips; they never render fake zero values.
- No new Complete state is introduced. A completed turn is represented by the transcript.

## Accessibility and overflow decision

- Interactive targets are at least 48 pt, including visually smaller model and context chips.
- Long title, path and tool command content wraps; there is no horizontal scrolling.
- At 200% text with context expanded, the facts/path body becomes the bounded inner scroller. Session info and the composer remain pinned and reachable.
- This leaves only a few transcript lines visible at that extreme. If more transcript must remain visible, reduce the expanded facts-body bound; do not shrink type or hide Session info.

## Production mapping

Use the design as a behavioral and layout contract when updating the existing Compose implementation, principally:

- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/App.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/chat/ChatChrome.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/SessionSheets.kt`
- localized strings under `mobile/composeApp/src/commonMain/composeResources/values*/strings.xml`

Preserve the existing state derivation and callback semantics. The `.dc.html` fixtures are not product logic and must not be copied into the app as a second state machine.

## Archive runtime note

The exact Claude Design export is intentionally retained as `.dc.html` plus `support.js`. It is a review artifact, not production web code. The runtime loads pinned React, ReactDOM and Babel packages from a CDN, while the master board loads its presentation fonts from Google Fonts; opening the archive outside Claude Design therefore requires network access. None of these web dependencies belong in the mobile application.

## Implementation record

Implementation was performed on 2026-08-09 from this handoff with the exact `claude-opus-5` model, then reviewed against the pre-implementation worktree baseline.

- Implementation session: `dc5e98f5-a684-45c2-9ae5-d18dbd7e2776`
- Independent model-probe session: `9d7222ea-3867-4a3d-81ea-40a835366c36`
- Implementation transcript: `/tmp/claude-implementation-20260809-113137.jsonl`
- Baseline patch: `/tmp/claude-implementation-baseline-20260809-113137.patch`

Production changes cover the existing Compose Chat implementation rather than introducing a parallel state machine:

- the transcript uses full-width list entries and removes the raised user-message bubble;
- context disclosure, long path and tool payload overflow, and pinned Session info follow the master contract;
- tool payloads retain the established two-line collapsed preview and expand in full on tap; wrapping does not remove disclosure;
- the composer exposes persistent upload/queue notes and uses real 48 pt interaction targets for attach, model, stack/context, send, microphone, stop and recording actions;
- streaming-empty and streaming-with-draft behavior preserve the existing state and callback semantics;
- Quick Actions remains the grouped low-container list, with Handoff presented as an ordinary peer action and no `NEW` treatment;
- English and Chinese accessibility state descriptions were added for context disclosure.

Verification completed before physical-device visual acceptance:

- focused Chat, Quick Actions, state, composer and context suites: 59 tests, zero failures;
- full mobile desktop suite: 95 suites, 755 tests, zero failures or errors;
- `:protocol:jvmTest`, `:daemon:test`, `:relay:test`, and `:mobile:composeApp:desktopTest`: successful;
- `:mobile:composeApp:compileKotlinDesktop`: successful;
- `:mobile:composeApp:compileDebugKotlinAndroid`: successful;
- native iOS 1.7.5 candidate build and installation on Pandaa: successful;
- desktop application update and restart: successful;
- `git diff --check`: clean.

`scripts/check-all.sh` reaches the native protocol test phase but this development Mac's current Xcode installation reports that the `ios_simulator_arm64` SDK cannot run simulator tests. The JVM, daemon, relay, desktop and physical-iOS paths above are the applicable release gates; this environment limitation is not represented as a product test failure.

Physical-device acceptance completed on Pandaa with the 1.7.5 candidate installed and launched. The first real-device review exposed one regression: single-line tool payloads no longer collapsed because wrapping had bypassed the existing disclosure state. The final candidate restores a two-line wrapped preview with tap-to-expand/tap-to-collapse, adds a focused layout-and-interaction regression test, and passed the 34-test Chat/Chrome/Quick Actions set plus the complete 755-test mobile desktop suite. The user then authorized the full-platform 1.7.5 release.
