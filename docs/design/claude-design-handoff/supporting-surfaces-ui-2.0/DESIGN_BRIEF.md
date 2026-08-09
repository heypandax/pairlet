# CC Pocket Mobile UI 2.0 — Supporting Surfaces

## 1. Goal

Complete the three first-hop destinations exposed by the Projects header:

1. **P1 — Fleet / Attention**: make computer status and pending approvals readable at a glance.
2. **P2 — Review Center**: bring the existing review workflow into the Mobile UI 2.0 hierarchy without changing delivery semantics.
3. **P3 — Settings**: replace the single long control sheet with a calm category landing and focused drill-down pages.

This is a visual and information-architecture refinement of existing behavior. It must feel like the same product as Projects, Sessions, Chat, Secure Approval, Help, and Changed Files.

## 2. Product and implementation context

- Product: a phone companion that securely drives Codex/Claude sessions running on paired computers.
- Mobile stack: Kotlin Compose Multiplatform.
- Baseline viewport: **iPhone 17 standard, 402 × 874 pt**. Larger phones gain space; smaller targets are out of scope for this pass.
- Required proof modes: dark and light appearance, English and Simplified Chinese, 200% text scaling.
- Existing visual language: low-container surfaces, strong type hierarchy, restrained orange accent, semantic status colors, monospaced text only for paths/commands/technical identifiers.
- Existing navigation entry points remain unchanged. These screens are pushed from Projects and return with a normal back action.

Relevant implementation files:

- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/fleet/FleetHome.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/fleet/AttentionInbox.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/fleet/FleetModel.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/review/ReviewRoute.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/review/ReviewScreens.kt`
- `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/Settings.kt`

## 3. Real data model and copy mapping

### Fleet / Attention

Use only fields already supplied by the live repository:

- `FleetMachine`: `accountId`, `name`, `os`, `status`, `activity`, `lastSeen`, `pending`, `current`.
- `MachineStatus`: online, reconnecting, offline.
- `AttentionEntry`: request id, computer identity, OS, tool, title, preview, elapsed seconds, current flag, conversation/workdir/session/origin routing fields.
- `FinishedEntry`: computer name, OS, title, success/failure, relative completion time.

Fixed grammatical labels must use resources in both languages. Computer names, paths, tool names, model names, IDs, and user-generated titles remain literal. Do not concatenate English status sentences into a localized UI model.

### Review Center

Use the existing `ReviewCenterState(sent, received, contacts, loading, unsupported, offline, error)` and `ReviewStatus` values. Never infer delivery from elapsed time and never make queued look delivered.

The three existing destinations remain:

- Inbox / 待我评审
- Sent / 我发出的
- Contacts / 评审联系人

Existing detail and creation flows remain reachable: received detail, sent detail, new review, invite, join, fingerprint verification, remove contact.

### Settings

All existing controls and capability gates remain. Current values shown as row summaries must come from repository/settings state; do not invent account, notification, security, or model status.

Top utilities remain directly reachable:

- Token usage
- Scheduled tasks

## 4. P1 — Fleet / Attention design

### Fleet landing hierarchy

1. Standard back affordance.
2. Large localized title: “Computers” / “电脑”.
3. One-line fleet summary, e.g. “1 computer · 1 online”; pluralization/localization must be real.
4. If approvals require attention, place a compact semantic attention strip before the computer list. It states the count and opens the existing Attention Inbox.
5. Section label and machine rows.
6. “Pair a new computer” as a full-width row after the list.

### Machine row

- Minimum 56 pt row, preferably 68–76 pt with two or three readable lines.
- Primary: OS icon + computer name + “current” label where applicable.
- Secondary: status written in words; status must not rely on color alone.
- Tertiary: real activity/path or last-seen information, truncated safely.
- Pending approval count is a semantic badge/action, not merely an orange dot.
- Entire row switches to that computer using the current behavior.
- Online, reconnecting, and offline have distinct written labels and restrained icon/color treatment.

### Attention Inbox

- Large title and summary count.
- Pending requests first; recently finished history is visually secondary.
- Each request identifies computer, tool, request title/preview, and elapsed time with safe truncation.
- Preserve the existing allow/deny behavior and routing. Full request details must remain reviewable before a consequential decision; do not hide commands, paths, or risk details.
- Empty, loading, disconnected, and recently-finished-only states need explicit designs.

## 5. P2 — Review Center design

### Center hierarchy

1. Standard back affordance.
2. Large title aligned with other Mobile UI 2.0 first-hop screens.
3. Compact three-tab control with labels that remain readable at 200% text. At large text it may wrap or become a horizontally safe control; it must not clip.
4. Content begins close enough to the selector to read as one task surface.

### Inbox

- Pending requests are the primary content.
- Rows show sender, title, honest status/time metadata, and a clear disclosure affordance.
- Empty state explains what will appear without turning into an oversized promotional block.
- Offline, unsupported, loading, and error states are visibly different and keep truthful retry/action behavior.

### Sent

- Rows show recipient, title, and the exact protocol-backed status.
- Queued, delivered, in progress, responded, and closed must be distinguishable by text, not color alone.
- Existing new-review action stays available and reachable.

### Contacts

- Existing invite and join actions remain.
- Contacts show verified identity/fingerprint state only when backed by real data.
- Remove-contact remains a deliberate destructive action in the detail flow.

## 6. P3 — Settings information architecture

The current page is not a flat catalogue anymore. The landing page contains a short status header, two utility rows, and five category rows. Categories open local Compose subpages; they do not require new protocol messages or a global navigation framework.

### Landing

1. Standard back affordance and large title.
2. Optional paired-computer summary using existing connection data.
3. Utility rows:
   - Token usage
   - Scheduled tasks
4. Category rows with a short, factual summary:
   - **General / 通用** — appearance, text size, notifications, voice/input preferences.
   - **Agent & session defaults / Agent 与会话默认项** — default agent, model, effort, permission mode, fast mode, context window, model overrides, agent filter.
   - **Connections & collaboration / 连接与协作** — paired computers, shared folders, collaborators, reviews, bridges.
   - **Security & approvals / 安全与审批** — approval timeout/manual behavior and full-control expiry/security controls.
   - **Support & about / 帮助与关于** — help/manual, troubleshooting, app/daemon versions, about, exit/unpair where currently applicable.

### Drill-down rules

- Reuse the current controls and state bindings; regroup rather than rewrite semantics.
- Each subpage has a standard back action and a clear localized title.
- Keep current capability gating, disabled explanations, confirmation dialogs, and destructive-action treatment.
- Complex existing full-screen routes (Usage, Schedule, Shared Folders, Collaborators, Bridges, Review Center, Help) stay full-screen and are linked from the appropriate category.
- Avoid nested card stacks. Use section headers, dividers, and row groups; reserve filled containers for status/warning blocks.
- Summaries on the landing must remain stable at 200% text and must never expose secrets such as API tokens.

## 7. Interaction and accessibility requirements

- Minimum target size: 44 × 44 pt; primary rows should be at least 48 pt.
- Back actions are predictable and do not discard changes silently.
- Focus/selected/disabled states are distinct in light and dark modes.
- Important state is conveyed with text/icon shape as well as color.
- Dynamic type at 200% may increase page height; content scrolls without overlapping the home indicator or top safe area.
- Chinese copy is product copy, not machine-like literal translation. English copy uses sentence case.
- VoiceOver semantics: row title, current value/status, and action/disclosure should read in a useful order.

## 8. Required proof frames

Produce one interactive self-contained master deliverable plus a compact proof board covering:

1. Fleet: one current online computer, multiple mixed-status computers, approval attention, offline/empty.
2. Attention: pending + finished, empty, disconnected.
3. Review: inbox empty, inbox populated, sent with multiple honest statuses, contacts, offline/unsupported/error.
4. Settings: landing, each of the five category pages, at least one capability-disabled example.
5. Dark + light, English + Chinese, 200% text proofs for the highest-risk layouts.

## 9. Non-goals and anti-drift boundaries

- No protocol or relay changes.
- No new product facts, aggregate metrics, delivery claims, or approval outcomes.
- No redesign of Share/Handoff, Archive, Workflow, Usage details, Schedule details, Help content, or Review protocol.
- No bottom navigation or new global navigation model.
- No desktop UI changes.
- No ornamental dashboards, charts, gradients, glass effects, or oversized empty cards.
- Do not remove any currently reachable setting or review action.
- Do not rename technical/user-generated values.

## 10. Acceptance criteria

- The three destinations visually belong to Mobile UI 2.0 and are clearly related to the Projects header entries.
- Fleet is fully localized and understandable without color.
- Review state is protocol-honest in every tab and failure state.
- Settings landing fits the core categories within roughly one iPhone 17 viewport; all existing settings remain reachable within at most one additional category drill-down, apart from existing full-screen detail flows.
- No clipped text or targets below 44 pt in the required proof modes.
- Existing repository behavior is preserved, new localization has English and Chinese resources, and implementation includes focused regression/render tests.
