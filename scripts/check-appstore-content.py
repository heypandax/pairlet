#!/usr/bin/env python3
"""Validate App Store metadata and deterministic screenshot deliverables."""

from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
METADATA = ROOT / "fastlane" / "metadata"
SCREENSHOTS = ROOT / "fastlane" / "screenshots"
PREVIEWS = ROOT / "fastlane" / "previews"
LOCALES = ("en-US", "zh-Hans")
LIMITS = {
    "description.txt": 4000,
    "keywords.txt": 100,
    "promotional_text.txt": 170,
    "release_notes.txt": 4000,
}
FORBIDDEN = (
    "Session Handoff",
    "Folder Share",
    "Stronger version if you decide",
    "若上架时主打",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:24]
    if len(data) != 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        fail(f"not a valid PNG: {path}")
    return struct.unpack(">II", data[16:24])


def main() -> None:
    public_text: list[str] = []
    for locale in LOCALES:
        directory = METADATA / locale
        for filename, limit in LIMITS.items():
            path = directory / filename
            if not path.is_file():
                fail(f"missing metadata: {path}")
            value = path.read_text(encoding="utf-8").strip()
            if not value:
                fail(f"empty metadata: {path}")
            if len(value) > limit:
                fail(f"{path} is {len(value)} characters; limit is {limit}")
            public_text.append(value)

        shots = sorted((SCREENSHOTS / locale).glob("*.png"))
        if len(shots) != 6:
            fail(f"{locale} needs exactly 6 screenshots; found {len(shots)}")
        expected = [f"{index:02d}-" for index in range(1, 7)]
        if any(not shot.name.startswith(prefix) for shot, prefix in zip(shots, expected)):
            fail(f"{locale} screenshots must use stable 01- through 06- ordering")
        for shot in shots:
            if png_dimensions(shot) != (1242, 2688):
                fail(f"{shot} must be 1242x2688 for APP_IPHONE_65; got {png_dimensions(shot)}")

        preview = PREVIEWS / locale / "app-preview.mov"
        if not preview.is_file() or preview.stat().st_size < 1_000_000:
            fail(f"missing or implausibly small App Preview: {preview}")
        if preview.read_bytes()[4:8] != b"ftyp":
            fail(f"not a QuickTime/MP4 container: {preview}")

    joined = "\n".join(public_text)
    for phrase in FORBIDDEN:
        if phrase.casefold() in joined.casefold():
            fail(f"public metadata must not promote or expose draft text: {phrase}")

    print("App Store content OK: 2 locales, 8 metadata fields, 12 screenshots, 2 previews")


if __name__ == "__main__":
    main()
