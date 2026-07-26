# Runtime paths

- `/repo`: read-only checkout of the public `heypandax/cc-pocket` repository.
- Search helper: `/repo/scripts/support-kb.py`

All shell work runs inside a per-session sandbox with a read-only root, bounded
CPU/memory/PIDs, and no network. There is no writable host mount and no shared
knowledge queue.
