# CC Pocket support knowledge base

The public user manual is the canonical source. This directory holds only
code-backed candidate answers and separate review records.

Lifecycle:

`observed → externally verified → manual`, with `stale`, `needs_changes`, and
`rejected` outcomes.

- `observed`: captured from current code with file, line, commit, and content
  hashes. It may be reused provisionally while every cited excerpt is current.
- `verified`: a stronger reviewer model confirmed the answer against current
  code. The verdict is stored separately under `reviews/` and is bound to the
  candidate's complete SHA-256, so the public support agent cannot self-verify
  or alter a reviewed answer without invalidating the verdict.
- `manual`: represented by `site/manual/manual-content.json`, not duplicated
  here. Promotion always goes through a maintainer-reviewed repository change.
- `stale`: one or more cited excerpts changed or disappeared. It is excluded
  from retrieval immediately.
- `rejected`: the evidence did not support the answer.

Runtime OpenClaw deployments use two filesystem trust zones:

- the public support agent can write only the candidate inbox and reads the
  governance root;
- the reviewer reads the candidate inbox and can write only the governance
  root containing verdicts and promotion proposals.

Pass the repository KB, runtime candidate inbox, and runtime governance root to
`scripts/support-kb.py search`.
