# CC Pocket support knowledge base

The public user manual is the canonical source. This directory holds only
code-backed candidate answers that are waiting for, or have passed, review.

Lifecycle:

`observed → verified → manual`, with `stale` and `rejected` as terminal review
states.

- `observed`: captured from current code with file, line, commit, and content
  hashes. It may be reused provisionally while every cited excerpt is current.
- `verified`: a stronger reviewer model confirmed the answer against current
  code. It is still not part of the public manual.
- `manual`: represented by `site/manual/manual-content.json`, not duplicated
  here. Promotion always goes through a maintainer-reviewed repository change.
- `stale`: one or more cited excerpts changed or disappeared. It is excluded
  from retrieval immediately.
- `rejected`: the evidence did not support the answer.

Runtime OpenClaw deployments should keep their writable queue outside the Git
checkout and pass both KB roots to `scripts/support-kb.py search`: this
repository directory plus the runtime queue.
