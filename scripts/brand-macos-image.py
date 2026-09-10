#!/usr/bin/env python3
"""Finalize device names in a private jpackage image, then re-seal the bundle before DMG creation."""
import argparse
from pathlib import Path
import plistlib
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True, type=Path)
    parser.add_argument("--identity", default="-")
    args = parser.parse_args()
    app = args.app.resolve()
    # Refuse a live installation: this hook belongs only to this checkout's build output.
    build = Path(__file__).resolve().parents[1] / "mobile/composeApp/build/compose/binaries"
    if not app.is_relative_to(build.resolve()) or app.name != "CC Pocket.app":
        raise ValueError("Expected this checkout's private CC Pocket.app build image")
    path = app / "Contents/Info.plist"
    info = plistlib.loads(path.read_bytes())
    assert info["CFBundleIdentifier"] == "dev.ccpocket.app"
    assert info["CFBundleExecutable"] == "CC Pocket"
    # Finder localizes a bundle only when the unlocalized name matches its file-system name.
    # Keep both base names old and supply the new names in InfoPlist.strings (Apple's bundle contract).
    info["CFBundleName"] = "CC Pocket"
    info["CFBundleDisplayName"] = "CC Pocket"
    info["CFBundleDevelopmentRegion"] = "en"
    info["LSHasLocalizedDisplayName"] = True
    path.write_bytes(plistlib.dumps(info, sort_keys=False))
    for locale in ("en", "zh-Hans"):
        localized = app / f"Contents/Resources/{locale}.lproj/InfoPlist.strings"
        localized.parent.mkdir(parents=True, exist_ok=True)
        localized.write_text('"CFBundleName" = "CC Pairlet";\n"CFBundleDisplayName" = "CC Pairlet";\n')
    # jpackage/Compose already sign the nested runtime and native libraries. Only the outer bundle's
    # plist changes, so re-seal that bundle using the SAME configured identity and preserved metadata.
    # Signed builds remain timestamped; local images remain ad-hoc. Never distribute before this succeeds.
    command = ["codesign", "--force", "--sign", args.identity,
               "--preserve-metadata=identifier,entitlements,requirements,flags,runtime"]
    if args.identity != "-":
        command.append("--timestamp")
    subprocess.run(command + [str(app)], check=True)
    subprocess.run(["codesign", "--verify", "--strict", str(app)], check=True)
    print("CC Pairlet bundle metadata finalized and signature verified; legacy path/launcher retained")


if __name__ == "__main__":
    main()
