# Defaults + Voice + Results UI 2.1 — Design Brief

## Objective

Refine three already-implemented mobile surfaces without changing their product semantics:

1. **Agent & session defaults (#237)** — make a long capability-driven settings page easier to scan while keeping every daemon-owned model and effort value readable.
2. **Voice-aware composer (#238)** — keep Mic, Stop, Send, upload progress, and voice capture truthful and reachable at compact phone widths.
3. **New Result in Sessions (#239)** — make completed-away work discoverable without confusing it with Running, Approval, Answer, Failure, or ordinary Complete.

This is a bounded continuation of **Mobile UI 2.0**, **Chat Master v2**, and **Settings + Bridges UI 2.1** in the existing `cc-pocket Design System 2.0` Claude Design project. It is not a redesign of navigation, Chat, Settings landing, or the session data model.

## Current production truth

### Shared visual language

- Phone proofs: 402 × 874 primary; 390 × 844 localization; 320 and 280 stress widths; 200% text.
- Paper/base background, low containers, hairlines, restrained terracotta accent, no nested-card dashboard chrome.
- System sans for prose; monospace only for technical values such as model ids, paths, branches, and counts.
- Every interactive target is at least 44 pt; 48 pt is the current UI 2.0 target.
- Color and shape are supplementary. Important state is written.
- Existing routes, resource copy, repository callbacks, capability gates, and protocol facts are authoritative.

### 1. Agent & session defaults

Route: Settings → `Agent & session defaults`.

The page currently renders, in this order:

- Default agent: Claude, Codex, OpenCode, Kimi.
- New-session permission mode: the existing four modes, plus Claude Auto only when advertised.
- Default model for the selected agent.
- Default reasoning effort for the selected agent.
- Codex Fast toggle only when the selected model advertises service tier `priority`.
- Catch-all context window, custom value, per-model overrides.
- Agent filter.

Model and effort catalogs are daemon-owned and may contain many values, legacy values, a stale currently-selected value, or long custom ids. `Default` is a real nullable choice. Do not invent a fixed catalog, alias Codex ids, truncate an auditable model id, or silently hide a selected unknown value. Agent selection changes the model/effort scope; it must not rewrite another agent's stored defaults.

Observed current frame at 402 pt: agent and model groups are low containers; permission modes are bare hairline rows; the page is visually dense and the selected Codex model appears only after substantial scrolling. At 200% text the title wraps to three lines and rows grow correctly, but the user sees little more than agent and the first permission modes.

### 2. Voice-aware Chat composer

Chat Master v2 remains the Chat source of truth. Keep the two-layer composer: full-width multiline field above an accessory row. Do not redraw the transcript, context disclosure, model picker, Quick Actions, or desktop composer.

Real state contract:

- Idle + empty: Mic is the accessory action.
- Draft/ready attachment/staged skill: field keeps Mic; Send is independently reachable.
- Streaming + empty: Stop only, with the written queue note.
- Streaming + staged content: field keeps Mic; Stop and Send coexist on a separate compact lane; Send queues into the running turn.
- Upload in flight: field keeps Mic; Send is unavailable until the workspace path lands; the real moving/total count is written.
- Recording or transcribing: the recording bar replaces the field. If streaming is also true, Stop remains reachable in a separate row throughout both capture phases.
- Voice transcript appends to the current draft and waits for explicit Send.
- Voice failure: a Retry voice action and the existing failure chip coexist with a staged draft's Send. Retry must not announce itself as Dictate.

Observed 320 pt frames:

- Streaming + a wrapped draft currently uses three vertical bands: queue note + field/Mic, attach/model, then context + Stop + Send. It is truthful and non-overlapping, but the action hierarchy can be made calmer.
- Recording + streaming uses the recording bar and a separate Stop row. Both are reachable, though the bottom stack feels visually fragmented.
- Upload-only and failed voice states keep Mic in the field and preserve written state. No controls clip.

### 3. Sessions — New Result

State precedence is fixed:

`Approval > Answer > Failure > Running > New Result > Complete`

`New Result` is process-local and appears only after this client observed an authoritative `executing || busy` session transition to settled while another session/surface was open. It is not inferred from timestamps, persists until an authoritative open, supports multiple independent sequential completions, and does not change daemon ordering or create a notification log.

Observed current frame: two independent New Result sessions stay in Active above one Running session; Complete is in Recent. New Result uses the attention tone plus a ring and written `New result / 有新结果` in metadata. It must remain less urgent than Approval/Answer, distinct from the filled Running mark, and clearly stronger than Complete.

## Design task

First create exactly one direction board, named:

`Defaults + Voice + Results Direction v1.dc.html`

It must compare 2–3 bounded visual refinements, select one with explicit reasoning, and stop without editing existing files. After that selection is accepted against the hard boundaries, create the implementation-ready artifact:

`Defaults + Voice + Results Master v1.dc.html`

Use live components, not flattened screenshots.

Include:

- Settings top, scrolled model/effort region, long-id stress, and 200% text.
- Chat state strip for idle, staged, streaming empty, streaming staged, upload, recording+streaming, transcribing+streaming, and failure+draft.
- Sessions state comparison for New Result, Running, Complete, Approval, and two simultaneous New Results.
- Exact spacing, typography, color/token use, container/hairline rules, responsive wrap rules, and touch-target geometry.
- Accessibility names/state descriptions and localization/large-type behavior.
- A short implementation boundary that maps refinements only to the existing Compose surfaces and test renderer.

## Hard boundaries

- No new routes, fields, statuses, values, copy, server facts, protocol messages, persistence, notification behavior, permissions, or daemon/desktop behavior.
- No changes to Settings landing information architecture.
- No changes to Chat transcript/context/Quick Actions/Model Picker.
- No hidden controls, overflow menus, swipe-only actions, horizontal scrolling, type shrinking, or model-id ellipsis on the settings page.
- No replacement of the existing New Result derivation with timestamps or optimistic taps.
- Excluded issues #232, #233, #216, and #228 are out of scope.

## Acceptance

- 402/390/320/280 widths and 200% text remain usable with no clipped or overlapping controls.
- Every control keeps at least a 44 pt target; implementation should retain the current 48 pt floor where present.
- All eight composer states remain reachable and semantically distinct.
- Multiple New Result rows remain independently discoverable and state precedence is unchanged.
- Long/custom model and effort values remain fully readable and selectable.
- The design can be implemented only in the current mobile Compose UI and its desktop UI tests; protocol, daemon, relay, and desktop app require no change.
