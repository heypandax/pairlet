#!/usr/bin/env python3
"""Record the exact source and checksums beside temporary Actions preview packages."""
import hashlib
import json
import os
from pathlib import Path
import sys


def main():
    output = Path(sys.argv[1])
    packages = sorted(p for p in output.iterdir() if p.is_file() and
                      p.name not in {"manifest.json", "SHA256SUMS", "PREVIEW.txt"})
    if not packages:
        raise SystemExit("No preview packages found")
    checksums = {}
    for package in packages:
        digest = hashlib.sha256()
        with package.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        checksums[package.name] = digest.hexdigest()
    manifest = {
        "kind": "unsigned-release-preview",
        "version": os.environ["PREVIEW_VERSION"],
        "commit": os.environ["GITHUB_SHA"],
        "run": f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
        "attempt": os.environ["GITHUB_RUN_ATTEMPT"],
        "sha256": checksums,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text("".join(f"{digest}  {name}\n" for name, digest in checksums.items()), encoding="utf-8")
    (output / "PREVIEW.txt").write_text(
        "Pairlet build rehearsal only. Not a published release.\n"
        "No distribution signing or notarization; mobile Firebase config is a placeholder.\n"
        "Unsigned Android APKs and iOS archives are build evidence, not installable store builds.\n"
        "Do not distribute through stable update channels. Artifacts expire after 14 days.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
