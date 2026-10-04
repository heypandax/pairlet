#!/usr/bin/env python3
"""Check the Pairlet candidate's explicit compatibility contract, optionally its macOS image.

This is a source/package gate, not proof of a device upgrade or search behavior.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mac-app", type=Path)
    args = parser.parse_args()
    contract = json.loads((ROOT / "packaging/brand-compatibility.json").read_text())
    for entry in contract["frozenFiles"]:
        path = ROOT / entry["path"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"], f"Frozen compatibility file changed: {path}"
    gradle = (ROOT / "mobile/composeApp/build.gradle.kts").read_text() + (ROOT / "mobile/androidApp/build.gradle.kts").read_text()
    for value in ['applicationId = "com.panda.ccpocket"', 'bundleID = "dev.ccpocket.app"',
                  '?: "CC Pocket"', 'packageName = desktopPackageName', f'upgradeUuid = "{contract["windowsUpgradeCode"]}"']:
        assert value in gradle, f"Packaging identity missing: {value}"
    ios = plistlib.loads((ROOT / "iosApp/iosApp/Info.plist").read_bytes())
    assert ios["CFBundleDisplayName"] == "Pairlet"
    for locale in ("en-US", "zh-Hans"):
        store_name = (ROOT / f"fastlane/metadata/{locale}/name.txt").read_text().strip()
        # en-US cannot be the bare name: App Store Connect reports "Pairlet" as used on another account
        # (2026-10-04). A store name may therefore carry a ": <descriptor>" suffix, never a different lead.
        device_name = ios["CFBundleDisplayName"]
        assert store_name == device_name or store_name.startswith(device_name + ": "), \
            f"Store name must be the device name, optionally followed by ': <descriptor>': {locale}"
    assert ios["CFBundleURLTypes"][0]["CFBundleURLSchemes"] == ["ccpocket", "pairlet"], "Keep the legacy scheme first; pairlet is additive"
    project = (ROOT / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()
    app_ids = set()
    for settings in re.findall(r'buildSettings = \{(.*?)\};', project, re.S):
        identifier = re.search(r'PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);', settings)
        if identifier is None:
            continue
        if "TEST_TARGET_NAME = iosApp;" in settings:
            assert identifier.group(1) == "com.panda.ccpocket.DiagnosticTests"
        else:
            app_ids.add(identifier.group(1))
    assert app_ids == {"com.panda.ccpocket"}, "Production iOS bundle identity changed"
    android = ET.parse(ROOT / "mobile/androidApp/src/main/AndroidManifest.xml").getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    assert android.find("application").get(ns + "label") == "Pairlet"
    assert any(item.get(ns + "scheme") == "ccpocket" for item in android.iter("data"))
    harmony = json.loads((ROOT / "harmony/AppScope/app.json5").read_text())
    assert harmony["app"]["bundleName"] == "com.ccpocket.app"
    for locale in ("values", "values-zh"):
        entries = ET.parse(ROOT / f"mobile/composeApp/src/commonMain/composeResources/{locale}/strings.xml").getroot()
        strings = {item.get("name"): item.text for item in entries if item.tag == "string"}
        assert "Pairlet" in strings["tray_open_app"]
        assert "pairlet pair" in strings["fr_step_pair_body"]
        assert "ccpocket://" in strings["paste_pair_link"]
    if args.mac_app:
        app = args.mac_app.resolve()
        # Two published images: the legacy one every existing install updates in place, and the Pairlet-named
        # one for new installs. Same bundle id; each keeps its own on-disk and launcher name.
        assert app.name in ("CC Pocket.app", "Pairlet.app"), "Keep the manual-install and self-update destination stable"
        base = app.stem
        info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
        expected = {"CFBundleIdentifier": "dev.ccpocket.app", "CFBundleExecutable": base,
                    "CFBundleDisplayName": base, "CFBundleName": base}
        for key, value in expected.items():
            assert info.get(key) == value, (key, info.get(key), value)
        for locale in ("en", "zh-Hans"):
            localized = (app / f"Contents/Resources/{locale}.lproj/InfoPlist.strings").read_text()
            assert '\"CFBundleName\" = \"Pairlet\";' in localized
            assert '\"CFBundleDisplayName\" = \"Pairlet\";' in localized
        assert (app / f"Contents/MacOS/{base}").is_file()
        config = (app / f"Contents/app/{base}.cfg").read_text()
        assert "Pairlet" in config, "Packaged JVM must receive the device name"
    print(f"Pairlet compatibility OK: {len(contract['frozenFiles'])} frozen files, platform identities, labels and legacy commands/links" +
          (", macOS packaged image" if args.mac_app else ""))


if __name__ == "__main__":
    main()
