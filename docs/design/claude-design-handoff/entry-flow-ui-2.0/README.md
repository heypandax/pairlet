# cc-pocket Entry Flow UI 2.0 — Claude Design handoff

This bundle is the implementation handoff for the entry path that leads into the already-locked Sessions and Chat surfaces. The online project is the review source of truth; these exports are immutable implementation evidence rather than production HTML.

## Online project

- Project: [cc-pocket Design System 2.0](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d)
- Direction: [Entry Flow Direction v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Entry+Flow+Direction+v1.dc.html)
- Interactive master: [Entry Flow Master v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Entry+Flow+Master+v1.dc.html)
- Shared live component: [EntryFlowDevice](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=EntryFlowDevice.dc.html)
- Projects-header refinement: [Entry Flow Master v2](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Entry+Flow+Master+v2.dc.html)
- Refined shared component: [EntryFlowDevice v2](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=EntryFlowDevice+v2.dc.html)
- Model visible during design and export: **Opus 5 Max**.
- Exported: 2026-08-09.

## Export inventory

| File | Role | SHA-256 |
|---|---|---|
| `DESIGN_BRIEF.md` | Authoritative product/data/interaction constraints, including the confirmed Header v2 amendment | `2c6f3087894a9e921d374a9cac3f5ee04d4cb57ec1f0eab17df33192a6434600` |
| `Entry Flow Direction v1.dc.html` | IA, shared grammar, state matrix and 13 direction frames | `4cf7c7b32e39fcf3cbdf876834747a016cb7dcfc913c21d3163ade94135f6bb7` |
| `Entry Flow Master v1.dc.html` | Implementation-ready board with 15 frames | `36341210c533e580c5db923f519628dc49d8a1cf53f0a8b2ce2054cc98cf0b42` |
| `EntryFlowDevice.dc.html` | Live shared component mounted by 14 Master frames | `3864df9f91d07ccc11874719ac20894e8cedafea754b78bcc286e6e6b99873f7` |
| `Entry Flow Master v2.dc.html` | Header macro-composition correction plus five focused proofs and the retained v1 flow board | `142f78078e92f1182d133e69f532a44f7fb24b2eea659c300a2c4f3d0d98a2f9` |
| `EntryFlowDevice v2.dc.html` | Versioned live component carrying the two-row Projects header and overflow interaction | `353fbfd3f6536546c79780bb25837ea705415d3e6f1b60bf4836cac05a8780be` |
| `support.js` | Claude Design runtime used by all three exports | `4935e85cbc1c2eafbb360ba1ca472bc556f7015973a5b0ae61e23646a5707dca` |

Serve this directory over HTTP to inspect the local export; opening the HTML directly does not provide the runtime import environment.

## Authority and scope

```text
protocol + repository runtime behavior
> Entry Flow Master v2 + EntryFlowDevice v2 (Projects-header composition and current entry-flow geometry)
> Entry Flow Master v1 + EntryFlowDevice (locked pre-amendment entry flow)
> Entry Flow Direction v1 (rationale, fields and stress matrix)
> A Master Core / Proofs + Approval Protocol Handoff (Sessions, Chat, Approval)
> prior designs
```

This slice covers Pairing, computer selection and recovery, Projects, the directory picker and new-session configuration. Sessions, Chat and Secure Approval remain governed by the existing Mobile UI 2.0 handoff. Fleet, Review Center, Settings, Help, folder sharing, guest-share terminal states and Demo remain reachable but are not redesigned here.

## Locked interaction decisions

- Pairing is code-first and camera-free by default. QR scanning is explicit; camera denial or unavailability keeps code and paste routes usable.
- `Connecting`, `Reconnecting`, `RelayUnreachable`, `ComputerOffline`, `PairingInvalid` and `Ready` remain distinct `ConnPhase` states with their existing routing semantics.
- Projects has one canonical open-folder/new-session path rather than duplicate equal-weight plus actions.
- The directory picker keeps recents at the home root and pins the current path plus `Start here` / `Options` decision region.
- Configuration order is workdir → Agent → Model → Mode → one final Start. Changing agent resets model and mode to that agent's real defaults.
- OpenCode exposes its actual automatic behavior instead of a disabled permission ladder.
- Full access retains the existing confirmation and names the agent, workdir and computer. Cancel starts nothing.
- Consequential targets are at least 48pt. The automated review raised four shared-component target groups to that minimum before export.
- The 402 × 874 iPhone 17 baseline, light palette and 200% type proof are part of acceptance.
- Projects Header v2 uses two rows: title + Computer/overflow controls, then written machine state + directly reachable Review count. Help and Settings move into overflow, and the real update dot moves with Settings to the overflow trigger.
- Header v2 does not add another plus or New-session action; `Open any folder…` remains the one content doorway.

## Still owned by code

- Real model IDs reported by the selected computer.
- Exact `CODEX_PRESETS` approval × sandbox pairs.
- Capability flags that hide unsupported agents or reveal Claude Auto.
- Localized strings from Compose resources.
- Repository routing, persistence, back behavior, guest/owner semantics and all network effects.

## Implementation boundary

- Recreate the hierarchy and behavior in the existing Compose Multiplatform architecture; do not paste prototype DOM/CSS into production.
- Do not add protocol fields, daemon commands, navigation ownership or synthetic device/project metadata.
- Preserve existing fast-path behavior: New session starts once with persisted defaults; its options control opens configuration and starts nothing.
- Preserve existing specialist routes and mixed-version fallbacks.
- Record any intentional visual deviation here after real simulator verification.

## Design verification

- Direction self-review fixed low contrast and two below-fold critical controls.
- Master self-review fixed stale rapid-tap state and a plural grammar error.
- The automated design checker fixed four sub-48pt targets in the shared component and re-rendered the affected frames.
- All exported files open from the authenticated online project and use the same shared Direction A visual language.
- Header v2 was screenshot-reviewed at 402 × 874. Its generator fixed an import-boundary prop-name defect and the 200% row-two wrap before export. The five proofs cover Ready + Review 2, Connecting, Reconnecting, the open overflow with update dot, and 200% light type.

## Implementation status

The Compose slice landed on 2026-08-09 in:

- `ui/entry/EntryUi.kt` — the pure `ConnPhase` → recovery mapping and the per-agent mode vocabulary.
- `ui/entry/EntryChrome.kt` — the shared low-container chrome (48pt floors, no fixed heights).
- `ui/entry/ComputersSurface.kt` — the paired-computer rows and the failing-phase recovery region.
- `ui/entry/ConfigureSessionSheet.kt` — workdir → Agent → Model → Mode → one Start, plus the Full-access confirmation.
- `ui/PairingScreen.kt` — code-first pairing and the explicit `PairScanRoute` (the only camera surface).
- `ui/DirectoryPicker.kt` — pinned header and decision region around a scrolling list.
- `ui/App.kt` — `ConnectionGate`, `ConnectScreen`, the Projects hierarchy / single open-folder entry, and
  Header v2's two-row responsive composition with real Computers, Review and overflow routes.

Verification performed: 716 desktop tests with 0 failures, iOS Simulator ARM64 compilation successful,
`git diff --check` clean, and reproducible 402 × 874 acceptance frames in `ShowcaseRender.renderEntryFrames`
(`ENTRY_UI_OUT=<dir>`) covering pairing, Computer-offline recovery, Projects dark and light, Projects at
200% type, the picker, Configure for Claude and for OpenCode, and Configure at 200% type. Header v2 also
has interaction tests for its two 48pt trailing controls, direct Review route, Help / Settings overflow,
outside-tap dismissal, skeleton geometry parity and 200% reflow.

## Intentional deviations from the export

Recorded here as the handoff requires. Each one exists because production owns a fact the prototype modelled.

- **Projects ships no `New session` dock.** The prototype's dock starts in one workdir; the real Projects
  screen lists many projects and can be drilled into folders, so it has no single directory to start in.
  Inventing one would be exactly the fabrication this handoff forbids. The fast path survives untouched
  where a real workdir exists — the picker's `Start here` / `Options` region and the Sessions dock. The
  connecting skeleton drops its inert dock to match, so skeleton → list still swaps with no geometry shift.
- **Agents are not capability-hidden.** No wire field reports which backends a computer has
  (`ClientCaps.supportsAgents` is the *client's* declaration, which the daemon uses to filter rows it sends).
  All four chips therefore render; only Claude's native Auto is capability-gated, through the existing
  `supportsPermissionMode`. A hidden-agents note would need a protocol field, which is out of scope.
- **The filesystem-root switcher stays folded into the breadcrumb (`~ ▾`)** rather than becoming standalone
  `~` / `/` chips. Same anchors, same behaviour, and the existing form is already pinned by
  `DirectoryPickerUiTest`.
- **Manual path remains a separate sheet** (issue #7's `NewPathSheet`) reached from an `Enter a path manually`
  row, instead of being inlined into the picker — that sheet owns real seeding and validation behaviour.
- **The collaborator-only banner keeps its existing copy**, which names the actual collaborators, instead of
  the prototype's generic sentence.
- **`Retry` renders as the existing localised `conn_retry`** rather than a second Retry string.
- **The no-results state omits the "pinned and active work is hidden while filtering" footnote** — that
  behaviour was not verified against the real row builder, and an unverified claim is not shippable copy.
- **The Reconnecting strip is not repeated on Projects**; the app root already renders exactly one above
  every content screen.
- **No acceptance frame for the camera-unavailable scanner.** That state is produced by the platform
  scanner's failure callback, which does not fire in an offscreen render; it is covered by
  `EntryFlowUiTest.anUnusableCameraKeepsBothPairingRoutes` instead.

Remaining device gates: real iPhone camera permission denial and recovery, VoiceOver traversal of the code
field and the recovery region, software-keyboard behaviour on the pairing and manual-path surfaces, and
OS-level 200% Dynamic Type. These are device QA, not permission to reopen the visual direction.
