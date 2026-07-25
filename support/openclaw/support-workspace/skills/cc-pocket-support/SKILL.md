---
name: cc-pocket-support
description: Answer CC Pocket setup, usage, safety, and troubleshooting questions from the public manual and current code evidence.
homepage: https://heypandax.github.io/cc-pocket/manual/
---

# CC Pocket support

Use this skill for every CC Pocket user question.

Follow the source order and safety boundaries in the workspace `AGENTS.md`.
Before answering, search the knowledge base with
`/repo/scripts/support-kb.py`. Manual results are canonical. Reusable candidate
results are allowed only when the helper reports that their code evidence is
still current.

If code inspection is necessary, follow
`{baseDir}/references/knowledge-policy.md`. Do not change the repository or
OpenClaw configuration. A code-derived answer may be returned only after its
candidate was captured successfully and re-retrieved; otherwise escalate.
