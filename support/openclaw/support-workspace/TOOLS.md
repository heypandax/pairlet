# Runtime paths

- `/repo`: read-only checkout of the public `heypandax/cc-pocket` repository.
- `/queue`: dedicated writable support knowledge queue. It contains no user
  secrets and must never be used for arbitrary files.
- Search helper: `/repo/scripts/support-kb.py`

All shell work runs inside the agent sandbox. Network access is disabled.
