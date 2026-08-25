# Settings + Bridges UI 2.1 handoff

Implementation handoff for the two mobile surfaces corrected after UI 2.0:

- Settings landing: separate the two utility destinations from the five setting categories and give General a factual local-state summary.
- Bridges: separate identity, link/trust facts, actions, and diagnostics; wrap whole action controls so Chinese labels never stack per glyph.

## Source of truth

`Settings + Bridges Master v1.dc.html` is the canonical, self-contained implementation board. It was generated in the existing Claude Design project:

<https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Settings+%2B+Bridges+Master+v1.dc.html>

`Settings + Bridges Direction v1.dc.html` records the preceding direction study. The master supersedes it where they differ.

## Files

- `DESIGN_BRIEF.md` — repository truth, behavior boundaries, target states, and acceptance criteria.
- `Settings + Bridges Master v1.dc.html` — canonical responsive prototype, field map, geometry, and proof frames.
- `Settings + Bridges Direction v1.dc.html` — direction exploration and rationale.
- `SHA256SUMS` — archive integrity hashes.

## Locked implementation model

### Settings

- Keep Token usage and Scheduled tasks as two bare utility rows.
- Add one written Categories / 设置分类 label and one low container holding exactly five category rows.
- General shows appearance plus text size from existing local preferences. Other rows retain their repository scope copy.
- Preserve the paired-computer summary, review badge, routes, order, and category capability gates.

### Bridges

- Card zones are: name plus AccessTier; link state plus optional trust/session facts; hairline; whole action controls; optional subordinate diagnostics.
- Link state is derived only from `pendingTicket` and `online`. Runner state selects actions only.
- A running managed bridge offers Restart, Stop, Edit, Revoke. A stopped managed bridge offers Start, Edit, Revoke. An unmanaged bridge offers Revoke only.
- Action controls use an 8 dp FlowRow gap, single-line labels, a 96 × 48 dp minimum, and a 150 × 60 dp minimum at font scale 1.5 or greater.
- Revoke remains visible on every card. Existing empty, unavailable, error, merge-loss, edit-sheet, and revoke-sheet semantics remain unchanged.
- Device IDs, environment values, and full workdir paths are never rendered.

## Responsive proof

- 390 dp Chinese: four actions wrap 3 + 1.
- 320 dp stress width: four actions wrap 2 + 2.
- 200% text: actions use the larger minimum and wrap 2 + 2.
- `编辑` / `Edit` always remains one horizontal line.

## Boundaries

No protocol, relay, daemon, or desktop changes. No invented data, state, recovery action, route, or backend capability.
