# Issue #228 ZCode backend — Claude implementation handoff

Updated: 2026-08-12 (America/Los_Angeles)

## Read this first

Continue the in-progress implementation of [issue #228](https://github.com/heypandax/cc-pocket/issues/228) in the current worktree. Do not start over, create another worktree, or discard any dirty files.

- Worktree: `/Users/lidapeng/.codex/worktrees/a2f1/cc-pocket`
- Baseline commit: `b6024adc1d6574f919fab71c4340fbac0125a422`
- Git state: detached HEAD, no branch, no commit, no staging, no push.
- The worktree was clean when this task began. The current tracked and untracked changes are the issue #228 implementation and its tests/probe.
- Read the repository `AGENTS.md` before acting.
- Never run `./gradlew :daemon:run`, execute the daemon distribution directly, or update/restart the local daemon while working on this handoff.
- Preserve every current dirty file. Do not use reset, clean, checkout, restore, rebase, commit, or push.

The implementation is substantial and mostly green, but it is not ready to call complete. The first task is the resume/model/effort ordering defect described below. After that, rerun the full serial suite and perform a final diff review. Claude Design refinement is a later, separate phase.

## User intent and scope

The requested outcome is to add ZCode (Z.ai/GLM) as another `AgentBackend`, matching the existing multi-agent experience rather than inventing a ZCode-only product flow.

Success means:

1. ZCode can be selected on supported daemon/app pairs.
2. New and resumed sessions use the official programmable entry, stream text/reasoning/tools/usage, support prompt FIFO, stop, permissions and user questions, and retain history/session lists.
3. Mobile and desktop expose the agent and its configured models without leaking Claude gateway aliases or UI.
4. New/old app-daemon combinations fail closed around the newly-added enum value instead of silently launching Claude or dropping whole list frames.
5. Existing Claude, Codex, OpenCode and Kimi behavior remains intact.
6. The implementation is backed by a repeatable ZCode protocol probe and tests.

Non-goals for this implementation pass:

- Do not add a separate ZCode approval model.
- Do not enable collaborator/session handoff for ZCode until that restricted execution path is explicitly proven.
- Do not claim built-in ZCode Start Plan subscription support from cc-pocket.
- Do not refactor the existing agent backends.
- Do not redesign the UI yet. Claude Design refinement comes only after runtime correctness is green.

## Proven runtime facts

The implementation was derived from the official ZCode 3.7.6 bundle and CLI 0.16.3, not from an assumed ACP/JSON-RPC contract.

- Machine entry: `app-server --stdio`.
- An official feedback log independently shows `zcode-agent app-server --stdio`: <https://github.com/zai-org/feedback/issues/195>.
- Framing is newline-delimited JSON with `{id, method, params}`. Adding a `jsonrpc` field is rejected.
- `initialize` is not a supported method.
- Probed lifecycle surface includes `session/create`, `session/list`, `session/resume`, `session/read`, `session/messages`, `session/subscribe`, `session/send`, `session/stop`, `session/setModel`, `session/setMode` and `session/setThoughtLevel`.
- `session/stop` must be a request, not a notification.
- The local store observed in 3.7.6 is `~/.zcode/cli/db/db.sqlite`.
- Model configuration observed in 0.16.3 is `model.main = {provider, model, ...}` and is projected on the cc-pocket wire as `provider/model`.
- The official desktop host handles `interaction/requestProviderRuntimeHeaders` for built-in Start Plan credentials by mutating private runtime headers. cc-pocket cannot access those desktop-owned credentials. The backend deliberately returns `{headersApplied:false,errorMessage:...}` so this fails explicitly instead of hanging.

The repeatable evidence tool is `scripts/probe-zcode-app-server.py`. The unpacked 3.7.6 CJS used during this task currently exists at `/tmp/cc-pocket-zcode-3.7.6/zcode.cjs`; Node 24 is `/Users/lidapeng/.nvm/versions/node/v24.3.0/bin/node`. Treat `/tmp` as disposable and rediscover/re-download if absent.

## What is implemented

### Protocol and compatibility

- `AgentKind.ZCODE` with wire name `zcode`.
- `DAEMON_SUPPORTED_AGENT_WIRES` and trailing optional `DaemonInfo.supportedAgents` for app-to-daemon reverse capability negotiation.
- ZCode model compatibility requires `provider/model`.
- New app + old daemon: ZCode is hidden and final `OpenSession` egress is denied by omission.
- New daemon + old app: ZCode session, directory and handoff rows are filtered per connection using `ClientCaps`; existing agents retain their old behavior.
- Directory filtering covers both `activeSessions` and `sessionAgents`.

Primary files:

- `protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Models.kt`
- `protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Messages.kt`
- `protocol/src/commonTest/kotlin/dev/ccpocket/protocol/DaemonInfoAgentCapabilitiesWireCompatTest.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/AgentAvailability.kt`

### Daemon backend

New ZCode package:

- `daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeBackend.kt`
- `daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeLauncher.kt`
- `daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeModelService.kt`
- `daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodePaths.kt`
- `daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeTranscriptScanner.kt`
- `daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeTranscriptReplay.kt`

Implemented behavior:

- Strict NDJSON request/response handling without `jsonrpc`.
- Lazy executable resolution and `--zcode-bin` / `CC_POCKET_ZCODE_BIN` support.
- macOS bundle CJS is launched through the bundle-matched Electron runtime with `ELECTRON_RUN_AS_NODE=1`; an orphan CJS fails fast instead of selecting an arbitrary old Node.
- Linux standalone agent fallback includes `~/.zcode/server/agents/glm/zcode-agent`.
- Create/resume/subscribe/send FIFO/stop, mode/model/thought-level setters.
- Text and reasoning deltas, tool cards/results, provider-call occupancy and turn usage.
- Exact permission option response objects and the shared AskUserQuestion card path.
- Official image attachment shape.
- Startup cancel is deferred until the opening prompt is written, then `session/stop` is sent.
- Read-only SQLite session listing, model lookup and transcript replay.
- Missing ZCode is lazy: it fails only when a ZCode conversation starts, not at daemon startup if another backend is available.

Integration touches `DaemonCore.kt`, `Main.kt`, `RequestRouter.kt`, `SessionRegistry.kt`, `DirectoryService.kt`, `SessionFilesService.kt`, `DeviceSessions.kt` and `WsConnection.kt`.

### Mobile and desktop

- ZCode name, color, glyph, tagline, compact badge and usage color.
- Agent choice in supported new-session and desktop Settings surfaces.
- Independent persisted default ZCode model.
- Modes map to ZCode `build`, `edit`, `plan` and `yolo`.
- ZCode models come only from the daemon's valid `provider/model` catalog.
- Loading, completed-empty and error states are distinct in the mobile model picker.
- Claude gateway URL, aliases, sections, presets and disclosure are Claude-only and cannot leak into ZCode.
- Unsupported/old daemons hide ZCode from both mobile and desktop entry points.

Primary files:

- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/AgentIdentity.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/SessionSheets.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/entry/ConfigureSessionSheet.kt`
- `mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/Popovers.kt`
- `mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/SettingsModal.kt`

### Handoff safety

- ZCode handoff creation is rejected before persistence with code `handoff_agent_unsupported`.
- Mobile and desktop hide the ZCode handoff entry.
- Handoff listings and updates are filtered per connection capability; no caps fails closed for ZCode.
- `CollaboratorGuard` also denies ZCode as defense in depth.

This is intentional. Do not “complete” issue #228 by weakening this boundary.

## Immediate P1: first prompt can outrun resumed settings

The interrupted final audit found a real ordering gap in the current `ZCodeBackend.response(openId, ...)` flow:

1. `session/resume` returns.
2. The backend writes `session/setModel`, `session/setMode`, and possibly starts the thought-level validation chain.
3. It immediately publishes `sessionId` and flushes the buffered first `session/send`.
4. `session/setModel` / `session/setThoughtLevel` responses may arrive only afterward.

The NDJSON writes are ordered, but the implementation has not proven that the app-server serializes the effects of those requests before accepting the prompt. A resumed conversation that changes model or effort may therefore run its first turn with stored/old settings.

Required fix:

- Introduce a small startup-settings barrier for resumed sessions.
- Do not flush the first queued prompt until all settings required for this open have either:
  - completed successfully, including the model-dependent thought-level validation/setter; or
  - failed/skipped through an explicit safe path.
- Include `setMode` in the barrier unless the official probe proves ordered application without waiting.
- Preserve ordinary FIFO behavior after startup.
- Preserve the startup-cancel invariant: a cancel received during startup remains deferred; once startup settles, the opening prompt is written before `session/stop`, so stop cancels the intended turn rather than an idle session.
- Do not block forever on a settings error. Consume the relevant error response, release the barrier safely, and surface/log enough context.
- Add tests that assert no `session/send` is written before the required setter responses for:
  - resume + changed model + explicit supported effort;
  - resume + changed model + unsupported effort;
  - resume + default-effort restoration;
  - resume + mode change;
  - startup cancel while the settings barrier is pending.

The interrupted thought-level patch itself is present and its focused tests pass. It currently:

- omits `thoughtLevel` from `session/create`;
- validates explicit effort against snapshot `enabled` + `available` before setting it;
- waits for a changed model's snapshot (with one `session/read` fallback);
- restores only a concrete advertised `defaultLevel` and never sends the invalid literal `default`;
- skips disabled, unknown or unsupported levels;
- prevents a global app default such as `xhigh` from flowing into ZCode without advertised capabilities.

Keep those invariants while adding the startup barrier.

## Verification state

Current, after the interrupted thought-level patch:

- `git diff --check`: PASS.
- `python3 scripts/probe-zcode-app-server.py --self-test`: PASS.
- Focused backend + app-default tests: PASS.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :daemon:test --tests 'dev.ccpocket.daemon.zcode.ZCodeBackendTest' \
  :mobile:composeApp:desktopTest --tests 'dev.ccpocket.app.data.DefaultModelTest' \
  --no-daemon --rerun-tasks
```

Result: `BUILD SUCCESSFUL in 36s`, 22 tasks executed.

Before the interrupted thought-level patch, the complete serial JVM/desktop suite passed:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :protocol:jvmTest :daemon:test :mobile:composeApp:desktopTest \
  --no-daemon --rerun-tasks
```

Result at that earlier snapshot: `BUILD SUCCESSFUL in 3m 29s`; 2,509 tests, zero failures/errors/skips.

This full result must not be presented as validation of the latest thought-level edits. After fixing the startup barrier, rerun the same full serial command and report the new result.

Probe commands already exercised:

```bash
python3 scripts/probe-zcode-app-server.py --self-test

python3 scripts/probe-zcode-app-server.py \
  --zcode-bin /tmp/cc-pocket-zcode-3.7.6/zcode.cjs \
  --node-bin /Users/lidapeng/.nvm/versions/node/v24.3.0/bin/node
```

The passive official-bundle probe passed CLI/envelope/method checks and did not create a session or call a model. A bounded active probe with a local fake Anthropic SSE provider proved the official runtime's session/event/permission/cancel chain. It did not prove a real Z.ai provider request.

## Unverified and unsupported boundaries

- This machine has no real ZCode login/model configuration. A real Z.ai/GLM provider turn remains unverified.
- Built-in Start Plan subscription is not supported from cc-pocket because its credentials are injected privately by the desktop host. API-key/custom-provider configuration is the intended standalone path, but a real provider call still needs manual verification on a configured machine.
- The ZCode app-server protocol is private and may drift. Re-run the probe on every ZCode upgrade.
- No local daemon was started, installed, updated or restarted during this implementation.
- Mobile device/iOS runtime UI has not been manually exercised for the new ZCode surfaces.

Do not turn these boundaries into claims of success. Report them explicitly.

## Completion checklist for Claude

1. Read `AGENTS.md`, this handoff, all ZCode package files and their focused tests.
2. Inspect the entire current diff before editing; preserve all current changes.
3. Fix the startup settings barrier and add the missing ordering/cancel tests.
4. Run the focused ZCode and compatibility tests while iterating.
5. Run `git diff --check`.
6. Run the full serial command shown above.
7. Re-run probe self-test; if the 3.7.6 temp bundle remains available, rerun the passive probe.
8. Audit the full diff for secrets, hard-coded `/tmp` paths in production, unknown-enum compatibility, and accidental changes to existing backends.
9. Do not start/update the daemon, commit, stage, push or clean the worktree.
10. In the completion report, separate proven behavior, inferred behavior and unverified real-provider behavior.

## Later Claude Design phase

Only after the implementation is green, use Claude Design to refine the visible ZCode integration. Treat the current UI as working implementation input, not an approved visual direction.

Design surfaces to inspect together:

- Mobile Configure Session agent/model/mode states.
- Mobile live-session ZCode badge and model-picker loading/empty/error states.
- Desktop New Session, Settings default agent/model and model popover.
- Four-agent layout at normal and 200% type, light/dark themes and narrow widths.
- ZCode unavailable on an old daemon.
- Handoff absent for ZCode.
- Start Plan unavailable/error copy without implying account access.

Design invariants:

- No ZCode-only approval model or navigation branch.
- No invented provider/account/plan status.
- Daemon capability is authoritative; unavailable means hidden or explicit unavailable state, never a clickable dead end.
- Model IDs remain literal `provider/model`; Claude gateway presets remain Claude-only.
- Preserve existing callbacks, permission semantics and compatibility gates.
- Archive a separate `DESIGN_BRIEF.md`, versioned Claude Design export and `README.md` before changing production UI.

