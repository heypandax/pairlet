# cc-pocket Mobile UI 2.0 — Design Brief

## Objective

Turn the approved **Direction A · Native Professional Utility** into the production mobile UI without reopening the visual direction or inventing client-side product facts. The first implementation slice is Secure Approval; Sessions and Chat remain the next two canonical surfaces.

Success means the user can quickly answer:

1. Which computer, project, session and agent am I controlling?
2. Is the agent running, waiting for an answer, waiting for approval, failed or complete?
3. For an approval: what exact tool and payload will run, what risk evidence exists, and which scopes are actually available?

## Product and implementation baseline

- Product: a phone companion that controls a local coding agent through the cc-pocket daemon and E2E relay.
- Production UI: Compose Multiplatform in `mobile/composeApp/src/commonMain`.
- State and behavior truth: `protocol/`, `PocketRepository`, daemon events and existing tests.
- Visual truth: the online Claude Design project and the exported files in this directory.
- Primary release frame: iPhone 17, **402 × 874 pt**. Taller devices expand scrollable content; smaller phones are not a V1 release gate.
- Dark first, complete light theme; system UI font; monospace only for code, command, path, branch, IDs and counts.
- Keep the current repository-driven root routing while surfaces migrate. Do not introduce a second navigation system during the visual refactor.

## Canonical hierarchy

```text
Computer
└── Project / workdir
    └── Session
        ├── Chat / work stream
        ├── QuestionCard
        └── Secure Approval
```

Settings, diagnostics, archive, fleet and sharing are supporting spaces and must not compete with this hierarchy.

## Locked visual principles

- Content-first, low-container layout; hierarchy comes from typography, hairlines and spacing rather than nested cards.
- Terracotta is the product accent; semantic success, warning and danger colors retain their meanings.
- Minimum interactive target is 48 dp for the new core surfaces.
- Long technical content wraps or scrolls inside a bounded region; it is never represented by a misleading partial command.
- One semantic component has one canonical owner. New variants are state/props, not copied components.
- No duration or event timestamp is shown unless the corresponding authoritative field exists.

## Secure Approval information architecture

`SecureApprovalSheet` is not a generic `PocketSheet`. It owns a security presentation contract:

1. Pinned header: state, optional queue, countdown or waiting state.
2. Scroll body: title, tool, risk evidence when available, literal payload and workdir.
3. Pinned decision region: only actions the current request can actually honor.

It has no grabber, no swipe-to-dismiss, no scrim dismissal and no system-back dismissal. Scrim and back resolve nothing. Explicit decision, authoritative withdrawal/session close, or authoritative timeout end the request.

### Selection pipeline

1. `ask.isQuestion` routes to the in-chat `QuestionCard`; no approval sheet is created.
2. Only `AskWithdrawn(TIMED_OUT)` creates `TimeoutTerminal`. Ordinary withdrawal/session close removes the sheet and advances the queue.
3. Decision family:
   - `ask.oneOff || handoffReviewShell`: Deny + Allow once only.
   - `grantOptions != null`: V2 family. Deny, Allow once and Retry safer are available; task/session actions are rendered only when their corresponding capability is offered.
   - `grantOptions == null`: legacy family. Deny, Allow once and Always allow, with the legacy 30-second fallback when `timeoutSec` is absent.
4. `noAutoDeny`, `danger`, queue and risk are independent modifiers. They do not change the family.

### Field-to-copy mapping

| Visible information | Authoritative source | Missing behavior |
|---|---|---|
| Title | `PermissionAsk.title` | Promote the fixed kind/tool line; never summarize the command |
| Tool | `PermissionAsk.tool` | Required |
| Payload | `inputPreview` or `diff` | Omit the block; never invent a paraphrase |
| Danger explanation | `danger` + `dangerNote` | Keep danger emphasis; omit absent note |
| Risk | full `PermissionRiskUpdated` | Omit the entire assessment; absence is not low risk |
| Project | current session `workdir` | Omit the row |
| Queue | repository position + total | Omit unless both are known and total > 1 |
| Timer | `timeoutSec`, legacy fallback, `noAutoDeny` | `noAutoDeny` shows waiting with no number |
| Persistent scope label | `grantOptions` + `rule` | Omit unoffered scope; keep label without sublabel if rule absent |

Never render `consequenceSummary`, Effects, Recommendation or a pre-decision permission-duration fact: these are not V1 protocol fields.

## Accepted implementation deltas from the exported prototype

These are deliberate convergence decisions, not invitations for another design iteration:

1. Capability controls action availability; it is not a recommendation. An ordinary V2 request must not fill “Allow for task” merely because task scope exists. Default actions remain neutral unless a real product security policy provides emphasis.
2. `noAutoDeny` says “Waiting for you / No response countdown”. Do not render `∞`; the daemon has a bounded renewal policy, not a literal infinite promise.
3. Use `PermissionAsk.oneOff`, which includes legacy `ExitPlanMode` fallback, rather than testing only `neverRemember`.
4. Keep current mixed-version timeout compatibility: a grant-aware local countdown reaching zero is only a display floor until the daemon signal; legacy 30-second display may reach its terminal presentation but must not send a client-generated deny verdict.
5. At that grant-aware display floor, replace `0s` and the auto-deny sentence with an explicit “still active on the computer” state. Decisions remain available until the daemon renews or withdraws the request.
6. All approval variants share one 94.5%-height shell at the 402 × 874 baseline. Header and decisions are pinned; only the evidence body grows or scrolls.
7. The custom overlay declares dialog semantics, and its host clears semantics from the covered application tree while approval is visible.

## Core states to verify

- Ordinary V2, task scope offered, session scope offered.
- V2 with no task scope and with unknown future flags.
- Danger request with and without `dangerNote`.
- Legacy peer with missing timeout/risk/queue.
- One-off request and review-shell request.
- `noAutoDeny` combined with V2 and danger.
- Risk absent, then arriving asynchronously without resetting countdown or decisions.
- Queue absent, 1 of 1 (hidden), and n of m.
- Long command, long diff, long path and 200% type.
- Timed-out withdrawal, ordinary withdrawal and session close.

## Interaction acceptance path

1. Open a session with a pending ordinary V2 ask.
2. Confirm exact title/tool/payload/workdir and only offered scopes render.
3. Tap More options only when session scope is offered.
4. Reopen with danger and verify action availability is unchanged while emphasis becomes least-privilege.
5. Reopen with `noAutoDeny`; verify no countdown and no infinity symbol.
6. Press system back, tap scrim and attempt downward drag; verify no decision and no dismissal.
7. Deliver a risk update in place; verify header/body positions and decision family do not change.
8. Deliver ordinary withdrawal; verify the sheet disappears and queue advances.
9. Deliver `TIMED_OUT`; verify decisions are replaced by read-only outcome and Dismiss.

## Non-goals for the first slice

- No daemon, relay or serialized wire-shape changes.
- No desktop visual redesign; desktop may share pure presentation classification only.
- No new global navigation framework.
- No Sessions or Chat visual implementation in the Secure Approval slice.
- No direct reuse of exported HTML/CSS/JavaScript in production.
