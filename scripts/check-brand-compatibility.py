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
    gradle = (ROOT / "mobile/composeApp/build.gradle.kts").read_text()
    for value in ['applicationId = "com.panda.ccpocket"', 'bundleID = "dev.ccpocket.app"',
                  'packageName = "CC Pocket"', f'upgradeUuid = "{contract["windowsUpgradeCode"]}"']:
        assert value in gradle, f"Packaging identity missing: {value}"
    ios = plistlib.loads((ROOT / "iosApp/iosApp/Info.plist").read_bytes())
    assert ios["CFBundleDisplayName"] == "CC Pairlet"
    assert ios["CFBundleURLTypes"][0]["CFBundleURLSchemes"] == ["ccpocket"]
    project = (ROOT / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()
    assert set(re.findall(r'PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);', project)) == {"com.panda.ccpocket"}
    android = ET.parse(ROOT / "mobile/composeApp/src/androidMain/AndroidManifest.xml").getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    assert android.find("application").get(ns + "label") == "CC Pairlet"
    assert any(item.get(ns + "scheme") == "ccpocket" for item in android.iter("data"))
    harmony = json.loads((ROOT / "harmony/AppScope/app.json5").read_text())
    assert harmony["app"]["bundleName"] == "com.ccpocket.app"
    for locale in ("values", "values-zh"):
        entries = ET.parse(ROOT / f"mobile/composeApp/src/commonMain/composeResources/{locale}/strings.xml").getroot()
        strings = {item.get("name"): item.text for item in entries if item.tag == "string"}
        assert "CC Pairlet" in strings["tray_open_app"]
        assert "cc-pocket-daemon pair" in strings["fr_step_pair_body"]
        assert "ccpocket://" in strings["paste_pair_link"]
    if args.mac_app:
        app = args.mac_app.resolve()
        assert app.name == "CC Pocket.app", "Keep the manual-install and self-update destination stable"
        info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
        expected = {"CFBundleIdentifier": "dev.ccpocket.app", "CFBundleExecutable": "CC Pocket",
                    "CFBundleDisplayName": "CC Pocket", "CFBundleName": "CC Pocket"}
        for key, value in expected.items():
            assert info.get(key) == value, (key, info.get(key), value)
        for locale in ("en", "zh-Hans"):
            localized = (app / f"Contents/Resources/{locale}.lproj/InfoPlist.strings").read_text()
            assert '\"CFBundleName\" = \"CC Pairlet\";' in localized
            assert '\"CFBundleDisplayName\" = \"CC Pairlet\";' in localized
        assert (app / "Contents/MacOS/CC Pocket").is_file()
        config = (app / "Contents/app/CC Pocket.cfg").read_text()
        assert "CC Pairlet" in config, "Packaged JVM must receive the device name"
    print(f"Pairlet compatibility OK: {len(contract['frozenFiles'])} frozen files, platform identities, labels and legacy commands/links" +
          (", macOS packaged image" if args.mac_app else ""))


if __name__ == "__main__":
    main()
