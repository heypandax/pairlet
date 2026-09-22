#!/usr/bin/env python3
"""Fail before upload if the signed iOS app/profile cannot register for APNs.

Inspect the app extracted from the IPA as well as the archive: export re-signs
the app, so an archive-only check cannot validate the distribution entitlement.
Only report fixed signing fields; never dump profiles (which contain device IDs).
"""

import argparse
import plistlib
import subprocess
import sys
from pathlib import Path


def validate_entitlements(info, signed, profile, environment, bundle_id, team_id):
    """Compare actual signed claims, not just what the profile permits."""
    if info.get("CFBundleIdentifier") != bundle_id:
        raise ValueError("app bundle identifier does not match the expected app")
    profile_entitlements = profile.get("Entitlements", {})
    for label, entitlements in (("app signature", signed), ("provisioning profile", profile_entitlements)):
        actual = entitlements.get("aps-environment")
        if actual != environment:
            raise ValueError(f"{label}: aps-environment must be {environment}; got {actual!r}")
        if entitlements.get("application-identifier") != f"{team_id}.{bundle_id}":
            raise ValueError(f"{label}: application-identifier does not match the expected team/app")
        if entitlements.get("com.apple.developer.team-identifier") != team_id:
            raise ValueError(f"{label}: team identifier does not match the expected team")
        if environment == "production" and entitlements.get("get-task-allow") is not False:
            raise ValueError(f"{label}: distribution signing must set get-task-allow=false")


def inspect_app(app, environment, bundle_id, team_id):
    with (app / "Info.plist").open("rb") as source:
        info = plistlib.load(source)
    subprocess.run(["codesign", "--verify", "--strict", str(app)], check=True, capture_output=True)
    signed = plistlib.loads(subprocess.run(
        ["codesign", "-d", "--entitlements", ":-", str(app)],
        check=True, capture_output=True,
    ).stdout)
    profile = plistlib.loads(subprocess.run(
        ["security", "cms", "-D", "-i", str(app / "embedded.mobileprovision")],
        check=True, capture_output=True,
    ).stdout)
    validate_entitlements(info, signed, profile, environment, bundle_id, team_id)
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="signed .app from an archive or extracted IPA")
    parser.add_argument("--environment", choices=("development", "production"), required=True)
    parser.add_argument("--bundle-id", default="com.panda.ccpocket")
    parser.add_argument("--team-id", default="SC9S2SJ42G")
    args = parser.parse_args()
    try:
        info = inspect_app(args.app, args.environment, args.bundle_id, args.team_id)
    except subprocess.CalledProcessError as error:
        print(f"APNs signing check FAILED: {error.cmd[0]} could not verify/read the signed app", file=sys.stderr)
        return 1
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        print(f"APNs signing check FAILED: {error}", file=sys.stderr)
        return 1
    print(f"APNs signing verified: {args.bundle_id} "
          f"{info.get('CFBundleShortVersionString')} ({info.get('CFBundleVersion')}), "
          f"aps-environment={args.environment} in signature and profile")
    return 0


if __name__ == "__main__":
    sys.exit(main())
