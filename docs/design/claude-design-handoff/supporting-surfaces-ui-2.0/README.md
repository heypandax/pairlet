# Supporting Surfaces UI 2.0 handoff

Implementation handoff for the three first-hop destinations opened from the Mobile UI 2.0 Projects header:

- P1 — Fleet / Attention
- P2 — Review Center
- P3 — Settings

## Source of truth

`Supporting Surfaces Master v1.dc.html` is the implementation source of truth. It is a self-contained, offline-openable HTML file: the shared device component and the required React/runtime code are embedded in the document, with no external stylesheet or script tag.

The master was generated in the existing Claude Design project:

<https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Supporting+Surfaces+Master+v1.dc.html>

`Supporting Surfaces Direction v1.dc.html` records the preceding direction study. It is useful for rationale, but the master supersedes two drift defects found during review:

1. Fleet has exactly three machine states: online, reconnecting, and offline. The direction study's extra “No state reported” state is not implementable.
2. Fleet exposes a coarse OS enum, not an OS version. Version strings such as “macOS 15.3” and “Ubuntu 24.04” must not appear.

## Files

- `DESIGN_BRIEF.md` — real product inputs, state matrix, behavior boundaries, non-goals, and acceptance criteria.
- `Supporting Surfaces Master v1.dc.html` — canonical interactive board and proof frames.
- `SupportingDevice.dc.html` — the shared live device component, also made independently offline-openable.
- `Supporting Surfaces Direction v1.dc.html` — direction and rationale board; non-canonical where it conflicts with the two corrections above.
- `SHA256SUMS` — archive integrity hashes.

## Locked interaction model

### Fleet / Attention

- Header: back, large localized title, factual computer/online summary.
- Optional attention strip appears only for real pending requests.
- Machine rows use name, coarse OS when present, one of the three written statuses, activity/path or non-online `lastSeen` fallback, pending count, and current marker.
- Selecting a machine preserves the existing switch behavior.
- Attention remains pending-first; a request opens its existing detail region before Allow or Deny.

### Review Center

- Inbox, Sent, and Contacts remain the only top-level tabs.
- Protocol statuses remain exactly Queued, Delivered, In progress, Responded, and Closed.
- Non-ready states do not reuse ready-state counts. Loading, empty, offline, unsupported, and error each keep factual copy.
- Received/sent detail, new review, invite, join, fingerprint, and remove-contact remain reachable and semantically unchanged.

### Settings

- The landing becomes a short IA page: optional computer summary, Token usage, Scheduled tasks, then five categories.
- Categories: General; Agent & session defaults; Connections & collaboration; Security & approvals; Support & about.
- Existing controls and capability gates move into category drill-downs; no setting is removed.
- Existing full-screen Usage, Schedule, Shared Folders, Collaborators, Bridges, Review Center, and Help surfaces are linked unchanged.

## Implementation boundaries

- No protocol, relay, daemon, or desktop changes.
- No new field, metric, health score, delivery inference, OS version, secret summary, or global navigation.
- No redesign of Share/Handoff, Archive, Workflow, Usage detail, Schedule detail, or Help content.
- English and Simplified Chinese resources are required for all fixed grammar.
- State is not color-only; targets are at least 44 pt; rows grow at 200% text rather than clipping or shrinking.

## Verification performed

- Claude Design's one-pass visual self-check ran at the real 402 × 874 device width.
- The master corrected the unsupported-state summary so it cannot assert a pending count without ready protocol data.
- The archived master was served from localhost and rendered the board plus live Computers, Review Center, and Settings component instances.
- The local document exposes no external `<script src>` or stylesheet dependency; fonts fall back to system sans/monospace families offline.

