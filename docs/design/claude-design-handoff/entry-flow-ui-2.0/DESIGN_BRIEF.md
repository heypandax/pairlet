# cc-pocket Mobile UI 2.0 — Entry Flow design brief

## Objective

Extend the approved **Direction A · Native Professional Utility** from Sessions/Chat/Approval to the entry path that precedes them:

```text
First launch / add computer
→ choose or recover a computer connection
→ choose a project or browse a directory
→ start a session with defaults or configure Agent / Model / Mode
→ existing Sessions / Chat
```

The user should understand within seconds:

1. Which computer, project or directory they are acting on.
2. Whether the connection is ready, still connecting, offline, unreachable or invalid.
3. What the one canonical next action is at the current step.
4. Before a configured start, which Agent, Model and permission behavior will actually run.

This is a visual/information-architecture refactor over the existing Compose Multiplatform routes. It must not change pairing cryptography, relay behavior, protocol fields, navigation ownership or session-start semantics.

## Authority and existing visual system

- Product code and state truth:
  - `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt`
  - `protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Models.kt`
  - `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/pairing/Pairing.kt`
- Existing routes/components:
  - `ui/PairingScreen.kt`
  - `ui/App.kt`: `ConnectionGate`, `ConnectScreen`, `DirectoryScreen`
  - `ui/DirectoryPicker.kt`: `DirectoryPickerSheet`
  - `ui/Permissions.kt`: `StartSessionModeSheet`
  - `ui/DeviceManagement.kt`: `DeviceList`
- Visual truth and locked direction:
  - `docs/design/claude-design-handoff/mobile-ui-2.0/A Master Core v1.dc.html`
  - `docs/design/claude-design-handoff/mobile-ui-2.0/A Master Proofs v1.dc.html`
  - `docs/design/claude-design-handoff/mobile-ui-2.0/Approval Protocol Handoff v1.dc.html`
- Reuse the existing Direction A tokens, status marks, typography hierarchy, 20 pt production gutter, hairlines, low-container rows, platform system font and monospace-only technical values.
- Do not create a new brand skin, bottom navigation, dashboard, card wall or onboarding carousel.
- Primary release frame: iPhone 17, **402 × 874 pt**. Dark first with a complete light proof. Taller screens add flexible/scrollable room; smaller screens are not a V1 release gate.

## Real state and data vocabulary

### Connection

`ConnPhase` is authoritative and has exactly these values:

- `Connecting`: first attempt. Show the real Projects-shaped skeleton until directories arrive.
- `Reconnecting`: connection was ready and dropped. Keep the previous content with a slim warning.
- `RelayUnreachable`: relay was not reached. Canonical action: Retry; secondary action: Exit/disconnect.
- `ComputerOffline`: relay is reachable but daemon is offline. Outside Chat, show Retry + Exit and the existing hint. Inside Chat, history remains visible under a slim banner.
- `PairingInvalid`: credential rejected. Canonical action: re-pair/remove the dead binding.
- `Ready`: relay attached, daemon online and directories received.

Never merge Relay unreachable, Computer offline and Pairing invalid into a generic error. Never show Ready optimistically.

### Paired computer

`PairedDaemon` provides only:

- display name: local `label`, else daemon `hostName`, else truncated `accountId`;
- account caption from `accountId`;
- active binding identity;
- optional direct LAN URL, not a guaranteed online indicator;
- owner/guest role.

The disconnected device picker may list several paired computers and must preserve add, switch, rename and remove. A row may say which binding is active, but it must not invent per-device latency, last seen or OS details.

### Project/directory

`DirectoryEntry` real fields include:

- `name`, full `path`, `hasSessions`, `recent`, authoritative `lastModified`;
- `open`, `executing`, `busy` and zero/one/many `activeSessions`;
- current live-session title/id and optional real `gitBranch`;
- optional guest-share owner, expiry and access tier;
- backend history availability (`sessionAgents`).

Real demo fixture shapes for visual proofs:

- active: `/Users/alex/code/cc-pocket`, branch `main`, live title `Add demo mode for App Review`;
- recent projects: `cc-pocket-site`, `relay-server`, `notes-cli`, `dotfiles`;
- directory browser: home anchor `~`, optional `/` or drive roots, breadcrumb and directories only;
- full paths may be long and must wrap or remain explicitly copyable/revealable.

Do not infer repository health, result success, branch for inactive rows, file counts or project recommendations.

### New session configuration

Agent values are exactly `Claude`, `Codex`, `OpenCode`, `Kimi`; capability/compatibility can hide unsupported agents on older peers.

The configuration order is semantic and must remain:

```text
Directory/workdir (context)
→ Agent
→ Model for that Agent
→ execution/permission Mode
→ start
```

- Model choices and defaults come from the daemon/repository. Missing means follow the configured/CLI default; do not invent a model.
- Claude: Default, Accept edits, Plan, Full access; optional native Auto when capability exists.
- Codex: Cautious, Balanced, Autonomous, Full access. These are real approval × sandbox mappings already owned by `CODEX_PRESETS`.
- OpenCode: immutable fully automatic behavior; do not offer a fake approval ladder.
- Kimi: Default, Plan and Full access; no Accept-edits equivalent.
- Full access always opens the existing explicit confirmation before start.
- The existing fast path remains: tapping New session starts immediately with persisted defaults. The defaults/options control opens configuration without starting.
- Current mode-row selection commits the configured start. The design may make the commitment more explicit, but implementation must preserve duplicate-tap guards and never start merely because a disclosure opened.

## Surfaces and information architecture

### 1. Pair a computer

Primary purpose: establish the first owner binding or add another computer.

Required content/actions:

- title and one concise explanation;
- primary 6-digit code entry;
- QR scan as an explicit alternative, including camera unavailable/denied treatment and paste-link fallback;
- exact desktop command `cc-pocket-daemon pair` as copyable technical help;
- paste pairing link disclosure;
- advanced direct LAN disclosure;
- demo entry as a clearly secondary exploration path;
- when adding from an existing binding, a real Cancel/back action;
- collaborator-only recipient banner when collaborator links exist but no owner binding exists.

The first frame must not depend on a live camera image for visual hierarchy. The user's physical environment must not become the page background. The scanner may be entered explicitly and must degrade to code/paste without blocking pairing.

### 2. Choose/recover a computer

- show paired computers as low-container rows, not a floating card stack;
- one real state sentence and one canonical action;
- retain Add computer, rename, remove and advanced direct LAN;
- connecting state must share the Projects header geometry so skeleton → list does not jump;
- Relay unreachable, Computer offline and Pairing invalid each get distinct words and recovery actions;
- existing guest-share terminal states remain specialist surfaces and are not redesigned here.

### 3. Projects

- hierarchy: screen title → machine/link state → utility actions → search/open-folder action → sections/rows;
- machine label remains an explicit doorway to the fleet screen;
- retain tree/flat toggle, Review Center, Help, Settings, approval queue, pull-to-refresh, pin/share long-press actions and guest behavior;
- utility actions must remain reachable at 48 pt but visually quieter than the title, state and current work;
- sections use real data: Pinned, Active, Current project, Projects/Open sessions;
- active project/session must be stronger than recent history and use written state plus a non-color mark;
- rows should present project name first, then only real supporting facts such as full/revealable path, live session title, branch and time;
- preserve filter, tree drill-in, breadcrumbs and no-match/empty states;
- the canonical “open any folder” action must be discoverable without duplicating equal-weight plus controls.

#### Confirmed Projects header v2 amendment

The first implementation exposed a macro-composition gap on the 402 pt release frame: the title, machine
state, Review, Help and Settings all accumulated on the leading edge while the upper-right region carried no
page-level action. The user confirmed this bounded correction on 2026-08-09:

- Row 1: `Projects` remains the large leading title. The trailing edge contains exactly two 48 pt controls:
  the paired-computer/fleet doorway and an overflow menu.
- Row 2: the written machine + connection state remains leading. Review remains directly reachable at the
  trailing edge and carries its real pending count when non-zero.
- Help and Settings move into the overflow menu. The existing version-update dot belongs to that overflow
  trigger; it is not lost or turned into a synthetic status.
- Remove the separate leading-only utility text band. Do not add a top-level plus or a second open-folder
  action: the existing `Open any folder…` row remains the one canonical doorway.
- The computer control opens the existing fleet/computers surface; Review, Help and Settings keep their
  existing routes. This is a composition and hierarchy correction, not a navigation change.
- Keep 20 pt side gutters, 48 pt minimum targets, shape + wording for state, wrapping/no fixed content-row
  heights, and the same header geometry for Ready, Connecting and Reconnecting. At 200% type the two rows
  may grow or reflow without clipping or making the canonical content action unreachable.

### 4. Directory picker

- bounded bottom sheet using the existing navigation model;
- header names that this is a directory on the selected remote computer;
- Recents remain pinned at the home root, followed by Browse;
- root switcher, breadcrumb, up navigation, directories-only results, loading skeleton, empty and error states;
- current full path is always visible/revealable;
- pinned bottom decision region: fast “start here” using defaults plus an explicit options path;
- manual path remains the escape hatch for off-home/old-daemon/guest constraints.

### 5. Configure new session

- bounded/scrollable bottom sheet; Directory/workdir context remains visible at the top;
- Agent → Model → Mode hierarchy must be legible without four cramped equal cards across 402 pt;
- selected value and actual default/fallback are distinguishable;
- backend-specific modes are honest and capability-driven;
- each mode explains behavior in plain language; raw technical axes may be quiet supporting labels;
- one canonical Start action should make the final combination explicit. If retaining tap-to-start rows for compatibility, the row must visibly read as an action rather than an innocent selection control;
- Full access confirmation remains a separate safety step naming the workdir and blast radius;
- sheet dismiss, system back and scrim do not start a session.

## Required states and proofs

At minimum provide 402 × 874 frames/proofs for:

1. Pairing default with code-first hierarchy and scan/paste alternatives.
2. Scanner/camera unavailable or denied, with code/paste path still usable.
3. Connecting Projects skeleton and Computer offline recovery.
4. Projects dark main state with one active project, pinned/recent rows and long names/paths.
5. Projects light empty or no-results state.
6. Directory picker at home with Recents + Browse, and a drilled long-path state.
7. New-session configuration for Claude and Codex.
8. OpenCode honest automatic mode and Full-access confirmation.
9. One 200% Dynamic Type proof where the canonical action and current context remain reachable.

## Accessibility and responsive constraints

- 48 pt minimum interactive targets.
- Status is never color-only; combine shape/wording with color.
- Titles may wrap to three lines; technical values wrap on characters or expose full copy/reveal.
- No fixed row heights for content-bearing rows.
- At 200% type, body/sheet content scrolls while the current context and canonical action remain reachable.
- Explicit semantic labels for scan, copy, expand/collapse, connection recovery, tree/flat and utility controls.
- Pairing must remain completable without camera permission.
- Light and dark themes must both preserve contrast and state priority.

## Interaction acceptance path

1. From an unpaired first launch, enter a six-digit code and reach Connecting without opening the camera.
2. Open Scan QR explicitly; simulate unavailable/denied camera; return to the same code/paste alternatives.
3. From a paired-but-disconnected state, choose another computer; see the Projects-shaped Connecting skeleton; reach Projects without header geometry shift.
4. Exercise Relay unreachable → Retry, Computer offline → Retry/Exit and Pairing invalid → re-pair.
5. In Projects, search, switch tree/flat, drill a folder and return by breadcrumb, open the fleet doorway, and reach the single open-folder entry.
6. Open the directory picker, use a recent, drill into a child, switch filesystem root, use manual path, then open configuration without starting.
7. In configuration, switch Agent and observe Model/Mode choices reset honestly; choose a model and safe mode and start once.
8. Select Full access and verify the separate confirmation; cancel leaves the session unopened.
9. Verify camera-free pairing, Projects and configuration at 200% type and in the light palette.

## Invariants

- Preserve current repository-driven root routing and every existing back/dismiss path.
- Do not alter E2E pairing, credential storage, relay/direct fallback or daemon commands.
- Do not add protocol fields or serialize new enum values.
- Do not remove access to Fleet, Review Center, Help, Settings, approval inbox, pin/share/group flows or Demo.
- Do not start a session twice, start while merely expanding options, or bypass Full-access confirmation.
- Do not turn OpenCode into a permission-aware backend.
- Do not replace the completed Sessions, Chat or Secure Approval visual contracts.

## Non-goals

- No redesign of Fleet, Review Center, Settings, Help, folder sharing or onboarding content.
- No new account/login system.
- No desktop UI redesign.
- No marketing hero, feature carousel, KPI dashboard, glassmorphism or card masonry.
- No copied production HTML/CSS from the design export.
