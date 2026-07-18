"""chat -> project routing for the feishu bridge (issue #91 follow-up).

The PoC hard-coded ONE workdir per process, with a comment saying "for a real deployment, route by
chat_id". That deployment step was the wall: a chat_id (oc_xxx) is not something an operator can look
up — it only exists inside the event payload. So the mapping is not configured ahead of time at all;
it is established IN the chat, by the owner, with /bind. This file is just the table + its disk form.

The table can only ever point at a workdir the CREDENTIAL already allow-lists (the daemon re-checks
every session.open against that same list — see BridgeGuard.BAD_WORKDIR — so a bad row here is denied
server-side, not merely by our own good behaviour). Projects are named by basename so nobody in a chat
has to type, or even see, an absolute path.
"""
from __future__ import annotations

import json
import os
import tempfile
from typing import Optional


class Routes:
    """chat_id -> absolute workdir, persisted as flat JSON.

    Written atomically at 0600: it is not secret (paths, not keys), but it decides where prompts from
    a chat land, so a half-written or world-writable table is a correctness/authz problem.
    """

    def __init__(self, path: str):
        self._path = path
        self._map: dict[str, str] = {}
        if os.path.exists(path):
            try:
                with open(path) as f:
                    raw = json.load(f)
                # tolerate hand-editing: keep only string->string rows
                self._map = {k: v for k, v in raw.items() if isinstance(k, str) and isinstance(v, str)}
            except (OSError, ValueError) as e:
                # a corrupt table must not silently become an empty one — that would look like
                # "all my chats got unbound" with no explanation.
                raise SystemExit(f"routes file {path} is unreadable ({e}); fix or delete it")

    def __len__(self) -> int:
        return len(self._map)

    def workdir_for(self, chat_id: str) -> Optional[str]:
        return self._map.get(chat_id)

    def bind(self, chat_id: str, workdir: str) -> None:
        self._map[chat_id] = workdir
        self._flush()

    def unbind(self, chat_id: str) -> bool:
        if chat_id not in self._map:
            return False
        del self._map[chat_id]
        self._flush()
        return True

    def chats_for(self, workdir: str) -> list[str]:
        return [c for c, w in self._map.items() if w == workdir]

    def _flush(self) -> None:
        d = os.path.dirname(os.path.abspath(self._path)) or "."
        fd, tmp = tempfile.mkstemp(dir=d, prefix=".routes-", suffix=".tmp")
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w") as f:
                json.dump(self._map, f, indent=2, sort_keys=True)
            os.replace(tmp, self._path)  # atomic: a crash mid-write leaves the old table intact
        except BaseException:
            os.unlink(tmp)
            raise


def project_name(workdir: str) -> str:
    """The chat-facing name of a workdir: its basename. /bind uses this, never the full path."""
    return os.path.basename(os.path.normpath(workdir))


def resolve_project(name: str, workdirs: list[str]) -> Optional[str]:
    """Map a /bind argument back to an allow-listed absolute workdir. Exact basename match, then a
    unique case-insensitive one — an ambiguous prefix must fail loudly rather than pick for the user."""
    name = name.strip()
    if not name:
        return None
    for w in workdirs:
        if project_name(w) == name:
            return w
    lowered = [w for w in workdirs if project_name(w).lower() == name.lower()]
    return lowered[0] if len(lowered) == 1 else None
