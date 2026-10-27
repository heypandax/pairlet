#!/usr/bin/env python3
"""Keep the published cask valid while a newer candidate is being built.

check: validate the explicitly recorded cask version/hashes, allowing it to lag
       the candidate. No network or changes.
prepare: derive both hashes from final artifacts AND SHA256SUMS; preview by
         default, write cask + record only with --write.
verify: require cask, record, target version and final artifact bytes to agree.
This does not publish to the tap or verify Apple notarization/signatures.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
SHA = r"[0-9a-f]{64}"
ARCHES = {"arm64": "arm", "x86_64": "intel"}
CASK_URL = (
    "https://github.com/heypandax/cc-pocket/releases/download/v#{version}/"
    "cc-pocket-daemon-#{version}-macos-#{arch}.tar.gz"
)


def version_tuple(value):
    if not isinstance(value, str) or not re.fullmatch(VERSION, value):
        raise ValueError(f"invalid release version: {value!r}")
    return tuple(map(int, value.split(".")))


def one(pattern, text, label):
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    if len(matches) != 1:
        raise ValueError(f"expected exactly one {label}")
    return matches[0].group(1)


def cask_fields(text):
    version = one(r'^\s*version "([^"]+)"\s*$', text, "cask version")
    version_tuple(version)
    hashes = {
        "arm64": one(r'^\s*sha256 arm:\s*"(' + SHA + r')",\s*$', text, "arm64 hash"),
        "x86_64": one(r'^\s*intel:\s*"(' + SHA + r')"\s*$', text, "x86_64 hash"),
    }
    if one(r'^\s*url "([^"]+)"\s*$', text, "cask URL") != CASK_URL:
        raise ValueError("unexpected cask URL; review the release asset mapping")
    if not re.search(r'^\s*arch arm: "arm64", intel: "x86_64"\s*$', text, re.MULTILINE):
        raise ValueError("unexpected cask architecture mapping")
    return {"version": version, "sha256": hashes}


def read_state(root):
    directory = root / "packaging/homebrew"
    cask = directory / "Casks/cc-pocket.rb"
    record = directory / "release.json"
    text = cask.read_text()
    expected = json.loads(record.read_text())
    actual = cask_fields(text)
    if actual != expected:
        raise ValueError("cask differs from packaging/homebrew/release.json")
    return cask, record, text, actual


def check(root, candidate):
    candidate_tuple = version_tuple(candidate)
    *_, current = read_state(root)
    if version_tuple(current["version"]) > candidate_tuple:
        raise ValueError("Homebrew release is newer than the candidate source")
    return current


def artifact_record(version, assets):
    version_tuple(version)
    sums = {}
    for line in (assets / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"(" + SHA + r") [ *](.+)", line)
        if not match:
            raise ValueError("invalid SHA256SUMS line")
        digest, name = match.groups()
        if name in sums:
            raise ValueError(f"duplicate checksum entry: {name}")
        sums[name] = digest
    hashes = {}
    for arch in ARCHES:
        name = f"cc-pocket-daemon-{version}-macos-{arch}.tar.gz"
        path = assets / name
        if name not in sums:
            raise ValueError(f"missing checksum: {name}")
        hasher = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(chunk)
        hashes[arch] = hasher.hexdigest()
        if hashes[arch] != sums[name]:
            raise ValueError(f"artifact checksum mismatch: {name}")
    return {"version": version, "sha256": hashes}


def prepare(root, version, assets, write=False):
    current = check(root, version)
    cask, record, text, _ = read_state(root)
    target = artifact_record(version, assets)
    text = re.sub(r'(^\s*version ")[^"]+(")', lambda m: m[1] + version + m[2],
                  text, count=1, flags=re.MULTILINE)
    for arch, key in ARCHES.items():
        text = re.sub(r'(\b' + key + r':\s*")' + SHA + r'(")',
                      lambda m: m[1] + target["sha256"][arch] + m[2], text, count=1)
    if cask_fields(text) != target:
        raise ValueError("generated cask does not match final artifacts")
    if version == current["version"] and target != current:
        raise ValueError("refusing changed hashes for an already recorded version; release a new version")
    if write:
        # If interrupted between these writes the preflight fails closed.
        record.write_text(json.dumps(target, indent=2) + "\n")
        cask.write_text(text)
        verify(root, version, assets)
    return target


def verify(root, version, assets):
    current = check(root, version)
    if current != artifact_record(version, assets):
        raise ValueError("cask must match the target version and both final artifacts")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check", "prepare", "verify"])
    parser.add_argument("version")
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if args.command != "check" and args.assets is None:
        parser.error("--assets is required")
    if args.command == "check" and args.assets is not None:
        parser.error("check does not read artifacts; use verify")
    if args.write and args.command != "prepare":
        parser.error("--write is only valid with prepare")
    try:
        if args.command == "check":
            current = check(ROOT, args.version)
            print(f"Homebrew recorded release OK: {current['version']} (candidate {args.version})")
        elif args.command == "prepare":
            result = prepare(ROOT, args.version, args.assets, args.write)
            print(("Updated" if args.write else "Preview; files unchanged") + ": " +
                  json.dumps(result))
        else:
            verify(ROOT, args.version, args.assets)
            print(f"Homebrew final artifacts OK: {args.version}")
    except (ValueError, OSError, KeyError) as error:
        print(f"Homebrew release check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
