#!/usr/bin/env python3
"""Check the Git index for local artifacts; never read credential contents.

Run after staging changes. CI checks the committed tree via the checkout index.
Only repository .gitignore files apply, not a developer's global excludes.
"""

from __future__ import annotations

from pathlib import PurePosixPath
import subprocess
import sys


MAX_BYTES = 10 * 1024 * 1024
# These are inputs to ios-store-metadata.yml, validated by check-appstore-content.py.
RELEASE_MEDIA = {
    "fastlane/previews/en-US/app-preview.mov",
    "fastlane/previews/zh-Hans/app-preview.mov",
}
LOCAL_PREFIXES = (
    "_local/", ".agents/", ".claude/", ".codex/", ".opencode/",
    "docs/plans/", "docs/design/app-design-runs/", "docs/design/image2-concepts/",
    "marketing/video/",
)
HANDOFF = "docs/design/claude-design-handoff/"


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])


def path_problem(path: str) -> str | None:
    if path.startswith(LOCAL_PREFIXES):
        return "local work product"
    if path.startswith(HANDOFF):
        relative = path[len(HANDOFF):]
        directory = relative.split("/", 1)[0]
        if (PurePosixPath(path).suffix != ".md"
                or directory in {"chats", "project", "video-stage", "xhs-off-lan"}
                or directory.startswith("project-refresh-")):
            return "raw design export or conversation; retain reviewed Markdown only"
    return None


def main() -> int:
    records = []
    problems = []
    for record in git("ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        meta, raw_path = record.split(b"\t", 1)
        mode, oid, stage = meta.decode().split()
        path = raw_path.decode("utf-8", "surrogateescape")
        if stage != "0":
            problems.append((path, "unmerged index entry"))
        elif mode != "160000":  # Submodule commit IDs need not exist in this repository.
            records.append((path, oid))

    ignored = {
        p.decode("utf-8", "surrogateescape")
        for p in git("ls-files", "-ci", "--exclude-per-directory=.gitignore", "-z").split(b"\0") if p
    }
    objects = sorted({oid for _, oid in records})
    result = subprocess.check_output(
        ["git", "cat-file", "--batch-check=%(objectname) %(objectsize)"],
        input=("\n".join(objects) + "\n").encode() if objects else b"",
    )
    sizes = {oid: int(size) for oid, size in (line.split() for line in result.decode().splitlines())}
    for path, oid in records:
        reason = path_problem(path)
        if reason:
            problems.append((path, reason))
        elif path in ignored:
            problems.append((path, "tracked despite repository .gitignore"))
        if sizes[oid] > MAX_BYTES and path not in RELEASE_MEDIA:
            problems.append((path, "over 10 MiB; archive outputs outside Git or document a required input"))

    if problems:
        for path, reason in sorted(set(problems)):
            print(f"FAIL {path}: {reason}", file=sys.stderr)
        print("Back up local files before untracking them. Do not bypass this check with git add -f.", file=sys.stderr)
        return 1
    print(f"Repository content OK: {len(records)} indexed files; no tracked local artifacts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
