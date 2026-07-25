# Runtime paths

- `/repo`: read-only current CC Pocket checkout.
- `/queue`: read-only candidate inbox written by the public support agent.
- `/governance`: writable routine-review inputs and verdict records. Promotion
  reviews and proposals belong to the separate strong-model workflow.
- Governance helper: `/repo/scripts/support-kb.py`

No network or host execution is permitted.
