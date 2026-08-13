#!/usr/bin/env python3
"""Validate the checked-in Firebase configs used by local and CI builds."""

from __future__ import annotations

import json
import plistlib
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IOS_TEMPLATE = ROOT / "iosApp/iosApp/GoogleService-Info.plist.template"
ANDROID_TEMPLATE = ROOT / "mobile/composeApp/google-services.json.template"
API_KEY_PATTERN = re.compile(r"AIza[A-Za-z0-9_-]{35}")


def main() -> None:
    with IOS_TEMPLATE.open("rb") as source:
        ios = plistlib.load(source)
    with ANDROID_TEMPLATE.open(encoding="utf-8") as source:
        android = json.load(source)

    android_client = android["client"][0]
    ios_key = ios["API_KEY"]
    android_key = android_client["api_key"][0]["current_key"]

    for label, key in (("iOS", ios_key), ("Android", android_key)):
        if API_KEY_PATTERN.fullmatch(key) is None:
            raise SystemExit(
                f"{label} placeholder API key must be a Firebase-compatible "
                "39-character key beginning with AIza"
            )

    if ios_key != android_key:
        raise SystemExit("iOS and Android placeholder API keys must stay in sync")

    package_name = android_client["client_info"]["android_client_info"]["package_name"]
    if ios["BUNDLE_ID"] != package_name:
        raise SystemExit("iOS bundle ID and Android package name must stay in sync")

    print("Firebase placeholder configs are structurally valid")


if __name__ == "__main__":
    main()
