# Mobile UI 2.0 supersession map

## Canonical assets

| Scope | Canonical source | Status |
|---|---|---|
| Product direction | `Direction Study Core 3` → Direction A | Decision record; locked |
| Foundations | `A Master Core v1` foundations strip | Seed; extract into reusable design/code tokens |
| Sessions | `A Master Core v1` + corresponding Proofs frame | Canonical |
| Chat | `A Master Core v1` + corresponding Proofs frame | Canonical |
| Approval | `Approval Protocol Handoff v1` | Canonical; highest visual authority for Approval |
| 402 × 874 behavior | `Approval Protocol Handoff v1` | Canonical V1 release baseline |
| Dynamic Type | `A Master Proofs v1` | Separate QA pass; not a smaller-phone release gate |

## Superseded content

The following remains useful history but must not be implemented:

- Approval frames in `A Master Core v1` and `A Master Proofs v1` wherever they conflict with the Approval handoff.
- `docs/design/claude-design-handoff/approval-v2/` as an Approval visual source. It contains earlier recommendation/effects assumptions.
- Earlier V1/V2/V3 Sessions explorations and all B/C direction frames.
- The previous Claude Design project (`93b56700-6ed2-46c9-bf81-3fd0b1a6340b`) as a component source.
- Any old Approval treatment that uses `PocketSheet`, a grabber, swipe dismissal, scrim dismissal or system-back dismissal.

## Removed Approval concepts

- `consequenceSummary`
- Effects list derived by the client
- Recommendation/reason block
- Pre-decision “Permission · This task only” fact
- Empty placeholders for missing risk, queue or project data
- Client-authored safety summaries

## Archive rule

Historical assets are not deleted. When referenced in future work, label them:

```text
ARCHIVED · NOT FOR IMPLEMENTATION · superseded by Mobile UI 2.0 / <canonical source>
```

Core may be reopened only when a real protocol/implementation constraint makes it unimplementable or a reproducible user test shows the core task fails. Aesthetic exploration alone does not reopen it.
