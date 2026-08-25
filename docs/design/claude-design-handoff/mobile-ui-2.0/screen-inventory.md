# Mobile UI 2.0 screen inventory

This inventory maps production surfaces to real code and sets migration priority. It is intentionally route/state oriented rather than a gallery of page names.

## P0 — core task and safety loop

| ID | Surface | Current production entry | Primary task | Required state coverage | Design status |
|---|---|---|---|---|---|
| ROOT-01 | Root connection gate | `ui/App.kt` root `App` | Reach the correct computer/project/session | unpaired, connecting, ready, reconnecting, offline, auth/error | Existing behavior; new shell pending |
| PRJ-01 | Directory / project picker | `DirectoryScreen` | Choose workdir | loading, recents, browse, empty, permission/error | Inventory only |
| SES-01 | Sessions | `SessionsScreen` | Resume or create session | empty, active, approval, answer, failed, running, complete, refreshing | Implemented from Core/Proofs; 402 × 874 dark/light proofs pass; real-device QA pending |
| SES-02 | New-session configuration | `StartSessionModeSheet` | Choose agent/model/permission | capability absence, loading models, duplicate-tap guard | Existing design; UI 2.0 mapping pending |
| CHAT-01 | Chat / work stream | `ChatScreen` | Monitor work and send instructions | streaming, queued, stalled, history paging, offline, observer, error | Implemented from Core/Proofs; 402 × 874 and 200% type proofs pass; real-device QA pending |
| CHAT-02 | Tool/message cards | `MessageItem` and specialist cards | Inspect agent action/result | command, diff, success, failure, truncation, long content | Generic User/Agent/Tool grammar implemented; specialist-card proof expansion pending |
| QST-01 | QuestionCard | in-chat `QuestionCard` | Answer agent question | one/many questions, selection, free text, withdrawal | Existing behavior; must remain outside Approval |
| APR-01 | Secure Approval | `ui/approval/SecureApprovalSheet` | Inspect and explicitly decide | V2, legacy, one-off, danger, queue, risk, noAutoDeny, timeout, withdrawal | Implemented; simulator proofs pass, device accessibility/safe-area QA pending |
| CONN-01 | Pairing / device connection | `PairingScreen`, `ConnectScreen` | Pair and recover connection | numeric code, camera unavailable, error, expired, offline | Production design pending |

## P1 — daily efficiency

| ID | Surface | Current production entry | Primary task | Design status |
|---|---|---|---|---|
| SES-03 | Session groups and row actions | `SessionSheets.kt`, Sessions row menus | Organize/archive/rename/move | Reuse Sessions canonical components |
| SES-04 | Archived sessions | `ArchivedSessionsScreen` | Find and restore archived work | Production design pending |
| SES-05 | Session switcher | `SessionSwitcher.kt` | Switch without losing context | Existing specialist design; migrate after Chat |
| CHAT-03 | Composer + attachments | Chat composer, media attachers | Send text/files/images/voice | Production proof pending |
| CHAT-04 | Model and permission controls | `ModelPicker`, mode sheets | Adjust execution behavior | Existing specialist design; UI 2.0 mapping pending |
| FLEET-01 | Machine fleet | `FleetHomeScreen` | See machines and intervention counts | Production design pending |
| FLEET-02 | Attention inbox | `AttentionInboxScreen` | Find pending approvals/questions | Must not hide intervention behind filters |
| TERM-01 | Terminal | `TerminalScreen` | Run/inspect direct shell work | Existing specialist design |
| FILE-01 | Changed files / file viewer | `FileViewerScreen` | Inspect output and diffs | Existing specialist design |
| FLOW-01 | Workflow run | `WorkflowRunScreen` | Inspect multi-agent work | Existing specialist design |
| HAND-01 | Session handoff | handoff screens under `ui/handoff` | Send/accept/review work | Existing specialist handoff |

## P2 — support and administration

| ID | Surface | Current production entry | Primary task | Design status |
|---|---|---|---|---|
| SET-01 | Settings | `SettingsScreen` | Configure appearance, agent and permissions | Production design pending |
| HELP-01 | Help center | `HelpCenterScreen` | Recover from unfamiliar flows | Existing implementation |
| USE-01 | Usage | `UsageScreen` | Inspect token/cost usage | Existing specialist design |
| SHARE-01 | Folder sharing | screens under `ui/share` | Share and manage folders | Existing specialist design |
| REVIEW-01 | Review center | screens under `ui/review` | Review collaboration output | Existing specialist design |
| LOCK-01 | App lock | root lock controller/screens | Protect local access | Existing specialist design |

## Coverage gate

Before a module is marked design-complete:

- Every real entry, sheet/dialog and terminal async state maps to an ID above or explicitly reuses a canonical component.
- Every state names its source, render condition, priority, allowed actions and ending event.
- The design uses real fixture shapes and missing-data behavior.
- One module owns one `Master + Proofs` set; parallel “latest/final/v2-final” variants are not implementation inputs.
