# Approved implementation brief — Entry Flow UI 2.0

## Objective

Implement the approved entry-flow redesign in the existing Compose Multiplatform mobile app. The slice starts before Sessions and ends when a session is opened:

1. Pair a computer.
2. Choose or recover a paired computer.
3. Browse Projects.
4. Choose an arbitrary directory when needed.
5. Start immediately with persisted defaults or explicitly configure Agent, Model and Mode.

The design is already approved. Do not create another visual direction.

## Authoritative references

Read these before editing:

- `docs/design/claude-design-handoff/entry-flow-ui-2.0/DESIGN_BRIEF.md`
- `docs/design/claude-design-handoff/entry-flow-ui-2.0/README.md`
- `docs/design/claude-design-handoff/entry-flow-ui-2.0/Entry Flow Master v1.dc.html`
- `docs/design/claude-design-handoff/entry-flow-ui-2.0/EntryFlowDevice.dc.html`
- `docs/design/claude-design-handoff/entry-flow-ui-2.0/Entry Flow Direction v1.dc.html`
- Existing locked foundations in `docs/design/claude-design-handoff/mobile-ui-2.0/`.

The HTML is design evidence only. Recreate the hierarchy and interactions with existing Compose components/tokens; do not copy DOM or CSS into production.

## Existing implementation to preserve

- Root routing, repository ownership and all back/dismiss behavior in `ui/App.kt`.
- `ConnPhase` and its exact six values in `PocketRepository.kt`.
- Pairing, LAN, camera, clipboard and settings platform effects already wired by `PairingScreen.kt` and `Permissions.kt`.
- Directory data, guest/owner behavior, pinned/active/recent logic, tree/flat mode, fleet/review/help/settings/share routes and sheets already wired by `DirectoryScreen`.
- Directory traversal, home/root anchors, recents, unreadable/empty/loading behavior and manual-path behavior in `DirectoryPicker.kt`.
- Agent capability, model-list, persisted-default and preset behavior in `StartSessionModeSheet` / `Permissions.kt`.
- The already-refactored Sessions, Chat and Secure Approval implementations and tests.
- Every pre-existing dirty-worktree change. Do not reset, clean, restore, overwrite or simplify unrelated work.

## Required behavior

### Pairing

- The initial pairing surface must be code-first and must not start or request the camera.
- Six-digit entry is the hierarchy and `Pair computer` is the canonical action.
- `Scan QR code` is an explicit secondary route. Only entering that route may request/start camera access.
- The scan surface must have a real back path. Camera denied/unavailable must remain useful: explain the state and keep routes to six-digit entry and paste-link pairing. Preserve entered digits when navigating back during the same composition lifetime.
- Keep paste-link, direct-LAN, daemon-command help, Demo, collaborator-only banner and add-device Cancel behavior reachable, with reduced visual weight rather than deletion.
- Do not change actual pairing validation, URI parsing or repository effects.

### Computer selection and connection recovery

- Present paired computers as low-container entity rows using real `PairedDaemon` fields only.
- Preserve active binding, owner/guest, rename/remove, add-computer and direct-LAN affordances.
- Keep all `ConnPhase` meanings distinct:
  - `Connecting`: first attempt; Projects-shaped skeleton; no recovery action yet.
  - `Reconnecting`: retain last known content under a slim warning.
  - `RelayUnreachable`: identify relay reachability; Retry and Exit.
  - `ComputerOffline`: relay reached but daemon unavailable; keep the existing daemon hint; Retry and Exit.
  - `PairingInvalid`: credential rejected; Pair again and Remove; no ineffective Retry.
  - `Ready`: render Projects only after directory data is ready.
- Do not infer online state, latency, OS, battery or last-seen data.

### Projects

- Use the approved Direction A hierarchy: title/context, machine doorway/state, quiet utilities, then work.
- Preserve filter, tree/flat, breadcrumb drill/back, pinned/active/projects sections, running/session information, fleet doorway, Review, Help, Settings, share/guest behavior and approval entry.
- There must be one canonical open-folder/new-session entry in the content hierarchy. Remove the current duplicate equal-weight plus treatment without removing functionality.
- Preserve the fast path: the primary New session control starts once with persisted defaults.
- Its adjacent defaults/options control opens configuration and starts nothing.
- Use real `DirectoryEntry` data only. Branch is shown only when supplied. Do not invent repository health, test status, file count or recency.

### Directory picker

- Preserve home/root anchors, recents at home root, child traversal, breadcrumb/up, manual path, loading, empty, unreadable and retry behavior.
- Keep the current path/context and the decision region reachable while the middle list scrolls.
- `Start here` starts through the existing effect exactly once; `Options` opens configuration and starts nothing.
- Long paths wrap or remain fully discoverable; do not truncate away the meaningful end.

### Configure session

- Order content as workdir → Agent → Model → Mode → one explicit final Start.
- Changing Agent must reset Model and Mode to that agent's real defaults/options.
- Model IDs must come verbatim from the selected computer. When no model list exists, state that the configured/default model will be followed; do not invent a model name.
- Preserve capability gating for exactly Claude, Codex, OpenCode and Kimi.
- Preserve the real Claude, Codex and Kimi mode sets and the exact `CODEX_PRESETS` axes already in code.
- OpenCode has no approval ladder. Replace it with an honest automatic-behavior statement and one Start action.
- Selecting an ordinary mode must not itself start the session. Starting occurs only from the final Start control.
- Full access opens the existing confirmation before starting. The confirmation names the agent, workdir and computer/reach using real data. Cancel/dismiss returns to configuration, preserves the selection and starts nothing.
- Prevent duplicate start effects caused by repeated taps or overlapping sheet callbacks.

## Visual and accessibility constraints

- Baseline: iPhone 17 standard screen, 402 × 874 pt.
- Reuse `Tok`, `Metrics` and the locked Mobile UI 2.0 typography, spacing, hairlines, state marks and low-container hierarchy.
- Dark-first, but retain readable light theme behavior.
- Consequential and navigation targets must be at least 48pt.
- Avoid fixed row heights that clip localized or 200% text. At 200%, context and the final decision must remain reachable while the middle body scrolls.
- State must be carried by text/shape as well as color.
- No bottom navigation, new brand skin, decorative card stack or duplicate filled primary actions.
- Add/update English and Chinese resources for every new user-visible string; do not hard-code UI copy in Kotlin.

## Implementation boundaries

- Mobile Compose UI and its focused tests only.
- Do not modify `protocol/`, `daemon/`, `relay/`, serialized models, wire enums or daemon commands.
- Do not replace navigation/repository architecture, add new persistent settings, or redesign specialist surfaces.
- Do not start, stop, update or install the local daemon.
- Do not commit, push, reset, clean, checkout, restore, switch or rebase.

## Tests and proof

Add focused pure/UI tests where practical for:

- camera-free default pairing and explicit scanner routing;
- the six connection-state action/retention rules;
- one canonical Projects open-folder/new-session entry;
- picker `Start here` versus `Options` effect ownership;
- Agent change resetting model/mode;
- ordinary mode selection not starting;
- OpenCode automatic behavior;
- Full access Cancel/start ownership and duplicate-start protection;
- target/layout invariants that are reasonably testable without screenshot goldens.

Extend the existing showcase/acceptance renderer with deterministic 402 × 874 entry-flow fixtures if the current architecture supports it without adding production-only routes. Cover at least Pairing, Projects, Directory picker, Configure, one connection failure, light theme and 200% type.

Run focused tests during implementation. The orchestrating agent will independently run the full desktop test suite, iOS Simulator ARM64 compilation and real iPhone 17 simulator verification after reviewing the diff.

## Completion report

Report:

- changed files and why;
- preserved routes/effects and any intentional design deviation;
- tests executed and exact results;
- assumptions and remaining device-only QA gates.
