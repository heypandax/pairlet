"""Idempotent App Store Connect beta-group/build relationship handling."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any


class BuildGroupAccessError(RuntimeError):
    """The build could not be made available to the requested beta group."""


def _relationship_contains(body: dict[str, Any], resource_id: str) -> bool:
    return any(item.get("id") == resource_id for item in body.get("data", []))


def ensure_build_group_access(
    call: Callable[[str, str, Any | None], tuple[int, dict[str, Any]]],
    group: dict[str, Any],
    build_id: str,
    render_errors: Callable[[dict[str, Any]], str],
    *,
    attempts: int = 6,
    retry_delay_seconds: float = 30,
    sleep: Callable[[float], None] = time.sleep,
) -> str:
    """Ensure a processed build is reachable through a beta group.

    App Store Connect can expose a newly processed build through ``GET /builds`` before
    the beta-group relationship endpoints accept it. In addition, an existing public
    group may already cover every build. Treat those states explicitly, verify existing
    membership before mutating it, and retry the observed transient 404 through both
    documented relationship directions.
    """

    if attempts < 1:
        raise ValueError("attempts must be at least one")

    group_id = group["id"]
    attrs = group.get("attributes") or {}
    if attrs.get("hasAccessToAllBuilds") is True:
        return "all-build group already grants access"

    group_relationship = f"/v1/betaGroups/{group_id}/relationships/builds"
    build_relationship = f"/v1/builds/{build_id}/relationships/betaGroups"
    group_payload = {"data": [{"type": "builds", "id": build_id}]}
    build_payload = {"data": [{"type": "betaGroups", "id": group_id}]}
    last_failures: list[str] = []

    for attempt in range(1, attempts + 1):
        status, body = call("GET", group_relationship)
        if status == 200 and _relationship_contains(body, build_id):
            return "already attached"

        status, body = call("POST", group_relationship, group_payload)
        if status in (200, 204):
            return "attached"
        detail = render_errors(body)
        if "already" in detail.lower():
            return "already attached"
        last_failures = [f"beta-group endpoint {status}: {detail}"]

        # Apple documents the inverse relationship endpoint as equivalent. The v1.7.6
        # release observed a transient 404 from the group-side endpoint even though the
        # same build was already readable and localizable, so try the build-side link too.
        if status == 404:
            inverse_status, inverse_body = call("POST", build_relationship, build_payload)
            if inverse_status in (200, 204):
                return "attached through build relationship"
            inverse_detail = render_errors(inverse_body)
            if "already" in inverse_detail.lower():
                return "already attached"
            last_failures.append(f"build endpoint {inverse_status}: {inverse_detail}")
            if inverse_status != 404:
                break
        else:
            break

        if attempt < attempts:
            sleep(retry_delay_seconds)

    raise BuildGroupAccessError(
        f"attaching the build failed on attempt {attempt}: " + "; ".join(last_failures)
    )
