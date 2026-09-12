#!/usr/bin/env python3
"""Convert the selected Pairlet raster to platform containers; preserve no-text miniature icons.

Run on macOS (sips/iconutil). This performs size/format conversion, not artwork generation.
"""
from pathlib import Path
import re
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/brand/pairlet-pocket-readable-v2.png"


def resize(size, destination):
    subprocess.run(["sips", "-z", str(size), str(size), str(SOURCE), "--out", str(destination)],
                   check=True, stdout=subprocess.DEVNULL)


def main():
    targets = {
        "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png": 1024,
        "mobile/composeApp/src/desktopMain/resources/app-icon.png": 256,
        "harmony/AppScope/resources/base/media/app_icon.png": 1024,
        "harmony/entry/src/main/resources/base/media/icon.png": 1024,
        "mobile/androidApp/src/main/res/drawable/ic_launcher_fg.png": 1024,
        "site/apple-touch-icon.png": 180,
    }
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        targets[f"mobile/androidApp/src/main/res/mipmap-{density}/ic_launcher.png"] = size
    for path, size in targets.items():
        resize(size, ROOT / path)
    with tempfile.TemporaryDirectory(prefix="pairlet-icon-") as directory:
        work = Path(directory)
        icns = ROOT / "mobile/composeApp/desktop-icons/cc-pocket.icns"
        iconset = work / "Pairlet.iconset"
        subprocess.run(["iconutil", "-c", "iconset", "-o", str(iconset), str(icns)], check=True)
        for path in iconset.glob("*.png"):
            match = re.fullmatch(r"icon_(\d+)x\d+(@2x)?\.png", path.name)
            if match and int(match[1]) >= 64:
                resize(int(match[1]) * (2 if match[2] else 1), path)
        subprocess.run(["iconutil", "-c", "icns", "-o", str(icns), str(iconset)], check=True)

        ico = ROOT / "mobile/composeApp/desktop-icons/cc-pocket.ico"
        old = ico.read_bytes()
        reserved, kind, count = struct.unpack_from("<HHH", old)
        assert (reserved, kind) == (0, 1)
        entries = []
        for index in range(count):
            header = list(struct.unpack_from("<BBBBHHII", old, 6 + 16 * index))
            if (header[0] or 256) < 64:
                entries.append((header, old[header[7]:header[7] + header[6]]))
        assert entries, "Expected existing no-text ICO representations"
        large = work / "256.png"
        resize(256, large)
        png = large.read_bytes()
        entries.append(([0, 0, 0, 0, 1, 32, len(png), 0], png))
        offset = 6 + 16 * len(entries)
        headers, payloads = [], []
        for header, payload in entries:
            header[7] = offset
            headers.append(struct.pack("<BBBBHHII", *header))
            payloads.append(payload)
            offset += len(payload)
        ico.write_bytes(struct.pack("<HHH", 0, 1, len(entries)) + b"".join(headers + payloads))
    print(f"Pairlet platform icon resources generated from {SOURCE.name}; miniatures retained")


if __name__ == "__main__":
    main()
