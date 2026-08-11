# Settings + Bridges UI 2.1 — Design brief

## Why this pass exists

Two mobile supporting surfaces visibly drifted after the UI 2.0 redesign:

1. The Settings landing is functionally complete but reads as one long, flat list. The two utility destinations and the five setting categories have no strong grouping, while long static subtitles make the page feel scattered.
2. On the mobile **Bridges / 机器人桥接** screen, a managed bridge puts status, trust state, and all runner actions in one horizontal row. On a narrow Chinese screen the `编辑` button is compressed until Compose wraps it as `编` over `辑`.

This is a hierarchy and responsive-layout correction, not a new product direction.

## Existing source of truth

- Claude Design project: `cc-pocket Design System 2.0`
- Project URL: <https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Supporting+Surfaces+Master+v1.dc.html>
- Existing master: `Supporting Surfaces Master v1.dc.html`
- Existing local handoff: `docs/design/claude-design-handoff/supporting-surfaces-ui-2.0/`

Extend this project. Do not create an unrelated visual language.

## Product truth from the repository

### Settings landing

The landing owns exactly two utility destinations:

- Token usage
- Scheduled tasks

It also owns exactly five category destinations:

- General / 通用
- Agent & session defaults / Agent 与会话默认项
- Connections & collaboration / 连接与协作
- Security & approvals / 安全与审批
- Support & about / 帮助与关于

The current paired computer name may appear as the header summary. A pending review count may appear beside Connections & collaboration when it is greater than zero.

Every existing destination and control must remain reachable. The pure map in `SettingsIA.kt` and its test are the coverage contract. Do not add a new route or move a control between categories in this pass.

### Bridges screen

Each `BridgeInfo` can truthfully supply:

- name
- tier
- workdir basenames
- pending-ticket, online, or offline state
- active session count
- optional managed runner state
- for a managed runner: running/stopped, no-approval flag, exit code, last error, log tail, env-key names

The phone may revoke a bridge. A managed runner may be started, stopped, restarted, or edited. An unmanaged bridge has no runner controls. Existing unavailable, empty, error, merge-loss, expanded-detail, edit-sheet, and revoke-sheet states must remain.

No protocol, daemon, or desktop behavior changes are permitted.

## Direction constraints

### Shared visual language

- Continue UI 2.0: paper-like base, low-container rows, 20 pt horizontal gutter, restrained orange accent, hairline dividers, written state beside any status mark.
- Use hierarchy, spacing, section labels, and typography before adding containers.
- Avoid nested card stacks and decorative dashboard chrome.
- Keep a 44 pt minimum touch target; 48 pt is preferred for consequential bridge actions.
- Rows and action groups grow or wrap structurally at 200% text. No control label may shrink, clip, or wrap one glyph per line.

### Settings correction

- Make the two utilities and five categories visibly distinct groups.
- Keep the landing calm and scan-friendly. The category titles are the primary information; supporting copy must be concise.
- A supporting line may show current values only when they already exist as reliable local repository state. Otherwise use a short scope description or omit the line. Never invent counts, usage periods, update availability, model names, connection health, or settings values.
- The fifth category may fall below the fold. Do not compress rows to force everything into one screen.
- Preserve the large Settings title and factual paired-computer summary.

### Bridges correction

- Separate identity, status/trust information, and runner actions into distinct layout zones.
- Status and `可设信任模式` are informational; Start/Stop/Restart/Edit are actions and should not compete for the same line.
- Action buttons wrap as whole controls onto another row when width or text scale demands it. Their labels are always single-line (`编辑`, `Edit`, `Restart`, etc.).
- Preserve the destructive Revoke action without making it the visual primary action.
- Make collapsed cards easy to scan; expanded diagnostics remain subordinate and readable.
- Align the screen chrome with the supporting-surface family where this can be done without changing navigation.

## Required prototype states

Use one live responsive component rather than disconnected pictures.

1. Settings landing, dark, English, 402 × 874.
2. Settings landing, light, Simplified Chinese, 390 × 844.
3. Settings landing at 200% text, 402 × 874; rows grow and scroll.
4. Bridges, dark, Simplified Chinese, 390 × 844, managed runner running, `可设信任模式`, active sessions, and `重启 / 停止 / 编辑` all visible without per-character wrapping.
5. Bridges at 320 pt content stress width and/or 200% text; action controls wrap as whole buttons.
6. Bridges managed runner stopped (`启动 / 编辑`).
7. Bridges unmanaged runner (no runner actions).
8. One expanded bridge with projects and diagnostics.
9. Empty and unavailable states.

Prototype interactions should cover:

- Settings landing → Connections & collaboration → Bridges → Back.
- Expand/collapse a bridge card.
- Open and close Edit and Revoke surfaces.
- Toggle English/Chinese, dark/light, and 100%/200% text.

## Implementation anchors

- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/Settings.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/SettingsIA.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/FirstHop.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/bridge/BridgesScreen.kt`
- `mobile/composeApp/src/commonMain/composeResources/values/strings.xml`
- `mobile/composeApp/src/commonMain/composeResources/values-zh/strings.xml`

Prefer the existing `FirstHopHeader`, `FirstHopSectionLabel`, `FirstHopRow`, theme tokens, and Compose `FlowRow` where they fit. Do not introduce a parallel token system.

## Acceptance criteria

- Settings has an unmistakable utility/category hierarchy while retaining exactly two utilities and five categories.
- All Settings IA destinations remain covered by the existing contract tests.
- At 390 pt Chinese and at the narrow stress case, `编辑` is horizontal and intact.
- At 200% text, every bridge action remains a single-line control and whole controls wrap to subsequent rows.
- Managed/unmanaged and running/stopped action availability remains truthful.
- No new backend call, protocol field, state inference, secret, absolute path, or fake metric is introduced.
- Light/dark, English/Chinese, screen reader labels, and back navigation remain intact.
