# Knowledge policy

## Trust levels

1. `manual / canonical`: safe to answer and cite directly.
2. `candidate / verified`: reviewed against current code; cite as code-backed
   until a manual page exists.
3. `candidate / observed`: provisionally reusable only while every captured
   excerpt hash is current. Label it pending manual review.
4. missing, stale, rejected, or unsupported: do not reuse.

## Candidate quality bar

Capture only a durable product fact that is likely to answer the same question
again. Do not capture:

- user-specific state or secrets;
- transient outages, rate limits, or local environment failures;
- speculation, design-only behavior, or unsupported negative claims;
- generic advice that does not depend on CC Pocket;
- large log excerpts or full conversations.

Good evidence identifies the runtime behavior, the user-facing label or route,
and a relevant test when available.

## Manual promotion

Only a candidate with a separate promotion-tier strong-model review may
generate a promotion proposal. No OpenClaw agent edits the manual directly. A
maintainer must review the current code, bilingual wording, generated pages,
and tests through the normal repository workflow.
