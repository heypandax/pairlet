# CC Pocket support knowledge base

The public user manual is the canonical source. This directory holds only
code-backed candidate answers and separate review records.

Lifecycle:

`observed → externally verified → manual`, with `stale`, `needs_changes`, and
`rejected` outcomes.

- `observed`: captured from current code with file, line, commit, and content
  hashes. It may be reused provisionally while every cited excerpt is current.
- `verified`: an independent reviewer confirmed the answer against current
  code. Routine reviews make a current answer reusable; a separate
  `promotion` review from the configured strong-model workflow is required
  before a manual proposal can be generated. Verdicts are stored separately
  under `reviews/` and bound to the candidate's complete SHA-256, so the public
  support agent cannot self-verify or alter a reviewed answer without
  invalidating the verdict.
- `manual`: represented by `site/manual/manual-content.json`, not duplicated
  here. Promotion always goes through a maintainer-reviewed repository change.
- `stale`: one or more cited excerpts changed or disappeared. It is excluded
  from retrieval immediately.
- `rejected`: the evidence did not support the answer.

Runtime OpenClaw deployments use two filesystem trust zones:

- the public support agent can write only the candidate inbox and reads the
  governance root;
- the routine reviewer reads the candidate inbox and can write only the
  governance root containing preliminary verdicts;
- the separate strong-model operator workflow records promotion-tier verdicts
  and creates promotion proposals.
- when a verified candidate is incorporated into a canonical manual article,
  add its ID to that article's `sourceCandidateIds`; search then retires the
  runtime candidate automatically so users see only the manual answer.

Pass the repository KB, runtime candidate inbox, and runtime governance root to
`scripts/support-kb.py search`.
